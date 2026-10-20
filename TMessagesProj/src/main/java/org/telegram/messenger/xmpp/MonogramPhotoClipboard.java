// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import com.negzero.monogram.xmpp.FileTransfer;
import java.io.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

/** An explicit image copy exports bytes, never an encrypted attachment's key or download URL. */
public final class MonogramPhotoClipboard {
    private static final AtomicLong requests=new AtomicLong();
    private MonogramPhotoClipboard() {}
    public static boolean isPhoto(MessageObject message) {
        if(message==null || message.messageOwner==null || message.isSecretMedia() || message.isEphemeral()
                || message.needDrawBluredPreview() || message.isHiddenSensitive() && !message.isMediaSpoilersRevealed) return false;
        String mime=message.getMimeType();
        return message.isPhoto() || !message.isAnyKindOfSticker() && mime!=null && mime.startsWith("image/");
    }
    public static void copy(BaseFragment fragment,MessageObject message) {
        if(!isPhoto(message) || fragment.getParentActivity()==null) return;
        final int account=fragment.getCurrentAccount();
        final MonogramController controller=MonogramController.get(account);
        final long owner=controller.accountGeneration(),request=requests.incrementAndGet();
        final File source=MonogramMessageInfo.localFile(account,message);
        if(source==null) { error(fragment);return; }
        final Context context=ApplicationLoader.applicationContext;
        Utilities.globalQueue.postRunnable(() -> {
            File prepared=null;
            try {
                if(owner!=controller.accountGeneration() || request!=requests.get()) return;
                prepared=prepare(source,context.getCacheDir());
                File exported=prepared;
                Uri uri=FileProvider.getUriForFile(context,ApplicationLoader.getApplicationId()+".provider",exported);
                String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(exported.getName()));
                if(mime==null || !mime.startsWith("image/")) throw new IOException("Image type unavailable");
                AndroidUtilities.runOnUIThread(() -> {
                    if(owner!=controller.accountGeneration() || request!=requests.get() || fragment.getCurrentAccount()!=account) { exported.delete();return; }
                    try {
                        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if(clipboard==null) throw new IllegalStateException("Clipboard unavailable");
                        ClipDescription description=new ClipDescription(LocaleController.getString(R.string.MonogramCopyPhoto),new String[]{mime});
                        clipboard.setPrimaryClip(new ClipData(description,new ClipData.Item(uri)));
                        trim(exported);
                        if(fragment.getParentActivity()!=null) BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.MonogramPhotoCopied),fragment.getResourceProvider()).show();
                    } catch(Exception failure) { exported.delete();if(fragment.getParentActivity()!=null) error(fragment); }
                });
                prepared=null;
            } catch(Exception failure) {
                AndroidUtilities.runOnUIThread(() -> {
                    if(owner==controller.accountGeneration() && request==requests.get() && fragment.getCurrentAccount()==account && fragment.getParentActivity()!=null) error(fragment);
                });
            } finally { if(prepared!=null) prepared.delete(); }
        });
    }
    static File prepare(File source,File cache) throws IOException {
        long size=source.length();if(!source.isFile() || size<=0 || size>FileTransfer.MAX_BYTES) throw new IOException("Photo unavailable");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(source.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outMimeType==null || !bounds.outMimeType.startsWith("image/")) throw new IOException("Invalid photo");
        String extension=MimeTypeMap.getSingleton().getExtensionFromMimeType(bounds.outMimeType);
        if(extension==null || !extension.matches("[A-Za-z0-9]{1,8}")) throw new IOException("Unsupported photo");
        File directory=new File(cache,"monogram-clipboard");
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache unavailable");
        File target=File.createTempFile("photo-","."+extension,directory);
        try(InputStream input=new FileInputStream(source);OutputStream output=new FileOutputStream(target)) {
            byte[] buffer=new byte[65536];int count;long copied=0;
            while((count=input.read(buffer))!=-1) { copied+=count;if(copied>FileTransfer.MAX_BYTES) throw new IOException("Photo changed");output.write(buffer,0,count); }
            if(copied!=size) throw new IOException("Photo changed");
        } catch(IOException failure) { target.delete();throw failure; }
        return target;
    }
    private static String extension(String name) { return name.substring(name.lastIndexOf('.')+1); }
    private static void trim(File current) {
        // Keep a short history for consumers that already received the previous clipboard URI.
        Utilities.globalQueue.postRunnable(() -> {
            File[] files=current.getParentFile().listFiles(file -> file.isFile() && file.getName().startsWith("photo-"));
            if(files==null) return;Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
            long now=System.currentTimeMillis();
            for(int i=0;i<files.length;i++) if(!files[i].equals(current) && (i>=8 || now-files[i].lastModified()>24L*60*60*1000)) files[i].delete();
        });
    }
    private static void error(BaseFragment fragment) {
        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.MonogramPhotoCopyFailed)).show();
    }
}
