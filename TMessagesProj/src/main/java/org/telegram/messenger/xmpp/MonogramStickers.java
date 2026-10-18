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
        TLRPC.Document previous = message.media == null ? null : message.media.document;
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = message.id; document.date = message.date; document.dc_id = Integer.MIN_VALUE;
        document.file_reference = new byte[0]; document.mime_type = sharing.mime; document.size = Math.max(0, sharing.size);
        document.localPath = message.attachPath;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename(); filename.file_name = sharing.name;
        document.attributes.add(filename);
        TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
        sticker.alt = message.message == null ? "" : message.message; sticker.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        document.attributes.add(sticker);
        TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize(); dimensions.w = dimensions.h = 512;
        if (previous != null) for (TLRPC.DocumentAttribute attribute : previous.attributes)
            if (attribute.w > 0 && attribute.h > 0) { dimensions.w = attribute.w; dimensions.h = attribute.h; }
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
        else if ("video/webm".equals(document.mime_type)) local.imageType = org.telegram.messenger.FileLoader.IMAGE_TYPE_ANIMATION;
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
        return "image/webp".equals(mime) || "image/png".equals(mime) || "application/x-tgsticker".equals(mime) || "video/webm".equals(mime);
    }
}
