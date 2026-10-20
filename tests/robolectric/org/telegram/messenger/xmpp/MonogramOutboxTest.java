package org.telegram.messenger.xmpp;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramOutboxTest {
    private TLRPC.Message message(int id,long peer,int date,int state) {
        TLRPC.Message message=new TLRPC.TL_message(); message.id=id; message.dialog_id=peer;
        message.date=date; message.out=true; message.send_state=state; return message;
    }
    @Test public void reopenRetainsQueuedAndFailedRowsWithoutChangingCachedHistoryOrder() {
        MonogramOutbox outbox=new MonogramOutbox();
        TLRPC.Message cached=message(1,7,100,0),queued=message(-1,7,102,1),failed=message(-2,7,101,2);
        ArrayList<TLRPC.Message> history=new ArrayList<>(Arrays.asList(cached));
        outbox.merge(7,history,new ArrayList<>(Arrays.asList(queued,failed,queued,message(-3,8,103,1))));
        assertEquals(Arrays.asList(queued,failed,cached),history);
        assertEquals(1,queued.send_state); assertEquals(2,failed.send_state);
    }
    @Test public void completionReplacesStaleLocalSnapshotsAndDoesNotDuplicateConfirmedMessages() {
        MonogramOutbox outbox=new MonogramOutbox(); TLRPC.Message pending=message(-1,-7,100,1),sent=message(9,-7,100,0);
        outbox.completed(-1,sent);
        ArrayList<TLRPC.Message> history=new ArrayList<>();
        outbox.merge(-7,history,new ArrayList<>(Arrays.asList(pending))); assertEquals(Arrays.asList(sent),history);
        outbox.merge(-7,history,new ArrayList<>(Arrays.asList(pending))); assertEquals(Arrays.asList(sent),history);
        outbox.clear(); history.clear(); outbox.merge(-7,history,new ArrayList<>(Arrays.asList(pending)));
        assertEquals(Arrays.asList(pending),history);
    }
    @Test public void roomWithoutStableReplyIdUsesQuoteAndStillProtectsEncryptedText() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication(); String file="quote-only-room.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.room("room@conference.example.org","Alice");
            int id=store.saveRoom(com.negzero.monogram.xmpp.MessageFixture.roomText("room@conference.example.org","Bob","wire",null,"Secret","bob@example.org","bob-id",100000,true,null,null),self);
            com.negzero.monogram.xmpp.ReplyReference quote=store.replyTo(id,peer,self,true,null,0);
            assertEquals("> Secret\nAnswer",quote.wireBody("Answer"));
            org.jivesoftware.smack.packet.Message wire=new org.jivesoftware.smack.packet.Message(); quote.addTo(wire);
            assertFalse(wire.hasExtension("reply",com.negzero.monogram.xmpp.ReplyReference.NAMESPACE));
            assertThrows(IllegalArgumentException.class,() -> store.replyTo(id,peer,self,false,null,0));
        } finally { context.deleteDatabase(file); }
    }

}
