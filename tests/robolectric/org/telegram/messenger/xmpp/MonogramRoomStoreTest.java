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
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramRoomStoreTest {
    @org.junit.Before public void nativeSerializationEnvironment() {
        org.telegram.messenger.ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
    }
    final String room="friends@conference.example.org", own="alice@example.org";
    @Test public void schemaSeventeenPreservesRoomsAndMapsVerifiedOwnerAndAdminRights() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-description-migration.db"; context.deleteDatabase(file);
        long dialog,self;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer(own);dialog=store.room(room,"Alice");
                store.getWritableDatabase().execSQL("UPDATE rooms SET title='Existing group',subject='Keep topic' WHERE id=?",new Object[]{-dialog});
                store.getWritableDatabase().execSQL("INSERT INTO room_permissions VALUES(?, 'moderator', 'owner')",new Object[]{-dialog});
                MonogramStoreDeviceTest.legacyRoomDescription(store.getWritableDatabase());store.getWritableDatabase().setVersion(17);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(28,store.getReadableDatabase().getVersion());assertEquals(dialog,store.knownRoom(room));
                assertEquals("Existing group",store.roomChat(dialog).title);assertEquals("Keep topic",store.room(dialog).subject);
                assertTrue(store.roomChat(dialog).creator);assertEquals("",store.roomFull(dialog,self).full_chat.about);
                store.getWritableDatabase().execSQL("UPDATE rooms SET description='Actual description' WHERE id=?",new Object[]{-dialog});
                assertEquals("Actual description",store.roomFull(dialog,self).full_chat.about);
                store.getWritableDatabase().execSQL("UPDATE room_permissions SET affiliation='admin' WHERE room=?",new Object[]{-dialog});
                TLRPC.Chat admin=store.roomChat(dialog);assertFalse(admin.creator);assertTrue(admin.admin_rights.invite_users);assertFalse(admin.admin_rights.change_info);
                store.getWritableDatabase().execSQL("UPDATE room_permissions SET affiliation='member',role='participant' WHERE room=?",new Object[]{-dialog});
                assertFalse(store.roomChat(dialog).creator);assertNull(store.roomChat(dialog).admin_rights);
                assertTrue(store.roomChat(dialog).default_banned_rights.change_info);
            }
        } finally {context.deleteDatabase(file);}
    }
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
            for(String name:new String[]{"worker","sendWorker", "cacheWorker","archiveWorker","fileWorker","profileWorker","autoDownloadWorker","roomWorker","bookmarkWorker","readSyncWorker","fingerprintWorker"})
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
                assertEquals(room,full.chats.get(0).title); assertEquals("",full.full_chat.about);
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
                db.execSQL("DROP TABLE rooms"); db.execSQL("DROP TABLE room_participants"); db.execSQL("DROP TABLE room_senders"); db.execSQL("DROP TABLE room_message_actors"); MonogramStoreDeviceTest.legacyDeletions(db); db.setVersion(9);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(28,store.getReadableDatabase().getVersion()); assertEquals("Keep my direct chat",store.getMessage(original,self).message);
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
                store.getWritableDatabase().execSQL("DROP TABLE room_bookmarks"); store.getWritableDatabase().execSQL("DROP TABLE room_message_actors"); MonogramStoreDeviceTest.legacyDeletions(store.getWritableDatabase()); store.getWritableDatabase().setVersion(10);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(28,store.getReadableDatabase().getVersion()); assertEquals(0,store.pendingBookmarks().get(0).revision);
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
        return MessageFixture.roomText(room,nick,stanza,archive,"Identity preserved",real,null,200000,false,null,null);
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
                java.io.File previousAvatar=store.avatar(dialog);
                android.graphics.Bitmap oversized=android.graphics.Bitmap.createBitmap(5000,1,android.graphics.Bitmap.Config.ARGB_8888);
                java.io.ByteArrayOutputStream invalid=new java.io.ByteArrayOutputStream();oversized.compress(android.graphics.Bitmap.CompressFormat.PNG,100,invalid);oversized.recycle();
                XmppProfile invalidReplacement=new XmppProfile("<vCard xmlns='vcard-temp'/>",null,invalid.toByteArray());
                try { store.profile(dialog,invalidReplacement);fail("An invalid replacement must preserve the cached avatar"); }
                catch(IllegalArgumentException expected) {}
                assertEquals(previousAvatar,store.avatar(dialog));assertTrue(previousAvatar.isFile());
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
                store.getWritableDatabase().execSQL("DROP TABLE room_permissions"); store.getWritableDatabase().execSQL("DROP TABLE room_message_actors"); MonogramStoreDeviceTest.legacyDeletions(store.getWritableDatabase()); store.getWritableDatabase().setVersion(11);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(28,store.getReadableDatabase().getVersion()); assertEquals("Keep me",store.getMessage(message,self).message);
                assertNull(store.archiveCursor(dialog,false)); assertEquals(dialog,store.knownRoom(room));
            }
        } finally { context.deleteDatabase(file); }
    }

    @Test public void roomRepliesUseRoomIdsAndResolveLateOriginals() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-replies.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer(own),dialog=store.room(room,"Alice");
            ReplyReference reference=new ReplyReference(room+"/Bob","server-original").quoted("Before 😀");
            int reply=store.saveRoom(MessageFixture.roomText(room,"Charlie","reply-wire","server-reply",reference.wireBody("Answer"),"charlie@example.org","charlie",200000,false,reference,null),self);
            TLRPC.Message missingOriginal=store.getMessage(reply,self);
            assertNull(missingOriginal.reply_to);assertEquals("Before 😀\nAnswer",missingOriginal.message);
            assertTrue(missingOriginal.entities.stream().anyMatch(entity -> entity instanceof TLRPC.TL_messageEntityBlockquote && entity.offset==0 && entity.length>=9));
            int original=store.saveRoom(MessageFixture.roomText(room,"Bob","client-original","server-original","Before 😀","bob@example.org","bob",100000,false,null,null),self);
            assertEquals(reply,store.changedReplies(dialog,self).get(0).id);
            assertEquals(original,store.getMessage(reply,self).reply_to.reply_to_msg_id);
            assertEquals("Answer",store.getMessage(reply,self).message); assertEquals("Before 😀",store.getMessage(reply,self).replyMessage.message);
            ReplyReference outgoing=store.replyTo(original,dialog,self,true,null,0);
            assertEquals("server-original",outgoing.id); assertEquals(room+"/Bob",outgoing.to);
            for(ReplyReference invalid:new ReplyReference[]{new ReplyReference(room+"/Bob","client-original"),new ReplyReference(room+"/Charlie","server-original")}) {
                int id=store.saveRoom(MessageFixture.roomText(room,"Charlie","invalid-"+invalid.id+invalid.to,"server-invalid-"+invalid.id+invalid.to,"Keep fallback","charlie@example.org","charlie",300000,false,invalid,null),self);
                assertNull(store.getMessage(id,self).reply_to);
            }
            int noRoomId=store.saveRoom(event("Bob","no-room-id",null,"No room ID",false,true),self);
            assertThrows(IllegalArgumentException.class,() -> store.replyTo(noRoomId,dialog,self,false,null,0));
            ReplyReference fallback=store.replyTo(noRoomId,dialog,self,false,null,0,true);
            assertEquals("",fallback.id); assertNull(fallback.to);
            assertTrue(fallback.wireBody("Answer").startsWith("> No room ID\n"));
        } finally { context.deleteDatabase(file); }
    }
    @Test public void correctionsBindStableAuthorsAndProtectEncryptedEditsAndReplyPreviews() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-corrections.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer(own),dialog=store.room(room,"Alice");
            int edit=store.saveRoom(MessageFixture.roomText(room,"NewBob","edit","server-edit","Corrected","bob@example.org","bob",200000,true,null,"original"),self);
            store.reconcileCorrections(dialog); assertFalse(store.hiddenCorrection(edit));
            int original=store.saveRoom(MessageFixture.roomText(room,"Bob","original","server-original","Before","bob@example.org","bob",100000,false,null,null),self);
            int collision=store.saveRoom(MessageFixture.roomText(room,"Charlie","original","server-other","Other author","charlie@example.org","charlie",100000,false,null,null),self);
            MonogramStore.CorrectionChanges changes=store.reconcileCorrections(dialog);
            assertTrue(changes.hidden.contains(edit)); assertEquals("Corrected",store.getMessage(original,self).message); assertEquals("Other author",store.getMessage(collision,self).message);
            assertTrue((store.getMessage(original,self).flags & TLRPC.MESSAGE_FLAG_EDITED)!=0);
            assertTrue(store.replyTo(original,dialog,self,false,null,0).ranges.isEmpty());
            ReplyReference ref=new ReplyReference(room+"/Bob","server-original");
            int reply=store.saveRoom(MessageFixture.roomText(room,"Charlie","reply","server-reply","Answer","charlie@example.org","charlie",250000,true,ref,null),self);
            store.changedReplies(dialog,self);
            int repeated=store.saveRoom(MessageFixture.roomText(room,"NewBob","edit-again","server-edit-again","Corrected again","bob@example.org","bob",300000,true,null,"original"),self);
            changes=store.reconcileCorrections(dialog); assertTrue(store.hiddenCorrection(repeated));
            assertEquals("Corrected again",store.changedReplies(dialog,self,changes.edited).get(0).replyMessage.message);
            for(String[] actor:new String[][]{{"Mallory","mallory@example.org","mallory"},{"NewBob","bob@example.org","reused-nickname"}}) {
                int forged=store.saveRoom(MessageFixture.roomText(room,actor[0],"forged-"+actor[2],"server-forged-"+actor[2],"Forged",actor[1],actor[2],400000,true,null,"original"),self);
                store.reconcileCorrections(dialog); assertFalse(store.hiddenCorrection(forged)); assertEquals("Corrected again",store.getMessage(original,self).message);
            }
            int downgrade=store.saveRoom(MessageFixture.roomText(room,"NewBob","downgrade","server-downgrade","Plaintext replacement","bob@example.org","bob",500000,false,null,"original"),self);
            store.reconcileCorrections(dialog); assertFalse(store.hiddenCorrection(downgrade)); assertEquals("Corrected again",store.getMessage(original,self).message);
            assertEquals(original,store.getMessage(reply,self).reply_to.reply_to_msg_id);
        } finally { context.deleteDatabase(file); }
    }
    @Test public void schemaThirteenPreservesCachedMessagesAndRequiresVerifiedAuthorsBeforeEditing() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="room-verified-migration.db"; context.deleteDatabase(file); long self,dialog; int original;
        XmppRoomMessage message=MessageFixture.roomText(room,"OtherDevice","mine","server-mine","Keep me",own,"own",100000,true,null,null);
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer(own); dialog=store.room(room,"Alice"); original=store.saveRoom(message,self);
                store.getWritableDatabase().execSQL("ALTER TABLE room_message_actors DROP COLUMN stable_id");
                store.getWritableDatabase().execSQL("ALTER TABLE room_message_actors DROP COLUMN verified");
                MonogramStoreDeviceTest.legacyDeletions(store.getWritableDatabase()); store.getWritableDatabase().setVersion(13);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(28,store.getReadableDatabase().getVersion()); assertEquals("Keep me",store.getMessage(original,self).message);
                try { store.editTarget(original,dialog,self); fail("Old unverified author accepted"); }
                catch(MonogramStore.EditUnavailable expected) { assertEquals("MONOGRAM_EDIT_ROOM_AUTHOR_UNVERIFIED",expected.code); }
                store.saveRoom(message,self); assertEquals("mine",store.editTarget(original,dialog,self).correction.id);
                assertEquals(1,store.history(dialog,self,0,100).size());
            }
        } finally { context.deleteDatabase(file); }
    }

}
