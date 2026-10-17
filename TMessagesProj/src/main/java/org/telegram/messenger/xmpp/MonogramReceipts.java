// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import org.telegram.tgnet.TLRPC;

public final class MonogramReceipts {
    private static final String INCOMING_UNREAD = "monogram-incoming-unread";
    private static final String DISPLAYED = "monogram-displayed", DELIVERED = "monogram-delivered";
    private final MonogramStore store;
    MonogramReceipts(MonogramStore store) { this.store = store; }
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS message_receipts (message INTEGER PRIMARY KEY, delivered INTEGER NOT NULL DEFAULT 0, displayed INTEGER NOT NULL DEFAULT 0)");
    }
    /** Native Telegram read maxima are numeric; XMPP local IDs do not encode chronology. */
    public static boolean isUnread(TLRPC.Message message) {
        if (!message.out && message.params != null && message.params.containsKey(INCOMING_UNREAD))
            return "true".equals(message.params.get(INCOMING_UNREAD));
        return message.out && message.params != null && message.params.containsKey(DISPLAYED)
                ? !"true".equals(message.params.get(DISPLAYED)) : message.unread;
    }
    public static boolean hasIncomingState(TLRPC.Message message) {
        return message != null && !message.out && message.params != null && message.params.containsKey(INCOMING_UNREAD);
    }
    /** Reuse Telegram's persisted per-message flags, without treating a history fetch as a read. */
    public static void restoreIncoming(TLRPC.Message message, int flags) {
        if (message.out) return;
        message.unread = (flags & 1) == 0;
        message.media_unread = (flags & 2) == 0;
        if (message.params == null) message.params = new java.util.HashMap<>();
        message.params.put(INCOMING_UNREAD, Boolean.toString(message.unread));
    }
    public static void markRead(TLRPC.Message message) {
        message.unread = false;
        if (!message.out && message.params != null && message.params.containsKey(INCOMING_UNREAD))
            message.params.put(INCOMING_UNREAD, "false");
    }
    static void pending(TLRPC.Message message) {
        if (message.params == null) message.params = new java.util.HashMap<>();
        message.params.put(DISPLAYED, "false"); message.params.put(DELIVERED, "false"); message.unread = true;
    }
    void decorate(TLRPC.Message message) {
        pending(message);
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT COALESCE(r.delivered,0),EXISTS(SELECT 1 FROM messages a JOIN message_receipts s ON s.message=a.id "
                + "WHERE a.peer=m.peer AND a.outgoing=1 AND s.displayed=1 AND (a.time_ms>m.time_ms OR (a.time_ms=m.time_ms AND a.archive_order>m.archive_order) "
                + "OR (a.time_ms=m.time_ms AND a.archive_order=m.archive_order AND a.id>=m.id))) "
                + "FROM messages m LEFT JOIN message_receipts r ON r.message=m.id WHERE m.id=?", new String[]{Integer.toString(message.id)})) {
            if (c.moveToFirst()) {
                message.params.put(DELIVERED, Boolean.toString(c.getInt(0) != 0 || c.getInt(1) != 0));
                message.params.put(DISPLAYED, Boolean.toString(c.getInt(1) != 0));
                message.unread = c.getInt(1) == 0;
            }
        }
    }
    ArrayList<Integer> receive(long peer, String wire, boolean displayed) {
        ArrayList<Integer> changed = new ArrayList<>();
        if (peer <= 0 || wire == null || wire.isEmpty() || wire.length() > 4096) return changed;
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            int anchor; long time, order;
            try (Cursor c = db.rawQuery("SELECT DISTINCT m.id,m.time_ms,m.archive_order FROM message_ids i JOIN messages m ON m.id=i.message "
                    + "WHERE i.peer=? AND m.peer=i.peer AND i.outgoing=1 AND m.outgoing=1 AND m.deleted=0 "
                    + "AND ((i.kind='stanza' AND substr(i.value,instr(i.value,char(10))+1)=?) OR (i.kind='legacy-stanza' AND i.value=?))",
                    new String[]{Long.toString(peer),wire,wire})) {
                if (!c.moveToFirst()) return changed;
                anchor = c.getInt(0); time = c.getLong(1); order = c.getLong(2);
                if (c.moveToNext()) return changed;
            }
            String where = displayed ? "m.peer=? AND m.outgoing=1 AND m.deleted=0 AND (m.time_ms<? OR (m.time_ms=? AND m.archive_order<?) OR (m.time_ms=? AND m.archive_order=? AND m.id<=?)) AND COALESCE(r.displayed,0)=0"
                    : "m.id=? AND COALESCE(r.delivered,0)=0";
            String[] args = displayed ? new String[]{Long.toString(peer),Long.toString(time),Long.toString(time),Long.toString(order),Long.toString(time),Long.toString(order),Integer.toString(anchor)} : new String[]{Integer.toString(anchor)};
            try (Cursor c = db.rawQuery("SELECT m.id FROM messages m LEFT JOIN message_receipts r ON r.message=m.id WHERE " + where, args)) {
                while (c.moveToNext()) changed.add(c.getInt(0));
            }
            for (int id : changed) {
                ContentValues empty = new ContentValues(); empty.put("message", id);
                db.insertWithOnConflict("message_receipts", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
                ContentValues values = new ContentValues(); values.put("delivered", 1);
                if (displayed) values.put("displayed", 1);
                db.update("message_receipts", values, "message=?", new String[]{Integer.toString(id)});
            }
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }
}
