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
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class)
public class MonogramEditingTest {
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
        for (String name : new String[]{"worker", "cacheWorker", "archiveWorker", "fileWorker", "profileWorker", "autoDownloadWorker"}) {
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

    @Test public void opensOwnOldTextEditorOffline() throws Exception {
        int id = message("own", "Before", true, true, null);
        TLRPC.Message nativeMessage = store.getMessage(id, self);
        assertTrue(MessageObject.canEditMessage(0, nativeMessage, null, false));
        assertTrue("XMPP corrections have no Telegram edit expiry", MessageObject.canEditMessageAnytime(0, nativeMessage, null));
        TLRPC.TL_messages_messageEditData data = (TLRPC.TL_messages_messageEditData) handle.invoke(controller, query(id, peer));
        assertFalse(data.caption);
        assertTrue("Do not offer unsupported Telegram read-date queries", store.fullUser(peer, self).read_dates_private);
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
