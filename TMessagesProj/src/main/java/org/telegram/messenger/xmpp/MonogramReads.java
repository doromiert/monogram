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
        createDisplayed(db);
    }
    static void createDisplayed(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS markable_messages (message INTEGER PRIMARY KEY,wire TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS markable_wire ON markable_messages(wire,message)");
        db.execSQL("CREATE TABLE IF NOT EXISTS displayed_anchors (peer INTEGER PRIMARY KEY,message INTEGER NOT NULL,time_ms INTEGER NOT NULL,archive_order INTEGER NOT NULL,wire TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_displayed (peer INTEGER PRIMARY KEY,message INTEGER NOT NULL,time_ms INTEGER NOT NULL,archive_order INTEGER NOT NULL,wire TEXT NOT NULL)");
    }
    void remember(int id,com.negzero.monogram.xmpp.XmppMessage message) {
        if(message.outgoing || message.markableId==null || message.correction!=null || message.reactions!=null || message.retraction!=null) return;
        store.getWritableDatabase().execSQL("INSERT OR IGNORE INTO markable_messages(message,wire) VALUES(?,?)",new Object[]{id,message.markableId});
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
        ReadResult result=throughWithChanges(peer,visible,all);
        return result==null ? null : result.read;
    }
    static final class ReadResult {
        final Set<Integer> read,newlyRead;
        ReadResult(Set<Integer> read,Set<Integer> newlyRead) { this.read=Collections.unmodifiableSet(read);this.newlyRead=Collections.unmodifiableSet(newlyRead); }
    }
    ReadResult throughWithChanges(long peer,Collection<Integer> visible,boolean all) {
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
                Set<Integer> newlyRead=new LinkedHashSet<>();
                try(Cursor c=db.rawQuery("SELECT m.id FROM messages m JOIN messages a ON a.id=? AND a.peer=m.peer WHERE m.peer=? AND m.outgoing=0 AND "+THROUGH+
                        " AND NOT EXISTS(SELECT 1 FROM local_read_messages r WHERE r.message=m.id)",new String[]{Integer.toString(anchor),Long.toString(peer)})) {
                    while(c.moveToNext()) newlyRead.add(c.getInt(0));
                }
                // Mark what is known through this displayed anchor. A delayed live arrival stays unread.
                db.execSQL("INSERT OR IGNORE INTO local_read_messages(message) SELECT m.id FROM messages m JOIN messages a ON a.id=? AND a.peer=m.peer WHERE m.peer=? AND m.outgoing=0 AND "+THROUGH,new Object[]{anchor,peer});
                Set<Integer> read=new LinkedHashSet<>();
                try(Cursor c=db.rawQuery("SELECT m.id FROM messages m JOIN local_read_messages r ON r.message=m.id WHERE m.peer=? AND m.outgoing=0",new String[]{Long.toString(peer)})) {
                    while(c.moveToNext()) read.add(c.getInt(0));
                }
                db.setTransactionSuccessful();return new ReadResult(read,newlyRead);
            } finally { db.endTransaction(); }
        }
    }
    static final class DisplayedCandidate {
        final long peer,timeMillis,archiveOrder;final int id;final String wire;
        DisplayedCandidate(long peer,int id,String wire,long timeMillis,long archiveOrder) {
            this.peer=peer;this.id=id;this.wire=wire;this.timeMillis=timeMillis;this.archiveOrder=archiveOrder;
        }
    }
    /** The caller supplies only this explicit read operation's newly read IDs, never cached read state. */
    DisplayedCandidate displayedCandidate(long peer,Collection<Integer> newlyRead) {
        if(peer<=0 || newlyRead==null || newlyRead.isEmpty()) return null;
        synchronized(store) {
            Set<Integer> ids=new LinkedHashSet<>();for(Integer id:newlyRead) if(id!=null && id>0) ids.add(id);
            if(ids.isEmpty()) return null;
            String eligible="SELECT m.id,k.wire,m.time_ms,m.archive_order FROM messages m JOIN markable_messages k ON k.message=m.id JOIN local_read_messages r ON r.message=m.id "+
                    "WHERE m.peer=? AND m.id IN ("+android.text.TextUtils.join(",",ids)+") AND m.outgoing=0 AND m.readable=1 AND m.deleted=0 "+
                    "AND (length(m.body)>0 OR EXISTS(SELECT 1 FROM attachments f WHERE f.message=m.id)) "+
                    "AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id) "+
                    "AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id) "+
                    "AND NOT EXISTS(SELECT 1 FROM retraction_sources s WHERE s.message=m.id) "+
                    "AND NOT EXISTS(SELECT 1 FROM markable_messages other JOIN messages duplicate ON duplicate.id=other.message WHERE duplicate.peer=m.peer AND duplicate.id!=m.id AND other.wire=k.wire) "+
                    "AND NOT EXISTS(SELECT 1 FROM displayed_anchors d WHERE d.peer=m.peer AND (d.message=m.id OR d.time_ms>m.time_ms OR (d.time_ms=m.time_ms AND d.archive_order>m.archive_order) OR (d.time_ms=m.time_ms AND d.archive_order=m.archive_order AND d.message>=m.id))) "+
                    "ORDER BY m.time_ms DESC,m.archive_order DESC,m.id DESC";
            try(Cursor c=store.getReadableDatabase().rawQuery(eligible,new String[]{Long.toString(peer)})) {
                while(c.moveToNext()) if(!store.pendingDecryption(c.getInt(0))) return new DisplayedCandidate(peer,c.getInt(0),c.getString(1),c.getLong(2),c.getLong(3));
            }
            return null;
        }
    }
    boolean queueDisplayed(DisplayedCandidate candidate) {
        if(candidate==null) return false;
        synchronized(store) {
            DisplayedCandidate current=displayedCandidate(candidate.peer,Collections.singleton(candidate.id));
            if(!same(candidate,current)) return false;
            SQLiteDatabase db=store.getWritableDatabase();
            try(Cursor c=db.rawQuery("SELECT time_ms,archive_order,message FROM pending_displayed WHERE peer=?",new String[]{Long.toString(candidate.peer)})) {
                if(c.moveToFirst() && compare(candidate,c.getLong(0),c.getLong(1),c.getInt(2))<=0) return false;
            }
            db.execSQL("INSERT OR REPLACE INTO pending_displayed(peer,message,time_ms,archive_order,wire) VALUES(?,?,?,?,?)",
                    new Object[]{candidate.peer,candidate.id,candidate.timeMillis,candidate.archiveOrder,candidate.wire});
            return true;
        }
    }
    List<DisplayedCandidate> pendingDisplayed() {
        synchronized(store) {
            ArrayList<DisplayedCandidate> pending=new ArrayList<>();ArrayList<Long> stale=new ArrayList<>();
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT peer,message,wire,time_ms,archive_order FROM pending_displayed",null)) {
                while(c.moveToNext()) {
                    DisplayedCandidate candidate=new DisplayedCandidate(c.getLong(0),c.getInt(1),c.getString(2),c.getLong(3),c.getLong(4));
                    if(same(candidate,displayedCandidate(candidate.peer,Collections.singleton(candidate.id)))) pending.add(candidate);
                    else stale.add(candidate.peer);
                }
            }
            for(long peer:stale) store.getWritableDatabase().delete("pending_displayed","peer=?",new String[]{Long.toString(peer)});
            return pending;
        }
    }
    boolean isCurrentDisplayed(DisplayedCandidate candidate) {
        if(candidate==null) return false;
        synchronized(store) {
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT peer,message,wire,time_ms,archive_order FROM pending_displayed WHERE peer=?",new String[]{Long.toString(candidate.peer)})) {
                if(!c.moveToFirst() || !same(candidate,new DisplayedCandidate(c.getLong(0),c.getInt(1),c.getString(2),c.getLong(3),c.getLong(4)))) return false;
            }
            return same(candidate,displayedCandidate(candidate.peer,Collections.singleton(candidate.id)));
        }
    }
    void clearPendingDisplayed() { synchronized(store) { store.getWritableDatabase().delete("pending_displayed",null,null); } }
    private static boolean same(DisplayedCandidate first,DisplayedCandidate second) {
        return first!=null && second!=null && first.peer==second.peer && first.id==second.id && first.wire.equals(second.wire) && first.timeMillis==second.timeMillis && first.archiveOrder==second.archiveOrder;
    }
    private static int compare(DisplayedCandidate candidate,long time,long order,int id) {
        int value=Long.compare(candidate.timeMillis,time);if(value!=0) return value;
        value=Long.compare(candidate.archiveOrder,order);return value!=0 ? value : Integer.compare(candidate.id,id);
    }
    /** Call only after a successful send while the captured account/session still owns this operation. */
    boolean acknowledgeDisplayed(DisplayedCandidate sent) {
        if(sent==null) return false;
        synchronized(store) {
            SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try {
                DisplayedCandidate current=displayedCandidate(sent.peer,Collections.singleton(sent.id));
                if(!same(sent,current)) return false;
                db.execSQL("INSERT OR REPLACE INTO displayed_anchors(peer,message,time_ms,archive_order,wire) VALUES(?,?,?,?,?)",
                        new Object[]{sent.peer,sent.id,sent.timeMillis,sent.archiveOrder,sent.wire});
                db.execSQL("DELETE FROM pending_displayed WHERE peer=? AND (time_ms<? OR (time_ms=? AND archive_order<?) OR (time_ms=? AND archive_order=? AND message<=?))",
                        new Object[]{sent.peer,sent.timeMillis,sent.timeMillis,sent.archiveOrder,sent.timeMillis,sent.archiveOrder,sent.id});
                db.setTransactionSuccessful();return true;
            } finally { db.endTransaction(); }
        }
    }
}
