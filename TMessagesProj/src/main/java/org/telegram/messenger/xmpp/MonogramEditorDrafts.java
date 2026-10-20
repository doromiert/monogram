// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.graphics.Bitmap;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

/** Unapplied native editor checkpoints, separate from PhotoEntry's applied media fields. */
public final class MonogramEditorDrafts {
    private MonogramEditorDrafts() {}
    private static final ExecutorService WRITER=Executors.newSingleThreadExecutor();
    private static final Map<MediaController.PhotoEntry,Map<String,Pending>> states=new WeakHashMap<>();
    private static final Map<String,Token> tokens=new HashMap<>();
    private static final Map<MediaController.PhotoEntry,String> ids=new WeakHashMap<>();
    private static final Map<MediaController.PhotoEntry,Boolean> previewOnly=new WeakHashMap<>();
    public static boolean previewOnly(MediaController.PhotoEntry photo) { synchronized(states) { return Boolean.TRUE.equals(previewOnly.get(photo)); } }
    static void previewOnly(MediaController.PhotoEntry photo,boolean value) { synchronized(states) { if(value) previewOnly.put(photo,true);else previewOnly.remove(photo); } }
    private static final Map<String,PaintJob> waiting=new LinkedHashMap<>();
    private static boolean draining;
    private static volatile PaintJob compressing;
    private static final class PaintJob {
        final Token token;final long version;final Bitmap bitmap;final String metadata;final MediaController.PhotoEntry photo;
        PaintJob(Token token,long version,Bitmap bitmap,String metadata) { this.token=token;this.version=version;this.bitmap=bitmap;this.metadata=metadata;this.photo=token.photo(); }
    }
    private static void drainPaint() {
        while(true) {
            PaintJob job;synchronized(waiting) { if(waiting.isEmpty()) { draining=false;return; }String key=waiting.keySet().iterator().next();job=waiting.remove(key); }
            compressing=job;try { writePaint(job); } finally { compressing=null; }
        }
    }
    static String identity(MediaController.PhotoEntry photo) { synchronized(states) { String id=ids.get(photo);if(id==null) { id=UUID.randomUUID().toString();ids.put(photo,id); }return id; } }
    static void identity(MediaController.PhotoEntry photo,String id) { synchronized(states) { ids.put(photo,id.isEmpty() ? UUID.randomUUID().toString() : id); } }
    private static String scope(MonogramMediaDrafts.Owner owner) { return owner.key+"/"+owner.epoch; }
    private static Pending pending(MediaController.PhotoEntry photo,MonogramMediaDrafts.Owner owner) { Map<String,Pending> saved=states.get(photo);return saved==null ? null : saved.get(scope(owner)); }
    private static void pending(MediaController.PhotoEntry photo,Pending saved) { states.computeIfAbsent(photo,key -> new HashMap<>()).put(scope(saved.owner),saved); }
    public static ArrayList<MediaController.PhotoEntry> photos(int account,long dialog) {
        MonogramMediaDrafts.Owner owner=MonogramMediaDrafts.Owner.current(account,dialog);ArrayList<MediaController.PhotoEntry> result=new ArrayList<>();if(owner==null) return result;
        synchronized(states) { for(Map.Entry<MediaController.PhotoEntry,Map<String,Pending>> entry:states.entrySet()) if(entry.getValue().containsKey(scope(owner))) result.add(entry.getKey()); }return result;
    }
    private static void forget(MediaController.PhotoEntry photo,MonogramMediaDrafts.Owner owner) {
        String id=identity(photo);for(Map.Entry<MediaController.PhotoEntry,Map<String,Pending>> entry:states.entrySet()) if(id.equals(identity(entry.getKey()))) entry.getValue().remove(scope(owner));
    }
    public static final class Pending {
        final MonogramMediaDrafts.Owner owner;final JSONObject data;
        Pending(MonogramMediaDrafts.Owner owner,JSONObject data) { this.owner=owner;this.data=data; }
        public String mode() { return data.optString("mode"); }
        public String paintPath() { return data.optString("paint",null); }
        public MediaController.CropState crop() { try { return readCrop(data.getString("crop")); } catch(Exception invalid) { return null; } }
        public ArrayList<VideoEditedInfo.MediaEntity> entities() { try { return MonogramMediaDrafts.readMediaEntities(data.optString("entities")); } catch(Exception invalid) { return null; } }
    }
    public static final class Token {
        final MonogramMediaDrafts.Owner owner;private final java.lang.ref.WeakReference<MediaController.PhotoEntry> selection;final String mode;final String id,key;private final AtomicLong revision=new AtomicLong();
        Token(MonogramMediaDrafts.Owner owner,MediaController.PhotoEntry photo,String mode) { this.owner=owner;this.selection=new java.lang.ref.WeakReference<>(photo);this.mode=mode;this.id=identity(photo);this.key=owner.key+"/"+owner.epoch+"/"+id; }
        MediaController.PhotoEntry photo() { return selection.get(); }
        public void changed() { revision.incrementAndGet(); }
        public long revision() { return revision.get(); }
        public boolean current(long version) { synchronized(states) { MediaController.PhotoEntry photo=photo();return photo!=null && tokens.get(key)==this && revision.get()==version && owner.current(); } }
    }
    public static Token begin(MediaController.PhotoEntry photo,int account,long dialog,String mode) {
        MonogramMediaDrafts.Owner owner=MonogramMediaDrafts.Owner.current(account,dialog);if(owner==null) return null;
        Token token=new Token(owner,photo,mode);if(!MonogramMediaDrafts.ensureEditorSelection(owner,photo)) return null;synchronized(states) { tokens.entrySet().removeIf(entry -> entry.getValue().photo()==null);tokens.put(token.key,token); }return token;
    }
    public static Pending get(MediaController.PhotoEntry photo,int account,long dialog) {
        MonogramMediaDrafts.Owner owner=MonogramMediaDrafts.Owner.current(account,dialog);if(owner==null) return null;
        synchronized(states) { Pending saved=pending(photo,owner);return saved!=null && saved.owner.key.equals(owner.key) && saved.owner.epoch.equals(owner.epoch) && saved.owner.current() ? saved : null; }
    }
    public static void crop(Token token,long version,MediaController.CropState crop) {
        if(token==null || !token.current(version)) return;
        try { JSONObject saved=new JSONObject().put("mode","crop").put("crop",crop(crop));synchronized(states) { if(!token.current(version)) return;pending(token.photo(),new Pending(token.owner,saved)); }MonogramMediaDrafts.persistEditor(token,version); }
        catch(Exception failed) { FileLog.e("Could not checkpoint native crop composition"); }
    }
    /** Owns/recycles the detached GL bitmap; compression never runs on the UI/render thread. */
    public static void paint(Token token,long version,Bitmap bitmap,ArrayList<VideoEditedInfo.MediaEntity> entities) {
        if(bitmap==null) return;
        final MediaController.PhotoEntry photo=token==null ? null : token.photo();
        final String metadata;
        try { metadata=MonogramMediaDrafts.mediaEntities(entities); } catch(Exception failed) { bitmap.recycle();return; }
        if(token==null || photo==null || !token.current(version)) { bitmap.recycle();return; }
        PaintJob job=new PaintJob(token,version,bitmap,metadata);
        synchronized(waiting) {
            PaintJob replaced=waiting.put(token.key,job);if(replaced!=null) replaced.bitmap.recycle();
            if(!draining) { draining=true;WRITER.execute(MonogramEditorDrafts::drainPaint); }
        }
    }
    private static void writePaint(PaintJob job) {
        Token token=job.token;long version=job.version;Bitmap bitmap=job.bitmap;String metadata=job.metadata;MediaController.PhotoEntry photo=job.photo;
        File temporary=null,destination=null;
        try {
            if(photo==null || !token.current(version)) return;
            File directory=new File(token.owner.directory(),"editor-assets");if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
            temporary=File.createTempFile("paint-",".tmp",directory);destination=new File(directory,UUID.randomUUID()+".png");
            try(FileOutputStream output=new FileOutputStream(temporary)) { if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) throw new IOException();output.getFD().sync(); }
            if(!token.current(version) || !temporary.renameTo(destination)) return;
            JSONObject saved=new JSONObject().put("mode","paint").put("paint",destination.getAbsolutePath()).put("entities",metadata);
            String previous;
            synchronized(states) { if(!token.current(version)) { destination.delete();return; }Pending old=pending(photo,token.owner);previous=old==null ? null : old.paintPath();pending(photo,new Pending(token.owner,saved)); }
            boolean committed=MonogramMediaDrafts.persistEditor(token,version);MonogramMediaDrafts.retireEditor(token.owner,previous,committed);
        } catch(Exception failed) { FileLog.e("Could not checkpoint native paint composition");MonogramMediaDrafts.editorError(token.owner,"Could not preserve the current painting. Keep the editor open and try again."); }
        finally { if(temporary!=null) temporary.delete();bitmap.recycle(); }
    }
    static boolean references(String path) throws Exception {
        synchronized(states) { for(Map<String,Pending> owner:states.values()) for(Pending saved:owner.values()) if(MonogramMediaDrafts.editorReferences(saved.data,path)) return true; }
        synchronized(waiting) { for(PaintJob job:waiting.values()) if(MonogramMediaDrafts.editorReferences(job.metadata,path)) return true; }
        PaintJob active=compressing;return active!=null && MonogramMediaDrafts.editorReferences(active.metadata,path);
    }
    public static void invalidate(Token token) { if(token!=null) synchronized(states) { if(tokens.get(token.key)==token) tokens.remove(token.key);token.changed(); } }
    public static void clear(MediaController.PhotoEntry photo,int account,long dialog) {
        MonogramMediaDrafts.Owner owner=MonogramMediaDrafts.Owner.current(account,dialog);synchronized(states) { Token token=owner==null ? null : tokens.remove(owner.key+"/"+owner.epoch+"/"+identity(photo));if(token!=null) token.changed();if(owner!=null) forget(photo,owner);previewOnly.remove(photo); }
        if(owner!=null) MonogramMediaDrafts.clearEditor(owner,photo);
    }
    static JSONObject encoded(MediaController.PhotoEntry photo,MonogramMediaDrafts.Owner owner) throws Exception {
        synchronized(states) { Pending saved=pending(photo,owner);return saved!=null && saved.owner.key.equals(owner.key) && saved.owner.epoch.equals(owner.epoch) ? new JSONObject(saved.data.toString()) : null; }
    }
    static void restore(MediaController.PhotoEntry photo,MonogramMediaDrafts.Owner owner,JSONObject saved) throws Exception {
        if(saved.optString("mode").equals("crop")) readCrop(saved.getString("crop"));
        else if(saved.optString("mode").equals("paint")) {
            if(!new File(saved.getString("paint")).isFile()) throw new IOException("Pending paint is missing");
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(saved.getString("paint"),bounds);
            if(bounds.outWidth<=0 || bounds.outHeight<=0) throw new IOException("Pending paint is invalid");
            MonogramMediaDrafts.validateEntities(MonogramMediaDrafts.readMediaEntities(saved.optString("entities")));
        }
        else throw new IOException("Unknown pending editor");
        synchronized(states) { Token old=tokens.remove(scope(owner)+"/"+identity(photo));if(old!=null) old.changed();forget(photo,owner);pending(photo,new Pending(owner,new JSONObject(saved.toString()))); }
    }
    static void snapshot(MonogramMediaDrafts.Owner owner,JSONObject saved) throws Exception {
        if(saved.has("paint")) { String path=saved.getString("paint");if(!path.startsWith(new File(owner.directory(),"editor-assets").getAbsolutePath()+File.separator)) saved.put("paint",MonogramMediaDrafts.copy(owner,path)); }
        ArrayList<VideoEditedInfo.MediaEntity> entities=MonogramMediaDrafts.readMediaEntities(saved.optString("entities"));MonogramMediaDrafts.snapshotEntities(owner,entities);saved.put("entities",MonogramMediaDrafts.mediaEntities(entities));
    }
    private static String crop(MediaController.CropState crop) {
        SerializedData data=new SerializedData();try { crop.serializeToStream(data);return android.util.Base64.encodeToString(data.toByteArray(),android.util.Base64.NO_WRAP); } finally { data.cleanup(); }
    }
    private static MediaController.CropState readCrop(String value) throws Exception {
        SerializedData data=new SerializedData(android.util.Base64.decode(value,android.util.Base64.DEFAULT));try { if(data.readInt32(true)!=MediaController.CropState.constructor) throw new IOException();MediaController.CropState crop=new MediaController.CropState();crop.readParams(data,true);return crop; } finally { data.cleanup(); }
    }
}
