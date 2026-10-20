// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smackx.omemo.element.OmemoBundleElement;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.state.*;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

/** Exact signed-bundle approval, atomic old receive-state retirement and a fresh active sender session. */
public final class MonogramLegacyRotation {
    private MonogramLegacyRotation() { }
    public static final class Expected {
        final OmemoDevice peer;
        final String ownerFingerprint,previousFingerprint;
        private Expected(OmemoDevice peer,String ownerFingerprint,String previousFingerprint) { this.peer=peer;this.ownerFingerprint=ownerFingerprint;this.previousFingerprint=previousFingerprint; }
    }
    public static final class Verified {
        final Expected expected;
        final OmemoFingerprint approved;
        final PreKeyBundle bundle;
        private Verified(Expected expected,OmemoFingerprint approved,PreKeyBundle bundle) { this.expected=expected;this.approved=approved;this.bundle=bundle; }
    }
    /** Caller serializes with the account manager. No network or trust mutations. Null means no identity replacement. */
    public static Expected replacement(MonogramSignalStore store,OmemoDevice own,OmemoDevice peer,OmemoFingerprint approved) throws Exception {
        if(own.equals(peer)) {
            if(!approved.equals(store.getFingerprint(own))) throw new GeneralSecurityException("Own identity approval changed");return null;
        }
        try(LegacyOmemoRecords tx=store.begin(own)) {
            return store.withTransaction(own,tx,() -> {
                IdentityKey previous=store.loadOmemoIdentityKey(own,peer);SessionRecord session=store.loadRawSession(own,peer);
                if(previous==null && session==null) return null;
                String fp=previous==null?null:store.keyUtil().getFingerprintOfIdentityKey(previous).toString();
                if(approved.toString().equals(fp)) return null;
                return new Expected(peer,store.getFingerprint(own).toString(),fp);
            });
        }
    }
    /** Verifies the exact displayed fingerprint AND the signed prekey before a database write is possible. */
    public static Verified verify(Expected expected,OmemoFingerprint approved,OmemoBundleElement published) throws Exception {
        if(expected==null || published==null) throw new GeneralSecurityException("A signed replacement bundle is required");
        SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();IdentityKey identity=keys.BUNDLE.identityKey(published);
        if(!approved.equals(keys.getFingerprintOfIdentityKey(identity)) || !Curve.verifySignature(identity.getPublicKey(),published.getSignedPreKey(),published.getSignedPreKeySignature()))
            throw new GeneralSecurityException("Published replacement identity does not match the approved fingerprint");
        Map<Integer,PreKeyBundle> candidates=keys.BUNDLE.bundles(published,expected.peer);
        if(candidates.isEmpty()) throw new GeneralSecurityException("Replacement bundle has no prekeys");
        PreKeyBundle selected=new ArrayList<>(candidates.values()).get(new SecureRandom().nextInt(candidates.size()));
        return new Verified(expected,approved,selected);
    }
    /**
     * Caller holds the manager monitor. Trust is persisted inside this transaction before SQL commit;
     * if SQL commit fails, the explicit approval may remain but all old keys/sessions stay unchanged.
     * Retrying the same approval performs the still-required replacement. No connection/send occurs.
     */
    public static void apply(MonogramSignalStore store,OmemoDevice own,LegacyIncomingJournal incoming,IdentityTrust trust,Map<OmemoDevice,OmemoFingerprint> approvals,
            List<Verified> replacements,Map<OmemoDevice,String> observed,String expectedOwn,BooleanSupplier current) throws Exception {
        if(incoming==null || !own.equals(incoming.own)) throw new GeneralSecurityException("Rotation journal belongs to another owner/device");
        try(LegacyOmemoRecords tx=store.begin(own)) {
            synchronized(trust) {
                store.withTransaction(own,tx,() -> {
                    if(!current.getAsBoolean()) throw new ConcurrentModificationException("Account changed during fingerprint approval");
                    IdentityKeyPair identity=store.loadOmemoIdentityKeyPair(own);
                    if(identity==null) throw new GeneralSecurityException("Local identity is unavailable");
                    String ownFingerprint=store.getFingerprint(own).toString();
                    if(!ownFingerprint.equals(expectedOwn)) throw new ConcurrentModificationException("Local identity changed during approval");
                    for(Map.Entry<OmemoDevice,String> item:observed.entrySet()) {
                        IdentityKey old=item.getKey().equals(own)?identity.getPublicKey():store.loadOmemoIdentityKey(own,item.getKey());
                        String fingerprint=old==null?null:store.keyUtil().getFingerprintOfIdentityKey(old).toString();
                        if(!Objects.equals(fingerprint,item.getValue())) throw new ConcurrentModificationException("Peer identity changed during approval");
                    }
                    // The public primitive must not silently downgrade a replacement to trust-only.
                    if(!observed.keySet().equals(approvals.keySet())) throw new GeneralSecurityException("Approval observation set is incomplete");
                    Set<OmemoDevice> proven=new HashSet<>();
                    for(Verified verified:replacements) if(!proven.add(verified.expected.peer)) throw new GeneralSecurityException("Duplicate replacement proof");
                    for(Map.Entry<OmemoDevice,OmemoFingerprint> approval:approvals.entrySet()) {
                        OmemoDevice peer=approval.getKey();String prior=observed.get(peer);
                        boolean needed=!own.equals(peer) && (prior!=null || store.loadRawSession(own,peer)!=null) && !approval.getValue().toString().equals(prior);
                        if(needed!=proven.contains(peer) || own.equals(peer) && !ownFingerprint.equals(approval.getValue().toString()))
                            throw new GeneralSecurityException("Exact signed replacement proof is required");
                    }
                    // Validate the whole batch before retiring/installing any selected peer.
                    for(Verified verified:replacements) {
                        if(!verified.approved.equals(approvals.get(verified.expected.peer)) || !ownFingerprint.equals(verified.expected.ownerFingerprint))
                            throw new ConcurrentModificationException("Approved identity/account changed during bundle lookup");
                        IdentityKey previous=store.loadOmemoIdentityKey(own,verified.expected.peer);
                        String previousFp=previous==null?null:store.keyUtil().getFingerprintOfIdentityKey(previous).toString();
                        if(!Objects.equals(previousFp,verified.expected.previousFingerprint)) throw new ConcurrentModificationException("Peer identity changed during bundle lookup");
                    }
                    for(Verified verified:replacements) {
                        OmemoDevice peer=verified.expected.peer;IdentityKey previous=store.loadOmemoIdentityKey(own,peer);SessionRecord session=store.loadRawSession(own,peer);
                        Map<String,Retired> lineages=retire(store,tx,peer,identity,previous,session);
                        incoming.retirePeer(tx,peer,lineages);
                        // An entirely detached empty session store is deliberate: SessionBuilder
                        // must never archive old receive states into the new outgoing SessionRecord.
                        InMemorySignalProtocolStore fresh=new InMemorySignalProtocolStore(identity,own.getDeviceId());
                        SignalProtocolAddress address=new SignalProtocolAddress(peer.getJid().toString(),peer.getDeviceId());
                        new SessionBuilder(fresh,address).process(verified.bundle);
                        store.storeOmemoIdentityKeyPair(own,identity); // materialize read-only bootstrap identity, never allocate one
                        store.storeOmemoIdentityKey(own,peer,verified.bundle.getIdentityKey());
                        store.storeRawSession(own,peer,fresh.loadSession(address));store.storeOmemoMessageCounter(own,peer,0);
                    }
                    if(!current.getAsBoolean()) throw new ConcurrentModificationException("Account changed before fingerprint approval commit");
                    trust.setTrustAll(approvals,true);return null;
                });tx.commit();
            }
        }
    }
    private static Map<String,Retired> retire(MonogramSignalStore store,LegacyOmemoRecords tx,OmemoDevice peer,IdentityKeyPair identity,IdentityKey previous,SessionRecord original) throws Exception {
        Map<String,IdentityKey> identities=new LinkedHashMap<>();List<SessionState> states=new ArrayList<>();
        if(original!=null) { states.add(original.getSessionState());states.addAll(original.getPreviousSessionStates()); }
        if(previous!=null) identities.put(store.keyUtil().getFingerprintOfIdentityKey(previous).toString(),previous);
        for(SessionState state:states) {
            IdentityKey remote=state.getRemoteIdentityKey();if(remote!=null) identities.put(store.keyUtil().getFingerprintOfIdentityKey(remote).toString(),remote);
        }
        if(identities.isEmpty()) {
            if(original!=null) throw new GeneralSecurityException("Existing peer session has no recoverable identity");return Collections.emptyMap();
        }
        String generation=UUID.randomUUID().toString();boolean originalSaved=false;Map<String,Retired> result=new LinkedHashMap<>();
        for(Map.Entry<String,IdentityKey> entry:identities.entrySet()) {
            Retired lineage=new Retired(peer.getJid().toString(),peer.getDeviceId(),entry.getKey(),generation);result.put(entry.getKey(),lineage);
            tx.saveRetired(lineage,IDENTITY_PAIR,0,encoded(store.keyUtil().identityKeyPairToBytes(identity)));
            tx.saveRetired(lineage,IDENTITY,0,encoded(store.keyUtil().identityKeyToBytes(entry.getValue())));
            List<SessionState> matching=new ArrayList<>();for(SessionState state:states) if(entry.getValue().equals(state.getRemoteIdentityKey())) matching.add(state);
            if(!matching.isEmpty()) {
                SessionRecord filtered=new SessionRecord(matching.get(matching.size()-1));
                for(int n=matching.size()-2;n>=0;n--) { filtered.archiveCurrentState();filtered.setState(matching.get(n)); }
                tx.saveRetired(lineage,SESSION,0,encoded(filtered.serialize()));
            }
            if(original!=null && !originalSaved) { tx.saveRetired(lineage,RETIRED_ORIGINAL_SESSION,0,encoded(original.serialize()));originalSaved=true; }
        }return result;
    }
    private static byte[] encoded(byte[] bytes) { byte[] encoded=new byte[bytes.length+1];encoded[0]=1;System.arraycopy(bytes,0,encoded,1,bytes.length);Arrays.fill(bytes,(byte)0);return encoded; }
}
