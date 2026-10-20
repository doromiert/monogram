// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.telegram.messenger.EmojiData;
import org.telegram.tgnet.TLRPC;

/** Durable XEP-0444 replacement sets, independent of visible messages and their unread counts. */
final class MonogramReactions {
    private static final Set<String> UNICODE=new HashSet<>();
    static {
        for(String[][] source:new String[][][]{EmojiData.data,EmojiData.dataColored})
            for(String[] section:source) for(String emoji:section) { UNICODE.add(emoji); UNICODE.add(emoji.replace("\ufe0f","")); }
        for(CharSequence alias:EmojiData.emojiAliasMap.keySet()) UNICODE.add(alias.toString());
    }
    static boolean isEmoji(String emoji) { return emoji!=null && UNICODE.contains(emoji.replace("\ufe0f","")); }
    private static String canonicalEmoji(String emoji) {
        StringBuilder result=new StringBuilder();
        emoji.replace("\ufe0f","").codePoints().forEach(code -> {
            result.appendCodePoint(code);
            if(code<=Character.MAX_VALUE && EmojiData.emojiToFE0FMap.containsKey((char)code)
                    || code==0x1f22f || code==0x1f004 || code==0x1f21a || code==0x1f17f) result.append('\ufe0f');
        });
        return result.toString();
    }
    private final MonogramStore store;
    MonogramReactions(MonogramStore store) { this.store=store; }
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reaction_events (id INTEGER PRIMARY KEY AUTOINCREMENT,peer INTEGER NOT NULL,target TEXT NOT NULL,actor TEXT NOT NULL,user INTEGER NOT NULL,stamp INTEGER NOT NULL,wire TEXT NOT NULL,protocol INTEGER NOT NULL,emojis TEXT NOT NULL,message INTEGER NOT NULL DEFAULT 0,failed INTEGER NOT NULL DEFAULT 0,UNIQUE(peer,actor,wire,protocol))");
        db.execSQL("CREATE INDEX IF NOT EXISTS reactions_by_message ON reaction_events(message,stamp,id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS reaction_sources (message INTEGER PRIMARY KEY)");
        // Discover reactions older releases ignored, retaining every cached message.
        db.execSQL("DELETE FROM archive_state");
    }
    Set<Integer> save(XmppMessage message,long self) {
        long peer=store.peer(message.peer.toString()),user=message.outgoing ? self : peer;
        return save(peer,"jid:"+store.jid(user),user,message.ids,message.reactions,
                message.timestamp,message.encryption.ordinal(),message.sender,message.outgoing);
    }
    Set<Integer> save(XmppRoomMessage message,long self) {
        long peer=store.knownRoom(message.room.toString());
        if(peer==0) throw new IllegalArgumentException("Unknown room");
        String actor=message.outgoing ? "jid:"+store.jid(self) : message.realSender!=null ? "jid:"+message.realSender
                : message.occupantId!=null ? "occupant:"+message.occupantId : null;
        // An anonymous nickname without server-backed continuity cannot identify a reaction sender.
        if(actor==null) return Collections.emptySet();
        long user=message.outgoing ? self : store.roomUser(peer,message.sender,message.realSender,self);
        return save(peer,actor,user,message.ids,message.reactions,message.timestamp,0,message.sender,message.outgoing);
    }
    private static String referenceKind(Map<String,String> ids) {
        for(String kind:new String[]{"stanza","origin","archive","mam","local"})
            if(ids.get(kind)!=null && !ids.get(kind).isEmpty()) return kind;
        return null;
    }
    private static String reference(Map<String,String> ids,String kind,long stamp,int protocol) {
        if(kind==null) return null;
        // The same ID-less live set can be added again after an intervening removal.
        return kind.equals("stanza") ? ids.get(kind) : kind+"\n"+ids.get(kind)+(kind.equals("local") && protocol==0 ? "\n"+stamp : "");
    }
    private Set<Integer> save(long peer,String actor,long user,Map<String,String> ids,MessageReactions reactions,long stamp,int protocol,String sender,boolean outgoing) {
        String kind=referenceKind(ids),wire=reference(ids,kind,stamp,protocol);
        if(reactions==null || wire==null || wire.isEmpty() || stamp<0 || stamp>System.currentTimeMillis()+300000) return Collections.emptySet();
        LinkedHashSet<String> emojis=new LinkedHashSet<>();
        for(String emoji:reactions.emojis) if(isEmoji(emoji)) emojis.add(canonicalEmoji(emoji));
        // Match MonogramStore lock order: helper monitor before a SQLite write transaction.
        synchronized(store) {
            SQLiteDatabase db=store.getWritableDatabase(); db.beginTransaction();
            try {
                List<Integer> sources=new ArrayList<>();
                try(Cursor c=db.rawQuery("SELECT DISTINCT m.id,m.readable FROM messages m JOIN message_ids i ON i.message=m.id WHERE m.peer=? AND m.outgoing=? AND m.encryption=? AND i.kind=? AND i.value=?",new String[]{Long.toString(peer),outgoing ? "1" : "0",Integer.toString(protocol),kind,kind.equals("stanza") ? sender+"\n"+ids.get(kind) : ids.get(kind)})) {
                    while(c.moveToNext()) { if(c.getInt(1)!=0) return Collections.emptySet(); sources.add(c.getInt(0)); }
                }
                int target=resolve(peer,reactions.id);
                ContentValues values=new ContentValues(); values.put("peer",peer); values.put("target",reactions.id); values.put("actor",actor); values.put("user",user);
                values.put("stamp",stamp); values.put("wire",wire); values.put("protocol",protocol); values.put("emojis",String.join("\n",emojis)); values.put("message",target);
                boolean inserted=db.insertWithOnConflict("reaction_events",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
                for(int source:sources) { ContentValues hidden=new ContentValues(); hidden.put("message",source); db.insertWithOnConflict("reaction_sources",null,hidden,SQLiteDatabase.CONFLICT_IGNORE); }
                db.setTransactionSuccessful();
                return inserted && target!=0 ? Collections.singleton(target) : Collections.emptySet();
            } finally { db.endTransaction(); }
        }
    }
    boolean known(XmppMessage message) {
        String wire=reference(message.ids,referenceKind(message.ids),message.timestamp,message.encryption.ordinal()); if(wire==null) return false;
        long peer=store.knownPeer(message.peer.toString()); if(peer==0) return false;
        String actor="jid:"+message.sender.split("/",2)[0];
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM reaction_events WHERE peer=? AND actor=? AND wire=? AND protocol=?",new String[]{Long.toString(peer),actor,wire,Integer.toString(message.encryption.ordinal())})) { return c.moveToFirst(); }
    }
    private int resolve(long peer,String target) {
        String sql=peer<0 ? "i.kind='archive' AND i.value=?"
                : "((i.kind='origin' AND i.value=?) OR (i.kind='stanza' AND substr(i.value,instr(i.value,char(10))+1)=?))";
        String[] args=peer<0 ? new String[]{Long.toString(peer),target} : new String[]{Long.toString(peer),target,target};
        int found=0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT i.message FROM message_ids i WHERE i.peer=? AND ("+sql+")",args)) {
            while(c.moveToNext()) {
                int candidate=c.getInt(0);
                try(Cursor correction=store.getReadableDatabase().rawQuery("SELECT resolved FROM corrections WHERE message=? AND resolved!=0",new String[]{Integer.toString(candidate)})) { if(correction.moveToFirst()) candidate=correction.getInt(0); }
                if(found!=0 && found!=candidate) return 0; found=candidate;
            }
        }
        return found;
    }
    String target(int message,long peer,long self) {
        if(store.getMessage(message,self).dialog_id!=peer || store.hiddenCorrection(message)) throw new IllegalArgumentException("Message is unavailable");
        String target=null;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT kind,value FROM message_ids WHERE message=? AND kind IN ("+(peer<0 ? "'archive'" : "'origin','stanza'")+") ORDER BY CASE kind WHEN 'origin' THEN 0 ELSE 1 END,rowid",new String[]{Integer.toString(message)})) {
            while(c.moveToNext()) {
                String value=c.getString(1); if(c.getString(0).equals("stanza")) value=value.substring(value.indexOf('\n')+1);
                if(resolve(peer,value)==message) { target=value; break; }
            }
        }
        if(target==null) throw new IllegalArgumentException(peer<0 ? "This room message has no stable room ID yet" : "The original message ID is unavailable or ambiguous");
        return target;
    }
    /** Reconcile after a message/archive page or correction resolves new aliases. */
    Set<Integer> reconcile(long peer) {
        // All updates to one referenced message share its resolution, regardless of actor or replay count.
        synchronized(store) {
            Set<Integer> changed=new LinkedHashSet<>(); SQLiteDatabase db=store.getWritableDatabase();
            Map<String,Integer> resolved=new HashMap<>(),updates=new LinkedHashMap<>();
            db.beginTransaction();
            try {
                try(Cursor c=db.rawQuery("SELECT target,message FROM reaction_events WHERE peer=? GROUP BY target,message",new String[]{Long.toString(peer)})) {
                    while(c.moveToNext()) {
                        String reference=c.getString(0); Integer target=resolved.get(reference);
                        if(target==null) { target=resolve(peer,reference); resolved.put(reference,target); }
                        int old=c.getInt(1);
                        if(target!=old) {
                            updates.put(reference,target);
                            if(old!=0) changed.add(old); if(target!=0) changed.add(target);
                        }
                    }
                }
                for(Map.Entry<String,Integer> update:updates.entrySet())
                    db.execSQL("UPDATE reaction_events SET message=? WHERE peer=? AND target=? AND message!=?",new Object[]{update.getValue(),peer,update.getKey(),update.getValue()});
                db.setTransactionSuccessful();
                return changed;
            } finally {
                db.endTransaction();
            }
        }
    }
    Set<Integer> targets(long peer) {
        Set<Integer> ids=new LinkedHashSet<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT message FROM reaction_events WHERE peer=? AND message!=0",new String[]{Long.toString(peer)})) { while(c.moveToNext()) ids.add(c.getInt(0)); }
        return ids;
    }
    Set<Integer> hidden(long peer) {
        Set<Integer> ids=new LinkedHashSet<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT s.message FROM reaction_sources s JOIN messages m ON m.id=s.message WHERE m.peer=?",new String[]{Long.toString(peer)})) { while(c.moveToNext()) ids.add(c.getInt(0)); }
        return ids;
    }
    Set<Integer> reject(String from,String wire,long self) throws Exception {
        org.jxmpp.jid.Jid jid=org.jxmpp.jid.impl.JidCreate.from(from);
        long peer=store.knownRoom(jid.asBareJid().toString());
        if(peer!=0 && jid.hasResource()) return Collections.emptySet();
        if(peer==0) peer=store.knownPeer(jid.asBareJid().toString());
        if(peer==0) return Collections.emptySet();
        Set<Integer> changed=new LinkedHashSet<>(); SQLiteDatabase db=store.getWritableDatabase();
        String[] args={Long.toString(peer),"jid:"+store.jid(self),wire};
        try(Cursor c=db.rawQuery("SELECT message FROM reaction_events WHERE peer=? AND actor=? AND wire=? AND failed=0",args)) { while(c.moveToNext()) if(c.getInt(0)!=0) changed.add(c.getInt(0)); }
        ContentValues failed=new ContentValues(); failed.put("failed",1); db.update("reaction_events",failed,"peer=? AND actor=? AND wire=?",args);
        return changed;
    }
    private static final class State {
        final long user,stamp; final int protocol; final String emojis;
        State(Cursor c) { user=c.getLong(1); stamp=c.getLong(2); protocol=c.getInt(3); emojis=c.getString(4); }
    }
    TLRPC.TL_messageReactions snapshot(int message,long self) {
        Map<String,State> latest=new LinkedHashMap<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT actor,user,stamp,protocol,emojis FROM reaction_events WHERE message=? AND failed=0 ORDER BY stamp,id",new String[]{Integer.toString(message)})) {
            while(c.moveToNext()) {
                State before=latest.get(c.getString(0)),next=new State(c);
                if(before==null || before.protocol!=2 || next.protocol==2) latest.put(c.getString(0),next);
            }
        }
        TLRPC.TL_messageReactions result=new TLRPC.TL_messageReactions(); result.can_see_list=true; result.flags=2;
        Map<String,TLRPC.ReactionCount> counts=new LinkedHashMap<>(); int order=0;
        for(State state:latest.values()) {
            if(state.emojis.isEmpty()) continue;
            for(String emoji:state.emojis.split("\n")) {
                TLRPC.ReactionCount count=counts.get(emoji);
                if(count==null) { count=new TLRPC.TL_reactionCount(); count.reaction=new TLRPC.TL_reactionEmoji(); ((TLRPC.TL_reactionEmoji)count.reaction).emoticon=emoji; counts.put(emoji,count); }
                count.count++;
                if(state.user==self) { count.chosen=true; count.flags=1; count.chosen_order=order++; }
                TLRPC.TL_messagePeerReaction actor=new TLRPC.TL_messagePeerReaction(); actor.peer_id=new TLRPC.TL_peerUser(); actor.peer_id.user_id=state.user;
                actor.date=(int)(state.stamp/1000); actor.reaction=count.reaction; result.recent_reactions.add(actor);
            }
        }
        result.results.addAll(counts.values()); return result;
    }
}
