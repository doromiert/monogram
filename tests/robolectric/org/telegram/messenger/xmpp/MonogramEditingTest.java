package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;

import com.negzero.monogram.xmpp.MessageFixture;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Exercise the native editor's preflight request, not only correction storage. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class,shadows={MonogramEditingTest.PendingSession.class})
public class MonogramEditingTest {
    @org.robolectric.annotation.Implements(value=com.negzero.monogram.xmpp.XmppSession.class,isInAndroidSdk=false)
    public static class PendingSession {
        org.jivesoftware.smack.tcp.XMPPTCPConnection connection;
        @org.robolectric.annotation.Implementation public org.jivesoftware.smack.tcp.XMPPTCPConnection connection() { return connection; }
    }
    private MonogramController controller;
    private MonogramStore store;
    private Method handle;
    private long self, peer;

    @Before public void setup() throws Exception {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        controller = ReflectionHelpers.callConstructor(MonogramController.class,
                ReflectionHelpers.ClassParameter.from(int.class, 0));
        store = ReflectionHelpers.getField(controller, "store");
        self = store.peer("alice@example.org"); peer = store.peer("bob@example.org");
        ReflectionHelpers.setField(controller, "self", self);
        handle = MonogramController.class.getDeclaredMethod("handle", TLObject.class);
        handle.setAccessible(true);
    }

    @After public void cleanup() throws Exception {
        for (String name : new String[]{"worker", "sendWorker", "cacheWorker", "archiveWorker", "fileWorker", "profileWorker", "autoDownloadWorker"}) {
            ((ExecutorService) ReflectionHelpers.getField(controller, name)).shutdownNow();
        }
        store.close();
        ApplicationLoader.applicationContext.deleteDatabase("monogram-0.db");
    }

    private int message(String id, String body, boolean outgoing, boolean encrypted, String target) throws Exception {
        return store.save(MessageFixture.correction(id, null, null, body, body != null, outgoing,
                100000, "test", encrypted, null, target));
    }

    private TLRPC.TL_messages_getMessageEditData query(int id, long chat) {
        TLRPC.TL_messages_getMessageEditData query = new TLRPC.TL_messages_getMessageEditData();
        query.id = id; query.peer = new TLRPC.TL_inputPeerUser(); query.peer.user_id = chat;
        return query;
    }

    private void rejected(TLRPC.TL_messages_getMessageEditData query) throws Exception {
        try { handle.invoke(controller, query); fail("Invalid edit preflight accepted"); }
        catch (InvocationTargetException error) { assertTrue(error.getCause() instanceof IllegalArgumentException); }
    }
    @Test public void modernModeIsExplicitAndSurvivesTemporarilyDisablingEncryption() {
        assertEquals(com.negzero.monogram.xmpp.XmppMessage.Encryption.LEGACY_OMEMO,controller.encryption(peer));
        controller.setEncryption(peer,com.negzero.monogram.xmpp.XmppMessage.Encryption.OMEMO2);
        assertTrue(controller.modernEncryption(peer));
        controller.setEncrypted(peer,false);
        assertEquals(com.negzero.monogram.xmpp.XmppMessage.Encryption.PLAIN,controller.encryption(peer));
        assertTrue(controller.modernEncryption(peer));
        controller.setEncrypted(peer,true);
        assertEquals(com.negzero.monogram.xmpp.XmppMessage.Encryption.OMEMO2,controller.encryption(peer));
        controller.setEncryption(peer,com.negzero.monogram.xmpp.XmppMessage.Encryption.LEGACY_OMEMO);
        assertFalse(controller.modernEncryption(peer));
        assertEquals(com.negzero.monogram.xmpp.XmppMessage.Encryption.LEGACY_OMEMO,controller.encryption(peer));
    }

    @Test public void opensOwnOldTextEditorOffline() throws Exception {
        int id = message("own", "Before", true, true, null);
        TLRPC.Message nativeMessage = store.getMessage(id, self);
        assertTrue(MessageObject.canEditMessage(0, nativeMessage, null, false));
        assertTrue("XMPP corrections have no Telegram edit expiry", MessageObject.canEditMessageAnytime(0, nativeMessage, null));
        TLRPC.TL_messages_messageEditData data = (TLRPC.TL_messages_messageEditData) handle.invoke(controller, query(id, peer));
        assertFalse(data.caption);
        assertTrue("Do not offer unsupported Telegram read-date queries", store.fullUser(peer, self).read_dates_private);
    }

