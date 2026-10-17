package org.telegram.messenger.xmpp;

import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.MessageFixture;
import com.negzero.monogram.xmpp.XmppSession;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramDisplayedPrivacyTest.Controller.class,MonogramDisplayedPrivacyTest.Session.class,MonogramMessageRegressionTest.Device.class})
public class MonogramDisplayedPrivacyTest {
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller {
        @Implementation public void __constructor__(int account) {}
    }
    @Implements(value=XmppSession.class,isInAndroidSdk=false)
    public static class Session {
        XMPPTCPConnection connection;
        MonogramStore store;
        boolean enabled;
        int sends,pendingWhenEnabled=-1;
        @Implementation public XMPPTCPConnection connection() { return connection; }
        @Implementation public void setDisplayedMarkersEnabled(boolean value) {
            enabled=value;
            if(value) pendingWhenEnabled=store.reads.pendingDisplayed().size();
        }
        @Implementation public boolean sendDisplayed(String peer,String wire) {
            assertTrue("Controller attempted to send through a disabled core gate",enabled);
            sends++;return true;
        }
    }
    /** Drain the real controller's queued closures in an explicitly chosen order. */
    static final class Queue extends AbstractExecutorService {
        final ArrayDeque<Runnable> pending=new ArrayDeque<>();
        boolean stopped;
        public void execute(Runnable task) { pending.add(task); }
        void drain() { while(!pending.isEmpty()) pending.remove().run(); }
        public void shutdown() { stopped=true; }
        public List<Runnable> shutdownNow() { stopped=true;pending.clear();return Collections.emptyList(); }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped && pending.isEmpty(); }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return isTerminated(); }
    }
    MonogramController controller;
    MonogramStore store;
    SharedPreferences preferences;
    Queue worker=new Queue(),sends=new Queue();
    Session network;
    long peer;
    int sequence;
    @Before public void setup() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("displayed-privacy.db");store=new MonogramStore(context,"displayed-privacy.db");
        peer=store.peer("bob@example.org");
        preferences=context.getSharedPreferences("displayed-privacy",0);preferences.edit().clear().putString("jid","alice@example.org").commit();
        controller=ReflectionHelpers.callConstructor(MonogramController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        ReflectionHelpers.setField(controller,"preferences",preferences);ReflectionHelpers.setField(controller,"store",store);
        ReflectionHelpers.setField(controller,"worker",worker);ReflectionHelpers.setField(controller,"sendWorker",sends);
        ReflectionHelpers.setField(controller,"displayedSettingsLock",new Object());
        ReflectionHelpers.setField(controller,"displayedSending",new AtomicBoolean());
        ReflectionHelpers.setField(controller,"displayedRequests",new AtomicLong());
        XmppSession session=Shadow.newInstanceOf(XmppSession.class);network=Shadow.extract(session);network.store=store;
        network.connection=new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());
        ReflectionHelpers.setField(network.connection,"authenticated",true);ReflectionHelpers.setField(controller,"session",session);
    }
    @After public void close() {
        worker.shutdownNow();sends.shutdownNow();store.close();
        RuntimeEnvironment.getApplication().deleteDatabase("displayed-privacy.db");
    }
    void queueRead() throws Exception {
        String wire="displayed-"+(++sequence);
        int id=store.save(MessageFixture.markable(wire,"origin-"+wire,null,wire,false,true,sequence*100000L,"phone"));
        MonogramReads.ReadResult read=store.reads.throughWithChanges(peer,Collections.singleton(id),false);
        assertTrue(store.reads.queueDisplayed(store.reads.displayedCandidate(peer,read.newlyRead)));
    }
    void flush() { ReflectionHelpers.callInstanceMethod(controller,"flushDisplayedMarkers"); }

    @Test public void optInIsOffByDefaultScopedToAccountAndClearsOldQueueBeforeEnablingWithoutBackfill() throws Exception {
        assertFalse(controller.readReceiptsEnabled());assertFalse(network.enabled);
        queueRead();assertEquals(1,store.reads.pendingDisplayed().size());
        assertTrue(controller.setReadReceiptsEnabled(true));assertTrue(controller.readReceiptsEnabled());
        assertFalse(network.enabled);assertFalse((Boolean)ReflectionHelpers.getField(controller,"displayedReady"));
        flush();assertTrue(sends.pending.isEmpty());assertEquals(1,store.reads.pendingDisplayed().size());
        worker.drain();assertTrue(network.enabled);assertEquals(0,network.pendingWhenEnabled);
        assertTrue(store.reads.pendingDisplayed().isEmpty());flush();sends.drain();assertEquals(0,network.sends);
        assertTrue(preferences.getBoolean("read-receipts-enabled-alice@example.org",false));
        preferences.edit().putString("jid","other@example.org").commit();assertFalse(controller.readReceiptsEnabled());
        assertTrue(controller.setReadReceiptsEnabled(true));worker.drain();
        assertTrue(preferences.getBoolean("read-receipts-enabled-other@example.org",false));
        preferences.edit().putString("jid","alice@example.org").commit();assertTrue(controller.readReceiptsEnabled());
        preferences.edit().remove("jid").commit();assertFalse(controller.readReceiptsEnabled());assertFalse(controller.setReadReceiptsEnabled(true));
    }
    @Test public void disableClosesCoreGateImmediatelyAndStalePrivacyOrAccountFlushCannotSendOrAcknowledge() throws Exception {
        assertTrue(controller.setReadReceiptsEnabled(true));worker.drain();assertTrue(network.enabled);
        queueRead();flush();assertEquals(1,sends.pending.size());
        assertTrue(controller.setReadReceiptsEnabled(false));assertFalse(network.enabled);
        sends.drain();assertEquals(0,network.sends);assertEquals(1,store.reads.pendingDisplayed().size());
        worker.drain();assertTrue(store.reads.pendingDisplayed().isEmpty());assertFalse(network.enabled);
        assertTrue(controller.setReadReceiptsEnabled(true));worker.drain();queueRead();flush();
        ReflectionHelpers.setField(controller,"accountEpoch",1L);
        sends.drain();assertEquals(0,network.sends);
        assertEquals("A stale account flush must not acknowledge the queued candidate",1,store.reads.pendingDisplayed().size());
    }
}
