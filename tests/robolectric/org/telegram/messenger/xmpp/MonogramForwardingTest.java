package org.telegram.messenger.xmpp;

import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.SendMessagesHelper.SendMessageParams;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMessageRegressionTest.Device.class,MonogramForwardingTest.Strings.class})
public class MonogramForwardingTest {
    @org.robolectric.annotation.Implements(value=org.telegram.messenger.LocaleController.class,isInAndroidSdk=false)
    public static class Strings {
        @org.robolectric.annotation.Implementation public static String getString(int id) { return "Forwarded attachment"; }
    }
    @Test public void textGetsNewDestinationWithoutSourceReplyOrEncryptionPolicy() {
        TLRPC.Message original=new TLRPC.TL_message();
        original.message="hello";original.dialog_id=42;
        original.reply_to=new TLRPC.TL_messageReplyHeader();original.reply_to.reply_to_msg_id=7;
        original.params=new HashMap<>();original.params.put("monogram-encryption","PLAIN");
        TLRPC.TL_messageEntityBold bold=new TLRPC.TL_messageEntityBold();bold.length=5;original.entities.add(bold);
        SendMessageParams send=MonogramForwarding.copy(original,-99,true);
        assertEquals(-99,send.peer);assertEquals("hello",send.message);
        assertEquals(1,send.entities.size());assertNotSame(original.entities,send.entities);
        assertNull(send.replyToMsg);assertNull(send.retryMessageObject);
        assertTrue(send.params.isEmpty());assertEquals(42,original.dialog_id);
    }
    @Test public void mediaCopiesAttributesAndOnlyCarriesPrivateDownloadReference() {
        TLRPC.Message original=new TLRPC.TL_message();original.id=12;original.message="caption";
        original.media=new TLRPC.TL_messageMediaDocument();
        TLRPC.TL_document document=new TLRPC.TL_document();document.id=33;document.mime_type="video/mp4";
        document.file_reference=new byte[0];document.size=100;
        TLRPC.TL_documentAttributeVideo video=new TLRPC.TL_documentAttributeVideo();video.round_message=true;
        document.attributes.add(video);original.media.document=document;
        original.params=new HashMap<>();original.params.put(MonogramAttachments.PARAM,"https://example.org/private-file");
        original.params.put("monogram-encryption","PLAIN");
        SendMessageParams send=MonogramForwarding.copy(original,77,true);
        assertEquals("",send.caption);assertEquals(77,send.peer);assertNotSame(document,send.document);
        assertTrue(send.document.attributes.get(0).round_message);
        assertEquals("12",send.params.get(MonogramForwarding.SOURCE));
        assertEquals(original.params.get(MonogramAttachments.PARAM),send.params.get(MonogramForwarding.ADDRESS));
        assertFalse(send.params.containsKey(MonogramAttachments.PARAM));assertFalse(send.params.containsKey("monogram-encryption"));
        assertNull(send.replyToMsg);assertEquals("caption",original.message);
        assertEquals("caption",MonogramForwarding.copy(original,77,false).caption);
    }
    @Test public void oldFailedForwardKeepsItsLocalIdentityAndAcquiresDownloadSourceOnRetry() {
        TLRPC.Message pending=new TLRPC.TL_message();pending.id=-17;pending.random_id=123;
        pending.message="caption";pending.fwd_from=new TLRPC.TL_messageFwdHeader();pending.flags|=TLRPC.MESSAGE_FLAG_FWD;
        pending.params=new HashMap<>();pending.params.put("fwd_id","12");pending.params.put("fwd_peer","42");
        pending.params.put("monogram-encryption","OMEMO");
        TLRPC.Message source=new TLRPC.TL_message();source.id=12;source.params=new HashMap<>();
        source.params.put(MonogramAttachments.PARAM,"https://example.org/private-file");
        MonogramForwarding.retry(pending,source);
        assertEquals(-17,pending.id);assertEquals(123,pending.random_id);assertEquals("caption",pending.message);
        assertNull(pending.fwd_from);assertEquals(0,pending.flags&TLRPC.MESSAGE_FLAG_FWD);
        assertFalse(pending.params.containsKey("fwd_id"));assertFalse(pending.params.containsKey("fwd_peer"));
        assertEquals("12",pending.params.get(MonogramForwarding.SOURCE));assertEquals("OMEMO",pending.params.get("monogram-encryption"));
    }
    @Test public void wireAttributionDoesNotMutateSourceOrNativeVisibleTextAndHideSenderIsRespected() {
        TLRPC.Message source=new TLRPC.TL_message();source.message="hello";source.date=1700000000;
        source.fwd_from=new TLRPC.TL_messageFwdHeader();source.fwd_from.from_name="Alice 🧑‍💻";source.fwd_from.date=1690000000;
        TLRPC.TL_messageEntityBold bold=new TLRPC.TL_messageEntityBold();bold.offset=0;bold.length=5;source.entities.add(bold);
        SendMessageParams copy=MonogramForwarding.copy(source,99,false,0,false);
        assertEquals("hello",copy.message);assertEquals("Alice 🧑‍💻",copy.params.get(MonogramForwarding.NAME));
        TLRPC.Message pending=new TLRPC.TL_message();pending.message=copy.message;pending.params=copy.params;pending.entities=copy.entities;
        MonogramForwarding.pending(pending);assertEquals("hello",pending.message);assertNotNull(pending.fwd_from);assertNull(pending.fwd_from.from_id);
        TLRPC.TL_messages_sendMessage request=new TLRPC.TL_messages_sendMessage();request.message=copy.message;request.entities=copy.entities;
        MonogramForwarding.prepare(request,pending);
        com.negzero.monogram.xmpp.ForwardedContent wire=com.negzero.monogram.xmpp.ForwardedContent.parse(request.message);
        assertNotNull(wire);assertEquals(1690000000,wire.date);assertEquals("hello",wire.body);
        assertEquals(wire.prefix().length(),request.entities.get(0).offset);assertEquals(0,pending.entities.get(0).offset);assertEquals(0,bold.offset);
        MonogramForwarding.prepare(request,pending);assertEquals(wire.wireBody(),request.message);
        SendMessageParams hidden=MonogramForwarding.copy(source,99,false,0,true);assertFalse(hidden.params.containsKey(MonogramForwarding.NAME));assertEquals("hello",hidden.message);
    }
    @Test public void attributionAndTextEntitiesSurviveStoreReopenAndReforwarding() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="forward-label.db";context.deleteDatabase(database);long self;int id;
        String text=com.negzero.monogram.xmpp.ForwardedContent.of("Alice 🧑‍💻",1700000000,"hello").wireBody();
        try {
            try(MonogramStore store=new MonogramStore(context,database)) {
                self=store.peer("bob@example.org");
                id=store.save(com.negzero.monogram.xmpp.XmppMessage.sent(org.jxmpp.jid.impl.JidCreate.entityFullFrom("bob@example.org/mobile"),"carol@example.org","forward",text,false));
                assertEquals("Alice 🧑‍💻",store.getMessage(id,self).fwd_from.from_name);
            }
            try(MonogramStore store=new MonogramStore(context,database)) {
                TLRPC.Message received=store.getMessage(id,self);assertEquals("hello",received.message);assertEquals(1700000000,received.fwd_from.date);
                SendMessageParams again=MonogramForwarding.copy(received,7,false,0,false);
                assertEquals("Alice 🧑‍💻",again.params.get(MonogramForwarding.NAME));assertEquals("hello",again.message);
            }
        } finally {context.deleteDatabase(database);}
    }
    @Test public void encryptedMediaForwardKeepsAHeaderEvenWithoutItsLegacyCaption() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="forward-file-label.db";context.deleteDatabase(database);
        try(MonogramStore store=new MonogramStore(context,database)) {
            long self=store.peer("bob@example.org");
            com.negzero.monogram.xmpp.FileLink link=com.negzero.monogram.xmpp.FileLink.parse("aesgcm://files.example.org/fresh.bin#"+new String(new char[88]).replace('\0','a'));
            com.negzero.monogram.xmpp.XmppMessage message=com.negzero.monogram.xmpp.XmppMessage.sentFile(org.jxmpp.jid.impl.JidCreate.entityFullFrom("bob@example.org/mobile"),"carol@example.org","forward-file",link,true,null);
            assertEquals(link.wireUrl,message.body);
            int id=store.save(message);store.forwarded(id,com.negzero.monogram.xmpp.ForwardedContent.of("original@example.org",1700000000,""));
            TLRPC.Message projected=store.getMessage(id,self);
            assertTrue(MonogramAttachments.has(projected));assertEquals("original@example.org",projected.fwd_from.from_name);
            assertEquals("",projected.message);assertEquals(link.wireUrl,projected.params.get(MonogramAttachments.PARAM));
        } finally {context.deleteDatabase(database);}
    }
    @Test public void forwardedLocationUsesTheSameBodyProjection() {
        TLRPC.TL_messages_sendMedia request=new TLRPC.TL_messages_sendMedia();request.media=new TLRPC.TL_inputMediaGeoPoint();
        request.media.geo_point=new TLRPC.TL_inputGeoPoint();request.media.geo_point.lat=51.5;request.media.geo_point._long=0.12;
        request.message=com.negzero.monogram.xmpp.ForwardedContent.of("Original",1700000000,"").wireBody();
        String wire=MonogramLocation.text(request).message;
        TLRPC.Message visible=new TLRPC.TL_message();visible.message=wire;MonogramForwarding.decorate(visible,wire);MonogramLocation.decorate(visible);
        assertTrue(visible.media instanceof TLRPC.TL_messageMediaGeo);assertEquals("Original",visible.fwd_from.from_name);
    }
    @Test public void forwardedPollProjectsAsANewPollAndClosingKeepsAttribution() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="forward-poll-label.db";context.deleteDatabase(database);
        try(MonogramStore store=new MonogramStore(context,database)) {
            long self=store.peer("bob@example.org");String poll=new MonogramPolls.Poll("Question?","Description",java.util.Arrays.asList("Yes","No"),false,false).body();
            String wire=com.negzero.monogram.xmpp.ForwardedContent.of("Original",1700000000,poll).wireBody();
            int id=store.save(com.negzero.monogram.xmpp.XmppMessage.sent(org.jxmpp.jid.impl.JidCreate.entityFullFrom("bob@example.org/mobile"),"carol@example.org","poll-forward",wire,false));
            TLRPC.Message projected=store.getMessage(id,self);assertTrue(projected.media instanceof TLRPC.TL_messageMediaPoll);assertEquals("Original",projected.fwd_from.from_name);
            TLRPC.TL_messages_editMessage close=new TLRPC.TL_messages_editMessage();close.peer=new TLRPC.TL_inputPeerUser();close.peer.user_id=projected.dialog_id;
            TLRPC.TL_inputMediaPoll input=new TLRPC.TL_inputMediaPoll();input.poll=new TLRPC.TL_poll();input.poll.closed=true;close.media=input;
            MonogramPolls.close(close,projected,self);
            com.negzero.monogram.xmpp.ForwardedContent closed=com.negzero.monogram.xmpp.ForwardedContent.parse(close.message);
            assertNotNull(closed);assertEquals("Original",closed.name);assertTrue(MonogramPolls.parse(closed.body).closed);
        } finally {context.deleteDatabase(database);}
    }
    @Test public void realXmppAddressWinsOverDisplayNameAndNameOnlyOldForwardsAreNotGuessed() {
        TLRPC.Message source=new TLRPC.TL_message();source.from_id=new TLRPC.TL_peerUser();source.from_id.user_id=42;
        assertEquals("alice@example.org",MonogramForwarding.sourceAddress(source,id->{assertEquals(42,id);return "alice@example.org";}));
        source.fwd_from=new TLRPC.TL_messageFwdHeader();source.fwd_from.from_name="old display name";
        assertNull(MonogramForwarding.sourceAddress(source,id->{throw new AssertionError("Must not misattribute an old forward to its forwarder");}));
        source.fwd_from.from_name="original@other.example";
        assertEquals("original@other.example",MonogramForwarding.sourceAddress(source,id->{throw new AssertionError();}));
        SendMessageParams copy=MonogramForwarding.copy(source,99,false,0,false);assertEquals("original@other.example",copy.params.get(MonogramForwarding.NAME));
    }
    @Test public void legacyAttributionIsASeparateCaptionWithCorrectUtf16EntityOffsets() {
        org.telegram.messenger.ApplicationLoader.applicationContext=org.robolectric.RuntimeEnvironment.getApplication();
        HashMap<String,String> params=new HashMap<>();params.put(MonogramForwarding.NAME,"alice@example.org");params.put(MonogramForwarding.DATE,"1700000000");
        String body=MonogramForwarding.legacyCaption(params,"caption 🧑‍💻");
        com.negzero.monogram.xmpp.ForwardedContent parsed=com.negzero.monogram.xmpp.ForwardedContent.parse(body);
        assertEquals("alice@example.org",parsed.name);assertEquals("caption 🧑‍💻",parsed.body);assertFalse(body.contains("aesgcm://"));
        TLRPC.TL_messageEntityBold entity=new TLRPC.TL_messageEntityBold();entity.offset=0;entity.length=7;
        java.util.ArrayList<TLRPC.MessageEntity> shifted=MonogramForwarding.captionEntities(body,java.util.Collections.singletonList(entity));
        assertEquals(parsed.prefix().length(),shifted.get(0).offset);assertEquals(0,entity.offset);
        assertFalse(com.negzero.monogram.xmpp.ForwardedContent.parse(MonogramForwarding.legacyCaption(params,"")).body.isEmpty());
        assertEquals("",MonogramForwarding.legacyCaption(null,""));
    }
    @Test public void legacyCaptionFoldsIntoTheFileAcrossReloadAndSearchWithoutChangingEitherWireBody() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="forward-compound.db";context.deleteDatabase(database);
        String url="aesgcm://files.example.org/file.jpg#"+new String(new char[88]).replace('\0','a');
        String wire=com.negzero.monogram.xmpp.ForwardedContent.of("original@example.org",1700000000,"caption 🧑‍💻").wireBody();
        int file,caption;long self,peer;
        try {
            try(MonogramStore store=new MonogramStore(context,database)) {
                self=store.peer("bob@example.org");peer=store.peer("carol@example.org");
                org.jxmpp.jid.EntityFullJid sender=org.jxmpp.jid.impl.JidCreate.entityFullFrom("bob@example.org/mobile");
                file=store.save(com.negzero.monogram.xmpp.XmppMessage.sentFile(sender,"carol@example.org","file",com.negzero.monogram.xmpp.FileLink.parse(url),true,null));
                store.forwarded(file,com.negzero.monogram.xmpp.ForwardedContent.of("original@example.org",1700000000,""));
                caption=store.save(com.negzero.monogram.xmpp.XmppMessage.sent(sender,"carol@example.org","caption",wire,true));
                int unrelated=store.save(com.negzero.monogram.xmpp.XmppMessage.sent(sender,"other@example.org","wrong-chat",wire,true));
                assertFalse(store.linkLegacyCaption(file,unrelated,false));assertFalse(store.hiddenMessage(unrelated));
                assertTrue(store.linkLegacyCaption(file,caption,false));
            }
            try(MonogramStore store=new MonogramStore(context,database)) {
                assertTrue(store.hiddenMessage(caption));assertEquals(file,store.legacyCaptionParent(caption));
                java.util.ArrayList<TLRPC.Message> history=store.history(peer,self,0,20);
                assertEquals(1,history.size());assertEquals(file,history.get(0).id);
                assertEquals("caption 🧑‍💻",history.get(0).message);assertEquals("original@example.org",history.get(0).fwd_from.from_name);
                TLRPC.TL_messages_messagesSlice found=store.search(self,peer,"caption",0,0,20,0,0,0,new TLRPC.TL_inputMessagesFilterEmpty(),false,false);
                assertEquals(1,found.count);assertEquals(file,found.messages.get(0).id);
                try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT body FROM messages WHERE id IN (?,?) ORDER BY id",new String[]{Integer.toString(file),Integer.toString(caption)})) {
                    assertTrue(row.moveToFirst());assertEquals(url,row.getString(0));assertTrue(row.moveToNext());assertEquals(wire,row.getString(0));
                }
            }
        } finally {context.deleteDatabase(database);}
    }
    @Test public void version32RecoveryOnlyFoldsTheExactOldGeneratedForwardPlaceholder() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="forward-placeholder-upgrade.db";context.deleteDatabase(database);
        String url="aesgcm://files.example.org/file.jpg#"+new String(new char[88]).replace('\0','a');
        int file,caption;long self,peer;
        try {
            try(MonogramStore store=new MonogramStore(context,database)) {
                self=store.peer("bob@example.org");peer=store.peer("carol@example.org");
                org.jxmpp.jid.EntityFullJid sender=org.jxmpp.jid.impl.JidCreate.entityFullFrom("bob@example.org/mobile");
                file=store.save(com.negzero.monogram.xmpp.XmppMessage.sentFile(sender,"carol@example.org","file",com.negzero.monogram.xmpp.FileLink.parse(url),true,null));
                store.forwarded(file,com.negzero.monogram.xmpp.ForwardedContent.of("original@example.org",1700000000,""));
                caption=store.save(com.negzero.monogram.xmpp.XmppMessage.sent(sender,"carol@example.org","caption",com.negzero.monogram.xmpp.ForwardedContent.of("original@example.org",1700000000,"Forwarded attachment").wireBody(),true));
                store.getWritableDatabase().execSQL("DROP TABLE local_media_captions");store.getWritableDatabase().setVersion(32);
            }
            try(MonogramStore store=new MonogramStore(context,database)) {
                assertEquals(33,store.getReadableDatabase().getVersion());assertTrue(store.hiddenMessage(caption));
                java.util.ArrayList<TLRPC.Message> history=store.history(peer,self,0,20);
                assertEquals(1,history.size());assertEquals(file,history.get(0).id);assertEquals("",history.get(0).message);
                store.clear();assertEquals(0,store.legacyCaptionParent(caption));
            }
        } finally {context.deleteDatabase(database);}
    }
    @Test public void uriOnlyForwardMetadataSurvivesReopenAndVersion31UpgradeWithoutRewritingWireBody() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String db="forward-31-upgrade.db";context.deleteDatabase(db);int id;long self;
        String url="aesgcm://files.example.org/file.jpg#"+new String(new char[88]).replace('\0','a');
        try {
            try(MonogramStore store=new MonogramStore(context,db)) {
                self=store.peer("bob@example.org");
                id=store.save(com.negzero.monogram.xmpp.XmppMessage.sentFile(org.jxmpp.jid.impl.JidCreate.entityFullFrom("bob@example.org/mobile"),"carol@example.org","uri-only",com.negzero.monogram.xmpp.FileLink.parse(url),true,null));
                store.getWritableDatabase().execSQL("DROP TABLE local_forwarded_messages");store.getWritableDatabase().setVersion(31);
            }
            try(MonogramStore store=new MonogramStore(context,db)) {
                assertEquals(33,store.getReadableDatabase().getVersion());
                store.forwarded(id,com.negzero.monogram.xmpp.ForwardedContent.of("original@example.org",1700000000,""));
            }
            try(MonogramStore store=new MonogramStore(context,db)) {
                assertEquals("original@example.org",store.getMessage(id,self).fwd_from.from_name);
                try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT body FROM messages WHERE id=?",new String[]{Integer.toString(id)})) {
                    assertTrue(row.moveToFirst());assertEquals(url,row.getString(0));
                }
            }
        } finally {context.deleteDatabase(db);}
    }
}
