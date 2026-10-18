// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.FileShare;
import java.io.File;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

/** XEP-0449 marker projection using Telegram's existing document/sticker renderer. */
public final class MonogramStickers {
    private MonogramStickers() {}
    public static boolean isSticker(TLRPC.Document document) {
        if (document == null) return false;
        for (TLRPC.DocumentAttribute attribute : document.attributes)
            if (attribute instanceof TLRPC.TL_documentAttributeSticker || attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) return true;
        return false;
    }
    public static FileShare description(TLRPC.Message message, FileShare sharing) throws Exception {
        return sharing != null && message.media != null && isSticker(message.media.document) ? sharing.asSticker() : sharing;
    }
    public static void apply(TLRPC.Message message, FileShare sharing) {
        if (sharing == null || !sharing.sticker || !supported(sharing.mime)) return;
        project(message,sharing.name,sharing.mime,Math.max(0,sharing.size));
    }
    /** File-I/O worker only: persist validated TGS type once, never inflate it during history projection. */
    public static TLRPC.MessageMedia inspectDisplayHint(TLRPC.MessageMedia media,File file,boolean hint) {
        if(!hint || media==null || media.document==null) return media;
        // Generic metadata probing can infer MIME from a name; only successful inspection establishes TGS here.
        if("application/x-tgsticker".equals(media.document.mime_type)) media.document.mime_type="application/octet-stream";
        if(file==null || !file.isFile() || file.length()>20L*1024*1024) return media;
        try(java.io.InputStream input=new java.io.FileInputStream(file)) {
            if(input.read()!=31 || input.read()!=139) return media;
        } catch(java.io.IOException unavailable) { return media; }
        try {
            byte[] data=MonogramPackArchive.read(new java.io.FileInputStream(file),20*1024*1024,()->{});
            MonogramPacks.validateMedia(data,"application/x-tgsticker");
            media.document.mime_type="application/x-tgsticker";
        } catch(Exception invalid) { /* A hinted ordinary/invalid gzip remains a generic file. */ }
        return media;
    }
    /** Presentation only, after authenticated file download. No outer source/pack metadata is used. */
    public static void applyDisplayHint(TLRPC.Message message,File file) {
        if(file==null || !file.isFile()) return;
        byte[] header=new byte[12]; int count=0;
        try(java.io.InputStream input=new java.io.FileInputStream(file)) {
            while(count<header.length) { int n=input.read(header,count,header.length-count);if(n<0)break;count+=n; }
        } catch(java.io.IOException unavailable) { return; }
        String mime=null;
        if(count>=8 && (header[0]&255)==137 && header[1]=='P' && header[2]=='N' && header[3]=='G'
                && header[4]==13 && header[5]==10 && header[6]==26 && header[7]==10) mime="image/png";
        else if(count>=12 && header[0]=='R' && header[1]=='I' && header[2]=='F' && header[3]=='F'
                && header[8]=='W' && header[9]=='E' && header[10]=='B' && header[11]=='P') mime="image/webp";
        else if(count>=6 && header[0]=='G' && header[1]=='I' && header[2]=='F' && header[3]=='8' && (header[4]=='7' || header[4]=='9') && header[5]=='a') mime="image/gif";
        else if(count>=4 && (header[0]&255)==26 && (header[1]&255)==69 && (header[2]&255)==223 && (header[3]&255)==163) mime="video/webm";
        TLRPC.Document previous=message.media==null ? null : message.media.document;
        if(mime==null && count>=2 && (header[0]&255)==31 && (header[1]&255)==139 && previous!=null
                && "application/x-tgsticker".equals(previous.mime_type)) mime="application/x-tgsticker";
        if(mime==null) return;
        String name="attachment";
        if(previous!=null) for(TLRPC.DocumentAttribute attribute:previous.attributes)
            if(attribute instanceof TLRPC.TL_documentAttributeFilename && attribute.file_name!=null) name=attribute.file_name;
        project(message,name,mime,file.length());
    }
    private static void project(TLRPC.Message message,String name,String mime,long size) {
        TLRPC.Document previous = message.media == null ? null : message.media.document;
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = message.id; document.date = message.date; document.dc_id = Integer.MIN_VALUE;
        document.file_reference = new byte[0]; document.mime_type = mime; document.size = size;
        document.localPath = message.attachPath;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename(); filename.file_name = name;
        document.attributes.add(filename);
        TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
        sticker.alt = message.message == null ? "" : message.message; sticker.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        document.attributes.add(sticker);
        TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize(); dimensions.w = dimensions.h = 512;
        if (previous != null) for (TLRPC.DocumentAttribute attribute : previous.attributes)
            if (attribute.w > 0 && attribute.h > 0) { dimensions.w = attribute.w; dimensions.h = attribute.h; }
        if(previous==null && message.media!=null && message.media.photo!=null && message.attachPath!=null
                && !message.attachPath.isEmpty() && new File(message.attachPath).isFile()) {
            long largest=0;
            for(TLRPC.PhotoSize photo:message.media.photo.sizes) if(photo.w>0 && photo.h>0 && (long)photo.w*photo.h>largest) {
                largest=(long)photo.w*photo.h;dimensions.w=photo.w;dimensions.h=photo.h;
            }
        }
        document.attributes.add(dimensions);
        TLRPC.TL_photoSize thumb = new TLRPC.TL_photoSize(); thumb.type = "s"; thumb.w = dimensions.w; thumb.h = dimensions.h;
        thumb.size = (int) Math.min(Integer.MAX_VALUE, document.size); thumb.location = new TLRPC.TL_fileLocationToBeDeprecated();
        thumb.location.volume_id = MonogramAttachments.PHOTO_VOLUME; thumb.location.local_id = message.id; thumb.location.dc_id = Integer.MIN_VALUE;
        document.thumbs.add(thumb); document.flags |= 1;
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument(); media.flags = 1; media.document = document;
        message.media = media; message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
    }
    public static org.telegram.messenger.ImageLocation localThumbnail(TLRPC.PhotoSize thumb, TLRPC.Document document) {
        if (thumb == null || thumb.location == null || document == null || !isSticker(document)
                || document.localPath == null || document.localPath.isEmpty() || !new File(document.localPath).isFile()) return null;
        long volume = thumb.location.volume_id;
        if (!MonogramPacks.isThumb(volume) && !MonogramEmojis.isThumb(volume) && volume != MonogramAttachments.PHOTO_VOLUME) return null;
        org.telegram.messenger.ImageLocation local = org.telegram.messenger.ImageLocation.getForPath(document.localPath);
        if ("application/x-tgsticker".equals(document.mime_type)) local.imageType = org.telegram.messenger.FileLoader.IMAGE_TYPE_LOTTIE;
        else if ("video/webm".equals(document.mime_type) || "image/gif".equals(document.mime_type)) local.imageType = org.telegram.messenger.FileLoader.IMAGE_TYPE_ANIMATION;
        return local;
    }
    /** File probing may improve geometry without discarding a known private local sticker kind. */
    public static TLRPC.MessageMedia preserve(TLRPC.MessageMedia previous, TLRPC.MessageMedia decoded) {
        if (previous == null || previous.document == null || decoded == null || !isSticker(previous.document)
                || !supported(previous.document.mime_type)) return decoded;
        TLRPC.Document source = previous.document;
        if (decoded.document == null) {
            if (decoded.photo == null || !("image/png".equals(source.mime_type) || "image/webp".equals(source.mime_type))) return decoded;
            TLRPC.TL_document document = new TLRPC.TL_document(); document.id = source.id; document.date = source.date;
            document.dc_id = Integer.MIN_VALUE; document.file_reference = new byte[0]; document.mime_type = source.mime_type;
            document.size = source.size; document.localPath = source.localPath; document.thumbs.addAll(source.thumbs); document.flags = source.flags;
            TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument(); media.flags = 1; media.document = document; decoded = media;
        }
        preserveAttributes(source, decoded.document);
        return decoded;
    }
    static void preserveAttributes(TLRPC.Document source, TLRPC.Document decoded) {
        if (source == null || decoded == null || !isSticker(source)) return;
        for (TLRPC.DocumentAttribute old : source.attributes) {
            if (!(old instanceof TLRPC.TL_documentAttributeSticker) && !(old instanceof TLRPC.TL_documentAttributeCustomEmoji)
                    && !(old instanceof TLRPC.TL_documentAttributeImageSize) && !(old instanceof TLRPC.TL_documentAttributeFilename)) continue;
            boolean exists = false;
            for (TLRPC.DocumentAttribute current : decoded.attributes) if (current.getClass() == old.getClass()) { exists = true; break; }
            if (!exists) decoded.attributes.add(old);
        }
    }
    public static boolean supported(String mime) {
        return "image/gif".equals(mime) || "image/webp".equals(mime) || "image/png".equals(mime) || "application/x-tgsticker".equals(mime) || "video/webm".equals(mime);
    }
}
