package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.MessageFixture;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramCallHistoryTest {
    private static final String FILE="monogram-call-history-test.db";
    private Context context;
    private MonogramStore store;
    private long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(FILE);store=new MonogramStore(context,FILE);
        self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
    }
    @After public void cleanup() { store.close();context.deleteDatabase(FILE); }
    private void reopen() { store.close();store=new MonogramStore(context,FILE); }
    private int save(String sid,boolean outgoing,boolean video,long time,int duration,String reason) {
        return store.saveCall(self,peer,sid,outgoing,video,time,duration,reason);
    }
    private TLRPC.TL_messages_messagesSlice search(boolean missed,int offset,int limit) {
        TLRPC.TL_inputMessagesFilterPhoneCalls filter=new TLRPC.TL_inputMessagesFilterPhoneCalls();filter.missed=missed;
        return store.search(self,0,"",offset,0,limit,0,0,0,filter,false,false);
    }
    private int count(String table) {
        try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)) { assertTrue(rows.moveToFirst());return rows.getInt(0); }
    }
    @Test public void nativeCallActionSurvivesRestartSerializationAndNormalChatHistory() throws Exception {
        int id=save("outgoing-video",true,true,100000,73,"hangup");assertTrue(id>0);reopen();
        TLRPC.Message message=store.getMessage(id,self);
        assertTrue(message instanceof TLRPC.TL_messageService);assertEquals(id,message.id);assertEquals(peer,message.dialog_id);
        assertEquals(self,message.from_id.user_id);assertEquals(peer,message.peer_id.user_id);assertTrue(message.out);assertFalse(message.unread);
        assertTrue(message.action instanceof TLRPC.TL_messageActionPhoneCall);assertEquals(73,message.action.duration);
        assertTrue(message.action.video);assertTrue(message.action.reason instanceof TLRPC.TL_phoneCallDiscardReasonHangup);
        SerializedData output=new SerializedData();message.serializeToStream(output);
        SerializedData input=new SerializedData(output.toByteArray());
        TLRPC.Message roundtrip=TLRPC.Message.TLdeserialize(input,input.readInt32(true),true);input.cleanup();output.cleanup();
        assertTrue(roundtrip instanceof TLRPC.TL_messageService);assertTrue(roundtrip.action.video);assertEquals(73,roundtrip.action.duration);
        assertEquals(id,store.history(peer,self,0,10).get(0).id);assertEquals(id,search(false,0,10).messages.get(0).id);
        assertEquals(0,count("message_ids"));assertEquals(0,count("message_receipts"));assertEquals(0,count("markable_messages"));
        try { store.editTarget(id,peer,self);fail("Local call was editable"); } catch(IllegalArgumentException expected) {}
        try { store.retractionTarget(id,peer,self);fail("Local call acquired a retraction target"); } catch(IllegalArgumentException expected) {}
        try { store.replyTo(id,peer,self,true,null,0);fail("Local call acquired a reply target"); } catch(IllegalArgumentException expected) {}
    }
    @Test public void terminalSidIsIdempotentScopedAndCannotReplaceOriginalSummary() throws Exception {
        int id=save("same-sid",false,false,100000,0,"missed");
        assertEquals(0,save("same-sid",false,true,200000,100,"hangup"));
        assertTrue(store.getMessage(id,self).action.reason instanceof TLRPC.TL_phoneCallDiscardReasonMissed);
        assertFalse(store.getMessage(id,self).action.video);assertEquals(0,store.getMessage(id,self).action.duration);
        int outgoing=save("same-sid",true,false,101000,0,"busy");assertNotEquals(id,outgoing);
        long otherPeer=store.peer("carol@example.org");
        int other=store.saveCall(self,otherPeer,"same-sid",false,false,102000,0,"disconnect");assertNotEquals(id,other);
        long otherOwner=store.peer("dan@example.org");
        int otherAccount=store.saveCall(otherOwner,peer,"same-sid",false,false,103000,0,"missed");
        assertNotEquals(id,otherAccount);assertEquals(Arrays.asList(other,outgoing,id),store.callMessageIds(self));
        try { store.getMessage(otherAccount,self);fail("Other owner's call was projected"); } catch(IllegalArgumentException expected) {}
        assertEquals(Collections.singletonList(otherAccount),store.callMessageIds(otherOwner));
    }
    @Test public void callsSearchPaginatesChronologicallyAndMissedFilterMirrorsNativeCallClassification() throws Exception {
        int newest=save("newest",false,true,500000,0,"missed");
        int oldest=save("oldest",false,false,100000,10,"hangup");
        int middle=save("middle",true,false,300000,0,"busy");
        TLRPC.TL_messages_messagesSlice first=search(false,0,2);assertEquals(3,first.count);
        assertEquals(Arrays.asList(newest,middle),Arrays.asList(first.messages.get(0).id,first.messages.get(1).id));
        TLRPC.TL_messages_messagesSlice next=search(false,middle,2);assertEquals(3,next.count);assertEquals(1,next.messages.size());assertEquals(oldest,next.messages.get(0).id);
        TLRPC.TL_messages_messagesSlice missed=search(true,0,10);assertEquals(2,missed.count);
        assertEquals(Arrays.asList(newest,middle),Arrays.asList(missed.messages.get(0).id,missed.messages.get(1).id));
    }
    @Test public void callsSearchExcludesOtherOwnersAndNeverProjectsUnrelatedRoomHistory() throws Exception {
        int own=save("own-call",true,false,100000,12,"hangup");
        long otherOwner=store.peer("carol@example.org");
        int foreign=store.saveCall(otherOwner,peer,"foreign-call",false,false,200000,0,"missed");
        long room=store.room("crowd@conference.example.org","Alice");
        store.getWritableDatabase().execSQL("INSERT INTO messages(peer,stanza,body,encrypted,encryption,outgoing,time,time_ms) VALUES(?,'unprojectable-room-row','ordinary room history',0,0,0,300,300000)",new Object[]{room});
        int unrelated;
        try(Cursor row=store.getReadableDatabase().rawQuery("SELECT id FROM messages WHERE stanza='unprojectable-room-row'",null)) { assertTrue(row.moveToFirst());unrelated=row.getInt(0); }
        // Both rows deliberately fail projection; a call-only search must exclude them in SQL first.
        try { store.getMessage(unrelated,self);fail("Fixture needs a missing room-sender record"); } catch(IllegalStateException expected) {}
        try { store.getMessage(foreign,self);fail("Fixture needs a different call owner"); } catch(IllegalArgumentException expected) {}
        TLRPC.TL_messages_messagesSlice calls=search(false,0,10);
        assertEquals(1,calls.count);assertEquals(1,calls.messages.size());assertEquals(own,calls.messages.get(0).id);
        assertEquals(0,search(true,0,10).count);assertTrue(search(false,foreign,10).messages.isEmpty());
    }
    @Test public void deletingCallSummariesRetainsSidTombstonesAndNeverDeletesOrdinaryMessages() throws Exception {
        int plain=store.save(MessageFixture.message("ordinary","ordinary-origin",null,"Keep normal message",true,false,50000));
        int first=save("deleted-call",false,false,100000,0,"missed");int second=save("other-call",true,false,200000,30,"hangup");
        store.deleteMessages(Collections.singleton(first));assertTrue(store.hiddenMessage(first));
        reopen();assertEquals(0,save("deleted-call",false,false,300000,0,"missed"));assertEquals(1,search(false,0,10).messages.size());
        assertEquals(Collections.singletonList(second),store.callMessageIds(self));
        store.deleteMessages(store.callMessageIds(self));assertTrue(search(false,0,10).messages.isEmpty());assertEquals(2,count("call_events"));
        assertEquals(Collections.singletonList(plain),Collections.singletonList(store.history(peer,self,0,10).get(0).id));
        assertEquals("Keep normal message",store.getMessage(plain,self).message);
        store.clear();assertEquals(0,count("call_events"));
    }
    @Test public void completedIncomingCallReadsOnlyItsOwnRowAndFailedSqliteCommitCreatesNoOrphan() throws Exception {
        int plain=store.save(MessageFixture.message("unread","unread-origin",null,"Leave unread",true,false,50000));
        int completed=save("completed",false,false,100000,23,"hangup");int missed=save("missed",false,false,200000,0,"missed");
        assertFalse(store.reads.isRead(plain));assertTrue(store.reads.isRead(completed));assertFalse(store.reads.isRead(missed));
        assertFalse(store.getMessage(completed,self).unread);assertTrue(store.getMessage(missed,self).unread);assertEquals(0,count("local_read_anchors"));
        int messages=count("messages");
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_call BEFORE INSERT ON call_events BEGIN SELECT RAISE(ABORT,'call event refused'); END");
        try { save("failed",true,false,300000,0,"disconnect");fail("Call event admission must fail"); } catch(android.database.sqlite.SQLiteException expected) {}
        assertEquals(messages,count("messages"));assertEquals(2,count("call_events"));
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_call");assertTrue(save("failed",true,false,300000,0,"disconnect")>0);
        try { save("invalid",true,false,300000,-1,"hangup");fail("Negative duration accepted"); } catch(IllegalArgumentException expected) {}
        try { save("invalid",true,false,300000,0,"unknown");fail("Unknown outcome accepted"); } catch(IllegalArgumentException expected) {}
    }
    @Test public void schemaTwentyThreeMigrationPreservesExistingMessagesAndAddsCallHistory() throws Exception {
        int existing=store.save(MessageFixture.message("before-migration","before-origin",null,"Existing history",true,false,50000));
        store.getWritableDatabase().execSQL("DROP TABLE call_events");store.getWritableDatabase().setVersion(23);reopen();
        assertEquals(28,store.getReadableDatabase().getVersion());assertEquals("Existing history",store.getMessage(existing,self).message);
        assertEquals(0,count("call_events"));assertTrue(save("after-migration",true,true,100000,13,"hangup")>0);
        assertEquals(2,store.history(peer,self,0,10).size());
    }
}
