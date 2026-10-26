package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.XmppMessage.Encryption;
import com.negzero.monogram.xmpp.XmppSession;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.jivesoftware.smack.tcp.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

/** Exercises the real pending-send admission and dispatch queue without sending network stanzas. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramScheduledEncryptionTest.Controller.class,MonogramScheduledEncryptionTest.Store.class,
                MonogramScheduledEncryptionTest.Session.class,MonogramScheduledEncryptionTest.Ui.class,
                MonogramScheduledEncryptionTest.Utility.class,MonogramScheduledEncryptionTest.Queue.class})
public class MonogramScheduledEncryptionTest {
    private static final String OWN="alice@example.org",CONTACT="bob@example.org";
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller {
        static int dispatches;static String requiredMode;
        @Implementation public void __constructor__(int account) {}
        @Implementation protected TLObject handle(TLObject request,String requiredEncryption) {
            dispatches++;requiredMode=requiredEncryption;
            return new TLRPC.TL_boolTrue();
        }
    }
    @Implements(value=MonogramStore.class,isInAndroidSdk=false)
    public static class Store { @Implementation public String jid(long peer) { return CONTACT; } }
    @Implements(value=XmppSession.class,isInAndroidSdk=false)
    public static class Session {
        static XMPPTCPConnection connection;
        static Encryption available;
        @Implementation public static void __staticInitializer__() {}
        @Implementation public XMPPTCPConnection connection() { return connection; }
        @Implementation public boolean probeEncryption(String jid,Encryption mode,boolean room,Set<String> excluded,boolean automatic) {
            return mode==available;
        }
    }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable task) {}
        @Implementation public static void runOnUIThread(Runnable task,long delay) {}
    }
    @Implements(value=Utilities.class,isInAndroidSdk=false)
    public static class Utility { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=DispatchQueue.class,isInAndroidSdk=false)
    public static class Queue { @Implementation public boolean postRunnable(Runnable task) { task.run();return true; } }
    private static class Serial extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void one() { tasks.remove().run(); }
        void drain() { while(!tasks.isEmpty()) one(); }
        public void shutdown() {} public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return true; }
    }
    private MonogramController controller;private SharedPreferences prefs;private MonogramEncryptionPolicy policy;
    private Serial sender;private AtomicReference<TLRPC.TL_error> failure;private AtomicBoolean completed;
    @Before public void setup() throws Exception {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        Utilities.stageQueue=Shadow.newInstanceOf(DispatchQueue.class);
        prefs=ApplicationLoader.applicationContext.getSharedPreferences("scheduled-pin-test",0);
        prefs.edit().clear().putString("jid",OWN).commit();
        policy=new MonogramEncryptionPolicy(prefs);sender=new Serial();
        controller=Shadow.newInstanceOf(MonogramController.class);
        ReflectionHelpers.setField(controller,"preferences",prefs);
        ReflectionHelpers.setField(controller,"encryptionPolicy",policy);
        ReflectionHelpers.setField(controller,"store",Shadow.newInstanceOf(MonogramStore.class));
        ReflectionHelpers.setField(controller,"sendWorker",sender);
        ReflectionHelpers.setField(controller,"localSendToken",new AtomicInteger(-1));
        for(String field:new String[]{"queuedEncryption","queuedLocalIds","queuedSecurityParams","queuedCaptions","queuedXmrPolicies","pendingSends"})
            ReflectionHelpers.setField(controller,field,new ConcurrentHashMap<>());
        ReflectionHelpers.setField(controller,"queuedPinnedEncryption",ConcurrentHashMap.newKeySet());
        Session.connection=new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());
        ReflectionHelpers.setField(Session.connection,"authenticated",true);
        ReflectionHelpers.setField(controller,"session",Shadow.newInstanceOf(XmppSession.class));
        Controller.dispatches=0;Controller.requiredMode=null;failure=new AtomicReference<>();completed=new AtomicBoolean();
    }
    private void mode(Encryption mode) {
        Session.available=mode;
        policy.selected(OWN,CONTACT,42,mode,System.currentTimeMillis());
        assertEquals(OWN,controller.accountJid());
        MonogramStore store=ReflectionHelpers.getField(controller,"store");assertEquals(CONTACT,store.jid(42));
        // Exercise the real automatic selector against a deterministic local probe; do not
        // depend on the test runner and instrumented app sharing a wall-clock cache epoch.
        policy.automatic(OWN,CONTACT,true);
    }
    private void queue(boolean pinned) {
        TLRPC.TL_messages_sendMessage request=new TLRPC.TL_messages_sendMessage();
        request.peer=new TLRPC.TL_inputPeerUser();request.peer.user_id=42;request.message="scheduled";
        ConcurrentHashMap<TLObject,String> modes=ReflectionHelpers.getField(controller,"queuedEncryption");modes.put(request,"OMEMO2");
        if(pinned) { Set<TLObject> pins=ReflectionHelpers.getField(controller,"queuedPinnedEncryption");pins.add(request); }
        RequestDelegate callback=(response,error)-> { failure.set(error);completed.set(true); };
        ReflectionHelpers.callInstanceMethod(controller,"queueSend",ReflectionHelpers.ClassParameter.from(int.class,0),
                ReflectionHelpers.ClassParameter.from(TLObject.class,request),ReflectionHelpers.ClassParameter.from(RequestDelegate.class,callback));
        assertTrue(((Set<?>)ReflectionHelpers.getField(controller,"queuedPinnedEncryption")).isEmpty());
    }
    private void assertBlocked() {
        sender.drain();assertTrue(completed.get());assertNotNull(failure.get());
        assertEquals("MONOGRAM_SENDING_MODE_CHANGED",failure.get().text);assertEquals(0,Controller.dispatches);
        assertTrue(((Map<?,?>)ReflectionHelpers.getField(controller,"pendingSends")).isEmpty());
    }
    @Test public void manualModernScheduleCannotDowngradeWhenCurrentPolicyIsAutomaticPlain() {
        mode(Encryption.PLAIN);queue(true);assertBlocked();
    }
    @Test public void manualModernScheduleCannotChangeToAutomaticLegacy() {
        mode(Encryption.LEGACY_OMEMO);queue(true);assertBlocked();
    }
    @Test public void matchingManualModeReachesDispatchWithThePinnedMode() {
        mode(Encryption.OMEMO2);queue(true);sender.drain();
        assertTrue(completed.get());assertNull(failure.get());assertEquals(1,Controller.dispatches);assertEquals("OMEMO2",Controller.requiredMode);
    }
    @Test public void policyChangeAfterAdmissionStillCannotReachActualDispatch() {
        mode(Encryption.OMEMO2);queue(true);sender.one();
        assertEquals(0,Controller.dispatches);assertFalse(completed.get());
        mode(Encryption.PLAIN);assertBlocked();
    }
    @Test public void automaticScheduleMayUseTheCurrentAutomaticPolicy() {
        mode(Encryption.PLAIN);queue(false);sender.drain();
        assertTrue(completed.get());assertNull(failure.get());assertEquals(1,Controller.dispatches);assertNull(Controller.requiredMode);
    }
}
