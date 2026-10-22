// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;

/**
 * Account-owned, device-local quick reply templates for XMPP accounts.
 *
 * <p>XMPP has no equivalent of Telegram's business quick-reply RPCs.  These templates are
 * deliberately local: callers turn {@link Outgoing} snapshots into ordinary XMPP sends, so no
 * remote Telegram state or paid account identity is required.</p>
 */
public final class MonogramQuickReplies {
    private static final String PREFERENCES = "monogram-quick-replies";
    private static final String VERSION = "version";
    private static final String OWNER = "owner";
    private static final String NEXT_ID = "next_id";
    private static final String REPLIES = "replies";
    private static final int FORMAT_VERSION = 1;
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private MonogramQuickReplies() { }

    /** A detached template suitable for native list and editor projections. */
    public static final class Reply {
        public final int id;
        public final String name;
        public final int order;
        private final ArrayList<String> messages;

        private Reply(int id, String name, int order, ArrayList<String> messages) {
            this.id = id;
            this.name = name;
            this.order = order;
            this.messages = new ArrayList<>(messages);
        }

        public int messageCount() {
            return messages.size();
        }

        /** Returns newly decoded copies; callers may freely change their fields before sending. */
        public ArrayList<TLRPC.Message> messages() {
            return decodeAll(messages);
        }
    }

    /** A normal-send payload. It never contains a Telegram quick-reply RPC request. */
    public static final class Outgoing {
        public final int replyId;
        public final String shortcut;
        public final ArrayList<TLRPC.Message> messages;

        private Outgoing(Reply reply) {
            replyId = reply.id;
            shortcut = reply.name;
            messages = reply.messages();
        }
    }

    public static ArrayList<Reply> list(int account, String jid) {
        synchronized (LOCK) {
            return copy(read(account, jid));
        }
    }

    public static Reply find(int account, String jid, int id) {
        synchronized (LOCK) {
            for (Reply reply : read(account, jid)) if (reply.id == id) return copy(reply);
            return null;
        }
    }

    public static Reply find(int account, String jid, String name) {
        String key = nameKey(name);
        if (key == null) return null;
        synchronized (LOCK) {
            for (Reply reply : read(account, jid)) if (key.equals(nameKey(reply.name))) return copy(reply);
            return null;
        }
    }

    public static boolean nameTaken(int account, String jid, String name, int exceptId) {
        String key = nameKey(name);
        if (key == null) return false;
        synchronized (LOCK) {
            for (Reply reply : read(account, jid)) if (reply.id != exceptId && key.equals(nameKey(reply.name))) return true;
            return false;
        }
    }

    public static Reply create(int account, String jid, String name, ArrayList<TLRPC.Message> messages) {
        String normalized = requireName(name);
        ArrayList<String> encoded = encodeAll(messages);
        synchronized (LOCK) {
            ArrayList<Reply> replies = read(account, jid);
            if (containsName(replies, normalized, 0)) throw new IllegalArgumentException("Quick reply name already exists");
            int id = nextId(account, jid, replies);
            Reply reply = new Reply(id, normalized, replies.size(), encoded);
            replies.add(reply);
            write(account, jid, replies, id + 1);
            return copy(reply);
        }
    }

    public static Reply rename(int account, String jid, int id, String name) {
        String normalized = requireName(name);
        synchronized (LOCK) {
            ArrayList<Reply> replies = read(account, jid);
            int index = indexOf(replies, id);
            if (index < 0) throw new IllegalArgumentException("Quick reply does not exist");
            if (containsName(replies, normalized, id)) throw new IllegalArgumentException("Quick reply name already exists");
            Reply old = replies.get(index);
            Reply updated = new Reply(old.id, normalized, old.order, old.messages);
            replies.set(index, updated);
            write(account, jid, replies, nextStoredId(account, jid));
            return copy(updated);
        }
    }

