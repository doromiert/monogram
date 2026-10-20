// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smackx.carbons.packet.CarbonExtension;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.exceptions.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.*;
import org.jxmpp.jid.BareJid;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.*;

/** Only incoming dispatch/commit changes; the installed Signal crypto and identity checks remain authoritative. */
public final class MonogramLegacyOmemoService extends OmemoService<IdentityKeyPair,IdentityKey,PreKeyRecord,
        SignedPreKeyRecord,SessionRecord,SignalProtocolAddress,ECPublicKey,PreKeyBundle,SessionCipher> {
    public interface Receiver {
        LegacyIncomingJournal.Context context(Message stanza,boolean live) throws Exception;
        void received(LegacyIncomingJournal.Received received);
        long capacityRevision();
        void capacityChanged();
        void blocked(LegacyIncomingJournal.Context context,long revision);
        void failed(Exception error);
    }
    private static MonogramLegacyOmemoService instance;
    private final MonogramSignalStore records;
    private static final class Registration {
        final LegacyIncomingJournal journal;final Receiver receiver;final List<MonogramLegacyReceiveOnly> recovery;
        IdentityTrust trust;volatile Map<LegacyOmemoRecords.Retired,MonogramLegacyReceiveOnly> retired=Collections.emptyMap();
        List<MonogramLegacyReceiveOnly> archives() { List<MonogramLegacyReceiveOnly> result=new ArrayList<>(recovery);result.addAll(retired.values());return result; }
        Registration(LegacyIncomingJournal journal,Receiver receiver) { this(journal,receiver,Collections.<MonogramLegacyReceiveOnly>emptyList()); }
        Registration(LegacyIncomingJournal journal,Receiver receiver,List<MonogramLegacyReceiveOnly> recovery) { this.journal=journal;this.receiver=receiver;this.recovery=Collections.unmodifiableList(new ArrayList<>(recovery)); }
    }
    private final Map<OmemoManager,Registration> receivers=Collections.synchronizedMap(new WeakHashMap<>());
    private MonogramLegacyOmemoService(MonogramSignalStore store) { records=store;setOmemoStoreBackend(store); }
    public static synchronized void setup(MonogramSignalStore store) {
        if(instance!=null) throw new IllegalStateException("Legacy OMEMO is already initialized");
        instance=new MonogramLegacyOmemoService(store);setInstance(instance);
    }
    public static MonogramLegacyOmemoService instance() {
        if(instance==null) throw new IllegalStateException("Initialize durable legacy OMEMO first");return instance;
    }
    public MonogramSignalStore records() { return records; }
    public void register(OmemoManager manager,LegacyIncomingJournal journal,Receiver receiver) { receivers.put(manager,new Registration(journal,receiver)); }
    public void register(OmemoManager manager,LegacyIncomingJournal journal,Receiver receiver,IdentityTrust trust) throws Exception {
        Registration registration=new Registration(journal,receiver);registration.trust=trust;
        registration.retired=MonogramLegacyRetired.load(records,journal.own,trust,Collections.emptyMap());
        receivers.put(manager,registration);
    }
    public void refreshRetired(OmemoManager manager) throws Exception {
        synchronized(manager) {
            Registration registration=receivers.get(manager);
            if(registration==null || registration.trust==null) throw new IllegalStateException("Active legacy trust registration missing");
            registration.retired=MonogramLegacyRetired.load(records,registration.journal.own,registration.trust,registration.retired);
        }
    }
    public List<LegacyIncomingJournal.Received> retiredPending(OmemoManager manager) throws Exception {
        Registration registration=receivers.get(manager);List<LegacyIncomingJournal.Received> result=new ArrayList<>();
        if(registration!=null) for(MonogramLegacyReceiveOnly lineage:registration.retired.values()) result.addAll(lineage.journal().pending());
        return Collections.unmodifiableList(result);
    }
    /** Cold migration startup only; never swap the singleton's backend or cached active ratchets. */
    public void configureRecovery(OmemoManager manager,MonogramLegacyReceiveOnly recovery) throws Exception {
        configureRecoveries(manager,recovery==null?Collections.<MonogramLegacyReceiveOnly>emptyList():Collections.singletonList(recovery));
    }
    public void configureRecoveries(OmemoManager manager,List<MonogramLegacyReceiveOnly> recovery) throws Exception {
        synchronized(manager) {
            if(manager.getConnection().isConnected()) throw new IllegalStateException("Configure recovery before connecting");
            Registration registration=receivers.get(manager);
            if(registration==null) throw new IllegalStateException("Register active incoming journal first");
            List<MonogramLegacyReceiveOnly> expanded=new ArrayList<>();
            for(MonogramLegacyReceiveOnly archive:recovery) { archive.requireActive(records,registration.journal);expanded.add(archive);expanded.addAll(archive.retiredBranches()); }
            Registration updated=new Registration(registration.journal,registration.receiver,expanded);updated.trust=registration.trust;updated.retired=registration.retired;
            receivers.put(manager,updated);
        }
    }
    public List<LegacyIncomingJournal.Received> pending(OmemoManager manager,LegacyIncomingJournal journal) throws Exception {
        List<LegacyIncomingJournal.Received> result=new ArrayList<>(journal.pending());
        Registration registration=receivers.get(manager);
        if(registration!=null && registration.journal==journal) for(MonogramLegacyReceiveOnly archive:registration.archives()) result.addAll(archive.journal().pending());
        return Collections.unmodifiableList(result);
    }
    public LegacyIncomingJournal source(OmemoManager manager,LegacyIncomingJournal active,LegacyIncomingJournal.Received received) {
        if(received==null || received.journal==active) return active;
        Registration registration=receivers.get(manager);
        if(registration!=null && registration.journal==active) for(MonogramLegacyReceiveOnly archive:registration.archives()) if(received.journal==archive.journal()) return received.journal;
        throw new IllegalArgumentException("Legacy incoming frame belongs to another journal");
    }
    public void unregister(OmemoManager manager) { receivers.remove(manager); }
    @Override protected MonogramSignalStore createDefaultOmemoStoreBackend() { return records; }
    @Override protected MonogramSignalRatchet instantiateOmemoRatchet(OmemoManager manager,
            OmemoStore<IdentityKeyPair,IdentityKey,PreKeyRecord,SignedPreKeyRecord,SessionRecord,SignalProtocolAddress,ECPublicKey,PreKeyBundle,SessionCipher> store) {
        return new MonogramSignalRatchet(manager,records);
    }
    @Override protected void processBundle(OmemoManager manager,PreKeyBundle bundle,OmemoDevice device) throws CorruptedOmemoKeyException {
        SignalOmemoStoreConnector connector=new SignalOmemoStoreConnector(manager,records);
        try { new SessionBuilder(connector,connector,connector,connector,SignalOmemoStoreConnector.asAddress(device)).process(bundle); }
        catch(InvalidKeyException invalid) { throw new CorruptedOmemoKeyException(invalid); }
        catch(UntrustedIdentityException impossible) { throw new AssertionError("Signal internal trust must remain disabled",impossible); }
    }
    @Override public void onOmemoMessageStanzaReceived(Stanza stanza,OmemoManager.LoggedInOmemoManager guard) throws IOException {
        if(stanza instanceof Message) dispatch(guard.get(),(Message)stanza,true);
    }
    @Override public void onOmemoCarbonCopyReceived(CarbonExtension.Direction direction,Message carbon,Message wrapping,
                                                    OmemoManager.LoggedInOmemoManager guard) throws IOException {
        OmemoManager manager=guard.get();
        if(manager.getConnection().getUser()==null || !manager.getConnection().getUser().asBareJid().equals(wrapping.getFrom())) return;
        dispatch(manager,carbon,false);
    }
    private void dispatch(OmemoManager manager,Message stanza,boolean live) throws IOException {
        Registration registration=receivers.get(manager);if(registration==null) return;
        LegacyIncomingJournal.Context context;
        try { context=registration.receiver.context(stanza,live); }
        catch(Exception invalid) { registration.receiver.failed(invalid);return; }
        if(context!=null) retry(manager,context);
    }
    /** Retry the captured route, never today's room nickname mapping. No record transaction spans network work. */
    public boolean retry(OmemoManager manager,LegacyIncomingJournal.Context context) {
        Registration registration=receivers.get(manager);if(registration==null) return true;
        LegacyIncomingJournal.Received result;long revision=registration.receiver.capacityRevision();
        try { result=receive(manager,registration.journal,context); }
        catch(LegacyIncomingJournal.FullException full) { registration.receiver.blocked(context,revision);return false; }
        catch(NoRawSessionException missing) {
            registration.receiver.failed(missing);
            if(receivers.get(manager)==registration && OmemoConfiguration.getRepairBrokenSessionsWithPreKeyMessages()) repair(manager,missing.getDeviceWithoutSession());
            return true;
        }
        catch(Exception failure) { registration.receiver.failed(failure);return true; }
        if(receivers.get(manager)!=registration) return true;
        if(result.token!=null) registration.receiver.received(result);
        return true;
    }
    public LegacyIncomingJournal.Received receive(OmemoManager manager,LegacyIncomingJournal journal,LegacyIncomingJournal.Context context) throws Exception {
        synchronized(manager) {
            if(!manager.getConnection().isAuthenticated() || manager.getConnection().getUser()==null
                    || !journal.own.getJid().equals(manager.getConnection().getUser().asBareJid()) || !journal.own.equals(manager.getOwnDevice()))
                throw new IOException("Legacy incoming account/connection changed");
            OmemoManager.LoggedInOmemoManager guard=new OmemoManager.LoggedInOmemoManager(manager);
            OmemoElement element=context.outer.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
            if(element==null || context.outer.hasExtension("encrypted",XmppOmemo2.NS)) throw new IOException("Invalid legacy incoming envelope");
            byte[] digest=journal.digest(context);
            Registration registration=receivers.get(manager);
            List<MonogramLegacyReceiveOnly> recovery=registration!=null && registration.journal==journal ? registration.archives() : Collections.emptyList();
            if(recovery.isEmpty()) return receiveActive(manager,journal,context,guard,digest);
            Map<Integer,OmemoKeyElement> keys=new HashMap<>();
            Set<Integer> ownIds=new HashSet<>();ownIds.add(journal.own.getDeviceId());for(MonogramLegacyReceiveOnly archive:recovery) ownIds.add(archive.deviceId());
            for(OmemoKeyElement key:element.getHeader().getKeys()) if(ownIds.contains(key.getId()) && keys.put(key.getId(),key)!=null)
                throw new IOException("Migration receive requires one key per local recipient device");
            List<MonogramLegacyRouting.Attempt<LegacyIncomingJournal.Received>> attempts=new ArrayList<>();
            for(MonogramLegacyReceiveOnly archive:recovery) if(archive.addressed(element) && archive.accepts(context,element)) {
                LegacyIncomingJournal.Received cached=archive.cached(context);if(cached!=null) return cached;
                attempts.add(() -> archive.receive(manager,context));
            }
            OmemoKeyElement activeKey=keys.get(journal.own.getDeviceId());
            if(activeKey!=null) attempts.add(activeKey.isPreKey()?attempts.size():0,() -> receiveActive(manager,journal,context,guard,digest));
            if(attempts.isEmpty()) throw new IOException("Not addressed to any active or historical local device");
            return MonogramLegacyRouting.receive(attempts);
        }
    }
    /** Caller holds the manager monitor; no alternate store is consulted in this normal path. */
    private LegacyIncomingJournal.Received receiveActive(OmemoManager manager,LegacyIncomingJournal journal,
            LegacyIncomingJournal.Context context,OmemoManager.LoggedInOmemoManager guard,byte[] digest) throws Exception {
            OmemoElement element=context.outer.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
            try(LegacyOmemoRecords tx=records.begin(manager.getOwnDevice())) {
                LegacyIncomingJournal.Received result=records.withTransaction(manager.getOwnDevice(),tx,() -> {
                    LegacyIncomingJournal.Received existing=journal.find(tx,context,digest);if(existing!=null) return existing;
                    OmemoMessage.Received clear=super.decryptMessage(guard,org.jxmpp.jid.impl.JidCreate.bareFrom(context.author),element);
                    Message completion=null;
                    if(clear.isPreKeyMessage() && OmemoConfiguration.getCompleteSessionWithEmptyMessage()) {
                        OmemoDevice sender=clear.getSenderDevice();
                        // This is the helper's ONLY network branch: forbid missing sessions before invoking it.
                        if(records.loadRawSession(manager.getOwnDevice(),sender)==null
                                || !clear.getSendersFingerprint().equals(records.getFingerprint(manager.getOwnDevice(),sender)))
                            throw new IOException("Legacy completion session/identity changed");
                        OmemoElement reply=createRatchetUpdateElement(guard,sender);
                        completion=new Message(sender.getJid());completion.setStanzaId(UUID.randomUUID().toString());completion.addExtension(reply);
                    }
                    boolean replenish=records.loadOmemoPreKeys(manager.getOwnDevice()).size()<org.jivesoftware.smackx.omemo.util.OmemoConstants.PRE_KEY_COUNT_PER_BUNDLE;
                    return journal.append(tx,context,digest,clear,completion,replenish);
                });
                tx.commit();return result;
            }
    }
    private void repair(OmemoManager manager,OmemoDevice device) {
        synchronized(manager) {
            try { manager.rebuildSessionWith(device);ratchetUpdate(manager,device); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(Exception failure) { LOGGER.log(Level.WARNING,"Could not repair legacy OMEMO session",failure); }
        }
    }
    private void ratchetUpdate(OmemoManager manager,OmemoDevice device) throws Exception {
        OmemoElement element=createRatchetUpdateElement(new OmemoManager.LoggedInOmemoManager(manager),device);
        Message response=new Message(device.getJid());response.addExtension(element);manager.getConnection().sendStanza(response);
    }
    /** Exact stored packet only; never rebuild a session or advance the ratchet again during recovery. */
    public void completePending(OmemoManager manager,LegacyIncomingJournal journal,LegacyIncomingJournal.Received received) throws Exception {
        LegacyIncomingJournal source=source(manager,journal,received);
        if(source!=journal) { source.completed(received,true,true);return; }
        Registration registration=null;List<LegacyIncomingJournal.Received> publication=null;OmemoBundleElement bundle=null;
        boolean changed=false;
        try {
            synchronized(manager) {
                registration=receivers.get(manager);
                if(registration==null || registration.journal!=journal || !manager.getConnection().isAuthenticated()
                        || manager.getConnection().getUser()==null || !journal.own.getJid().equals(manager.getConnection().getUser().asBareJid())) return;
                LegacyIncomingJournal.Received pending=journal.protocol(received);if(pending==null) return;
                if(receivers.get(manager)!=registration) return;
                if(pending.completion!=null) {
                    manager.getConnection().sendStanza(pending.completion);
                    changed=journal.completed(pending,true,false);
                }
                if(pending.replenish) {
                    publication=journal.pending();
                    records.replenishKeys(manager.getOwnDevice());
                    bundle=records.packOmemoBundle(manager.getOwnDevice());
                }
            }
            // Slow PEP IQs cannot block incoming ratchets or other sends behind the manager monitor.
            if(bundle!=null && receivers.get(manager)==registration && manager.getConnection().isAuthenticated()
                    && journal.protocol(received)!=null) {
                publishBundle(manager.getConnection(),manager.getOwnDevice(),bundle);
                changed=journal.replenished(publication) || changed;
            }
        } finally {
            if(changed && registration!=null) registration.receiver.capacityChanged();
        }
    }
}
