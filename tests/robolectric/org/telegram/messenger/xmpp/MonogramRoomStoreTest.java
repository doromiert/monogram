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
    @org.junit.Before public void nativeSerializationEnvironment() {
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
    }
    final String room="friends@conference.example.org", own="alice@example.org";
    @Test public void recipientChoicesPersistPerAccountAndRoomWithoutChangingTrust() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); org.telegram.messenger.ApplicationLoader.applicationContext=context;
        android.content.SharedPreferences prefs=context.getSharedPreferences("monogram-0",Context.MODE_PRIVATE); prefs.edit().clear().putString("jid",own).commit();
        MonogramController controller=org.robolectric.util.ReflectionHelpers.callConstructor(MonogramController.class,org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,0));
        MonogramStore store=org.robolectric.util.ReflectionHelpers.getField(controller,"store");
        try {
            long dialog=store.room(room,"Alice"),other=store.room("other@conference.example.org","Alice");
            assertTrue(controller.automaticRoomRecipients(dialog));
            controller.setAutomaticRoomRecipients(dialog,false); assertFalse(controller.automaticRoomRecipients(dialog));
            assertTrue(controller.automaticRoomRecipients(other));
            prefs.edit().putString("jid","another@example.org").commit(); assertTrue(controller.automaticRoomRecipients(dialog));
            prefs.edit().putString("jid",own).commit(); assertFalse(controller.automaticRoomRecipients(dialog));
            controller.setAutomaticRoomRecipients(dialog,true);
            assertTrue(controller.excludedRoomRecipients(dialog).isEmpty());
            assertTrue(controller.setRoomRecipient(dialog,"offline@example.org",false));
            assertEquals(java.util.Collections.singleton("offline@example.org"),controller.excludedRoomRecipients(dialog));
            controller.excludedRoomRecipients(dialog).clear(); assertFalse(controller.excludedRoomRecipients(dialog).isEmpty());
            assertTrue(controller.excludedRoomRecipients(other).isEmpty());
            prefs.edit().putString("jid","another@example.org").commit(); assertTrue(controller.excludedRoomRecipients(dialog).isEmpty());
            prefs.edit().putString("jid",own).commit(); assertFalse(controller.excludedRoomRecipients(dialog).isEmpty());
            prefs.edit().putString("jid","another@example.org").commit();
            controller.recordContactRequest(own,"requester@example.org",true);
            assertTrue(controller.pendingContactRequests().isEmpty());
            prefs.edit().putString("jid",own).commit();
            assertEquals(java.util.Collections.singleton("requester@example.org"),controller.pendingContactRequests());
            controller.pendingContactRequests().clear(); assertFalse(controller.pendingContactRequests().isEmpty());
            prefs.edit().putString("jid","another@example.org").commit(); assertTrue(controller.pendingContactRequests().isEmpty());
            prefs.edit().putString("jid",own).commit(); assertFalse(controller.pendingContactRequests().isEmpty());
            prefs.edit().putString("jid","another@example.org").commit();
            controller.recordContactRequest(own,"requester@example.org",false);
            prefs.edit().putString("jid",own).commit(); assertTrue(controller.pendingContactRequests().isEmpty());
            try { controller.setRoomRecipient(dialog,own,false); fail("Own devices cannot be excluded"); } catch(IllegalArgumentException expected) {}
            try { controller.setRoomRecipient(dialog,"bob@example.org/phone",false); fail("Only bare account addresses are recipients"); } catch(IllegalArgumentException expected) {}
            try { controller.setRoomRecipient(store.peer("bob@example.org"),"offline@example.org",false); fail("Room choices cannot modify direct chats"); } catch(IllegalArgumentException expected) {}
            assertTrue(controller.setRoomRecipient(dialog,"offline@example.org",true)); assertTrue(controller.excludedRoomRecipients(dialog).isEmpty());
        } finally {
            for(String name:new String[]{"worker","sendWorker", "cacheWorker","archiveWorker","fileWorker","profileWorker","autoDownloadWorker","roomWorker","bookmarkWorker","fingerprintWorker"})
                ((java.util.concurrent.ExecutorService)org.robolectric.util.ReflectionHelpers.getField(controller,name)).shutdownNow();
            store.close(); ((MonogramOmemo2Store)org.robolectric.util.ReflectionHelpers.getField(controller,"modernStore")).close();
            prefs.edit().clear().commit(); context.deleteDatabase("monogram-0.db");
        }
    }
    XmppRoomMessage event(String nick,String stanza,String archive,String body,boolean encrypted,boolean readable) throws Exception {
        Message wire=new Message(JidCreate.entityBareFrom(own),Message.Type.groupchat);
        wire.setFrom(JidCreate.entityFullFrom(room+"/"+nick)); wire.setStanzaId(stanza);
        wire.addExtension(new OriginIdElement(stanza));
        if(archive!=null) wire.addExtension(new StanzaIdElement(archive,room));
        if(encrypted) wire.addExtension(StandardExtensionElement.builder("encrypted","eu.siacs.conversations.axolotl").build());
        return XmppRoomMessage.from(wire,JidCreate.entityBareFrom(room),JidCreate.entityBareFrom(own),"Alice",body,encrypted,readable);
    }
    @Test public void roomAttachmentsUseTheSharedCacheAndRetainVoiceMetadata() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-attachments.db"; context.deleteDatabase(file);
        String address="friends@conference.example.org";
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),room=store.room(address,"Alice");
            TLRPC.TL_messages_sendMessage send=new TLRPC.TL_messages_sendMessage(); send.peer=new TLRPC.TL_inputPeerChat(); send.peer.chat_id=-room;
            assertEquals(room,MonogramController.sendingPeer(send));
            java.io.File source=java.io.File.createTempFile("monogram-voice",".ogg",context.getCacheDir());
            try {
                java.nio.file.Files.write(source.toPath(),new byte[]{'O','g','g','S',1,2,3,4});
                FileLink plain=FileLink.parse("https://files.example.org/voice.ogg");
                FileShare sharing=FileShare.describe(plain,source,"voice.ogg","audio/ogg");
                int id=store.saveRoom(com.negzero.monogram.xmpp.MessageFixture.roomFile(address,"Bob","voice",sharing.body("Listen"),false,sharing),self);
                TLRPC.Message received=store.getMessage(id,self); assertEquals(room,received.dialog_id); assertTrue(MonogramAttachments.has(received)); assertEquals("Listen",received.message);
                assertEquals(room,MonogramController.sendingPeer(new MonogramFileRequest(received,source,"audio/ogg","Listen")));
                assertNotNull(store.sharing(id)); assertEquals("audio/ogg",received.media.document.mime_type);
                TLRPC.TL_messageMediaDocument media=new TLRPC.TL_messageMediaDocument(); media.document=new TLRPC.TL_document(); media.flags=1;
                media.document.id=id; media.document.dc_id=Integer.MIN_VALUE; media.document.file_reference=new byte[0]; media.document.mime_type="audio/ogg";
                TLRPC.TL_documentAttributeAudio audio=new TLRPC.TL_documentAttributeAudio(); audio.voice=true; audio.duration=1; media.document.attributes.add(audio);
                java.io.File cached=store.attachmentFile(sharing); cached.getParentFile().mkdirs(); java.nio.file.Files.copy(source.toPath(),cached.toPath());
                store.attach(id,plain.wireUrl,cached,media);
                assertTrue(store.getMessage(id,self).media.document.attributes.stream().anyMatch(attribute -> attribute instanceof TLRPC.TL_documentAttributeAudio && ((TLRPC.TL_documentAttributeAudio)attribute).voice));
                cached.delete();
                String secret="aesgcm://files.example.org/secret.png#"+new String(new char[88]).replace('\0','a');
                int encrypted=store.saveRoom(com.negzero.monogram.xmpp.MessageFixture.roomFile(address,"Bob","secret",secret,true,null),self);
                assertTrue(MonogramAttachments.has(store.getMessage(encrypted,self))); assertEquals(secret,store.getMessage(encrypted,self).params.get(MonogramAttachments.PARAM));
            } finally { source.delete(); }
        } finally { context.deleteDatabase(file); }
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
    @Test public void schemaNineMigrationKeepsMessagesAndRescansReactions() throws Exception {
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
                assertEquals(13,store.getReadableDatabase().getVersion()); assertEquals("Keep my direct chat",store.getMessage(original,self).message);
                assertNull(store.archiveCursor(false)); assertTrue(store.rooms().isEmpty()); assertTrue(store.room(room,"Alice")<0);
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
                assertEquals(13,store.getReadableDatabase().getVersion()); assertEquals(0,store.pendingBookmarks().get(0).revision);
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
    XmppRoomMessage identified(String nick,String stanza,String archive,String real) throws Exception {
        Message wire=org.jivesoftware.smack.util.PacketParserUtils.parseStanza("<message type='groupchat' from='"+room+"/"+nick+"' to='"+own+"'><x xmlns='http://jabber.org/protocol/muc#user'><item jid='"+real+"' affiliation='member' role='participant'/></x></message>");
        wire.setStanzaId(stanza); wire.addExtension(new OriginIdElement(stanza)); wire.addExtension(new StanzaIdElement(archive,room));
        return XmppRoomMessage.from(wire,JidCreate.entityBareFrom(room),JidCreate.entityBareFrom(own),nick,"Identity preserved",false,true);
    }
    @Test public void realMembersShareDmProfilesAndOwnEchoRepairsCachedDirection() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-identities.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer(own),bob=store.peer("bob@example.org"),dialog=store.room(room,"Alice");
            store.rosterName(bob,"My friend");
            int incoming=store.saveRoom(identified("RoomNickname","bob-wire","bob-server","bob@example.org/phone"),self);
            assertEquals(bob,store.getMessage(incoming,self).from_id.user_id); assertEquals("My friend",store.user(bob,self).first_name);
            int original=store.saveRoom(event("OtherDevice","mine","mine-server","Identity preserved",false,true),self);
            assertFalse(store.getMessage(original,self).out);
            assertEquals(original,store.saveRoom(identified("OtherDevice","mine","mine-server",own+"/laptop"),self));
            assertTrue(store.getMessage(original,self).out); assertEquals(self,store.getMessage(original,self).from_id.user_id);
            assertEquals(2,store.history(dialog,self,0,100).size());
        } finally { context.deleteDatabase(file); }
    }
    @Test public void roomRightsAndAvatarSurviveNativeSerializationAndReopen() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-avatar.db"; context.deleteDatabase(file); long dialog;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                store.peer(own); dialog=store.room(room,"Alice");
                TLRPC.Chat chat=store.roomChat(dialog);
                assertTrue(org.telegram.messenger.ChatObject.canUserDoAction(chat,org.telegram.messenger.ChatObject.ACTION_SEND_PLAIN)); assertTrue(org.telegram.messenger.ChatObject.canUserDoAction(chat,org.telegram.messenger.ChatObject.ACTION_SEND_PHOTO));
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes); bitmap.recycle();
                store.profile(dialog,new XmppProfile("<vCard xmlns='vcard-temp'/>",null,bytes.toByteArray()));
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                TLRPC.Chat chat=store.roomChat(dialog);
                SerializedData encoded=new SerializedData(); chat.serializeToStream(encoded); SerializedData input=new SerializedData(encoded.toByteArray());
                TLRPC.Chat restored=TLRPC.Chat.TLdeserialize(input,input.readInt32(true),true); encoded.cleanup(); input.cleanup();
                assertTrue(org.telegram.messenger.ChatObject.canUserDoAction(restored,org.telegram.messenger.ChatObject.ACTION_SEND_PLAIN)); assertEquals(Integer.MIN_VALUE,restored.photo.dc_id);
                assertTrue(store.avatar(dialog).isFile()); assertNotNull(MonogramProfiles.imageLocation(restored,store.avatar(dialog)));
                assertEquals(dialog,(long)MonogramProfiles.imageLocation(restored,store.avatar(dialog)).location.local_id);
                assertTrue(store.roomFull(dialog,store.knownPeer(own)).full_chat.chat_photo instanceof TLRPC.TL_photo);
                store.getWritableDatabase().execSQL("INSERT INTO room_permissions VALUES(?, 'visitor', 'none')",new Object[]{-dialog});
                assertFalse(org.telegram.messenger.ChatObject.canUserDoAction(store.roomChat(dialog),org.telegram.messenger.ChatObject.ACTION_SEND_PLAIN));
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void schemaElevenKeepsMessagesAndRescansRoomAuthors() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-authors-migration.db"; context.deleteDatabase(file); long self,dialog; int message;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer(own); dialog=store.room(room,"Alice"); message=store.saveRoom(event("Bob","existing","existing-server","Keep me",false,true),self);
                store.getWritableDatabase().execSQL("INSERT INTO archive_state VALUES(?, 'oldest', 'newest', 1)",new Object[]{"room:"+(-dialog)});
                store.getWritableDatabase().execSQL("DROP TABLE room_permissions"); store.getWritableDatabase().execSQL("DROP TABLE room_message_actors"); store.getWritableDatabase().setVersion(11);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(13,store.getReadableDatabase().getVersion()); assertEquals("Keep me",store.getMessage(message,self).message);
                assertNull(store.archiveCursor(dialog,false)); assertEquals(dialog,store.knownRoom(room));
            }
        } finally { context.deleteDatabase(file); }
    }

}
