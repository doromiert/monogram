// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** MediaStore sources stay as content URIs until a selected upload needs a private file. */
public final class MonogramAudioSources {
    private MonogramAudioSources() {}
    public static boolean isAudioSource(String path) {
        if(path==null) return false;
        Uri uri=Uri.parse(path);
        if(!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()) || uri.getQuery()!=null || uri.getFragment()!=null) return false;
        java.util.List<String> parts=uri.getPathSegments();
        if(parts.size()!=4 || !"audio".equals(parts.get(1)) || !"media".equals(parts.get(2))) return false;
        try { return Long.parseLong(parts.get(3))>0; } catch(NumberFormatException invalid) { return false; }
    }
    public static Uri contentUri(String path) {
        return isAudioSource(path) ? Uri.parse(path) : null;
    }
    public static ArrayList<MediaController.AudioEntry> query(int account) {
        ArrayList<MediaController.AudioEntry> entries=new ArrayList<>();
        ContentResolver resolver=ApplicationLoader.applicationContext.getContentResolver();
        String[] columns={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE,MediaStore.Audio.Media.SIZE};
        try(Cursor cursor=resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,columns,MediaStore.Audio.Media.IS_MUSIC+" != 0",null,MediaStore.Audio.Media.TITLE)) {
            int localId=-2000000000;
            while(cursor!=null && cursor.moveToNext()) {
                MediaController.AudioEntry entry=new MediaController.AudioEntry();
                entry.id=cursor.getLong(0);entry.uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,entry.id);
                if(entry.id<=0) continue;
                entry.author=clean(cursor.getString(1));entry.title=clean(cursor.getString(2));
                entry.duration=(int)Math.min(Integer.MAX_VALUE,Math.max(0,cursor.getLong(3)/1000));entry.genre=clean(cursor.getString(4));
                entry.displayName=filename(cursor.getString(5));entry.mimeType=mime(cursor.getString(6),entry.displayName);entry.size=cursor.getLong(7);
                if(!entry.mimeType.startsWith("audio/") && !"application/ogg".equals(entry.mimeType)) continue;
                if(entry.size>com.negzero.monogram.xmpp.FileTransfer.MAX_BYTES) continue;
                entry.size=Math.max(0,entry.size);
                entry.path=entry.uri.toString();
                if(entry.title.isEmpty()) entry.title=entry.displayName;
                entry.messageObject=new MessageObject(account,message(entry,localId--,UserConfig.getInstance(account).getClientUserId()),false,true);
                entry.messageObject.attachPathExists=true;
                entries.add(entry);
            }
        } catch(SecurityException denied) {
            // The picker handles its existing storage permission prompt.
        } catch(RuntimeException unavailable) {
            org.telegram.messenger.FileLog.e(unavailable);
        }
        return entries;
    }
    private static String clean(String value) { return value==null || "<unknown>".equals(value) ? "" : value; }
    static String filename(String value) {
        if(value==null || value.isEmpty()) return "audio.mp3";
        String name=value.replace('\\','_').replace('/','_').replaceAll("[\\p{Cntrl}]","_");
        if(name.length()<=200) return name;
        int dot=name.lastIndexOf('.');String extension=dot>=0 && name.length()-dot<=11 ? name.substring(dot) : "";
        return name.substring(0,200-extension.length())+extension;
    }
    private static String mime(String value,String name) {
        if(value!=null && !value.isEmpty() && !"application/octet-stream".equalsIgnoreCase(value)) return value.toLowerCase(Locale.ROOT);
        int dot=name.lastIndexOf('.');
        String type=dot<0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));
        return type==null ? "audio/mpeg" : type;
    }
    static TLRPC.TL_message message(MediaController.AudioEntry entry,int id,long self) {
        TLRPC.TL_message message=new TLRPC.TL_message();message.out=true;message.id=id;
        message.peer_id=new TLRPC.TL_peerUser();message.from_id=new TLRPC.TL_peerUser();message.peer_id.user_id=message.from_id.user_id=self;
        message.date=(int)(System.currentTimeMillis()/1000);message.message="";message.attachPath=entry.path;
        message.media=new TLRPC.TL_messageMediaDocument();message.media.flags|=3;message.media.document=new TLRPC.TL_document();
        message.flags|=TLRPC.MESSAGE_FLAG_HAS_MEDIA|TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        TLRPC.Document document=message.media.document;document.file_reference=new byte[0];document.date=message.date;
        document.mime_type=entry.mimeType;document.size=entry.size;
        TLRPC.TL_documentAttributeAudio audio=new TLRPC.TL_documentAttributeAudio();
        audio.duration=entry.duration;audio.title=entry.title;audio.performer=entry.author;audio.flags|=3;document.attributes.add(audio);
        TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();name.file_name=entry.displayName;document.attributes.add(name);
        return message;
    }
    /** Worker-thread only; maxPixels is the maximum thumbnail width or height. */
    public static Bitmap cover(String path,int maxPixels) {
        Uri uri=contentUri(path);if(uri==null || maxPixels<=0) return null;
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try {
            retriever.setDataSource(ApplicationLoader.applicationContext,uri);
            byte[] image=retriever.getEmbeddedPicture();
            if(image==null || image.length>2*1024*1024) return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(image,0,image.length,options);
            if(options.outWidth<=0 || options.outHeight<=0 || (long)options.outWidth*options.outHeight>40000000) return null;
            int side=Math.min(maxPixels,512);options.inSampleSize=1;
            while((options.outWidth+options.inSampleSize-1)/options.inSampleSize>side || (options.outHeight+options.inSampleSize-1)/options.inSampleSize>side) options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            return BitmapFactory.decodeByteArray(image,0,image.length,options);
        } catch(Exception unavailable) { return null; }
        finally { try { retriever.release(); } catch(Exception ignored) {} }
    }
    /** Caller owns the returned file and must delete it after snapshotting or abandoning upload. */
    public static File materialize(int account,Uri uri,String displayName,long maxBytes,BooleanSupplier cancelled) throws IOException {
        if(account<0 || account>=UserConfig.MAX_ACCOUNT_COUNT || uri==null || contentUri(uri.toString())==null || maxBytes<=0)
            throw new IOException("Invalid audio source");
        long limit=Math.min(maxBytes,com.negzero.monogram.xmpp.FileTransfer.MAX_BYTES);
        check(cancelled);
        File directory=new File(ApplicationLoader.applicationContext.getCacheDir(),"monogram-audio-"+account);
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private audio cache");
        String name=filename(displayName);int dot=name.lastIndexOf('.');
        String extension=dot<0 ? "" : name.substring(dot);
        if(!extension.matches("\\.[A-Za-z0-9]{1,10}")) extension=".audio";
        File file=File.createTempFile("selected-",extension,directory);
        try {
            try(InputStream input=ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                FileOutputStream output=new FileOutputStream(file)) {
                if(input==null) throw new IOException("Audio source is unavailable");
                copy(input,output,limit,cancelled);output.getFD().sync();
            }
            check(cancelled);return file;
        } catch(IOException | RuntimeException failed) { file.delete();throw failed; }
    }
    static long copy(InputStream input,java.io.OutputStream output,long limit,BooleanSupplier cancelled) throws IOException {
        byte[] buffer=new byte[64*1024];long total=0;
        while(true) {
            check(cancelled);int count=input.read(buffer);if(count<0) break;
            if(count>limit-total) throw new IOException("Audio file exceeds the upload limit");
            check(cancelled);output.write(buffer,0,count);total+=count;
        }
        if(total==0) throw new IOException("Audio source is empty");
        return total;
    }
    private static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted() || cancelled!=null && cancelled.getAsBoolean()) throw new InterruptedIOException("Audio preparation cancelled");
    }
}
