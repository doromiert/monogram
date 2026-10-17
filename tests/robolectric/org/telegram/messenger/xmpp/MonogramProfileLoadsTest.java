package org.telegram.messenger.xmpp;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonogramProfileLoadsTest {
    @Test public void reconnectTransfersWaitersAndOldCompletionCannotClearTheReplacement() {
        MonogramProfileLoads loads=new MonogramProfileLoads();Object first=new Object(),second=new Object();AtomicInteger count=new AtomicInteger();
        MonogramProfileLoads.Load old=loads.begin(7,first,1,1,"bob@example.org",count::incrementAndGet);
        assertNull(loads.begin(7,first,1,1,"bob@example.org",count::incrementAndGet));
        MonogramProfileLoads.Load current=loads.begin(7,second,1,2,"bob@example.org",count::incrementAndGet);
        assertNotNull(current);assertNull(loads.finish(7,old));assertTrue(loads.current(7,current));
        for(Runnable callback:loads.finish(7,current)) callback.run();
        assertEquals(3,count.get());assertNull(loads.finish(7,current));
    }
    @Test public void reusedIdsAcrossAccountsDoNotTransferOldCallbacksAndLogoutCancelsEntries() {
        MonogramProfileLoads loads=new MonogramProfileLoads();Object connection=new Object();AtomicInteger oldCount=new AtomicInteger(),newCount=new AtomicInteger();
        MonogramProfileLoads.Load old=loads.begin(1,connection,1,1,"bob@example.org",oldCount::incrementAndGet);
        MonogramProfileLoads.Load current=loads.begin(1,connection,2,2,"carol@example.org",newCount::incrementAndGet);
        assertNull(loads.finish(1,old));for(Runnable callback:loads.finish(1,current)) callback.run();
        assertEquals(0,oldCount.get());assertEquals(1,newCount.get());
        MonogramProfileLoads.Load canceled=loads.begin(1,connection,2,2,"carol@example.org",newCount::incrementAndGet);loads.clear();
        assertNull(loads.finish(1,canceled));assertEquals(1,newCount.get());
    }
}