    public static Reply replaceMessages(int account, String jid, int id, ArrayList<TLRPC.Message> messages) {
        ArrayList<String> encoded = encodeAll(messages);
        synchronized (LOCK) {
            ArrayList<Reply> replies = read(account, jid);
            int index = indexOf(replies, id);
            if (index < 0) throw new IllegalArgumentException("Quick reply does not exist");
            Reply old = replies.get(index);
            Reply updated = new Reply(old.id, old.name, old.order, encoded);
            replies.set(index, updated);
            write(account, jid, replies, nextStoredId(account, jid));
            return copy(updated);
        }
    }

    public static boolean delete(int account, String jid, int id) {
        synchronized (LOCK) {
            ArrayList<Reply> replies = read(account, jid);
            int index = indexOf(replies, id);
            if (index < 0) return false;
            replies.remove(index);
            normalizeOrder(replies);
            write(account, jid, replies, nextStoredId(account, jid));
            return true;
        }
    }

    /** Applies the requested order only when it is a complete permutation of this account's rows. */
    public static ArrayList<Reply> reorder(int account, String jid, ArrayList<Integer> orderedIds) {
        synchronized (LOCK) {
            ArrayList<Reply> replies = read(account, jid);
            if (orderedIds == null || orderedIds.size() != replies.size()) throw new IllegalArgumentException("Quick reply order is incomplete");
            HashSet<Integer> expected = new HashSet<>();
            for (Reply reply : replies) expected.add(reply.id);
            if (expected.size() != orderedIds.size() || !expected.containsAll(orderedIds)) throw new IllegalArgumentException("Quick reply order is invalid");
            ArrayList<Reply> ordered = new ArrayList<>();
            for (int id : orderedIds) ordered.add(replies.get(indexOf(replies, id)));
            normalizeOrder(ordered);
            write(account, jid, ordered, nextStoredId(account, jid));
            return copy(ordered);
        }
    }

