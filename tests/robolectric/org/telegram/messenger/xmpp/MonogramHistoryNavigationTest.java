package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.MessageFixture;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramHistoryNavigationTest.Controller.class,MonogramHistoryNavigationTest.Storage.class,MonogramHistoryNavigationTest.Base.class,
                MonogramHistoryNavigationTest.Queue.class,MonogramHistoryNavigationTest.Utility.class})
public class MonogramHistoryNavigationTest {
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller { @Implementation public void __constructor__(int account) {} }
    @Implements(value=MessagesStorage.class,isInAndroidSdk=false)
    public static class Storage extends Base {
        static int outboxLoads;
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static MessagesStorage getInstance(int account) { return Shadow.newInstanceOf(MessagesStorage.class); }
        @Implementation public void restoreMonogramReadState(ArrayList<TLRPC.Message> messages,ArrayList<TLRPC.Dialog> dialogs,Runnable done) { done.run(); }
        @Implementation public void getMonogramOutbox(long peer,Consumer<ArrayList<TLRPC.Message>> done) { outboxLoads++;done.accept(new ArrayList<>()); }
    }
    @Implements(value=BaseController.class,isInAndroidSdk=false)
    public static class Base { @Implementation public void __constructor__(int account) {} }
    @Implements(value=Utilities.class,isInAndroidSdk=false)
    public static class Utility { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=DispatchQueue.class,isInAndroidSdk=false)
    public static class Queue { @Implementation public void postRunnable(Runnable task) { task.run(); } }
    private static class Inline extends AbstractExecutorService {
        public void execute(Runnable task) { task.run(); }
        public void shutdown() {} public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return true; }
    }
    private Context context;private MonogramStore store;private long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("history-navigation.db");
        store=new MonogramStore(context,"history-navigation.db");self=store.peer("alice@example.org");peer=store.peer("bob@example.org");Storage.outboxLoads=0;
    }
    @After public void close() { store.close();context.deleteDatabase("history-navigation.db"); }
    private int message(String id,int seconds) throws Exception {
        return store.save(MessageFixture.replyMessage(id,id,null,id,true,false,(long)seconds*1000,"phone",false,null));
    }
    private List<String> text(List<TLRPC.Message> messages) {
        List<String> result=new ArrayList<>();for(TLRPC.Message message:messages) result.add(message.message);return result;
    }
    private MonogramController controller() {
        MonogramController controller=Shadow.newInstanceOf(MonogramController.class);
        ReflectionHelpers.setField(controller,"store",store);ReflectionHelpers.setField(controller,"self",self);
        ReflectionHelpers.setField(controller,"cacheWorker",new Inline());ReflectionHelpers.setField(controller,"archiveWorker",new Inline());ReflectionHelpers.setField(controller,"outbox",new MonogramOutbox());
        ReflectionHelpers.setField(controller,"calendarHistory",ConcurrentHashMap.newKeySet());ReflectionHelpers.setField(controller,"visibleHistory",new ConcurrentHashMap<>());
        ReflectionHelpers.setField(controller,"calendarWindows",new ConcurrentHashMap<>());
        android.content.SharedPreferences preferences=context.getSharedPreferences("navigation-controller",0);preferences.edit().clear().putString("jid","alice@example.org").commit();ReflectionHelpers.setField(controller,"preferences",preferences);
        ReflectionHelpers.setStaticField(Utilities.class,"stageQueue",Shadow.newInstanceOf(DispatchQueue.class));return controller;
    }
    @Test public void messageJumpCannotRetainAnotherDateWindowsRowsOrCursorsWhenOffline() throws Exception {
        int latest=message("latest",900),target=message("target",500),older=message("older",100);
        MonogramController controller=controller();MonogramCalendarWindow previous=new MonogramCalendarWindow();
        previous.start(new MonogramCalendarWindow.Page("recent-first","recent-last",Collections.singleton(latest),false),new MonogramCalendarWindow.Page(null,null,Collections.emptySet(),true));
        ((Map<Long,MonogramCalendarWindow>)ReflectionHelpers.getField(controller,"calendarWindows")).put(peer,previous);
        List<TLRPC.messages_Messages> results=new ArrayList<>();List<Boolean> ends=new ArrayList<>();
        controller.loadHistory(peer,target,1,3,(messages,end)->{results.add(messages);ends.add(end);});
        assertEquals(Collections.singletonList("target"),text(results.get(0).messages));assertEquals(Boolean.FALSE,ends.get(0));assertEquals(0,Storage.outboxLoads);
        MonogramCalendarWindow current=((Map<Long,MonogramCalendarWindow>)ReflectionHelpers.getField(controller,"calendarWindows")).get(peer);
        assertNotSame(previous,current);assertTrue(current.cachedOnly());assertEquals(Collections.singleton(target),current.ids());assertNull(current.cursor(true));assertNull(current.cursor(false));
        controller.loadHistory(peer,target,50,1,(messages,end)->{results.add(messages);ends.add(end);});
        assertEquals(Collections.singletonList("latest"),text(results.get(1).messages));assertTrue(ends.get(1));assertTrue(current.ids().contains(latest));assertFalse(current.ids().contains(older));
        assertNull(current.cursor(true));assertNull(current.cursor(false)); // Local continuation never borrows the old date cursor.
        assertThrows(IllegalArgumentException.class,()->store.historyAround(peer,self,target,50,Collections.singleton(latest)));
    }
    @Test public void onlyExplicitMamResultMetadataCanSupplyANavigationCursor() throws Exception {
        int serverOnly=store.save(MessageFixture.serverAssigned("wire-only","server-stanza",null,100000,false));
        int mam=store.save(MessageFixture.serverAssigned("wire-mam","server-stanza-2","real-mam-uid",200000,false));
        assertNull(store.navigationArchiveId(peer,serverOnly));assertEquals("real-mam-uid",store.navigationArchiveId(peer,mam));assertNull(store.navigationArchiveId(store.peer("foreign@example.org"),mam));
        store.getWritableDatabase().execSQL("INSERT INTO message_ids(peer,outgoing,kind,value,message) VALUES(?,1,'mam','real-mam-uid',?)",new Object[]{peer,serverOnly});
        assertNull(store.navigationArchiveId(peer,mam));
    }
    @Test public void calendarUsesChronologyWithBoundedWindowAndBoundaryDates() throws Exception {
        message("latest",900);message("oldest",100);message("target",500);message("next",700);
        assertEquals(Arrays.asList("next","target","oldest"),text(store.historyAroundDate(peer,self,500,3)));
        assertEquals(Collections.singletonList("next"),text(store.historyAroundDate(peer,self,600,1)));
        assertEquals(Collections.singletonList("oldest"),text(store.historyAroundDate(peer,self,1,1)));
        assertEquals(Collections.singletonList("latest"),text(store.historyAroundDate(peer,self,1000,1)));
        assertEquals(Collections.singletonList("latest"),text(store.historyAroundDate(peer,self,0,1)));
        assertTrue(store.historyAroundDate(store.peer("other@example.org"),self,500,50).isEmpty());
    }
    @Test public void calendarAndMessageJumpsExcludeDeletedPendingAndHiddenControlEvents() throws Exception {
        int original=message("original",100),deleted=message("deleted",200);
        store.deleteMessages(new ArrayList<>(Collections.singletonList(deleted)));
        int correction=store.save(MessageFixture.correction("correction","correction",null,"Edited",true,false,300000,"phone",false,null,"original"));
        store.reconcileCorrections(peer);
        int pending=store.savePending(MessageFixture.replyMessage("pending","pending",null,null,false,false,400000,"phone",true,null));
        int target=message("visible",500);
        assertEquals(target,store.historyAroundDate(peer,self,200,1).get(0).id);
        assertThrows(IllegalArgumentException.class,()->store.historyAround(peer,self,correction,10));
        assertThrows(IllegalArgumentException.class,()->store.historyAround(peer,self,pending,10));
        assertThrows(IllegalArgumentException.class,()->store.historyAround(peer,self,deleted,10));
        assertThrows(IllegalArgumentException.class,()->store.historyAround(store.peer("foreign@example.org"),self,original,10));
        assertEquals("Edited",store.getMessage(original,self).message);
    }
    @Test public void calendarWorksForRoomsWithoutMarkingHistoryReadOrChangingArchiveCursors() throws Exception {
        String room="room@conference.example.org";long dialog=store.room(room,"Alice");
        int newer=store.saveRoom(MessageFixture.roomArchive(room,"Bob","new","sid-new","mam-new","New",900000,false),self);
        int older=store.saveRoom(MessageFixture.roomArchive(room,"Bob","old","sid-old","mam-old","Old",100000,false),self);
        assertEquals(older,store.historyAroundDate(dialog,self,100,1).get(0).id);
        assertEquals(newer,store.historyAroundDate(dialog,self,200,1).get(0).id);
        assertNull(store.archiveCursor(dialog,false));
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM local_read_messages",null)) { assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0)); }
    }
    @Test public void calendarImportPreservesSyncCursorsAndHistoricalReadBoundary() throws Exception {
        String room="room@conference.example.org";long dialog=store.room(room,"Alice");
        int read=store.saveRoom(MessageFixture.roomArchive(room,"Bob","read","sid-read","mam-read","Read",900000,false),self);
        store.reads.through(dialog,Collections.singletonList(read),false);
        com.negzero.monogram.xmpp.XmppRoomMessage old=MessageFixture.roomArchive(room,"Bob","old","sid-old","mam-old","Old",100000,false);
        Set<Integer> updated=new HashSet<>();
        store.saveRoomCalendar(dialog,new com.negzero.monogram.xmpp.XmppRooms.ArchivePage(Collections.singletonList(old),"mam-old","mam-old",false),self,updated);
        assertEquals(1,updated.size());int imported=updated.iterator().next();
        assertTrue(store.reads.isRead(imported));assertFalse(store.getMessage(imported,self).unread);
        assertNull(store.archiveCursor(dialog,true));assertNull(store.archiveCursor(dialog,false));assertFalse(store.archiveComplete(dialog));
        store.saveRoomCalendar(dialog,new com.negzero.monogram.xmpp.XmppRooms.ArchivePage(Collections.singletonList(old),"mam-old","mam-old",false),self,new HashSet<>());
        assertEquals(2,store.history(dialog,self,0,100).size());
        try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT message FROM local_read_anchors WHERE peer=?",new String[]{Long.toString(dialog)})) {
            assertTrue(c.moveToFirst());assertEquals(read,c.getInt(0));
        }
    }
    @Test public void schema24UpgradeAddsEmojiCacheWithoutChangingHistoryOrReadState() throws Exception {
        int original=message("keep",500);store.reads.through(peer,Collections.singletonList(original),false);
        String room="keep@conference.example.org";long dialog=store.room(room,"Alice");
        store.getWritableDatabase().execSQL("INSERT INTO archive_state(scope,oldest,newest,complete) VALUES('direct:"+peer+"','old','new',0)");
        store.close();
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("history-navigation.db").getPath(),null,0)) {
            for(String table:Arrays.asList("inline_emoji_messages","inline_emoji_ranges","inline_emoji_assets","inline_emoji_state")) db.execSQL("DROP TABLE "+table);
            db.setVersion(24);
        }
        store=new MonogramStore(context,"history-navigation.db");
        assertEquals(28,store.getReadableDatabase().getVersion());assertEquals(peer,store.knownPeer("bob@example.org"));assertEquals(dialog,store.knownRoom(room));
        assertEquals("keep",store.getMessage(original,self).message);assertTrue(store.reads.isRead(original));
        assertEquals("old",store.archiveCursor(peer,true));assertEquals("new",store.archiveCursor(peer,false));
        for(String table:Arrays.asList("inline_emoji_messages","inline_emoji_ranges","inline_emoji_assets")) {
            try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)) { assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0)); }
        }
    }
    @Test public void calendarWindowExcludesUnrelatedCachedHistoryAndKeepsCursorOwnership() throws Exception {
        int latest=message("latest",900),selected=message("selected",500),older=message("older",400),oldest=message("oldest",100);
        Set<Integer> window=new HashSet<>(Arrays.asList(selected,older));
        assertEquals(Arrays.asList("selected","older"),text(store.historyAroundDate(peer,self,500,50,window)));
        assertEquals(Collections.singletonList("older"),text(store.history(peer,self,selected,50,false,window)));
        assertEquals(Collections.singletonList("selected"),text(store.history(peer,self,older,50,true,window)));
        assertTrue(store.history(peer,self,older,50,false,window).isEmpty());
        assertTrue(store.historyAroundDate(peer,self,500,50,Collections.emptySet()).isEmpty());
        assertNull(store.archiveCursor(peer,true));assertNull(store.archiveCursor(peer,false));
        assertEquals(4,store.history(peer,self,0,50).size());
    }
    @Test public void controllerRoutesSelectedDateAndDoesNotMixRecentOutboxIntoOldCalendarWindow() throws Exception {
        message("latest",900);message("selected",500);
        MonogramController controller=controller();
        List<TLRPC.messages_Messages> result=new ArrayList<>();
        controller.loadHistory(peer,0,1,4,500,(messages,end)->result.add(messages));
        assertEquals(Collections.singletonList("selected"),text(result.get(0).messages));assertEquals(0,Storage.outboxLoads);
        assertTrue(((Set<Long>)ReflectionHelpers.getField(controller,"calendarHistory")).contains(peer));controller.historyVisible(peer,result.get(0).messages.get(0).id);
        controller.loadHistory(peer,0,1,0,(messages,end)->result.add(messages));
        assertEquals(Collections.singletonList("latest"),text(result.get(1).messages));assertEquals(1,Storage.outboxLoads);
        assertTrue(((Set<Long>)ReflectionHelpers.getField(controller,"calendarHistory")).isEmpty());
    }
}
