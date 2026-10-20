package org.telegram.messenger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executor;

/** One decoder, with visible demand ahead of bounded speculative prefetch. */
final class EmojiLoadQueue {
    private final ArrayList<LinkedHashMap<Integer,Runnable>> queues=new ArrayList<>();
    private final Executor executor;
    private boolean scheduled;
    private Integer running;
    EmojiLoadQueue(Executor executor) {
        this.executor=executor;
        for(int i=0;i<3;i++) queues.add(new LinkedHashMap<>());
    }
    void submit(int key,int priority,Runnable decode) {
        boolean start=false;
        synchronized(this) {
            if(running!=null && running==key) return;
            for(int level=0;level<queues.size();level++) {
                Runnable pending=queues.get(level).get(key);
                if(pending==null) continue;
                if(level>=priority) return;
                queues.get(level).remove(key);decode=pending;break;
            }
            queues.get(priority).put(key,decode);
            trimPrefetch();
            if(!scheduled) { scheduled=true;start=true; }
        }
        if(start) executor.execute(this::decodeNext);
    }
    synchronized void visibleWindow(List<Integer> keys) {
        LinkedHashMap<Integer,Runnable> visible=queues.get(1), ordered=new LinkedHashMap<>();
        for(int key:keys) {
            Runnable pending=visible.remove(key);
            if(pending!=null) ordered.put(key,pending);
        }
        for(Map.Entry<Integer,Runnable> old:visible.entrySet()) queues.get(0).put(old.getKey(),old.getValue());
        queues.set(1,ordered);trimPrefetch();
    }
    private void trimPrefetch() {
        while(queues.get(0).size()>64) {
            java.util.Iterator<Integer> oldest=queues.get(0).keySet().iterator();oldest.next();oldest.remove();
        }
    }
    private void decodeNext() {
        Map.Entry<Integer,Runnable> task=null;
        synchronized(this) {
            for(int level=queues.size()-1;level>=0;level--) {
                if(queues.get(level).isEmpty()) continue;
                java.util.Iterator<Map.Entry<Integer,Runnable>> first=queues.get(level).entrySet().iterator();
                task=first.next();running=task.getKey();first.remove();break;
            }
            if(task==null) { scheduled=false;return; }
        }
        try { task.getValue().run(); }
        finally {
            synchronized(this) { running=null; }
            executor.execute(this::decodeNext);
        }
    }
}
