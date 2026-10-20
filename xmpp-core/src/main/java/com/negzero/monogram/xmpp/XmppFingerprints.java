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
    public interface ProgressSource extends Source {
        Map<OmemoDevice,OmemoFingerprint> fetch(String address,boolean modern,Consumer<Map<OmemoDevice,OmemoFingerprint>> progress) throws Exception;
        @Override default Map<OmemoDevice,OmemoFingerprint> fetch(String address,boolean modern) throws Exception { return fetch(address,modern,ignored -> {}); }
    }
    public static final class Partial extends Exception {
        final Map<OmemoDevice,OmemoFingerprint> identities;
        public Partial(Map<OmemoDevice,OmemoFingerprint> identities) { this(identities,"One or more device bundles are unavailable"); }
        public Partial(Map<OmemoDevice,OmemoFingerprint> identities,String problem) { super(problem); this.identities=identities; }
    }
    public static final class Snapshot {
        public final Map<OmemoDevice,OmemoFingerprint> identities;
        public final int completed,total,failed;
        public final boolean complete;
        public final Map<String,String> failures;
        Snapshot(Map<OmemoDevice,OmemoFingerprint> identities,int completed,int total,int failed,boolean complete,Map<String,String> failures) {
            this.failures=Collections.unmodifiableMap(new LinkedHashMap<>(failures));
            this.identities=Collections.unmodifiableMap(identities); this.completed=completed; this.total=total; this.failed=failed; this.complete=complete;
        }
    }
    private static final class Cached {
        final long time=System.nanoTime(); final Map<OmemoDevice,OmemoFingerprint> identities;
        final boolean failed;
        final String problem;
        Cached(Map<OmemoDevice,OmemoFingerprint> identities,boolean failed,String problem) { this.identities=Collections.unmodifiableMap(new LinkedHashMap<>(identities)); this.failed=failed; this.problem=problem; }
    }
    private final class Fetch {
        final CompletableFuture<Cached> result=new CompletableFuture<>();
        final Set<Request> watchers=new HashSet<>();
        FutureTask<Void> task;
    }
    private boolean closed;
    private final Source source;
    private final long timeoutMillis;
    private final ThreadPoolExecutor workers=(ThreadPoolExecutor)Executors.newFixedThreadPool(4,r -> { Thread t=new Thread(r,"Monogram fingerprints"); t.setDaemon(true); return t; });
    private final ExecutorService visibleWorkers=Executors.newFixedThreadPool(2,r -> { Thread t=new Thread(r,"Monogram visible fingerprints"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"Monogram fingerprint deadlines"); t.setDaemon(true); return t; });
    private final Map<String,Cached> cache=new HashMap<>();
    private final Map<String,Fetch> pending=new HashMap<>();
    public XmppFingerprints(Source source) { this(source,20000); }
    XmppFingerprints(Source source,long timeoutMillis) { this.source=source; this.timeoutMillis=timeoutMillis; }
    private static String key(String address,boolean modern) { return (modern ? "modern:" : "legacy:")+address; }
    private synchronized CompletableFuture<Cached> fetch(String address,boolean modern,boolean force,Request request) {
        if(closed) { CompletableFuture<Cached> failed=new CompletableFuture<>(); failed.completeExceptionally(new CancellationException()); return failed; }
        String key=key(address,modern); Cached cached=cache.get(key);
        if(!force && cached!=null && !cached.failed && System.nanoTime()-cached.time<TimeUnit.MINUTES.toNanos(1)) return CompletableFuture.completedFuture(cached);
        Fetch existing=pending.get(key);
        if(existing!=null) {
            existing.watchers.add(request);
            if(request.interactive && workers.getQueue().remove(existing.task)) visibleWorkers.execute(existing.task);
            return existing.result;
        }
        Fetch fetch=new Fetch(); fetch.watchers.add(request); pending.put(key,fetch);
        fetch.task=new FutureTask<>(() -> {
            try {
                Cached found;
                try {
                    Map<OmemoDevice,OmemoFingerprint> ids=source instanceof ProgressSource
                            ? ((ProgressSource)source).fetch(address,modern,partial -> progress(key,address,fetch,partial)) : source.fetch(address,modern);
                    found=new Cached(ids,false,null);
                } catch(Partial partial) { found=new Cached(partial.identities,true,partial.getMessage()); }
                synchronized(this) { if(!closed && pending.get(key)==fetch) cache.put(key,found); pending.remove(key,fetch); fetch.watchers.clear(); }
                fetch.result.complete(found);
            } catch(Exception failed) { synchronized(this) { pending.remove(key,fetch); fetch.watchers.clear(); } fetch.result.completeExceptionally(failed); }
        },null);
        (request.interactive ? visibleWorkers : workers).execute(fetch.task);
        return fetch.result;
    }
    private void progress(String key,String address,Fetch fetch,Map<OmemoDevice,OmemoFingerprint> identities) {
        Cached value=new Cached(identities,true,"Device discovery is still in progress"); List<Request> listeners;
        synchronized(this) { if(pending.get(key)!=fetch) return; cache.put(key,value); listeners=new ArrayList<>(fetch.watchers); }
        for(Request request:listeners) request.progress(address,value);
    }
    private void detach(Request request) {
        List<Fetch> abandoned=new ArrayList<>();
        synchronized(this) {
            Iterator<Map.Entry<String,Fetch>> entries=pending.entrySet().iterator();
            while(entries.hasNext()) {
                Fetch fetch=entries.next().getValue(); fetch.watchers.remove(request);
                if(fetch.watchers.isEmpty()) { entries.remove(); if(fetch.task!=null) fetch.task.cancel(true); abandoned.add(fetch); }
            }
        }
        for(Fetch fetch:abandoned) fetch.result.completeExceptionally(new CancellationException());
    }
    public final class Request implements AutoCloseable {
        private final Map<String,Cached> results=new LinkedHashMap<>();
        private final Consumer<Snapshot> listener;
        private final Set<String> addresses,done=new HashSet<>();
        private final Map<String,String> failures=new LinkedHashMap<>();
        private final int total;
        private final boolean interactive;
        private int completed,failed;
        private boolean finished;
        private ScheduledFuture<?> deadline;
        Request(Set<String> addresses,boolean modern,Consumer<Snapshot> listener,boolean interactive) {
            this.interactive=interactive; this.addresses=addresses; this.total=addresses.size(); this.listener=listener;
            synchronized(XmppFingerprints.this) { for(String address:addresses) { Cached old=cache.get(key(address,modern)); if(old!=null) results.put(address,old); } }
        }
        private void publish(boolean complete) {
            Map<OmemoDevice,OmemoFingerprint> all=new LinkedHashMap<>(); for(Cached result:results.values()) all.putAll(result.identities);
            Map<String,String> problems=new LinkedHashMap<>();
            for(Map.Entry<String,Cached> entry:results.entrySet()) if(entry.getValue().problem!=null) problems.put(entry.getKey(),entry.getValue().problem);
            problems.putAll(failures);
            listener.accept(new Snapshot(all,completed,total,failed,complete,problems));
        }
        private synchronized void received(String address,Cached result,Throwable error) {
            if(finished) return;
            done.add(address); completed++;
            if(error!=null) { failed++; failures.put(address,error instanceof CancellationException ? "XMPP session ended; refresh after reconnecting" : XmppRequestException.describe(error)); }
            else { results.put(address,result); if(result.failed) { failed++; failures.put(address,result.problem); } }
            finished=completed==total; if(finished && deadline!=null) deadline.cancel(false);
            publish(finished);
        }
        private synchronized void progress(String address,Cached result) { if(!finished) { results.put(address,result); publish(false); } }
        private void timeout() {
            synchronized(this) { if(!finished) {
                failed+=total-completed; finished=true;
                for(String address:addresses) if(!done.contains(address)) failures.put(address,"Fingerprint lookup timed out");
                publish(true);
            } }
            detach(this);
        }
        @Override public void close() {
            synchronized(this) { finished=true; if(deadline!=null) deadline.cancel(false); }
            detach(this);
        }
    }
    public Request load(Collection<String> addresses,boolean modern,boolean force,Consumer<Snapshot> listener) {
        return load(addresses,modern,force,listener,false);
    }
    public Request loadInteractive(Collection<String> addresses,boolean modern,boolean force,Consumer<Snapshot> listener) {
        return load(addresses,modern,force,listener,true);
    }
    private Request load(Collection<String> addresses,boolean modern,boolean force,Consumer<Snapshot> listener,boolean interactive) {
        Set<String> unique=new LinkedHashSet<>(addresses); Request request=new Request(unique,modern,listener,interactive);
        synchronized(request) {
            request.publish(unique.isEmpty());
            if(unique.isEmpty()) { request.finished=true; return request; }
            request.deadline=deadlines.schedule(request::timeout,timeoutMillis,TimeUnit.MILLISECONDS);
        }
        for(String address:unique) fetch(address,modern,force,request).whenComplete((result,error) -> request.received(address,result,error));
        return request;
    }
    public ScheduledFuture<?> afterTimeout(Runnable action) { return deadlines.schedule(action,timeoutMillis,TimeUnit.MILLISECONDS); }
    @Override public void close() {
        List<Fetch> abandoned;
        synchronized(this) { closed=true; workers.shutdownNow(); visibleWorkers.shutdownNow(); deadlines.shutdownNow(); abandoned=new ArrayList<>(pending.values()); pending.clear(); cache.clear(); }
        for(Fetch fetch:abandoned) fetch.result.completeExceptionally(new CancellationException());
    }
}
