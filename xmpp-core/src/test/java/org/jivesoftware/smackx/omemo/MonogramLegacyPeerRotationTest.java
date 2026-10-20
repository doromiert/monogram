package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jivesoftware.smackx.omemo.element.OmemoKeyElement;
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
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

class MonogramLegacyPeerRotationTest {
    @TempDir Path directory;
    final OmemoDevice own=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),202),peer=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("bob@example.org"),303);
    final SignalProtocolAddress ownAddress=new SignalProtocolAddress(own.getJid().toString(),202),peerAddress=new SignalProtocolAddress(peer.getJid().toString(),303);
    final SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();
    MonogramSignalStore store(String name,LegacyOmemoTestDatabase db,IdentityKeyPair identity,int pre,int signed) throws Exception {
        MonogramSignalStore store=new MonogramSignalStore(new SignalFileBasedOmemoStore(directory.resolve(name).toFile()),db);
        store.storeOmemoIdentityKeyPair(own,identity);store.storeOmemoPreKeys(own,store.generateOmemoPreKeys(pre,100));store.storeOmemoSignedPreKey(own,signed,store.generateOmemoSignedPreKey(identity,signed));return store;
    }
    PreKeyBundle bundle(MonogramSignalStore store,int pre,int signed) throws Exception {
        PreKeyRecord p=store.loadOmemoPreKey(own,pre);SignedPreKeyRecord s=store.loadOmemoSignedPreKey(own,signed);
        return new PreKeyBundle(202,202,pre,p.getKeyPair().getPublicKey(),signed,s.getKeyPair().getPublicKey(),s.getSignature(),store.loadOmemoIdentityKeyPair(own).getPublicKey());
    }
    InMemorySignalProtocolStore sender(IdentityKeyPair identity,PreKeyBundle bundle) throws Exception {
        InMemorySignalProtocolStore sender=new InMemorySignalProtocolStore(identity,303);new SessionBuilder(sender,ownAddress).process(bundle);return sender;
    }
    OmemoKeyElement key(CiphertextMessage packet) { return new OmemoKeyElement(packet.serialize(),202,packet.getType()==CiphertextMessage.PREKEY_TYPE); }
    byte[] body(int marker) { byte[] body=new byte[32];Arrays.fill(body,(byte)marker);return body; }
    MonogramLegacyArchiveCipher.Authenticated receive(MonogramSignalStore store,IdentityTrust trust,CiphertextMessage packet) throws Exception {
        try(LegacyOmemoRecords tx=store.begin(own)) {
            synchronized(trust) {
                MonogramLegacyArchiveCipher.Authenticated received=store.withTransaction(own,tx,() -> {
                    MonogramLegacyArchiveCipher.checkCandidate(store,own,peer,key(packet),trust);
                    return MonogramLegacyArchiveCipher.decryptAuthenticated(store,own,peer,key(packet),trust);
                });tx.commit();return received;
            }
        }
    }
    @Test void trustedNewPeerSkipsOldArchiveWithoutChangingItsIdentitySessionOrPrekeys() throws Exception {
        IdentityKeyPair local=KeyHelper.generateIdentityKeyPair(),oldPeer=KeyHelper.generateIdentityKeyPair(),newPeer=KeyHelper.generateIdentityKeyPair();
        LegacyOmemoTestDatabase oldDb=new LegacyOmemoTestDatabase();
        MonogramSignalStore archive=store("old",oldDb,local,20,7),active=store("new",new LegacyOmemoTestDatabase(),local,120,8);
        archive.storeOmemoIdentityKey(own,peer,oldPeer.getPublicKey());archive.storeRawSession(own,peer,new SessionRecord());
        byte[] oldSession=archive.loadRawSession(own,peer).serialize(),oldPre=archive.loadOmemoPreKey(own,20).serialize();
        IdentityTrust trust=new IdentityTrust(directory.resolve("trust").toFile());OmemoFingerprint approved=keys.getFingerprintOfIdentityKey(newPeer.getPublicKey());trust.setTrust(peer,approved,true);
        InMemorySignalProtocolStore sending=sender(newPeer,bundle(active,120,8));CiphertextMessage packet=new SessionCipher(sending,ownAddress).encrypt(body(7));
        AtomicInteger activeCalls=new AtomicInteger(),archiveCalls=new AtomicInteger();
        MonogramLegacyArchiveCipher.Authenticated result=MonogramLegacyRouting.receive(true,
                () -> { activeCalls.incrementAndGet();return receive(active,trust,packet); },
                () -> { archiveCalls.incrementAndGet();return receive(archive,trust,packet); });
        assertArrayEquals(body(7),result.material);assertEquals(approved,result.fingerprint);assertEquals(1,activeCalls.get());assertEquals(1,archiveCalls.get());
        assertArrayEquals(oldPeer.getPublicKey().serialize(),archive.loadOmemoIdentityKey(own,peer).serialize());assertArrayEquals(oldSession,archive.loadRawSession(own,peer).serialize());assertArrayEquals(oldPre,archive.loadOmemoPreKey(own,20).serialize());
        trust.setTrust(peer,approved,false);activeCalls.set(0);
        assertThrows(GeneralSecurityException.class,() -> MonogramLegacyRouting.receive(true,
                () -> { activeCalls.incrementAndGet();return receive(active,trust,packet); },() -> receive(archive,trust,packet)));
        assertEquals(0,activeCalls.get(),"A revoked incoming identity must not be treated as an archive mismatch");
        trust.setTrust(peer,approved,true);
        try(LegacyOmemoRecords tx=oldDb.begin(own.getJid().toString(),202)) { tx.save(IDENTITY,peer.getJid().toString(),303,new byte[]{1,2,3});tx.commit(); }
        Exception corrupt=assertThrows(Exception.class,() -> receive(archive,trust,packet));assertFalse(MonogramLegacyRouting.recoverable(corrupt));
    }
    @Test void previousTrustedPeerStateKeepsItsActualFingerprintWithoutOverwritingCurrentPeerPin() throws Exception {
        IdentityKeyPair local=KeyHelper.generateIdentityKeyPair(),oldPeer=KeyHelper.generateIdentityKeyPair(),newPeer=KeyHelper.generateIdentityKeyPair();
        MonogramSignalStore archive=store("archive",new LegacyOmemoTestDatabase(),local,20,7);
        IdentityTrust trust=new IdentityTrust(directory.resolve("trust").toFile());OmemoFingerprint oldFp=keys.getFingerprintOfIdentityKey(oldPeer.getPublicKey()),newFp=keys.getFingerprintOfIdentityKey(newPeer.getPublicKey());trust.setTrust(peer,oldFp,true);trust.setTrust(peer,newFp,true);
        InMemorySignalProtocolStore oldSender=sender(oldPeer,bundle(archive,20,7));SessionCipher oldCipher=new SessionCipher(oldSender,ownAddress);
        receive(archive,trust,oldCipher.encrypt(body(1)));
        CiphertextMessage queuedPrekey=oldCipher.encrypt(body(2));assertEquals(CiphertextMessage.PREKEY_TYPE,queuedPrekey.getType());
        InMemorySignalProtocolStore receiver=new InMemorySignalProtocolStore(local,202) {
            @Override public boolean isTrustedIdentity(SignalProtocolAddress remote,IdentityKey identity,IdentityKeyStore.Direction direction) { return true; }
        };
        receiver.storeSession(peerAddress,archive.loadRawSession(own,peer));receiver.saveIdentity(peerAddress,oldPeer.getPublicKey());
        CiphertextMessage acknowledgement=new SessionCipher(receiver,peerAddress).encrypt(body(3));oldCipher.decrypt(new SignalMessage(acknowledgement.serialize()));
        CiphertextMessage queuedNormal=oldCipher.encrypt(body(4));assertEquals(CiphertextMessage.WHISPER_TYPE,queuedNormal.getType());
        // Model a legitimate backup containing a current new-peer session plus the library's
        // previous old-peer state. This is fixture setup, never a production identity rewrite.
        PreKeyRecord pre=KeyHelper.generatePreKeys(500,1).get(0);SignedPreKeyRecord signed=KeyHelper.generateSignedPreKey(newPeer,9);
        new SessionBuilder(receiver,peerAddress).process(new PreKeyBundle(303,303,pre.getId(),pre.getKeyPair().getPublicKey(),signed.getId(),signed.getKeyPair().getPublicKey(),signed.getSignature(),newPeer.getPublicKey()));
        archive.storeRawSession(own,peer,receiver.loadSession(peerAddress));archive.storeOmemoIdentityKey(own,peer,newPeer.getPublicKey());
        MonogramLegacyArchiveCipher.Authenticated preResult=receive(archive,trust,queuedPrekey);assertArrayEquals(body(2),preResult.material);assertEquals(oldFp,preResult.fingerprint);
        MonogramLegacyArchiveCipher.Authenticated normalResult=receive(archive,trust,queuedNormal);assertArrayEquals(body(4),normalResult.material);assertEquals(oldFp,normalResult.fingerprint);assertNotEquals(newFp,normalResult.fingerprint);
        assertArrayEquals(newPeer.getPublicKey().serialize(),archive.loadOmemoIdentityKey(own,peer).serialize(),"Promoting a receive state must not replace the archive's current peer identity pin");
        trust.setTrust(peer,oldFp,false);byte[] before=archive.loadRawSession(own,peer).serialize();CiphertextMessage revoked=oldCipher.encrypt(body(5));
        Exception rejected=assertThrows(Exception.class,() -> receive(archive,trust,revoked));assertFalse(MonogramLegacyRouting.recoverable(rejected));assertArrayEquals(before,archive.loadRawSession(own,peer).serialize());assertTrue(trust.isTrusted(peer,newFp));
    }
}
