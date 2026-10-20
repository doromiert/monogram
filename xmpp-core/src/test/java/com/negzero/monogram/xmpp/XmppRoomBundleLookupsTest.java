package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppRoomBundleLookupsTest {
    @Test void coldLookupsOverlapButNeverExceedFourAndReturnEveryOutcome() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(4);
        Set<Future<?>> pending=ConcurrentHashMap.newKeySet();
        CountDownLatch firstWave=new CountDownLatch(4);AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        Map<Integer,Callable<Integer>> jobs=new LinkedHashMap<>();
        for(int i=0;i<9;i++) {
            int id=i;jobs.put(i,() -> {
                int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);
                try { firstWave.countDown();assertTrue(firstWave.await(3,TimeUnit.SECONDS));return id*2; }
                finally { active.decrementAndGet(); }
            });
        }
        try {
            Map<Integer,XmppRooms.Lookup<Integer>> results=XmppRooms.parallelLookups(jobs,workers,pending,System.nanoTime()+TimeUnit.SECONDS.toNanos(5),() -> true,(key,result) -> {});
            assertEquals(4,maximum.get());assertEquals(9,results.size());assertTrue(pending.isEmpty());
            for(int i=0;i<9;i++) { assertEquals(i*2,results.get(i).value);assertNull(results.get(i).error); }
        } finally { workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS)); }
    }
    @Test void anActualCompletedFailureCanReachBackoffBeforeGlobalTimeoutButUnfinishedJobsCannot() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(4);Set<Future<?>> pending=ConcurrentHashMap.newKeySet();
        CountDownLatch release=new CountDownLatch(1);AtomicInteger attempted=new AtomicInteger();
        Map<Integer,Callable<Integer>> jobs=new LinkedHashMap<>();
        org.jivesoftware.smack.SmackException.NoResponseException actual=org.jivesoftware.smack.SmackException.NoResponseException.newWith(
                25L,(org.jivesoftware.smack.filter.StanzaFilter)null,false);
        jobs.put(0,() -> { attempted.incrementAndGet();throw actual; });
        for(int i=1;i<9;i++) jobs.put(i,() -> { attempted.incrementAndGet();release.await();return 1; });
        Map<Integer,Exception> finished=new HashMap<>();
        try {
            OmemoSendException failure=assertThrows(OmemoSendException.class,() -> XmppRooms.parallelLookups(jobs,workers,pending,
                    System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500),() -> true,(key,result) -> finished.put(key,result.error)));
            assertEquals(OmemoSendException.Reason.LOOKUP_TIMEOUT,failure.reason);
            assertEquals(Collections.singleton(0),finished.keySet());assertSame(actual,finished.get(0));
            assertTrue(attempted.get()<=5,"Unattempted jobs must stay unattempted when the batch times out");
            assertTrue(pending.isEmpty());
        } finally { release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS)); }
    }
    @Test void closingCancelsOutstandingWorkAndCannotProduceAPartialRecipientBatch() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(4),caller=Executors.newSingleThreadExecutor();
        Set<Future<?>> pending=ConcurrentHashMap.newKeySet();AtomicBoolean current=new AtomicBoolean(true);
        CountDownLatch started=new CountDownLatch(4),release=new CountDownLatch(1);Map<Integer,Callable<Integer>> jobs=new LinkedHashMap<>();
        for(int i=0;i<8;i++) jobs.put(i,() -> { started.countDown();release.await();return 1; });
        try {
            Future<?> batch=caller.submit(() -> {
                try { XmppRooms.parallelLookups(jobs,workers,pending,System.nanoTime()+TimeUnit.SECONDS.toNanos(30),current::get,(key,result) -> fail("No lookup completed before close")); }
                catch(Exception failure) { throw new CompletionException(failure); }
            });
            assertTrue(started.await(3,TimeUnit.SECONDS));current.set(false);
            for(Future<?> future:pending) future.cancel(true);workers.shutdownNow();
            ExecutionException failed=assertThrows(ExecutionException.class,() -> batch.get(3,TimeUnit.SECONDS));
            assertTrue(failed.getCause() instanceof CompletionException);assertTrue(failed.getCause().getCause() instanceof OmemoSendException);
            assertEquals(OmemoSendException.Reason.ROOM_CHANGED,((OmemoSendException)failed.getCause().getCause()).reason);assertTrue(pending.isEmpty());
        } finally { release.countDown();workers.shutdownNow();caller.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));assertTrue(caller.awaitTermination(3,TimeUnit.SECONDS)); }
    }
}
