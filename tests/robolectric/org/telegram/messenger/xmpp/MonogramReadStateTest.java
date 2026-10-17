package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMessageRegressionTest.Device.class,MonogramReadStateTest.Database.class,
                MonogramReadStateTest.Rows.class,MonogramReadStateTest.Queue.class,MonogramReadStateTest.Storage.class,MonogramReadStateTest.Logging.class})
public class MonogramReadStateTest {
    static android.database.sqlite.SQLiteDatabase db;
    @Implements(value=FileLog.class,isInAndroidSdk=false)
    public static class Logging {
        @Implementation public static void e(String text) {}
    }
    @Implements(value=MessagesStorage.class,isInAndroidSdk=false)
    public static class Storage {
        @Implementation public void __constructor__(int account) {}
    }
    @Implements(value=SQLiteDatabase.class,isInAndroidSdk=false)
    public static class Database {
        @Implementation public void __constructor__(String path) {}
        @Implementation public SQLiteCursor queryFinalized(String sql,Object... arguments) {
            String[] args=Arrays.stream(arguments).map(String::valueOf).toArray(String[]::new);
            SQLiteCursor result=new SQLiteCursor(null);
            ((Rows)Shadow.extract(result)).cursor=db.rawQuery(sql,args);
            return result;
        }
    }
    @Implements(value=SQLiteCursor.class,isInAndroidSdk=false)
    public static class Rows {
        android.database.Cursor cursor;
        @Implementation public boolean next() { return cursor.moveToNext(); }
        @Implementation public int intValue(int index) { return cursor.getInt(index); }
        @Implementation public void dispose() { cursor.close(); }
    }
    @Implements(value=DispatchQueue.class,isInAndroidSdk=false)
    public static class Queue {
        @Implementation public boolean postRunnable(Runnable runnable) { runnable.run();return true; }
    }
    private MessagesStorage storage;
    @Before public void setup() throws Exception {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        db=android.database.sqlite.SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE messages_v2(uid INTEGER,mid INTEGER,out INTEGER,read_state INTEGER)");
        db.execSQL("CREATE TABLE dialogs(did INTEGER,unread_count INTEGER,unread_count_i INTEGER,unread_reactions INTEGER,flags INTEGER)");
        storage=new MessagesStorage(0);
        ReflectionHelpers.setField(storage,"database",new SQLiteDatabase("ignored"));
        ReflectionHelpers.setField(storage,"storageQueue",new DispatchQueue("read-state-test",false));
    }
    @After public void cleanup() { db.close(); }
    private TLRPC.Message message(int id,long peer,boolean outgoing) {
        TLRPC.Message message=new TLRPC.TL_message();message.id=id;message.dialog_id=peer;message.out=outgoing;
        message.unread=true;message.media_unread=true;
        if(!outgoing) MonogramReceipts.restoreIncoming(message,0);
        return message;
    }
    @Test public void historyRestoresExactPeerFlagsAndLeavesUnknownRowsAndOutgoingReceiptsAlone() {
        db.execSQL("INSERT INTO messages_v2 VALUES(7,1,0,2),(7,2,0,1),(8,1,0,3),(7,3,1,3)");
        TLRPC.Message unread=message(1,7,false),read=message(2,7,false),other=message(1,8,false),unknown=message(99,7,false),outgoing=message(3,7,true);
        MonogramReceipts.pending(outgoing);
        AtomicBoolean done=new AtomicBoolean();
        storage.restoreMonogramReadState(new ArrayList<>(Arrays.asList(unread,read,other,unknown,outgoing)),null,() -> done.set(true));
        assertTrue(done.get());assertTrue(MessageObject.isUnread(unread));assertFalse(unread.media_unread);
        assertFalse(MessageObject.isUnread(read));assertTrue(read.media_unread);
        assertFalse(MessageObject.isUnread(other));assertFalse(other.media_unread);
        assertTrue(MessageObject.isUnread(unknown));assertTrue(unknown.media_unread);assertTrue(MessageObject.isUnread(outgoing));
        unread.unread=false; // Numeric Telegram read maxima must not erase restored per-message state.
        assertTrue(MessageObject.isUnread(unread));
        MonogramReceipts.markRead(unread);
        assertFalse(MessageObject.isUnread(unread));assertEquals(1,MessageObject.getUnreadFlags(unread)&1);
    }
    @Test public void refreshedDmAndRoomListsRetainNativeCountsAndManualUnreadMarks() {
        db.execSQL("INSERT INTO dialogs VALUES(7,4,1,2,1),(-9,2,0,1,0)");
        TLRPC.Dialog dm=new TLRPC.TL_dialog();dm.id=7;
        TLRPC.Dialog room=new TLRPC.TL_dialog();room.id=-9;
        TLRPC.Dialog absent=new TLRPC.TL_dialog();absent.id=99;
        storage.restoreMonogramReadState(new ArrayList<>(),new ArrayList<>(Arrays.asList(dm,room,absent)),() -> {});
        assertEquals(4,dm.unread_count);assertEquals(1,dm.unread_mentions_count);assertEquals(2,dm.unread_reactions_count);assertTrue(dm.unread_mark);
        assertEquals(2,room.unread_count);assertEquals(0,room.unread_mentions_count);assertEquals(1,room.unread_reactions_count);assertFalse(room.unread_mark);
        assertEquals(0,absent.unread_count);
    }
    @Test public void contentRefreshRechecksNativeFlagsBeforeOverwritingACachedRow() throws Exception {
        db.execSQL("INSERT INTO messages_v2 VALUES(7,1,0,0)");
        TLRPC.Message message=message(1,7,false);
        ArrayList<TLRPC.Message> messages=new ArrayList<>(Arrays.asList(message));
        storage.restoreMonogramReadState(messages,null,() -> {});assertTrue(MessageObject.isUnread(message));
        db.execSQL("UPDATE messages_v2 SET read_state=3 WHERE uid=7 AND mid=1");
        ReflectionHelpers.callInstanceMethod(storage,"restoreMonogramMessageReadState",ReflectionHelpers.ClassParameter.from(ArrayList.class,messages));
        assertFalse(MessageObject.isUnread(message));assertFalse(message.media_unread);
        assertEquals(3,MessageObject.getUnreadFlags(message));
    }
    @Test public void unavailableNativeStateStillCompletesWithoutInventingReads() {
        db.execSQL("DROP TABLE messages_v2");db.execSQL("DROP TABLE dialogs");
        TLRPC.Message message=message(1,7,false);TLRPC.Dialog dialog=new TLRPC.TL_dialog();dialog.id=7;dialog.unread_count=3;
        AtomicBoolean done=new AtomicBoolean();
        storage.restoreMonogramReadState(new ArrayList<>(Arrays.asList(message)),new ArrayList<>(Arrays.asList(dialog)),() -> done.set(true));
        assertTrue(done.get());assertTrue(message.unread);assertEquals(3,dialog.unread_count);
    }
}
