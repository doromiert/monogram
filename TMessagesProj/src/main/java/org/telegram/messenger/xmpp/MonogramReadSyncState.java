// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.SharedPreferences;
import android.database.Cursor;
import com.negzero.monogram.xmpp.XmppReadSync;
import com.negzero.monogram.xmpp.RoomDisplayedMarker;
import java.util.*;
import org.json.JSONObject;

/** Private own-device state; public receipt consent and queues are deliberately separate. */
final class MonogramReadSyncState {
    private final MonogramStore store;
    private final SharedPreferences preferences;
    MonogramReadSyncState(MonogramStore store,SharedPreferences preferences) { this.store=store;this.preferences=preferences; }
    static final class Candidate {
        final long peer,time,order;final int id;final XmppReadSync.Entry entry;
        Candidate(long peer,int id,long time,long order,XmppReadSync.Entry entry) { this.peer=peer;this.id=id;this.time=time;this.order=order;this.entry=entry; }
        int compare(Candidate other) {
            int value=Long.compare(time,other.time);if(value!=0) return value;
            value=Long.compare(order,other.order);return value!=0 ? value : Integer.compare(id,other.id);
        }
        String json() throws Exception {
            return new JSONObject().put("peer",peer).put("id",id).put("time",time).put("order",order)
                    .put("chat",entry.chat).put("by",entry.by).put("wire",entry.id).toString();
        }
    }
    private String prefix(String own) { return "private-read-"+own+"/"; }
    private String key(String own,String kind,long peer) { return prefix(own)+kind+"/"+peer; }
    private Candidate saved(String key) {
        String value=preferences.getString(key,null);if(value==null) return null;
        try {
            JSONObject json=new JSONObject(value);
            return new Candidate(json.getLong("peer"),json.getInt("id"),json.getLong("time"),json.getLong("order"),
                    XmppReadSync.Entry.create(json.getString("chat"),json.getString("by"),json.getString("wire")));
        } catch(Exception invalid) { return null; }
    }
    private Candidate row(String own,long peer,int id,boolean local) throws Exception {
        if(peer==0 || own.isEmpty()) return null;
        final String chat;
        try { chat=store.jid(peer); } catch(IllegalArgumentException unknown) { return null; }
        if(own.equals(chat)) return null;
        String kind=peer<0 ? RoomDisplayedMarker.LOCAL_ID : XmppReadSync.LOCAL_ID;
        String query="SELECT m.time_ms,m.archive_order,k.value FROM messages m JOIN message_ids k ON k.message=m.id AND k.peer=m.peer AND k.kind=? "+
                "WHERE m.peer=? AND m.id=? AND m.deleted=0 AND m.readable=1 "+
                "AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id) "+
                "AND (length(m.body)>0 OR EXISTS(SELECT 1 FROM attachments f WHERE f.message=m.id)) "+
                "AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id) "+
                "AND NOT EXISTS(SELECT 1 FROM retraction_sources s WHERE s.message=m.id) "+
                "AND NOT EXISTS(SELECT 1 FROM message_ids d WHERE d.peer=m.peer AND d.kind=k.kind AND d.value=k.value AND d.message!=m.id)";
        if(local) query+=" AND m.outgoing=0 AND EXISTS(SELECT 1 FROM local_read_messages r WHERE r.message=m.id)";
        try(Cursor c=store.getReadableDatabase().rawQuery(query,new String[]{kind,Long.toString(peer),Integer.toString(id)})) {
            if(!c.moveToFirst() || store.pendingDecryption(id)) return null;
            Candidate found=new Candidate(peer,id,c.getLong(0),c.getLong(1),XmppReadSync.Entry.create(chat,peer<0 ? chat : own,c.getString(2)));
            return c.moveToNext() ? null : found;
        }
    }
    synchronized Candidate candidate(String own,long peer,Collection<Integer> newlyRead) throws Exception {
        synchronized(store) {
            Candidate latest=null,previous=saved(key(own,"seen",peer));
            for(Integer id:newlyRead) {
                if(id==null || id<=0) continue;
                Candidate next=row(own,peer,id,true);
                if(next!=null && (previous==null || next.compare(previous)>0) && (latest==null || next.compare(latest)>0)) latest=next;
            }
            return latest;
        }
    }
    synchronized boolean queue(String own,Candidate value) throws Exception {
        if(!current(own,value)) return false;
        String key=key(own,"pending",value.peer);Candidate previous=saved(key),seen=saved(key(own,"seen",value.peer));
        if(previous!=null && value.compare(previous)<=0 || seen!=null && value.compare(seen)<=0) return false;
        return preferences.edit().putString(key,value.json()).commit();
    }
    synchronized boolean current(String own,Candidate value) throws Exception {
        synchronized(store) {
            Candidate now=row(own,value.peer,value.id,true);
            return now!=null && now.compare(value)==0 && now.entry.id.equals(value.entry.id) && now.entry.chat.equals(value.entry.chat) && now.entry.by.equals(value.entry.by);
        }
    }
    synchronized List<Candidate> pending(String own) throws Exception {
        ArrayList<Candidate> found=new ArrayList<>();String prefix=prefix(own)+"pending/";
        for(String key:preferences.getAll().keySet()) if(key.startsWith(prefix)) {
            Candidate value=saved(key);
            if(value!=null && current(own,value)) found.add(value);
            else preferences.edit().remove(key).commit();
        }
        return found;
    }
    synchronized void acknowledge(String own,Candidate value) throws Exception {
        String key=key(own,"seen",value.peer);Candidate seen=saved(key),pending=saved(key(own,"pending",value.peer));
        SharedPreferences.Editor edit=preferences.edit();
        if(seen==null || value.compare(seen)>0) edit.putString(key,value.json());
        if(pending!=null && pending.compare(value)<=0) edit.remove(key(own,"pending",value.peer));
        if(!edit.commit()) throw new java.io.IOException("Could not save private read state");
    }
    /** A known server reference is required. Full MUC private JIDs are unsupported, never collapsed. */
    synchronized Candidate observe(String own,XmppReadSync.Entry entry) throws Exception {
        synchronized(store) {
            if(entry.chat.contains("/") || entry.chat.equals(own)) return null;
            long peer=store.knownRoom(entry.chat);if(peer==0) peer=store.knownPeer(entry.chat);
            if(peer==0 || !entry.by.equals(peer<0 ? entry.chat : own)) return null;
            String kind=peer<0 ? RoomDisplayedMarker.LOCAL_ID : XmppReadSync.LOCAL_ID;
            int id;
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT message FROM message_ids WHERE peer=? AND kind=? AND value=?",new String[]{Long.toString(peer),kind,entry.id})) {
                if(!c.moveToFirst()) return null;id=c.getInt(0);if(c.moveToNext()) return null;
            }
            Candidate value=row(own,peer,id,false),seen=saved(key(own,"seen",peer));
            if(value==null || seen!=null && value.compare(seen)<0) return null;
            acknowledge(own,value);return value;
        }
    }
    synchronized boolean setEnabled(String own,boolean enabled) {
        SharedPreferences.Editor edit=preferences.edit().putBoolean("private-read-enabled-"+own,enabled);
        String prefix=prefix(own)+"pending/";
        for(String key:preferences.getAll().keySet()) if(key.startsWith(prefix)) edit.remove(key);
        return edit.commit();
    }
    synchronized void clearPending(String own) {
        SharedPreferences.Editor edit=preferences.edit();String prefix=prefix(own)+"pending/";
        for(String key:preferences.getAll().keySet()) if(key.startsWith(prefix)) edit.remove(key);
        if(!edit.commit()) throw new IllegalStateException("Could not clear private read queue");
    }
}
