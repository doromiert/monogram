package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppFingerprintsTest {
    Map<OmemoDevice,OmemoFingerprint> identity(String jid) throws Exception {
        return Collections.singletonMap(new OmemoDevice(JidCreate.entityBareFrom(jid),42),new OmemoFingerprint(String.join("",Collections.nCopies(64,"a"))));
    }
    @Test void partialAndFailedRefreshesKeepRowsUntilSuccessfulRemovalIsProven() throws Exception {
        Map<OmemoDevice,OmemoFingerprint> shown=new LinkedHashMap<>();shown.putAll(identity("bob@example.org"));shown.putAll(identity("carol@example.org"));
        Set<String> requested=new LinkedHashSet<>(Arrays.asList("bob@example.org","carol@example.org"));
        XmppFingerprints.mergeDisplay(shown,requested,new XmppFingerprints.Snapshot(Collections.emptyMap(),0,2,0,false,Collections.emptyMap()));assertEquals(2,shown.size());
        XmppFingerprints.mergeDisplay(shown,requested,new XmppFingerprints.Snapshot(Collections.emptyMap(),2,2,1,true,Collections.singletonMap("carol@example.org","Timed out")));
        assertEquals(identity("carol@example.org"),shown);
        XmppFingerprints.mergeDisplay(shown,requested,new XmppFingerprints.Snapshot(Collections.emptyMap(),2,2,0,true,Collections.emptyMap()));assertTrue(shown.isEmpty());
    }
    @Test void publishedDeviceChangeInvalidatesSuccessfulDisplayCache() throws Exception {
        AtomicInteger calls=new AtomicInteger();BlockingQueue<XmppFingerprints.Snapshot> updates=new LinkedBlockingQueue<>();
        try(XmppFingerprints loader=new XmppFingerprints((jid,modern)->{calls.incrementAndGet();return identity(jid);},2000)) {
            for(int round=0;round<2;round++) {
                if(round==1)loader.invalidate("bob@example.org");
                loader.load(Collections.singleton("bob@example.org"),false,false,updates::add);
                XmppFingerprints.Snapshot result;do{result=updates.poll(3,TimeUnit.SECONDS);assertNotNull(result);}while(!result.complete);
                assertEquals(1,result.identities.size());assertEquals(round+1,calls.get());
            }
        }
    }
    @Test void deadlineKeepsSuccessfulAccountsAndSharedCacheAvoidsRepeatedFetches() throws Exception {
        AtomicInteger fetched=new AtomicInteger(); CountDownLatch stalled=new CountDownLatch(1),started=new CountDownLatch(1);
        BlockingQueue<XmppFingerprints.Snapshot> updates=new LinkedBlockingQueue<>();
        try(XmppFingerprints loader=new XmppFingerprints((jid,modern) -> {
            fetched.incrementAndGet();
            if(jid.startsWith("slow")) { started.countDown(); stalled.await(); }
            return identity(jid);
        },500)) {
            loader.load(Arrays.asList("fast@example.org","slow@example.org"),false,false,updates::add);
            assertTrue(started.await(2,TimeUnit.SECONDS)); XmppFingerprints.Snapshot result;
            do { result=updates.poll(2,TimeUnit.SECONDS); assertNotNull(result); } while(!result.complete);
            assertEquals(1,result.identities.size()); assertEquals(1,result.failed);
            updates.clear(); loader.load(Collections.singleton("fast@example.org"),false,false,updates::add);
            do { result=updates.poll(2,TimeUnit.SECONDS); assertNotNull(result); } while(!result.complete);
            assertEquals(2,fetched.get()); assertEquals(1,result.identities.size()); assertEquals(0,result.failed);
            stalled.countDown();
        } finally { stalled.countDown(); }
    }
    @Test void failedDeviceKeepsOtherFingerprintsAndAnExplicitRefreshRetries() throws Exception {
        AtomicInteger fetched=new AtomicInteger(); BlockingQueue<XmppFingerprints.Snapshot> updates=new LinkedBlockingQueue<>();
        try(XmppFingerprints loader=new XmppFingerprints((jid,modern) -> {
            if(fetched.incrementAndGet()==1) throw new XmppFingerprints.Partial(identity(jid)); return identity(jid);
        })) {
            loader.load(Collections.singleton("alice@example.org"),false,false,updates::add);
            XmppFingerprints.Snapshot result;
            do { result=updates.poll(2,TimeUnit.SECONDS); assertNotNull(result); } while(!result.complete);
            assertEquals(1,result.failed); assertEquals(1,result.identities.size());
            updates.clear(); loader.load(Collections.singleton("alice@example.org"),false,true,updates::add);
            do { result=updates.poll(2,TimeUnit.SECONDS); assertNotNull(result); } while(!result.complete);
            assertEquals(2,fetched.get()); assertEquals(0,result.failed); assertEquals(1,result.identities.size());
        }
    }
    @Test void partialDevicesAppearWhileSharedLookupWaitsAndSurviveDeadline() throws Exception {
        CountDownLatch partial=new CountDownLatch(1),release=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        BlockingQueue<XmppFingerprints.Snapshot> first=new LinkedBlockingQueue<>(),second=new LinkedBlockingQueue<>();
        AtomicInteger calls=new AtomicInteger();
        try(XmppFingerprints loader=new XmppFingerprints((XmppFingerprints.ProgressSource)(jid,modern,progress) -> {
            int attempt=calls.incrementAndGet(); Map<OmemoDevice,OmemoFingerprint> ids=identity(jid); progress.accept(ids); partial.countDown();
            if(attempt==1) try { release.await(); } catch(InterruptedException stopped) { cancelled.countDown(); throw stopped; }
            return ids;
        },500)) {
            loader.load(Collections.singleton("alice@example.org"),false,false,first::add);
            assertTrue(partial.await(2,TimeUnit.SECONDS));
            XmppFingerprints.Snapshot early;
            do { early=first.poll(2,TimeUnit.SECONDS); assertNotNull(early); } while(early.identities.isEmpty());
            assertFalse(early.complete); assertEquals(0,early.failed);
            loader.load(Collections.singleton("alice@example.org"),false,false,second::add);
            XmppFingerprints.Snapshot late;
            do { late=second.poll(2,TimeUnit.SECONDS); assertNotNull(late); } while(!late.complete);
            assertEquals(1,late.identities.size()); assertEquals(1,late.failed);
            assertEquals("Fingerprint lookup timed out",late.failures.get("alice@example.org")); assertEquals(1,calls.get());
            assertTrue(cancelled.await(2,TimeUnit.SECONDS),"Timed-out work must release discovery workers");
            second.clear(); loader.load(Collections.singleton("alice@example.org"),false,true,second::add);
            do { late=second.poll(2,TimeUnit.SECONDS); assertNotNull(late); } while(!late.complete);
            assertEquals(0,late.failed); assertEquals(2,calls.get());
            release.countDown();
        } finally { release.countDown(); }
    }

    @Test void visibleChatPromotesSharedQueuedLookupAheadOfBackgroundWork() throws Exception {
        CountDownLatch busy=new CountDownLatch(4),release=new CountDownLatch(1);
        AtomicInteger visibleCalls=new AtomicInteger(); BlockingQueue<XmppFingerprints.Snapshot> updates=new LinkedBlockingQueue<>();
        try(XmppFingerprints loader=new XmppFingerprints((jid,modern) -> {
            if(jid.startsWith("busy")) { busy.countDown(); release.await(); }
            else visibleCalls.incrementAndGet();
            return identity(jid);
        },3000)) {
            loader.load(Arrays.asList("busy1@example.org","busy2@example.org","busy3@example.org","busy4@example.org"),false,false,ignored -> {});
            assertTrue(busy.await(2,TimeUnit.SECONDS));
            loader.load(Collections.singleton("visible@example.org"),false,false,ignored -> {});
            loader.loadInteractive(Collections.singleton("visible@example.org"),false,false,updates::add);
            XmppFingerprints.Snapshot result;
            do { result=updates.poll(1,TimeUnit.SECONDS); assertNotNull(result,"A visible chat waited behind blocked background discovery"); } while(!result.complete);
            assertEquals(1,result.identities.size()); assertEquals(0,result.failed); assertEquals(1,visibleCalls.get());
        } finally { release.countDown(); }
    }

}
