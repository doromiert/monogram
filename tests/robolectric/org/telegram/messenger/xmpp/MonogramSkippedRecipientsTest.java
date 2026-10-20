package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.AndroidUtilities;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramSkippedRecipientsTest.Account.class,MonogramSkippedRecipientsTest.Rooms.class,
                MonogramSkippedRecipientsTest.Session.class,MonogramSkippedRecipientsTest.Ui.class})
public class MonogramSkippedRecipientsTest {
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Account { @Implementation public void __constructor__(int account) {} }
    @Implements(value=XmppRooms.class,isInAndroidSdk=false)
    public static class Rooms {
        Map<String,String> restored;Consumer<Map<String,String>> changed;
        @Implementation public void setUnavailableCache(Map<String,String> saved,Consumer<Map<String,String>> callback) {
            restored=new LinkedHashMap<>(saved);changed=callback;
        }
    }
    @Implements(value=XmppSession.class,isInAndroidSdk=false)
    public static class Session { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        static int scheduled;
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable task) { scheduled++; }
    }
    private static class Serial extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void drain() { while(!tasks.isEmpty()) tasks.remove().run(); }
        public void shutdown() {} public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return true; }
    }
    private static final String OWN="alice@example.org",KEY="unavailable-room-recipients-"+OWN;
    private Context context;private SharedPreferences preferences;private MonogramStore store;
    private Serial worker;private MonogramController controller;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();preferences=context.getSharedPreferences("skipped-recipients-test",0);preferences.edit().clear().putString("jid",OWN).commit();
        context.deleteDatabase("skipped-recipients-test.db");store=new MonogramStore(context,"skipped-recipients-test.db");worker=new Serial();
        controller=Shadow.newInstanceOf(MonogramController.class);
        ReflectionHelpers.setField(controller,"preferences",preferences);ReflectionHelpers.setField(controller,"store",store);
        ReflectionHelpers.setField(controller,"worker",worker);ReflectionHelpers.setField(controller,"self",store.peer(OWN));
        ReflectionHelpers.setField(controller,"accountEpoch",3L);ReflectionHelpers.setField(controller,"sessionEpoch",7L);Ui.scheduled=0;
    }
    @After public void cleanup() { store.close();context.deleteDatabase("skipped-recipients-test.db"); }
    private Rooms bind(long epoch) {
        XmppSession session=Shadow.newInstanceOf(XmppSession.class);XmppRooms rooms=Shadow.newInstanceOf(XmppRooms.class);
        ReflectionHelpers.setField(session,"rooms",rooms);ReflectionHelpers.setField(controller,"session",session);ReflectionHelpers.setField(controller,"sessionEpoch",epoch);
        ReflectionHelpers.callInstanceMethod(controller,"bindUnavailableRecipients",ReflectionHelpers.ClassParameter.from(XmppSession.class,session),
                ReflectionHelpers.ClassParameter.from(long.class,epoch),ReflectionHelpers.ClassParameter.from(long.class,3L),ReflectionHelpers.ClassParameter.from(String.class,OWN));
        return Shadow.extract(rooms);
    }
    @Test public void cachePersistsAcrossReplacementCoalescesAndIgnoresObsoleteSessionWrites() {
        Rooms first=bind(7);assertTrue(first.restored.isEmpty());
        for(int i=0;i<30;i++) first.changed.accept(Collections.singletonMap("bob@example.org · device 23","unavailable "+i));
        assertEquals(1,worker.tasks.size());worker.drain();
        assertTrue(preferences.getString(KEY,"").contains("unavailable 29"));
        Rooms replacement=bind(8);assertEquals(Collections.singletonMap("bob@example.org · device 23","unavailable 29"),replacement.restored);
        first.changed.accept(Collections.singletonMap("obsolete@example.org","old session"));worker.drain();
        assertFalse(preferences.getString(KEY,"").contains("obsolete"));
        replacement.changed.accept(Collections.emptyMap());worker.drain();assertFalse(preferences.contains(KEY));
        replacement.changed.accept(Collections.singletonMap("old-account@example.org","late completion"));
        preferences.edit().putString("jid","charlie@example.org").commit();ReflectionHelpers.setField(controller,"accountEpoch",4L);worker.drain();
        assertFalse(preferences.contains(KEY));
        preferences.edit().putString("jid",OWN).putString(KEY,"{broken").commit();ReflectionHelpers.setField(controller,"accountEpoch",3L);assertTrue(bind(9).restored.isEmpty());
    }
    @Test public void skippedReportRemainsAvailableWithoutSchedulingAToast() throws Exception {
        XmppRoomMessage message=MessageFixture.roomFile("room@conference.example.org","Alice","sent-id","Text",false,null);
        ReflectionHelpers.setField(message,"skippedRecipients",Collections.singletonMap("unavailable@example.org","No published devices"));
        ReflectionHelpers.callInstanceMethod(controller,"reportSkippedRecipients",ReflectionHelpers.ClassParameter.from(long.class,-12L),ReflectionHelpers.ClassParameter.from(XmppRoomMessage.class,message));
        assertEquals(0,Ui.scheduled);assertTrue(controller.skippedRoomRecipients(-12L).contains("unavailable@example.org"));
    }
}
