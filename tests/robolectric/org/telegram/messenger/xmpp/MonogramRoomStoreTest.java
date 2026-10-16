package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramRoomStoreTest {
    final String room="friends@conference.example.org", own="alice@example.org";
    XmppRoomMessage event(String nick,String stanza,String archive,String body,boolean encrypted,boolean readable) throws Exception {
        Message wire=new Message(JidCreate.entityBareFrom(own),Message.Type.groupchat);
        wire.setFrom(JidCreate.entityFullFrom(room+"/"+nick)); wire.setStanzaId(stanza);
        wire.addExtension(new OriginIdElement(stanza));
        if(archive!=null) wire.addExtension(new StanzaIdElement(archive,room));
        if(encrypted) wire.addExtension(StandardExtensionElement.builder("encrypted","eu.siacs.conversations.axolotl").build());
        return XmppRoomMessage.from(wire,JidCreate.entityBareFrom(room),JidCreate.entityBareFrom(own),"Alice",body,encrypted,readable);
    }
    @Test public void roomHistoryIsSeparateSenderScopedAndPreservesKnownPlaintext() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="rooms.db"; context.deleteDatabase(file);
        long dialog,self; int first;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer(own); long contact=store.peer("bob@example.org"); dialog=store.room(room,"Alice");
                first=store.saveRoom(event("Bob","same-origin","server-1","Hello room",false,true),self);
                int other=store.saveRoom(event("Charlie","same-origin","server-2","Different author",false,true),self);
                assertTrue(first>0 && other!=first);
                assertEquals(0,store.saveRoom(event("Bob","same-origin","server-1","Hello room",false,true),self));
                assertEquals(2,store.history(dialog,self,0,100).size()); assertTrue(store.history(contact,self,0,100).isEmpty());
                assertEquals(-dialog,store.getMessage(first,self).peer_id.chat_id);
                assertEquals("Bob",store.user(store.getMessage(first,self).from_id.user_id,self).first_name);
                assertEquals(2,store.peers().size()); assertTrue(store.peers().contains(self));
                int ownId=store.saveRoom(event("Alice","my-origin",null,"My room message",false,true),self);
                assertEquals(0,store.saveRoom(event("Alice","my-origin","server-3","My room message",false,true),self));
                assertEquals(self,store.getMessage(ownId,self).from_id.user_id); assertTrue(store.getMessage(ownId,self).out);
                int encrypted=store.saveRoom(event("Bob","encrypted","server-4",null,true,false),self);
                assertEquals(encrypted,store.saveRoom(event("Bob","encrypted","server-4","Decrypted room text",true,true),self));
                assertEquals(0,store.saveRoom(event("Bob","encrypted","server-4",null,true,false),self));
                assertEquals("Decrypted room text",store.getMessage(encrypted,self).message);
                SerializedData data=new SerializedData(); store.roomFull(dialog,self).serializeToStream(data); byte[] bytes=data.toByteArray(); data.cleanup();
                SerializedData input=new SerializedData(bytes); TLRPC.TL_messages_chatFull full=TLRPC.TL_messages_chatFull.TLdeserialize(input,input.readInt32(true),true); input.cleanup();
                assertEquals(room,full.chats.get(0).title); assertEquals(room,full.full_chat.about);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(dialog,store.knownRoom(room)); assertEquals("Hello room",store.getMessage(first,self).message);
                assertTrue(store.room(dialog).autojoin); store.leaveRoom(dialog); assertFalse(store.room(dialog).autojoin);
                assertEquals(4,store.history(dialog,self,0,100).size());
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void schemaNineMigrationKeepsDirectMessagesAndCursors() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-migration.db"; context.deleteDatabase(file);
        int original; long self;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer(own);
                original=store.save(MessageFixture.replyMessage("existing",null,null,"Keep my direct chat",true,false,100000,"phone",false,null));
                android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
                db.execSQL("INSERT INTO archive_state VALUES('direct','oldest','newest',1)");
                db.execSQL("DROP TABLE rooms"); db.execSQL("DROP TABLE room_participants"); db.execSQL("DROP TABLE room_senders"); db.setVersion(9);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(11,store.getReadableDatabase().getVersion()); assertEquals("Keep my direct chat",store.getMessage(original,self).message);
                assertEquals("newest",store.archiveCursor(false)); assertTrue(store.rooms().isEmpty()); assertTrue(store.room(room,"Alice")<0);
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void schemaTenImportsExistingBookmarksAndKeepsOfflineChoices() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="bookmarks.db"; context.deleteDatabase(file);
        long dialog,self; int message;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer(own); dialog=store.room(room,"Alice"); message=store.saveRoom(event("Bob","old","sid-old","Keep my room history",false,true),self);
                store.getWritableDatabase().execSQL("DROP TABLE room_bookmarks"); store.getWritableDatabase().setVersion(10);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(11,store.getReadableDatabase().getVersion()); assertEquals(0,store.pendingBookmarks().get(0).revision);
                XmppBookmarks.Bookmark bookmark=XmppBookmarks.Bookmark.parse(room,"<conference xmlns='urn:xmpp:bookmarks:1' name='My existing group' autojoin='true'><nick>ExistingNick</nick></conference>");
                store.importBookmark(bookmark,"vault-encrypted-xml","Alice");
                assertTrue(store.pendingBookmarks().isEmpty()); assertEquals("ExistingNick",store.room(dialog).nickname); assertEquals("My existing group",store.roomChat(dialog).title);
                store.leaveRoom(dialog); store.dirtyBookmark(dialog); long revision=store.pendingBookmarks().get(0).revision;
                store.importBookmark(bookmark,"new-vault-encrypted-xml","Alice"); assertFalse(store.room(dialog).autojoin);
                store.bookmarkSaved(dialog,revision-1,"stale-confirmation"); assertFalse(store.pendingBookmarks().isEmpty());
                store.bookmarkSaved(dialog,revision,"current-confirmation"); assertTrue(store.pendingBookmarks().isEmpty());
                store.missingBookmarks(java.util.Collections.emptySet()); assertFalse(store.room(dialog).autojoin);
                assertEquals("Keep my room history",store.getMessage(message,self).message);
                assertEquals(dialog,store.knownRoom(room));
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void roomArchivePagesKeepKnownTextAndCommitOnlyTheirOwnCursor() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-pages.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer(own),dialog=store.room(room,"Alice");
            int original=store.saveRoom(event("Bob","encrypted","stable-id","Cached decrypted text",true,true),self);
            XmppRoomMessage replay=MessageFixture.roomArchive(room,"Bob","encrypted","stable-id","mam-id",null,200000,true);
            XmppRoomMessage older=MessageFixture.roomArchive(room,"Charlie","oldest","stable-oldest","mam-oldest","Older room message",100000,false);
            java.util.Set<Integer> changed=new java.util.HashSet<>();
            assertTrue(store.saveRoomArchive(dialog,new XmppRooms.ArchivePage(java.util.Arrays.asList(older,replay),"mam-oldest","mam-id",true),true,self,changed));
            assertEquals("Cached decrypted text",store.getMessage(original,self).message); assertEquals(200,store.getMessage(original,self).date);
            assertEquals(2,store.history(dialog,self,0,100).size()); assertTrue(store.archiveComplete(dialog));
            assertEquals("mam-id",store.archiveCursor(dialog,false)); assertNull(store.archiveCursor(false));
            XmppRoomMessage foreign=MessageFixture.roomArchive("other@conference.example.org","Bob","foreign","foreign","foreign","Wrong room",300000,false);
            try { store.saveRoomArchive(dialog,new XmppRooms.ArchivePage(java.util.Collections.singletonList(foreign),"wrong","wrong",true),false,self,changed); fail("Foreign archive must fail"); }
            catch(IllegalArgumentException expected) {}
            assertEquals("mam-id",store.archiveCursor(dialog,false)); assertEquals(2,store.history(dialog,self,0,100).size());
        } finally { context.deleteDatabase(file); }
    }
}
