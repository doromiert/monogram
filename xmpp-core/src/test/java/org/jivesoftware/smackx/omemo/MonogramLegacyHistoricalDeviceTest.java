package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.nio.file.Path;
import java.util.*;
import org.jivesoftware.smackx.omemo.element.OmemoKeyElement;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.state.*;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

class MonogramLegacyHistoricalDeviceTest {
    @TempDir Path directory;
    @Test void historicalOwnIdReceivesUsingItsIdentityWithoutCreatingOrChangingAnActiveManager() throws Exception {
        OmemoDevice oldOwn=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),202),activeOwn=new OmemoDevice(oldOwn.getJid(),101),sender=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("bob@example.org"),303);
        LegacyOmemoTestDatabase archiveDb=new LegacyOmemoTestDatabase(),activeDb=new LegacyOmemoTestDatabase();
        MonogramSignalStore old=new MonogramSignalStore(new SignalFileBasedOmemoStore(directory.resolve("empty-old").toFile()),archiveDb),active=new MonogramSignalStore(new SignalFileBasedOmemoStore(directory.resolve("empty-active").toFile()),activeDb);
        IdentityKeyPair oldIdentity=KeyHelper.generateIdentityKeyPair(),activeIdentity=KeyHelper.generateIdentityKeyPair();
        old.storeOmemoIdentityKeyPair(oldOwn,oldIdentity);old.storeOmemoPreKeys(oldOwn,old.generateOmemoPreKeys(20,100));old.storeOmemoSignedPreKey(oldOwn,7,old.generateOmemoSignedPreKey(oldIdentity,7));
        active.storeOmemoIdentityKeyPair(activeOwn,activeIdentity);
        try(LegacyOmemoRecords tx=activeDb.begin(activeOwn.getJid().toString(),101)) { tx.save(PURGED,"",0,new byte[]{1});tx.commit(); }
        try(LegacyOmemoRecords tx=archiveDb.begin(oldOwn.getJid().toString(),202)) { tx.save(PURGED,"",0,new byte[]{1});tx.commit(); }
        MonogramLegacyRestore.freshKeys(active,activeOwn,12345);
        IdentityTrust trust=new IdentityTrust(directory.resolve("trust").toFile());
        MonogramLegacyReceiveOnly recovery=new MonogramLegacyReceiveOnly(active,activeOwn,oldOwn,old.getFingerprint(oldOwn).toString(),new SignalFileBasedOmemoStore(directory.resolve("empty-bootstrap").toFile()),archiveDb,trust);
        recovery.requireActive(active,new LegacyIncomingJournal(active,activeOwn));assertEquals(oldOwn,recovery.journal().own);
        IdentityKeyPair senderIdentity=KeyHelper.generateIdentityKeyPair();InMemorySignalProtocolStore outgoing=new InMemorySignalProtocolStore(senderIdentity,303);
        PreKeyRecord pre=old.loadOmemoPreKey(oldOwn,20);SignedPreKeyRecord signed=old.loadOmemoSignedPreKey(oldOwn,7);
        SignalProtocolAddress recipientAddress=new SignalProtocolAddress(oldOwn.getJid().toString(),202);
        new SessionBuilder(outgoing,recipientAddress).process(new PreKeyBundle(202,202,20,pre.getKeyPair().getPublicKey(),7,signed.getKeyPair().getPublicKey(),signed.getSignature(),oldIdentity.getPublicKey()));
        byte[] transported=new byte[32];Arrays.fill(transported,(byte)7);CiphertextMessage packet=new SessionCipher(outgoing,recipientAddress).encrypt(transported);
        try(LegacyOmemoRecords tx=old.begin(oldOwn)) {
            byte[] clear=old.withTransaction(oldOwn,tx,() -> MonogramLegacyArchiveCipher.decrypt(old,oldOwn,sender,new OmemoKeyElement(packet.serialize(),202,true)));
            assertArrayEquals(transported,clear);Arrays.fill(clear,(byte)0);tx.commit();
        }
        assertNotNull(old.loadRawSession(oldOwn,sender));assertNull(old.loadOmemoPreKey(oldOwn,20));assertNull(active.loadRawSession(activeOwn,sender));
        assertArrayEquals(oldIdentity.serialize(),old.loadOmemoIdentityKeyPair(oldOwn).serialize());assertArrayEquals(activeIdentity.serialize(),active.loadOmemoIdentityKeyPair(activeOwn).serialize());
    }
}
