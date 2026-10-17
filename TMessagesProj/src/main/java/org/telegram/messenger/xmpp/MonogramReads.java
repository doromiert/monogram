// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Local read boundaries follow the same order as history, not archive insertion IDs. */
final class MonogramReads {
    private final MonogramStore store;
    MonogramReads(MonogramStore store) { this.store=store; }
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_read_anchors (peer INTEGER PRIMARY KEY,message INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS local_read_messages (message INTEGER PRIMARY KEY)");
    }
    private static final String THROUGH="(m.time_ms<a.time_ms OR (m.time_ms=a.time_ms AND m.archive_order<a.archive_order) OR (m.time_ms=a.time_ms AND m.archive_order=a.archive_order AND m.id<=a.id))";
    boolean isRead(int message) {
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM local_read_messages WHERE message=?",new String[]{Integer.toString(message)})) {
            return c.moveToFirst();
        }
    }
    /** Only a newly inserted archive message may inherit a historical read boundary. */
    void inheritArchive(int message) {
        store.getWritableDatabase().execSQL("INSERT OR IGNORE INTO local_read_messages(message) SELECT m.id FROM messages m JOIN local_read_anchors r ON r.peer=m.peer JOIN messages a ON a.id=r.message AND a.peer=m.peer WHERE m.id=? AND m.outgoing=0 AND "+THROUGH,new Object[]{message});
    }
    /** Null means none of the requested anchors belongs to this conversation. */
    Set<Integer> through(long peer,Collection<Integer> visible,boolean all) {
        synchronized(store) {
            SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try {
                Set<Integer> anchors=new LinkedHashSet<>();
                for(Integer id:visible) if(id!=null && id>0 && id!=Integer.MAX_VALUE) anchors.add(id);
                if(!all && anchors.isEmpty()) return null;
                String where=all ? "" : " AND m.id IN ("+android.text.TextUtils.join(",",anchors)+")";
                int anchor=0;
                try(Cursor c=db.rawQuery("SELECT m.id FROM messages m WHERE m.peer=? AND m.deleted=0"+where+
                        " AND NOT EXISTS(SELECT 1 FROM corrections x WHERE x.message=m.id AND x.resolved!=0)"+
                        " AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id) ORDER BY m.time_ms DESC,m.archive_order DESC,m.id DESC LIMIT 1",new String[]{Long.toString(peer)})) {
                    if(c.moveToFirst()) anchor=c.getInt(0);
                }
                if(anchor==0) return null;
                int previous=0;
                try(Cursor c=db.rawQuery("SELECT r.message FROM local_read_anchors r JOIN messages m ON m.id=r.message AND m.peer=r.peer WHERE r.peer=?",new String[]{Long.toString(peer)})) {
                    if(c.moveToFirst()) previous=c.getInt(0);
                }
                boolean advance=previous==0;
                if(!advance) try(Cursor c=db.rawQuery("SELECT 1 FROM messages m JOIN messages a ON a.id=? AND a.peer=m.peer WHERE m.id=? AND "+THROUGH,new String[]{Integer.toString(anchor),Integer.toString(previous)})) {
                    advance=c.moveToFirst();
                }
                if(advance) db.execSQL("INSERT OR REPLACE INTO local_read_anchors(peer,message) VALUES(?,?)",new Object[]{peer,anchor});
                // Mark what is known through this displayed anchor. A delayed live arrival stays unread.
                db.execSQL("INSERT OR IGNORE INTO local_read_messages(message) SELECT m.id FROM messages m JOIN messages a ON a.id=? AND a.peer=m.peer WHERE m.peer=? AND m.outgoing=0 AND "+THROUGH,new Object[]{anchor,peer});
                Set<Integer> read=new LinkedHashSet<>();
                try(Cursor c=db.rawQuery("SELECT m.id FROM messages m JOIN local_read_messages r ON r.message=m.id WHERE m.peer=? AND m.outgoing=0",new String[]{Long.toString(peer)})) {
                    while(c.moveToNext()) read.add(c.getInt(0));
                }
                db.setTransactionSuccessful();return Collections.unmodifiableSet(read);
            } finally { db.endTransaction(); }
        }
    }
}
