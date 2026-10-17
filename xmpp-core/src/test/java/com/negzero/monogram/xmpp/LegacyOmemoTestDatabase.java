package com.negzero.monogram.xmpp;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Explicit host-test factory shared across session reopen; never installed implicitly in production. */
public class LegacyOmemoTestDatabase implements LegacyOmemoRecords.Factory {
    private static final LegacyOmemoTestDatabase SHARED=new LegacyOmemoTestDatabase();
    public static LegacyOmemoTestDatabase shared() { return SHARED; }
    private final ReentrantLock lock=new ReentrantLock();
    private final Map<String,Map<String,byte[]>> rows=new HashMap<>();
    public boolean failNextSave,failNextCommit;
    @Override public SortedSet<Integer> localDevices(String owner) {
        lock.lock();try {
            SortedSet<Integer> ids=new TreeSet<>();
            for(String scope:rows.keySet()) if(scope.startsWith(owner+"\n")) ids.add(Integer.parseInt(scope.substring(owner.length()+1)));
            return ids;
        } finally { lock.unlock(); }
    }
    @Override public LegacyOmemoRecords begin(String owner,int device) throws Exception {
        lock.lock();String scope=owner+"\n"+device;
        Map<String,byte[]> staged=new HashMap<>();
        for(Map.Entry<String,byte[]> row:rows.getOrDefault(scope,Collections.emptyMap()).entrySet()) staged.put(row.getKey(),row.getValue().clone());
        return new LegacyOmemoRecords() {
            final Thread thread=Thread.currentThread();boolean closed;
            void active() { if(closed || Thread.currentThread()!=thread) throw new IllegalStateException("Test transaction closed or on another thread"); }
            String key(int kind,String peer,int id) { return kind+"\n"+peer+"\n"+id; }
            public byte[] load(int kind,String peer,int id) { active();byte[] value=staged.get(key(kind,peer,id));return value==null ? null : value.clone(); }
            public int[] ids(int kind,String peer) {
                active();String prefix=kind+"\n"+peer+"\n";SortedSet<Integer> ids=new TreeSet<>();
                for(String key:staged.keySet()) if(key.startsWith(prefix)) ids.add(Integer.parseInt(key.substring(prefix.length())));
                return ids.stream().mapToInt(Integer::intValue).toArray();
            }
            public void save(int kind,String peer,int id,byte[] value) throws IOException {
                active();if(failNextSave) { failNextSave=false;throw new IOException("Injected legacy write failure"); }
                if(value==null) staged.remove(key(kind,peer,id));else staged.put(key(kind,peer,id),value.clone());
            }
            public void purge() { active();staged.clear(); }
            public void commit() throws IOException {
                active();if(failNextCommit) { failNextCommit=false;throw new IOException("Injected legacy commit failure"); }
                rows.put(scope,staged);close();
            }
            public void close() { if(closed) return;active();closed=true;lock.unlock(); }
        };
    }
}
