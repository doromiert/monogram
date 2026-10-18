// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.RLottieNative;

/** Explicit user export; never publish a sticker merely because it was viewed. */
public final class MonogramStickerExport {
    private MonogramStickerExport() {}
    public static String label(TLRPC.Document document) {
        return document!=null && "application/x-tgsticker".equals(document.mime_type)
                ? "Save frame to Gallery" : LocaleController.getString(R.string.SaveToGallery);
    }
    public static void save(Activity activity,int account,TLRPC.Document document) {
        if(activity==null || document==null) return;
        if(Build.VERSION.SDK_INT<=28 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},4);
            Toast.makeText(activity,"Allow storage access, then tap Save to Gallery again.",Toast.LENGTH_LONG).show();return;
        }
        final String owner=MonogramPacks.owner(account);
        TLRPC.Document local=MonogramPacks.document(account,document.id);
        File source=local!=null && local.localPath!=null ? new File(local.localPath) : FileLoader.getInstance(account).getPathToAttach(document,true);
        if(source==null || !source.isFile() || source.length()==0) {
            Toast.makeText(activity,"Download the sticker first, then save it to Gallery.",Toast.LENGTH_LONG).show();return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File prepared=null;boolean success=false;
            try {
                prepared=prepare(source,document.mime_type,activity.getCacheDir());
                if(!owner.equals(MonogramPacks.owner(account))) return;
                publish(activity,prepared);success=true;
            } catch(Exception | LinkageError failure) { /* Show an actionable result, never log private file paths. */ }
            finally {
                if(prepared!=null) prepared.delete();
                final boolean saved=success;
                AndroidUtilities.runOnUIThread(() -> {
                    if(!activity.isFinishing() && owner.equals(MonogramPacks.owner(account)))
                        Toast.makeText(activity,saved ? "Saved to Gallery" : "Could not save the sticker. Check storage access and free space.",Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    static File prepare(File source,String mime,File cache) throws Exception {
        if(source.length()<8 || source.length()>20L*1024*1024) throw new IOException("Invalid sticker size");
        byte[] bytes;
        try(InputStream input=new FileInputStream(source);ByteArrayOutputStream data=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16384];int read;
            while((read=input.read(buffer))!=-1) {
                if(data.size()>20*1024*1024-read) throw new IOException("Invalid sticker size");data.write(buffer,0,read);
            }
            bytes=data.toByteArray();
        }
        MonogramPacks.validateMedia(bytes,mime);
        boolean animated="application/x-tgsticker".equals(mime);
        String extension=animated || "image/png".equals(mime) ? ".png" : "image/webp".equals(mime) ? ".webp" : ".webm";
        File result=File.createTempFile("sticker-export-",extension,cache);
        try(FileOutputStream output=new FileOutputStream(result)) {
            if(animated) {
                RLottieNative animation=RLottieNative.createFromFile(source.getAbsolutePath());
                if(animation==null) throw new IOException("Invalid animation");
                Bitmap frame=null;
                try {
                    frame=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
                    if(animation.getFrame(animation.getFrameCount()/2,frame,true)<0 || !frame.compress(Bitmap.CompressFormat.PNG,100,output)) throw new IOException("Cannot render sticker");
                } finally { if(frame!=null) frame.recycle();animation.recycle(); }
            } else output.write(bytes);
            output.getFD().sync();return result;
        } catch(Exception | LinkageError failure) { result.delete();throw failure; }
    }
    static Uri publish(Context context,File source) throws IOException {
        boolean video=source.getName().endsWith(".webm");String extension=video ? ".webm" : source.getName().endsWith(".webp") ? ".webp" : ".png";
        String mime=video ? "video/webm" : extension.equals(".webp") ? "image/webp" : "image/png";
        String name="Monogram_"+java.util.UUID.randomUUID()+extension;
        if(Build.VERSION.SDK_INT>=29) {
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)+"/Monogram");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri collection=video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            android.content.ContentResolver resolver=context.getContentResolver();Uri target=resolver.insert(collection,values);
            if(target==null) throw new IOException("Gallery unavailable");
            try {
                try(InputStream input=new FileInputStream(source);OutputStream output=resolver.openOutputStream(target)) {
                    if(output==null) throw new IOException("Gallery unavailable");copy(input,output);
                }
                ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
                if(resolver.update(target,ready,null,null)!=1) throw new IOException("Could not publish gallery entry");return target;
            } catch(Exception failure) {
                try { resolver.delete(target,null,null); } catch(Exception ignored) {}
                throw new IOException("Could not publish sticker",failure);
            }
        }
        File directory=new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),"Monogram");
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Gallery unavailable");
        File target=new File(directory,name);
        try(InputStream input=new FileInputStream(source);OutputStream output=new FileOutputStream(target)) { copy(input,output); }
        catch(IOException failure) { target.delete();throw failure; }
        AndroidUtilities.addMediaToGallery(target);return Uri.fromFile(target);
    }
    private static void copy(InputStream input,OutputStream output) throws IOException {
        byte[] buffer=new byte[16384];int read;while((read=input.read(buffer))!=-1) output.write(buffer,0,read);
    }
}
