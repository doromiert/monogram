package org.telegram.messenger.xmpp;

import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.MessageFixture;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramDisplayedMarkersTest {
    Context context;MonogramStore store;long peer,other,self;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("displayed-markers.db");store=new MonogramStore(context,"displayed-markers.db");
        self=store.peer("alice@example.org");peer=store.peer("bob@example.org");other=store.peer("other@example.org");
    }
    @After public void close() { store.close();context.deleteDatabase("displayed-markers.db"); }
    int save(String wire,long time) throws Exception { return store.save(MessageFixture.markable(wire,"origin-"+wire,null,wire,false,true,time,"phone")); }
    MonogramReads.ReadResult read(Integer... ids) { return store.reads.throughWithChanges(peer,Arrays.asList(ids),false); }
    MonogramReads.DisplayedCandidate candidate(Collection<Integer> ids) { return store.reads.displayedCandidate(peer,ids); }
    void reopen() { store.close();store=new MonogramStore(context,"displayed-markers.db"); }
    @Test public void roomMarkersUseRoomIdsAndOwnReflectionsCannotCreateFeedback() throws Exception {
        String address="room@conference.example.org";long room=store.room(address,"Alice");
        int sent=store.saveRoom(MessageFixture.roomMarkable(address,"Alice","sent",100000,true),self);
        int incoming=store.saveRoom(MessageFixture.roomMarkable(address,"Bob","incoming",200000,false),self);
        MonogramReads.ReadResult read=store.reads.throughWithChanges(room,Collections.singleton(incoming),false);
        MonogramReads.DisplayedCandidate candidate=store.reads.displayedCandidate(room,read.newlyRead);
        assertNotNull(candidate);assertEquals("incoming",candidate.wire);assertTrue(store.reads.queueDisplayed(candidate));
        assertEquals(incoming,store.reads.ownMarkerAnchor(room,"incoming"));assertEquals(0,store.reads.ownMarkerAnchor(peer,"incoming"));
        assertEquals(Collections.singletonList(sent),store.roomDisplayed(room,"incoming"));
        assertFalse(MonogramReceipts.isUnread(store.getMessage(sent,self)));
        assertTrue(store.roomDisplayed(room,"sender-incoming").isEmpty());assertTrue(store.roomDisplayed(room,"unknown").isEmpty());
        assertTrue(store.reads.throughWithChanges(room,Collections.singleton(incoming),false).newlyRead.isEmpty());
        reopen();assertFalse(MonogramReceipts.isUnread(store.getMessage(sent,self)));assertEquals(candidate.wire,store.reads.pendingDisplayed().get(0).wire);
        store.reads.clearPendingGroupDisplayed();assertTrue(store.reads.pendingDisplayed().isEmpty());
    }
    @Test public void everyRoomReaderIsRetainedEvenAfterTheFirstDoubleCheckAndRestart() throws Exception {
        String address="room@conference.example.org";long room=store.room(address,"Alice");
        int latest=store.saveRoom(MessageFixture.roomMarkable(address,"Alice","latest",300000,true),self);
        int older=store.saveRoom(MessageFixture.roomMarkable(address,"Alice","older",100000,true),self);
        int anchor=store.saveRoom(MessageFixture.roomMarkable(address,"Carol","anchor",200000,false),self);
        long bob=store.peer("bob@example.org"),carol=store.peer("carol@example.org");
        assertEquals(Collections.singletonList(older),store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Bob","anchor","jid:bob@example.org",false)));
        assertTrue(store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Carol","anchor","jid:carol@example.org",false)).isEmpty());
        assertEquals(Arrays.asList(bob,carol),store.roomReaders(room,older,self));
        assertTrue(store.roomReaders(room,latest,self).isEmpty());
        assertTrue(store.roomReaders(room,anchor,self).isEmpty()); // Incoming messages have no outgoing seen-by menu.
        assertTrue(store.roomReaders(peer,older,self).isEmpty());
        reopen();assertEquals(Arrays.asList(bob,carol),store.roomReaders(room,older,self));
        store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Bob","latest","jid:bob@example.org",false));
        store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Bob","older","jid:bob@example.org",false)); // Stale replay cannot regress Bob.
        assertEquals(Collections.singletonList(bob),store.roomReaders(room,latest,self));
        // Anonymous/departed readers still have local user details for the native seen-by list.
        assertTrue(store.roomFull(room,self).users.stream().anyMatch(user -> user.id==carol));
        store.deleteMessages(Collections.singleton(older));assertTrue(store.roomReaders(room,older,self).isEmpty());
        store.clear();assertEquals(0,rows("room_readers"));
    }
    @Test public void anonymousReadersAreRoomScopedAndNicknameReuseDoesNotMergePeople() throws Exception {
        String address="room@conference.example.org",second="other@conference.example.org";
        long room=store.room(address,"Alice"),otherRoom=store.room(second,"Alice");
        int sent=store.saveRoom(MessageFixture.roomMarkable(address,"Alice","sent",100000,true),self);
        int otherSent=store.saveRoom(MessageFixture.roomMarkable(second,"Alice","sent",100000,true),self);
        store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Bob","sent","id:first",false));
        store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Bob","sent","id:second",false));
        store.roomDisplayed(otherRoom,MessageFixture.roomDisplayed(second,"Bob","sent","id:first",false));
        ArrayList<Long> readers=store.roomReaders(room,sent,self);assertEquals(2,readers.size());
        assertEquals("Bob",store.user(readers.get(0),self).first_name);
        assertTrue(Collections.disjoint(store.peers(),readers));
        assertFalse(readers.contains(store.roomReaders(otherRoom,otherSent,self).get(0)));
        store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Alice","sent","jid:alice@example.org",true));
        store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Eve","unknown","jid:eve@example.org",false));
        assertEquals(readers,store.roomReaders(room,sent,self));
        assertTrue(store.roomDisplayed(room,MessageFixture.roomDisplayed(second,"Mallory","sent","jid:mallory@example.org",false)).isEmpty());
    }
    @Test public void schema29MigrationPreservesDoubleChecksWithoutInventingReaders() throws Exception {
        String address="room@conference.example.org";long room=store.room(address,"Alice");
        int sent=store.saveRoom(MessageFixture.roomMarkable(address,"Alice","sent",100000,true),self);
        store.roomDisplayed(room,"sent");
        store.getWritableDatabase().execSQL("DROP TABLE room_readers");store.getWritableDatabase().setVersion(29);
        reopen();assertEquals(30,store.getReadableDatabase().getVersion());
        assertFalse(MonogramReceipts.isUnread(store.getMessage(sent,self)));
        assertTrue(store.roomReaders(room,sent,self).isEmpty());
        store.roomDisplayed(room,MessageFixture.roomDisplayed(address,"Bob","sent","jid:bob@example.org",false));
        assertEquals(Collections.singletonList(store.peer("bob@example.org")),store.roomReaders(room,sent,self));
    }
    int rows(String table) {
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)) { assertTrue(c.moveToFirst());return c.getInt(0); }
    }
    @Test public void durablePendingAndSentAnchorsAdvanceChronologicallyRatherThanByInsertionId() throws Exception {
        int newer=save("newer-wire",300000),older=save("older-wire",100000);assertTrue(older>newer);
        MonogramReads.ReadResult first=read(older);assertEquals(Collections.singleton(older),first.newlyRead);
        MonogramReads.DisplayedCandidate old=candidate(first.newlyRead);assertEquals("older-wire",old.wire);assertTrue(store.reads.queueDisplayed(old));
        assertTrue(store.reads.isCurrentDisplayed(old));
        MonogramReads.ReadResult second=read(newer);assertEquals(Collections.singleton(newer),second.newlyRead);
        MonogramReads.DisplayedCandidate latest=candidate(second.read);assertEquals(newer,latest.id);assertEquals("newer-wire",latest.wire);
        assertTrue(store.reads.queueDisplayed(latest));assertFalse(store.reads.queueDisplayed(old));
        assertFalse(store.reads.isCurrentDisplayed(old));assertTrue(store.reads.isCurrentDisplayed(latest));
        assertTrue(store.reads.acknowledgeDisplayed(old)); // An in-flight older send must retain the newer queued read.
        assertEquals(newer,store.reads.pendingDisplayed().get(0).id);
        reopen();assertEquals(newer,store.reads.pendingDisplayed().get(0).id);assertTrue(store.reads.isRead(older));
        assertTrue(store.reads.acknowledgeDisplayed(store.reads.pendingDisplayed().get(0)));assertTrue(store.reads.pendingDisplayed().isEmpty());
        assertNull(candidate(Arrays.asList(older,newer)));assertFalse(store.reads.acknowledgeDisplayed(old));
        reopen();assertNull(candidate(Arrays.asList(older,newer)));assertEquals(1,rows("displayed_anchors"));
    }
    @Test public void onlyExplicitNewReadsCanCreateWorkAndImportsNeverBackfillPastReadState() throws Exception {
        int anchor=save("read-with-privacy-off",300000);read(anchor);
        int archive=store.save(MessageFixture.markable("archive-wire","archive-origin","mam-id","Past history",false,true,100000,"phone"));
        assertTrue(store.reads.isRead(archive));assertTrue(read(anchor).newlyRead.isEmpty());
        assertNull(candidate(read(archive).newlyRead));assertTrue(store.reads.pendingDisplayed().isEmpty());
        reopen();assertTrue(read(anchor).newlyRead.isEmpty());assertTrue(store.reads.pendingDisplayed().isEmpty());
        int fresh=save("fresh-wire",400000);MonogramReads.DisplayedCandidate next=candidate(read(fresh).newlyRead);
        assertNotNull(next);assertEquals(fresh,next.id);assertTrue(store.reads.queueDisplayed(next));
        store.reads.clearPendingDisplayed();assertTrue(store.reads.pendingDisplayed().isEmpty());
        assertTrue(read(fresh).newlyRead.isEmpty()); // Re-enabling consent cannot recreate a discarded queue.
    }
    @Test public void unreadableDeletedControlOutgoingWrongPeerAndOpaqueMessagesCannotSendMarkers() throws Exception {
        int normal=store.save(MessageFixture.replyMessage("not-markable","not-markable",null,"Normal",true,false,100000,"phone",false,null));
        int unreadable=store.save(MessageFixture.markable("unreadable","opaque-origin",null,null,false,false,200000,"phone"));
        int deleted=save("deleted",300000);store.deleteMessages(Collections.singleton(deleted));
        int reaction=save("reaction-source",400000);store.getWritableDatabase().execSQL("INSERT INTO reaction_sources(message) VALUES(?)",new Object[]{reaction});
        int correction=save("correction-source",500000);store.getWritableDatabase().execSQL("INSERT INTO corrections(message,target) VALUES(?,'original')",new Object[]{correction});
        int retraction=save("retraction-source",600000);store.getWritableDatabase().execSQL("INSERT INTO retraction_sources(message) VALUES(?)",new Object[]{retraction});
        int outgoing=store.save(MessageFixture.markable("own","own",null,"Own message",true,true,700000,"phone"));
        int pending=store.savePending(MessageFixture.markable("pending","pending",null,"Not revealed",false,true,750000,"phone"));
        int idless=store.save(MessageFixture.markable(null,null,null,"No wire ID",false,true,800000,"phone"));
        MonogramReads.ReadResult result=read(outgoing,idless);assertNull(candidate(result.newlyRead));
        assertNull(store.reads.displayedCandidate(other,result.read));assertNull(store.reads.displayedCandidate(-peer,result.read));
        assertEquals(0,rows("pending_displayed"));assertNotNull(store.getMessage(normal,self));
        store.save(MessageFixture.markable("unreadable","opaque-origin",null,"Decrypted later",false,true,200000,"phone"));
        assertTrue(read(unreadable).newlyRead.isEmpty());assertNull(candidate(read(unreadable).newlyRead));
    }
    @Test public void reusedWireIdsAndNewlyHiddenTargetsArePrunedWithoutAcknowledgingThem() throws Exception {
        int one=store.save(MessageFixture.markable("reused","first-origin",null,"One",false,true,100000,"phone"));
        MonogramReads.DisplayedCandidate first=candidate(read(one).newlyRead);assertTrue(store.reads.queueDisplayed(first));
        int two=store.save(MessageFixture.markable("reused","second-origin",null,"Two",false,true,200000,"laptop"));
        assertNotEquals(one,two);assertNull(candidate(read(two).read));assertTrue(store.reads.pendingDisplayed().isEmpty());
        assertFalse(store.reads.acknowledgeDisplayed(first));assertEquals(0,rows("displayed_anchors"));
        int doomed=save("doomed",300000);MonogramReads.DisplayedCandidate pending=candidate(read(doomed).newlyRead);assertTrue(store.reads.queueDisplayed(pending));
        store.deleteMessages(Collections.singleton(doomed));assertFalse(store.reads.isCurrentDisplayed(pending));assertTrue(store.reads.pendingDisplayed().isEmpty());assertFalse(store.reads.acknowledgeDisplayed(pending));
    }
    @Test public void equalTimestampUsesArchiveOrderThenNativeIdAndNeverRegressesTheSentAnchor() throws Exception {
        int first=save("first",100000),second=save("second",100000);
        MonogramReads.ReadResult result=read(first,second);assertEquals(second,candidate(result.newlyRead).id);
        store.getWritableDatabase().execSQL("UPDATE messages SET archive_order=2 WHERE id=?",new Object[]{first});
        MonogramReads.DisplayedCandidate anchor=candidate(result.newlyRead);assertEquals(first,anchor.id);assertTrue(store.reads.queueDisplayed(anchor));
        assertTrue(store.reads.acknowledgeDisplayed(anchor));assertNull(candidate(Collections.singleton(second)));
    }
    @Test public void schema21MigrationPreservesLocalReadDataAndDoesNotInventOldMarkerRequests() throws Exception {
        int original=save("keep",100000);read(original);
        for(String table:Arrays.asList("markable_messages","displayed_anchors","pending_displayed")) store.getWritableDatabase().execSQL("DROP TABLE "+table);
        store.getWritableDatabase().execSQL("DROP TABLE IF EXISTS call_events");store.getWritableDatabase().execSQL("DROP TABLE saved_message_tags");store.getWritableDatabase().execSQL("DROP TABLE saved_tag_names");store.getWritableDatabase().setVersion(21);reopen();assertEquals(30,store.getReadableDatabase().getVersion());
        assertTrue(store.reads.isRead(original));assertEquals("keep",store.getMessage(original,self).message);assertEquals(0,rows("markable_messages"));
        store.save(MessageFixture.markable("keep","origin-keep","mam-replay","keep",false,true,100000,"phone"));
        assertEquals(1,rows("markable_messages"));assertTrue(read(original).newlyRead.isEmpty());assertTrue(store.reads.pendingDisplayed().isEmpty());
        int fresh=save("new",200000);assertTrue(store.reads.queueDisplayed(candidate(read(fresh).newlyRead)));store.clear();
        for(String table:Arrays.asList("local_read_messages","local_read_anchors","markable_messages","displayed_anchors","pending_displayed")) assertEquals(0,rows(table));
    }
}
