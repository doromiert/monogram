// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.content.Context;
import com.negzero.monogram.xmpp.XmppMessage;
import com.negzero.monogram.xmpp.XmppSession;
import java.util.Map;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.UUID;

/** Local IDs adapt XMPP addresses and stanza IDs to Telegram's existing views and storage. */
final class MonogramStore extends SQLiteOpenHelper {
    MonogramStore(int account) { this(ApplicationLoader.applicationContext, "monogram-" + account + ".db"); }
    MonogramStore(Context context, String name) { super(context, name, null, 3); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer INTEGER NOT NULL, stanza TEXT NOT NULL, body TEXT NOT NULL, encrypted INTEGER NOT NULL, outgoing INTEGER NOT NULL, time INTEGER NOT NULL, UNIQUE(peer,stanza,outgoing))");
        addMessageMetadata(db);
    }
    private void addMessageMetadata(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN readable INTEGER NOT NULL DEFAULT 1");
        db.execSQL("ALTER TABLE messages ADD COLUMN time_ms INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE messages SET time_ms=time*1000");
        db.execSQL("ALTER TABLE messages ADD COLUMN archive_order INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX message_timeline ON messages(peer,time_ms,archive_order,id)");
        db.execSQL("CREATE TABLE message_ids (peer INTEGER NOT NULL, outgoing INTEGER NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, message INTEGER NOT NULL, PRIMARY KEY(peer,outgoing,kind,value))");
        db.execSQL("INSERT INTO message_ids SELECT peer,outgoing,'legacy-stanza',stanza,id FROM messages");
        db.execSQL("CREATE TABLE archive_state (scope TEXT PRIMARY KEY, oldest TEXT, newest TEXT, complete INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 3) addMessageMetadata(db);
        else if (oldVersion == 2 && newVersion == 3) db.execSQL("UPDATE message_ids SET kind='legacy-stanza' WHERE kind='stanza'");
        else throw new IllegalStateException("Unknown database migration");
    }
    synchronized long peer(String jid) {
        ContentValues values = new ContentValues(); values.put("jid", jid);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("peers", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        try (Cursor c = db.rawQuery("SELECT id FROM peers WHERE jid=?", new String[]{jid})) {
            if (!c.moveToFirst()) throw new IllegalStateException("Cannot persist JID");
            return c.getLong(0);
        }
    }
    synchronized String jid(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT jid FROM peers WHERE id=?", new String[]{Long.toString(id)})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("Unknown XMPP peer");
            return c.getString(0);
        }
    }
    synchronized void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try { db.delete("message_ids", null, null); db.delete("archive_state", null, null); db.delete("messages", null, null); db.delete("peers", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    TLRPC.User user(long id, long self) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id; user.first_name = jid(id); user.last_name = ""; user.phone = "";
        user.self = id == self; user.contact = id != self; user.flags = 2;
        user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        user.status = new TLRPC.TL_userStatusEmpty();
        return user;
    }
    synchronized int save(XmppMessage message) {
        long peer = peer(message.peer.toString());
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int existing = 0;
            for (Map.Entry<String, String> alias : message.ids.entrySet()) {
                try (Cursor c = db.rawQuery("SELECT message FROM message_ids WHERE peer=? AND outgoing=? AND kind=? AND value=?",
                        new String[]{Long.toString(peer), message.outgoing ? "1" : "0", alias.getKey(), aliasValue(message, alias)})) {
                    if (c.moveToFirst()) {
                        int matched = c.getInt(0);
                        if (existing != 0 && existing != matched) throw new IllegalStateException("Conflicting XMPP message IDs");
                        existing = matched;
                    }
                }
            }
            String legacy = message.ids.get("stanza");
            if (existing == 0 && legacy != null) {
                try (Cursor c = db.rawQuery("SELECT message FROM message_ids WHERE peer=? AND outgoing=? AND kind='legacy-stanza' AND value=?",
                        new String[]{Long.toString(peer), message.outgoing ? "1" : "0", legacy})) {
                    if (c.moveToFirst()) existing = c.getInt(0);
                }
            }
            ContentValues values = new ContentValues();
            values.put("body", message.readable ? message.body : "Encrypted message unavailable on this device");
            values.put("encrypted", message.encrypted); values.put("readable", message.readable);
            long seconds = message.timestamp / 1000;
            if (seconds < 0 || seconds > Integer.MAX_VALUE) throw new IllegalArgumentException("Unsupported message date");
            values.put("time", (int) seconds); values.put("time_ms", message.timestamp);
            boolean changed = existing == 0;
            int id = existing;
            if (existing == 0) {
                values.put("peer", peer); values.put("stanza", UUID.randomUUID().toString()); values.put("outgoing", message.outgoing);
                long inserted = db.insertOrThrow("messages", null, values);
                if (inserted > Integer.MAX_VALUE) throw new IllegalStateException("Local message ID space exhausted");
                id = (int) inserted;
            } else {
                try (Cursor c = db.rawQuery("SELECT readable,time_ms FROM messages WHERE id=?", new String[]{Integer.toString(existing)})) {
                    if (!c.moveToFirst()) throw new IllegalStateException("Missing XMPP message");
                    // Replaying OMEMO from MAM may fail after its ratchet key was consumed. Keep known plaintext.
                    if (c.getInt(0) == 0 && message.readable) {
                        db.update("messages", values, "id=?", new String[]{Integer.toString(existing)}); changed = true;
                    } else if (message.archived && c.getLong(1) != message.timestamp) {
                        ContentValues date = new ContentValues(); date.put("time", (int) seconds); date.put("time_ms", message.timestamp);
                        db.update("messages", date, "id=?", new String[]{Integer.toString(existing)}); changed = true;
                    }
                }
            }
            for (Map.Entry<String, String> alias : message.ids.entrySet()) {
                ContentValues identity = new ContentValues(); identity.put("peer", peer); identity.put("outgoing", message.outgoing);
                identity.put("kind", alias.getKey()); identity.put("value", aliasValue(message, alias)); identity.put("message", id);
                db.insertWithOnConflict("message_ids", null, identity, SQLiteDatabase.CONFLICT_IGNORE);
            }
            if (legacy != null) db.delete("message_ids", "kind='legacy-stanza' AND message=? AND value=?", new String[]{Integer.toString(id), legacy});
            db.setTransactionSuccessful();
            return changed ? id : 0;
        } finally { db.endTransaction(); }
    }
    private static String aliasValue(XmppMessage message, Map.Entry<String, String> alias) {
        // Client stanza IDs need only be unique within a stream, unlike origin/archive IDs.
        return alias.getKey().equals("stanza") ? message.sender + "\n" + alias.getValue() : alias.getValue();
    }
    synchronized void resetArchiveCursor() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("archive_state", null, null);
            db.execSQL("UPDATE messages SET archive_order=0");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized String archiveCursor(boolean oldest) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + (oldest ? "oldest" : "newest") + " FROM archive_state WHERE scope='direct'", null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
    synchronized boolean archiveComplete() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT complete FROM archive_state WHERE scope='direct'", null)) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }
    synchronized boolean saveArchive(XmppSession.ArchivePage page, boolean backwards) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            boolean changed = false;
            long order;
            try (Cursor c = db.rawQuery("SELECT " + (backwards ? "min" : "max") + "(archive_order) FROM messages", null)) {
                c.moveToFirst(); order = backwards ? Math.min(0, c.getLong(0)) - page.messages.size() : Math.max(0, c.getLong(0)) + 1;
            }
            for (XmppMessage message : page.messages) {
                changed |= save(message) != 0;
                db.execSQL("UPDATE messages SET archive_order=? WHERE archive_order=0 AND id=(SELECT message FROM message_ids WHERE peer=? AND outgoing=? AND kind='archive' AND value=?)",
                        new Object[]{order++, peer(message.peer.toString()), message.outgoing ? 1 : 0, message.ids.get("archive")});
            }
            ContentValues empty = new ContentValues(); empty.put("scope", "direct");
            db.insertWithOnConflict("archive_state", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues cursor = new ContentValues();
            if (backwards) {
                if (page.firstId != null) cursor.put("oldest", page.firstId);
                cursor.put("complete", page.complete || page.firstId == null);
            }
            if (archiveCursor(false) == null || !backwards) {
                if (page.lastId != null) cursor.put("newest", page.lastId);
            }
            if (cursor.size() > 0) db.update("archive_state", cursor, "scope='direct'", null);
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }
    TLRPC.Message message(int id, long peer, long self, String body, boolean outgoing, int time) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id; message.date = time; message.message = body; message.out = outgoing; message.unread = !outgoing;
        message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = outgoing ? peer : self;
        message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = outgoing ? self : peer;
        message.flags = 256; message.dialog_id = peer;
        message.media = new TLRPC.TL_messageMediaEmpty();
        return message;
    }
    synchronized ArrayList<TLRPC.Message> history(long peer, long self, int offset, int limit) {
        return history(peer, self, offset, limit, false);
    }
    synchronized ArrayList<TLRPC.Message> history(long peer, long self, int offset, int limit, boolean forward) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        // Local IDs reflect insertion, not chronology: an archive import may insert an older message later.
        String sql = "SELECT m.id,m.body,m.outgoing,m.time FROM messages m";
        ArrayList<String> args = new ArrayList<>();
        if (offset != 0) {
            sql += " JOIN messages a ON a.peer=m.peer AND a.id=?";
            args.add(Integer.toString(offset));
        }
        sql += " WHERE m.peer=?"; args.add(Long.toString(peer));
        if (offset != 0) {
            String compare = forward ? ">" : "<";
            sql += " AND (m.time_ms" + compare + "a.time_ms OR (m.time_ms=a.time_ms AND m.archive_order" + compare
                    + "a.archive_order) OR (m.time_ms=a.time_ms AND m.archive_order=a.archive_order AND m.id" + compare + "a.id))";
        }
        sql += forward ? " ORDER BY m.time_ms ASC,m.archive_order ASC,m.id ASC LIMIT ?"
                : " ORDER BY m.time_ms DESC,m.archive_order DESC,m.id DESC LIMIT ?";
        args.add(Integer.toString(Math.min(Math.max(limit, 1), 100)));
        try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                TLRPC.Message message = message(c.getInt(0), peer, self, c.getString(1), c.getInt(2) == 1, c.getInt(3));
                message.unread = false;
                result.add(message);
            }
        }
        if (forward) java.util.Collections.reverse(result);
        return result;
    }
    synchronized ArrayList<Long> peers() {
        ArrayList<Long> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM peers ORDER BY id", null)) {
            while (c.moveToNext()) result.add(c.getLong(0));
        }
        return result;
    }
}
