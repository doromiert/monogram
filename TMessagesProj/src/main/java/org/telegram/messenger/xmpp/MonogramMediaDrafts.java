// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

/** Local attachment compositions; native picker entries and the native durable outbox remain authoritative. */
public final class MonogramMediaDrafts {
    private MonogramMediaDrafts() {}
    public static final String BATCH="monogram-draft-batch",ITEM="monogram-draft-item",OWNER="monogram-draft-owner";
    private static final ExecutorService WRITER=Executors.newSingleThreadExecutor(),READER=Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String,JSONObject> latest=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,AtomicLong> revisions=new ConcurrentHashMap<>();
    private static final Set<String> loggedOut=ConcurrentHashMap.newKeySet();
    private static final Set<String> cleared=ConcurrentHashMap.newKeySet(),protectedPaths=ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String,String> runtimeEpochs=new ConcurrentHashMap<>();
    private static volatile Session active;
    public static final class Owner {
        final int account;final long dialog;final String jid,peer,key,epoch;
        Owner(int account,long dialog,String jid,String peer) throws Exception {
            if(jid.isEmpty() || peer.isEmpty()) throw new IllegalArgumentException("Missing draft owner");
            this.account=account;this.dialog=dialog;this.jid=jid;this.peer=peer;this.epoch=ownershipEpoch(account,jid);this.key=hash(account+"\n"+jid+"\n"+peer);
        }
        public static Owner current(int account,long dialog) {
            try { MonogramController controller=MonogramController.get(account);return new Owner(account,dialog,controller.accountJid(),controller.jid(dialog)); }
            catch(Exception unavailable) { return null; }
        }
        boolean current() { if(loggedOut.contains(account+"\n"+jid)) return false;Owner current=current(account,dialog);return current!=null && key.equals(current.key) && epoch.equals(current.epoch); }
        File directory() { return new File(scopeDirectory(account,jid),key); }
        AtomicFile manifest() { return new AtomicFile(new File(directory(),"draft.json")); }
    }
    public static final class Restored {
        public final ArrayList<MediaController.PhotoEntry> photos=new ArrayList<>();
        public String caption="";public ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();public boolean above;public int missing;
    }
    public static final class Session {
        public final Owner owner;
        private final Consumer<String> errors;
        private volatile boolean closed,sending;
        private String batch;
        private JSONArray missingEntries=new JSONArray();
        private final Map<Integer,String> itemTokens=new HashMap<>();
        private Session(Owner owner,Consumer<String> errors) { this.owner=owner;this.errors=errors; }
        public boolean active() { return !closed && active==this && owner.current(); }
        public void close() { closed=true; }
        public void restore(Consumer<Restored> result) {
            READER.execute(() -> {
                Restored restored=new Restored();JSONArray unavailable=new JSONArray();
                try {
                    JSONObject state=load(owner);if(state==null) return;
                    checkOwner(owner,state);restored.caption=state.optString("caption");restored.above=state.optBoolean("above");restored.entities=readEntities(state.optString("entities"));
                    Set<String> accepted=strings(state.optJSONArray("accepted"));JSONArray entries=state.getJSONArray("photos");
                    for(int i=0;i<entries.length();i++) { JSONObject entry=entries.getJSONObject(i);if(accepted.contains(entry.optString("item"))) continue;
                        try { restored.photos.add(decode(entry)); } catch(Exception missing) { restored.missing++;unavailable.put(entry); }
                    }
                    if(restored.photos.isEmpty() && entries.length()>0) restored.caption="";
                } catch(Exception invalid) { restored.missing++; }
                finally { AndroidUtilities.runOnUIThread(() -> { if(active()) { missingEntries=unavailable;result.accept(restored); } }); }
            });
        }
        public void save(Map<Object,Object> photos,List<Object> order,CharSequence caption,boolean above,boolean beginSend) {
            if(!active() || sending && !beginSend) return;
            try {
                if(beginSend) { batch=UUID.randomUUID().toString();itemTokens.clear(); }
                JSONObject state=new JSONObject().put("version",1).put("account",owner.account).put("jid",owner.jid).put("peer",owner.peer).put("epoch",owner.epoch).put("above",above);
                CharSequence[] text={caption==null ? "" : new android.text.SpannableStringBuilder(caption)};ArrayList<TLRPC.MessageEntity> entities=MediaDataController.getInstance(owner.account).getEntities(text,false);
                state.put("caption",text[0].toString()).put("entities",entities(entities));JSONArray entries=new JSONArray();
                for(Object key:order) {
                    Object value=photos.get(key);if(!(value instanceof MediaController.PhotoEntry)) throw new IOException("This selection cannot be saved as a media draft");
                    MediaController.PhotoEntry photo=(MediaController.PhotoEntry)value;JSONObject entry=encode(photo,owner.account);
                    if(beginSend) { String token=UUID.randomUUID().toString();itemTokens.put(photo.imageId,token);entry.put("item",token); }
                    entries.put(entry);protect(entry);
                }
                for(int i=0;i<missingEntries.length();i++) entries.put(missingEntries.getJSONObject(i));
                if(entries.length()==0 && text[0].length()==0) { discard();return; }
                state.put("photos",entries);if(beginSend) { sending=true;state.put("batch",batch); }
                publish(owner,state,errors);
            } catch(Exception failed) { report(errors,"Could not save the media draft. Keep the picker open and try again."); }
        }
        public void tag(SendMessagesHelper.SendingMediaInfo info,MediaController.PhotoEntry photo) {
            if(!owner.current() || batch==null || !itemTokens.containsKey(photo.imageId)) return;
            if(info.params==null) info.params=new HashMap<>();info.params.put(BATCH,batch);info.params.put(ITEM,itemTokens.get(photo.imageId));info.params.put(OWNER,owner.key);
        }
        public void discard() { if(!active()) return;sending=false;batch=null;itemTokens.clear();clear(owner); }
    }
    public static Session bind(int account,long dialog,Consumer<String> errors) {
        Owner owner=Owner.current(account,dialog);if(owner==null) return null;loggedOut.remove(account+"\n"+owner.jid);
        Session previous=active;if(previous!=null) previous.close();Session session=new Session(owner,errors);active=session;return session;
    }
    public static void deactivate() { Session session=active;if(session!=null) session.close();active=null; }
    public static boolean protectedFile(String path) { return path!=null && protectedPaths.contains(path); }
    public static void mergeParams(HashMap<String,String> destination,HashMap<String,String> source) {
        if(source==null) return;for(String key:Arrays.asList(BATCH,ITEM,OWNER)) if(source.containsKey(key)) destination.put(key,source.get(key));
    }
    /** reserveFile follows native putMessages; the callback below runs behind that transaction on its storage queue. */
    public static void accepted(int account,TLRPC.Message message) {
        if(message.params==null || !message.params.containsKey(BATCH) || !message.params.containsKey(ITEM)) return;
        Owner owner=Owner.current(account,message.dialog_id);if(owner==null || !owner.key.equals(message.params.get(OWNER))) return;
        final String batch=message.params.get(BATCH),item=message.params.get(ITEM);final int id=message.id;
        MessagesStorage.getInstance(account).getMonogramOutbox(message.dialog_id,rows -> {
            boolean durable=false;
            for(TLRPC.Message row:rows) if(row.id==id && row.params!=null && batch.equals(row.params.get(BATCH)) && item.equals(row.params.get(ITEM))) { durable=true;break; }
            if(!durable || !owner.current()) return;
            WRITER.execute(() -> {
                synchronized(revision(owner)) { try {
                    JSONObject state=load(owner);if(state==null || !batch.equals(state.optString("batch"))) return;
                    Set<String> accepted=strings(state.optJSONArray("accepted"));accepted.add(item);JSONArray acknowledgements=new JSONArray();for(String value:accepted) acknowledgements.put(value);
                    state=new JSONObject(state.toString()).put("accepted",acknowledgements);JSONArray photos=state.getJSONArray("photos");boolean complete=true;
                    for(int i=0;i<photos.length();i++) if(!accepted.contains(photos.getJSONObject(i).optString("item"))) complete=false;
                    File retained=new File(owner.directory(),"outbox-owned");try(FileOutputStream marker=new FileOutputStream(retained)) { marker.write(1);marker.getFD().sync(); }
                    if(complete) { latest.remove(owner.key);cleared.add(owner.key);revision(owner).incrementAndGet();owner.manifest().delete(); }
                    else { latest.put(owner.key,state);write(owner,state); }
                    // Files can still be read by pending native outbox entries; successful handoff never deletes them.
                } catch(Exception unavailable) { /* Retain the draft rather than falsely acknowledging a file. */ } }
            });
        });
    }
    private static File scopeDirectory(int account,String jid) {
        try { return new File(ApplicationLoader.applicationContext.getFilesDir(),"monogram-media-drafts/"+account+"-"+hash(jid)); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static synchronized String ownershipEpoch(int account,String jid) {
        android.content.SharedPreferences preferences=ApplicationLoader.applicationContext.getSharedPreferences("monogram-media-draft-owners",Context.MODE_PRIVATE);
        String key=account+"\n"+jid,value=runtimeEpochs.get(key);if(value!=null) return value;value=preferences.getString(key,null);
        if(value==null) { value=UUID.randomUUID().toString();if(!preferences.edit().putString(key,value).commit()) throw new IllegalStateException("Cannot save media draft owner"); }
        return value;
    }
    public static void logout(int account,String jid) {
        final String nextEpoch=UUID.randomUUID().toString();
        loggedOut.add(account+"\n"+jid);runtimeEpochs.put(account+"\n"+jid,nextEpoch);
        boolean persisted=false;
        try { persisted=ApplicationLoader.applicationContext.getSharedPreferences("monogram-media-draft-owners",Context.MODE_PRIVATE).edit().putString(account+"\n"+jid,nextEpoch).commit(); }
        catch(Exception failed) { FileLog.e("Media draft logout generation could not be saved"); }
        if(!persisted) {
            // Fence this process immediately, then best-effort remove old metadata before credentials are cleared.
            // Never remove possibly queued assets when disk/permissions are unreliable.
            FileLog.e("Media draft logout metadata persistence failed");
            try { File[] directories=scopeDirectory(account,jid).listFiles();if(directories!=null) for(File directory:directories) new AtomicFile(new File(directory,"draft.json")).delete(); }
            catch(Exception ignored) { FileLog.e("Media draft logout metadata cleanup failed"); }
        }
        Session session=active;
        if(session!=null && session.owner.account==account && session.owner.jid.equals(jid)) { session.close();active=null; }
        for(Map.Entry<String,JSONObject> entry:latest.entrySet()) if(entry.getValue().optInt("account",-1)==account && jid.equals(entry.getValue().optString("jid"))) {
            revisions.computeIfAbsent(entry.getKey(),key -> new AtomicLong()).incrementAndGet();latest.remove(entry.getKey());cleared.add(entry.getKey());
        }
        WRITER.execute(() -> {
            File[] directories=scopeDirectory(account,jid).listFiles();if(directories==null) return;
            for(File directory:directories) {
                if(!nextEpoch.equals(ownershipEpoch(account,jid))) return;
                boolean retained=new File(directory,"outbox-owned").isFile();AtomicFile manifest=new AtomicFile(new File(directory,"draft.json"));
                try(InputStream input=manifest.openRead()) { ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))>=0) { if(bytes.size()+count>16*1024*1024) throw new IOException();bytes.write(buffer,0,count); }JSONObject state=new JSONObject(bytes.toString("UTF-8"));if(nextEpoch.equals(state.optString("epoch"))) continue;retained|=state.has("batch"); }
                catch(FileNotFoundException absent) {} catch(Exception uncertain) { retained=true; }
                manifest.delete();if(!retained) deleteTree(directory);
            }
        });
    }
    private static void deleteTree(File file) { File[] children=file.listFiles();if(children!=null) for(File child:children) deleteTree(child);file.delete(); }
    private static AtomicLong revision(Owner owner) { return revisions.computeIfAbsent(owner.key,key -> new AtomicLong()); }
    private static void publish(Owner owner,JSONObject state,Consumer<String> errors) {
        final long version;
        synchronized(revision(owner)) {
            version=revision(owner).incrementAndGet();
            try { state.put("revision",version);latest.put(owner.key,state);cleared.remove(owner.key);write(owner,state); }
            catch(Exception failed) { report(errors,"Could not save the media draft. Keep the picker open and try again."); }
        }
        WRITER.execute(() -> {
            if(version!=revision(owner).get() || !owner.current()) return;
            try {
                JSONObject saved=new JSONObject(state.toString());JSONArray photos=saved.getJSONArray("photos");
                boolean incomplete=false;for(int i=0;i<photos.length();i++) try { snapshotFiles(owner,photos.getJSONObject(i)); } catch(Exception missing) { incomplete=true; }
                synchronized(revision(owner)) {
                    if(version!=revision(owner).get() || !owner.current()) return;
                    JSONObject current=latest.get(owner.key);if(current!=null && current.has("accepted")) saved.put("accepted",current.getJSONArray("accepted"));
                    write(owner,saved);latest.put(owner.key,saved);
                }
                if(incomplete) report(errors,"Some draft media could not be preserved. Keep the original files available before leaving the picker.");
            } catch(Exception failed) { report(errors,"Some draft media could not be preserved. Keep the original files available before leaving the picker."); }
        });
    }
    private static void clear(Owner owner) {
        synchronized(revision(owner)) { revision(owner).incrementAndGet();latest.remove(owner.key);cleared.add(owner.key);owner.manifest().delete(); }
    }
    private static JSONObject load(Owner owner) throws Exception {
        synchronized(revision(owner)) { return loadLocked(owner); }
    }
    private static JSONObject loadLocked(Owner owner) throws Exception {
        JSONObject memory=latest.get(owner.key);if(memory!=null) return new JSONObject(memory.toString());if(cleared.contains(owner.key)) return null;
        try(InputStream input=owner.manifest().openRead()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))>=0) { if(bytes.size()+count>16*1024*1024) throw new IOException("Media draft metadata is too large");bytes.write(buffer,0,count); }
            JSONObject state=new JSONObject(bytes.toString("UTF-8"));if(!owner.epoch.equals(state.optString("epoch"))) return null;checkOwner(owner,state);revision(owner).accumulateAndGet(state.optLong("revision"),Math::max);return state;
        } catch(FileNotFoundException absent) { return null; }
    }
    private static void checkOwner(Owner owner,JSONObject state) throws Exception {
        if(state.getInt("version")!=1 || state.getInt("account")!=owner.account || !owner.jid.equals(state.getString("jid")) || !owner.peer.equals(state.getString("peer")) || !owner.epoch.equals(state.getString("epoch"))) throw new IOException("Draft owner changed");
    }
    private static void write(Owner owner,JSONObject state) throws Exception {
        synchronized(revision(owner)) {
            if(state.optLong("revision")!=revision(owner).get()) return;
            if(!owner.directory().isDirectory() && !owner.directory().mkdirs()) throw new IOException("Draft storage unavailable");
            AtomicFile file=owner.manifest();FileOutputStream output=file.startWrite();
            try { output.write(state.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output); }
            catch(Exception failed) { file.failWrite(output);throw failed; }
        }
    }
    private static void report(Consumer<String> errors,String text) { if(errors!=null) AndroidUtilities.runOnUIThread(() -> errors.accept(text)); }
    private static Set<String> strings(JSONArray array) { Set<String> values=new HashSet<>();if(array!=null) for(int i=0;i<array.length();i++) values.add(array.optString(i));return values; }
    private static String hash(String value) throws Exception { byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:hash) result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString(); }

    private static final String[] PATHS={"path","thumbPath","imagePath","filterPath","paintPath","croppedPaintPath","fullPaintPath","coverPath"};
    private static JSONObject encode(MediaController.PhotoEntry photo,int account) throws Exception {
        JSONObject out=new JSONObject().put("bucket",photo.bucketId).put("id",photo.imageId).put("date",photo.dateTaken).put("duration",photo.duration)
                .put("width",photo.width).put("height",photo.height).put("size",photo.size).put("orientation",photo.orientation).put("invert",photo.invert).put("video",photo.isVideo)
                .put("muted",photo.isMuted).put("delete",photo.canDeleteAfter).put("spoiler",photo.hasSpoiler).put("videoOrientation",photo.videoOrientation)
                .put("liveOffset",photo.livePhotoVideoOffset).put("liveTime",photo.livePhotoTimestampUs).put("averageDuration",photo.averageDuration)
                .put("filtered",photo.isFiltered).put("painted",photo.isPainted).put("cropped",photo.isCropped).put("ttl",photo.ttl).put("effect",photo.effectId).put("coverPosition",photo.coverSavedPosition)
                .put("quality",photo.highQuality==null ? JSONObject.NULL : photo.highQuality).put("discardLive",photo.discardLivePhoto==null ? JSONObject.NULL : photo.discardLivePhoto);
        String[] paths={photo.path,photo.thumbPath,photo.imagePath,photo.filterPath,photo.paintPath,photo.croppedPaintPath,photo.fullPaintPath,photo.coverPath};
        for(int i=0;i<PATHS.length;i++) if(paths[i]!=null && !paths[i].isEmpty()) out.put(PATHS[i],paths[i]);
        CharSequence[] text={photo.caption==null ? "" : new android.text.SpannableStringBuilder(photo.caption)};ArrayList<TLRPC.MessageEntity> entities=photo.entities;
        if(photo.caption instanceof android.text.Spanned) entities=MediaDataController.getInstance(account).getEntities(text,false);
        out.put("caption",text[0].toString()).put("entities",entities(entities));
        if(photo.editedInfo!=null) out.put("edit",edit(photo.editedInfo));
        if(photo.savedFilterState!=null) out.put("filter",binary(data -> photo.savedFilterState.serializeToStream(data)));
        if(photo.cropState!=null) out.put("crop",binary(data -> photo.cropState.serializeToStream(data)));
        if(photo.coverPhoto!=null) out.put("cover",binary(data -> photo.coverPhoto.serializeToStream(data)));
        out.put("mediaEntities",mediaEntities(photo.mediaEntities)).put("croppedEntities",mediaEntities(photo.croppedMediaEntities));
        if(photo.stickers!=null) out.put("stickers",binary(data -> { data.writeInt32(photo.stickers.size());for(TLRPC.InputDocument document:photo.stickers) document.serializeToStream(data); }));
        File source=photo.path==null ? null : new File(photo.path);if(source!=null && source.isFile()) out.put("sourceSize",source.length()).put("sourceModified",source.lastModified());
        return out;
    }
    private static MediaController.PhotoEntry decode(JSONObject in) throws Exception {
        String path=in.getString("path");File source=new File(path);if(!source.isFile() || !source.canRead()) throw new IOException("Draft source unavailable");
        if(in.has("sourceSize") && source.length()!=in.getLong("sourceSize") || in.has("sourceModified") && source.lastModified()!=in.getLong("sourceModified")) throw new IOException("Draft source changed");
        MediaController.PhotoEntry photo=new MediaController.PhotoEntry(in.getInt("bucket"),in.getInt("id"),in.getLong("date"),path,in.getInt("orientation"),in.getInt("duration"),in.getBoolean("video"),in.getInt("width"),in.getInt("height"),in.getLong("size"));
        photo.invert=in.optInt("invert");photo.isMuted=in.optBoolean("muted");photo.canDeleteAfter=in.optBoolean("delete");photo.hasSpoiler=in.optBoolean("spoiler");photo.videoOrientation=in.optInt("videoOrientation",-1);
        photo.livePhotoVideoOffset=in.optLong("liveOffset");photo.livePhotoTimestampUs=in.optLong("liveTime");photo.averageDuration=in.optLong("averageDuration");
        photo.isFiltered=in.optBoolean("filtered");photo.isPainted=in.optBoolean("painted");photo.isCropped=in.optBoolean("cropped");photo.ttl=in.optInt("ttl");photo.effectId=in.optLong("effect");photo.coverSavedPosition=in.optLong("coverPosition");
        photo.highQuality=in.isNull("quality") ? null : in.getBoolean("quality");photo.discardLivePhoto=in.isNull("discardLive") ? null : in.getBoolean("discardLive");
        photo.caption=in.optString("caption");photo.entities=readEntities(in.optString("entities"));
        photo.thumbPath=existing(in,"thumbPath",false);photo.imagePath=existing(in,"imagePath",true);photo.filterPath=existing(in,"filterPath",true);photo.paintPath=existing(in,"paintPath",true);
        photo.croppedPaintPath=existing(in,"croppedPaintPath",true);photo.fullPaintPath=existing(in,"fullPaintPath",true);photo.coverPath=existing(in,"coverPath",true);
        if(in.has("edit")) {
            photo.editedInfo=readEdit(in.getJSONObject("edit"));
            requireAsset(photo.editedInfo.paintPath);requireAsset(photo.editedInfo.blurPath);validateEntities(photo.editedInfo.mediaEntities);
        }
        if(in.has("filter")) { SerializedData data=read(in.getString("filter"));try { photo.savedFilterState=new MediaController.SavedFilterState();photo.savedFilterState.readParams(data,true); } finally { data.cleanup(); } }
        if(in.has("crop")) { SerializedData data=read(in.getString("crop"));try { if(data.readInt32(true)!=MediaController.CropState.constructor) throw new IOException("Invalid crop");photo.cropState=new MediaController.CropState();photo.cropState.readParams(data,true); } finally { data.cleanup(); } }
        if(in.has("cover")) { SerializedData data=read(in.getString("cover"));try { photo.coverPhoto=TLRPC.Photo.TLdeserialize(data,data.readInt32(true),true); } finally { data.cleanup(); } }
        photo.mediaEntities=readMediaEntities(in.optString("mediaEntities"));photo.croppedMediaEntities=readMediaEntities(in.optString("croppedEntities"));
        validateEntities(photo.mediaEntities);validateEntities(photo.croppedMediaEntities);
        if(in.has("stickers")) { SerializedData data=read(in.getString("stickers"));try { int count=data.readInt32(true);if(count<0 || count>4096) throw new IOException("Invalid masks");photo.stickers=new ArrayList<>();for(int i=0;i<count;i++) photo.stickers.add(TLRPC.InputDocument.TLdeserialize(data,data.readInt32(true),true)); } finally { data.cleanup(); } }
        return photo;
    }
    private static void requireAsset(String path) throws IOException {
        if(path!=null && !path.isEmpty() && (!new File(path).isFile() || !new File(path).canRead())) throw new IOException("An edited draft asset is missing");
    }
    private static void validateEntities(ArrayList<VideoEditedInfo.MediaEntity> entities) throws IOException {
        if(entities==null) return;
        for(VideoEditedInfo.MediaEntity entity:entities) {
            if(entity.type!=VideoEditedInfo.MediaEntity.TYPE_TEXT && entity.text!=null && (entity.text.startsWith("/") || entity.text.startsWith("content:"))) requireAsset(entity.text);
            requireAsset(entity.segmentedPath);
            if(entity.document!=null) requireAsset(entity.document.localPath);
            for(VideoEditedInfo.EmojiEntity emoji:entity.entities) requireAsset(emoji.documentAbsolutePath);
        }
    }
    private static String existing(JSONObject in,String key,boolean required) throws Exception { String path=in.optString(key,"");if(path.isEmpty()) return null;if(new File(path).isFile()) return path;if(required) throw new IOException("An edited draft asset is missing");return null; }
    private static JSONObject edit(VideoEditedInfo info) throws Exception { return new JSONObject().put("native",info.getString()).put("muted",info.muted).put("round",info.roundVideo).put("photo",info.isPhoto).put("size",info.estimatedSize).put("duration",info.estimatedDuration).put("quality",info.compressQuality).put("start",info.start).put("end",info.end).put("volume",info.volume).put("fullEntities",mediaEntities(info.mediaEntities)); }
    private static VideoEditedInfo readEdit(JSONObject in) throws Exception { VideoEditedInfo info=new VideoEditedInfo();if(!info.parseString(in.getString("native"))) throw new IOException("Invalid video edit");info.muted=in.optBoolean("muted");info.roundVideo=in.optBoolean("round");info.isPhoto=in.optBoolean("photo");info.estimatedSize=in.optLong("size");info.estimatedDuration=in.optLong("duration");info.compressQuality=in.optInt("quality");info.start=(float)in.optDouble("start");info.end=(float)in.optDouble("end");info.volume=(float)in.optDouble("volume",1);info.mediaEntities=readMediaEntities(in.optString("fullEntities"));return info; }
    private interface Encoder { void write(SerializedData data); }
    private static String binary(Encoder encoder) { SerializedData data=new SerializedData();try { encoder.write(data);return android.util.Base64.encodeToString(data.toByteArray(),android.util.Base64.NO_WRAP); } finally { data.cleanup(); } }
    private static SerializedData read(String value) { return new SerializedData(android.util.Base64.decode(value,android.util.Base64.DEFAULT)); }
    private static String entities(ArrayList<TLRPC.MessageEntity> entities) { return binary(data -> { data.writeInt32(entities==null ? 0 : entities.size());if(entities!=null) for(TLRPC.MessageEntity entity:entities) entity.serializeToStream(data); }); }
    private static ArrayList<TLRPC.MessageEntity> readEntities(String value) throws Exception { ArrayList<TLRPC.MessageEntity> result=new ArrayList<>();if(value.isEmpty()) return result;SerializedData data=read(value);try { int count=data.readInt32(true);if(count<0 || count>4096) throw new IOException("Invalid caption entities");for(int i=0;i<count;i++) result.add(TLRPC.MessageEntity.TLdeserialize(data,data.readInt32(true),true));return result; } finally { data.cleanup(); } }
    private static String mediaEntities(ArrayList<VideoEditedInfo.MediaEntity> entities) { if(entities==null) return "";return binary(data -> { data.writeInt32(entities.size());for(VideoEditedInfo.MediaEntity entity:entities) { entity.serializeTo(data,true);data.writeString(entity.document==null || entity.document.localPath==null ? "" : entity.document.localPath);data.writeInt32(entity.entities.size());for(VideoEditedInfo.EmojiEntity emoji:entity.entities) data.writeString(emoji.documentAbsolutePath==null ? "" : emoji.documentAbsolutePath); } }); }
    private static ArrayList<VideoEditedInfo.MediaEntity> readMediaEntities(String value) throws Exception { if(value.isEmpty()) return null;SerializedData data=read(value);try { int count=data.readInt32(true);if(count<0 || count>4096) throw new IOException("Invalid paint entities");ArrayList<VideoEditedInfo.MediaEntity> result=new ArrayList<>();for(int i=0;i<count;i++) { VideoEditedInfo.MediaEntity entity=new VideoEditedInfo.MediaEntity(data,true,true);String local=data.readString(true);if(entity.document!=null && !local.isEmpty()) entity.document.localPath=local;int emojis=data.readInt32(true);if(emojis!=entity.entities.size()) throw new IOException("Invalid paint emoji paths");for(VideoEditedInfo.EmojiEntity emoji:entity.entities) { String path=data.readString(true);if(!path.isEmpty()) emoji.documentAbsolutePath=path; } result.add(entity); }return result; } finally { data.cleanup(); } }
    private static void protect(JSONObject entry) { for(String key:PATHS) { String path=entry.optString(key);if(!path.isEmpty()) { protectedPaths.add(path);if(path.startsWith("content:")) try { ApplicationLoader.applicationContext.getContentResolver().takePersistableUriPermission(Uri.parse(path),Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(SecurityException ignored) {} } } }
    private static void snapshotFiles(Owner owner,JSONObject entry) throws Exception {
        for(String key:PATHS) {
            String source=entry.optString(key);if(source.isEmpty()) continue;
            boolean copy=!key.equals("path") || entry.optBoolean("delete") || source.startsWith("content:") || source.startsWith(ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath()+File.separator);
            if(key.equals("thumbPath") && !source.startsWith("content:") && !new File(source).isFile()) { entry.remove(key);continue; }
            String path=copy ? copy(owner,source) : source;entry.put(key,path);
            if(key.equals("path")) { File file=new File(path);entry.put("sourceSize",file.length()).put("sourceModified",file.lastModified()); }
        }
        if(entry.has("edit")) {
            VideoEditedInfo info=readEdit(entry.getJSONObject("edit"));info.originalPath=entry.getString("path");
            if(info.paintPath!=null) info.paintPath=copy(owner,info.paintPath);if(info.blurPath!=null) info.blurPath=copy(owner,info.blurPath);
            snapshotEntities(owner,info.mediaEntities);entry.put("edit",edit(info));
        }
        for(String key:Arrays.asList("mediaEntities","croppedEntities")) { ArrayList<VideoEditedInfo.MediaEntity> entities=readMediaEntities(entry.optString(key));snapshotEntities(owner,entities);entry.put(key,mediaEntities(entities)); }
    }
    private static void snapshotEntities(Owner owner,ArrayList<VideoEditedInfo.MediaEntity> entities) throws Exception {
        if(entities==null) return;
        for(VideoEditedInfo.MediaEntity entity:entities) {
            if(entity.type!=VideoEditedInfo.MediaEntity.TYPE_TEXT && entity.text!=null && (entity.text.startsWith("/") || entity.text.startsWith("content:"))) entity.text=copy(owner,entity.text);
            if(entity.segmentedPath!=null && !entity.segmentedPath.isEmpty()) entity.segmentedPath=copy(owner,entity.segmentedPath);
            if(entity.document!=null && entity.document.localPath!=null && !entity.document.localPath.isEmpty()) entity.document.localPath=copy(owner,entity.document.localPath);
            for(VideoEditedInfo.EmojiEntity emoji:entity.entities) if(emoji.documentAbsolutePath!=null && !emoji.documentAbsolutePath.isEmpty()) emoji.documentAbsolutePath=copy(owner,emoji.documentAbsolutePath);
        }
    }
    private static String copy(Owner owner,String source) throws Exception {
        File original=new File(source),assets=new File(owner.directory(),"assets");if(source.startsWith(assets.getAbsolutePath()+File.separator) && original.isFile()) return source;
        if(!assets.isDirectory() && !assets.mkdirs()) throw new IOException("Draft assets unavailable");
        String suffix="";int dot=source.lastIndexOf('.');if(dot>=0 && source.length()-dot<=10) suffix=source.substring(dot).replaceAll("[^a-zA-Z0-9.]","");
        File destination=new File(assets,hash(source+"\n"+original.length()+"\n"+original.lastModified()+(source.startsWith("content:") ? "\n"+UUID.randomUUID() : ""))+suffix);if(destination.isFile()) return destination.getAbsolutePath();
        Uri uri=Uri.parse(source);boolean content="content".equals(uri.getScheme());
        if(content) try { ApplicationLoader.applicationContext.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(SecurityException unsupported) { /* A private snapshot outlives temporary grants. */ }
        File temporary=File.createTempFile("asset-",".tmp",assets);
        try(InputStream input=content ? ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri) : new FileInputStream(original);FileOutputStream output=new FileOutputStream(temporary)) {
            if(input==null) throw new FileNotFoundException();byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))>=0) { if(loggedOut.contains(owner.account+"\n"+owner.jid)) throw new InterruptedIOException("Draft owner logged out");output.write(buffer,0,count); }output.getFD().sync();
            if(!temporary.renameTo(destination)) throw new IOException("Could not save draft asset");return destination.getAbsolutePath();
        } finally { temporary.delete(); }
    }
}
