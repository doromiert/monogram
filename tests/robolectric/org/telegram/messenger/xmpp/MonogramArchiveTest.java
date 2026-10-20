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
        shadows = {MonogramArchiveTest.ArchiveSession.class, MonogramArchiveTest.QuietLog.class,MonogramArchiveTest.Profiles.class})
public class MonogramArchiveTest {
    // This isolated Application has no Telegram logger/native-library startup.
    @Implements(value = org.telegram.messenger.FileLog.class, isInAndroidSdk = false)
    public static class QuietLog {
        @Implementation public static void e(String message) { }
    }
    static final List<String> stages=new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.Set<String> blockedProfiles=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static CountDownLatch profileEntered,profileRelease;
    @Implements(value=com.negzero.monogram.xmpp.XmppProfiles.class,isInAndroidSdk=false)
    public static class Profiles {
        @Implementation public com.negzero.monogram.xmpp.XmppProfile load(String address) throws Exception {
            stages.add("profile:"+address);
            if(blockedProfiles.contains(address)) { profileEntered.countDown(); if(!profileRelease.await(10,TimeUnit.SECONDS)) throw new java.io.IOException("Profile test timed out"); }
            return new com.negzero.monogram.xmpp.XmppProfile("<vCard xmlns='vcard-temp'/>",null,new byte[0]);
        }
        @Implementation public void useOwnProfile(com.negzero.monogram.xmpp.XmppProfile profile) {}
    }
    @Implements(value = XmppSession.class, isInAndroidSdk = false)
    public static class ArchiveSession {
        XMPPTCPConnection connection;
        final List<String> calls = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean block,expireBob,failBob,windowMode,unsupported;
        String blockPeer;
        com.negzero.monogram.xmpp.XmppMessage lateMessage;
        @Implementation public XMPPTCPConnection connection() { return connection; }
        @Implementation public boolean hasArchive() { return !unsupported; }
        @Implementation public java.util.Set<org.jivesoftware.smackx.omemo.internal.OmemoDevice> devices(String jid) { stages.add("fingerprint:"+jid); return java.util.Collections.emptySet(); }
        @Implementation public XmppSession.ArchivePage archiveBefore(String peer, String before, int limit) throws Exception {
            calls.add("before:" + peer + ":" + before);
            assertEquals(50,limit); stages.add("messages:"+peer);
            if(expireBob && "bob@example.org".equals(peer) && "b1".equals(before)) {
                expireBob=false; throw new org.jivesoftware.smack.XMPPException.XMPPErrorException(null,org.jivesoftware.smack.packet.StanzaError.getBuilder(org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found).build());
            }
            if(windowMode && "bob@example.org".equals(peer)) {
                int end=before==null ? 400 : Integer.parseInt(before.substring(1)); int first=Math.max(0,end-50);
                List<com.negzero.monogram.xmpp.XmppMessage> messages=new ArrayList<>();
                for(int i=first;i<end;i++) messages.add(MessageFixture.replyMessage("wire-"+i,"origin-"+i,"m"+i,"Message "+i,true,false,100000L+i*1000,"phone",false,null));
                return MessageFixture.page(first==0,"m"+first,"m"+(end-1),messages.toArray(new com.negzero.monogram.xmpp.XmppMessage[0]));
            }
            if(blockPeer==null || blockPeer.equals(peer)) {
                entered.countDown();
                if (block && !release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test did not release archive");
            }
            if (failBob && "bob@example.org".equals(peer)) throw new java.io.IOException("Test archive timeout");
            if ("bob@example.org".equals(peer) && before == null) return lateMessage==null ? MessageFixture.page(false, "b1", "b2") : MessageFixture.page(true,"b1","b2",lateMessage);
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
        stages.clear(); blockedProfiles.clear(); profileEntered=new CountDownLatch(1); profileRelease=new CountDownLatch(1);
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        controller = ReflectionHelpers.callConstructor(MonogramController.class, ReflectionHelpers.ClassParameter.from(int.class, 0));
        store = ReflectionHelpers.getField(controller, "store");
        ((android.content.SharedPreferences)ReflectionHelpers.getField(controller,"preferences")).edit().putBoolean("private-read-enabled-alice@example.org",false).commit();
        ReflectionHelpers.setField(controller, "self", store.peer("alice@example.org"));
        bob = store.peer("bob@example.org"); other = store.peer("other@example.org");
        session = Shadow.newInstanceOf(XmppSession.class);
        network = Shadow.extract(session);
        ReflectionHelpers.setField(session,"fingerprints",new com.negzero.monogram.xmpp.XmppFingerprints((address,modern) -> { stages.add("fingerprint:"+address); return java.util.Collections.emptyMap(); }));
        ReflectionHelpers.setField(session,"profiles",Shadow.newInstanceOf(com.negzero.monogram.xmpp.XmppProfiles.class));
        network.connection = new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain("example.org").setUsernameAndPassword("test", "test").build());
        ReflectionHelpers.setField(network.connection, "authenticated", true);
        ReflectionHelpers.setField(controller, "session", session);
        archives = ReflectionHelpers.getField(controller, "archiveWorker");
    }
    @After public void cleanup() {
        profileRelease.countDown(); session.fingerprints.close();
        network.release.countDown();
        for (String name : new String[]{"worker", "sendWorker", "cacheWorker", "archiveWorker", "fileWorker", "profileWorker", "autoDownloadWorker", "roomWorker", "bookmarkWorker","readSyncWorker", "fingerprintWorker"}) {
            ((ExecutorService) ReflectionHelpers.getField(controller, name)).shutdownNow();
        }
        store.close(); ApplicationLoader.applicationContext.deleteDatabase("monogram-0.db");
    }
    private void start() throws Exception {
        controller.worker.submit(() -> ReflectionHelpers.callInstanceMethod(controller, "startArchiveSync",
                ReflectionHelpers.ClassParameter.from(XmppSession.class, session))).get(5, TimeUnit.SECONDS);
    }
    private void finished() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10); boolean done=false;
        while(System.nanoTime()<deadline) {
            done=controller.worker.submit(() -> {
                Object sync=ReflectionHelpers.getField(controller,"archiveSync");
                return sync!=null && ((java.util.Set<?>)ReflectionHelpers.getField(sync,"pending")).isEmpty()
                    && ((java.util.Set<?>)ReflectionHelpers.getField(sync,"preparing")).isEmpty() && !(Boolean)ReflectionHelpers.getField(sync,"running");
            }).get(5,TimeUnit.SECONDS);
            if(done) break; Thread.sleep(10);
        }
        assertTrue("Archive window did not finish",done); archives.submit(() -> {}).get(5,TimeUnit.SECONDS);
    }
    @Test public void startsAtNewestAfterMetadataAndFingerprintsInsteadOfReusingOldCatchupCursor() throws Exception {
        start(); finished();
        assertEquals(4,network.calls.size()); assertTrue(network.calls.contains("before:bob@example.org:b1"));
        assertTrue(store.archiveComplete(bob)); assertTrue(store.archiveComplete(other));
        for(String peer:new String[]{"bob@example.org","other@example.org"}) {
            int profile=stages.indexOf("profile:"+peer),fingerprint=stages.indexOf("fingerprint:"+peer),message=stages.indexOf("messages:"+peer);
            assertTrue(profile>=0 && profile<fingerprint); assertTrue(fingerprint<message);
        }
        network.calls.clear(); start(); finished();
        assertTrue(network.calls.contains("before:bob@example.org:null")); assertTrue(network.calls.stream().noneMatch(call -> call.startsWith("after:")));
    }
    @Test public void threeFiftyMessageBatchesAdvanceOneBatchWhenScrollingIntoTheNext() throws Exception {
        network.windowMode=true; start(); finished();
        assertEquals(3,network.calls.stream().filter(call -> call.startsWith("before:bob@example.org:")).count());
        assertFalse(store.archiveComplete(bob));
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM messages WHERE peer=?",new String[]{Long.toString(bob)})) { c.moveToFirst(); assertEquals(150,c.getInt(0)); }
        int visible=store.history(bob,1,0,100).get(50).id; controller.historyVisible(bob,visible);
        ((ExecutorService)ReflectionHelpers.getField(controller,"cacheWorker")).submit(() -> {}).get(5,TimeUnit.SECONDS);
        controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS); finished();
        assertEquals(4,network.calls.stream().filter(call -> call.startsWith("before:bob@example.org:")).count());
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM messages WHERE peer=?",new String[]{Long.toString(bob)})) { c.moveToFirst(); assertEquals(200,c.getInt(0)); }
    }
    @Test public void explicitSearchExpansionLoadsExactlyOneMorePageAndKeepsItUnread() throws Exception {
        network.windowMode=true;start();finished();
        android.content.SharedPreferences preferences=ReflectionHelpers.getField(controller,"preferences");preferences.edit().putString("jid","alice@example.org").commit();
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>("not completed");
        CountDownLatch callback=new CountDownLatch(1);
        controller.loadOlderForSearch(bob,value -> { error.set(value);callback.countDown(); });
        finished();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(callback.await(5,TimeUnit.SECONDS));assertNull(error.get());
        assertEquals(4,network.calls.stream().filter(call -> call.startsWith("before:bob@example.org:")).count());
        try(android.database.Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM messages WHERE peer=?",new String[]{Long.toString(bob)})) { assertTrue(rows.moveToFirst());assertEquals(200,rows.getInt(0)); }
        try(android.database.Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM local_read_messages",null)) { assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0)); }
    }
    @Test public void searchExpansionReportsUnsupportedEndAndDisconnectedStateWithoutFetching() throws Exception {
        start();finished();android.content.SharedPreferences preferences=ReflectionHelpers.getField(controller,"preferences");preferences.edit().putString("jid","alice@example.org").commit();
        int calls=network.calls.size();java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        controller.loadOlderForSearch(bob,error::set);controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(error.get());assertEquals(calls,network.calls.size());
        network.unsupported=true;start();finished();error.set(null);
        controller.loadOlderForSearch(bob,error::set);controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(error.get());assertTrue(error.get().contains("server"));assertEquals(calls,network.calls.size());
        ReflectionHelpers.setField(network.connection,"authenticated",false);error.set(null);
        controller.loadOlderForSearch(bob,error::set);controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(error.get());assertEquals(calls,network.calls.size());
    }
    @Test public void reconnectCancelsSearchWaiterExactlyOnceAndStaleCompletionCannotReuseIt() throws Exception {
        start();finished();Object oldSync=ReflectionHelpers.getField(controller,"archiveSync");
        Object task=((java.util.Map<?,?>)ReflectionHelpers.getField(oldSync,"windows")).get(bob);
        java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();
        ReflectionHelpers.setField(task,"searchCompletion",(java.util.function.Consumer<String>)error -> { assertNotNull(error);callbacks.incrementAndGet(); });
        start();finished();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,callbacks.get());
        java.lang.reflect.Method finish=MonogramController.class.getDeclaredMethod("finishSearchLoad",task.getClass(),String.class);finish.setAccessible(true);finish.invoke(controller,task,null);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,callbacks.get());
    }
    @Test public void cachedChatCatchesUpWhileItsRefreshAndAnotherChatsMetadataAreBlocked() throws Exception {
        long own=ReflectionHelpers.getField(controller,"self");
        com.negzero.monogram.xmpp.XmppProfile cached=new com.negzero.monogram.xmpp.XmppProfile("<vCard xmlns='vcard-temp'><NICKNAME>Cached</NICKNAME></vCard>",null,new byte[0]);
        store.profile(own,cached); store.profile(bob,cached);
        blockedProfiles.add("bob@example.org"); blockedProfiles.add("other@example.org");
        network.blockPeer="bob@example.org";
        start(); assertTrue(profileEntered.await(5,TimeUnit.SECONDS));
        assertTrue("A cached chat waited for unrelated metadata or its background refresh",network.entered.await(5,TimeUnit.SECONDS));
        assertFalse(stages.contains("messages:other@example.org"));
        profileRelease.countDown(); finished();
        assertTrue(stages.contains("messages:other@example.org"));
    }
    @Test public void decryptedCallbacksSurviveReconnectButCannotCrossAccountChanges() throws Exception {
        android.content.SharedPreferences preferences=ReflectionHelpers.getField(controller,"preferences");
        preferences.edit().putString("jid","alice@example.org").commit();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        controller.worker.execute(() -> { entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();
        com.negzero.monogram.xmpp.XmppMessage message=MessageFixture.message("buffered",null,null,"Retain decrypted text",true,false,100000);
        ReflectionHelpers.callInstanceMethod(controller,"received",ReflectionHelpers.ClassParameter.from(long.class,0L),ReflectionHelpers.ClassParameter.from(String.class,"alice@example.org"),ReflectionHelpers.ClassParameter.from(com.negzero.monogram.xmpp.XmppMessage.class,message),ReflectionHelpers.ClassParameter.from(Runnable.class,accepted::incrementAndGet));
        ReflectionHelpers.setField(controller,"sessionEpoch",99L); ReflectionHelpers.setField(controller,"session",null);
        release.countDown(); controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);
        assertEquals("Retain decrypted text",store.history(bob,1,0,50).get(0).message); assertEquals(1,accepted.get());
        ReflectionHelpers.setField(controller,"accountEpoch",1L);
        message=MessageFixture.message("stale-account",null,null,"Must not import",true,false,100001);
        ReflectionHelpers.callInstanceMethod(controller,"received",ReflectionHelpers.ClassParameter.from(long.class,0L),ReflectionHelpers.ClassParameter.from(String.class,"alice@example.org"),ReflectionHelpers.ClassParameter.from(com.negzero.monogram.xmpp.XmppMessage.class,message),ReflectionHelpers.ClassParameter.from(Runnable.class,accepted::incrementAndGet));
        controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);
        assertEquals(1,store.history(bob,1,0,50).size()); assertEquals(1,accepted.get());
    }
    @Test public void lateDecryptedHistorySurvivesReconnectWithoutWritingOldCursors() throws Exception {
        android.content.SharedPreferences preferences=ReflectionHelpers.getField(controller,"preferences");
        preferences.edit().putString("jid","alice@example.org").commit();
        network.block=true; network.blockPeer="bob@example.org";
        network.lateMessage=MessageFixture.message("late-decoded",null,null,"Preserve decoded archive text",true,false,100000);
        start(); assertTrue(network.entered.await(5,TimeUnit.SECONDS));
        controller.worker.submit(() -> ReflectionHelpers.setField(controller,"archiveSync",null)).get(5,TimeUnit.SECONDS);
        network.release.countDown(); archives.submit(() -> {}).get(5,TimeUnit.SECONDS);
        controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);
        assertEquals("Preserve decoded archive text",store.history(bob,1,0,50).get(0).message);
        assertNull(store.archiveCursor(bob,false));
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
        assertEquals(2,java.util.Collections.frequency(network.calls,"before:bob@example.org:null"));
        assertEquals(1,java.util.Collections.frequency(network.calls,"before:other@example.org:null")); assertTrue(store.archiveComplete(bob));
    }
    @Test public void oneChatFailureDoesNotStopDiscoveryOrOtherChats() throws Exception {
        network.failBob = true; start(); finished();
        assertEquals(3,network.calls.size()); assertTrue(network.calls.contains("before:other@example.org:null")); assertTrue(network.calls.contains("before:null:null"));
        assertNull(store.archiveCursor(bob, false)); assertTrue(store.archiveComplete(other)); assertTrue(store.archiveComplete());
    }
    @Test public void trustApprovalRescansAfterTheInFlightPageWithoutCancellingIt() throws Exception {
        network.block=true; network.blockPeer="bob@example.org"; start(); assertTrue(network.entered.await(5,TimeUnit.SECONDS));
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
