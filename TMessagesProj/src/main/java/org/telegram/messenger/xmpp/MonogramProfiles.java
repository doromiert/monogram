// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.negzero.monogram.xmpp.XmppProfile;
import com.negzero.monogram.xmpp.XmppProfiles;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.telegram.tgnet.TLRPC;

/** Translate verified cached XMPP profile data into the upstream profile model. */
public final class MonogramProfiles {
    public static final long PHOTO_VOLUME = Long.MIN_VALUE + 101;
    private MonogramProfiles() {}
    static byte[] png(byte[] encoded) throws Exception {
        if (encoded.length == 0) return encoded;
        if (encoded.length > XmppProfile.MAX_AVATAR_BYTES) throw new IllegalArgumentException("Avatar too large");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096
                || (long) bounds.outWidth * bounds.outHeight > 16 * 1024 * 1024) throw new IllegalArgumentException("Invalid avatar dimensions");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 512 || bounds.outHeight / options.inSampleSize > 512) {
            options.inSampleSize = options.inSampleSize < 1 ? 2 : options.inSampleSize * 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (bitmap == null) throw new IllegalArgumentException("Invalid avatar");
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("Avatar encoding failed");
            byte[] png = out.toByteArray();
            if (png.length > XmppProfile.MAX_AVATAR_BYTES) throw new IllegalArgumentException("Avatar too large");
            return png;
        } finally { bitmap.recycle(); }
    }
    static File cache(android.content.Context context, String database, byte[] png) throws Exception {
        File directory = new File(context.getFilesDir(), "monogram-profiles/" + database);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create profile cache");
        File file = new File(directory, XmppProfiles.sha1(png) + ".png");
        if (!file.isFile()) {
            File part = File.createTempFile("avatar-", ".part", directory);
            try {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) { out.write(png); }
                if (!part.renameTo(file)) throw new java.io.IOException("Cannot save avatar");
            } finally { part.delete(); }
        }
        return file;
    }
    public static org.telegram.messenger.ImageLocation imageLocation(TLRPC.User user, File avatar) {
        if (user == null || user.photo == null || avatar == null || !avatar.isFile()) return null;
        org.telegram.messenger.ImageLocation image = org.telegram.messenger.ImageLocation.getForPath(avatar.getAbsolutePath());
        // The native profile gallery also needs the location and photo ID for comparisons.
        image.location = location(user.id);
        image.photoId = user.photo.photo_id;
        image.dc_id = Integer.MIN_VALUE;
        image.currentSize = avatar.length();
        return image;
    }
    public static org.telegram.messenger.ImageLocation imageLocation(TLRPC.Chat chat, File avatar) {
        if(chat==null || chat.photo==null || avatar==null || !avatar.isFile()) return null;
        org.telegram.messenger.ImageLocation image=org.telegram.messenger.ImageLocation.getForPath(avatar.getAbsolutePath());
        image.location=location(-chat.id); image.photoId=chat.photo.photo_id; image.dc_id=Integer.MIN_VALUE; image.currentSize=avatar.length();
        return image;
    }
    static TLRPC.TL_fileLocationToBeDeprecated location(long peer) {
        if (peer == 0 || peer > Integer.MAX_VALUE || peer < -Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid profile ID");
        TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
        location.volume_id = PHOTO_VOLUME; location.local_id = (int) peer; location.dc_id = Integer.MIN_VALUE;
        return location;
    }
    static TLRPC.Photo photo(long peer, File avatar) {
        if (avatar == null || !avatar.isFile()) return new TLRPC.TL_photoEmpty();
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = Long.parseLong(avatar.getName().substring(0, 15), 16) + 1; photo.dc_id = Integer.MIN_VALUE; photo.file_reference = new byte[0];
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(avatar.getAbsolutePath(), bounds);
        // Preserve the local file reference in Telegram's serialized gallery cache as well.
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize_layer127(); size.type = "x"; size.w = bounds.outWidth; size.h = bounds.outHeight;
        size.size = (int) avatar.length(); size.location = location(peer); photo.sizes.add(size);
        return photo;
    }
    public static Map<String, String> details(XmppProfile profile, String jid) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("XMPP ID", jid);
        if (profile == null) return fields;
        add(fields, "Nickname", profile.nickname); add(fields, "Full name", profile.field("FN"));
        add(fields, "Website", profile.field("URL")); add(fields, "Birthday", profile.field("BDAY"));
        add(fields, "Organization", profile.field("ORG", "ORGNAME")); add(fields, "Department", profile.field("ORG", "ORGUNIT"));
        add(fields, "Title", profile.field("TITLE")); add(fields, "Role", profile.field("ROLE"));
        int email = 0, phone = 0, address = 0;
        for (org.jivesoftware.smack.packet.StandardExtensionElement element : profileElements(profile)) {
            String name = element.getElementName();
            if (!XmppProfile.VCARD.equals(element.getNamespace())) continue;
            if ("EMAIL".equals(name)) add(fields, "Email " + ++email, value(element, "USERID"));
            else if ("TEL".equals(name)) add(fields, "Phone " + ++phone, value(element, "NUMBER"));
            else if ("ADR".equals(name)) {
                StringBuilder text = new StringBuilder();
                for (String part : new String[]{"POBOX", "EXTADR", "STREET", "LOCALITY", "REGION", "PCODE", "CTRY"}) {
                    String value = value(element, part);
                    if (!value.isEmpty()) { if (text.length() > 0) text.append(", "); text.append(value); }
                }
                add(fields, "Address " + ++address, text.toString());
            }
        }
        return fields;
    }
    private static java.util.List<org.jivesoftware.smack.packet.StandardExtensionElement> profileElements(XmppProfile profile) {
        try { return org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE.parse(
                org.jivesoftware.smack.util.PacketParserUtils.getParserFor(profile.xml())).getElements(); }
        catch (Exception invalid) { return java.util.Collections.emptyList(); }
    }
    private static String value(org.jivesoftware.smack.packet.StandardExtensionElement element, String key) {
        org.jivesoftware.smack.packet.StandardExtensionElement child = element.getFirstElement(key, XmppProfile.VCARD);
        return child == null || child.getText() == null ? "" : child.getText();
    }
    private static void add(Map<String, String> fields, String name, String value) { if (value != null && !value.trim().isEmpty()) fields.put(name, value); }
}