    @Test public void offlineSendsWaitForReconnectAndCanBeCancelled() throws Exception {
        java.util.concurrent.CountDownLatch completed=new java.util.concurrent.CountDownLatch(1);
        TLRPC.TL_messages_sendMessage send=new TLRPC.TL_messages_sendMessage(); send.peer=new TLRPC.TL_inputPeerUser(); send.peer.user_id=peer; send.message="queued";
        TLRPC.TL_message saved=new TLRPC.TL_message(); saved.dialog_id=peer;
        controller.prepareOutgoingMessage(saved); controller.prepareOutgoingRequest(send,saved);
        controller.request(501,send,(response,error) -> { assertEquals("MONOGRAM_SENDING_MODE_CHANGED",error.text); completed.countDown(); });
        ExecutorService sends=ReflectionHelpers.getField(controller,"sendWorker"); sends.submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1,controller.queuedSendCount()); assertEquals(1,completed.getCount());
        controller.request(502,send,(response,error) -> fail("Cancelled queued send completed"));
        sends.submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS); controller.cancelRequest(502); assertEquals(1,controller.queuedSendCount());
        // Reconnection must not silently downgrade a message composed with encryption enabled.
        controller.setEncrypted(peer,false); controller.prepareOutgoingMessage(saved);
        assertEquals("LEGACY_OMEMO",saved.params.get("monogram-encryption"));
        org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration config=org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build();
        org.jivesoftware.smack.tcp.XMPPTCPConnection connection=new org.jivesoftware.smack.tcp.XMPPTCPConnection(config);
        ReflectionHelpers.setField(connection,"authenticated",true);
        com.negzero.monogram.xmpp.XmppSession session=org.robolectric.shadow.api.Shadow.newInstanceOf(com.negzero.monogram.xmpp.XmppSession.class);
        PendingSession network=org.robolectric.shadow.api.Shadow.extract(session); network.connection=connection;
        ReflectionHelpers.setField(controller,"session",session);
        ReflectionHelpers.callInstanceMethod(controller,"flushPendingSends");
        assertTrue("Reconnect did not drain queued sends",completed.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,controller.queuedSendCount());
        ReflectionHelpers.setField(connection,"authenticated",false);
        controller.request(503,send,(response,error) -> fail("Old account queue completed after account change")); sends.submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS);
        ReflectionHelpers.setField(controller,"accountEpoch",1L); ReflectionHelpers.callInstanceMethod(controller,"flushPendingSends");
        sends.submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS); sends.submit(() -> {}).get(5,java.util.concurrent.TimeUnit.SECONDS); assertEquals(0,controller.queuedSendCount());
    }
    @Test public void roomPreflightUsesTheNativeEditorWithoutTelegramExpiry() throws Exception {
        String room="friends@conference.example.org"; long dialog=store.room(room,"Alice");
        int id=store.saveRoom(MessageFixture.roomText(room,"Alice","own-room","server-own-room","Before","alice@example.org","own",100000,true,null,null),self);
        TLRPC.Message nativeMessage=store.getMessage(id,self);
        assertTrue(MessageObject.canEditMessage(0,nativeMessage,store.roomChat(dialog),false));
        assertTrue(MessageObject.canEditMessageAnytime(0,nativeMessage,store.roomChat(dialog)));
        TLRPC.TL_messages_getMessageEditData request=query(id,peer); request.peer=new TLRPC.TL_inputPeerChat(); request.peer.chat_id=-dialog;
        assertTrue(handle.invoke(controller,request) instanceof TLRPC.TL_messages_messageEditData);
        request.peer.chat_id=-store.room("other@conference.example.org","Alice"); rejected(request);
    }
    @Test public void preflightDoesNotWaitForNetworkWorker() throws Exception {
        int id = message("offline", "Edit without a network round trip", true, false, null);
        java.util.concurrent.CountDownLatch releaseNetwork = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        controller.worker.execute(() -> {
            try { releaseNetwork.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        java.util.concurrent.atomic.AtomicReference<TLObject> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<TLRPC.TL_error> failure = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            controller.request(query(id, peer), (response, error) -> { result.set(response); failure.set(error); completed.countDown(); });
            assertTrue("Preflight blocked behind network work", completed.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(result.get() instanceof TLRPC.TL_messages_messageEditData);
        } finally { releaseNetwork.countDown(); }
    }

    @Test public void archiveCursorsAreIndependentAndTransactional() throws Exception {
        int original = message("saved", "Keep cached text", true, true, null);
        long other = store.peer("other@example.org");
        store.saveArchive(peer, MessageFixture.page(true, "bob-first", "bob-last"), true, null);
        store.saveArchive(other, MessageFixture.page(false, "other-first", "other-last"), true, null);
        store.saveArchive(MessageFixture.page(true, "global-first", "global-last"), true);
        assertEquals("bob-last", store.archiveCursor(peer, false));
        assertEquals("other-last", store.archiveCursor(other, false));
        assertEquals("global-last", store.archiveCursor(false));
        assertTrue(store.archiveComplete(peer)); assertFalse(store.archiveComplete(other));
        try {
            store.saveArchive(other, MessageFixture.page(MessageFixture.message("wrong-chat", null, "wrong-archive",
                    "Wrong chat", true, false, 200000)), false, null);
            fail("Cross-chat archive response accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals("other-last", store.archiveCursor(other, false));
        assertEquals("bob-last", store.archiveCursor(peer, false));
        store.resetArchiveCursor(other);
        assertNull(store.archiveCursor(other, false)); assertFalse(store.archiveComplete(other));
        assertEquals("bob-last", store.archiveCursor(peer, false));
        assertEquals("global-last", store.archiveCursor(false));
        assertEquals("Keep cached text", store.getMessage(original, self).message);
        MonogramStore reopened = new MonogramStore(0);
        try { assertEquals("bob-last", reopened.archiveCursor(peer, false)); }
        finally { reopened.close(); }
    }

    @Test public void imageReceiverUsesIntegrityPathBeforeDownloadAndFailsClosedOnBadMetadata() throws Exception {
        java.io.File source = java.io.File.createTempFile("image-source-", ".png", ApplicationLoader.applicationContext.getCacheDir());
        java.nio.file.Files.write(source.toPath(), new byte[]{1,2,3});
        com.negzero.monogram.xmpp.FileShare sharing = com.negzero.monogram.xmpp.FileShare.describe(
                com.negzero.monogram.xmpp.FileLink.parse("https://example.org/photo.png"), source, "photo.png", "image/png");
        int id = store.save(com.negzero.monogram.xmpp.XmppMessage.sentFile(org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/test"),
                "bob@example.org", "photo", sharing, "Caption", null));
        java.io.File expected = store.attachmentFile(sharing); assertFalse(expected.isFile());
        TLRPC.TL_photoSize_layer127 size = new TLRPC.TL_photoSize_layer127();
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        size.location.volume_id = MonogramAttachments.PHOTO_VOLUME; size.location.local_id = id;
        org.telegram.messenger.ImageLocation location = new org.telegram.messenger.ImageLocation();
        location.photoSize = size; location.location = (TLRPC.TL_fileLocationToBeDeprecated) size.location;
        MonogramController[] instances = ReflectionHelpers.getStaticField(MonogramController.class, "instances");
        instances[0] = controller;
        try {
            org.telegram.messenger.ImageReceiver receiver = new org.telegram.messenger.ImageReceiver();
            ReflectionHelpers.setField(receiver, "currentAccount", 0);
            receiver.setAllowLoadingOnAttachedOnly(true);
            receiver.setImage(null, null, location, null, location, null, null, 3, "png", null, 0);
            Object backup = ReflectionHelpers.getField(receiver, "setImageBackup");
            org.telegram.messenger.ImageLocation image = ReflectionHelpers.getField(backup, "imageLocation");
            assertEquals(expected.getAbsolutePath(), image.path);
            assertNull(ReflectionHelpers.getField(backup, "thumbLocation"));
            store.getWritableDatabase().execSQL("UPDATE attachments SET sharing='<broken/>' WHERE message=?", new Object[]{id});
            receiver.setImage(null, null, location, null, location, null, null, 3, "png", null, 0);
            assertNull(ReflectionHelpers.getField(backup, "imageLocation"));
            assertNull(ReflectionHelpers.getField(backup, "thumbLocation"));
        } finally { instances[0] = null; source.delete(); }
    }

    @Test public void refusesForeignMissingUnreadableAndMediaTargets() throws Exception {
        int own = message("own", "Before", true, false, null);
        rejected(query(own, store.peer("other@example.org")));
        rejected(query(Integer.MAX_VALUE, peer));
        rejected(query(message("incoming", "Theirs", false, true, null), peer));
        rejected(query(message("unreadable", null, true, true, null), peer));
        int file = message("file", "https://example.org/picture.jpg", true, false, null);
        store.attach(file, "https://example.org/picture.jpg", new java.io.File("/missing-test-image"), new TLRPC.TL_messageMediaEmpty());
        rejected(query(file, peer));
        rejected(query(message("correction", "New text", true, true, "own"), peer));
        TLRPC.TL_messages_getMessageEditData noPeer = query(own, peer); noPeer.peer = null;
        rejected(noPeer);
    }
}