    /** Returns detached ordinary-send messages, or {@code null} if the requested template is absent. */
    public static Outgoing outgoing(int account, String jid, int id) {
        Reply reply = find(account, jid, id);
        if (reply == null) return null;
        Outgoing outgoing = new Outgoing(reply);
        if (outgoing.messages.isEmpty()) throw new IllegalStateException("Quick reply has no sendable messages");
        return outgoing;
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String key(int account) {
        return "account-" + account;
    }

    private static ArrayList<Reply> read(int account, String jid) {
        if (jid == null || jid.trim().isEmpty()) return new ArrayList<>();
        String raw = preferences().getString(key(account), null);
        if (raw == null) return new ArrayList<>();
        try {
            JSONObject state = new JSONObject(raw);
            if (state.optInt(VERSION) != FORMAT_VERSION || !jid.equals(state.optString(OWNER))) return new ArrayList<>();
            JSONArray entries = state.optJSONArray(REPLIES);
            if (entries == null) return new ArrayList<>();
            ArrayList<Reply> replies = new ArrayList<>();
            HashSet<Integer> ids = new HashSet<>();
            HashSet<String> names = new HashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                int id = entry.optInt("id", 0);
                String name = nameKey(entry.optString("name", null));
                JSONArray encoded = entry.optJSONArray("messages");
                if (id <= 0 || name == null || !ids.add(id) || !names.add(name) || encoded == null) continue;
                ArrayList<String> messages = new ArrayList<>();
                for (int m = 0; m < encoded.length(); m++) {
                    String value = encoded.optString(m, null);
                    if (decode(value) != null) messages.add(value);
                }
                replies.add(new Reply(id, entry.optString("name").trim(), entry.optInt("order", i), messages));
            }
            Collections.sort(replies, Comparator.comparingInt(reply -> reply.order));
            normalizeOrder(replies);
            return replies;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static void write(int account, String jid, ArrayList<Reply> replies, int nextId) {
        if (jid == null || jid.trim().isEmpty()) throw new IllegalArgumentException("XMPP account is not connected");
        normalizeOrder(replies);
        try {
            JSONArray entries = new JSONArray();
            for (Reply reply : replies) {
                JSONArray messages = new JSONArray();
                for (String message : reply.messages) messages.put(message);
                entries.put(new JSONObject().put("id", reply.id).put("name", reply.name).put("order", reply.order).put("messages", messages));
            }
            JSONObject state = new JSONObject().put(VERSION, FORMAT_VERSION).put(OWNER, jid).put(NEXT_ID, Math.max(1, nextId)).put(REPLIES, entries);
            if (!preferences().edit().putString(key(account), state.toString()).commit()) throw new IllegalStateException("Could not save quick replies");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Could not save quick replies", error);
        }
    }

    private static int nextId(int account, String jid, ArrayList<Reply> replies) {
        int next = nextStoredId(account, jid);
        HashSet<Integer> used = new HashSet<>();
        for (Reply reply : replies) used.add(reply.id);
        while (next <= 0 || used.contains(next)) {
            next = RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1;
        }
        return next;
    }

    private static int nextStoredId(int account, String jid) {
        try {
            String raw = preferences().getString(key(account), null);
            if (raw != null) {
                JSONObject state = new JSONObject(raw);
                if (jid.equals(state.optString(OWNER))) return Math.max(1, state.optInt(NEXT_ID, 1));
            }
        } catch (Exception ignored) { }
        return 1;
    }

    private static int indexOf(ArrayList<Reply> replies, int id) {
        for (int i = 0; i < replies.size(); i++) if (replies.get(i).id == id) return i;
        return -1;
    }

    private static boolean containsName(ArrayList<Reply> replies, String name, int exceptId) {
        String key = nameKey(name);
        for (Reply reply : replies) if (reply.id != exceptId && key.equals(nameKey(reply.name))) return true;
        return false;
    }

    private static void normalizeOrder(ArrayList<Reply> replies) {
        for (int i = 0; i < replies.size(); i++) {
            Reply reply = replies.get(i);
            if (reply.order != i) replies.set(i, new Reply(reply.id, reply.name, i, reply.messages));
        }
    }

    private static ArrayList<Reply> copy(ArrayList<Reply> values) {
        ArrayList<Reply> copy = new ArrayList<>();
        for (Reply value : values) copy.add(copy(value));
        return copy;
    }

    private static Reply copy(Reply value) {
        return new Reply(value.id, value.name, value.order, value.messages);
    }

    private static String requireName(String name) {
        String normalized = name == null ? null : name.trim();
        if (normalized == null || normalized.isEmpty()) throw new IllegalArgumentException("Quick reply name is required");
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) throw new IllegalArgumentException("Quick reply name is invalid");
        return normalized;
    }

    private static String nameKey(String name) {
        if (name == null) return null;
        String normalized = name.trim();
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static ArrayList<String> encodeAll(ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("Quick reply needs at least one message");
        ArrayList<String> encoded = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            String value = encode(message);
            if (value == null) throw new IllegalArgumentException("Quick reply contains an unsupported message");
            encoded.add(value);
        }
        return encoded;
    }

    private static String encode(TLRPC.Message message) {
        if (message == null) return null;
        SerializedData data = new SerializedData();
        try {
            message.serializeToStream(data);
            return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
            return null;
        } finally {
            data.cleanup();
        }
    }

    private static ArrayList<TLRPC.Message> decodeAll(ArrayList<String> messages) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        for (String encoded : messages) {
            TLRPC.Message message = decode(encoded);
            if (message != null) result.add(message);
        }
        return result;
    }

    private static TLRPC.Message decode(String value) {
        if (value == null || value.isEmpty()) return null;
        SerializedData data = null;
        try {
            data = new SerializedData(Base64.decode(value, Base64.DEFAULT));
            return TLRPC.Message.TLdeserialize(data, data.readInt32(true), true);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (data != null) data.cleanup();
        }
    }
}
