package org.jivesoftware.smackx.omemo;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jivesoftware.smackx.omemo.exceptions.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.protocol.*;
import org.whispersystems.libsignal.state.*;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;
import static org.junit.jupiter.api.Assertions.*;

class MonogramLegacyRoutingTest {
    final OmemoDevice sender=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),101);
    final SignalProtocolAddress senderAddress=new SignalProtocolAddress("alice@example.org",101),receiverAddress=new SignalProtocolAddress("bob@example.org",202);
    static final class Peer {
        final InMemorySignalProtocolStore store;
        final PreKeyRecord pre;
        final SignedPreKeyRecord signed;
        Peer(IdentityKeyPair identity,int preId,int signedId) throws Exception {
            store=new InMemorySignalProtocolStore(identity,202);
            pre=KeyHelper.generatePreKeys(preId,1).get(0);signed=KeyHelper.generateSignedPreKey(identity,signedId);
            store.storePreKey(preId,pre);store.storeSignedPreKey(signedId,signed);
        }
        PreKeyBundle bundle() {
            return new PreKeyBundle(202,202,pre.getId(),pre.getKeyPair().getPublicKey(),signed.getId(),signed.getKeyPair().getPublicKey(),signed.getSignature(),store.getIdentityKeyPair().getPublicKey());
        }
    }
    InMemorySignalProtocolStore sender(IdentityKeyPair identity,Peer recipient) throws Exception {
        InMemorySignalProtocolStore store=new InMemorySignalProtocolStore(identity,101);
        new SessionBuilder(store,receiverAddress).process(recipient.bundle());return store;
    }
    /** Exact installed Smack 4.4.8 receive-wrapper shape, around real Java Signal ratchets. */
    byte[] receive(Peer peer,CiphertextMessage packet) throws Exception {
        SessionCipher cipher=new SessionCipher(peer.store,senderAddress);
        try {
            return packet.getType()==CiphertextMessage.PREKEY_TYPE
                    ? cipher.decrypt(new PreKeySignalMessage(packet.serialize())) : cipher.decrypt(new SignalMessage(packet.serialize()));
        } catch(InvalidKeyIdException | InvalidMessageException | NoSessionException mismatch) {
            // SignalOmemoRatchet -> NoRawSessionException; OmemoRatchet key loop ->
            // CryptoFailedException(NoRawSessionException) -> MultipleCryptoFailedException.
            throw aggregate(new NoRawSessionException(sender,mismatch));
        }
    }
    CryptoFailedException aggregate(Exception failure) {
        return MultipleCryptoFailedException.from(Collections.singletonList(new CryptoFailedException(failure)));
    }
    @Test void newActivePrekeyPassesOldArchiveMissAndOldExistingMessagePassesNewActiveRatchetMismatch() throws Exception {
        IdentityKeyPair receiverIdentity=KeyHelper.generateIdentityKeyPair(),senderIdentity=KeyHelper.generateIdentityKeyPair();
        Peer archive=new Peer(receiverIdentity,20,7),active=new Peer(receiverIdentity,120,8);
        InMemorySignalProtocolStore newSender=sender(senderIdentity,active),oldSender=sender(senderIdentity,archive);
        SessionCipher newCipher=new SessionCipher(newSender,receiverAddress),oldCipher=new SessionCipher(oldSender,receiverAddress);
        byte[] fresh=new byte[32];Arrays.fill(fresh,(byte)7);CiphertextMessage freshPacket=newCipher.encrypt(fresh);
        assertEquals(CiphertextMessage.PREKEY_TYPE,freshPacket.getType());
        Exception archiveMiss=assertThrows(CryptoFailedException.class,() -> receive(archive,freshPacket));
        assertTrue(MonogramLegacyRouting.recoverable(archiveMiss));assertFalse(archive.store.containsSession(senderAddress));
        AtomicInteger activeCalls=new AtomicInteger(),archiveCalls=new AtomicInteger();
        byte[] delivered=MonogramLegacyRouting.receive(true,() -> { activeCalls.incrementAndGet();return receive(active,freshPacket); },() -> { archiveCalls.incrementAndGet();return receive(archive,freshPacket); });
        assertArrayEquals(fresh,delivered);assertEquals(1,activeCalls.get());assertEquals(1,archiveCalls.get());

        CiphertextMessage historicStart=oldCipher.encrypt(new byte[32]);receive(archive,historicStart);
        // Finish that independent historical handshake, then exercise a normal old session packet.
        CiphertextMessage reply=new SessionCipher(archive.store,senderAddress).encrypt(new byte[32]);
        oldCipher.decrypt(new SignalMessage(reply.serialize()));
        byte[] oldBody=new byte[32];Arrays.fill(oldBody,(byte)9);CiphertextMessage oldPacket=oldCipher.encrypt(oldBody);
        assertEquals(CiphertextMessage.WHISPER_TYPE,oldPacket.getType());
        byte[] activeBefore=active.store.loadSession(senderAddress).serialize();
        Exception activeMismatch=assertThrows(CryptoFailedException.class,() -> receive(active,oldPacket));
        assertTrue(MonogramLegacyRouting.recoverable(activeMismatch));
        assertArrayEquals(oldBody,MonogramLegacyRouting.receive(false,() -> receive(active,oldPacket),() -> receive(archive,oldPacket)));
        assertArrayEquals(activeBefore,active.store.loadSession(senderAddress).serialize(),"Historical receive must not promote an archived chain into the active sending store");
    }
    @Test void mixedAggregatesAndUntypedTrustIoDuplicateAndFormatFailuresNeverRetry() throws Exception {
        CryptoFailedException mismatch=aggregate(new NoRawSessionException(sender,new InvalidKeyIdException("missing key")));
        assertTrue(MonogramLegacyRouting.recoverable(mismatch));
        for(Exception terminal:Arrays.asList(new IOException("disk"),new GeneralSecurityException("trust"),new DuplicateMessageException("duplicate"),new CryptoFailedException("no key or ignored identity"))) {
            CryptoFailedException mixed=MultipleCryptoFailedException.from(Arrays.asList(mismatch,new CryptoFailedException(terminal)));
            assertFalse(MonogramLegacyRouting.recoverable(mixed));AtomicInteger fallback=new AtomicInteger();
            assertSame(mixed,assertThrows(CryptoFailedException.class,() -> MonogramLegacyRouting.receive(true,() -> { fallback.incrementAndGet();return 1; },() -> { throw mixed; })));
            assertEquals(0,fallback.get());
        }
        assertFalse(MonogramLegacyRouting.recoverable(new NoRawSessionException(sender,new IOException("wrapped disk failure"))));
        assertFalse(MonogramLegacyRouting.recoverable(new CryptoFailedException(new org.whispersystems.libsignal.InvalidVersionException("version"))));
    }
}
