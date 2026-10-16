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
}
