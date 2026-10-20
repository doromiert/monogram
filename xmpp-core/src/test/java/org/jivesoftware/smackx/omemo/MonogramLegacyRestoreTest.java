package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.io.IOException;
import java.nio.file.Path;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

class MonogramLegacyRestoreTest {
    @TempDir Path directory;
    final OmemoDevice own=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),101);
    @Test void freshKeysPreserveIdentityAndAvoidEveryCapturedPrivateKeyIdentifier() throws Exception {
        LegacyOmemoTestDatabase database=new LegacyOmemoTestDatabase();
        MonogramSignalStore store=new MonogramSignalStore(new SignalFileBasedOmemoStore(directory.resolve("bootstrap").toFile()),database);
        org.whispersystems.libsignal.IdentityKeyPair identity=new SignalOmemoKeyUtil().generateOmemoIdentityKeyPair();store.storeOmemoIdentityKeyPair(own,identity);
        store.storeOmemoSignedPreKey(own,7,store.generateOmemoSignedPreKey(identity,7));store.storeOmemoPreKeys(own,store.generateOmemoPreKeys(20,100));
        // A consumed highest prekey remains a tombstone and must still reserve its identifier.
        store.removeOmemoPreKey(own,119);
        try(LegacyOmemoRecords tx=database.begin(own.getJid().toString(),101)) { tx.save(PURGED,"",0,new byte[]{1});tx.commit(); }
        MonogramLegacyRestore.freshKeys(store,own,1234567);
        assertArrayEquals(identity.serialize(),store.loadOmemoIdentityKeyPair(own).serialize());
        assertEquals(100,store.loadOmemoPreKeys(own).size());assertTrue(store.loadOmemoPreKeys(own).firstKey()>119);
        assertEquals(8,store.loadCurrentOmemoSignedPreKeyId(own));assertNull(store.loadOmemoSignedPreKey(own,7));
        try(LegacyOmemoRecords tx=database.begin(own.getJid().toString(),101)) { assertTrue(MonogramLegacyRestore.marker(tx.load(PURGED,"",0)));assertNotNull(tx.load(INCOMING,"",0)); }
        assertThrows(java.security.GeneralSecurityException.class,() -> MonogramLegacyRestore.freshKeys(store,own,1234568));
    }
    @Test void archiveRecordGuardRejectsCrossAccountSendingKeysAndPublicationButAllowsReceiveTombstone() throws Exception {
        LegacyOmemoTestDatabase database=new LegacyOmemoTestDatabase();LegacyOmemoRecords.Factory guarded=MonogramLegacyReceiveOnly.guard(database,own);
        assertThrows(java.security.GeneralSecurityException.class,() -> guarded.begin("bob@example.org",101));
        try(LegacyOmemoRecords tx=guarded.begin(own.getJid().toString(),101)) {
            for(int kind:new int[]{IDENTITY_PAIR,SIGNED_PRE_KEY,DEVICE_LIST,LAST_PUBLISHED,LAST_RENEWAL,PURGED})
                assertThrows(IOException.class,() -> tx.save(kind,"",0,new byte[]{1}));
            assertThrows(IOException.class,() -> tx.save(PRE_KEY,"",1,new byte[]{1,2}));assertThrows(IOException.class,tx::purge);
            tx.save(PRE_KEY,"",1,new byte[]{0});tx.save(SESSION,"bob@example.org",202,new byte[]{1,8});tx.save(INCOMING,"",1,new byte[]{3});tx.commit();
        }
        try(LegacyOmemoRecords tx=database.begin(own.getJid().toString(),101)) { assertArrayEquals(new byte[]{0},tx.load(PRE_KEY,"",1));assertArrayEquals(new byte[]{1,8},tx.load(SESSION,"bob@example.org",202)); }
    }
}
