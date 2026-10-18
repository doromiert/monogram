package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.MessageFixture;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramChronologicalReadsTest {
    Context context;MonogramStore store;long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("chronological-reads.db");store=new MonogramStore(context,"chronological-reads.db");
        self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
    }
    @After public void close() {store.close();context.deleteDatabase("chronological-reads.db");}
    int save(String id,long time) throws Exception {
        return store.save(MessageFixture.replyMessage(id,id,null,id,true,false,time,"phone",false,null));
    }
    @Test public void readingOlderImportedMessageDoesNotReadNewerLowerIdInSameSecond() throws Exception {
        int newer=save("newer",300500),older=save("older",300100);
        assertTrue(older>newer);
        assertEquals(Collections.singleton(older),store.reads.through(peer,Collections.singleton(older),false));
        assertFalse(MonogramReceipts.isUnread(store.getMessage(older,self)));assertTrue(MonogramReceipts.isUnread(store.getMessage(newer,self)));
        assertEquals(new LinkedHashSet<>(Arrays.asList(newer,older)),store.reads.through(peer,Arrays.asList(older,newer),false));
        store.reads.through(peer,Collections.singleton(older),false); // Scrolling back cannot move the boundary backwards.
        assertFalse(MonogramReceipts.isUnread(store.getMessage(newer,self)));
    }
    @Test public void readBoundarySurvivesReopenAndMarksLaterImportedOlderHistoryRead() throws Exception {
        int anchor=save("anchor",300000);store.reads.through(peer,Collections.singleton(anchor),false);
        store.close();store=new MonogramStore(context,"chronological-reads.db");
        int historical=store.save(MessageFixture.replyMessage("historical","historical","mam-historical","historical",true,false,200000,"phone",false,null)),fresh=save("fresh",400000);
        TLRPC.Message read=store.getMessage(historical,self);
        assertFalse(MonogramReceipts.isUnread(read));
        MonogramReceipts.restoreIncoming(read,0);assertFalse(MonogramReceipts.isUnread(read)); // A stale native cache cannot revoke a persisted read.
        assertTrue(MonogramReceipts.isUnread(store.getMessage(fresh,self)));
    }
    @Test public void delayedLiveMessageRemainsUnreadEvenWhenItIsLaterReplayedByMam() throws Exception {
        int anchor=save("anchor",300000);store.reads.through(peer,Collections.singleton(anchor),false);
        int late=save("late-live",200000);
        assertTrue(MonogramReceipts.isUnread(store.getMessage(late,self)));
        store.save(MessageFixture.replyMessage("late-live","late-live","mam-late","late-live",true,false,200000,"phone",false,null));
        assertTrue(MonogramReceipts.isUnread(store.getMessage(late,self)));
        store.close();store=new MonogramStore(context,"chronological-reads.db");
        assertTrue(MonogramReceipts.isUnread(store.getMessage(late,self)));
        store.reads.through(peer,Collections.singleton(late),false);
        assertFalse(MonogramReceipts.isUnread(store.getMessage(late,self)));
    }
    @Test public void archiveBatchAssignsEqualTimestampOrderBeforeInheritingReads() throws Exception {
        int anchor=save("anchor",300000);store.reads.through(peer,Collections.singleton(anchor),false);
        com.negzero.monogram.xmpp.XmppMessage old=MessageFixture.replyMessage("old","old","mam-old","old",true,false,300000,"phone",false,null);
        store.saveArchive(peer,MessageFixture.page(old),true,new HashSet<>());
        TLRPC.Message historical=store.history(peer,self,0,10).stream().filter(m -> m.message.equals("old")).findFirst().get();
        assertFalse(MonogramReceipts.isUnread(historical));
        int live=save("late-live",200000);
        com.negzero.monogram.xmpp.XmppMessage replay=MessageFixture.replyMessage("late-live","late-live","mam-live","late-live",true,false,200000,"phone",false,null);
        store.saveArchive(peer,MessageFixture.page(replay),true,new HashSet<>());
        assertTrue(MonogramReceipts.isUnread(store.getMessage(live,self)));
    }
    @Test public void equalTimestampsUseArchiveOrderAndRejectOtherRoomOrPendingAnchors() throws Exception {
        int newer=save("newer",300000),older=save("older",300000);
        store.getWritableDatabase().execSQL("UPDATE messages SET archive_order=-1 WHERE id=?",new Object[]{older});
        assertEquals(Collections.singleton(older),store.reads.through(peer,Collections.singleton(older),false));
        assertTrue(MonogramReceipts.isUnread(store.getMessage(newer,self)));
        String room="friends@conference.example.org";long dialog=store.room(room,"Alice");
        int group=store.saveRoom(MessageFixture.roomArchive(room,"Bob","client","room-id","mam-id","Room",400000,false),self);
        assertNull(store.reads.through(peer,Collections.singleton(group),false));
        assertNull(store.reads.through(dialog,Collections.singleton(-1),false));
        assertTrue(MonogramReceipts.isUnread(store.getMessage(group,self)));
        assertEquals(Collections.singleton(group),store.reads.through(dialog,Collections.singleton(group),false));
        assertEquals(new HashSet<>(Arrays.asList(newer,older)),store.reads.through(peer,Collections.emptyList(),true));
    }
    @Test public void schema20UpgradeAddsOnlyReadAnchorsAndRetainsMessagesAndAliases() throws Exception {
        int original=save("keep",300000);
        for(String table:Arrays.asList("local_read_anchors","local_read_messages","markable_messages","displayed_anchors","pending_displayed")) store.getWritableDatabase().execSQL("DROP TABLE "+table);
        store.getWritableDatabase().execSQL("DROP TABLE IF EXISTS call_events");store.getWritableDatabase().setVersion(20);
        store.close();store=new MonogramStore(context,"chronological-reads.db");
        assertEquals(27,store.getReadableDatabase().getVersion());assertEquals("keep",store.getMessage(original,self).message);
        assertTrue(MonogramReceipts.isUnread(store.getMessage(original,self)));assertFalse(store.reads.isRead(original));
        assertEquals(Collections.singleton(original),store.reads.through(peer,Collections.singleton(original),false));
        assertFalse(MonogramReceipts.isUnread(store.getMessage(original,self)));
    }
    @Test public void logoutForgetsLocalReadStateWithTheAccountCache() throws Exception {
        int original=save("old-account",300000);store.reads.through(peer,Collections.singleton(original),false);
        assertTrue(store.reads.isRead(original));store.clear();
        for(String table:Arrays.asList("local_read_messages","local_read_anchors"))
            try(android.database.Cursor rows=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)) {
                assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0));
            }
    }
}
