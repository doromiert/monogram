// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.UUID;

/** Local IDs adapt XMPP addresses and stanza IDs to Telegram's existing views and storage. */
final class MonogramStore extends SQLiteOpenHelper {
    MonogramStore(int account) { super(ApplicationLoader.applicationContext, "monogram-" + account + ".db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer INTEGER NOT NULL, stanza TEXT NOT NULL, body TEXT NOT NULL, encrypted INTEGER NOT NULL, outgoing INTEGER NOT NULL, time INTEGER NOT NULL, UNIQUE(peer,stanza,outgoing))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unknown database migration"); }
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
        try { db.delete("messages", null, null); db.delete("peers", null, null); db.setTransactionSuccessful(); }
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
    synchronized int save(long peer, String stanza, String body, boolean encrypted, boolean outgoing, int time) {
        if (stanza == null) stanza = UUID.randomUUID().toString();
        ContentValues values = new ContentValues(); values.put("peer", peer); values.put("stanza", stanza);
        values.put("body", body); values.put("encrypted", encrypted); values.put("outgoing", outgoing); values.put("time", time);
        long id = getWritableDatabase().insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM messages WHERE peer=? AND stanza=? AND outgoing=?",
                    new String[]{Long.toString(peer), stanza, outgoing ? "1" : "0"})) {
                if (c.moveToFirst()) return 0;
            }
            throw new android.database.sqlite.SQLiteException("Cannot persist XMPP message");
        }
        if (id > Integer.MAX_VALUE) throw new IllegalStateException("Local message ID space exhausted");
        return (int) id;
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
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,body,outgoing,time FROM messages WHERE peer=? AND (?=0 OR id<?) ORDER BY id DESC LIMIT ?",
                new String[]{Long.toString(peer), Integer.toString(offset), Integer.toString(offset), Integer.toString(Math.min(Math.max(limit, 1), 100))})) {
            while (c.moveToNext()) result.add(message(c.getInt(0), peer, self, c.getString(1), c.getInt(2) == 1, c.getInt(3)));
        }
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
