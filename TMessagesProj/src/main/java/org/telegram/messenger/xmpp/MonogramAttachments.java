// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.graphics.BitmapFactory;
import com.negzero.monogram.xmpp.FileLink;
import java.io.File;
import java.security.MessageDigest;
import java.util.HashMap;
import org.telegram.tgnet.TLRPC;

public final class MonogramAttachments {
    public static final long PHOTO_VOLUME = Long.MIN_VALUE + 100;
    public static final String PARAM = "monogramAttachment";
    private MonogramAttachments() {}
    public static boolean has(TLRPC.Message message) {
        return message != null && message.params != null && message.params.containsKey(PARAM);
    }
    /** Android may otherwise linkify the host/fragment of an aesgcm URI as a browser URL. */
    public static void suppressEncryptedLinks(CharSequence text) {
        if (!(text instanceof android.text.Spannable)) return;
        android.text.Spannable spans = (android.text.Spannable) text;
        java.util.regex.Matcher links = java.util.regex.Pattern.compile("(?i)aesgcm://\\S+").matcher(text);
        while (links.find()) {
            for (android.text.style.URLSpan link : spans.getSpans(links.start(), links.end(), android.text.style.URLSpan.class)) spans.removeSpan(link);
        }
    }
    static File cache(Context context, String database, String url) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
        StringBuilder name = new StringBuilder();
        for (byte b : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return new File(context.getFilesDir(), "monogram-attachments/" + database + "/" + name);
    }
    static void apply(TLRPC.Message message, FileLink link, File file, TLRPC.MessageMedia saved, String caption, String name) {
        if (message.params == null) message.params = new HashMap<>();
        message.params.put(PARAM, link.wireUrl); message.message = caption == null ? "" : caption; message.entities.clear();
        message.flags &= ~TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        message.attachPath = file.getAbsolutePath();
        message.media = saved != null && !(saved.photo != null && isGif(file)) ? saved : media(message.id, message.date, file, name);
        if (message.media.document != null && file.isFile()) message.media.document.localPath = file.getAbsolutePath();
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
    }
    static TLRPC.MessageMedia media(int id, int date, File file, String name) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        if (file.isFile()) BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        boolean gif = isGif(file);
        if (!gif && bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 12000 && bounds.outHeight <= 12000
                && (long) bounds.outWidth * bounds.outHeight <= 50000000) {
            return photo(id, date, bounds.outWidth, bounds.outHeight, file.length());
        }
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument(); media.flags = 1;
        TLRPC.TL_document document = new TLRPC.TL_document(); document.id = id; document.date = date;
        document.dc_id = Integer.MIN_VALUE; document.file_reference = new byte[0];
        document.mime_type = gif ? "image/gif" : file.isFile() ? java.net.URLConnection.guessContentTypeFromName(name) : null;
        if (document.mime_type == null) document.mime_type = "application/octet-stream";
        document.size = file.isFile() ? file.length() : 0;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename(); filename.file_name = name;
        document.attributes.add(filename);
        if (gif) {
            TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize();
            dimensions.w = Math.max(1, bounds.outWidth); dimensions.h = Math.max(1, bounds.outHeight);
            document.attributes.add(dimensions); document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        }
        media.document = document;
        return media;
    }
    private static boolean isGif(File file) {
        if (!file.isFile()) return false;
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            byte[] header = new byte[6];
            return input.read(header) == 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                    && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a';
        } catch (java.io.IOException unavailable) { return false; }
    }
    private static TLRPC.MessageMedia photo(int id, int date, int width, int height, long length) {
        TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto(); media.flags = 1;
        TLRPC.TL_photo photo = new TLRPC.TL_photo(); photo.id = id; photo.date = date; photo.file_reference = new byte[0];
        TLRPC.TL_photoSize_layer127 size = new TLRPC.TL_photoSize_layer127();
        size.type = "x"; size.w = width; size.h = height; size.size = (int) Math.min(Integer.MAX_VALUE, Math.max(0, length));
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        size.location.volume_id = PHOTO_VOLUME; size.location.local_id = id; size.location.dc_id = Integer.MIN_VALUE;
        size.location.file_reference = new byte[0]; photo.sizes.add(size); media.photo = photo;
        return media;
    }
    /** Native remote placeholders and DownloadController metadata; image pixels require verified local bytes. */
    public static TLRPC.MessageMedia remoteMedia(int id, String name, String contentType, long size) {
        String mime = contentType == null ? "application/octet-stream" : contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (mime.equals("application/octet-stream") || mime.equals("binary/octet-stream")) {
            int dot = name.lastIndexOf('.');
            String inferred = dot < 0 ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
            if (inferred != null) mime = inferred;
        }
        if (mime.startsWith("image/") && !mime.equals("image/gif")) return photo(id, 0, 1, 1, size);
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument(); media.flags = 1;
        media.document = new TLRPC.TL_document(); media.document.id = id; media.document.mime_type = mime; media.document.size = Math.max(0, size);
        media.document.dc_id = Integer.MIN_VALUE; media.document.file_reference = new byte[0];
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename(); filename.file_name = name;
        media.document.attributes.add(filename);
        if (mime.equals("image/gif")) media.document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        else if (mime.startsWith("video/")) media.document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        else if (mime.startsWith("audio/")) media.document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        return media;
    }
}
