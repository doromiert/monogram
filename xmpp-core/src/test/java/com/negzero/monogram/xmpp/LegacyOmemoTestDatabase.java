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
    private final Map<String,Map<LegacyOmemoRecords.Retired,Map<String,byte[]>>> retiredRows=new HashMap<>();
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
        Map<LegacyOmemoRecords.Retired,Map<String,byte[]>> retired=new LinkedHashMap<>();
        for(Map.Entry<LegacyOmemoRecords.Retired,Map<String,byte[]>> branch:retiredRows.getOrDefault(scope,Collections.emptyMap()).entrySet()) {
            Map<String,byte[]> copy=new HashMap<>();for(Map.Entry<String,byte[]> row:branch.getValue().entrySet()) copy.put(row.getKey(),row.getValue().clone());retired.put(branch.getKey(),copy);
        }
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
            public List<Retired> retired() { active();return new ArrayList<>(retired.keySet()); }
            public byte[] loadRetired(Retired branch,int kind,int id) { active();byte[] value=retired.getOrDefault(branch,Collections.emptyMap()).get(kind+"\n"+id);return value==null?null:value.clone(); }
            public int[] retiredIds(Retired branch,int kind) {
                active();String prefix=kind+"\n";return retired.getOrDefault(branch,Collections.emptyMap()).keySet().stream().filter(key -> key.startsWith(prefix)).mapToInt(key -> Integer.parseInt(key.substring(prefix.length()))).sorted().toArray();
            }
            public void saveRetired(Retired branch,int kind,int id,byte[] value) throws IOException {
                active();if(failNextSave) { failNextSave=false;throw new IOException("Injected retired write failure"); }
                byte[] previous=loadRetired(branch,kind,id);
                if(kind!=SESSION && kind!=INCOMING && (value==null || previous!=null && !Arrays.equals(previous,value))) throw new IOException("Immutable retired record");
                Map<String,byte[]> rows=retired.computeIfAbsent(branch,ignored -> new HashMap<>());
                if(value==null) rows.remove(kind+"\n"+id);else rows.put(kind+"\n"+id,value.clone());
            }
            public void purge() { active();staged.clear();retired.clear(); }
            public void commit() throws IOException {
                active();if(failNextCommit) { failNextCommit=false;throw new IOException("Injected legacy commit failure"); }
                rows.put(scope,staged);retiredRows.put(scope,retired);close();
            }
            public void close() { if(closed) return;active();closed=true;lock.unlock(); }
        };
    }
}
