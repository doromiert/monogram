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
    private int account=-1;
    private String contactOwner() { return account<0 ? "" : storeContext.getSharedPreferences("monogram-"+account,Context.MODE_PRIVATE).getString("jid",""); }
    final MonogramReactions reactions=new MonogramReactions(this);
    final MonogramSavedTags savedTags=new MonogramSavedTags(this);
    final MonogramReads reads=new MonogramReads(this);
    private final MonogramRetractions retractions=new MonogramRetractions(this);
    private final MonogramReceipts receipts=new MonogramReceipts(this);
    private final java.util.Set<Integer> pendingDecryptions=new java.util.HashSet<>();
    synchronized boolean pendingDecryption(int id) { return pendingDecryptions.contains(id); }
    synchronized void finishPendingDecryption(int id) { pendingDecryptions.remove(id); }
    private String pendingClause(String alias) {
        return MonogramLegacyCaptions.visibleClause(alias)+(pendingDecryptions.isEmpty() ? "" : " AND " + alias + ".id NOT IN (" + android.text.TextUtils.join(",",pendingDecryptions) + ")");
    }
    private final java.util.Map<Long,java.util.Map<Long,String>> mentionNamesCache=new java.util.HashMap<>();
    private final java.util.Map<Long, com.negzero.monogram.xmpp.XmppProfile> profileCache = new java.util.HashMap<>();
    MonogramStore(int account) { this(ApplicationLoader.applicationContext, "monogram-" + account + ".db");this.account=account; }
    MonogramStore(Context context, String name) { super(context, name, null, 33); storeContext = context; database = new java.io.File(name).getName(); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer INTEGER NOT NULL, stanza TEXT NOT NULL, body TEXT NOT NULL, encrypted INTEGER NOT NULL, outgoing INTEGER NOT NULL, time INTEGER NOT NULL, UNIQUE(peer,stanza,outgoing))");
        addMessageMetadata(db); addAttachments(db); addProfiles(db); addReplies(db); addCorrections(db); addCaptions(db); addProtocols(db); addProfileSources(db); addRooms(db); addRoomAffiliations(db); addBookmarks(db); addRoomProfiles(db); addRoomAuthorVerification(db); MonogramReactions.create(db);
        addDeletions(db); addSenderFingerprints(db); MonogramRetractions.create(db);
        MonogramReceipts.create(db);
        MonogramReads.create(db);
        MonogramFormatting.create(db);
        MonogramEmojis.create(db);
        addCalls(db);
        addLocalNames(db);
        addStickerHints(db);
        addLinkPreviews(db);
        MonogramSavedTags.create(db);addForwardedMetadata(db);MonogramLegacyCaptions.create(db);
    }
    synchronized boolean linkLegacyCaption(int parent,int companion,boolean placeholder) {
        return MonogramLegacyCaptions.link(getWritableDatabase(),parent,companion,placeholder);
    }
    synchronized int legacyCaptionParent(int companion) {
        return MonogramLegacyCaptions.parent(getReadableDatabase(),companion);
    }
    private static void addForwardedMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_forwarded_messages(message INTEGER PRIMARY KEY,sender TEXT NOT NULL,date INTEGER NOT NULL)");
    }
    /** Local presentation metadata must not change the transmitted/archived legacy file body. */
    synchronized void forwarded(int id,com.negzero.monogram.xmpp.ForwardedContent forward) {
        if(forward==null || hiddenMessage(id)) return;
        ContentValues row=new ContentValues();row.put("message",id);row.put("sender",forward.name);row.put("date",forward.date);
        getWritableDatabase().insertWithOnConflict("local_forwarded_messages",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private void decorateForwarded(TLRPC.Message message) {
        if(message.fwd_from!=null || !message.out || !MonogramAttachments.has(message)) return;
        try(Cursor row=getReadableDatabase().rawQuery("SELECT sender,date FROM local_forwarded_messages WHERE message=?",new String[]{Integer.toString(message.id)})) {
            if(row.moveToFirst()) MonogramForwarding.header(message,com.negzero.monogram.xmpp.ForwardedContent.of(row.getString(0),row.getInt(1),""));
        }
    }
    private static void addLinkPreviews(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS link_previews(url TEXT PRIMARY KEY,expires INTEGER NOT NULL,media BLOB)");
    }
    synchronized boolean linkPreviewFresh(String url,long now) {
        try(Cursor row=getReadableDatabase().rawQuery("SELECT expires FROM link_previews WHERE url=?",new String[]{url})) {
            return row.moveToFirst() && row.getLong(0)>now;
        }
    }
    synchronized void saveLinkPreview(String url,TLRPC.WebPage page,long expires) {
        ContentValues value=new ContentValues();value.put("url",url);value.put("expires",expires);
        if(page!=null) {
            org.telegram.tgnet.SerializedData data=new org.telegram.tgnet.SerializedData();
            try { page.serializeToStream(data);value.put("media",data.toByteArray()); } finally { data.cleanup(); }
        }
        getWritableDatabase().insertWithOnConflict("link_previews",null,value,SQLiteDatabase.CONFLICT_REPLACE);
        getWritableDatabase().execSQL("DELETE FROM link_previews WHERE url NOT IN (SELECT url FROM link_previews ORDER BY expires DESC LIMIT 256)");
    }
    synchronized void expireYouTubeLinkPreviews() {
        ArrayList<String> urls=new ArrayList<>();
        try(Cursor row=getReadableDatabase().rawQuery("SELECT url FROM link_previews WHERE media IS NOT NULL",null)) {
            while(row.moveToNext()) try {
                if(MonogramLinkPreviews.youtubeMetadataUrl(new java.net.URI(row.getString(0)))!=null) urls.add(row.getString(0));
            } catch(java.net.URISyntaxException malformed) { /* Ignore invalid optional cache entries. */ }
        }
        ContentValues value=new ContentValues();value.put("expires",0);
        for(String url:urls) getWritableDatabase().update("link_previews",value,"url=?",new String[]{url});
    }
    synchronized void expireIncompleteMediaPreviews() {
        ContentValues value=new ContentValues();value.put("expires",0);
        getWritableDatabase().update("link_previews",value,"url LIKE ? OR url LIKE ?",new String[]{"https://www.kkinstagram.com/%","https://kkinstagram.com/%"});
    }
    synchronized void clearFailedLinkPreviews() { getWritableDatabase().delete("link_previews","media IS NULL",null); }
    synchronized void clearLinkPreviews() { getWritableDatabase().delete("link_previews",null,null); }
    private void decorateLinkPreview(TLRPC.Message message) {
        if(!MonogramLinkPreviews.candidate(message)) return;
        String url=MonogramLinkPreviews.firstUrl(message.message);
        try(Cursor row=getReadableDatabase().rawQuery("SELECT media FROM link_previews WHERE url=?",new String[]{url})) {
            if(!row.moveToFirst() || row.isNull(0)) return;
            org.telegram.tgnet.SerializedData data=new org.telegram.tgnet.SerializedData(row.getBlob(0));
            try {
                TLRPC.WebPage page=TLRPC.WebPage.TLdeserialize(data,data.readInt32(true),true);
                if(!(page instanceof TLRPC.TL_webPage)) return;
                TLRPC.TL_messageMediaWebPage media=new TLRPC.TL_messageMediaWebPage();media.webpage=page;
                message.media=media;message.flags|=512;
            } catch(RuntimeException invalid) { /* Corrupt optional cached metadata cannot hide messages. */ }
            finally { data.cleanup(); }
        }
    }
    private boolean rememberStickerHint(SQLiteDatabase db,int id,String url,boolean hint) {
        if(!hint) return false;
        ContentValues value=new ContentValues(); value.put("display_sticker",1);
        return db.update("attachments",value,"message=? AND url=? AND display_sticker=0",new String[]{Integer.toString(id),url})>0;
    }
    private static void addStickerHints(SQLiteDatabase db) {
        try(Cursor cursor=db.rawQuery("PRAGMA table_info(attachments)",null)) {
            while(cursor.moveToNext()) if("display_sticker".equals(cursor.getString(1))) return;
        }
        db.execSQL("ALTER TABLE attachments ADD COLUMN display_sticker INTEGER NOT NULL DEFAULT 0");
    }
    private static void addLocalNames(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_contact_names(peer INTEGER PRIMARY KEY,name TEXT NOT NULL)");
    }
    private static void addCalls(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE call_events(message INTEGER PRIMARY KEY,owner TEXT NOT NULL,peer INTEGER NOT NULL,sid TEXT NOT NULL,outgoing INTEGER NOT NULL,video INTEGER NOT NULL,duration INTEGER NOT NULL,reason TEXT NOT NULL,UNIQUE(owner,peer,sid,outgoing))");
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
        if (newVersion != 33 || oldVersion < 1 || oldVersion > 32) throw new IllegalStateException("Unknown database migration");
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
        if(oldVersion<17) MonogramReceipts.create(db);
        if(oldVersion>=10 && oldVersion<18) db.execSQL("ALTER TABLE rooms ADD COLUMN description TEXT NOT NULL DEFAULT ''");
        addRoomAffiliations(db);addProfileSources(db);
        if(oldVersion<21) MonogramReads.create(db);
        if(oldVersion<22) MonogramReads.createDisplayed(db);
        if(oldVersion<23) MonogramFormatting.create(db);
        if(oldVersion<24) addCalls(db);
        if(oldVersion<25) MonogramEmojis.create(db);
        if(oldVersion<26) addLocalNames(db);
        if(oldVersion<27) addStickerHints(db);
        if(oldVersion<28) addLinkPreviews(db);
        if(oldVersion<29) MonogramSavedTags.create(db);
        else if(oldVersion<31) db.execSQL("ALTER TABLE saved_tag_names ADD COLUMN color INTEGER NOT NULL DEFAULT 0");
        if(oldVersion<30) MonogramReceipts.createRoomReaders(db);
        if(oldVersion<32) addForwardedMetadata(db);
        if(oldVersion<33) { MonogramLegacyCaptions.create(db);MonogramLegacyCaptions.recoverPlaceholders(db); }
    }
    private static void addProfileSources(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS profile_avatar_sources (peer INTEGER PRIMARY KEY,bytes BLOB NOT NULL)");
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
    synchronized ArrayList<Integer> receipt(long peer, String wire, boolean displayed) { return receipts.receive(peer, wire, displayed); }
    synchronized ArrayList<Integer> roomDisplayed(long peer,String wire) { return receipts.receiveRoom(peer,wire); }
    synchronized ArrayList<Integer> roomDisplayed(long peer,com.negzero.monogram.xmpp.RoomDisplayedMarker marker) {
        if(marker.own || peer>=0 || !room(peer).jid.equals(marker.room.toString())) return new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT m.id) FROM message_ids i JOIN messages m ON m.id=i.message "
                +"WHERE i.peer=? AND m.peer=i.peer AND m.deleted=0 AND i.kind='room-displayed' AND i.value=?",
                new String[]{Long.toString(peer),marker.id})) {
            if(!c.moveToFirst() || c.getInt(0)!=1) return new ArrayList<>();
        }
        long user;
        if(marker.actor.startsWith("jid:")) user=peer(marker.actor.substring(4));
        else if(marker.actor.startsWith("id:")) {
            // Stable occupant IDs are room-scoped. Never identify a reader by a
            // nickname alone: another person can reuse it after a departure.
            String identity=room(peer).jid+"/monogram-reader-"+UUID.nameUUIDFromBytes(marker.actor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            user=peer(identity);
            rosterName(user,marker.occupant.substring(marker.occupant.indexOf('/')+1));
        } else return new ArrayList<>();
        return receipts.receiveRoom(peer,marker.id,marker.actor,user);
    }
    synchronized ArrayList<Long> roomReaders(long peer,int message,long self) { return receipts.roomReaders(peer,message,self); }
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
                MonogramEmojis.remove(db,id);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private boolean rememberDeleted(SQLiteDatabase db,int id,long peer,java.util.Map<String,String> ids,String sender,boolean outgoing,int namespace,boolean room) {
        if(id==0 || !deleted(id)) return false;
        for(java.util.Map.Entry<String,String> alias:ids.entrySet()) {
            boolean scoped=room ? roomAuthorScoped(alias.getKey()) : alias.getKey().equals("stanza");
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
        profileCache.clear();mentionNamesCache.clear();
        pendingDecryptions.clear();
        db.beginTransaction();
        try { db.delete("local_media_captions",null,null); db.delete("local_forwarded_messages",null,null); db.delete("saved_message_tags",null,null); db.delete("saved_tag_names",null,null); db.delete("link_previews",null,null); MonogramEmojis.clear(db,storeContext,database); db.delete("message_formatting",null,null); db.delete("call_events",null,null); db.delete("pending_displayed",null,null); db.delete("displayed_anchors",null,null); db.delete("markable_messages",null,null); db.delete("local_read_messages",null,null); db.delete("local_read_anchors",null,null); db.delete("message_receipts",null,null); db.delete("room_readers",null,null); db.delete("retraction_sources",null,null); db.delete("message_retractions",null,null); db.delete("reaction_events",null,null); db.delete("reaction_sources",null,null); db.delete("room_affiliations",null,null); db.delete("room_permissions",null,null); db.delete("room_message_actors",null,null); db.delete("room_bookmarks", null, null); db.delete("room_senders", null, null); db.delete("room_participants", null, null); db.delete("rooms", null, null); db.delete("applied_corrections", null, null); db.delete("corrections", null, null); db.delete("replies", null, null); db.delete("message_senders", null, null); db.delete("profile_avatar_sources",null,null); db.delete("local_contact_names",null,null); db.delete("profiles", null, null); db.delete("attachments", null, null); db.delete("message_ids", null, null); db.delete("archive_state", null, null); db.delete("messages", null, null); db.delete("peers", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    private void addRooms(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rooms (id INTEGER PRIMARY KEY AUTOINCREMENT,jid TEXT NOT NULL UNIQUE,nickname TEXT NOT NULL,title TEXT NOT NULL,subject TEXT NOT NULL DEFAULT '',description TEXT NOT NULL DEFAULT '',autojoin INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_participants (room INTEGER NOT NULL,occupant TEXT NOT NULL,user INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 0,real_jid TEXT,PRIMARY KEY(room,occupant))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_senders (message INTEGER PRIMARY KEY,user INTEGER NOT NULL)");
    }
    private void addRoomAffiliations(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS room_affiliations (room INTEGER NOT NULL,user INTEGER NOT NULL,affiliation TEXT NOT NULL,PRIMARY KEY(room,user))");
    }
    synchronized void roomRoster(long dialog,java.util.Map<String,String> roster) throws Exception {
        room(dialog);
        java.util.Map<Long,String> members=new java.util.LinkedHashMap<>();
        for(java.util.Map.Entry<String,String> member:roster.entrySet()) {
            if(!org.jxmpp.jid.impl.JidCreate.from(member.getKey()).isEntityBareJid()
                    || !java.util.Arrays.asList("member","admin","owner").contains(member.getValue()))
                throw new IllegalArgumentException("Invalid room member");
            members.put(peer(member.getKey()),member.getValue());
        }
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            db.delete("room_affiliations","room=?",new String[]{Long.toString(-dialog)});
            for(java.util.Map.Entry<Long,String> member:members.entrySet()) {
                ContentValues values=new ContentValues();values.put("room",-dialog);values.put("user",member.getKey());values.put("affiliation",member.getValue());
                db.insertOrThrow("room_affiliations",null,values);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static final String ROOM_MEMBERS="SELECT user FROM room_participants WHERE room=? AND active=1 UNION SELECT user FROM room_affiliations WHERE room=?";
    synchronized java.util.Set<Long> activeRoomUsers(long dialog) {
        java.util.Set<Long> users=new java.util.HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT user FROM room_participants WHERE room=? AND active=1",new String[]{Long.toString(-dialog)})) {
            while(c.moveToNext()) users.add(c.getLong(0));
        }
        return users;
    }
    static final class Room {
        final long dialog;
        final String jid,nickname,title,subject,description;
        final boolean autojoin;
        Room(Cursor c) { dialog=-c.getLong(0); jid=c.getString(1); nickname=c.getString(2); title=c.getString(3); subject=c.getString(4); autojoin=c.getInt(5)!=0; description=c.getString(6); }
    }
    synchronized Room room(long dialog) {
        if (dialog>=0) throw new IllegalArgumentException("Room dialog required");
        try (Cursor c=getReadableDatabase().rawQuery("SELECT id,jid,nickname,title,subject,autojoin,description FROM rooms WHERE id=?",new String[]{Long.toString(-dialog)})) {
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
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM messages m JOIN messages a ON a.id=? WHERE m.peer=? AND m.deleted=0"+pendingClause("m")+" AND (m.time_ms>a.time_ms OR (m.time_ms=a.time_ms AND m.archive_order>a.archive_order) OR (m.time_ms=a.time_ms AND m.archive_order=a.archive_order AND m.id>a.id)) AND NOT EXISTS(SELECT 1 FROM corrections x WHERE x.message=m.id AND x.resolved!=0) AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id)",new String[]{Integer.toString(anchor),Long.toString(peer)})) { c.moveToFirst(); return c.getInt(0); }
    }
    synchronized ArrayList<Long> rooms() {
        ArrayList<Long> result=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery("SELECT id FROM rooms ORDER BY id",null)) { while(c.moveToNext()) result.add(-c.getLong(0)); }
        return result;
    }
    synchronized void leaveRoom(long dialog) { getWritableDatabase().execSQL("UPDATE rooms SET autojoin=0 WHERE id=?",new Object[]{-dialog}); }
    synchronized long roomUser(long dialog,String occupant,String realJid,long self) {
        mentionNamesCache.remove(dialog);
        if (!occupant.startsWith(room(dialog).jid+"/")) throw new IllegalArgumentException("Occupant belongs to another room");
        long user=realJid!=null ? peer(realJid) : peer(occupant);
        if (realJid==null) rosterName(user,occupant.substring(occupant.indexOf('/')+1));
        ContentValues values=new ContentValues(); values.put("room",-dialog); values.put("occupant",occupant); values.put("user",user); values.put("real_jid",realJid);
        getWritableDatabase().insertWithOnConflict("room_participants",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        if(realJid!=null) getWritableDatabase().update("room_participants",values,"room=? AND occupant=?",new String[]{Long.toString(-dialog),occupant});
        return user;
    }
    synchronized void roomMetadata(long dialog,String title,String description) {
        ContentValues values=new ContentValues();
        if(title!=null && !title.trim().isEmpty()) values.put("title",title);
        if(description!=null) values.put("description",description);
        if(values.size()>0) getWritableDatabase().update("rooms",values,"id=?",new String[]{Long.toString(-dialog)});
    }
    /** Apply membership differences, rather than rewriting every occupant on each presence stanza. */
    synchronized boolean roomState(com.negzero.monogram.xmpp.XmppRooms.State state,long self) {
        return roomState(state,self,null);
    }
    synchronized boolean roomState(com.negzero.monogram.xmpp.XmppRooms.State state,long self,java.util.Set<Long> affectedUsers) {
        java.util.Set<Long> affected=new java.util.LinkedHashSet<>();
        long dialog=knownRoom(state.room.toString()); if(dialog==0) return false;
        Room previous=room(dialog);SQLiteDatabase db=getWritableDatabase();
        boolean changed=false;db.beginTransaction();
        try {
            ContentValues values=new ContentValues();
            if(!java.util.Objects.equals(previous.subject,state.subject)) values.put("subject",state.subject);
            if(state.title!=null && !state.title.trim().isEmpty() && !state.title.equals(previous.title)) values.put("title",state.title);
            if(state.description!=null && !state.description.equals(previous.description)) values.put("description",state.description);
            if(!state.nickname.isEmpty() && !state.nickname.equals(previous.nickname)) values.put("nickname",state.nickname);
            if(values.size()>0) {db.update("rooms",values,"id=?",new String[]{Long.toString(-dialog)});changed=true;}
            if(state.joined && !state.role.isEmpty()) {
                boolean same;
                try(Cursor row=db.rawQuery("SELECT role,affiliation FROM room_permissions WHERE room=?",new String[]{Long.toString(-dialog)})) {
                    same=row.moveToFirst() && state.role.equals(row.getString(0)) && state.affiliation.equals(row.getString(1));
                }
                if(!same) {
                    ContentValues permissions=new ContentValues();permissions.put("room",-dialog);permissions.put("role",state.role);permissions.put("affiliation",state.affiliation);
                    db.insertWithOnConflict("room_permissions",null,permissions,SQLiteDatabase.CONFLICT_REPLACE);changed=true;
                }
            }
            java.util.Map<String,String> identities=new java.util.HashMap<>();java.util.Set<String> active=new java.util.HashSet<>();
            java.util.Map<String,Long> previousUsers=new java.util.HashMap<>();
            try(Cursor rows=db.rawQuery("SELECT occupant,real_jid,active,user FROM room_participants WHERE room=?",new String[]{Long.toString(-dialog)})) {
                while(rows.moveToNext()) {String occupant=rows.getString(0);identities.put(occupant,rows.getString(1));previousUsers.put(occupant,rows.getLong(3));if(rows.getInt(2)!=0)active.add(occupant);}
            }
            for(com.negzero.monogram.xmpp.XmppRooms.Occupant occupant:state.occupants) {
                if(!occupant.address.startsWith(previous.jid+"/")) throw new IllegalArgumentException("Occupant belongs to another room");
                boolean wasActive=active.remove(occupant.address);
                boolean sameIdentity=identities.containsKey(occupant.address) && java.util.Objects.equals(identities.get(occupant.address),occupant.realJid);
                if(wasActive && sameIdentity) continue;
                ContentValues participant=new ContentValues();participant.put("active",1);
                Long oldUser=previousUsers.get(occupant.address);if(oldUser!=null)affected.add(oldUser);
                if(!sameIdentity) {
                    long user=roomUser(dialog,occupant.address,occupant.realJid,self);affected.add(user);
                    participant.put("real_jid",occupant.realJid);participant.put("user",user);
                }
                db.update("room_participants",participant,"room=? AND occupant=?",new String[]{Long.toString(-dialog),occupant.address});changed=true;
            }
            for(String occupant:active) {
                affected.add(previousUsers.get(occupant));
                ContentValues inactive=new ContentValues();inactive.put("active",0);
                db.update("room_participants",inactive,"room=? AND occupant=?",new String[]{Long.toString(-dialog),occupant});changed=true;
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        if(changed) mentionNamesCache.remove(dialog);
        if(affectedUsers!=null)affectedUsers.addAll(affected);
        return changed;
    }
    synchronized java.util.Map<Long,String> mentionNames(long dialog,long self) {
        if(mentionNamesCache.containsKey(dialog)) return mentionNamesCache.get(dialog);
        java.util.Map<Long,String> result=new java.util.LinkedHashMap<>();if(dialog>=0) return result;
        result.put(self,room(dialog).nickname);
        try(Cursor rows=getReadableDatabase().rawQuery("SELECT user,occupant FROM room_participants WHERE room=? ORDER BY active DESC,occupant",new String[]{Long.toString(-dialog)})) {
            while(rows.moveToNext()) { String occupant=rows.getString(1);int slash=occupant.indexOf('/');
                if(slash>=0) result.putIfAbsent(rows.getLong(0),occupant.substring(slash+1)); }
        }
        java.util.Map<Long,String> cached=java.util.Collections.unmodifiableMap(result);mentionNamesCache.put(dialog,cached);return cached;
    }
    synchronized String roomMentionFallback(long user) {
        String address=jid(user);int slash=address.indexOf('/');
        return slash>=0 ? address.substring(slash+1) : "Member";
    }
    synchronized String mentionAddress(long dialog,long user) {
        if(dialog<0) {
            try(Cursor row=getReadableDatabase().rawQuery("SELECT real_jid FROM room_participants WHERE room=? AND user=? AND real_jid IS NOT NULL ORDER BY active DESC LIMIT 1",new String[]{Long.toString(-dialog),Long.toString(user)})) {
                return row.moveToFirst() ? row.getString(0) : null;
            }
        }
        try { return jid(user); } catch(IllegalArgumentException unknown) { return null; }
    }
    synchronized ArrayList<TLRPC.User> roomUsers(long dialog,long self) {
        java.util.Set<Long> ids=new java.util.LinkedHashSet<>(); ids.add(self);
        try (Cursor c=getReadableDatabase().rawQuery("SELECT user FROM room_participants WHERE room=? UNION SELECT s.user FROM room_senders s JOIN messages m ON m.id=s.message WHERE m.peer=? UNION SELECT user FROM room_affiliations WHERE room=? UNION SELECT user FROM room_readers WHERE peer=?",new String[]{Long.toString(-dialog),Long.toString(dialog),Long.toString(-dialog),Long.toString(dialog)})) {
            while(c.moveToNext()) ids.add(c.getLong(0));
        }
        ArrayList<TLRPC.User> result=new ArrayList<>(); for (long id:ids) result.add(user(id,self)); return result;
    }
    synchronized ArrayList<TLRPC.User> roomPresenceUsers(long dialog,long self,java.util.Collection<Long> changed) {
        if(changed==null) return roomUsers(dialog,self);
        ArrayList<TLRPC.User> result=new ArrayList<>();
        for(long id:new java.util.LinkedHashSet<>(changed)) if(id>0)result.add(user(id,self));
        return result;
    }
    synchronized boolean roomMember(long chat,long peer) {
        if(chat<=0 || peer<=0) return false;
        try(Cursor rows=getReadableDatabase().rawQuery("SELECT 1 FROM ("+ROOM_MEMBERS+") WHERE user=? LIMIT 1",
                new String[]{Long.toString(chat),Long.toString(chat),Long.toString(peer)})) { return rows.moveToFirst(); }
    }
    synchronized TLRPC.Chat roomChat(long dialog) { return roomChat(dialog,0); }
    synchronized TLRPC.Chat roomChat(long dialog,long self) {
        Room room=room(dialog); TLRPC.TL_chat chat=new TLRPC.TL_chat();
        chat.id=-dialog; chat.title=room.title; chat.photo=new TLRPC.TL_chatPhotoEmpty(); chat.version=1; chat.left=!room.autojoin;
        // A missing rights object makes modern Telegram deny every send action.
        chat.default_banned_rights=new TLRPC.TL_chatBannedRights(); chat.default_banned_rights.change_info=true; chat.flags|=262144;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT role,affiliation FROM room_permissions WHERE room=?",new String[]{Long.toString(-dialog)})) {
            boolean present=c.moveToFirst();
            if(present) {
                chat.creator="owner".equals(c.getString(1));
                if("admin".equals(c.getString(1))) {
                    chat.admin_rights=new TLRPC.TL_chatAdminRights(); chat.admin_rights.invite_users=true;
                    chat.admin_rights.ban_users=true; chat.admin_rights.flags=16|32; chat.flags|=16384;
                }
            }
            if(present && "visitor".equals(c.getString(0))) {
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
        try (Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM ("+ROOM_MEMBERS+")",new String[]{Long.toString(-dialog),Long.toString(-dialog)})) { c.moveToFirst(); chat.participants_count=c.getInt(0); }
        if(self>0) MonogramGroupAppearance.project(database,jid(self),room.jid,chat);
        return chat;
    }
    synchronized TLRPC.TL_messages_chatFull roomFull(long dialog,long self) {
        Room room=room(dialog); TLRPC.TL_messages_chatFull result=new TLRPC.TL_messages_chatFull();
        result.chats.add(roomChat(dialog,self)); result.users.addAll(roomUsers(dialog,self));
        TLRPC.TL_chatFull full=new TLRPC.TL_chatFull(); full.id=-dialog; full.about=room.description;
        full.notify_settings=new TLRPC.TL_peerNotifySettings(); full.participants=new TLRPC.TL_chatParticipants(); full.participants.chat_id=-dialog; full.participants.version=1;
        try (Cursor c=getReadableDatabase().rawQuery("SELECT members.user,a.affiliation FROM ("+ROOM_MEMBERS+") members LEFT JOIN room_affiliations a ON a.room=? AND a.user=members.user",
                new String[]{Long.toString(-dialog),Long.toString(-dialog),Long.toString(-dialog)})) {
            while(c.moveToNext()) {
                String affiliation=c.getString(1);
                if(c.getLong(0)==self && result.chats.get(0).creator) affiliation="owner";
                TLRPC.ChatParticipant participant="owner".equals(affiliation) ? new TLRPC.TL_chatParticipantCreator()
                        : "admin".equals(affiliation) ? new TLRPC.TL_chatParticipantAdmin() : new TLRPC.TL_chatParticipant();
                participant.user_id=c.getLong(0);full.participants.participants.add(participant);
            }
        }
        full.chat_photo=MonogramProfiles.photo(dialog,avatar(dialog));
        if(full.chat_photo instanceof TLRPC.TL_photo) full.flags|=4;
        result.full_chat=full; return result;
    }
    synchronized int saveRoom(com.negzero.monogram.xmpp.XmppRoomMessage message,long self) {
        return saveRoom(message,self,null);
    }
    synchronized int saveRoom(com.negzero.monogram.xmpp.XmppRoomMessage message,long self,java.util.Set<Integer> replaced) {
        return saveRoom(message,self,replaced,false);
    }
    synchronized int savePending(com.negzero.monogram.xmpp.XmppRoomMessage message,long self) { return saveRoom(message,self,null,true); }
    private int saveRoom(com.negzero.monogram.xmpp.XmppRoomMessage message,long self,java.util.Set<Integer> replaced,boolean pending) {
        return saveRoom(message,self,replaced,pending,false);
    }
    private int saveRoom(com.negzero.monogram.xmpp.XmppRoomMessage message,long self,java.util.Set<Integer> replaced,boolean pending,boolean archiveBatch) {
        if(message.retraction!=null) { retractions.save(message); return 0; }
        if(message.reactions!=null) { reactions.save(message,self); return 0; }
        long dialog=knownRoom(message.room.toString()); if (dialog==0) throw new IllegalArgumentException("Unknown room message");
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            int existing=0;
            for (Map.Entry<String,String> alias:message.ids.entrySet()) {
                String value=roomAuthorScoped(alias.getKey()) ? message.sender+"\n"+alias.getValue() : alias.getValue();
                try (Cursor c=db.rawQuery("SELECT DISTINCT message FROM message_ids WHERE peer=? AND kind=? AND value=? AND namespace=?",new String[]{Long.toString(dialog),alias.getKey(),value,Integer.toString(message.encryption.ordinal())})) {
                    while(c.moveToNext()) {
                        int match=c.getInt(0);
                        if(roomAuthorScoped(alias.getKey()) && message.ids.get("archive")!=null) {
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
            changed|=MonogramFormatting.remember(db,id,message.body,message.readable,message.markup,message.captionMarkup);
            changed|=MonogramEmojis.save(db,storeContext,database,id,message.inlineEmoji);
            if(existing==0 && message.archived && !archiveBatch) reads.inheritArchive(id);
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
                identity.put("value",roomAuthorScoped(alias.getKey()) ? message.sender+"\n"+alias.getValue() : alias.getValue()); identity.put("namespace",message.encryption.ordinal()); identity.put("message",id);
                db.insertWithOnConflict("message_ids",null,identity,SQLiteDatabase.CONFLICT_IGNORE);
            }
            reads.remember(id,message);
            if(message.attachment!=null) {
                ContentValues attachment=new ContentValues(); attachment.put("message",id); attachment.put("url",message.attachment.wireUrl); attachment.put("caption",message.caption);
                if(message.sharing!=null) attachment.put("sharing",message.sharing.xml());
                changed|=db.insertWithOnConflict("attachments",null,attachment,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
                changed|=rememberStickerHint(db,id,message.attachment.wireUrl,message.displayStickerHint);
                if(message.sharing!=null) {
                    attachment.remove("message"); attachment.remove("url");
                    changed|=db.update("attachments",attachment,"message=? AND url=? AND sharing IS NULL",new String[]{Integer.toString(id),message.attachment.wireUrl})>0;
                }
            }
            db.setTransactionSuccessful();
            boolean revealed=!pending && (!message.archived || message.readable) && pendingDecryptions.remove(id);
            if (pending && changed && existing==0) pendingDecryptions.add(id);
            if(changed && existing!=0 && replaced!=null && !revealed) replaced.add(id);
            return changed || revealed ? id : 0;
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
        return saveRoomArchive(dialog,page,backwards,self,updated,true);
    }
    synchronized boolean saveRoomCalendar(long dialog,com.negzero.monogram.xmpp.XmppRooms.ArchivePage page,long self,java.util.Set<Integer> updated) {
        return saveRoomCalendar(dialog,page,false,self,updated);
    }
    synchronized boolean saveRoomCalendar(long dialog,com.negzero.monogram.xmpp.XmppRooms.ArchivePage page,boolean backwards,long self,java.util.Set<Integer> updated) {
        return saveRoomArchive(dialog,page,backwards,self,updated,false);
    }
    private boolean saveRoomArchive(long dialog,com.negzero.monogram.xmpp.XmppRooms.ArchivePage page,boolean backwards,long self,java.util.Set<Integer> updated,boolean advanceCursor) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            boolean changed=false; long order;
            int previousId=latestLocalMessageId(db);
            try(Cursor c=db.rawQuery("SELECT "+(backwards ? "min" : "max")+"(archive_order) FROM messages WHERE peer=?",new String[]{Long.toString(dialog)})) {
                c.moveToFirst(); order=backwards ? Math.min(0,c.getLong(0))-page.messages.size() : Math.max(0,c.getLong(0))+1;
            }
            for(com.negzero.monogram.xmpp.XmppRoomMessage message:page.messages) {
                if(!jid(dialog).equals(message.room.toString())) throw new IllegalArgumentException("Room archive belongs to another room");
                int id=saveRoom(message,self,null,false,true); changed|=id!=0; if(id!=0) updated.add(id);
                db.execSQL("UPDATE messages SET archive_order=? WHERE archive_order=0 AND id IN (SELECT message FROM message_ids WHERE peer=? AND kind='mam' AND value=? AND namespace=?)",new Object[]{order++,dialog,message.ids.get("mam"),message.encryption.ordinal()});
                if(id>previousId) reads.inheritArchive(id);
            }
            if(advanceCursor) {
            ContentValues empty=new ContentValues(); empty.put("scope",archiveScope(dialog)); db.insertWithOnConflict("archive_state",null,empty,SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues cursor=new ContentValues();
            if(backwards) { if(page.firstId!=null) cursor.put("oldest",page.firstId); cursor.put("complete",page.complete || page.firstId==null); }
            if(archiveCursor(dialog,false)==null || !backwards) if(page.lastId!=null) cursor.put("newest",page.lastId);
            if(cursor.size()>0) db.update("archive_state",cursor,"scope=?",new String[]{archiveScope(dialog)});
            }
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
    /** Load image bytes only for a refresh, never for ordinary member-list/name reads. */
    synchronized com.negzero.monogram.xmpp.XmppProfile profileForRefresh(long peer) throws Exception {
        com.negzero.monogram.xmpp.XmppProfile cached=profile(peer);if(cached==null) return null;
        byte[] bytes=null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT bytes FROM profile_avatar_sources WHERE peer=?",new String[]{Long.toString(peer)})) {
            if(c.moveToFirst()) bytes=c.getBlob(0);
        }
        if(bytes==null) {
            java.io.File image=remoteAvatar(peer);
            if(image!=null && image.isFile() && image.length()>0 && image.length()<=com.negzero.monogram.xmpp.XmppProfile.MAX_AVATAR_BYTES) {
                bytes=new byte[(int)image.length()];try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(image))) { input.readFully(bytes); }
            } else bytes=new byte[0];
        }
        return new com.negzero.monogram.xmpp.XmppProfile(cached.xml(),cached.nickname,bytes);
    }
    synchronized String localName(long peer) {
        try(Cursor row=getReadableDatabase().rawQuery("SELECT name FROM local_contact_names WHERE peer=?",new String[]{Long.toString(peer)})) {
            return row.moveToFirst() ? row.getString(0) : "";
        }
    }
    synchronized void localName(long peer,String name) {
        if(peer<=0) throw new IllegalArgumentException("Choose a contact to rename.");
        jid(peer);
        String value=name.trim();
        if(value.codePointCount(0,value.length())>128 || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c==0x2028 || c==0x2029))
            throw new IllegalArgumentException("Use a name of up to 128 characters on one line.");
        if(value.isEmpty()) getWritableDatabase().delete("local_contact_names","peer=?",new String[]{Long.toString(peer)});
        else {
            ContentValues row=new ContentValues();row.put("peer",peer);row.put("name",value);
            if(getWritableDatabase().insertWithOnConflict("local_contact_names",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)
                throw new IllegalStateException("Could not save the local name.");
        }
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
        catch (IllegalArgumentException invalid) { if(avatar(peer)!=null) throw invalid;png = new byte[0]; }
        java.io.File avatar = png.length == 0 ? null : MonogramProfiles.cache(storeContext, database, png);
        ContentValues empty = new ContentValues(); empty.put("peer", peer);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict("profiles", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues value = new ContentValues(); value.put("card", profile.xml()); value.put("nickname", profile.nickname);
            if (avatar == null) value.putNull("avatar"); else value.put("avatar", avatar.getAbsolutePath());
            db.update("profiles", value, "peer=?", new String[]{Long.toString(peer)});
            // Room peer IDs are negative; a successful insert's row ID can be negative too.
            db.execSQL("INSERT OR REPLACE INTO profile_avatar_sources(peer,bytes) VALUES(?,?)",new Object[]{peer,profile.avatar});
            db.setTransactionSuccessful();profileCache.remove(peer);
        } finally { db.endTransaction(); }
    }
    synchronized java.io.File avatar(long peer) {
        String owner=contactOwner();
        if(peer>0 && !owner.isEmpty()) {
            java.io.File local=MonogramContactDetails.avatar(account,owner,jid(peer));if(local!=null) return local;
        }
        return remoteAvatar(peer);
    }
    synchronized java.io.File remoteAvatar(long peer) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT avatar FROM profiles WHERE peer=?", new String[]{Long.toString(peer)})) {
            return c.moveToFirst() && !c.isNull(0) ? new java.io.File(c.getString(0)) : null;
        }
    }
    synchronized TLRPC.User user(long id, long self) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        String jid = jid(id), roster = "", alias = "";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT p.roster_name,a.name FROM peers x LEFT JOIN profiles p ON p.peer=x.id LEFT JOIN local_contact_names a ON a.peer=x.id WHERE x.id=?", new String[]{Long.toString(id)})) {
            if (c.moveToFirst()) { roster=c.isNull(0) ? "" : c.getString(0);alias=c.isNull(1) ? "" : c.getString(1); }
        }
        com.negzero.monogram.xmpp.XmppProfile profile = profile(id);
        user.id = id; user.first_name = profile == null ? (id == self || roster.isEmpty() ? jid : roster) : profile.displayName(id == self ? "" : roster, jid);
        if (id != self && !alias.isEmpty()) user.first_name=alias;
        if (id == self && user.first_name.equals(jid) && jid.indexOf('@') > 0) user.first_name = jid.substring(0, jid.indexOf('@'));
        user.last_name = ""; user.phone = "";
        user.self = id == self; user.contact = id != self; user.flags = 2;
        java.io.File avatar = avatar(id);
        if (avatar != null && avatar.isFile()) {
            user.photo = new TLRPC.TL_userProfilePhoto(); user.flags |= 32;
            user.photo.photo_id = MonogramProfiles.photo(id, avatar).id;
            String owner=contactOwner();
            user.photo.personal=id!=self && !owner.isEmpty() && MonogramContactDetails.avatar(account,owner,jid)!=null;
            user.photo.photo_small = user.photo.photo_big = MonogramProfiles.location(id); user.photo.dc_id = Integer.MIN_VALUE;
        } else user.photo = new TLRPC.TL_userProfilePhotoEmpty();
        user.status = new TLRPC.TL_userStatusEmpty();
        if(user.self) MonogramUserColors.project(database,jid,jid,user);
        return user;
    }
    synchronized TLRPC.UserFull fullUser(long id, long self) {
        TLRPC.TL_userFull full = new TLRPC.TL_userFull(); full.id = id; full.user = user(id, self);
        // Telegram's private read-date lookup has no XMPP implementation.
        full.read_dates_private = true;
        full.can_pin_message = true; // Monogram pins are local and do not require peer permission.
        full.settings = new TLRPC.TL_peerSettings(); full.notify_settings = new TLRPC.TL_peerNotifySettings();
        com.negzero.monogram.xmpp.XmppProfile profile = profile(id);
        full.about = profile == null ? "" : profile.field("DESC");
        if (!full.about.isEmpty()) full.flags |= 2;
        full.profile_photo = MonogramProfiles.photo(id, remoteAvatar(id));
        String owner=contactOwner();
        if(id!=self && !owner.isEmpty()) {
            java.io.File local=MonogramContactDetails.avatar(account,owner,jid(id));
            if(local!=null) { full.personal_photo=MonogramProfiles.photo(id,local);full.flags|=2097152; }
            String note=MonogramContactDetails.note(account,owner,jid(id));
            if(!note.isEmpty()) { full.note=new TLRPC.TL_textWithEntities();full.note.text=note;full.flags2|=org.telegram.tgnet.TLObject.FLAG_22; }
        }
        if (full.profile_photo instanceof TLRPC.TL_photo) full.flags |= 4;
        return full;
    }
    synchronized int save(XmppMessage message) { return save(message,null); }
    synchronized int save(XmppMessage message,java.util.Set<Integer> replaced) {
        return save(message,replaced,false);
    }
    synchronized int savePending(XmppMessage message) { return save(message,null,true); }
    private int save(XmppMessage message,java.util.Set<Integer> replaced,boolean pending) {
        return save(message,replaced,pending,false);
    }
    private int save(XmppMessage message,java.util.Set<Integer> replaced,boolean pending,boolean archiveBatch) {
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
            changed|=MonogramFormatting.remember(db,id,message.body,message.readable,message.markup,message.captionMarkup);
            changed|=MonogramEmojis.save(db,storeContext,database,id,message.inlineEmoji);
            reads.remember(id,message);
            if(existing==0 && message.archived && !archiveBatch) reads.inheritArchive(id);
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
                changed |= rememberStickerHint(db,id,message.attachment.wireUrl,message.displayStickerHint);
                if (message.sharing != null) {
                    attachment.remove("message"); attachment.remove("url");
                    changed |= db.update("attachments", attachment, "message=? AND url=? AND sharing IS NULL", new String[]{Integer.toString(id), message.attachment.wireUrl}) > 0;
                }
            }
            if (legacy != null && namespace(message) == 0) db.delete("message_ids", "kind='legacy-stanza' AND message=? AND value=?", new String[]{Integer.toString(id), legacy});
            db.setTransactionSuccessful();
            boolean revealed=!pending && (!message.archived || message.readable) && pendingDecryptions.remove(id);
            if (pending && changed && existing==0) pendingDecryptions.add(id);
            if(changed && existing!=0 && replaced!=null && !revealed) replaced.add(id);
            return changed || revealed ? id : 0;
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
    private static boolean roomAuthorScoped(String kind) {
        // Ciphertext aliases already bind the verified real sender and room, independent of nick changes.
        return !kind.equals("archive") && !kind.equals("mam") && !kind.equals("legacy-cipher") && !kind.equals("room-displayed");
    }
    static final class CorrectionChanges {
        final java.util.Set<Integer> edited = new java.util.HashSet<>(), hidden = new java.util.HashSet<>(), shown = new java.util.HashSet<>();
    }
    synchronized boolean hiddenCorrection(int id) {
        if(deleted(id)) return true;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM corrections WHERE message=? AND resolved!=0 UNION SELECT 1 FROM reaction_sources WHERE message=?", new String[]{Integer.toString(id),Integer.toString(id)})) { return c.moveToFirst(); }
    }
    synchronized boolean hiddenMessage(int id) { return pendingDecryptions.contains(id) || hiddenCorrection(id) || legacyCaptionParent(id)!=0; }
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
            try (Cursor c = db.rawQuery("SELECT c.message,c.target,c.resolved,m.outgoing,CASE WHEN m.encryption<0 THEN m.encrypted ELSE m.encryption END FROM corrections c JOIN messages m ON m.id=c.message WHERE m.peer=? AND m.readable=1 ORDER BY m.time_ms,m.archive_order,m.id", new String[]{Long.toString(peer)})) {
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
                        if (target != 0 && !captionOnlyAttachmentCorrection(db, target, event, c.getInt(4))) target = 0;
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
            try (Cursor c = db.rawQuery("SELECT c.resolved,c.message FROM corrections c JOIN messages m ON m.id=c.message WHERE m.peer=? AND m.readable=1 AND c.resolved!=0 ORDER BY m.time_ms,m.archive_order,m.id", new String[]{Long.toString(peer)})) {
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
    /** A correction may change an attachment caption, never its source, integrity metadata, or local media identity. */
    private boolean captionOnlyAttachmentCorrection(SQLiteDatabase db, int target, int event, int correctionProtocol) {
        String originalUrl = null, originalSharing = null, correctionUrl = null, correctionSharing = null, path = null;
        byte[] media = null; boolean originalAttachment = false, correctionAttachment = false;
        try (Cursor c = db.rawQuery("SELECT message,url,sharing,path,media FROM attachments WHERE message IN (?,?)", new String[]{Integer.toString(target), Integer.toString(event)})) {
            while (c.moveToNext()) {
                if (c.getInt(0) == target) {
                    originalAttachment = true; originalUrl = c.getString(1); originalSharing = c.getString(2); path = c.getString(3); media = c.isNull(4) ? null : c.getBlob(4);
                } else {
                    correctionAttachment = true; correctionUrl = c.getString(1); correctionSharing = c.getString(2);
                }
            }
        }
        if (!originalAttachment && !correctionAttachment) return true;
        if (!originalAttachment || !correctionAttachment || correctionProtocol == XmppMessage.Encryption.LEGACY_OMEMO.ordinal()
                || !sameAttachment(originalUrl, originalSharing, correctionUrl, correctionSharing)) return false;
        try (Cursor c = db.rawQuery("SELECT CASE WHEN encryption<0 THEN encrypted ELSE encryption END FROM messages WHERE id=?", new String[]{Integer.toString(target)})) {
            if (!c.moveToFirst() || c.getInt(0) == XmppMessage.Encryption.LEGACY_OMEMO.ordinal()) return false;
        }
        ContentValues preserved = new ContentValues();
        if (path == null) preserved.putNull("path"); else preserved.put("path", path);
        if (media == null) preserved.putNull("media"); else preserved.put("media", media);
        return db.update("attachments", preserved, "message=?", new String[]{Integer.toString(event)}) == 1;
    }
    private boolean sameAttachment(String originalUrl, String originalXml, String correctionUrl, String correctionXml) {
        if (!java.util.Objects.equals(originalUrl, correctionUrl) || originalXml == null || correctionXml == null) return false;
        try {
            com.negzero.monogram.xmpp.FileShare original = sharing(originalUrl, originalXml);
            com.negzero.monogram.xmpp.FileShare correction = sharing(correctionUrl, correctionXml);
            return original != null && correction != null && original.sameAttachment(correction);
        } catch (Exception invalid) { return false; }
    }
    synchronized TLRPC.TL_textWithEntities translationText(int id,long peer,long self) {
        if(id<=0 || hiddenMessage(id)) throw new IllegalArgumentException("Message unavailable for translation");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT peer,readable,deleted FROM messages WHERE id=?",new String[]{Integer.toString(payload(id))})) {
            if(!c.moveToFirst() || c.getLong(0)!=peer || c.getInt(1)==0 || c.getInt(2)!=0)
                throw new IllegalArgumentException("Message unavailable for translation");
        }
        TLRPC.Message message=getMessage(id,self);
        if(message.message==null || message.message.isEmpty()) throw new IllegalArgumentException("Message has no text to translate");
        TLRPC.TL_textWithEntities result=new TLRPC.TL_textWithEntities();result.text=message.message;result.entities.addAll(message.entities);
        return result;
    }
    static final class EditUnavailable extends IllegalArgumentException {
        final String code;
        EditUnavailable(String code) { super(code); this.code=code; }
    }
    static final class EditTarget {
        final com.negzero.monogram.xmpp.MessageCorrection correction;
        final boolean encrypted;
        final XmppMessage.Encryption encryption;
        final ReplyReference reply;
        final com.negzero.monogram.xmpp.FileShare sharing;
        EditTarget(String id, int protocol, ReplyReference reply, com.negzero.monogram.xmpp.FileShare sharing) {
            this.correction = new com.negzero.monogram.xmpp.MessageCorrection(id); this.encrypted = protocol > 0; this.reply = reply;
            this.encryption = protocol == 2 ? XmppMessage.Encryption.OMEMO2 : protocol == 1 ? XmppMessage.Encryption.LEGACY_OMEMO : XmppMessage.Encryption.PLAIN;
            this.sharing = sharing;
        }
    }
    synchronized EditTarget editTarget(int id, long peer, long self) {
        TLRPC.Message original = getMessage(id, self);
        if(original.action instanceof TLRPC.TL_messageActionPhoneCall) throw new IllegalArgumentException("Local call summaries cannot be edited");
        boolean attachment = MonogramAttachments.has(original);
        if (!original.out || original.dialog_id != peer) throw new IllegalArgumentException("Only your messages can be edited");
        SQLiteDatabase db = getReadableDatabase(); String wire = null; int protocol;
        if(peer<0) try(Cursor actor=db.rawQuery("SELECT 1 FROM room_message_actors WHERE message=? AND verified=1 AND real_jid=?",new String[]{Integer.toString(id),jid(self)})) {
            if(!actor.moveToFirst()) throw new EditUnavailable("MONOGRAM_EDIT_ROOM_AUTHOR_UNVERIFIED");
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
        com.negzero.monogram.xmpp.FileShare sharing = null;
        if (attachment) {
            if (protocol == XmppMessage.Encryption.LEGACY_OMEMO.ordinal()) throw new EditUnavailable("MONOGRAM_EDIT_LEGACY_CAPTION_UNSUPPORTED");
            try (Cursor c = db.rawQuery("SELECT url,sharing FROM attachments WHERE message=?", new String[]{Integer.toString(payload(id))})) {
                if (!c.moveToFirst()) throw new EditUnavailable("MONOGRAM_EDIT_ATTACHMENT_METADATA_UNAVAILABLE");
                sharing = sharing(c.getString(0), c.getString(1));
                if (sharing == null) throw new EditUnavailable("MONOGRAM_EDIT_ATTACHMENT_METADATA_UNAVAILABLE");
            } catch (EditUnavailable unavailable) { throw unavailable; }
            catch (Exception invalid) { throw new EditUnavailable("MONOGRAM_EDIT_ATTACHMENT_METADATA_UNAVAILABLE"); }
            if(peer<0 && sharing.link.encrypted()) throw new EditUnavailable("MONOGRAM_EDIT_ROOM_CAPTION_UNSUPPORTED");
        }
        return new EditTarget(wire, protocol, reply, sharing);
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
        return saveArchive(scope,page,backwards,updated,true);
    }
    synchronized boolean saveCalendar(long peer,XmppSession.ArchivePage page,java.util.Set<Integer> updated) {
        return saveCalendar(peer,page,false,updated);
    }
    synchronized boolean saveCalendar(long peer,XmppSession.ArchivePage page,boolean backwards,java.util.Set<Integer> updated) {
        return saveArchive(peer,page,backwards,updated,false);
    }
    private boolean saveArchive(long scope,XmppSession.ArchivePage page,boolean backwards,java.util.Set<Integer> updated,boolean advanceCursor) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            boolean changed = false;
            int previousId=latestLocalMessageId(db);
            long order;
            try (Cursor c = db.rawQuery("SELECT " + (backwards ? "min" : "max") + "(archive_order) FROM messages", null)) {
                c.moveToFirst(); order = backwards ? Math.min(0, c.getLong(0)) - page.messages.size() : Math.max(0, c.getLong(0)) + 1;
            }
            for (XmppMessage message : page.messages) {
                if (scope != 0 && !jid(scope).equals(message.peer.toString())) throw new IllegalArgumentException("Archive belongs to another chat");
                int saved = save(message,null,false,true); changed |= saved != 0;
                if (updated != null && saved != 0) updated.add(saved);
                db.execSQL("UPDATE messages SET archive_order=? WHERE archive_order=0 AND id=(SELECT message FROM message_ids WHERE peer=? AND outgoing=? AND kind='archive' AND value=? AND namespace=?)",
                        new Object[]{order++, peer(message.peer.toString()), message.outgoing ? 1 : 0, message.ids.get("archive"),namespace(message)});
                if(saved>previousId) reads.inheritArchive(saved);
            }
            if(advanceCursor) {
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
            }
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }
    private int latestLocalMessageId(SQLiteDatabase db) {
        try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM messages",null)) {c.moveToFirst();return c.getInt(0);}
    }
    TLRPC.Message message(int id, long peer, long self, String body, boolean outgoing, int time) {
        return message(id, peer, self, body, outgoing, time, true);
    }
    /** Local call summaries never acquire XMPP wire IDs or enter the outgoing message queue. */
    synchronized int saveCall(long self,long peer,String sid,boolean outgoing,boolean video,long timestampMillis,int durationSeconds,String reason) {
        if(self<=0 || peer<=0 || self==peer || sid==null || sid.isEmpty() || sid.length()>256 || timestampMillis<0
                || timestampMillis/1000>Integer.MAX_VALUE || durationSeconds<0) throw new IllegalArgumentException("Invalid local call summary");
        callReason(reason);
        String owner=jid(self);jid(peer);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor existing=db.rawQuery("SELECT message FROM call_events WHERE owner=? AND peer=? AND sid=? AND outgoing=?",
                    new String[]{owner,Long.toString(peer),sid,outgoing ? "1" : "0"})) {
                if(existing.moveToFirst()) { db.setTransactionSuccessful();return 0; }
            }
            ContentValues message=new ContentValues();message.put("peer",peer);message.put("stanza","monogram-local-call:"+UUID.randomUUID());
            message.put("body","");message.put("encrypted",0);message.put("encryption",0);message.put("readable",1);
            message.put("outgoing",outgoing);message.put("time",timestampMillis/1000);message.put("time_ms",timestampMillis);
            long inserted=db.insertOrThrow("messages",null,message);
            if(inserted>Integer.MAX_VALUE) throw new IllegalStateException("Message ID space exhausted");
            int id=(int)inserted;
            ContentValues call=new ContentValues();call.put("message",id);call.put("owner",owner);call.put("peer",peer);call.put("sid",sid);
            call.put("outgoing",outgoing);call.put("video",video);call.put("duration",durationSeconds);call.put("reason",reason);
            db.insertOrThrow("call_events",null,call);
            if(!outgoing && !reason.equals("missed")) db.execSQL("INSERT OR IGNORE INTO local_read_messages(message) VALUES(?)",new Object[]{id});
            db.setTransactionSuccessful();return id;
        } finally { db.endTransaction(); }
    }
    synchronized ArrayList<Integer> callMessageIds(long self) {
        ArrayList<Integer> ids=new ArrayList<>();
        try(Cursor rows=getReadableDatabase().rawQuery("SELECT c.message FROM call_events c JOIN messages m ON m.id=c.message WHERE c.owner=? AND m.deleted=0 ORDER BY m.time_ms DESC,m.id DESC",new String[]{jid(self)})) {
            while(rows.moveToNext()) ids.add(rows.getInt(0));
        }
        return ids;
    }
    private static TLRPC.PhoneCallDiscardReason callReason(String reason) {
        if("missed".equals(reason)) return new TLRPC.TL_phoneCallDiscardReasonMissed();
        if("busy".equals(reason)) return new TLRPC.TL_phoneCallDiscardReasonBusy();
        if("disconnect".equals(reason)) return new TLRPC.TL_phoneCallDiscardReasonDisconnect();
        if("hangup".equals(reason)) return new TLRPC.TL_phoneCallDiscardReasonHangup();
        throw new IllegalArgumentException("Invalid local call outcome");
    }
    private TLRPC.Message callMessage(int id,long peer,long self,boolean outgoing,int time) {
        try(Cursor call=getReadableDatabase().rawQuery("SELECT owner,video,duration,reason FROM call_events WHERE message=?",new String[]{Integer.toString(id)})) {
            if(!call.moveToFirst()) return null;
            if(!jid(self).equals(call.getString(0))) throw new IllegalArgumentException("Call summary belongs to another account");
            TLRPC.TL_messageService message=new TLRPC.TL_messageService();message.id=id;message.date=time;message.out=outgoing;message.dialog_id=peer;
            message.message="";message.flags=256;message.media=new TLRPC.TL_messageMediaEmpty();
            message.peer_id=new TLRPC.TL_peerUser();message.peer_id.user_id=outgoing ? peer : self;
            message.from_id=new TLRPC.TL_peerUser();message.from_id.user_id=outgoing ? self : peer;
            TLRPC.TL_messageActionPhoneCall action=new TLRPC.TL_messageActionPhoneCall();action.call_id=id;action.video=call.getInt(1)!=0;
            action.duration=call.getInt(2);action.reason=callReason(call.getString(3));action.flags=3;
            message.action=action;message.unread=!outgoing && !reads.isRead(id);return message;
        }
    }
    private TLRPC.Message message(int id, long peer, long self, String body, boolean outgoing, int time, boolean embedReply) {
        if(!deleted(id)) { TLRPC.Message call=callMessage(id,peer,self,outgoing,time);if(call!=null) return call; }
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
        message.params=new java.util.HashMap<>();
        message.params.put("monogram-actual-encryption",Integer.toString(encryptionOf(id)));
        if (payload != id) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT body,time FROM messages WHERE id=?", new String[]{Integer.toString(payload)})) {
                if (c.moveToFirst()) { body = c.getString(0); message.message = body; message.edit_date = c.getInt(1); message.flags |= TLRPC.MESSAGE_FLAG_EDITED; }
            }
        }
        message.media = new TLRPC.TL_messageMediaEmpty();
        decorate(message, payload);
        decorateReply(message, self, body, embedReply, payload);
        com.negzero.monogram.xmpp.MessageMarkup visibleMarkup=MonogramFormatting.decorate(getReadableDatabase(),message,payload,body);
        MonogramMentions.decorate(this,message,self,visibleMarkup);
        MonogramEmojis.decorate(getReadableDatabase(),storeContext,database,message,payload);
        MonogramForwarding.decorate(message,body);decorateForwarded(message);
        MonogramLegacyCaptions.decorate(this,getReadableDatabase(),message,self);
        MonogramLocation.decorate(message);
        decorateLinkPreview(message);
        MonogramPolls.Poll poll=MonogramPolls.parse(message.message);
        long reactionCutoff=poll!=null && poll.closed ? Math.max((long)message.date,message.edit_date)*1000+999 : Long.MAX_VALUE;
        message.reactions=peer==self ? savedTags.snapshot(id) : reactions.snapshot(id,self,reactionCutoff);
        MonogramPolls.decorate(message,self);
        if (outgoing && peer != 0 && peer != self) receipts.decorate(message);
        if (!outgoing) MonogramReceipts.restoreIncoming(message, message.media_unread ? 0 : 2);
        if (!outgoing && reads.isRead(id)) MonogramReceipts.markLocalRead(message);
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
        return pendingDecryptions.contains(found) ? 0 : found;
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
        return replyTo(target,peer,self,encrypted,quote,quoteOffset,peer>0);
    }
    synchronized ReplyReference replyTo(int target, long peer, long self, boolean encrypted, String quote, int quoteOffset, boolean quoteFallbackAllowed) {
        if(hiddenCorrection(target)) throw new IllegalArgumentException("Reply target is unavailable");
        TLRPC.Message original = getMessage(target, self, false);
        if(original.action instanceof TLRPC.TL_messageActionPhoneCall) throw new IllegalArgumentException("Local call summaries cannot be replied to");
        if (original.dialog_id != peer) throw new IllegalArgumentException("Reply target belongs to another conversation");
        String author = null, id = null;
        if(peer<0) {
            try(Cursor c=getReadableDatabase().rawQuery("SELECT i.value,a.occupant FROM message_ids i LEFT JOIN room_message_actors a ON a.message=i.message WHERE i.message=? AND i.kind='archive' ORDER BY i.rowid LIMIT 1",new String[]{Integer.toString(target)})) {
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
        if(id==null && !quoteFallbackAllowed) throw new IllegalArgumentException("The original room-assigned message ID has not synced yet");
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
    synchronized boolean hasCachedAttachment(String url, java.io.File file) {
        if (!file.isFile() || file.length() <= 0) return false;
        // Only reuse files committed to this account, never a leftover partial download.
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM attachments WHERE url=? AND path=? AND media IS NOT NULL LIMIT 1",
                new String[]{url, file.getAbsolutePath()})) {
            return c.moveToFirst();
        }
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
    synchronized boolean displayStickerHint(int id,String url) {
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT display_sticker FROM attachments WHERE message=? AND url=? AND sharing IS NULL",new String[]{Integer.toString(id),url})) {
            return cursor.moveToFirst() && cursor.getInt(0)!=0;
        }
    }
    synchronized com.negzero.monogram.xmpp.FileShare sharing(int id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,sharing FROM attachments WHERE message=?", new String[]{Integer.toString(id)})) {
            return c.moveToFirst() ? sharing(c.getString(0), c.getString(1)) : null;
        } catch (Exception invalid) { throw new IllegalStateException("Invalid cached file description", invalid); }
    }
    /** A late metadata upgrade or deletion must invalidate work verified against the earlier description. */
    /** Header hints are provisional: do not replace decoded bytes or newer file-sharing metadata. */
    synchronized boolean attachRemoteMetadata(int id,String url,java.io.File path,TLRPC.MessageMedia media) throws Exception {
        if(path.isFile()) return false;
        return attachDownloaded(id,url,null,path,media);
    }
    synchronized boolean attachDownloaded(int id,String url,com.negzero.monogram.xmpp.FileShare expected,java.io.File path,TLRPC.MessageMedia media) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(hiddenMessage(id)) return false;
            try(Cursor c=db.rawQuery("SELECT url,sharing FROM attachments WHERE message=?",new String[]{Integer.toString(id)})) {
                if(!c.moveToFirst() || !url.equals(c.getString(0))) return false;
                com.negzero.monogram.xmpp.FileShare current=sharing(url,c.getString(1));
                if(expected==null ? current!=null : current==null || !expected.link.wireUrl.equals(url) || !expected.xml().equals(current.xml())) return false;
            }
            attach(id,url,path,media);db.setTransactionSuccessful();return true;
        } finally { db.endTransaction(); }
    }
    synchronized void attach(int id, String url, java.io.File path, TLRPC.MessageMedia media) {
        ContentValues empty = new ContentValues(); empty.put("message", id); empty.put("url", url);
        getWritableDatabase().insertWithOnConflict("attachments", null, empty, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues values = new ContentValues(); values.put("path", path.getAbsolutePath());
        org.telegram.tgnet.SerializedData bytes = new org.telegram.tgnet.SerializedData();
        media.serializeToStream(bytes); values.put("media", bytes.toByteArray()); bytes.cleanup();
        if (getWritableDatabase().update("attachments", values, "message=? AND url=?", new String[]{Integer.toString(id), url}) != 1) throw new IllegalStateException("Attachment reference changed");
    }
    synchronized java.util.LinkedHashMap<String,String> messageMetadata(int id,long self) {
        java.util.LinkedHashMap<String,String> result=new java.util.LinkedHashMap<>();
        TLRPC.Message message=getMessage(id,self);
        result.put("peer",jid(org.telegram.messenger.DialogObject.getPeerDialogId(message.peer_id)));
        int mode=encryptionOf(id);
        result.put("encryption",mode==0 ? "Plaintext" : mode==2 ? "OMEMO 2" : "OMEMO");
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT sender FROM message_senders WHERE message=? ORDER BY instr(sender,'/') DESC LIMIT 1",new String[]{Integer.toString(id)})) {
            if(cursor.moveToFirst()) result.put("sender",cursor.getString(0));
        }
        if(!result.containsKey("sender")) result.put("sender",jid(message.out ? self : org.telegram.messenger.DialogObject.getPeerDialogId(message.peer_id)));
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT kind,value FROM message_ids WHERE message=? ORDER BY kind,value",new String[]{Integer.toString(id)})) {
            while(cursor.moveToNext()) {
                String key="id:"+cursor.getString(0),value=cursor.getString(1);
                if(result.containsKey(key)) value=result.get(key)+"\n"+value;
                result.put(key,value);
            }
        }
        return result;
    }

    synchronized String debugMessage(int id,long self) {
        TLRPC.Message message=getMessage(id,self);
        StringBuilder result=new StringBuilder("Local message ID: ").append(id).append("\nEncryption mode: ").append(encryptionOf(id));
        try(Cursor c=getReadableDatabase().rawQuery("SELECT kind,value FROM message_ids WHERE message=?",new String[]{Integer.toString(id)})) {
            while(c.moveToNext()) result.append("\n").append(c.getString(0)).append(": ").append(c.getString(1));
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT display_sticker,sharing,path FROM attachments WHERE message=?",new String[]{Integer.toString(id)})) {
            if(c.moveToFirst()) {
                result.append("\nLegacy sticker hint: ").append(c.getInt(0)!=0);
                result.append("\nCached file present: ").append(c.getString(2)!=null && new java.io.File(c.getString(2)).isFile());
                result.append("\n\nStored file-sharing XML:\n").append(c.isNull(1) ? "Not present (normal for legacy OMEMO)." : c.getString(1));
            }
        }
        result.append("\n\nProjected media: ").append(message.media==null ? "none" : message.media.getClass().getSimpleName());
        TLRPC.Document document=message.media==null ? null : message.media.document;
        if(document!=null) {
            result.append("\nMIME: ").append(document.mime_type);
            for(TLRPC.DocumentAttribute attr:document.attributes) result.append("\nAttribute: ").append(attr.getClass().getSimpleName());
        }
        return result.toString();
    }
    synchronized TLRPC.Message getMessage(int id, long self) { return getMessage(id, self, true); }
    private TLRPC.Message getMessage(int id, long self, boolean embedReply) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT peer,body,outgoing,time FROM messages WHERE id=?", new String[]{Integer.toString(id)})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("Unknown XMPP message");
            return message(id, c.getLong(0), self, c.getString(1), c.getInt(2) == 1, c.getInt(3), embedReply);
        }
    }
    private synchronized void decorate(TLRPC.Message message, int payload) {
        // Caption edits share the original transfer/cache; later downloads must remain visible through the edit.
        try (Cursor c = getReadableDatabase().rawQuery("SELECT a.url,a.path,a.media,COALESCE(c.caption,a.caption),a.sharing,a.display_sticker FROM attachments a LEFT JOIN attachments c ON c.message=? WHERE a.message=?", new String[]{Integer.toString(payload),Integer.toString(message.id)})) {
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
                MonogramStickers.apply(message, sharing);
                if(sharing==null && link.encrypted() && c.getInt(5)!=0) MonogramStickers.applyDisplayHint(message,file);
            } else if (message.message != null && message.message.toLowerCase(java.util.Locale.ROOT).contains("aesgcm://")) {
                message.message = "Encrypted file link in an unsupported format";
            }
        } catch (Exception e) { message.message = "Attachment unavailable"; }
    }
    synchronized TLRPC.Document customEmoji(long id) { return MonogramEmojis.document(getReadableDatabase(),storeContext,database,id); }
    synchronized ArrayList<TLRPC.Message> historyAround(long peer, long self, int anchor, int limit) {
        return historyAround(peer,self,anchor,limit,null);
    }
    synchronized ArrayList<TLRPC.Message> historyAround(long peer,long self,int anchor,int limit,java.util.Collection<Integer> window) {
        if(hiddenMessage(anchor) || window!=null && !window.contains(anchor)) throw new IllegalArgumentException("Message is not visible in this history window");
        TLRPC.Message target = getMessage(anchor, self);
        if (target.dialog_id != peer) throw new IllegalArgumentException("History anchor belongs to another conversation");
        int count = Math.min(Math.max(limit, 1), 100);
        ArrayList<TLRPC.Message> newer = count > 1 ? history(peer, self, anchor, count / 2, true,window) : new ArrayList<>();
        ArrayList<TLRPC.Message> older = count > newer.size() + 1 ? history(peer, self, anchor, count - newer.size() - 1, false,window) : new ArrayList<>();
        if (older.size() + newer.size() + 1 < count && count > 1) newer = history(peer, self, anchor, count - older.size() - 1, true,window);
        newer.add(target); newer.addAll(older); return newer;
    }
    /** Only a MAM result ID is a proven RSM cursor; an account-injected stanza ID need not equal it. */
    synchronized String navigationArchiveId(long peer,int anchor) {
        try(Cursor rows=getReadableDatabase().rawQuery("SELECT DISTINCT k.value FROM message_ids k WHERE k.peer=? AND k.message=? AND k.kind='mam' AND NOT EXISTS(SELECT 1 FROM message_ids other WHERE other.peer=k.peer AND other.kind=k.kind AND other.value=k.value AND other.message!=k.message)",new String[]{Long.toString(peer),Integer.toString(anchor)})) {
            if(!rows.moveToFirst()) return null;String value=rows.getString(0);return rows.moveToNext() ? null : value;
        }
    }
    synchronized ArrayList<TLRPC.Message> historyAroundDate(long peer,long self,int date,int limit) {
        return historyAroundDate(peer,self,date,limit,null);
    }
    synchronized ArrayList<TLRPC.Message> historyAroundDate(long peer,long self,int date,int limit,java.util.Collection<Integer> window) {
        if(date<=0) return history(peer,self,0,limit,false,window);
        String visible=" FROM messages m WHERE m.peer=? AND m.deleted=0"
                +" AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id AND c.resolved!=0)"
                +" AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id)"+pendingClause("m");
        visible+=calendarClause(window);
        String[] args={Long.toString(peer),Long.toString((long)date*1000)};
        int anchor=0;
        // Choose the first visible message on/after the selected date, then fall back before it.
        try(Cursor c=getReadableDatabase().rawQuery("SELECT m.id"+visible+" AND m.time_ms>=? ORDER BY m.time_ms,m.archive_order,m.id LIMIT 1",args)) {
            if(c.moveToFirst()) anchor=c.getInt(0);
        }
        if(anchor==0) try(Cursor c=getReadableDatabase().rawQuery("SELECT m.id"+visible+" AND m.time_ms<? ORDER BY m.time_ms DESC,m.archive_order DESC,m.id DESC LIMIT 1",args)) {
            if(c.moveToFirst()) anchor=c.getInt(0);
        }
        return anchor==0 ? new ArrayList<>() : historyAround(peer,self,anchor,limit,window);
    }
    synchronized ArrayList<TLRPC.Message> history(long peer, long self, int offset, int limit) {
        return history(peer, self, offset, limit, false);
    }
    synchronized ArrayList<TLRPC.Message> history(long peer, long self, int offset, int limit, boolean forward) {
        return history(peer,self,offset,limit,forward,null);
    }
    synchronized ArrayList<TLRPC.Message> history(long peer,long self,int offset,int limit,boolean forward,java.util.Collection<Integer> window) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        // Local IDs reflect insertion, not chronology: an archive import may insert an older message later.
        String sql = "SELECT m.id,m.body,m.outgoing,m.time FROM messages m";
        ArrayList<String> args = new ArrayList<>();
        if (offset != 0) {
            sql += " JOIN messages a ON a.peer=m.peer AND a.id=?";
            args.add(Integer.toString(offset));
        }
        sql += " WHERE m.peer=? AND m.deleted=0 AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id AND c.resolved!=0) AND NOT EXISTS(SELECT 1 FROM reaction_sources s WHERE s.message=m.id)"; args.add(Long.toString(peer));
        sql += pendingClause("m");
        sql += calendarClause(window);
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
                result.add(message);
            }
        }
        if (forward) java.util.Collections.reverse(result);
        return result;
    }
    private static String calendarClause(java.util.Collection<Integer> window) {
        if(window==null) return "";
        if(window.isEmpty()) return " AND 0";
        StringBuilder sql=new StringBuilder(" AND m.id IN (");
        for(Integer id:window) { if(sql.charAt(sql.length()-1)!='(') sql.append(',');sql.append(id.intValue()); }
        return sql.append(')').toString();
    }
    synchronized java.util.Set<Integer> calendarIds(long peer,java.util.Collection<String> archiveIds) {
        java.util.Set<Integer> ids=new java.util.LinkedHashSet<>();
        for(String archive:archiveIds) if(archive!=null) {
            try(Cursor rows=getReadableDatabase().rawQuery("SELECT DISTINCT message FROM message_ids WHERE peer=? AND kind=? AND value=?",
                    new String[]{Long.toString(peer),peer<0 ? "mam" : "archive",archive})) { while(rows.moveToNext()) ids.add(rows.getInt(0)); }
        }
        return ids;
    }
    synchronized TLRPC.TL_messages_messagesSlice search(long self, long peer, String query, int offset, int skip,
            int limit, long sender, int minDate, int maxDate, TLRPC.MessagesFilter filter, boolean groups, boolean users) {
        return search(self,peer,query,offset,skip,limit,sender,minDate,maxDate,filter,groups,users,java.util.Collections.emptyList());
    }
    synchronized TLRPC.TL_messages_messagesSlice search(long self, long peer, String query, int offset, int skip,
            int limit, long sender, int minDate, int maxDate, TLRPC.MessagesFilter filter, boolean groups, boolean users,
            java.util.List<TLRPC.Reaction> tags) {
        TLRPC.TL_messages_messagesSlice result=new TLRPC.TL_messages_messagesSlice();
        String needle=query==null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        boolean phoneCalls=filter instanceof TLRPC.TL_inputMessagesFilterPhoneCalls;
        ArrayList<String> args=new ArrayList<>();
        // Keep the owner-indexed call table first, rather than scanning and projecting unrelated chat history.
        String source=phoneCalls ? "call_events ce CROSS JOIN messages m ON m.id=ce.message AND ce.owner=? " : "messages m ";
        if(phoneCalls) args.add(jid(self));
        String sql="SELECT m.id,m.peer,p.body,COALESCE(cm.body,a.caption,''),m.time_ms,m.archive_order,f.markup,COALESCE(cf.body,f.caption_body),COALESCE(cf.markup,f.caption_markup) FROM "+source
                +"LEFT JOIN applied_corrections ac ON ac.original=m.id JOIN messages p ON p.id=COALESCE(ac.message,m.id) "
                +"LEFT JOIN message_formatting f ON f.message=p.id AND f.body=p.body "
                +"LEFT JOIN attachments a ON a.message=p.id LEFT JOIN room_senders r ON r.message=m.id "
                +"LEFT JOIN local_media_captions lc ON lc.message=m.id AND lc.placeholder=0 LEFT JOIN messages cm ON cm.id=lc.companion AND cm.deleted=0 AND cm.readable=1 "
                +"LEFT JOIN message_formatting cf ON cf.message=cm.id AND cf.body=cm.body "
                +"WHERE m.deleted=0 AND p.deleted=0 AND p.readable=1 "
                +"AND NOT EXISTS(SELECT 1 FROM corrections c WHERE c.message=m.id AND c.resolved!=0) "
                +"AND NOT EXISTS(SELECT 1 FROM reaction_sources x WHERE x.message=m.id) "
                +"AND NOT EXISTS(SELECT 1 FROM retraction_sources x WHERE x.message=m.id AND x.hidden=1)"+pendingClause("m");
        if(!tags.isEmpty()) {
            sql+=" AND m.peer=?";args.add(Long.toString(self));
            sql+=MonogramSavedTags.searchClause(tags,args);
        }
        if(phoneCalls && filter.missed) sql+=" AND ce.reason IN ('missed','busy')";
        if(peer!=0) { sql+=" AND m.peer=?"; args.add(Long.toString(peer)); }
        if(groups) sql+=" AND m.peer<0";
        if(users) sql+=" AND m.peer>0";
        if(sender!=0) { sql+=" AND CASE WHEN m.outgoing=1 THEN ? ELSE COALESCE(r.user,m.peer) END=?"; args.add(Long.toString(self)); args.add(Long.toString(sender)); }
        if(minDate>0) { sql+=" AND m.time>=?"; args.add(Integer.toString(minDate)); }
        if(maxDate>0) { sql+=" AND m.time<=?"; args.add(Integer.toString(maxDate)); }
        long[] anchor=null;
        if(offset>0) {
            ArrayList<String> anchorArgs=new ArrayList<>();anchorArgs.add(Integer.toString(offset));
            String anchorSql="SELECT time_ms,archive_order,id FROM messages WHERE id=?";
            if(peer!=0) { anchorSql+=" AND peer=?";anchorArgs.add(Long.toString(peer)); }
            if(phoneCalls) { anchorSql+=" AND EXISTS(SELECT 1 FROM call_events c WHERE c.message=messages.id AND c.owner=?)";anchorArgs.add(jid(self)); }
            try(Cursor c=getReadableDatabase().rawQuery(anchorSql,anchorArgs.toArray(new String[0]))) {
                if(c.moveToFirst()) anchor=new long[]{c.getLong(0),c.getLong(1),c.getLong(2)};
                else return result;
            }
        }
        int remainingSkip=Math.max(0,skip), maximum=Math.min(100,Math.max(1,limit));
        boolean unfiltered=phoneCalls || filter==null || filter instanceof TLRPC.TL_inputMessagesFilterEmpty;
        try(Cursor c=getReadableDatabase().rawQuery(sql+" ORDER BY m.time_ms DESC,m.archive_order DESC,m.id DESC",args.toArray(new String[0]))) {
            while(c.moveToNext()) {
                String text=MonogramFormatting.displayText(c.getString(2),c.getString(6)),caption=c.getString(3);
                if(java.util.Objects.equals(caption,c.getString(7))) caption=MonogramFormatting.displayText(caption,c.getString(8));
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
        if(filter instanceof TLRPC.TL_inputMessagesFilterPhoneCalls) return message.action instanceof TLRPC.TL_messageActionPhoneCall
                && (!filter.missed || message.action.reason instanceof TLRPC.TL_phoneCallDiscardReasonMissed || message.action.reason instanceof TLRPC.TL_phoneCallDiscardReasonBusy);
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM peers WHERE id NOT IN (SELECT user FROM room_participants WHERE occupant=peers.jid) AND NOT EXISTS(SELECT 1 FROM room_readers r WHERE r.user=peers.id AND r.actor LIKE 'id:%') ORDER BY id", null)) {
            while (c.moveToNext()) result.add(c.getLong(0));
        }
        return result;
    }
}
