// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;

/** Session-local, shared fingerprint discovery. Display caching never grants trust. */
public final class XmppFingerprints implements AutoCloseable {
    public interface Source { Map<OmemoDevice,OmemoFingerprint> fetch(String address,boolean modern) throws Exception; }
    public static final class Partial extends Exception {
        final Map<OmemoDevice,OmemoFingerprint> identities;
        public Partial(Map<OmemoDevice,OmemoFingerprint> identities) { this.identities=identities; }
    }
    public static final class Snapshot {
        public final Map<OmemoDevice,OmemoFingerprint> identities;
        public final int completed,total,failed;
        public final boolean complete;
        Snapshot(Map<OmemoDevice,OmemoFingerprint> identities,int completed,int total,int failed,boolean complete) {
            this.identities=Collections.unmodifiableMap(identities); this.completed=completed; this.total=total; this.failed=failed; this.complete=complete;
        }
    }
    private static final class Cached {
        final long time=System.nanoTime(); final Map<OmemoDevice,OmemoFingerprint> identities;
        final boolean failed;
        Cached(Map<OmemoDevice,OmemoFingerprint> identities,boolean failed) { this.identities=Collections.unmodifiableMap(new LinkedHashMap<>(identities)); this.failed=failed; }
    }
    private final Source source;
    private final long timeoutMillis;
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r -> { Thread t=new Thread(r,"Monogram fingerprints"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"Monogram fingerprint deadlines"); t.setDaemon(true); return t; });
    private final Map<String,Cached> cache=new HashMap<>();
    private final Map<String,CompletableFuture<Cached>> pending=new HashMap<>();
    public XmppFingerprints(Source source) { this(source,20000); }
    XmppFingerprints(Source source,long timeoutMillis) { this.source=source; this.timeoutMillis=timeoutMillis; }
    private static String key(String address,boolean modern) { return (modern ? "modern:" : "legacy:")+address; }
    private synchronized CompletableFuture<Cached> fetch(String address,boolean modern,boolean force) {
        String key=key(address,modern); Cached cached=cache.get(key);
        if(!force && cached!=null && !cached.failed && System.nanoTime()-cached.time<TimeUnit.MINUTES.toNanos(1)) return CompletableFuture.completedFuture(cached);
        CompletableFuture<Cached> existing=pending.get(key); if(existing!=null) return existing;
        CompletableFuture<Cached> result=new CompletableFuture<>(); pending.put(key,result);
        workers.execute(() -> {
            try {
                Cached found;
                try { found=new Cached(source.fetch(address,modern),false); }
                catch(Partial partial) { found=new Cached(partial.identities,true); }
                synchronized(this) { cache.put(key,found); pending.remove(key,result); }
                result.complete(found);
            } catch(Exception failed) { synchronized(this) { pending.remove(key,result); } result.completeExceptionally(failed); }
            finally { synchronized(this) { pending.remove(key,result); } }
        });
        return result;
    }
    public final class Request implements AutoCloseable {
        private final Map<String,Cached> results=new LinkedHashMap<>();
        private final Consumer<Snapshot> listener;
        private final int total;
        private int completed,failed;
        private boolean finished;
        private ScheduledFuture<?> deadline;
        Request(Set<String> addresses,boolean modern,Consumer<Snapshot> listener) {
            this.total=addresses.size(); this.listener=listener;
            synchronized(XmppFingerprints.this) { for(String address:addresses) { Cached old=cache.get(key(address,modern)); if(old!=null) results.put(address,old); } }
        }
        private void publish(boolean complete) {
            Map<OmemoDevice,OmemoFingerprint> all=new LinkedHashMap<>(); for(Cached result:results.values()) all.putAll(result.identities);
            listener.accept(new Snapshot(all,completed,total,failed,complete));
        }
        private synchronized void received(String address,Cached result,Throwable error) {
            if(finished) return;
            completed++; if(error!=null) failed++; else { results.put(address,result); if(result.failed) failed++; }
            finished=completed==total; if(finished && deadline!=null) deadline.cancel(false);
            publish(finished);
        }
        private synchronized void timeout() { if(!finished) { failed+=total-completed; finished=true; publish(true); } }
        @Override public synchronized void close() { finished=true; if(deadline!=null) deadline.cancel(false); }
    }
    public Request load(Collection<String> addresses,boolean modern,boolean force,Consumer<Snapshot> listener) {
        Set<String> unique=new LinkedHashSet<>(addresses); Request request=new Request(unique,modern,listener);
        synchronized(request) {
            request.publish(unique.isEmpty());
            if(unique.isEmpty()) { request.finished=true; return request; }
            request.deadline=deadlines.schedule(request::timeout,timeoutMillis,TimeUnit.MILLISECONDS);
        }
        for(String address:unique) fetch(address,modern,force).whenComplete((result,error) -> request.received(address,result,error));
        return request;
    }
    public ScheduledFuture<?> afterTimeout(Runnable action) { return deadlines.schedule(action,timeoutMillis,TimeUnit.MILLISECONDS); }
    @Override public synchronized void close() { workers.shutdownNow(); deadlines.shutdownNow(); pending.clear(); cache.clear(); }
}
