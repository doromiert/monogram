// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

/** Device-only room presentation, isolated by database, account JID and room JID. */
public final class MonogramGroupAppearance {
    private MonogramGroupAppearance() {}
    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-group-appearance", Context.MODE_PRIVATE);
    }
    private static String key(String database, String owner, String room) throws Exception {
        if (owner == null || owner.isEmpty() || room == null || room.isEmpty() || room.indexOf('/') >= 0) throw new IllegalArgumentException();
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest((database + "\n" + owner + "\n" + room).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    public static boolean save(int account, String expectedOwner, long generation, String room, long dialog,
            TLRPC.Chat chat, int name, int profile, long nameEmoji, long profileEmoji, TLRPC.EmojiStatus status) {
        if (dialog >= 0 || chat == null || chat.id != -dialog || name < 0 || name > 6 || profile < -1 || profile > 7) return false;
        try {
            MonogramController controller = MonogramController.get(account);
            if (!current(controller, expectedOwner, generation, room, dialog)) return false;
            if (!MonogramUserColors.validEmoji(account, nameEmoji) || !MonogramUserColors.validEmoji(account, profileEmoji)) return false;
            long statusEmoji = 0;
            int until = 0;
            if (status != null && !(status instanceof TLRPC.TL_emojiStatusEmpty)) {
                if (!(status instanceof TLRPC.TL_emojiStatus)) return false;
                statusEmoji = ((TLRPC.TL_emojiStatus) status).document_id;
                until = status.until;
                if (until < 0 || !MonogramUserColors.validEmoji(account, statusEmoji)) return false;
            }
            JSONObject value = new JSONObject().put("name", name).put("profile", profile)
                    .put("nameEmoji", nameEmoji).put("profileEmoji", profileEmoji).put("statusEmoji", statusEmoji).put("until", until);
            if (!current(controller, expectedOwner, generation, room, dialog)) return false;
            String database = "monogram-" + account + ".db";
            if (!preferences().edit().putString(key(database, expectedOwner, room), value.toString()).commit()) return false;
            if (!current(controller, expectedOwner, generation, room, dialog)) return false;
            project(database, expectedOwner, room, chat);
            return true;
        } catch (Exception failed) {
            FileLog.e("Could not save local room appearance");
            return false;
        }
    }
    private static boolean current(MonogramController controller, String owner, long generation, String room, long dialog) {
        return owner != null && !owner.isEmpty() && owner.equals(controller.accountJid())
                && generation == controller.accountGeneration() && room != null && room.equals(controller.jid(dialog));
    }
    /** No controller or pack-cache initialization while the room store is opening. */
    public static void project(String database, String owner, String room, TLRPC.Chat chat) {
        if (chat == null) return;
        try {
            String raw = preferences().getString(key(database, owner, room), null);
            if (raw == null || raw.length() > 16384) return;
            JSONObject value = new JSONObject(raw);
            int name = value.getInt("name"), profile = value.getInt("profile");
            if (name < 0 || name > 6 || profile < -1 || profile > 7) return;
            chat.color = color(name, value.optLong("nameEmoji"));
            chat.profile_color = color(profile, value.optLong("profileEmoji"));
            chat.flags2 |= 128 | 256;
            long statusEmoji = value.optLong("statusEmoji");
            int until = value.optInt("until");
            chat.emoji_status = new TLRPC.TL_emojiStatusEmpty();
            chat.flags2 &= ~512;
            if (MonogramPacks.localId(statusEmoji) && (until == 0 || until > System.currentTimeMillis() / 1000L)) {
                TLRPC.TL_emojiStatus status = new TLRPC.TL_emojiStatus();
                status.document_id = statusEmoji;
                status.until = until;
                if (until != 0) status.flags |= 1;
                chat.emoji_status = status;
                chat.flags2 |= 512;
            }
        } catch (Exception invalid) { FileLog.e("Could not restore local room appearance"); }
    }
    private static TLRPC.TL_peerColor color(int id, long emoji) {
        TLRPC.TL_peerColor value = new TLRPC.TL_peerColor();
        if (id >= 0) { value.flags |= 1; value.color = id; }
        if (MonogramPacks.localId(emoji)) { value.flags |= 2; value.background_emoji_id = emoji; }
        return value;
    }
}
