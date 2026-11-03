// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.pep.*;
import org.jxmpp.jid.impl.JidCreate;
import org.signal.libsignal.protocol.SignalProtocolAddress;

/** Separate opt-in experimental protocol. Blocking operations belong on the existing crypto worker. */
public final class XmppPqOmemo implements AutoCloseable {
    public static final String NS = PqOmemoBundle.NS;
    public final PqOmemoDevice device;
    private final XMPPConnection connection;
    private final PqOmemoPep pep;
    private final SecureRandom random = new SecureRandom();
    private final Map<String,PqOmemoBundle> bundles = new ConcurrentHashMap<>();
    private final Map<String,Long> refreshed = new ConcurrentHashMap<>();
    private final Map<String,Set<Integer>> deviceLists = new ConcurrentHashMap<>();
    private final Map<String,Long> listsRefreshed = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService ownRepairs = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"PQ device announcement"); t.setDaemon(true); return t; });
    private final java.util.concurrent.atomic.AtomicBoolean repairPending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Exception lastError;
    private final PepListener listener;
    private final Consumer<String> changed;
    private volatile boolean ready, closed;

    public XmppPqOmemo(XMPPConnection connection,PqOmemoDevice device,Consumer<String> changed) {
        this.connection = connection; this.device = device; this.changed = changed; pep = new PqOmemoPep(connection,device);
        FileShare.initialize(); MessageCorrection.initialize(); MessageReactions.initialize(); MessageRetraction.initialize(); MessageMarkup.initialize();
        ProviderManager.addExtensionProvider("encrypted",NS,StandardExtensionElementProvider.INSTANCE);
        listener = (from,event,message) -> {
            String node = event.getEvent().getNode();
            if (closed || !PqOmemoPep.DEVICES.equals(node) && !PqOmemoPep.BUNDLES.equals(node)) return;
            String peer = from.toString();
            deviceLists.remove(peer); listsRefreshed.remove(peer);
            refreshed.keySet().removeIf(key -> key.startsWith(peer + "\n"));
            bundles.keySet().removeIf(key -> key.startsWith(peer + "\n"));
            if (ready && peer.equals(device.jid) && repairPending.compareAndSet(false,true)) {
                try { ownRepairs.execute(() -> {
                    try { synchronized(XmppPqOmemo.this) { if (ready()) pep.announce(); } }
                    catch(Exception error) { lastError = error; }
                    finally { repairPending.set(false); }
                }); } catch(java.util.concurrent.RejectedExecutionException stopped) { repairPending.set(false); }
            }
            changed.accept(peer);
        };
        PepManager.getInstanceFor(connection).addPepListener(listener);
    }
    public boolean ready() { return ready && !closed; }
    public synchronized void initialize() throws Exception {
        if (closed) throw new IllegalStateException("PQ session closed");
        ready = false; bundles.clear(); refreshed.clear(); deviceLists.clear(); listsRefreshed.clear();
        pep.initialize(device.prepare());
        ServiceDiscoveryManager disco = ServiceDiscoveryManager.getInstanceFor(connection);
        for (String feature : new String[]{NS,PqOmemoPep.DEVICES + "+notify",PqOmemoPep.BUNDLES + "+notify",NS + ":pqxdh",NS + ":spqr",NS + ":bundle-v4",NS + ":kex-auth"}) disco.addFeature(feature);
        ready = true; connection.sendStanza(new Presence(Presence.Type.available));
    }
    private PqOmemoBundle bundle(String peer,int id,boolean force) throws Exception {
        String key = peer + "\n" + id; Long time = refreshed.get(key);
        PqOmemoBundle cached = bundles.get(key);
        if (!force && cached != null && time != null && System.nanoTime() - time < java.util.concurrent.TimeUnit.MINUTES.toNanos(1)) return cached;
        StandardExtensionElement xml = pep.item(peer,PqOmemoPep.BUNDLES,Integer.toString(id));
        if (xml == null) throw new java.security.GeneralSecurityException("No experimental PQ bundle for device " + id);
        PqOmemoBundle found = PqOmemoBundle.parse(xml); device.observe(peer,id,found);
        bundles.put(key,found); refreshed.put(key,System.nanoTime()); return found;
    }
    public synchronized List<PqOmemoDevice.Fingerprint> discover(String peer) throws Exception {
        requireReady(); peer = PqOmemoDevice.bare(peer);
        Set<Integer> active = devices(peer,true);
        for (int id : new TreeSet<>(active)) if (!peer.equals(device.jid) || id != device.id) bundle(peer,id,true);
        List<PqOmemoDevice.Fingerprint> result = new ArrayList<>();
        for (PqOmemoDevice.Fingerprint key : device.fingerprints(peer)) result.add(key.withActive(active.contains(key.device)));
        return result;
    }
    private Set<Integer> devices(String peer,boolean force) throws Exception {
        Long time = listsRefreshed.get(peer); Set<Integer> cached = deviceLists.get(peer);
        if (!force && cached != null && time != null && System.nanoTime() - time < java.util.concurrent.TimeUnit.MINUTES.toNanos(1)) return cached;
        Set<Integer> found = new LinkedHashSet<>(pep.devices(peer));
        if (found.size() > 128) throw new java.security.GeneralSecurityException("Experimental PQ device list exceeds its supported limit");
        deviceLists.put(peer,found); listsRefreshed.put(peer,System.nanoTime()); return found;
    }
    private List<PqOmemoMessage.Target> targets(Collection<String> peers) throws Exception {
        List<PqOmemoMessage.Target> result = new ArrayList<>();
        for (String peer : new LinkedHashSet<>(peers)) {
            peer = PqOmemoDevice.bare(peer); int count = 0;
            for (int id : new TreeSet<>(devices(peer,false))) if (!peer.equals(device.jid) || id != device.id) {
                SignalProtocolAddress address = PqOmemoDevice.remote(peer,id);
                try (PqOmemoRecords tx = device.records.begin()) {
                    if (tx.get("blocked/" + PqOmemoSignalStore.peer(address)) != null) continue;
                }
                result.add(new PqOmemoMessage.Target(peer,id,bundle(peer,id,false))); count++;
            }
            if (count == 0 && !peer.equals(device.jid)) throw new java.security.GeneralSecurityException("Contact has no unblocked experimental PQ devices");
        }
        return result;
    }
    public synchronized Message encrypt(Message plaintext) throws Exception {
        Omemo2Payload.direct(plaintext);
        return encrypt(plaintext,Arrays.asList(plaintext.getTo().asBareJid().toString(),device.jid));
    }
    synchronized Message encrypt(Message plaintext,Collection<String> recipients) throws Exception {
        requireReady();
        Message wire = new Message(plaintext.getTo(),plaintext.getType()); wire.setFrom(plaintext.getFrom()); wire.setStanzaId(plaintext.getStanzaId());
        wire.addExtension(PqOmemoMessage.encrypt(device,plaintext,targets(recipients),random));
        wire.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build()); return wire;
    }
    public static final class Received {
        public final Message outer,content;
        public final String fingerprint,digest,archiveId;
        public final long timestamp;
        Received(PqOmemoJournal.Entry entry) {
            outer = entry.outer; content = entry.content; fingerprint = entry.fingerprint;
            digest = entry.digest; timestamp = entry.timestamp; archiveId = entry.archive;
        }
    }
    public synchronized Received receive(Message outer,String sender,String destination,long timestamp,String archive) throws Exception {
        requireReady();
        String digest = PqOmemoJournal.digest(outer,sender,destination);
        try (PqOmemoRecords tx = device.records.begin()) {
            PqOmemoJournal.Entry cached = PqOmemoJournal.find(tx,digest);
            if (cached != null) return new Received(cached);
        }
        final PqOmemoJournal.Entry[] saved = new PqOmemoJournal.Entry[1];
        PqOmemoMessage.decrypt(device,outer,sender,destination,timestamp,(tx,content) -> {
            StandardExtensionElement root = outer.getExtension("encrypted",NS), header = PqOmemoBundle.one(root,"header");
            int sid = Integer.parseInt(header.getAttributeValue("sid"));
            byte[] hybrid = tx.get(PqOmemoSignalStore.trust(PqOmemoDevice.remote(sender,sid)));
            if (hybrid == null || hybrid.length != 2625) throw PqOmemoBundle.invalid();
            String fingerprint = PqOmemoBundle.fingerprint(Arrays.copyOf(hybrid,33),Arrays.copyOfRange(hybrid,33,hybrid.length));
            saved[0] = new PqOmemoJournal.Entry(digest,fingerprint,outer,content,timestamp,archive);
            PqOmemoJournal.save(tx,saved[0]);
        });
        return new Received(saved[0]);
    }
    public synchronized List<Received> pending() throws Exception {
        List<Received> pending = new ArrayList<>();
        try (PqOmemoRecords tx = device.records.begin()) { for (PqOmemoJournal.Entry entry : PqOmemoJournal.pending(tx)) pending.add(new Received(entry)); }
        return pending;
    }
    /** Call only after durable message-store acceptance. Reconnects retain everything not accepted. */
    public synchronized void accepted(Received received) {
        if (received == null || !received.digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid PQ journal token");
        try (PqOmemoRecords tx = device.records.begin()) { tx.put("incoming/" + received.digest,null); tx.commit(); }
    }
    public String ownFingerprint() throws Exception {
        try (PqOmemoRecords tx = device.records.begin()) { return PqOmemoBundle.fingerprint(device.store(tx).getIdentityKeyPair().getPublicKey().serialize(),device.pq(tx).getPublicKey().serialize()); }
    }
    public synchronized void replenish() throws Exception { requireReady(); pep.publishBundle(device.prepare()); }
    public synchronized void replenishIfNeeded() throws Exception {
        boolean shortfall;
        try (PqOmemoRecords tx = device.records.begin()) {
            int kem = 0; for (String key : tx.keys("kem/")) if (tx.get("last-resort/" + key.substring(4)) == null) kem++;
            shortfall = tx.keys("prekey/").size() < 16 || kem < 16;
        }
        if (shortfall) replenish();
    }
    private void requireReady() { if (!ready()) throw new IllegalStateException("Experimental PQ OMEMO is not initialized"); }
    @Override public synchronized void close() { closed = true; ready = false; ownRepairs.shutdownNow(); PepManager.getInstanceFor(connection).removePepListener(listener); bundles.clear(); refreshed.clear(); deviceLists.clear(); listsRefreshed.clear(); }
}
