// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

/** Local metadata only: never fetches media or includes attachment URLs, keys or message bodies. */
public final class MonogramMessageInfo {
    private MonogramMessageInfo() {}
    public static final class Entry {
        public static final int COPY=0,OPEN_FILE=1,OPEN_LOCATION=2;
        public final int icon,action;
        public final String title,value,mime;
        public Entry(int icon,String title,String value) { this(icon,title,value,COPY,null); }
        public Entry(int icon,String title,String value,int action,String mime) { this.icon=icon;this.title=title;this.value=value;this.action=action;this.mime=mime; }
    }
    public static void load(int account,MessageObject message,Consumer<List<Entry>> ready) {
        load(account,message,() -> true,ready);
    }
    public static void load(int account,MessageObject message,java.util.function.BooleanSupplier current,Consumer<List<Entry>> ready) {
        if(message==null || message.messageOwner==null || message.currentAccount!=account) return;
        MonogramController controller=MonogramController.get(account);long generation=controller.accountGeneration();
        controller.messageMetadata(message.getId(),metadata -> Utilities.globalQueue.postRunnable(() -> {
            if(controller.accountGeneration()!=generation || !current.getAsBoolean()) return;
            List<Entry> values;
            try { values=collect(account,message,metadata); }
            catch(RuntimeException | LinkageError unavailable) { values=java.util.Collections.singletonList(new Entry(R.drawable.msg_info,LocaleController.getString(R.string.MonogramMessageDetails),LocaleController.getString(R.string.ErrorOccurred))); }
            final List<Entry> entries=values;
            AndroidUtilities.runOnUIThread(() -> { if(controller.accountGeneration()==generation && current.getAsBoolean()) ready.accept(entries); });
        }));
    }
    static List<Entry> collect(int account,MessageObject message,LinkedHashMap<String,String> metadata) {
        ArrayList<Entry> result=new ArrayList<>();
        add(result,R.drawable.msg_info,R.string.MonogramDetailsLocalId,Integer.toString(message.getId()));
        if(message.messageOwner.date>0) add(result,R.drawable.msg_calendar2,R.string.MonogramDetailsDate,date(message.messageOwner.date));
        if(message.messageOwner.fwd_from!=null && message.messageOwner.fwd_from.date>0)
            add(result,R.drawable.msg_forward,R.string.MonogramDetailsForwardedDate,date(message.messageOwner.fwd_from.date));
        if(message.messageOwner.edit_date>0 && message.messageOwner.edit_date!=message.messageOwner.date && !message.messageOwner.edit_hide)
            add(result,R.drawable.msg_edit,R.string.MonogramDetailsEditedDate,date(message.messageOwner.edit_date));
        add(result,R.drawable.msg_user_search,R.string.MonogramDetailsSender,metadata.get("sender"));
        add(result,R.drawable.msg_message,R.string.MonogramDetailsAddress,metadata.get("peer"));
        add(result,R.drawable.msg_secret,R.string.MonogramDetailsEncryption,metadata.get("encryption"));
        for(Map.Entry<String,String> id:metadata.entrySet()) if(id.getKey().startsWith("id:"))
            result.add(new Entry(R.drawable.msg_info,"XMPP · "+id.getKey().substring(3),id.getValue()));
        String mime=message.getMimeType();TLRPC.Document document=message.getDocument();
        File file=localFile(account,message);
        boolean image=message.isPhoto() || message.isSticker() || mime!=null && mime.startsWith("image/");
        BitmapFactory.Options bounds=null;
        if(image && file!=null) {
            bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
            if(bounds.outMimeType!=null && bounds.outMimeType.startsWith("image/")) mime=bounds.outMimeType;
        }
        add(result,R.drawable.msg_media,R.string.MonogramDetailsMime,mime);
        if(document!=null) for(TLRPC.DocumentAttribute attribute:document.attributes)
            if(attribute instanceof TLRPC.TL_documentAttributeFilename) add(result,R.drawable.msg_log,R.string.MonogramDetailsFileName,attribute.file_name);
        long bytes=message.getSize();if(bytes<=0 && file!=null) bytes=file.length();
        if(bytes>0) add(result,R.drawable.msg_sendfile,R.string.MonogramDetailsFileSize,AndroidUtilities.formatFileSize(bytes));
        int width=0,height=0;
        if(document!=null) for(TLRPC.DocumentAttribute attribute:document.attributes)
            if(attribute.w>0 && attribute.h>0) { width=attribute.w;height=attribute.h;break; }
        boolean video=message.isVideo() || message.isRoundVideo() || message.isVideoSticker() || message.isGif();
        if(image && file!=null) {
            try {
                if(bounds!=null && bounds.outWidth>0 && bounds.outHeight>0) { width=bounds.outWidth;height=bounds.outHeight; }
                ExifInterface exif=new ExifInterface(file);
                add(result,R.drawable.menu_devices,R.string.MonogramDetailsSoftware,exif.getAttribute(ExifInterface.TAG_SOFTWARE));
                double[] coordinates=exif.getLatLong();
                if(coordinates!=null && Math.abs(coordinates[0])<=90 && Math.abs(coordinates[1])<=180)
                    result.add(new Entry(R.drawable.msg_location,LocaleController.getString(R.string.MonogramDetailsLocation),coordinates[0]+", "+coordinates[1],Entry.OPEN_LOCATION,null));
            } catch(Exception ignored) { /* Most delivered images intentionally contain no EXIF. */ }
        }
        if(file!=null && (video || message.isMusic() || message.isVoice())) {
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                String bitrate=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                if(bitrate!=null) { long rate=Long.parseLong(bitrate);if(rate>0) add(result,R.drawable.msg_noise_on,R.string.MonogramDetailsBitrate,rate/1000+" Kbps"); }
                if(width<=0 || height<=0) {
                    String w=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),h=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if(w!=null && h!=null) { width=Integer.parseInt(w);height=Integer.parseInt(h); }
                }
            } catch(Exception ignored) { /* Incomplete/unsupported cached media still has its native metadata. */ }
            finally { try { retriever.release(); } catch(Exception ignored) {} }
        }
        if(width>0 && height>0) add(result,R.drawable.media_crop,R.string.MonogramDetailsResolution,width+" × "+height);
        int duration=(int)message.getDuration();
        if(duration>0) add(result,R.drawable.msg2_animations,R.string.MonogramDetailsDuration,AndroidUtilities.formatShortDuration(duration));
        if(file!=null) result.add(new Entry(R.drawable.msg_folders,LocaleController.getString(R.string.MonogramDetailsFilePath),file.getAbsolutePath(),Entry.OPEN_FILE,mime));
        return result;
    }
    private static String date(int timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.MEDIUM,LocaleController.getInstance().getCurrentLocale()).format(new Date(timestamp*1000L));
    }
    private static void add(List<Entry> entries,int icon,int title,String value) {
        if(value!=null && !value.isEmpty()) entries.add(new Entry(icon,LocaleController.getString(title),value));
    }
    public static File localFile(int account,MessageObject message) {
        if(message==null || message.messageOwner==null || message.currentAccount!=account) return null;
        String path=message.messageOwner.attachPath;
        File file=path==null || path.isEmpty() ? null : new File(path);
        if(file!=null && file.isFile() && file.length()>0) return file;
        file=FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        return file!=null && file.isFile() && file.length()>0 ? file : null;
    }
}
