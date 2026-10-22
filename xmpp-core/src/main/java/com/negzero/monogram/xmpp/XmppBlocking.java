// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.function.Consumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler;
import org.jivesoftware.smack.iqrequest.IQRequestHandler;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.StanzaError;
import org.jivesoftware.smackx.blocking.BlockingCommandManager;
import org.jivesoftware.smackx.blocking.element.*;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;

/** Server-enforced XEP-0191 blocking. Network operations require a worker thread. */
public final class XmppBlocking implements AutoCloseable {
    private final XMPPConnection connection;
    private final BlockingCommandManager manager;
    private final Consumer<List<String>> changed;
    private final IQRequestHandler blocks,unblocks;
    private final org.jivesoftware.smack.ConnectionListener lifecycle;
    private final Object stateLock=new Object();
    private final Set<String> current=new TreeSet<>();
    private final Map<String,Boolean> duringQuery=new LinkedHashMap<>();
    private boolean querying,clearedDuringQuery,closed,loaded;
    private long generation;

    public XmppBlocking(XMPPConnection connection,Consumer<List<String>> changed) {
        this.connection=connection; this.changed=changed;
        manager=BlockingCommandManager.getInstanceFor(connection);
        // Smack 4.4 accepts pushes without checking their sender and exposes its mutable cache.
        blocks=handler(BlockContactsIQ.ELEMENT,true);
        unblocks=handler(UnblockContactsIQ.ELEMENT,false);
        connection.registerIQRequestHandler(blocks); connection.registerIQRequestHandler(unblocks);
        lifecycle=new org.jivesoftware.smack.ConnectionListener() {
            @Override public void authenticated(XMPPConnection connection,boolean resumed) { if(!resumed) invalidate(); }
            @Override public void connectionClosed() { invalidate(); }
            @Override public void connectionClosedOnError(Exception error) { invalidate(); }
        };
        connection.addConnectionListener(lifecycle);
    }
    private IQRequestHandler handler(String element,boolean block) {
        return new AbstractIqRequestHandler(element,BlockingCommandManager.NAMESPACE,IQ.Type.set,IQRequestHandler.Mode.sync) {
            @Override public IQ handleIQRequest(IQ request) {
                Jid from=request.getFrom();
                if(!connection.isAuthenticated() || connection.getUser()==null || from!=null && !from.equals(connection.getUser().asBareJid()))
                    return IQ.createErrorResponse(request,StanzaError.getBuilder(StanzaError.Condition.not_allowed));
                List<Jid> jids=block ? ((BlockContactsIQ)request).getJids() : ((UnblockContactsIQ)request).getJids();
                if(block && (jids==null || jids.isEmpty()) || jids!=null && jids.contains(null))
                    return IQ.createErrorResponse(request,StanzaError.getBuilder(StanzaError.Condition.bad_request));
                List<String> snapshot;
                synchronized(stateLock) {
                    if(closed) return IQ.createResultIQ(request);
                    if(jids==null) {
                        current.clear();
                        if(querying) { clearedDuringQuery=true; duringQuery.clear(); }
                    } else for(Jid jid:jids) {
                        String address=jid.toString();
                        if(block) current.add(address); else current.remove(address);
                        if(querying) duringQuery.put(address,block);
                    }
                    snapshot=snapshotLocked();
                }
                notifyChanged(snapshot);
                return IQ.createResultIQ(request);
            }
        };
    }
    private List<String> snapshotLocked() { return Collections.unmodifiableList(new ArrayList<>(current)); }
    /** Null means this connection has not fetched an authoritative list yet. */
    public List<String> snapshot() { synchronized(stateLock) { return loaded && !closed ? snapshotLocked() : null; } }
    private void invalidate() { synchronized(stateLock) { generation++; loaded=false; current.clear(); duringQuery.clear(); } }
    private void notifyChanged(List<String> snapshot) {
        try { changed.accept(snapshot); } catch(RuntimeException ignored) { /* UI listeners cannot suppress the server acknowledgement. */ }
    }
    public synchronized List<String> list() throws Exception {
        long expected;
        synchronized(stateLock) {
            if(closed) throw new IllegalStateException("Blocking session closed");
            expected=generation; querying=true; clearedDuringQuery=false; duringQuery.clear();
        }
        try {
            BlockListIQ result=connection.createStanzaCollectorAndSend(new BlockListIQ()).nextResultOrThrow();
            List<String> snapshot;
            synchronized(stateLock) {
                if(closed || generation!=expected || !connection.isAuthenticated()) throw new IllegalStateException("Blocking connection changed");
                current.clear();
                if(!clearedDuringQuery) for(Jid jid:result.getBlockedJids()) current.add(jid.toString());
                // A push can follow the server's snapshot before this worker receives its IQ result.
                for(Map.Entry<String,Boolean> update:duringQuery.entrySet()) {
                    if(update.getValue()) current.add(update.getKey()); else current.remove(update.getKey());
                }
                querying=false; loaded=true; duringQuery.clear(); snapshot=snapshotLocked();
            }
            notifyChanged(snapshot); return snapshot;
        } finally {
            synchronized(stateLock) { querying=false; duringQuery.clear(); }
        }
    }
    public synchronized List<String> block(String address) throws Exception {
        Jid jid=address(address);
        manager.blockContacts(Collections.singletonList(jid)); return list();
    }
    public synchronized List<String> unblock(String address) throws Exception {
        Jid jid=address(address);
        manager.unblockContacts(Collections.singletonList(jid)); return list();
    }
    private Jid address(String address) throws Exception {
        if(address==null || address.isEmpty()) throw new IllegalArgumentException("A XMPP address is required");
        for(int i=0;i<address.length();i++) if(Character.isISOControl(address.charAt(i))) throw new IllegalArgumentException("Invalid XMPP address");
        synchronized(stateLock) { if(closed) throw new IllegalStateException("Blocking session closed"); }
        return JidCreate.from(address);
    }
    @Override public void close() {
        synchronized(stateLock) { closed=true; current.clear(); duringQuery.clear(); }
        connection.unregisterIQRequestHandler(blocks); connection.unregisterIQRequestHandler(unblocks);
        connection.removeConnectionListener(lifecycle);
    }
}
