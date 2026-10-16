package com.negzero.monogram.xmpp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Private disposable transaction fixture; Android tests separately exercise the real SQLite adapter. */
final class Omemo2TestDatabase implements Omemo2Records.Factory {
    private Map<String,byte[]> records = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    boolean failCommit;
    @Override public Omemo2Records begin() {
        lock.lock(); Map<String,byte[]> pending = new HashMap<>(records);
        return new Omemo2Records() {
            boolean closed;
            void active() { if (closed || !lock.isHeldByCurrentThread()) throw new IllegalStateException(); }
            String key(int kind,String peer,int id) { active(); return kind+"\n"+peer+"\n"+id; }
            @Override public byte[] load(int kind,String peer,int id) { byte[] value = pending.get(key(kind,peer,id)); return value == null ? null : value.clone(); }
            @Override public int[] ids(int kind) { active(); String prefix = kind+"\n\n"; return pending.keySet().stream().filter(k -> k.startsWith(prefix)).mapToInt(k -> Integer.parseInt(k.substring(prefix.length()))).sorted().toArray(); }
            @Override public void save(int kind,String peer,int id,byte[] value) {
                String key = key(kind,peer,id);
                if (kind == TRUSTED && !java.util.Arrays.equals(load(kind,peer,id),value)) {
                    pending.remove(key(SESSION,peer,id)); pending.remove(key(SESSION_IDENTITY,peer,id));
                }
                if (value == null) pending.remove(key); else pending.put(key,value.clone());
            }
            @Override public void commit() throws Exception { active(); if (failCommit) throw new java.io.IOException("Test commit failure"); records = pending; close(); }
            @Override public void close() { if (!closed) { active(); closed = true; lock.unlock(); } }
        };
    }
}
