// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

/** Device-only native chat appearance, owned by account JID + peer JID rather than reusable peer IDs. */
public final class MonogramChatAppearance {
    private MonogramChatAppearance() {}
    private static final ExecutorService WRITER=Executors.newSingleThreadExecutor();
    private static final Map<String,Long> revisions=new HashMap<>();
    private static final Map<Integer,Long> generations=new HashMap<>();
    private static final Map<String,Owner> owners=new HashMap<>();
    private static final Map<String,TLRPC.WallPaper> wallpapers=new HashMap<>();
    /** Fence pending writes before logout, including a later login to the same JID. */
    public static void invalidate(int account) { synchronized(revisions) { generations.put(account,generations.getOrDefault(account,0L)+1);owners.clear();wallpapers.clear(); } }
    private static String accountJid(int account) { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-"+account,Context.MODE_PRIVATE).getString("jid",""); }
    private static Owner owner(int account,long dialog) throws Exception { synchronized(revisions) {
        String index=account+":"+dialog;Owner owner=owners.get(index);
        if(owner==null || !owner.current()) { owner=new Owner(account,dialog);owners.put(index,owner); }
        return owner;
    } }
    private static SharedPreferences preferences() { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-chat-appearance",Context.MODE_PRIVATE); }
    static final class Owner {
        final int account;final long dialog,generation;final String jid,peer,key;
        Owner(int account,long dialog) throws Exception {
            this.account=account;this.dialog=dialog;generation=generations.getOrDefault(account,0L);
            MonogramController controller=MonogramController.get(account);jid=controller.accountJid();peer=controller.jid(dialog);
            if(jid.isEmpty() || peer==null || peer.isEmpty()) throw new IOException("Missing appearance owner");
            key=digest(account+"\n"+jid+"\n"+peer);
        }
        boolean current() { synchronized(revisions) { return generation==generations.getOrDefault(account,0L) && jid.equals(accountJid(account)); } }
        File directory() { return new File(ApplicationLoader.applicationContext.getFilesDir(),"monogram-chat-appearance/"+key); }
    }
    private static String digest(String value) throws Exception { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes) out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString(); }
    public static String theme(int account,long dialog) {
        try { Owner owner=owner(account,dialog);return preferences().getString(owner.key+"/theme",null); } catch(Exception unavailable) { return null; }
    }
    public static boolean theme(int account,long dialog,String value) { return theme(account,dialog,value,false); }
    public static boolean theme(int account,long dialog,String value,boolean resetWallpaper) {
        try { Owner owner=owner(account,dialog);synchronized(revisions) {
            if(!owner.current()) return false;
            SharedPreferences.Editor edit=preferences().edit().putString(owner.key+"/theme",value);
            String old=resetWallpaper ? preferences().getString(owner.key+"/wallpaper",null) : null;
            if(resetWallpaper) edit.remove(owner.key+"/wallpaper");
            if(!edit.commit()) return false;
            if(resetWallpaper) { revisions.put(owner.key,revisions.getOrDefault(owner.key,0L)+1);wallpapers.put(owner.key,null);retire(owner,old,null); }
            return true;
        } } catch(Exception failed) { FileLog.e("Could not save local chat theme");return false; }
    }
    public static TLRPC.WallPaper wallpaper(int account,long dialog) {
        synchronized(revisions) { return readWallpaper(account,dialog); }
    }
    private static TLRPC.WallPaper readWallpaper(int account,long dialog) {
        try {
            Owner owner=owner(account,dialog);synchronized(revisions) { if(wallpapers.containsKey(owner.key)) return wallpapers.get(owner.key); } String value=preferences().getString(owner.key+"/wallpaper",null);if(value==null) { synchronized(revisions) { wallpapers.put(owner.key,null); } return null; }
            JSONObject json=new JSONObject(value);SerializedData data=new SerializedData(Base64.decode(json.getString("native"),Base64.DEFAULT));
            try {
                TLRPC.WallPaper wallpaper=TLRPC.WallPaper.TLdeserialize(data,data.readInt32(true),true);String path=json.optString("file");
                if(!path.isEmpty()) { File file=new File(path);if(!file.getCanonicalFile().getParentFile().equals(owner.directory().getCanonicalFile()) || !file.isFile()) return null;wallpaper.uploadingImage=path; }
                synchronized(revisions) { if(owner.current()) wallpapers.put(owner.key,wallpaper); } return wallpaper;
            } finally { data.cleanup(); }
        } catch(Exception invalid) { FileLog.e("Could not restore local chat wallpaper");return null; }
    }
    public static boolean clearWallpaper(int account,long dialog) {
        try { Owner owner=owner(account,dialog);synchronized(revisions) { revisions.put(owner.key,revisions.getOrDefault(owner.key,0L)+1);if(!owner.current()) return false;String old=preferences().getString(owner.key+"/wallpaper",null);if(!preferences().edit().remove(owner.key+"/wallpaper").commit()) return false;wallpapers.put(owner.key,null);retire(owner,old,null);return true; } }
        catch(Exception failed) { FileLog.e("Could not reset local chat wallpaper");return false; }
    }
    private static void retire(Owner owner,String previous,File replacement) {
        if(previous==null) return;
        try { String path=new JSONObject(previous).optString("file");if(path.isEmpty()) return;
            File old=new File(path).getCanonicalFile();
            if(old.getParentFile().equals(owner.directory().getCanonicalFile()) && (replacement==null || !old.equals(replacement.getCanonicalFile()))) old.delete();
        } catch(Exception ignored) { /* Retain on uncertain ownership. */ }
    }
    /** Takes ownership of an optional detached bitmap; private file work never runs in the preview UI. */
    public static void wallpaper(int account,long dialog,TLRPC.WallPaperSettings settings,Bitmap bitmap,String source,Consumer<TLRPC.WallPaper> ready,Runnable failed) {
        final Owner owner;final byte[] encoded;final long revision;
        try {
            owner=owner(account,dialog);SerializedData data=new SerializedData();try { settings.serializeToStream(data);encoded=data.toByteArray(); } finally { data.cleanup(); }
            synchronized(revisions) { revision=revisions.getOrDefault(owner.key,0L)+1;revisions.put(owner.key,revision); }
        } catch(Exception invalid) { if(bitmap!=null) bitmap.recycle();AndroidUtilities.runOnUIThread(failed);return; }
        WRITER.execute(() -> {
            File file=null;boolean committed=false;
            try {
                if(!owner.current()) return;
                SerializedData data=new SerializedData(encoded);TLRPC.WallPaperSettings copy;
                try { copy=TLRPC.WallPaperSettings.TLdeserialize(data,data.readInt32(true),true); } finally { data.cleanup(); }
                TLRPC.TL_wallPaperNoFile wallpaper=new TLRPC.TL_wallPaperNoFile();wallpaper.id=UUID.randomUUID().getLeastSignificantBits();wallpaper.flags=4;wallpaper.settings=copy;
                if(bitmap!=null || source!=null) {
                    if(!owner.directory().isDirectory() && !owner.directory().mkdirs()) throw new IOException();
                    file=new File(owner.directory(),UUID.randomUUID()+".jpg");
                    try(FileOutputStream output=new FileOutputStream(file)) {
                        if(bitmap!=null) { if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,output)) throw new IOException(); }
                        else { File original=new File(source);if(!original.isFile() || original.length()>64L*1024*1024) throw new IOException();try(InputStream input=new FileInputStream(original)) { byte[] buffer=new byte[65536];int count;long size=0;while((count=input.read(buffer))>=0) { size+=count;if(size>64L*1024*1024) throw new IOException();output.write(buffer,0,count); } } }
                        output.getFD().sync();
                    }
                    wallpaper.uploadingImage=file.getAbsolutePath();
                }
                SerializedData nativeData=new SerializedData();String nativeValue;try { wallpaper.serializeToStream(nativeData);nativeValue=Base64.encodeToString(nativeData.toByteArray(),Base64.NO_WRAP); } finally { nativeData.cleanup(); }
                JSONObject json=new JSONObject().put("native",nativeValue);if(file!=null) json.put("file",file.getAbsolutePath());
                synchronized(revisions) {
                    if(!owner.current() || revisions.getOrDefault(owner.key,0L)!=revision) return;
                    String old=preferences().getString(owner.key+"/wallpaper",null);if(!preferences().edit().putString(owner.key+"/wallpaper",json.toString()).commit()) throw new IOException();committed=true;wallpapers.put(owner.key,wallpaper);retire(owner,old,file);
                }
                AndroidUtilities.runOnUIThread(() -> { synchronized(revisions) { if(owner.current() && revisions.getOrDefault(owner.key,0L)==revision) ready.accept(wallpaper); } });
            } catch(Exception error) { FileLog.e("Could not save local chat wallpaper");AndroidUtilities.runOnUIThread(() -> { if(owner.current()) failed.run(); }); }
            finally { if(bitmap!=null) bitmap.recycle();if(!committed && file!=null) file.delete(); }
        });
    }
}
