package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramScheduledMessagesTest {
    private static final String OWNER="schedule-test@example.org";
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext.getSharedPreferences("monogram-scheduled-0",0).edit().clear().commit();
    }
    private MonogramScheduledMessages.Entry entry(long due,int repeat) {
        TLRPC.TL_messages_sendMessage request=new TLRPC.TL_messages_sendMessage();
        request.peer=new TLRPC.TL_inputPeerUser(); request.peer.user_id=42;
        request.message="durable draft"; request.random_id=901;
        return MonogramScheduledMessages.fromRequest(MonogramScheduledMessages.allocateId(0,OWNER,1),-9,due,repeat,"contact@example.org",request,"OMEMO2",true,"");
    }
    @Test public void claimedDraftCannotBeSentTwiceOrFalselyCancelled() {
        MonogramScheduledMessages.Entry original=entry(1000,0);
        assertTrue(MonogramScheduledMessages.put(0,OWNER,1,original));
        MonogramScheduledMessages.Claim claim=MonogramScheduledMessages.claimDue(0,OWNER,1,2000,10).get(0);
        assertTrue(MonogramScheduledMessages.claimDue(0,OWNER,1,900000,10).isEmpty());
        assertThrows(IllegalStateException.class,()->MonogramScheduledMessages.cancel(0,OWNER,1,42,Collections.singletonList(original.id)));
        assertTrue(MonogramScheduledMessages.complete(claim,2000).committed);
        assertFalse(MonogramScheduledMessages.complete(claim,2000).committed);
        assertTrue(MonogramScheduledMessages.list(0,OWNER,1,42).isEmpty());
    }
    @Test public void retryPreservesIdentityButRepeatAdvancesWithoutCatchupFlood() {
        MonogramScheduledMessages.Entry original=entry(1000,60);
        assertTrue(MonogramScheduledMessages.put(0,OWNER,1,original));
        MonogramScheduledMessages.Claim first=MonogramScheduledMessages.claimDue(0,OWNER,1,1000,10).get(0);
        assertTrue(MonogramScheduledMessages.fail(first,2000));
        MonogramScheduledMessages.Claim retry=MonogramScheduledMessages.claimDue(0,OWNER,1,90000,10).get(0);
        assertEquals(original.randomId,retry.entry.randomId);
        MonogramScheduledMessages.Completion result=MonogramScheduledMessages.complete(retry,90000);
        assertTrue(result.committed); assertNotNull(result.next);
        assertTrue(result.next.dueAt>90000); assertNotEquals(original.randomId,result.next.randomId);
        assertTrue(MonogramScheduledMessages.claimDue(0,OWNER,1,90000,10).isEmpty());
    }
    @Test public void projectionAndAccountOwnershipStaySeparateFromNormalOutbox() {
        MonogramScheduledMessages.Entry original=entry(1000,0);
        assertTrue(MonogramScheduledMessages.put(0,OWNER,1,original));
        assertTrue(MonogramScheduledMessages.list(0,"different@example.org",1,42).isEmpty());
        TLRPC.Message projected=MonogramScheduledMessages.project(original,7);
        assertTrue(projected.id>0); assertEquals(7,projected.from_id.user_id);
        assertEquals("durable draft",projected.message);
        assertEquals(0,original.request(new TLRPC.TL_inputPeerUser()).schedule_date);
    }
}
