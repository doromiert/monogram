// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.*;
import java.util.*;

/** Author-scoped requests survive arrival before their originals and archive replay. */
final class MonogramRetractions {
    private final MonogramStore store;
    MonogramRetractions(MonogramStore store) { this.store=store; }
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE message_retractions (peer INTEGER NOT NULL,actor TEXT NOT NULL,target TEXT NOT NULL,legacy INTEGER NOT NULL,identity TEXT NOT NULL,real_jid TEXT,stable_id TEXT,verified INTEGER NOT NULL DEFAULT 0,resolved INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(peer,actor,target,legacy,identity))");
        db.execSQL("CREATE INDEX pending_retractions ON message_retractions(peer,resolved)");
    }
    void save(XmppMessage message) {
        record(store.peer(message.peer.toString()),message.sender.split("/",2)[0],message.retraction,null,null,false);
    }
    void save(XmppRoomMessage message) {
        record(store.knownRoom(message.room.toString()),message.sender,message.retraction,message.realSender,message.occupantId,message.realSenderVerified);
    }
    private void record(long peer,String actor,MessageRetraction request,String real,String stable,boolean verified) {
        if(peer==0 || request==null) return;
        ContentValues value=new ContentValues(); value.put("peer",peer); value.put("actor",actor); value.put("target",request.id); value.put("legacy",request.legacy);
        value.put("identity",stable!=null ? "occupant:"+stable : verified && real!=null ? "jid:"+real : "");
        value.put("real_jid",real); value.put("stable_id",stable); value.put("verified",verified);
        store.getWritableDatabase().insertWithOnConflict("message_retractions",null,value,SQLiteDatabase.CONFLICT_IGNORE);
        if(verified) store.getWritableDatabase().update("message_retractions",value,
                "peer=? AND actor=? AND target=? AND legacy=? AND verified=0 AND (stable_id IS NULL OR stable_id=?)",
                new String[]{Long.toString(peer),actor,request.id,request.legacy ? "1" : "0",stable});
    }
    Set<Integer> reconcile(long peer) {
        SQLiteDatabase db=store.getWritableDatabase(); Set<Integer> removed=new HashSet<>();
        db.beginTransaction();
        try {
        try(Cursor events=db.rawQuery("SELECT actor,target,legacy,real_jid,stable_id,verified,rowid FROM message_retractions WHERE peer=? AND resolved=0",new String[]{Long.toString(peer)})) {
            while(events.moveToNext()) {
                String actor=events.getString(0),reference=events.getString(1); boolean legacy=events.getInt(2)!=0;
                String kind=legacy ? "origin" : peer<0 ? "archive" : "stanza";
                String predicate=peer>0 && !legacy || peer<0 && legacy ? "substr(i.value,instr(i.value,char(10))+1)=?" : "i.value=?";
                int target=0; boolean ambiguous=false;
                try(Cursor matches=db.rawQuery("SELECT DISTINCT i.message FROM message_ids i JOIN messages m ON m.id=i.message WHERE i.peer=? AND i.kind=? AND "+predicate
                        +" AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id) AND NOT EXISTS(SELECT 1 FROM reaction_sources r WHERE r.message=m.id)",new String[]{Long.toString(peer),kind,reference})) {
                    while(matches.moveToNext()) {
                        int candidate=matches.getInt(0);
                        if(peer>0 && !directAuthor(candidate,actor)) continue;
                        if(peer<0 && legacy && !roomAuthor(candidate,actor,events.getString(3),events.getString(4),events.getInt(5)!=0)) continue;
                        if(target!=0 && target!=candidate) { ambiguous=true; break; }
                        target=candidate;
                    }
                }
                if(ambiguous || target==0 || peer<0 && !roomAuthor(target,actor,events.getString(3),events.getString(4),events.getInt(5)!=0)) continue;
                if(!store.hiddenCorrection(target)) removed.add(target);
                db.execSQL("UPDATE message_retractions SET resolved=? WHERE rowid=?",new Object[]{target,events.getLong(6)});
            }
        }
        if(!removed.isEmpty()) store.deleteMessages(removed);
        db.setTransactionSuccessful();
        return removed;
        } finally { db.endTransaction(); }
    }
    private boolean directAuthor(int id,String author) {
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT sender FROM message_senders WHERE message=?",new String[]{Integer.toString(id)})) {
            return c.moveToFirst() && author.equals(c.getString(0).split("/",2)[0]);
        }
    }
    private boolean roomAuthor(int id,String occupant,String real,String stable,boolean verified) {
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT occupant,real_jid,stable_id,verified FROM room_message_actors WHERE message=?",new String[]{Integer.toString(id)})) {
            if(!c.moveToFirst()) return false;
            if(verified && c.getInt(3)!=0 && !Objects.equals(real,c.getString(1))) return false;
            if(stable!=null && !c.isNull(2)) return stable.equals(c.getString(2));
            return verified && c.getInt(3)!=0 && real!=null && real.equals(c.getString(1)) && occupant.equals(c.getString(0));
        }
    }
    static final class Target {
        final int id; final long peer; final String current,legacy,sender,stable;
        Target(int id,long peer,String current,String legacy,String sender,String stable) { this.id=id; this.peer=peer; this.current=current; this.legacy=legacy; this.sender=sender; this.stable=stable; }
    }
    Target target(int id,long peer,long self) {
        org.telegram.tgnet.TLRPC.Message message=store.getMessage(id,self);
        if(!message.out || message.dialog_id!=peer || store.hiddenCorrection(id)) throw new IllegalArgumentException("Only your existing messages can be retracted");
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM corrections WHERE message=?",new String[]{Integer.toString(id)})) {
            if(c.moveToFirst()) throw new IllegalArgumentException("Original message is unavailable for this correction");
        }
        String sender=null,stable=null;
        if(peer<0) try(Cursor c=store.getReadableDatabase().rawQuery("SELECT occupant,stable_id FROM room_message_actors WHERE message=? AND verified=1 AND real_jid=?",new String[]{Integer.toString(id),store.jid(self)})) {
            if(!c.moveToFirst()) throw new IllegalArgumentException("Original room author is not verified"); sender=c.getString(0); stable=c.getString(1);
        }
        String current=alias(id,peer<0 ? "archive" : "stanza",peer>0),legacy=alias(id,"origin",peer<0);
        if(current==null && legacy==null) throw new IllegalArgumentException("Original message reference is unavailable");
        if(current!=null) unique(peer,peer<0 ? "archive" : "stanza",current,peer>0,store.jid(self));
        if(legacy!=null) unique(peer,"origin",legacy,peer<0,store.jid(self));
        return new Target(id,peer,current,legacy,sender,stable);
    }
    private void unique(long peer,String kind,String reference,boolean scoped,String own) {
        String value=scoped ? "substr(i.value,instr(i.value,char(10))+1)" : "i.value";
        String author=peer>0 ? " AND (CASE WHEN instr(s.sender,'/')>0 THEN substr(s.sender,1,instr(s.sender,'/')-1) ELSE s.sender END)=?" : "";
        String[] args=peer>0 ? new String[]{Long.toString(peer),kind,reference,own} : new String[]{Long.toString(peer),kind,reference};
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(DISTINCT i.message) FROM message_ids i LEFT JOIN message_senders s ON s.message=i.message WHERE i.peer=? AND i.kind=? AND "+value+"=?"+author,args)) {
            if(!c.moveToFirst() || c.getInt(0)!=1) throw new IllegalArgumentException("Original message reference is ambiguous");
        }
    }
    private String alias(int id,String kind,boolean scoped) {
        String found=null;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT value FROM message_ids WHERE message=? AND kind=?",new String[]{Integer.toString(id),kind})) {
            while(c.moveToNext()) {
                String value=c.getString(0); if(scoped) value=value.substring(value.indexOf('\n')+1);
                if(found!=null && !found.equals(value)) throw new IllegalArgumentException("Ambiguous original reference"); found=value;
            }
        }
        return found;
    }
}
