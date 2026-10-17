package org.telegram.messenger;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmojiLoadQueueTest {
    @Test public void quickThenCurrentViewportThenPrefetchWithoutDuplicateDecodes() {
        ArrayDeque<Runnable> worker=new ArrayDeque<>();List<Integer> decoded=new ArrayList<>();
        EmojiLoadQueue queue=new EmojiLoadQueue(worker::add);
        queue.submit(1,0,() -> decoded.add(1));
        queue.submit(2,1,() -> decoded.add(2));
        queue.submit(3,0,() -> decoded.add(3));
        queue.submit(3,1,() -> fail("Promotion must retain the same decode"));
        queue.submit(4,2,() -> { decoded.add(4);queue.submit(4,2,() -> fail("Already decoding")); });
        queue.visibleWindow(Arrays.asList(3));
        while(!worker.isEmpty()) worker.remove().run();
        assertEquals(Arrays.asList(4,3,1,2),decoded);
    }
    @Test public void stalePrefetchIsBoundedAndFailureDoesNotStrandLaterRequests() {
        ArrayDeque<Runnable> worker=new ArrayDeque<>();List<Integer> decoded=new ArrayList<>();
        EmojiLoadQueue queue=new EmojiLoadQueue(worker::add);
        for(int i=0;i<100;i++) { final int key=i;queue.submit(key,0,() -> decoded.add(key)); }
        queue.submit(100,2,() -> { throw new IllegalStateException("decode failed"); });
        try { worker.remove().run();fail(); } catch(IllegalStateException expected) { }
        while(!worker.isEmpty()) worker.remove().run();
        assertEquals(64,decoded.size());assertEquals(Integer.valueOf(36),decoded.get(0));assertEquals(Integer.valueOf(99),decoded.get(63));
    }
}
