package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;

import com.negzero.monogram.xmpp.MessageFixture;
import com.negzero.monogram.xmpp.XmppSession;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

/** Real controller/executors/database with scripted network responses. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class,
        shadows = {MonogramArchiveTest.ArchiveSession.class, MonogramArchiveTest.QuietLog.class})
public class MonogramArchiveTest {
    // This isolated Application has no Telegram logger/native-library startup.
    @Implements(value = org.telegram.messenger.FileLog.class, isInAndroidSdk = false)
    public static class QuietLog {
        @Implementation public static void e(String message) { }
    }
    @Implements(value = XmppSession.class, isInAndroidSdk = false)
    public static class ArchiveSession {
        XMPPTCPConnection connection;
        final List<String> calls = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean block, expireBob, failBob;
        @Implementation public XMPPTCPConnection connection() { return connection; }
        @Implementation public boolean hasArchive() { return true; }
        @Implementation public XmppSession.ArchivePage archiveBefore(String peer, String before, int limit) throws Exception {
            calls.add("before:" + peer + ":" + before);
            entered.countDown();
            if (block && !release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test did not release archive");
            if (failBob && "bob@example.org".equals(peer)) throw new java.io.IOException("Test archive timeout");
            if ("bob@example.org".equals(peer) && before == null) return MessageFixture.page(false, "b1", "b2");
            return MessageFixture.page(true, before == null ? "first" : "b0", before == null ? "last" : "b0");
        }
        @Implementation public XmppSession.ArchivePage archiveAfter(String peer, String after, int limit) throws Exception {
            calls.add("after:" + peer + ":" + after);
            if (expireBob && "bob@example.org".equals(peer)) {
                throw new org.jivesoftware.smack.XMPPException.XMPPErrorException(null,
                        org.jivesoftware.smack.packet.StanzaError.getBuilder(org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found).build());
            }
            return MessageFixture.page(true, null, null);
        }
    }

    private MonogramController controller;
    private MonogramStore store;
    private XmppSession session;
    private ArchiveSession network;
    private ExecutorService archives;
    private long bob, other;
    @Before public void setup() throws Exception {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        controller = ReflectionHelpers.callConstructor(MonogramController.class, ReflectionHelpers.ClassParameter.from(int.class, 0));
        store = ReflectionHelpers.getField(controller, "store");
        ReflectionHelpers.setField(controller, "self", store.peer("alice@example.org"));
        bob = store.peer("bob@example.org"); other = store.peer("other@example.org");
        session = Shadow.newInstanceOf(XmppSession.class);
        network = Shadow.extract(session);
        network.connection = new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain("example.org").setUsernameAndPassword("test", "test").build());
        ReflectionHelpers.setField(network.connection, "authenticated", true);
        ReflectionHelpers.setField(controller, "session", session);
        archives = ReflectionHelpers.getField(controller, "archiveWorker");
    }
    @After public void cleanup() {
        network.release.countDown();
        for (String name : new String[]{"worker", "cacheWorker", "archiveWorker", "fileWorker", "profileWorker", "autoDownloadWorker", "roomWorker", "bookmarkWorker"}) {
            ((ExecutorService) ReflectionHelpers.getField(controller, name)).shutdownNow();
        }
        store.close(); ApplicationLoader.applicationContext.deleteDatabase("monogram-0.db");
    }
    private void start() throws Exception {
        controller.worker.submit(() -> ReflectionHelpers.callInstanceMethod(controller, "startArchiveSync",
                ReflectionHelpers.ClassParameter.from(XmppSession.class, session))).get(5, TimeUnit.SECONDS);
    }
    private void finished() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ReflectionHelpers.getField(controller, "archiveSync") != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNull("Archive run did not finish", ReflectionHelpers.getField(controller, "archiveSync"));
        archives.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    @Test public void pagesRotateAcrossChatsAndDiscoveryThenCatchUpIndividually() throws Exception {
        start(); finished();
        assertEquals(java.util.Arrays.asList("before:bob@example.org:null", "before:other@example.org:null",
                "before:null:null", "before:bob@example.org:b1"), network.calls);
        assertTrue(store.archiveComplete(bob)); assertTrue(store.archiveComplete(other));
        assertEquals("b2", store.archiveCursor(bob, false)); assertEquals("b0", store.archiveCursor(bob, true));
        network.calls.clear(); start(); finished();
        assertEquals(java.util.Arrays.asList("after:bob@example.org:b2", "after:other@example.org:last", "after:null:last"), network.calls);
    }
    @Test public void stalledArchiveLeavesNetworkRequestsResponsiveAndAccountChangesDiscardResults() throws Exception {
        network.block = true; start(); assertTrue(network.entered.await(5, TimeUnit.SECONDS));
        CountDownLatch response = new CountDownLatch(1);
        controller.request(new TLRPC.TL_updates_getState(), (result, error) -> {
            if (result instanceof TLRPC.TL_updates_state && error == null) response.countDown();
        });
        assertTrue("Network request blocked behind MAM", response.await(5, TimeUnit.SECONDS));
        ReflectionHelpers.setField(controller, "accountEpoch", 1L);
        network.release.countDown(); archives.submit(() -> {}).get(5, TimeUnit.SECONDS);
        controller.worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertNull("Stale archive page wrote into a changed account", store.archiveCursor(bob, false));
    }
    @Test public void expiredCursorResetsOnlyItsChatAndRetriesAfterOtherChats() throws Exception {
        store.saveArchive(bob, MessageFixture.page(true, "expired-first", "expired-last"), true, null);
        network.expireBob = true; start(); finished();
        assertEquals(java.util.Arrays.asList("after:bob@example.org:expired-last", "before:other@example.org:null",
                "before:null:null", "before:bob@example.org:null", "before:bob@example.org:b1"), network.calls);
        assertEquals("last", store.archiveCursor(other, false)); assertEquals("b2", store.archiveCursor(bob, false));
    }
    @Test public void oneChatFailureDoesNotStopDiscoveryOrOtherChats() throws Exception {
        network.failBob = true; start(); finished();
        assertEquals(java.util.Arrays.asList("before:bob@example.org:null", "before:other@example.org:null", "before:null:null"), network.calls);
        assertNull(store.archiveCursor(bob, false)); assertTrue(store.archiveComplete(other)); assertTrue(store.archiveComplete());
    }
    @Test public void trustApprovalRescansAfterTheInFlightPageWithoutCancellingIt() throws Exception {
        network.block=true; start(); assertTrue(network.entered.await(5,TimeUnit.SECONDS));
        controller.retryEncryptedHistory(bob,false); controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);
        network.release.countDown(); finished();
        assertEquals(2,java.util.Collections.frequency(network.calls,"before:bob@example.org:null"));
        assertTrue(store.archiveComplete(bob)); assertTrue(store.archiveComplete(other));
    }
    @Test public void serverBookmarksAppearAsNativeDialogsWithoutLocalMessages() throws Exception {
        String room="already-joined@conference.example.org";
        com.negzero.monogram.xmpp.XmppBookmarks.Bookmark bookmark=com.negzero.monogram.xmpp.XmppBookmarks.Bookmark.parse(room,"<conference xmlns='urn:xmpp:bookmarks:1' name='My other-client room' autojoin='true'><nick>Alice</nick></conference>");
        store.importBookmark(bookmark,"private-vault-payload","Alice");
        TLRPC.TL_messages_dialogs result=ReflectionHelpers.callInstanceMethod(controller,"dialogs");
        long dialog=store.knownRoom(room); assertTrue(dialog<0);
        assertTrue(result.dialogs.stream().anyMatch(value -> value.id==dialog && value.peer.chat_id==-dialog));
        assertTrue(result.chats.stream().anyMatch(value -> value.id==-dialog && "My other-client room".equals(value.title)));
        assertTrue(store.history(dialog,1,0,100).isEmpty());
    }
}
