package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.nio.file.Path;
import java.util.*;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.*;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.protocol.*;
import org.whispersystems.libsignal.state.*;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prepared source fixtures: no server, publication, singleton service or production keys. */
class MonogramLegacyActiveRotationTest {
    @TempDir Path directory;
    final OmemoDevice own=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),202),peer=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("bob@example.org"),303);
    final SignalProtocolAddress ownAddress=new SignalProtocolAddress(own.getJid().toString(),202),peerAddress=new SignalProtocolAddress(peer.getJid().toString(),303);
    final SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();
    final LegacyOmemoTestDatabase database=new LegacyOmemoTestDatabase();
    MonogramSignalStore store;
    IdentityTrust trust;
    LegacyIncomingJournal journal;
    IdentityKeyPair local,oldPeer;
    InMemorySignalProtocolStore oldSender;
    SessionCipher oldCipher;
    void setup() throws Exception {
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("encrypted",OmemoElement_VAxolotl.NAMESPACE,new org.jivesoftware.smackx.omemo.provider.OmemoVAxolotlProvider());
        local=KeyHelper.generateIdentityKeyPair();oldPeer=KeyHelper.generateIdentityKeyPair();trust=new IdentityTrust(directory.resolve("trust").toFile());
        store=new MonogramSignalStore(new SignalFileBasedOmemoStore(directory.resolve("bootstrap").toFile()),database);
        store.storeOmemoIdentityKeyPair(own,local);PreKeyRecord pre=KeyHelper.generatePreKeys(20,1).get(0);SignedPreKeyRecord signed=KeyHelper.generateSignedPreKey(local,7);
        store.storeOmemoPreKey(own,20,pre);store.storeOmemoSignedPreKey(own,7,signed);trust.setTrust(peer,keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey()),true);
        oldSender=new InMemorySignalProtocolStore(oldPeer,303);
        new SessionBuilder(oldSender,ownAddress).process(new PreKeyBundle(202,202,20,pre.getKeyPair().getPublicKey(),7,signed.getKeyPair().getPublicKey(),signed.getSignature(),local.getPublicKey()));
        oldCipher=new SessionCipher(oldSender,ownAddress);receive(store,oldCipher.encrypt(new byte[32]));journal=new LegacyIncomingJournal(store,own);
    }
    MonogramLegacyArchiveCipher.Authenticated receive(MonogramSignalStore target,CiphertextMessage packet) throws Exception {
        try(LegacyOmemoRecords tx=target.begin(own)) {
            synchronized(trust) {
                MonogramLegacyArchiveCipher.Authenticated result=target.withTransaction(own,tx,() -> MonogramLegacyArchiveCipher.decryptAuthenticated(target,own,peer,new OmemoKeyElement(packet.serialize(),202,packet.getType()==CiphertextMessage.PREKEY_TYPE),trust));
                tx.commit();return result;
            }
        }
    }
    OmemoBundleElement bundle(IdentityKeyPair identity) throws Exception {
        SignedPreKeyRecord signed=KeyHelper.generateSignedPreKey(identity,9);PreKeyRecord pre=KeyHelper.generatePreKeys(500,1).get(0);
        HashMap<Integer,byte[]> prekeys=new HashMap<>();prekeys.put(pre.getId(),pre.getKeyPair().getPublicKey().serialize());
        return new OmemoBundleElement_VAxolotl(signed.getId(),signed.getKeyPair().getPublicKey().serialize(),signed.getSignature(),identity.getPublicKey().serialize(),prekeys);
    }
    MonogramLegacyRotation.Verified prepare(IdentityKeyPair replacement) throws Exception {
        OmemoFingerprint fingerprint=keys.getFingerprintOfIdentityKey(replacement.getPublicKey());
        return MonogramLegacyRotation.verify(MonogramLegacyRotation.replacement(store,own,peer,fingerprint),fingerprint,bundle(replacement));
    }
    void apply(MonogramLegacyRotation.Verified prepared,java.util.function.BooleanSupplier guard) throws Exception {
        Map<OmemoDevice,String> observed;String ownFingerprint;
        // Inspection must not consume the commit failure intended for the rotation transaction.
        try(LegacyOmemoRecords tx=store.begin(own)) {
            observed=store.withTransaction(own,tx,() -> Collections.singletonMap(peer,store.getFingerprint(own,peer).toString()));
            ownFingerprint=store.withTransaction(own,tx,() -> store.getFingerprint(own).toString());
        }
        MonogramLegacyRotation.apply(store,own,journal,trust,Collections.singletonMap(peer,prepared.approved),Collections.singletonList(prepared),observed,ownFingerprint,guard);
    }
    @Test void approvedReplacementRetiresCompleteOldStateAndNeverPromotesItIntoActiveSending() throws Exception {
        setup();byte[] before=store.loadRawSession(own,peer).serialize();IdentityKeyPair replacement=KeyHelper.generateIdentityKeyPair();
        byte[] body=new byte[32];Arrays.fill(body,(byte)7);CiphertextMessage queued=oldCipher.encrypt(body);
        apply(prepare(replacement),() -> true);
        assertArrayEquals(local.serialize(),store.loadOmemoIdentityKeyPair(own).serialize());
        assertEquals(keys.getFingerprintOfIdentityKey(replacement.getPublicKey()),store.getFingerprint(own,peer));
        assertTrue(store.loadRawSession(own,peer).getPreviousSessionStates().isEmpty());byte[] active=store.loadRawSession(own,peer).serialize();
        Retired retired;
        try(LegacyOmemoRecords tx=store.begin(own)) {
            assertEquals(1,tx.retired().size());retired=tx.retired().get(0);
            byte[] original=tx.loadRetired(retired,RETIRED_ORIGINAL_SESSION,0);assertEquals(1,original[0]);assertArrayEquals(before,Arrays.copyOfRange(original,1,original.length));
        }
        MonogramSignalStore receiveOnly=store.receiveView(MonogramLegacyRetired.scope(store,own,retired));
        MonogramLegacyArchiveCipher.Authenticated received=receive(receiveOnly,queued);
        assertArrayEquals(body,received.material);assertEquals(keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey()),received.fingerprint);
        assertArrayEquals(active,store.loadRawSession(own,peer).serialize());
        Map<Retired,MonogramLegacyReceiveOnly> loaded=MonogramLegacyRetired.load(store,own,trust,Collections.emptyMap());
        assertEquals(1,loaded.size());assertSame(loaded.get(retired),MonogramLegacyRetired.load(store,own,trust,loaded).get(retired));
        trust.setTrust(peer,received.fingerprint,false);CiphertextMessage revoked=oldCipher.encrypt(body);
        Exception rejected=assertThrows(Exception.class,() -> receive(receiveOnly,revoked));assertFalse(MonogramLegacyRouting.recoverable(rejected));
        assertArrayEquals(active,store.loadRawSession(own,peer).serialize());
    }
    @Test void repeatedFingerprintReturnRetainsEveryIndependentRetirementGeneration() throws Exception {
        setup();IdentityKeyPair second=KeyHelper.generateIdentityKeyPair();apply(prepare(second),() -> true);apply(prepare(oldPeer),() -> true);apply(prepare(second),() -> true);
        try(LegacyOmemoRecords tx=store.begin(own)) {
            assertEquals(3,tx.retired().size());Set<String> generations=new HashSet<>();int first=0;
            for(Retired branch:tx.retired()) { generations.add(branch.generation);if(branch.fingerprint.equals(keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey()).toString())) first++;assertNotNull(tx.loadRetired(branch,RETIRED_ORIGINAL_SESSION,0)); }
            assertEquals(3,generations.size());assertEquals(2,first);
        }
        assertTrue(store.loadRawSession(own,peer).getPreviousSessionStates().isEmpty());
    }
    @Test void wrongFingerprintSignatureAccountRaceAndStorageFailureLeaveOldStateIntact() throws Exception {
        setup();IdentityKeyPair replacement=KeyHelper.generateIdentityKeyPair();MonogramLegacyRotation.Verified prepared=prepare(replacement);byte[] before=store.loadRawSession(own,peer).serialize();
        assertThrows(Exception.class,() -> MonogramLegacyRotation.verify(prepared.expected,keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey()),bundle(replacement)));
        OmemoBundleElement valid=bundle(replacement);byte[] bad=valid.getSignedPreKeySignature();bad[0]^=1;
        OmemoBundleElement tampered=new OmemoBundleElement_VAxolotl(valid.getSignedPreKeyId(),valid.getSignedPreKey(),bad,valid.getIdentityKey(),valid.getPreKeys());
        assertThrows(Exception.class,() -> MonogramLegacyRotation.verify(prepared.expected,prepared.approved,tampered));
        assertThrows(ConcurrentModificationException.class,() -> apply(prepared,() -> false));
        database.failNextSave=true;assertThrows(Exception.class,() -> apply(prepared,() -> true));
        database.failNextCommit=true;assertThrows(Exception.class,() -> apply(prepared,() -> true));
        assertTrue(trust.isTrusted(peer,prepared.approved),"Explicit trust may persist if SQL commit fails; retry must still retire old state");
        assertArrayEquals(before,store.loadRawSession(own,peer).serialize());assertEquals(keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey()),store.getFingerprint(own,peer));
        try(LegacyOmemoRecords tx=store.begin(own)) { assertTrue(tx.retired().isEmpty()); }
        apply(prepared,() -> true);assertEquals(prepared.approved,store.getFingerprint(own,peer));
    }
    @Test void noSessionOldIdentityNeedsVerifiedReplacementButUnchangedIdentityKeepsOrdinaryPreparation() throws Exception {
        setup();store.removeRawSession(own,peer);OmemoFingerprint oldFp=keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey());
        assertNull(MonogramLegacyRotation.replacement(store,own,peer,oldFp));
        IdentityKeyPair replacement=KeyHelper.generateIdentityKeyPair();MonogramLegacyRotation.Verified proof=prepare(replacement);
        Map<OmemoDevice,OmemoFingerprint> approval=Collections.singletonMap(peer,proof.approved);Map<OmemoDevice,String> observed=Collections.singletonMap(peer,oldFp.toString());String ownFp=store.getFingerprint(own).toString();
        assertThrows(Exception.class,() -> MonogramLegacyRotation.apply(store,own,journal,trust,approval,Collections.emptyList(),observed,ownFp,() -> true));
        assertEquals(oldFp,store.getFingerprint(own,peer));assertNull(store.loadRawSession(own,peer));
        apply(proof,() -> true);assertNotNull(store.loadRawSession(own,peer));assertEquals(proof.approved,store.getFingerprint(own,peer));
        try(LegacyOmemoRecords tx=store.begin(own)) {
            assertEquals(1,tx.retired().size());Retired branch=tx.retired().get(0);assertNotNull(tx.loadRetired(branch,IDENTITY,0));assertNull(tx.loadRetired(branch,SESSION,0));assertNull(tx.loadRetired(branch,RETIRED_ORIGINAL_SESSION,0));
        }
        assertTrue(MonogramLegacyRetired.load(store,own,trust,Collections.emptyMap()).isEmpty(),"An identity-only archive cannot decrypt and has no pending body");
    }
    @Test void pendingBodiesMoveAtomicallyToReadOnlyJournalAndOldAcknowledgementsCannotEscape() throws Exception {
        setup();org.jivesoftware.smack.packet.Message wire=new org.jivesoftware.smack.packet.Message(own.getJid(),org.jivesoftware.smack.packet.Message.Type.chat);
        wire.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));wire.setStanzaId("pending-before-rotation");
        OmemoElement element=new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(303,Collections.singletonList(new OmemoKeyElement(new byte[]{1,2,3},202,true)),new byte[16]),new byte[]{4,5,6});wire.addExtension(element);
        LegacyIncomingJournal.Context context=LegacyIncomingJournal.Context.direct(wire,own.getJid().asEntityBareJidOrThrow(),true,null,null);
        org.jivesoftware.smack.packet.Message completion=new org.jivesoftware.smack.packet.Message(peer.getJid());completion.setStanzaId("old-crypto-ack");
        completion.addExtension(new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(202,Collections.singletonList(new OmemoKeyElement(new byte[]{7,8,9},303)),new byte[12]),null));
        LegacyIncomingJournal.Received original;
        try(LegacyOmemoRecords tx=store.begin(own)) {
            original=store.withTransaction(own,tx,() -> journal.append(tx,context,journal.digest(context),new OmemoMessage.Received(element,new byte[16],new byte[16],"Keep this body",keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey()),peer,true),completion,true));tx.commit();
        }
        MonogramLegacyRotation.Verified prepared=prepare(KeyHelper.generateIdentityKeyPair());database.failNextCommit=true;
        assertThrows(Exception.class,() -> apply(prepared,() -> true));assertEquals("Keep this body",journal.pending().get(0).message.body);
        apply(prepared,() -> true);assertTrue(journal.pending().isEmpty());assertFalse(journal.accept(original));assertNull(journal.protocol(original));
        MonogramLegacyReceiveOnly branch=MonogramLegacyRetired.load(store,own,trust,Collections.emptyMap()).values().iterator().next();
        LegacyIncomingJournal.Received pending=branch.journal().pending().get(0);
        assertEquals("Keep this body",pending.message.body);assertEquals(original.message.ids,pending.message.ids);
        assertNull(pending.completion);assertFalse(pending.replenish);assertFalse(pending.receiptEligible());
        assertTrue(branch.journal().accept(pending));assertTrue(branch.journal().pending().isEmpty());
    }
    @Test void trustAllRotatesEveryVerifiedPeerInOneTransactionAndRejectsChangedObservedState() throws Exception {
        setup();OmemoDevice other=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("carol@example.org"),404);
        store.storeOmemoIdentityKey(own,other,oldPeer.getPublicKey());store.storeRawSession(own,other,store.loadRawSession(own,peer));
        IdentityKeyPair first=KeyHelper.generateIdentityKeyPair(),second=KeyHelper.generateIdentityKeyPair();MonogramLegacyRotation.Verified one=prepare(first);
        OmemoFingerprint secondFp=keys.getFingerprintOfIdentityKey(second.getPublicKey());
        MonogramLegacyRotation.Verified two=MonogramLegacyRotation.verify(MonogramLegacyRotation.replacement(store,own,other,secondFp),secondFp,bundle(second));
        Map<OmemoDevice,OmemoFingerprint> approvals=new LinkedHashMap<>();approvals.put(peer,one.approved);approvals.put(other,two.approved);
        Map<OmemoDevice,String> observed=new LinkedHashMap<>();observed.put(peer,store.getFingerprint(own,peer).toString());observed.put(other,store.getFingerprint(own,other).toString());
        String ownFp=store.getFingerprint(own).toString();database.failNextCommit=true;
        assertThrows(Exception.class,() -> MonogramLegacyRotation.apply(store,own,journal,trust,approvals,Arrays.asList(one,two),observed,ownFp,() -> true));
        try(LegacyOmemoRecords tx=store.begin(own)) { assertTrue(tx.retired().isEmpty()); }
        Map<OmemoDevice,String> wrong=new LinkedHashMap<>(observed);wrong.put(other,secondFp.toString());
        assertThrows(ConcurrentModificationException.class,() -> MonogramLegacyRotation.apply(store,own,journal,trust,approvals,Arrays.asList(one,two),wrong,ownFp,() -> true));
        MonogramLegacyRotation.apply(store,own,journal,trust,approvals,Arrays.asList(one,two),observed,ownFp,() -> true);
        assertEquals(one.approved,store.getFingerprint(own,peer));assertEquals(two.approved,store.getFingerprint(own,other));
        try(LegacyOmemoRecords tx=store.begin(own)) { assertEquals(2,tx.retired().size()); }
    }
    @Test void scopeCannotReadActivePrekeysWriteAnIdentityOrReachAnotherOwner() throws Exception {
        setup();apply(prepare(KeyHelper.generateIdentityKeyPair()),() -> true);Retired branch;
        try(LegacyOmemoRecords tx=store.begin(own)) { branch=tx.retired().get(0); }
        LegacyOmemoRecords.Factory factory=MonogramLegacyRetired.scope(store,own,branch);
        try(LegacyOmemoRecords tx=factory.begin(own.getJid().toString(),202)) {
            assertNull(tx.load(SIGNED_PRE_KEY,"",7));assertNotNull(tx.load(PURGED,"",0));
            assertThrows(Exception.class,() -> tx.save(IDENTITY,peer.getJid().toString(),303,new byte[]{1,2}));
            assertThrows(Exception.class,() -> tx.save(SESSION,"other@example.org",303,new byte[]{1,2}));
        }
        assertThrows(Exception.class,() -> factory.begin("other@example.org",202));
    }
}
