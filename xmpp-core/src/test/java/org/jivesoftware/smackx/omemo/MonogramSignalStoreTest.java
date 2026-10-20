package org.jivesoftware.smackx.omemo;

import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import com.negzero.monogram.xmpp.LegacyOmemoTestDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;

class MonogramSignalStoreTest {
    @TempDir Path directory;
    final OmemoDevice own=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),101);
    final OmemoDevice peer=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("bob@example.org"),202);
    final LegacyOmemoTestDatabase database=new LegacyOmemoTestDatabase();
    SignalFileBasedOmemoStore bootstrap() { return new SignalFileBasedOmemoStore(directory.toFile()); }
    MonogramSignalStore store() { return new MonogramSignalStore(bootstrap(),database); }

    @Test void detachedSessionsAndAtomicRatchetJournalWritesRollbackAndReopenWithoutChangingBootstrap() throws Exception {
        SignalFileBasedOmemoStore files=bootstrap();files.storeRawSession(own,peer,new SessionRecord());
        MonogramSignalStore store=store();SessionRecord speculative=store.loadRawSession(own,peer);
        speculative.getSessionState().setLocalRegistrationId(77);
        assertEquals(0,store.loadRawSession(own,peer).getSessionState().getLocalRegistrationId());
        try(LegacyOmemoRecords tx=store.begin(own)) {
            store.withTransaction(own,tx,() -> {
                store.storeRawSession(own,peer,speculative);store.storeOmemoMessageCounter(own,peer,7);
                tx.save(INCOMING,"",1,new byte[]{8});
                assertEquals(77,store.loadRawSession(own,peer).getSessionState().getLocalRegistrationId());return null;
            });
        }
        assertEquals(0,store.loadRawSession(own,peer).getSessionState().getLocalRegistrationId());assertEquals(0,store.loadOmemoMessageCounter(own,peer));
        try(LegacyOmemoRecords tx=store.begin(own)) {
            store.withTransaction(own,tx,() -> { store.storeRawSession(own,peer,speculative);tx.save(INCOMING,"",1,new byte[]{8});return null; });tx.commit();
        }
        MonogramSignalStore reopened=store();
        assertEquals(77,reopened.loadRawSession(own,peer).getSessionState().getLocalRegistrationId());
        assertEquals(0,files.loadRawSession(own,peer).getSessionState().getLocalRegistrationId());
        try(LegacyOmemoRecords tx=reopened.begin(own)) { assertArrayEquals(new byte[]{8},tx.load(INCOMING,"",1)); }
    }
    @Test void tombstonesMergeEnumerationsAndPurgeCannotResurrectBootstrapKeysOrOtherOwnerState() throws Exception {
        SignalFileBasedOmemoStore files=bootstrap();SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();IdentityKeyPair identity=keys.generateOmemoIdentityKeyPair();
        files.storeOmemoIdentityKeyPair(own,identity);files.storeOmemoPreKeys(own,keys.generateOmemoPreKeys(10,2));files.storeRawSession(own,peer,new SessionRecord());
        MonogramSignalStore store=store();assertArrayEquals(identity.serialize(),store.loadOmemoIdentityKeyPair(own).serialize());
        store.removeOmemoPreKey(own,10);store.removeRawSession(own,peer);
        assertEquals(Collections.singleton(11),store.loadOmemoPreKeys(own).keySet());assertTrue(store.loadAllRawSessionsOf(own,peer.getJid()).isEmpty());
        assertNotNull(files.loadOmemoPreKey(own,10));assertNotNull(files.loadRawSession(own,peer));
        OmemoDevice other=new OmemoDevice(peer.getJid(),303);store.storeOmemoIdentityKeyPair(other,keys.generateOmemoIdentityKeyPair());
        store.purgeOwnDeviceKeys(own);MonogramSignalStore reopened=store();
        assertNull(reopened.loadOmemoIdentityKeyPair(own));assertTrue(reopened.loadOmemoPreKeys(own).isEmpty());assertFalse(reopened.containsRawSession(own,peer));
        assertTrue(reopened.localDeviceIdsOf(own.getJid()).isEmpty());assertNotNull(reopened.loadOmemoIdentityKeyPair(other));
        assertNotNull(files.loadOmemoIdentityKeyPair(own));
        reopened.storeOmemoIdentityKeyPair(own,keys.generateOmemoIdentityKeyPair());
        assertNull(reopened.loadOmemoPreKey(own,11),"New identity must not reenable old bootstrap material");
        assertEquals(Collections.singleton(101),reopened.localDeviceIdsOf(own.getJid()));
    }
    @Test void swallowedVoidHookFailureAndCommitFailureCannotPublishStagedSessionOrConsumePrekey() throws Exception {
        MonogramSignalStore store=store();PreKeyRecord pre=new SignalOmemoKeyUtil().generateOmemoPreKeys(11,1).get(11);store.storeOmemoPreKey(own,11,pre);
        try(LegacyOmemoRecords tx=store.begin(own)) {
            assertThrows(IOException.class,() -> store.withTransaction(own,tx,() -> {
                store.storeRawSession(own,peer,new SessionRecord());database.failNextSave=true;
                try { store.removeOmemoPreKey(own,11); } catch(IllegalStateException swallowed) { /* Mimic a void Smack hook caller catching its failure. */ }
                tx.save(INCOMING,"",1,new byte[]{9});return null;
            }));
            assertThrows(IOException.class,tx::commit,"A caller cannot accidentally commit a poisoned transaction");
        }
        assertNotNull(store.loadOmemoPreKey(own,11));assertNull(store.loadRawSession(own,peer));
        try(LegacyOmemoRecords tx=store.begin(own)) {
            store.withTransaction(own,tx,() -> { store.removeOmemoPreKey(own,11);store.storeRawSession(own,peer,new SessionRecord());tx.save(INCOMING,"",1,new byte[]{9});return null; });
            database.failNextCommit=true;assertThrows(IOException.class,tx::commit);
        }
        assertNotNull(store.loadOmemoPreKey(own,11));assertNull(store.loadRawSession(own,peer));
        try(LegacyOmemoRecords tx=store.begin(own)) { assertNull(tx.load(INCOMING,"",1)); }
    }
    @Test void journalOnlyDeviceDoesNotMasqueradeAsAnInitializedIdentityAndMetadataReadsAreDetached() throws Exception {
        MonogramSignalStore store=store();
        try(LegacyOmemoRecords tx=store.begin(own)) { tx.save(INCOMING,"",0,new byte[]{1});tx.commit(); }
        assertTrue(store.localDeviceIdsOf(own.getJid()).isEmpty());
        Date date=new Date(123456);store.setDateOfLastReceivedMessage(own,peer,date);Date loaded=store.getDateOfLastReceivedMessage(own,peer);loaded.setTime(7);
        assertEquals(date,store.getDateOfLastReceivedMessage(own,peer));
        store.setDateOfLastDeviceIdPublication(own,peer,date);store.setDateOfLastSignedPreKeyRenewal(own,date);
        store.storeOmemoMessageCounter(own,peer,42);assertEquals(date,store.getDateOfLastDeviceIdPublication(own,peer));assertEquals(date,store.getDateOfLastSignedPreKeyRenewal(own));assertEquals(42,store.loadOmemoMessageCounter(own,peer));
    }
}
