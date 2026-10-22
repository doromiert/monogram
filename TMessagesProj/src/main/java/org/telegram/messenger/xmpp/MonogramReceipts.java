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
        createRoomReaders(db);
    }
    static void createRoomReaders(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS room_readers (peer INTEGER NOT NULL,actor TEXT NOT NULL,user INTEGER NOT NULL,message INTEGER NOT NULL,time_ms INTEGER NOT NULL,archive_order INTEGER NOT NULL,PRIMARY KEY(peer,actor))");
        db.execSQL("CREATE INDEX IF NOT EXISTS room_readers_message ON room_readers(peer,time_ms,archive_order,message)");
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
        if(message.params!=null && "true".equals(message.params.get("monogram-local-read"))) flags|=1;
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
    public static void markLocalRead(TLRPC.Message message) {
        if(message.out) return;
        if(message.params==null) message.params=new java.util.HashMap<>();
        message.params.put("monogram-local-read","true");
        message.params.put(INCOMING_UNREAD,"false");
        message.unread=false;
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
    ArrayList<Long> roomReaders(long peer,int message,long self) {
        ArrayList<Long> readers=new ArrayList<>();
        if(peer>=0) return readers;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT r.user FROM room_readers r JOIN messages m ON m.peer=r.peer "
                +"WHERE m.id=? AND m.peer=? AND m.outgoing=1 AND m.deleted=0 AND r.user!=? AND "
                +"(r.time_ms>m.time_ms OR (r.time_ms=m.time_ms AND r.archive_order>m.archive_order) "
                +"OR (r.time_ms=m.time_ms AND r.archive_order=m.archive_order AND r.message>=m.id)) ORDER BY r.user",
                new String[]{Integer.toString(message),Long.toString(peer),Long.toString(self)})) {
            while(c.moveToNext()) readers.add(c.getLong(0));
        }
        return readers;
    }
    ArrayList<Integer> receive(long peer, String wire, boolean displayed) {
        return receive(peer,wire,displayed,false);
    }
    ArrayList<Integer> receiveRoom(long peer,String wire) { return receive(peer,wire,true,true); }
    ArrayList<Integer> receiveRoom(long peer,String wire,String actor,long user) {
        if(actor==null || actor.isEmpty() || user<=0) return new ArrayList<>();
        return receive(peer,wire,true,true,actor,user);
    }
    private ArrayList<Integer> receive(long peer,String wire,boolean displayed,boolean room) {
        return receive(peer,wire,displayed,room,null,0);
    }
    private ArrayList<Integer> receive(long peer,String wire,boolean displayed,boolean room,String actor,long user) {
        ArrayList<Integer> changed = new ArrayList<>();
        if ((room ? peer>=0 : peer<=0) || wire == null || wire.isEmpty() || wire.length() > 4096) return changed;
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            int anchor; long time, order;
            try (Cursor c = db.rawQuery("SELECT DISTINCT m.id,m.time_ms,m.archive_order FROM message_ids i JOIN messages m ON m.id=i.message "
                    + "WHERE i.peer=? AND m.peer=i.peer AND m.deleted=0 "
                    + (room ? "AND i.kind='room-displayed' AND i.value=?" : "AND i.outgoing=1 AND m.outgoing=1 AND ((i.kind='stanza' AND substr(i.value,instr(i.value,char(10))+1)=?) OR (i.kind='legacy-stanza' AND i.value=?))"),
                    room ? new String[]{Long.toString(peer),wire} : new String[]{Long.toString(peer),wire,wire})) {
                if (!c.moveToFirst()) return changed;
                anchor = c.getInt(0); time = c.getLong(1); order = c.getLong(2);
                if (c.moveToNext()) return changed;
            }
            if(room && actor!=null) {
                // Keep each reader's furthest confirmed anchor, even when another reader
                // already turned the same messages into double checks.
                db.execSQL("INSERT OR IGNORE INTO room_readers(peer,actor,user,message,time_ms,archive_order) VALUES(?,?,?,?,?,?)",
                        new Object[]{peer,actor,user,anchor,time,order});
                db.execSQL("UPDATE room_readers SET user=?,message=?,time_ms=?,archive_order=? WHERE peer=? AND actor=? AND "
                        +"(time_ms<? OR (time_ms=? AND archive_order<?) OR (time_ms=? AND archive_order=? AND message<=?))",
                        new Object[]{user,anchor,time,order,peer,actor,time,time,order,time,order,anchor});
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
