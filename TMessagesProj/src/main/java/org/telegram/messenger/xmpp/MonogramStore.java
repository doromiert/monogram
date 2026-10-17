// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.content.Context;
import com.negzero.monogram.xmpp.XmppMessage;
import com.negzero.monogram.xmpp.ReplyReference;
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
    private final Context storeContext;
    private final String database;
    final MonogramReactions reactions=new MonogramReactions(this);
    private final MonogramRetractions retractions=new MonogramRetractions(this);
    private final java.util.Map<Long, com.negzero.monogram.xmpp.XmppProfile> profileCache = new java.util.HashMap<>();
    MonogramStore(int account) { this(ApplicationLoader.applicationContext, "monogram-" + account + ".db"); }
    MonogramStore(Context context, String name) { super(context, name, null, 16); storeContext = context; database = new java.io.File(name).getName(); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer INTEGER NOT NULL, stanza TEXT NOT NULL, body TEXT NOT NULL, encrypted INTEGER NOT NULL, outgoing INTEGER NOT NULL, time INTEGER NOT NULL, UNIQUE(peer,stanza,outgoing))");
        addMessageMetadata(db); addAttachments(db); addProfiles(db); addReplies(db); addCorrections(db); addCaptions(db); addProtocols(db); addRooms(db); addBookmarks(db); addRoomProfiles(db); addRoomAuthorVerification(db); MonogramReactions.create(db);
        addDeletions(db); addSenderFingerprints(db); MonogramRetractions.create(db);
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
    private void addAttachments(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE attachments (message INTEGER PRIMARY KEY, url TEXT NOT NULL, path TEXT, media BLOB)");
        db.execSQL("INSERT INTO attachments(message,url) SELECT id,body FROM messages WHERE encrypted=1 AND readable=1 AND body LIKE 'aesgcm://%' AND instr(body,char(10))=0");
    }
    private void addProfiles(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profiles (peer INTEGER PRIMARY KEY, card TEXT, nickname TEXT, avatar TEXT, roster_name TEXT NOT NULL DEFAULT '')");
    }
    private void addReplies(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE message_senders (message INTEGER NOT NULL, sender TEXT NOT NULL, PRIMARY KEY(message,sender))");
        db.execSQL("INSERT OR IGNORE INTO message_senders SELECT message,substr(value,1,instr(value,char(10))-1) FROM message_ids WHERE kind='stanza' AND instr(value,char(10))>0");
        db.execSQL("CREATE TABLE replies (message INTEGER PRIMARY KEY, author TEXT, target TEXT NOT NULL, fallback TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0)");
        // Re-read archive metadata; keep every local message, ID, attachment and known plaintext.
        db.execSQL("DELETE FROM archive_state WHERE scope='direct'");
    }
    private void addCorrections(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE corrections (message INTEGER PRIMARY KEY, target TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX correction_targets ON corrections(resolved)");
        db.execSQL("CREATE TABLE applied_corrections (original INTEGER PRIMARY KEY, message INTEGER NOT NULL)");
        db.execSQL("DELETE FROM archive_state WHERE scope='direct'");
    }
    private void addCaptions(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE attachments ADD COLUMN caption TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE attachments ADD COLUMN sharing TEXT");
        db.execSQL("DELETE FROM archive_state");
    }
    private void addProtocols(SQLiteDatabase db) {
        // Older clients imported unsupported encrypted fallbacks as plaintext. Keep that content unclassified.
        db.execSQL("ALTER TABLE messages ADD COLUMN encryption INTEGER NOT NULL DEFAULT -1");
        db.execSQL("UPDATE messages SET encryption=1 WHERE encrypted=1");
        db.execSQL("ALTER TABLE message_ids RENAME TO old_message_ids");
        db.execSQL("CREATE TABLE message_ids (peer INTEGER NOT NULL,outgoing INTEGER NOT NULL,kind TEXT NOT NULL,value TEXT NOT NULL,message INTEGER NOT NULL,namespace INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(peer,outgoing,kind,value,namespace))");
        db.execSQL("INSERT INTO message_ids(peer,outgoing,kind,value,message) SELECT peer,outgoing,kind,value,message FROM old_message_ids");
        db.execSQL("DROP TABLE old_message_ids");
        db.execSQL("DELETE FROM archive_state");
    }
    private static int protocol(XmppMessage message) {
        return message.encryption == XmppMessage.Encryption.OMEMO2 ? 2 : message.encrypted ? 1 : 0;
    }
    private static int namespace(XmppMessage message) { return message.encryption == XmppMessage.Encryption.OMEMO2 ? 2 : 0; }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (newVersion != 16 || oldVersion < 1 || oldVersion > 15) throw new IllegalStateException("Unknown database migration");
        if (oldVersion == 1) addMessageMetadata(db);
        else if (oldVersion == 2) db.execSQL("UPDATE message_ids SET kind='legacy-stanza' WHERE kind='stanza'");
        if (oldVersion < 4) addAttachments(db);
        if (oldVersion < 5) addProfiles(db);
        if (oldVersion < 6) addReplies(db);
        if (oldVersion < 7) addCorrections(db);
        if (oldVersion < 8) addCaptions(db);
        if (oldVersion < 9) addProtocols(db);
        if(oldVersion<10) addRooms(db);
        if(oldVersion<11) addBookmarks(db);
        if(oldVersion<12) addRoomProfiles(db);
        if(oldVersion<14) addRoomAuthorVerification(db);
        if(oldVersion<14) MonogramReactions.create(db);
        if(oldVersion<15) addDeletions(db);
        if(oldVersion<16) { addSenderFingerprints(db); MonogramRetractions.create(db); }
    }
    private void addSenderFingerprints(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN sender_fingerprint TEXT");
    }
    private void rememberFingerprint(SQLiteDatabase db,int id,boolean readable,int protocol,String fingerprint) {
        if(!readable || protocol==0 || fingerprint==null) return;
        String normalized=MonogramRetractions.fingerprint(fingerprint);
        if(normalized==null) throw new IllegalArgumentException("Invalid authenticated sender fingerprint");
        try(Cursor c=db.rawQuery("SELECT sender_fingerprint FROM messages WHERE id=?",new String[]{Integer.toString(id)})) {
            if(!c.moveToFirst()) throw new IllegalStateException("Missing fingerprint target");
            if(!c.isNull(0) && !normalized.equals(c.getString(0))) throw new IllegalStateException("Conflicting authenticated sender fingerprints");
        }
        ContentValues value=new ContentValues(); value.put("sender_fingerprint",normalized);
        db.update("messages",value,"id=? AND sender_fingerprint IS NULL",new String[]{Integer.toString(id)});
    }
    private void addDeletions(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
    }
    private boolean deleted(int id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT deleted FROM messages WHERE id=?",new String[]{Integer.toString(id)})) {
            return c.moveToFirst() && c.getInt(0)!=0;
        }
    }
    synchronized java.util.Set<Integer> reconcileRetractions(long peer) { return retractions.reconcile(peer); }
    synchronized MonogramRetractions.Target retractionTarget(int id,long peer,long self) { return retractions.target(id,peer,self); }
    synchronized void deleteMessages(java.util.Collection<Integer> ids) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            java.util.Set<Integer> hidden=new java.util.HashSet<>();
            for(int id:ids) if(id>0) {
                hidden.add(id);
                try(Cursor c=db.rawQuery("SELECT message FROM corrections WHERE resolved=?",new String[]{Integer.toString(id)})) {
                    while(c.moveToNext()) hidden.add(c.getInt(0));
                }
            }
            for(int id:hidden) {
                // Keep identity aliases as tombstones so a MAM replay cannot resurrect the content.
                db.execSQL("UPDATE messages SET deleted=1,body='',readable=0 WHERE id=?",new Object[]{id});
                db.delete("attachments","message=?",new String[]{Integer.toString(id)});
                db.delete("replies","message=?",new String[]{Integer.toString(id)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private boolean rememberDeleted(SQLiteDatabase db,int id,long peer,java.util.Map<String,String> ids,String sender,boolean outgoing,int namespace,boolean room) {
        if(id==0 || !deleted(id)) return false;
        for(java.util.Map.Entry<String,String> alias:ids.entrySet()) {
            boolean scoped=room ? !alias.getKey().equals("archive") && !alias.getKey().equals("mam") : alias.getKey().equals("stanza");
            ContentValues value=new ContentValues(); value.put("peer",peer); value.put("outgoing",outgoing); value.put("kind",alias.getKey());
            value.put("value",scoped ? sender+"\n"+alias.getValue() : alias.getValue()); value.put("namespace",namespace); value.put("message",id);
            db.insertWithOnConflict("message_ids",null,value,SQLiteDatabase.CONFLICT_IGNORE);
        }
        return true;
    }
    private void addRoomAuthorVerification(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE room_message_actors ADD COLUMN stable_id TEXT");
        db.execSQL("ALTER TABLE room_message_actors ADD COLUMN verified INTEGER NOT NULL DEFAULT 0");
        db.execSQL("DELETE FROM archive_state WHERE scope LIKE 'room:%'");
    }
    private void addRoomProfiles(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS room_permissions (room INTEGER PRIMARY KEY,role TEXT NOT NULL,affiliation TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_message_actors (message INTEGER PRIMARY KEY,occupant TEXT NOT NULL,real_jid TEXT)");
        // Re-read authoritative author metadata without deleting cached conversations.
        db.execSQL("DELETE FROM archive_state WHERE scope LIKE 'room:%'");
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
    synchronized long knownPeer(String jid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM peers WHERE jid=?", new String[]{jid})) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }
    synchronized String jid(long id) {
        if (id < 0) return room(id).jid;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT jid FROM peers WHERE id=?", new String[]{Long.toString(id)})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("Unknown XMPP peer");
            return c.getString(0);
        }
    }
    synchronized void clear() {
        SQLiteDatabase db = getWritableDatabase();
        profileCache.clear();
        db.beginTransaction();
        try { db.delete("retraction_sources",null,null); db.delete("message_retractions",null,null); db.delete("reaction_events",null,null); db.delete("reaction_sources",null,null); db.delete("room_permissions",null,null); db.delete("room_message_actors",null,null); db.delete("room_bookmarks", null, null); db.delete("room_senders", null, null); db.delete("room_participants", null, null); db.delete("rooms", null, null); db.delete("applied_corrections", null, null); db.delete("corrections", null, null); db.delete("replies", null, null); db.delete("message_senders", null, null); db.delete("profiles", null, null); db.delete("attachments", null, null); db.delete("message_ids", null, null); db.delete("archive_state", null, null); db.delete("messages", null, null); db.delete("peers", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    private void addRooms(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rooms (id INTEGER PRIMARY KEY AUTOINCREMENT,jid TEXT NOT NULL UNIQUE,nickname TEXT NOT NULL,title TEXT NOT NULL,subject TEXT NOT NULL DEFAULT '',autojoin INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_participants (room INTEGER NOT NULL,occupant TEXT NOT NULL,user INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 0,real_jid TEXT,PRIMARY KEY(room,occupant))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_senders (message INTEGER PRIMARY KEY,user INTEGER NOT NULL)");
    }
    static final class Room {
        final long dialog;
        final String jid,nickname,title,subject;
        final boolean autojoin;
        Room(Cursor c) { dialog=-c.getLong(0); jid=c.getString(1); nickname=c.getString(2); title=c.getString(3); subject=c.getString(4); autojoin=c.getInt(5)!=0; }
    }
    synchronized Room room(long dialog) {
        if (dialog>=0) throw new IllegalArgumentException("Room dialog required");
        try (Cursor c=getReadableDatabase().rawQuery("SELECT id,jid,nickname,title,subject,autojoin FROM rooms WHERE id=?",new String[]{Long.toString(-dialog)})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("Unknown room"); return new Room(c);
        }
    }
    synchronized long room(String jid,String nickname) {
        ContentValues values=new ContentValues(); values.put("jid",jid); values.put("nickname",nickname); values.put("title",jid);
        getWritableDatabase().insertWithOnConflict("rooms",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        getWritableDatabase().execSQL("UPDATE rooms SET nickname=?,autojoin=1 WHERE jid=?",new Object[]{nickname,jid});
        return knownRoom(jid);
    }
    synchronized long knownRoom(String jid) {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT id FROM rooms WHERE jid=?",new String[]{jid})) { return c.moveToFirst() ? -c.getLong(0) : 0; }
    }
    synchronized long knownOccupant(String address) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT user FROM room_participants WHERE occupant=? AND active=1",new String[]{address})) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }
    synchronized int newerMessages(long peer,int anchor) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM messages m JOIN messages a ON a.id=? WHERE m.peer=? AND m.deleted=0 AND (m.time_ms>a.time_ms OR (m.time_ms=a.time_ms AND m.archive_order>a.archive_order) OR (m.time_ms=a.time_ms AND m.archive_order=a.archive_order AND m.id>a.id)) AND NOT EXISTS(SELECT 1 FROM corrections x WHERE x.message=m.id AND x.resolved!=0) AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id)",new String[]{Integer.toString(anchor),Long.toString(peer)})) { c.moveToFirst(); return c.getInt(0); }
    }
    synchronized ArrayList<Long> rooms() {
        ArrayList<Long> result=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT id FROM rooms ORDER BY id",null)) { while(c.moveToNext()) result.add(-c.getLong(0)); }
        return result;
    }
    synchronized void leaveRoom(long dialog) { getWritableDatabase().execSQL("UPDATE rooms SET autojoin=0 WHERE id=?",new Object[]{-dialog}); }
    synchronized long roomUser(long dialog,String occupant,String realJid,long self) {
        if (!occupant.startsWith(room(dialog).jid+"/")) throw new IllegalArgumentException("Occupant belongs to another room");
        long user=realJid!=null ? peer(realJid) : peer(occupant);
        if (realJid==null) rosterName(user,occupant.substring(occupant.indexOf('/')+1));
        ContentValues values=new ContentValues(); values.put("room",-dialog); values.put("occupant",occupant); values.put("user",user); values.put("real_jid",realJid);
        getWritableDatabase().insertWithOnConflict("room_participants",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        if(realJid!=null) getWritableDatabase().update("room_participants",values,"room=? AND occupant=?",new String[]{Long.toString(-dialog),occupant});
        return user;
    }
    synchronized void roomState(com.negzero.monogram.xmpp.XmppRooms.State state,long self) {
        long dialog=knownRoom(state.room.toString()); if (dialog==0) return;
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues values=new ContentValues(); values.put("subject",state.subject);
            if(state.title!=null && !state.title.trim().isEmpty()) {
                try(Cursor c=db.rawQuery("SELECT name FROM room_bookmarks WHERE room=? AND present=1",new String[]{Long.toString(-dialog)})) {
                    if(!c.moveToFirst() || c.getString(0).isEmpty()) values.put("title",state.title);
                }
            }
            if (!state.nickname.isEmpty()) values.put("nickname",state.nickname);
            db.update("rooms",values,"id=?",new String[]{Long.toString(-dialog)});
            if(state.joined && !state.role.isEmpty()) {
                ContentValues permissions=new ContentValues(); permissions.put("room",-dialog); permissions.put("role",state.role); permissions.put("affiliation",state.affiliation);
                db.insertWithOnConflict("room_permissions",null,permissions,SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.execSQL("UPDATE room_participants SET active=0 WHERE room=?",new Object[]{-dialog});
            for (com.negzero.monogram.xmpp.XmppRooms.Occupant occupant:state.occupants) {
                roomUser(dialog,occupant.address,occupant.realJid,self);
                db.execSQL("UPDATE room_participants SET active=1,real_jid=?,user=? WHERE room=? AND occupant=?",new Object[]{occupant.realJid,occupant.realJid==null ? peer(occupant.address) : peer(occupant.realJid),-dialog,occupant.address});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized ArrayList<TLRPC.User> roomUsers(long dialog,long self) {
        java.util.Set<Long> ids=new java.util.LinkedHashSet<>(); ids.add(self);
        try (Cursor c=getReadableDatabase().rawQuery("SELECT user FROM room_participants WHERE room=? UNION SELECT s.user FROM room_senders s JOIN messages m ON m.id=s.message WHERE m.peer=?",new String[]{Long.toString(-dialog),Long.toString(dialog)})) {
            while(c.moveToNext()) ids.add(c.getLong(0));
        }
        ArrayList<TLRPC.User> result=new ArrayList<>(); for (long id:ids) result.add(user(id,self)); return result;
    }
    synchronized TLRPC.Chat roomChat(long dialog) {
        Room room=room(dialog); TLRPC.TL_chat chat=new TLRPC.TL_chat();
        chat.id=-dialog; chat.title=room.title; chat.photo=new TLRPC.TL_chatPhotoEmpty(); chat.version=1; chat.left=!room.autojoin;
        // A missing rights object makes modern Telegram deny every send action.
        chat.default_banned_rights=new TLRPC.TL_chatBannedRights(); chat.flags|=262144;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT role FROM room_permissions WHERE room=?",new String[]{Long.toString(-dialog)})) {
            if(c.moveToFirst() && "visitor".equals(c.getString(0))) {
                chat.default_banned_rights.send_messages=true; chat.default_banned_rights.send_plain=true; chat.default_banned_rights.send_media=true;
                chat.default_banned_rights.send_photos=true; chat.default_banned_rights.send_videos=true; chat.default_banned_rights.send_docs=true;
                chat.default_banned_rights.send_audios=true; chat.default_banned_rights.send_voices=true; chat.default_banned_rights.send_roundvideos=true;
                chat.default_banned_rights.send_stickers=true; chat.default_banned_rights.send_gifs=true; chat.default_banned_rights.send_polls=true;
            }
        }
        java.io.File avatar=avatar(dialog);
        if(avatar!=null && avatar.isFile()) {
            chat.photo=new TLRPC.TL_chatPhoto(); chat.photo.photo_id=MonogramProfiles.photo(dialog,avatar).id;
            chat.photo.photo_small=chat.photo.photo_big=MonogramProfiles.location(dialog); chat.photo.dc_id=Integer.MIN_VALUE;
        }
        try (Cursor c=getReadableDatabase().rawQuery("SELECT count(DISTINCT user) FROM room_participants WHERE room=? AND active=1",new String[]{Long.toString(-dialog)})) { c.moveToFirst(); chat.participants_count=c.getInt(0); }
        return chat;
    }
    synchronized TLRPC.TL_messages_chatFull roomFull(long dialog,long self) {
        Room room=room(dialog); TLRPC.TL_messages_chatFull result=new TLRPC.TL_messages_chatFull();
        result.chats.add(roomChat(dialog)); result.users.addAll(roomUsers(dialog,self));
        TLRPC.TL_chatFull full=new TLRPC.TL_chatFull(); full.id=-dialog; full.about=room.jid+(room.subject.isEmpty() ? "" : "\n\n"+room.subject);
        full.notify_settings=new TLRPC.TL_peerNotifySettings(); full.participants=new TLRPC.TL_chatParticipants(); full.participants.chat_id=-dialog; full.participants.version=1;
        try (Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT user FROM room_participants WHERE room=? AND active=1",new String[]{Long.toString(-dialog)})) {
            while(c.moveToNext()) { TLRPC.TL_chatParticipant participant=new TLRPC.TL_chatParticipant(); participant.user_id=c.getLong(0); full.participants.participants.add(participant); }
        }
        full.chat_photo=MonogramProfiles.photo(dialog,avatar(dialog));
        if(full.chat_photo instanceof TLRPC.TL_photo) full.flags|=4;
        result.full_chat=full; return result;
    }
    synchronized int saveRoom(com.negzero.monogram.xmpp.XmppRoomMessage message,long self) {
        return saveRoom(message,self,null);
    }
    synchronized int saveRoom(com.negzero.monogram.xmpp.XmppRoomMessage message,long self,java.util.Set<Integer> replaced) {
        if(message.retraction!=null) { retractions.save(message); return 0; }
        if(message.reactions!=null) { reactions.save(message,self); return 0; }
        long dialog=knownRoom(message.room.toString()); if (dialog==0) throw new IllegalArgumentException("Unknown room message");
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            int existing=0;
            for (Map.Entry<String,String> alias:message.ids.entrySet()) {
                String value=(alias.getKey().equals("archive") || alias.getKey().equals("mam")) ? alias.getValue() : message.sender+"\n"+alias.getValue();
                try (Cursor c=db.rawQuery("SELECT DISTINCT message FROM message_ids WHERE peer=? AND kind=? AND value=? AND namespace=?",new String[]{Long.toString(dialog),alias.getKey(),value,Integer.toString(message.encryption.ordinal())})) {
                    while(c.moveToNext()) {
                        int match=c.getInt(0);
                        if(!alias.getKey().equals("archive") && !alias.getKey().equals("mam") && message.ids.get("archive")!=null) {
                            try(Cursor authority=db.rawQuery("SELECT value FROM message_ids WHERE message=? AND kind='archive'",new String[]{Integer.toString(match)})) {
                                if(authority.moveToFirst() && !message.ids.get("archive").equals(authority.getString(0))) continue;
                            }
                        }
                        if(existing!=0 && existing!=match) throw new IllegalStateException("Conflicting room IDs"); existing=match;
                    }
                }
            }
            if(rememberDeleted(db,existing,dialog,message.ids,message.sender,message.outgoing,message.encryption.ordinal(),true)) {
                db.setTransactionSuccessful(); return 0;
            }
            ContentValues values=new ContentValues(); values.put("body",message.readable ? message.body : "Encrypted room message unavailable on this device");
            values.put("encrypted",message.encrypted); values.put("encryption",message.encryption.ordinal()); values.put("readable",message.readable);
            long seconds=message.timestamp/1000; if(seconds<0 || seconds>Integer.MAX_VALUE) throw new IllegalArgumentException("Unsupported date");
            values.put("time",seconds); values.put("time_ms",message.timestamp);
            int id=existing; boolean changed=false;
            if (existing==0) {
                values.put("peer",dialog); values.put("stanza",UUID.randomUUID().toString()); values.put("outgoing",message.outgoing);
                long inserted=db.insertOrThrow("messages",null,values); if(inserted>Integer.MAX_VALUE) throw new IllegalStateException("Message ID space exhausted"); id=(int)inserted; changed=true;
                long sender=message.outgoing ? self : roomUser(dialog,message.sender,message.realSender,self);
                ContentValues author=new ContentValues(); author.put("message",id); author.put("user",sender); db.insertOrThrow("room_senders",null,author);
            } else try (Cursor c=db.rawQuery("SELECT readable,time_ms FROM messages WHERE id=?",new String[]{Integer.toString(id)})) {
                if(c.moveToFirst()) {
                    if(c.getInt(0)==0 && message.readable) { db.update("messages",values,"id=?",new String[]{Integer.toString(id)}); changed=true; }
                    else if(message.archived && c.getLong(1)!=message.timestamp) {
                        ContentValues date=new ContentValues(); date.put("time",seconds); date.put("time_ms",message.timestamp);
                        db.update("messages",date,"id=?",new String[]{Integer.toString(id)}); changed=true;
                    }
                }
            }
            rememberFingerprint(db,id,message.readable,message.encryption.ordinal(),message.senderFingerprint);
            if(message.realSenderVerified && message.realSender!=null) {
                long sender=roomUser(dialog,message.sender,message.realSender,self);
                try(Cursor c=db.rawQuery("SELECT s.user,m.outgoing,CASE WHEN a.verified=1 THEN a.real_jid END FROM room_senders s JOIN messages m ON m.id=s.message LEFT JOIN room_message_actors a ON a.message=m.id WHERE m.id=?",new String[]{Integer.toString(id)})) {
                    if(c.moveToFirst()) {
                        if(!c.isNull(2) && !message.realSender.equals(c.getString(2))) throw new IllegalStateException("Conflicting room authors");
                        if(c.getLong(0)!=sender || (c.getInt(1)!=0)!=message.outgoing) {
                            db.execSQL("UPDATE room_senders SET user=? WHERE message=?",new Object[]{sender,id});
                            db.execSQL("UPDATE messages SET outgoing=? WHERE id=?",new Object[]{message.outgoing ? 1 : 0,id}); changed=true;
                        }
                    }
                }
            }
            ContentValues actor=new ContentValues(); actor.put("message",id); actor.put("occupant",message.sender);
            db.insertWithOnConflict("room_message_actors",null,actor,SQLiteDatabase.CONFLICT_IGNORE);
            actor.remove("message");
            try(Cursor c=db.rawQuery("SELECT stable_id,verified,real_jid FROM room_message_actors WHERE message=?",new String[]{Integer.toString(id)})) {
                c.moveToFirst();
                if(message.occupantId!=null) {
                    if(!c.isNull(0) && !message.occupantId.equals(c.getString(0))) throw new IllegalStateException("Conflicting stable room authors");
                    actor.put("stable_id",message.occupantId); changed|=c.isNull(0);
                }
                if(message.realSenderVerified && message.realSender!=null) {
                    actor.put("real_jid",message.realSender); actor.put("verified",1); changed|=c.getInt(1)==0;
                }
            }
            db.update("room_message_actors",actor,"message=?",new String[]{Integer.toString(id)});
            if(message.correction!=null) {
                ContentValues correction=new ContentValues(); correction.put("message",id); correction.put("target",message.correction.id);
                changed|=db.insertWithOnConflict("corrections",null,correction,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
            }
            if(message.reply!=null) changed|=saveReply(db,id,message.reply);
            for (Map.Entry<String,String> alias:message.ids.entrySet()) {
                ContentValues identity=new ContentValues(); identity.put("peer",dialog); identity.put("outgoing",message.outgoing); identity.put("kind",alias.getKey());
                identity.put("value",(alias.getKey().equals("archive") || alias.getKey().equals("mam")) ? alias.getValue() : message.sender+"\n"+alias.getValue()); identity.put("namespace",message.encryption.ordinal()); identity.put("message",id);
                db.insertWithOnConflict("message_ids",null,identity,SQLiteDatabase.CONFLICT_IGNORE);
            }
            if(message.attachment!=null) {
                ContentValues attachment=new ContentValues(); attachment.put("message",id); attachment.put("url",message.attachment.wireUrl); attachment.put("caption",message.caption);
                if(message.sharing!=null) attachment.put("sharing",message.sharing.xml());
                changed|=db.insertWithOnConflict("attachments",null,attachment,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
                if(message.sharing!=null) {
                    attachment.remove("message"); attachment.remove("url");
                    changed|=db.update("attachments",attachment,"message=? AND url=? AND sharing IS NULL",new String[]{Integer.toString(id),message.attachment.wireUrl})>0;
                }
            }
            db.setTransactionSuccessful();
            if(changed && existing!=0 && replaced!=null) replaced.add(id);
            return changed ? id : 0;
        } finally { db.endTransaction(); }
    }
    private void addBookmarks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS room_bookmarks (room INTEGER PRIMARY KEY,payload TEXT,name TEXT NOT NULL DEFAULT '',revision INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 0,present INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("INSERT OR IGNORE INTO room_bookmarks(room,dirty) SELECT id,1 FROM rooms");
    }
    static final class PendingBookmark {
        final long dialog,revision;
        final String payload,jid,nickname;
        final boolean autojoin;
        PendingBookmark(Cursor c) { dialog=-c.getLong(0); revision=c.getLong(1); payload=c.getString(2); jid=c.getString(3); nickname=c.getString(4); autojoin=c.getInt(5)!=0; }
    }
    synchronized void dirtyBookmark(long dialog) {
        room(dialog);
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO room_bookmarks(room) VALUES(?)",new Object[]{-dialog});
        getWritableDatabase().execSQL("UPDATE room_bookmarks SET dirty=1,present=1,revision=revision+1 WHERE room=?",new Object[]{-dialog});
    }
    synchronized ArrayList<PendingBookmark> pendingBookmarks() {
        ArrayList<PendingBookmark> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT b.room,b.revision,b.payload,r.jid,r.nickname,r.autojoin FROM room_bookmarks b JOIN rooms r ON r.id=b.room WHERE b.dirty=1",null)) {
            while(c.moveToNext()) result.add(new PendingBookmark(c));
        }
        return result;
    }
    synchronized void bookmarkSaved(long dialog,long revision,String encryptedPayload) {
        getWritableDatabase().execSQL("UPDATE room_bookmarks SET dirty=0,present=1,payload=? WHERE room=? AND revision=?",new Object[]{encryptedPayload,-dialog,revision});
    }
    synchronized void importBookmark(com.negzero.monogram.xmpp.XmppBookmarks.Bookmark bookmark,String encryptedPayload,String defaultNickname) {
        long dialog=knownRoom(bookmark.jid);
        String nickname=bookmark.nickname.isEmpty() ? defaultNickname : bookmark.nickname;
        if(dialog==0) dialog=room(bookmark.jid,nickname);
        SQLiteDatabase db=getWritableDatabase();
        boolean pending=false;
        try(Cursor c=db.rawQuery("SELECT dirty,revision FROM room_bookmarks WHERE room=?",new String[]{Long.toString(-dialog)})) { pending=c.moveToFirst() && c.getInt(0)!=0 && c.getLong(1)>0; }
        ContentValues metadata=new ContentValues(); metadata.put("room",-dialog); metadata.put("payload",encryptedPayload); metadata.put("name",bookmark.name); metadata.put("present",1);
        db.insertWithOnConflict("room_bookmarks",null,metadata,SQLiteDatabase.CONFLICT_IGNORE);
        db.update("room_bookmarks",metadata,"room=?",new String[]{Long.toString(-dialog)});
        if(!pending) {
            db.execSQL("UPDATE room_bookmarks SET dirty=0 WHERE room=?",new Object[]{-dialog});
            ContentValues desired=new ContentValues(); desired.put("nickname",nickname); desired.put("autojoin",bookmark.autojoin);
            if(!bookmark.name.isEmpty()) desired.put("title",bookmark.name);
            db.update("rooms",desired,"id=?",new String[]{Long.toString(-dialog)});
        }
    }
    synchronized void missingBookmarks(java.util.Set<String> present) {
        SQLiteDatabase db=getWritableDatabase();
        try(Cursor c=db.rawQuery("SELECT b.room,r.jid FROM room_bookmarks b JOIN rooms r ON r.id=b.room WHERE b.present=1 AND b.dirty=0 AND b.payload IS NOT NULL",null)) {
            while(c.moveToNext()) if(!present.contains(c.getString(1))) {
                db.execSQL("UPDATE room_bookmarks SET present=0 WHERE room=?",new Object[]{c.getLong(0)});
                db.execSQL("UPDATE rooms SET autojoin=0 WHERE id=?",new Object[]{c.getLong(0)});
            }
        }
    }
    synchronized boolean saveRoomArchive(long dialog,com.negzero.monogram.xmpp.XmppRooms.ArchivePage page,boolean backwards,long self,java.util.Set<Integer> updated) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            boolean changed=false; long order;
            try(Cursor c=db.rawQuery("SELECT "+(backwards ? "min" : "max")+"(archive_order) FROM messages WHERE peer=?",new String[]{Long.toString(dialog)})) {
                c.moveToFirst(); order=backwards ? Math.min(0,c.getLong(0))-page.messages.size() : Math.max(0,c.getLong(0))+1;
            }
            for(com.negzero.monogram.xmpp.XmppRoomMessage message:page.messages) {
                if(!jid(dialog).equals(message.room.toString())) throw new IllegalArgumentException("Room archive belongs to another room");
                int id=saveRoom(message,self); changed|=id!=0; if(id!=0) updated.add(id);
                db.execSQL("UPDATE messages SET archive_order=? WHERE archive_order=0 AND id IN (SELECT message FROM message_ids WHERE peer=? AND kind='mam' AND value=? AND namespace=?)",new Object[]{order++,dialog,message.ids.get("mam"),message.encryption.ordinal()});
            }
            ContentValues empty=new ContentValues(); empty.put("scope",archiveScope(dialog)); db.insertWithOnConflict("archive_state",null,empty,SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues cursor=new ContentValues();
            if(backwards) { if(page.firstId!=null) cursor.put("oldest",page.firstId); cursor.put("complete",page.complete || page.firstId==null); }
            if(archiveCursor(dialog,false)==null || !backwards) if(page.lastId!=null) cursor.put("newest",page.lastId);
            if(cursor.size()>0) db.update("archive_state",cursor,"scope=?",new String[]{archiveScope(dialog)});
            db.setTransactionSuccessful(); return changed;
        } finally { db.endTransaction(); }
    }
    synchronized com.negzero.monogram.xmpp.XmppProfile profile(long peer) {
        if (profileCache.containsKey(peer)) return profileCache.get(peer);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT card,nickname FROM profiles WHERE peer=?", new String[]{Long.toString(peer)})) {
            if (!c.moveToFirst() || c.isNull(0)) return null;
            com.negzero.monogram.xmpp.XmppProfile result = new com.negzero.monogram.xmpp.XmppProfile(c.getString(0), c.getString(1), new byte[0]);
            profileCache.put(peer, result); return result;
        } catch (Exception invalid) { return null; }
    }
    synchronized void rosterName(long peer, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues empty = new ContentValues(); empty.put("peer", peer);
        db.insertWithOnConflict("profiles", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues value = new ContentValues(); value.put("roster_name", name == null ? "" : name);
        db.update("profiles", value, "peer=?", new String[]{Long.toString(peer)});
    }
    synchronized void profile(long peer, com.negzero.monogram.xmpp.XmppProfile profile) throws Exception {
        byte[] png;
        try { png = MonogramProfiles.png(profile.avatar); }
        catch (IllegalArgumentException invalid) { png = new byte[0]; }
        java.io.File avatar = png.length == 0 ? null : MonogramProfiles.cache(storeContext, database, png);
        ContentValues empty = new ContentValues(); empty.put("peer", peer);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("profiles", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues value = new ContentValues(); value.put("card", profile.xml()); value.put("nickname", profile.nickname);
        if (avatar == null) value.putNull("avatar"); else value.put("avatar", avatar.getAbsolutePath());
        db.update("profiles", value, "peer=?", new String[]{Long.toString(peer)});
        profileCache.remove(peer);
    }
    synchronized java.io.File avatar(long peer) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT avatar FROM profiles WHERE peer=?", new String[]{Long.toString(peer)})) {
            return c.moveToFirst() && !c.isNull(0) ? new java.io.File(c.getString(0)) : null;
        }
    }
    synchronized TLRPC.User user(long id, long self) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        String jid = jid(id), roster = "";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT roster_name FROM profiles WHERE peer=?", new String[]{Long.toString(id)})) {
            if (c.moveToFirst()) roster = c.getString(0);
        }
        com.negzero.monogram.xmpp.XmppProfile profile = profile(id);
        user.id = id; user.first_name = profile == null ? (id == self || roster.isEmpty() ? jid : roster) : profile.displayName(id == self ? "" : roster, jid);
        if (id == self && user.first_name.equals(jid) && jid.indexOf('@') > 0) user.first_name = jid.substring(0, jid.indexOf('@'));
        user.last_name = ""; user.phone = "";
        user.self = id == self; user.contact = id != self; user.flags = 2;
        java.io.File avatar = avatar(id);
        if (avatar != null && avatar.isFile()) {
            user.photo = new TLRPC.TL_userProfilePhoto(); user.flags |= 32;
            user.photo.photo_id = MonogramProfiles.photo(id, avatar).id;
            user.photo.photo_small = user.photo.photo_big = MonogramProfiles.location(id); user.photo.dc_id = Integer.MIN_VALUE;
        } else user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        user.status = new TLRPC.TL_userStatusEmpty();
        return user;
    }
    synchronized TLRPC.UserFull fullUser(long id, long self) {
        TLRPC.TL_userFull full = new TLRPC.TL_userFull(); full.id = id; full.user = user(id, self);
        // Telegram's private read-date lookup has no XMPP implementation.
        full.read_dates_private = true;
        full.settings = new TLRPC.TL_peerSettings(); full.notify_settings = new TLRPC.TL_peerNotifySettings();
        com.negzero.monogram.xmpp.XmppProfile profile = profile(id);
        full.about = profile == null ? "" : profile.field("DESC");
        if (!full.about.isEmpty()) full.flags |= 2;
        full.profile_photo = MonogramProfiles.photo(id, avatar(id));
        if (full.profile_photo instanceof TLRPC.TL_photo) full.flags |= 4;
        return full;
    }
    synchronized int save(XmppMessage message) { return save(message,null); }
    synchronized int save(XmppMessage message,java.util.Set<Integer> replaced) {
        if(message.retraction!=null) { retractions.save(message); return 0; }
        if(message.reactions!=null) { long self=message.outgoing ? peer(message.sender.split("/",2)[0]) : 0; reactions.save(message,self); return 0; }
        if(!message.readable && reactions.known(message)) return 0;
        long peer = peer(message.peer.toString());
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int existing = 0;
            for (Map.Entry<String, String> alias : message.ids.entrySet()) {
                try (Cursor c = db.rawQuery("SELECT message FROM message_ids WHERE peer=? AND outgoing=? AND kind=? AND value=? AND namespace=?",
                        new String[]{Long.toString(peer), message.outgoing ? "1" : "0", alias.getKey(), aliasValue(message, alias),Integer.toString(namespace(message))})) {
                    if (c.moveToFirst()) {
                        int matched = c.getInt(0);
                        if (existing != 0 && existing != matched) throw new IllegalStateException("Conflicting XMPP message IDs");
                        existing = matched;
                    }
                }
            }
            String legacy = message.ids.get("stanza");
            if (existing == 0 && legacy != null && namespace(message) == 0) {
                try (Cursor c = db.rawQuery("SELECT message FROM message_ids WHERE peer=? AND outgoing=? AND kind='legacy-stanza' AND value=?",
                        new String[]{Long.toString(peer), message.outgoing ? "1" : "0", legacy})) {
                    if (c.moveToFirst()) existing = c.getInt(0);
                }
            }
            if(rememberDeleted(db,existing,peer,message.ids,message.sender,message.outgoing,namespace(message),false)) {
                db.setTransactionSuccessful(); return 0;
            }
            ContentValues values = new ContentValues();
            values.put("body", message.readable ? message.body : "Encrypted message unavailable on this device");
            values.put("encrypted", message.encrypted); values.put("readable", message.readable);
            values.put("encryption",protocol(message));
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
                try (Cursor c = db.rawQuery("SELECT readable,time_ms,encrypted,encryption FROM messages WHERE id=?", new String[]{Integer.toString(existing)})) {
                    if (!c.moveToFirst()) throw new IllegalStateException("Missing XMPP message");
                    if ((c.getInt(2) == 1) != message.encrypted) throw new IllegalStateException("Conflicting message encryption modes");
                    if (c.getInt(3) >= 0 && c.getInt(3) != protocol(message)) throw new IllegalStateException("Conflicting message encryption protocols");
                    if (c.getInt(3) < 0) {
                        ContentValues classification = new ContentValues(); classification.put("encryption",protocol(message));
                        db.update("messages",classification,"id=?",new String[]{Integer.toString(existing)});
                    }
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
                identity.put("namespace",namespace(message));
                db.insertWithOnConflict("message_ids", null, identity, SQLiteDatabase.CONFLICT_IGNORE);
            }
            rememberFingerprint(db,id,message.readable,protocol(message),message.senderFingerprint);
            ContentValues sender = new ContentValues(); sender.put("message", id); sender.put("sender", message.sender);
            db.insertWithOnConflict("message_senders", null, sender, SQLiteDatabase.CONFLICT_IGNORE);
            if (message.correction != null) {
                ContentValues correction = new ContentValues(); correction.put("message", id); correction.put("target", message.correction.id);
                changed |= db.insertWithOnConflict("corrections", null, correction, SQLiteDatabase.CONFLICT_IGNORE) != -1;
            }
            if (message.reply != null) changed |= saveReply(db,id,message.reply);
            if (message.attachment != null) {
                ContentValues attachment = new ContentValues(); attachment.put("message", id); attachment.put("url", message.attachment.wireUrl);
                attachment.put("caption", message.caption);
                if (message.sharing != null) attachment.put("sharing", message.sharing.xml());
                db.insertWithOnConflict("attachments", null, attachment, SQLiteDatabase.CONFLICT_IGNORE);
                if (message.sharing != null) {
                    attachment.remove("message"); attachment.remove("url");
                    changed |= db.update("attachments", attachment, "message=? AND url=? AND sharing IS NULL", new String[]{Integer.toString(id), message.attachment.wireUrl}) > 0;
                }
            }
            if (legacy != null && namespace(message) == 0) db.delete("message_ids", "kind='legacy-stanza' AND message=? AND value=?", new String[]{Integer.toString(id), legacy});
            db.setTransactionSuccessful();
            if(changed && existing!=0 && replaced!=null) replaced.add(id);
            return changed ? id : 0;
        } finally { db.endTransaction(); }
    }
    private boolean saveReply(SQLiteDatabase db,int id,ReplyReference reference) {
        ContentValues reply=new ContentValues(); reply.put("message",id); reply.put("author",reference.to);
        reply.put("target",reference.id); reply.put("fallback",reference.ranges);
        boolean changed=db.insertWithOnConflict("replies",null,reply,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
        if(!reference.ranges.isEmpty()) {
            ContentValues fallback=new ContentValues(); fallback.put("fallback",reference.ranges);
            String[] args=reference.to==null ? new String[]{Integer.toString(id),reference.id} : new String[]{Integer.toString(id),reference.id,reference.to};
            changed|=db.update("replies",fallback,"message=? AND target=? AND fallback='' AND "+(reference.to==null ? "author IS NULL" : "author=?"),args)>0;
        }
        return changed;
    }
    private boolean sameRoomAuthor(int first,int second) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT a.stable_id,b.stable_id,a.verified,b.verified,a.real_jid,b.real_jid FROM room_message_actors a JOIN room_message_actors b ON b.message=? WHERE a.message=?",new String[]{Integer.toString(second),Integer.toString(first)})) {
            if(!c.moveToFirst()) return false;
            if(!c.isNull(0) && !c.isNull(1)) return c.getString(0).equals(c.getString(1))
                && !(c.getInt(2)==1 && c.getInt(3)==1 && !java.util.Objects.equals(c.getString(4),c.getString(5)));
            return c.getInt(2)==1 && c.getInt(3)==1 && !c.isNull(4) && c.getString(4).equals(c.getString(5));
        }
    }
    private static String aliasValue(XmppMessage message, Map.Entry<String, String> alias) {
        // Client stanza IDs need only be unique within a stream, unlike origin/archive IDs.
        return alias.getKey().equals("stanza") ? message.sender + "\n" + alias.getValue() : alias.getValue();
    }
    static final class CorrectionChanges {
        final java.util.Set<Integer> edited = new java.util.HashSet<>(), hidden = new java.util.HashSet<>(), shown = new java.util.HashSet<>();
    }
    synchronized boolean hiddenCorrection(int id) {
        if(deleted(id)) return true;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM corrections WHERE message=? AND resolved!=0 UNION SELECT 1 FROM reaction_sources WHERE message=?", new String[]{Integer.toString(id),Integer.toString(id)})) { return c.moveToFirst(); }
    }
    private int payload(int original) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT message FROM applied_corrections WHERE original=?", new String[]{Integer.toString(original)})) { return c.moveToFirst() ? c.getInt(0) : original; }
    }
    /** Re-evaluate late originals/aliases too: a newly discovered ID collision must undo an unsafe match. */
    synchronized CorrectionChanges reconcileCorrections(long peer) { return reconcileCorrections(peer, java.util.Collections.emptySet()); }
    synchronized CorrectionChanges reconcileCorrections(long peer, java.util.Set<Integer> updated) {
        SQLiteDatabase db = getWritableDatabase(); CorrectionChanges changes = new CorrectionChanges();
        db.beginTransaction();
        try {
            java.util.Map<Integer, Integer> protectedModes = new java.util.HashMap<>();
            try (Cursor c = db.rawQuery("SELECT c.message,c.target,c.resolved,m.outgoing,CASE WHEN m.encryption<0 THEN m.encrypted ELSE m.encryption END FROM corrections c JOIN messages m ON m.id=c.message WHERE m.peer=? ORDER BY m.time_ms,m.archive_order,m.id", new String[]{Long.toString(peer)})) {
                while (c.moveToNext()) {
                    int event = c.getInt(0), old = c.getInt(2), target = 0;
                    // XEP-0308 uses the client stanza ID, never an archive ID or an origin-only alias.
                    try (Cursor matches = db.rawQuery("SELECT DISTINCT i.message FROM message_ids i JOIN messages m ON m.id=i.message WHERE i.peer=? AND i.outgoing=? AND i.kind='stanza' AND instr(i.value,char(10))>0 AND substr(i.value,instr(i.value,char(10))+1)=? AND NOT EXISTS(SELECT 1 FROM corrections x WHERE x.message=m.id)",
                            new String[]{Long.toString(peer), c.getInt(3) == 1 ? "1" : "0", c.getString(1)})) {
                        while(matches.moveToNext()) {
                            int match=matches.getInt(0); if(peer<0 && !sameRoomAuthor(event,match)) continue;
                            if(target!=0) { target=0; break; } target=match;
                        }
                    }
                    if (target != 0) {
                        try (Cursor original = db.rawQuery("SELECT CASE WHEN encryption<0 THEN encrypted ELSE encryption END FROM messages WHERE id=?", new String[]{Integer.toString(target)})) {
                            if (!original.moveToFirst() || Math.max(original.getInt(0), protectedModes.getOrDefault(target, 0)) > c.getInt(4)) target = 0;
                        }
                        // shortcut: text corrections only; support media replacement with the caption/file-sharing implementation.
                        try (Cursor attachment = db.rawQuery("SELECT 1 FROM attachments WHERE message IN (?,?)", new String[]{Integer.toString(target), Integer.toString(event)})) {
                            if (attachment.moveToFirst()) target = 0;
                        }
                    }
                    if (target != 0) protectedModes.put(target, c.getInt(4));
                    if (old != target) {
                        ContentValues resolved = new ContentValues(); resolved.put("resolved", target);
                        db.update("corrections", resolved, "message=?", new String[]{Integer.toString(event)});
                        if (old == 0 && target != 0) changes.hidden.add(event);
                        if (old != 0 && target == 0) changes.shown.add(event);
                    }
                }
            }
            java.util.Map<Integer, Integer> latest = new java.util.HashMap<>(), previous = new java.util.HashMap<>();
            try (Cursor c = db.rawQuery("SELECT a.original,a.message FROM applied_corrections a JOIN messages m ON m.id=a.original WHERE m.peer=?", new String[]{Long.toString(peer)})) {
                while (c.moveToNext()) previous.put(c.getInt(0), c.getInt(1));
            }
            try (Cursor c = db.rawQuery("SELECT c.resolved,c.message FROM corrections c JOIN messages m ON m.id=c.message WHERE m.peer=? AND c.resolved!=0 ORDER BY m.time_ms,m.archive_order,m.id", new String[]{Long.toString(peer)})) {
                while (c.moveToNext()) latest.put(c.getInt(0), c.getInt(1));
            }
            java.util.Set<Integer> originals = new java.util.HashSet<>(previous.keySet()); originals.addAll(latest.keySet());
            for (int original : originals) {
                Integer old = previous.get(original), next = latest.get(original);
                if (next != null && updated.contains(next)) changes.edited.add(original);
                if (java.util.Objects.equals(old, next)) continue;
                changes.edited.add(original);
                if (next == null) db.delete("applied_corrections", "original=?", new String[]{Integer.toString(original)});
                else {
                    ContentValues applied = new ContentValues(); applied.put("original", original); applied.put("message", next);
                    db.insertWithOnConflict("applied_corrections", null, applied, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            changes.edited.removeIf(this::deleted); changes.shown.removeIf(this::deleted);
            db.setTransactionSuccessful(); return changes;
        } finally { db.endTransaction(); }
    }
    static final class EditTarget {
        final com.negzero.monogram.xmpp.MessageCorrection correction;
        final boolean encrypted;
        final XmppMessage.Encryption encryption;
        final ReplyReference reply;
        EditTarget(String id, int protocol, ReplyReference reply) {
            this.correction = new com.negzero.monogram.xmpp.MessageCorrection(id); this.encrypted = protocol > 0; this.reply = reply;
            this.encryption = protocol == 2 ? XmppMessage.Encryption.OMEMO2 : protocol == 1 ? XmppMessage.Encryption.LEGACY_OMEMO : XmppMessage.Encryption.PLAIN;
        }
    }
    synchronized EditTarget editTarget(int id, long peer, long self) {
        TLRPC.Message original = getMessage(id, self);
        if (!original.out || original.dialog_id != peer || MonogramAttachments.has(original)) throw new IllegalArgumentException("Only your text messages can be edited");
        SQLiteDatabase db = getReadableDatabase(); String wire = null; int protocol;
        if(peer<0) try(Cursor actor=db.rawQuery("SELECT 1 FROM room_message_actors WHERE message=? AND verified=1 AND real_jid=?",new String[]{Integer.toString(id),jid(self)})) {
            if(!actor.moveToFirst()) throw new IllegalArgumentException("Original room author is not verified; refresh room history");
        }
        try (Cursor c = db.rawQuery("SELECT CASE WHEN encryption<0 THEN encrypted ELSE encryption END,readable FROM messages WHERE id=? AND NOT EXISTS(SELECT 1 FROM corrections WHERE message=?)", new String[]{Integer.toString(id), Integer.toString(id)})) {
            if (!c.moveToFirst() || c.getInt(1) == 0) throw new IllegalArgumentException("Original message unavailable"); protocol = c.getInt(0);
        }
        try (Cursor c = db.rawQuery("SELECT readable,CASE WHEN encryption<0 THEN encrypted ELSE encryption END FROM messages WHERE id=?", new String[]{Integer.toString(payload(id))})) {
            if (!c.moveToFirst() || c.getInt(0) == 0) throw new IllegalArgumentException("Latest correction unavailable");
            protocol = Math.max(protocol,c.getInt(1));
        }
        try (Cursor c = db.rawQuery("SELECT substr(value,instr(value,char(10))+1) FROM message_ids WHERE message=? AND kind='stanza' ORDER BY rowid LIMIT 1", new String[]{Integer.toString(id)})) {
            if (c.moveToFirst()) wire = c.getString(0);
        }
        if (wire == null) throw new IllegalArgumentException("Original client message ID unavailable");
        // Refuse reused IDs, including across the account's different resources.
        try (Cursor c = db.rawQuery("SELECT count(DISTINCT message) FROM message_ids WHERE peer=? AND outgoing=1 AND kind='stanza' AND substr(value,instr(value,char(10))+1)=?", new String[]{Long.toString(peer), wire})) {
            c.moveToFirst(); if (c.getInt(0) != 1) throw new IllegalArgumentException("Ambiguous original message ID");
        }
        ReplyReference reply = null;
        try (Cursor c = db.rawQuery("SELECT author,target FROM replies WHERE message=?", new String[]{Integer.toString(payload(id))})) {
            if (c.moveToFirst()) reply = new ReplyReference(c.getString(0), c.getString(1));
        }
        return new EditTarget(wire, protocol, reply);
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
        return archiveCursor(0, oldest);
    }
    private static String archiveScope(long peer) { return peer == 0 ? "direct" : peer<0 ? "room:"+-peer : "direct:"+peer; }
    synchronized void resetArchiveCursor(long peer) {
        getWritableDatabase().delete("archive_state", "scope=?", new String[]{archiveScope(peer)});
    }
    synchronized java.util.Set<Long> unreadableModernPeers() {
        java.util.Set<Long> peers=new java.util.HashSet<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT peer FROM messages WHERE encryption=2 AND readable=0",null)) {
            while(c.moveToNext()) peers.add(c.getLong(0));
        }
        return peers;
    }
    synchronized String archiveCursor(long peer, boolean oldest) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + (oldest ? "oldest" : "newest") + " FROM archive_state WHERE scope=?", new String[]{archiveScope(peer)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
    synchronized boolean archiveComplete() {
        return archiveComplete(0);
    }
    synchronized boolean archiveComplete(long peer) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT complete FROM archive_state WHERE scope=?", new String[]{archiveScope(peer)})) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }
    synchronized boolean saveArchive(XmppSession.ArchivePage page, boolean backwards) { return saveArchive(page, backwards, null); }
    synchronized boolean saveArchive(XmppSession.ArchivePage page, boolean backwards, java.util.Set<Integer> updated) {
        return saveArchive(0, page, backwards, updated);
    }
    synchronized boolean saveArchive(long scope, XmppSession.ArchivePage page, boolean backwards, java.util.Set<Integer> updated) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            boolean changed = false;
            long order;
            try (Cursor c = db.rawQuery("SELECT " + (backwards ? "min" : "max") + "(archive_order) FROM messages", null)) {
                c.moveToFirst(); order = backwards ? Math.min(0, c.getLong(0)) - page.messages.size() : Math.max(0, c.getLong(0)) + 1;
            }
            for (XmppMessage message : page.messages) {
                if (scope != 0 && !jid(scope).equals(message.peer.toString())) throw new IllegalArgumentException("Archive belongs to another chat");
                int saved = save(message); changed |= saved != 0;
                if (updated != null && saved != 0) updated.add(saved);
                db.execSQL("UPDATE messages SET archive_order=? WHERE archive_order=0 AND id=(SELECT message FROM message_ids WHERE peer=? AND outgoing=? AND kind='archive' AND value=? AND namespace=?)",
                        new Object[]{order++, peer(message.peer.toString()), message.outgoing ? 1 : 0, message.ids.get("archive"),namespace(message)});
            }
            ContentValues empty = new ContentValues(); empty.put("scope", archiveScope(scope));
            db.insertWithOnConflict("archive_state", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues cursor = new ContentValues();
            if (backwards) {
                if (page.firstId != null) cursor.put("oldest", page.firstId);
                cursor.put("complete", page.complete || page.firstId == null);
            }
            if (archiveCursor(scope, false) == null || !backwards) {
                if (page.lastId != null) cursor.put("newest", page.lastId);
            }
            if (cursor.size() > 0) db.update("archive_state", cursor, "scope=?", new String[]{archiveScope(scope)});
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }
    TLRPC.Message message(int id, long peer, long self, String body, boolean outgoing, int time) {
        return message(id, peer, self, body, outgoing, time, true);
    }
    private TLRPC.Message message(int id, long peer, long self, String body, boolean outgoing, int time, boolean embedReply) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id; message.date = time; message.message = body; message.out = outgoing; message.unread = !outgoing;
        message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = outgoing ? peer : self;
        if (peer < 0) { message.peer_id = new TLRPC.TL_peerChat(); message.peer_id.chat_id = -peer; }
        message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = outgoing ? self : peer;
        if(deleted(id)) { message.message=""; message.unread=false; message.media=new TLRPC.TL_messageMediaEmpty(); return message; }
        if (peer < 0) try (Cursor sender = getReadableDatabase().rawQuery("SELECT user FROM room_senders WHERE message=?", new String[]{Integer.toString(id)})) {
            if (!sender.moveToFirst()) throw new IllegalStateException("Missing room sender");
            message.from_id.user_id=sender.getLong(0);
        }
        message.flags = 256; message.dialog_id = peer;
        int payload = payload(id);
        if (payload != id) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT body,time FROM messages WHERE id=?", new String[]{Integer.toString(payload)})) {
                if (c.moveToFirst()) { body = c.getString(0); message.message = body; message.edit_date = c.getInt(1); message.flags |= TLRPC.MESSAGE_FLAG_EDITED; }
            }
        }
        message.media = new TLRPC.TL_messageMediaEmpty();
        decorate(message, payload);
        decorateReply(message, self, body, embedReply, payload);
        message.reactions=reactions.snapshot(id,self);
        message.flags|=1048576;
        return message;
    }
    private int resolveReply(long peer, long self, ReplyReference reply) {
        if(peer<0) {
            int found=0;
            try(Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT i.message,a.occupant FROM message_ids i JOIN room_message_actors a ON a.message=i.message WHERE i.peer=? AND i.kind='archive' AND i.value=?",new String[]{Long.toString(peer),reply.id})) {
                while(c.moveToNext()) {
                    if(reply.to!=null && !reply.to.equals(c.getString(1))) continue;
                    if(found!=0 && found!=c.getInt(0)) return 0; found=c.getInt(0);
                }
            }
            if(found!=0) try(Cursor c=getReadableDatabase().rawQuery("SELECT resolved FROM corrections WHERE message=? AND resolved!=0",new String[]{Integer.toString(found)})) { if(c.moveToFirst()) found=c.getInt(0); }
            return found;
        }
        String author = reply.to;
        boolean full = false;
        Integer direction = null;
        if (author != null) {
            try {
                org.jxmpp.jid.Jid jid = org.jxmpp.jid.impl.JidCreate.from(author);
                String bare = jid.asBareJid().toString(); full = jid.hasResource();
                if (bare.equals(jid(self))) direction = 1;
                else if (bare.equals(jid(peer))) direction = 0;
                else return 0;
            } catch (Exception invalid) { return 0; }
        }
        int found = 0;
        String sql = "SELECT DISTINCT i.message FROM message_ids i WHERE i.peer=? AND ((i.kind='origin' AND i.value=?) OR (i.kind='stanza' AND instr(i.value,char(10))>0 AND substr(i.value,instr(i.value,char(10))+1)=?))";
        ArrayList<String> args = new ArrayList<>(); args.add(Long.toString(peer)); args.add(reply.id); args.add(reply.id);
        if (direction != null) { sql += " AND i.outgoing=?"; args.add(Integer.toString(direction)); }
        if (full) { sql += " AND EXISTS(SELECT 1 FROM message_senders s WHERE s.message=i.message AND s.sender=?)"; args.add(author); }
        try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) { if (found != 0 && found != c.getInt(0)) return 0; found = c.getInt(0); }
        }
        if (found != 0) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT resolved FROM corrections WHERE message=? AND resolved!=0", new String[]{Integer.toString(found)})) { if (c.moveToFirst()) found = c.getInt(0); }
        }
        return found;
    }
    private synchronized void decorateReply(TLRPC.Message message, long self, String body, boolean embedReply, int payload) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT r.author,r.target,r.fallback,m.readable FROM replies r JOIN messages m ON m.id=r.message WHERE r.message=?", new String[]{Integer.toString(payload)})) {
            if (!c.moveToFirst()) return;
            ReplyReference reply = new ReplyReference(c.getString(0), c.getString(1), c.getString(2));
            int target = resolveReply(message.dialog_id, self, reply);
            if (target == message.id || deleted(target)) return;
            if (target == 0) {
                // Keep the fallback when the original is missing; never invent a local target ID.
                if (MonogramAttachments.has(message) && !reply.ranges.isEmpty()) {
                    message.message = reply.fallbackText(body) + message.message;
                    if (message.message.toLowerCase(java.util.Locale.ROOT).contains("aesgcm://")) message.message = "Reply to an unavailable attachment";
                }
                return;
            }
            message.reply_to = new TLRPC.TL_messageReplyHeader(); message.reply_to.flags = 16;
            message.reply_to.reply_to_msg_id = target; message.flags |= TLRPC.MESSAGE_FLAG_REPLY;
            if (!MonogramAttachments.has(message) && c.getInt(3) != 0) message.message = reply.withoutFallback(body);
            if (embedReply) message.replyMessage = getMessage(target, self, false);
        }
    }
    synchronized ReplyReference replyTo(int target, long peer, long self, boolean encrypted, String quote, int quoteOffset) {
        if(hiddenCorrection(target)) throw new IllegalArgumentException("Reply target is unavailable");
        TLRPC.Message original = getMessage(target, self, false);
        if (original.dialog_id != peer) throw new IllegalArgumentException("Reply target belongs to another conversation");
        String author = null, id = null;
        if(peer<0) {
            try(Cursor c=getReadableDatabase().rawQuery("SELECT i.value,a.occupant FROM message_ids i JOIN room_message_actors a ON a.message=i.message WHERE i.message=? AND i.kind='archive' ORDER BY i.rowid LIMIT 1",new String[]{Integer.toString(target)})) {
                if(c.moveToFirst()) { id=c.getString(0); author=c.getString(1); }
            }
        } else {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT kind,value FROM message_ids WHERE message=? AND kind IN ('origin','stanza') ORDER BY CASE kind WHEN 'origin' THEN 0 ELSE 1 END,rowid", new String[]{Integer.toString(target)})) {
            if (c.moveToFirst()) {
                id = c.getString(1);
                if ("stanza".equals(c.getString(0))) {
                    int split = id.indexOf('\n'); if (split < 1) throw new IllegalArgumentException("Reply target has no author");
                    author = id.substring(0, split); id = id.substring(split + 1);
                }
            }
        }
        }

        if (author == null) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT sender FROM message_senders WHERE message=? ORDER BY instr(sender,'/') DESC LIMIT 1", new String[]{Integer.toString(target)})) {
                if (c.moveToFirst()) author = c.getString(0);
            }
        }
        if (author == null) author = jid(original.out ? self : peer);
        ReplyReference reference = id==null ? ReplyReference.quoteOnly("") : new ReplyReference(author,id);
        // An encrypted original must never be quoted into a plaintext reply.
        try (Cursor c = getReadableDatabase().rawQuery("SELECT max(encrypted),min(readable) FROM messages WHERE id IN (?,?)", new String[]{Integer.toString(target),Integer.toString(payload(target))})) {
            c.moveToFirst();
            if(!encrypted && c.getInt(0)==1 || c.getInt(1)==0) {
                if(id==null) throw new IllegalArgumentException("Original message cannot be quoted in this encryption mode");
                return reference;
            }
        }
        String text = MonogramAttachments.has(original) && (original.message == null || original.message.isEmpty()) ? "Attachment" : original.message;
        if (quote != null && !quote.isEmpty()) {
            if (quoteOffset < 0 || quoteOffset > text.length() || !text.startsWith(quote, quoteOffset)) throw new IllegalArgumentException("Reply quote does not match the original");
            text = quote;
        }
        if (text != null && text.toLowerCase(java.util.Locale.ROOT).contains("aesgcm://")) text = "Attachment";
        return reference.quoted(text);
    }
    synchronized ArrayList<TLRPC.Message> changedReplies(long peer, long self) { return changedReplies(peer, self, java.util.Collections.emptySet()); }
    synchronized ArrayList<TLRPC.Message> changedReplies(long peer, long self, java.util.Set<Integer> updated) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT r.message,r.author,r.target,r.fallback,r.resolved FROM replies r JOIN messages m ON m.id=r.message WHERE m.peer=? AND NOT EXISTS(SELECT 1 FROM corrections x WHERE x.message=m.id AND x.resolved!=0)", new String[]{Long.toString(peer)})) {
            while (c.moveToNext()) {
                int target = resolveReply(peer, self, new ReplyReference(c.getString(1), c.getString(2), c.getString(3)));
                if (target == c.getInt(0) || deleted(target)) target = 0;
                if (target != c.getInt(4) || target != 0 && updated.contains(target)) {
                    ContentValues values = new ContentValues(); values.put("resolved", target);
                    getWritableDatabase().update("replies", values, "message=?", new String[]{Integer.toString(c.getInt(0))});
                    result.add(getMessage(c.getInt(0), self));
                }
            }
        }
        return result;
    }
    synchronized java.io.File attachmentFile(String url) throws Exception {
        return MonogramAttachments.cache(storeContext, database, url);
    }
    synchronized java.io.File attachmentFile(com.negzero.monogram.xmpp.FileShare sharing) throws Exception {
        return MonogramAttachments.cache(storeContext, database, sharing.cacheKey());
    }
    private com.negzero.monogram.xmpp.FileShare sharing(String url, String xml) throws Exception {
        if (xml == null) return null;
        com.negzero.monogram.xmpp.FileShare sharing = new com.negzero.monogram.xmpp.FileShare(xml);
        if (!sharing.link.wireUrl.equals(url)) throw new IllegalArgumentException("File source mismatch");
        return sharing;
    }
    private java.io.File attachmentPath(String url, String path, com.negzero.monogram.xmpp.FileShare sharing) throws Exception {
        // Metadata enrichment must not reuse old URL-only bytes without checking the new hashes.
        if (sharing != null) return attachmentFile(sharing);
        return path == null ? attachmentFile(url) : new java.io.File(path);
    }
    synchronized java.io.File attachmentPath(int id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,path,sharing FROM attachments WHERE message=?", new String[]{Integer.toString(id)})) {
            if (!c.moveToFirst()) return null;
            return attachmentPath(c.getString(0), c.getString(1), sharing(c.getString(0), c.getString(2)));
        } catch (Exception invalid) { return null; }
    }
    synchronized int encryptionOf(int id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT CASE WHEN encryption<0 THEN encrypted ELSE encryption END FROM messages WHERE id=?",new String[]{Integer.toString(payload(id))})) {
            if(!c.moveToFirst()) throw new IllegalArgumentException("Unknown message"); return c.getInt(0);
        }
    }
    synchronized com.negzero.monogram.xmpp.FileShare sharing(int id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,sharing FROM attachments WHERE message=?", new String[]{Integer.toString(id)})) {
            return c.moveToFirst() ? sharing(c.getString(0), c.getString(1)) : null;
        } catch (Exception invalid) { throw new IllegalStateException("Invalid cached file description", invalid); }
    }
    synchronized void attach(int id, String url, java.io.File path, TLRPC.MessageMedia media) {
        ContentValues empty = new ContentValues(); empty.put("message", id); empty.put("url", url);
        getWritableDatabase().insertWithOnConflict("attachments", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues values = new ContentValues(); values.put("path", path.getAbsolutePath());
        org.telegram.tgnet.SerializedData bytes = new org.telegram.tgnet.SerializedData();
        media.serializeToStream(bytes); values.put("media", bytes.toByteArray()); bytes.cleanup();
        if (getWritableDatabase().update("attachments", values, "message=? AND url=?", new String[]{Integer.toString(id), url}) != 1) throw new IllegalStateException("Attachment reference changed");
    }
    synchronized TLRPC.Message getMessage(int id, long self) { return getMessage(id, self, true); }
    private TLRPC.Message getMessage(int id, long self, boolean embedReply) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT peer,body,outgoing,time FROM messages WHERE id=?", new String[]{Integer.toString(id)})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("Unknown XMPP message");
            return message(id, c.getLong(0), self, c.getString(1), c.getInt(2) == 1, c.getInt(3), embedReply);
        }
    }
    private synchronized void decorate(TLRPC.Message message, int payload) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,path,media,caption,sharing FROM attachments WHERE message=?", new String[]{Integer.toString(payload)})) {
            if (c.moveToFirst()) {
                com.negzero.monogram.xmpp.FileLink link = com.negzero.monogram.xmpp.FileLink.parse(c.getString(0));
                com.negzero.monogram.xmpp.FileShare sharing = sharing(link.wireUrl, c.getString(4));
                java.io.File file = attachmentPath(link.wireUrl, c.getString(1), sharing);
                TLRPC.MessageMedia media = null;
                boolean sameCache = sharing == null || c.getString(1) != null && file.equals(new java.io.File(c.getString(1)));
                if (!c.isNull(2) && sameCache) {
                    org.telegram.tgnet.SerializedData bytes = new org.telegram.tgnet.SerializedData(c.getBlob(2));
                    media = TLRPC.MessageMedia.TLdeserialize(bytes, bytes.readInt32(true), true); bytes.cleanup();
                }
                String name = sharing == null ? link.fileName() : sharing.name;
                if (media == null && sharing != null && !file.isFile()) {
                    media = MonogramAttachments.remoteMedia(message.id, name, sharing.mime, sharing.size);
                    if (media.photo != null) media.photo.date = message.date;
                    if (media.document != null) media.document.date = message.date;
                }
                MonogramAttachments.apply(message, link, file, media, c.getString(3), name);
            } else if (message.message != null && message.message.toLowerCase(java.util.Locale.ROOT).contains("aesgcm://")) {
                message.message = "Encrypted file link in an unsupported format";
            }
        } catch (Exception e) { message.message = "Attachment unavailable"; }
    }
    synchronized ArrayList<TLRPC.Message> historyAround(long peer, long self, int anchor, int limit) {
        if(deleted(anchor)) throw new IllegalArgumentException("Message was deleted locally");
        TLRPC.Message target = getMessage(anchor, self);
        if (target.dialog_id != peer) throw new IllegalArgumentException("History anchor belongs to another conversation");
        int count = Math.min(Math.max(limit, 1), 100);
        ArrayList<TLRPC.Message> newer = count > 1 ? history(peer, self, anchor, count / 2, true) : new ArrayList<>();
        ArrayList<TLRPC.Message> older = count > newer.size() + 1 ? history(peer, self, anchor, count - newer.size() - 1, false) : new ArrayList<>();
        if (older.size() + newer.size() + 1 < count && count > 1) newer = history(peer, self, anchor, count - older.size() - 1, true);
        newer.add(target); newer.addAll(older); return newer;
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
        sql += " WHERE m.peer=? AND m.deleted=0 AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id AND c.resolved!=0) AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id)"; args.add(Long.toString(peer));
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
    synchronized TLRPC.TL_messages_messagesSlice search(long self, long peer, String query, int offset, int skip,
            int limit, long sender, int minDate, int maxDate, TLRPC.MessagesFilter filter, boolean groups, boolean users) {
        TLRPC.TL_messages_messagesSlice result=new TLRPC.TL_messages_messagesSlice();
        String needle=query==null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        String sql="SELECT m.id,m.peer,p.body,COALESCE(a.caption,''),m.time_ms,m.archive_order FROM messages m "
                +"LEFT JOIN applied_corrections ac ON ac.original=m.id JOIN messages p ON p.id=COALESCE(ac.message,m.id) "
                +"LEFT JOIN attachments a ON a.message=p.id LEFT JOIN room_senders r ON r.message=m.id "
                +"WHERE m.deleted=0 AND p.deleted=0 AND p.readable=1 "
                +"AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id AND c.resolved!=0) "
                +"AND NOT EXISTS(SELECT 1 FROM reaction_sources x WHERE x.message=m.id) "
                +"AND NOT EXISTS(SELECT 1 FROM retraction_sources x WHERE x.message=m.id AND x.hidden=1)";
        ArrayList<String> args=new ArrayList<>();
        if(peer!=0) { sql+=" AND m.peer=?"; args.add(Long.toString(peer)); }
        if(groups) sql+=" AND m.peer<0";
        if(users) sql+=" AND m.peer>0";
        if(sender!=0) { sql+=" AND CASE WHEN m.outgoing=1 THEN ? ELSE COALESCE(r.user,m.peer) END=?"; args.add(Long.toString(self)); args.add(Long.toString(sender)); }
        if(minDate>0) { sql+=" AND m.time>=?"; args.add(Integer.toString(minDate)); }
        if(maxDate>0) { sql+=" AND m.time<=?"; args.add(Integer.toString(maxDate)); }
        long[] anchor=null;
        if(offset>0) try(Cursor c=getReadableDatabase().rawQuery("SELECT time_ms,archive_order,id FROM messages WHERE id=?"+(peer==0 ? "" : " AND peer=?"),
                peer==0 ? new String[]{Integer.toString(offset)} : new String[]{Integer.toString(offset),Long.toString(peer)})) {
            if(c.moveToFirst()) anchor=new long[]{c.getLong(0),c.getLong(1),c.getLong(2)};
            else return result;
        }
        int remainingSkip=Math.max(0,skip), maximum=Math.min(100,Math.max(1,limit));
        boolean unfiltered=filter==null || filter instanceof TLRPC.TL_inputMessagesFilterEmpty;
        try(Cursor c=getReadableDatabase().rawQuery(sql+" ORDER BY m.time_ms DESC,m.archive_order DESC,m.id DESC",args.toArray(new String[0]))) {
            while(c.moveToNext()) {
                String text=c.getString(2),caption=c.getString(3);
                if(!needle.isEmpty() && !(text==null ? "" : text).toLowerCase(java.util.Locale.ROOT).contains(needle)
                        && !caption.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
                TLRPC.Message message=unfiltered ? null : getMessage(c.getInt(0),self);
                if(!unfiltered && !matchesSearchFilter(message,filter)) continue;
                result.count++;
                if(anchor!=null && !(c.getLong(4)<anchor[0] || c.getLong(4)==anchor[0] && (c.getLong(5)<anchor[1] || c.getLong(5)==anchor[1] && c.getInt(0)<anchor[2]))) continue;
                if(remainingSkip>0) { remainingSkip--; continue; }
                if(result.messages.size()<maximum) result.messages.add(message==null ? getMessage(c.getInt(0),self) : message);
            }
        }
        return result;
    }
    private boolean matchesSearchFilter(TLRPC.Message message,TLRPC.MessagesFilter filter) {
        if(filter==null || filter instanceof TLRPC.TL_inputMessagesFilterEmpty) return true;
        if(filter instanceof TLRPC.TL_inputMessagesFilterPhotos) return org.telegram.messenger.MessageObject.isPhoto(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterVideo) return org.telegram.messenger.MessageObject.isVideoMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterPhotoVideo) return org.telegram.messenger.MessageObject.isPhoto(message) || org.telegram.messenger.MessageObject.isVideoMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterMusic) return org.telegram.messenger.MessageObject.isMusicMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterVoice) return org.telegram.messenger.MessageObject.isVoiceMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterRoundVoice) return org.telegram.messenger.MessageObject.isVoiceMessage(message) || org.telegram.messenger.MessageObject.isRoundVideoMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterRoundVideo) return org.telegram.messenger.MessageObject.isRoundVideoMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterGif) return org.telegram.messenger.MessageObject.isGifMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterDocument) return message.media!=null && message.media.document!=null
                && !org.telegram.messenger.MessageObject.isMusicMessage(message) && !org.telegram.messenger.MessageObject.isVoiceMessage(message)
                && !org.telegram.messenger.MessageObject.isVideoMessage(message) && !org.telegram.messenger.MessageObject.isGifMessage(message)
                && !org.telegram.messenger.MessageObject.isRoundVideoMessage(message);
        if(filter instanceof TLRPC.TL_inputMessagesFilterUrl) return message.message!=null
                && org.telegram.messenger.MessageObject.containsUrls(message.message)
                && android.util.Patterns.WEB_URL.matcher(message.message).find();
        return false;
    }
    synchronized ArrayList<Long> peers() {
        ArrayList<Long> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM peers WHERE id NOT IN (SELECT user FROM room_participants WHERE occupant=peers.jid) ORDER BY id", null)) {
            while (c.moveToNext()) result.add(c.getLong(0));
        }
        return result;
    }
}
