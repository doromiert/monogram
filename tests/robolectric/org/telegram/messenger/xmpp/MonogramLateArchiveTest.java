package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramLateArchiveTest.Storage.class,MonogramLateArchiveTest.NativeDialogs.class,
                MonogramLateArchiveTest.Account.class,MonogramLateArchiveTest.Ui.class,
                MonogramLateArchiveTest.Events.class,MonogramLateArchiveTest.Notifications.class,
                MonogramNotificationDecryptionTest.Queue.class,MonogramAudioSourcesTest.Message.class,
                MonogramNotificationSettingsTest.Clock.class,MonogramNotificationSettingsTest.Base.class,
                MonogramLateArchiveTest.Controller.class,MonogramScreenWakeTest.Session.class})
public class MonogramLateArchiveTest {
    @Implements(value=MessagesStorage.class,isInAndroidSdk=false)
    public static class Storage extends MonogramNotificationSettingsTest.Base {
        @Implementation public void __constructor__(int account) {}
        static final List<TLRPC.Message> saved=new ArrayList<>();
        @Implementation public static MessagesStorage getInstance(int account) { return MonogramScreenWakeTest.instance(MessagesStorage.class,account); }
        @Implementation public void putMessages(ArrayList<TLRPC.Message> messages,boolean transaction,boolean queue,boolean date,int mask,int mode,long thread) { saved.addAll(messages); }
        @Implementation public DispatchQueue getStorageQueue() { return new DispatchQueue("late-archive-test"); }
        @Implementation public void restoreMonogramReadState(ArrayList<TLRPC.Message> messages,ArrayList<TLRPC.Dialog> dialogs,Runnable done) { done.run(); }
        @Implementation public void putUsersAndChats(List<TLRPC.User> users,List<TLRPC.Chat> chats,boolean transaction,boolean queue) {}
    }
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class NativeDialogs extends MonogramNotificationSettingsTest.Base {
        @Implementation public void __constructor__(int account) {}
        static int publications,liveUpdates;
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static MessagesController getInstance(int account) { return MonogramScreenWakeTest.instance(MessagesController.class,account); }
        @Implementation public void putUsers(ArrayList<TLRPC.User> users,boolean cache) {}
        @Implementation public void putChat(TLRPC.Chat chat,boolean cache) {}
        @Implementation public void processLoadedDialogs(TLRPC.messages_Dialogs dialogs,ArrayList<TLRPC.EncryptedChat> encrypted,ArrayList<TLRPC.UserFull> full,int folder,int offset,int count,int type,boolean reset,boolean migrate,boolean cache) { publications++; }
        @Implementation public boolean processUpdateArray(ArrayList<TLRPC.Update> updates,ArrayList<TLRPC.User> users,ArrayList<TLRPC.Chat> chats,boolean difference,int date) {
            for(TLRPC.Update update:updates) if(update instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) liveUpdates++;
            return true;
        }
    }
    @Implements(value=UserConfig.class,isInAndroidSdk=false)
    public static class Account extends MonogramNotificationSettingsTest.Base {
        @Implementation public void __constructor__(int account) {}
        @Implementation public static UserConfig getInstance(int account) { return MonogramScreenWakeTest.instance(UserConfig.class,account); }
        @Implementation public boolean isClientActivated() { return true; }
    }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        static final ArrayDeque<Runnable> pending=new ArrayDeque<>();
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable action) { pending.add(action); }
        @Implementation public static boolean isTablet() { return false; }
        static void drain() { while(!pending.isEmpty()) pending.remove().run(); }
    }
    @Implements(value=NotificationCenter.class,isInAndroidSdk=false)
    public static class Events {
        @Implementation public void __constructor__(int account) {}
        static final List<MessageObject> replaced=new ArrayList<>();
        @Implementation public static NotificationCenter getInstance(int account) { return MonogramScreenWakeTest.instance(NotificationCenter.class,account); }
        @Implementation public void postNotificationName(int id,Object... args) {
            if(id==NotificationCenter.replaceMessagesObjects) replaced.addAll((ArrayList<MessageObject>)args[1]);
        }
    }
    @Implements(value=NotificationsController.class,isInAndroidSdk=false)
    public static class Notifications extends MonogramNotificationSettingsTest.Base {
        @Implementation public void __constructor__(int account) {}
        static int edits,pushes;
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static NotificationsController getInstance(int account) { return MonogramScreenWakeTest.instance(NotificationsController.class,account); }
        @Implementation public void processEditedMessages(androidx.collection.LongSparseArray<ArrayList<MessageObject>> messages) { edits++; }
        @Implementation public void processNewMessages(ArrayList<MessageObject> messages,boolean last,boolean fcm,java.util.concurrent.CountDownLatch done) { pushes++; }
    }
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller {
        @Implementation public void __constructor__(int account) {}
        @Implementation public void refreshProfile(long peer) {}
    }
    Context context;MonogramStore store;MonogramController controller;long self,peer;
    Class<?> syncClass,taskClass;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("late-archive.db");store=new MonogramStore(context,"late-archive.db");
        self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
        controller=MonogramScreenWakeTest.instance(MonogramController.class,0);
        ReflectionHelpers.setField(controller,"store",store);ReflectionHelpers.setField(controller,"self",self);
        ReflectionHelpers.setField(controller,"preferences",context.getSharedPreferences("late-archive",0));
        context.getSharedPreferences("late-archive",0).edit().clear().putString("jid","alice@example.org").commit();
        ReflectionHelpers.setField(controller,"accountEpoch",7L);
        ReflectionHelpers.setField(controller,"pendingRecoveryRooms",new HashSet<String>());
        syncClass=Class.forName(MonogramController.class.getName()+"$ArchiveSync");
        taskClass=Class.forName(MonogramController.class.getName()+"$ArchiveTask");
        Storage.saved.clear();Events.replaced.clear();Ui.pending.clear();NativeDialogs.publications=0;NativeDialogs.liveUpdates=0;Notifications.edits=0;Notifications.pushes=0;
    }
    @After public void close() { Ui.pending.clear();store.close();context.deleteDatabase("late-archive.db"); }
    private Object construct(Class<?> type,Class<?>[] parameters,Object... args) throws Exception {
        Constructor<?> constructor=type.getDeclaredConstructor(parameters);constructor.setAccessible(true);return constructor.newInstance(args);
    }
    private Object sync() throws Exception {
        return construct(syncClass,new Class<?>[]{XmppSession.class,long.class,String.class},MonogramScreenWakeTest.session(),7L,"alice@example.org");
    }
    private Runnable delayedPage(Object oldSync,XmppSession.ArchivePage page) throws Exception {
        Object task=construct(taskClass,new Class<?>[]{long.class},peer);
        Method accept=MonogramController.class.getDeclaredMethod("archivePage",syncClass,taskClass,XmppSession.ArchivePage.class,boolean.class);accept.setAccessible(true);
        return () -> { try { accept.invoke(controller,oldSync,task,page,true); } catch(Exception failed) { throw new AssertionError(failed); } };
    }
    private XmppSession.ArchivePage page() throws Exception {
        return MessageFixture.page(MessageFixture.modern("late","mam-late","Recovered after reconnect",false,100000,null,null,null));
    }
    @Test public void delayedPageSurvivesReplacedSyncAndSessionWithoutCommittingOldCursorOrNewMessageEvents() throws Exception {
        Object oldSync=sync();Runnable completed=delayedPage(oldSync,page());
        ReflectionHelpers.setField(controller,"archiveSync",sync());
        ReflectionHelpers.setField(controller,"session",MonogramScreenWakeTest.session());
        // The replacement sync is also obsolete here; neither session may suppress retained authenticated data.
        completed.run();
        assertNull(store.archiveCursor(peer,false));assertNull(store.archiveCursor(false));
        assertEquals(1,store.history(peer,self,0,10).size());
        assertEquals("Recovered after reconnect",Storage.saved.get(0).message);
        ReflectionHelpers.setField(controller,"session",MonogramScreenWakeTest.session());
        Ui.drain();
        assertEquals(1,Events.replaced.size());assertEquals(Storage.saved.get(0).id,Events.replaced.get(0).getId());
        assertEquals(1,NativeDialogs.publications);assertEquals(1,Notifications.edits);
        assertEquals(0,store.save(page().messages.get(0))); // A duplicate replay cannot be relied on to publish this later.
    }
    @Test public void changedAccountRejectsLatePageAndQueuedUiPublication() throws Exception {
        Runnable completed=delayedPage(sync(),page());
        ReflectionHelpers.setField(controller,"accountEpoch",8L);completed.run();Ui.drain();
        assertTrue(store.history(peer,self,0,10).isEmpty());assertTrue(Storage.saved.isEmpty());assertTrue(Events.replaced.isEmpty());
        ReflectionHelpers.setField(controller,"accountEpoch",7L);completed.run();assertFalse(Storage.saved.isEmpty());
        ReflectionHelpers.setField(controller,"accountEpoch",8L);Ui.drain();
        assertTrue(Events.replaced.isEmpty());assertEquals(0,NativeDialogs.publications);assertEquals(0,Notifications.edits);
    }
    private void received(long owner,String jid,XmppMessage message,Runnable accepted) {
        ReflectionHelpers.callInstanceMethod(controller,"received",ReflectionHelpers.ClassParameter.from(long.class,owner),
                ReflectionHelpers.ClassParameter.from(String.class,jid),ReflectionHelpers.ClassParameter.from(XmppMessage.class,message),
                ReflectionHelpers.ClassParameter.from(Runnable.class,accepted));
    }
    @Test public void journalReplayPublishesArchivedCanonicalHistorySilentlyAndAcceptsAfterSaving() throws Exception {
        MonogramDisplayedPrivacyTest.Queue worker=new MonogramDisplayedPrivacyTest.Queue();ReflectionHelpers.setField(controller,"worker",worker);
        XmppMessage recovered=page().messages.get(0);assertTrue(recovered.archived);
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();
        received(7L,"alice@example.org",recovered,() -> {
            assertEquals("Recovered after reconnect",store.history(peer,self,0,10).get(0).message);
            assertEquals(1,Storage.saved.size());accepted.incrementAndGet();
        });
        assertEquals(0,accepted.get());assertTrue(store.history(peer,self,0,10).isEmpty());
        worker.drain();assertEquals(1,accepted.get());
        Ui.drain();
        assertEquals(1,Events.replaced.size());assertEquals(Storage.saved.get(0).id,Events.replaced.get(0).getId());
        assertEquals(1,NativeDialogs.publications);assertEquals(1,Notifications.edits);
        assertEquals(0,NativeDialogs.liveUpdates);assertEquals(0,Notifications.pushes);
        assertNull(store.archiveCursor(peer,false));assertNull(store.archiveCursor(false));
    }
    @Test public void failedReplaySaveDoesNotAcceptAndCanBeRetried() throws Exception {
        MonogramDisplayedPrivacyTest.Queue worker=new MonogramDisplayedPrivacyTest.Queue();ReflectionHelpers.setField(controller,"worker",worker);
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();XmppMessage recovered=page().messages.get(0);
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_replay BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'replay save refused'); END");
        received(7L,"alice@example.org",recovered,accepted::incrementAndGet);
        try { worker.drain();fail("Message save must fail"); } catch(android.database.sqlite.SQLiteException expected) {}
        assertEquals(0,accepted.get());assertTrue(store.history(peer,self,0,10).isEmpty());assertTrue(Storage.saved.isEmpty());
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_replay");
        received(7L,"alice@example.org",recovered,accepted::incrementAndGet);worker.drain();assertEquals(1,accepted.get());
    }
    @Test public void accountEpochOrJidMismatchRejectsReplayWithoutAcceptance() throws Exception {
        MonogramDisplayedPrivacyTest.Queue worker=new MonogramDisplayedPrivacyTest.Queue();ReflectionHelpers.setField(controller,"worker",worker);
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();XmppMessage recovered=page().messages.get(0);
        received(6L,"alice@example.org",recovered,accepted::incrementAndGet);
        received(7L,"other@example.org",recovered,accepted::incrementAndGet);
        worker.drain();Ui.drain();
        assertEquals(0,accepted.get());assertTrue(store.history(peer,self,0,10).isEmpty());assertTrue(Storage.saved.isEmpty());assertTrue(Events.replaced.isEmpty());
    }
    private static final String ROOM="friends@conference.example.org";
    private XmppRoomMessage roomMessage() throws Exception {
        return MessageFixture.roomText(ROOM,"Bob","room-recovered","room-mam-recovered","Room recovered after reconnect",
                "bob@example.org","bob-stable",100000,true,null,null);
    }
    private void receivedRoom(long owner,String jid,XmppRoomMessage message,Runnable accepted) {
        ReflectionHelpers.callInstanceMethod(controller,"receivedRoom",ReflectionHelpers.ClassParameter.from(long.class,owner),
                ReflectionHelpers.ClassParameter.from(String.class,jid),ReflectionHelpers.ClassParameter.from(XmppRoomMessage.class,message),
                ReflectionHelpers.ClassParameter.from(Runnable.class,accepted));
    }
    @Test public void roomJournalReplaySavesCanonicalHistoryAndNativeCacheBeforeAcceptanceWithoutLiveEvents() throws Exception {
        long room=store.room(ROOM,"Alice");
        MonogramDisplayedPrivacyTest.Queue worker=new MonogramDisplayedPrivacyTest.Queue();ReflectionHelpers.setField(controller,"worker",worker);
        XmppRoomMessage recovered=roomMessage();assertTrue(recovered.archived);
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();
        receivedRoom(7L,"alice@example.org",recovered,() -> {
            assertEquals("Room recovered after reconnect",store.history(room,self,0,10).get(0).message);
            assertEquals(1,Storage.saved.size());assertEquals(room,Storage.saved.get(0).dialog_id);accepted.incrementAndGet();
        });
        assertEquals(0,accepted.get());assertTrue(store.history(room,self,0,10).isEmpty());
        worker.drain();assertEquals(1,accepted.get());Ui.drain();
        assertEquals(1,Events.replaced.size());assertEquals(Storage.saved.get(0).id,Events.replaced.get(0).getId());
        assertEquals(1,NativeDialogs.publications);assertEquals(1,Notifications.edits);
        assertEquals(0,NativeDialogs.liveUpdates);assertEquals(0,Notifications.pushes);
        assertNull(store.archiveCursor(room,false));assertNull(store.archiveCursor(false));
        assertEquals(0,store.saveRoom(recovered,self));
    }
    @Test public void failedRoomReplaySaveLeavesUnacceptedJournalRetryable() throws Exception {
        long room=store.room(ROOM,"Alice");
        MonogramDisplayedPrivacyTest.Queue worker=new MonogramDisplayedPrivacyTest.Queue();ReflectionHelpers.setField(controller,"worker",worker);
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();XmppRoomMessage recovered=roomMessage();
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_room_replay BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'room replay save refused'); END");
        receivedRoom(7L,"alice@example.org",recovered,accepted::incrementAndGet);
        try { worker.drain();fail("Room save must fail"); } catch(android.database.sqlite.SQLiteException expected) {}
        assertEquals(0,accepted.get());assertTrue(store.history(room,self,0,10).isEmpty());assertTrue(Storage.saved.isEmpty());
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_room_replay");
        receivedRoom(7L,"alice@example.org",recovered,accepted::incrementAndGet);worker.drain();assertEquals(1,accepted.get());
    }
    @Test public void unknownRoomAndAccountMismatchDoNotAcceptOrLosePendingRecovery() throws Exception {
        MonogramDisplayedPrivacyTest.Queue worker=new MonogramDisplayedPrivacyTest.Queue();ReflectionHelpers.setField(controller,"worker",worker);
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();XmppRoomMessage recovered=roomMessage();
        receivedRoom(6L,"alice@example.org",recovered,accepted::incrementAndGet);
        receivedRoom(7L,"other@example.org",recovered,accepted::incrementAndGet);worker.drain();
        Set<String> pending=ReflectionHelpers.getField(controller,"pendingRecoveryRooms");assertTrue(pending.isEmpty());
        receivedRoom(7L,"alice@example.org",recovered,accepted::incrementAndGet);worker.drain();Ui.drain();
        assertEquals(Collections.singleton(ROOM),pending);assertEquals(0,store.knownRoom(ROOM));
        assertEquals(0,accepted.get());assertTrue(Storage.saved.isEmpty());assertTrue(Events.replaced.isEmpty());
        long room=store.room(ROOM,"Alice");
        receivedRoom(7L,"alice@example.org",recovered,accepted::incrementAndGet);worker.drain();
        assertEquals(1,accepted.get());assertTrue(pending.isEmpty());assertEquals(1,store.history(room,self,0,10).size());
    }
    @Test public void roomArchivePageAcceptsOnlyAfterSuccessfulRoomAndNativeCacheStorage() throws Exception {
        long room=store.room(ROOM,"Alice");XmppRoomMessage recovered=roomMessage();
        java.util.concurrent.atomic.AtomicInteger accepted=new java.util.concurrent.atomic.AtomicInteger();
        Runnable callback=() -> {
            assertEquals(1,store.history(room,self,0,10).size());assertEquals(1,Storage.saved.size());accepted.incrementAndGet();
        };
        XmppRooms.ArchivePage page=(XmppRooms.ArchivePage)construct(XmppRooms.ArchivePage.class,
                new Class<?>[]{List.class,String.class,String.class,boolean.class,List.class},Collections.singletonList(recovered),
                "room-mam-recovered","room-mam-recovered",true,Collections.singletonList(callback));
        Object task=construct(taskClass,new Class<?>[]{long.class},room);
        Method receive=MonogramController.class.getDeclaredMethod("roomArchivePage",syncClass,taskClass,XmppRooms.ArchivePage.class,boolean.class);receive.setAccessible(true);
        Object oldSync=sync();
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_room_page BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'room page save refused'); END");
        try { receive.invoke(controller,oldSync,task,page,true);fail("Room archive save must fail"); }
        catch(InvocationTargetException expected) { assertTrue(expected.getCause() instanceof android.database.sqlite.SQLiteException); }
        assertEquals(0,accepted.get());assertTrue(store.history(room,self,0,10).isEmpty());assertTrue(Storage.saved.isEmpty());
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_room_page");
        receive.invoke(controller,oldSync,task,page,true);assertEquals(1,accepted.get());
        page.stored();assertEquals(1,accepted.get());assertNull(store.archiveCursor(room,false));
        Ui.drain();assertEquals(0,NativeDialogs.liveUpdates);assertEquals(0,Notifications.pushes);
    }
}
