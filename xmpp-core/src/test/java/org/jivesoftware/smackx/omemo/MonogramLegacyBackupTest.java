package org.jivesoftware.smackx.omemo;

import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import com.negzero.monogram.xmpp.LegacyOmemoTestDatabase;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.SessionRecord;

class MonogramLegacyBackupTest {
    @TempDir Path directory;
    final BareJid alice=JidCreate.bareFromOrThrowUnchecked("alice@example.org"),bob=JidCreate.bareFromOrThrowUnchecked("bob@example.org");
    @Test void snapshotMaterializesMergedBootstrapWithoutOtherOwnersOrResurrectedTombstones() throws Exception {
        File root=directory.resolve("live-bootstrap").toFile();SignalFileBasedOmemoStore files=new SignalFileBasedOmemoStore(root);
        SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();
        OmemoDevice own=new OmemoDevice(alice,101),secondOwn=new OmemoDevice(alice,102),peer=new OmemoDevice(bob,202),otherOwner=new OmemoDevice(bob,303);
        IdentityKeyPair identity=keys.generateOmemoIdentityKeyPair(),secondIdentity=keys.generateOmemoIdentityKeyPair();
        files.storeOmemoIdentityKeyPair(own,identity);files.storeOmemoIdentityKeyPair(secondOwn,secondIdentity);files.storeOmemoIdentityKeyPair(otherOwner,keys.generateOmemoIdentityKeyPair());
        files.storeOmemoPreKeys(own,keys.generateOmemoPreKeys(0,2));files.storeRawSession(own,peer,new SessionRecord());
        files.setDateOfLastReceivedMessage(own,peer,new Date(12345));
        LegacyOmemoTestDatabase isolated=new LegacyOmemoTestDatabase();MonogramSignalStore overlay=new MonogramSignalStore(files,isolated);
        overlay.removeOmemoPreKey(own,1);overlay.storeOmemoMessageCounter(own,peer,399);
        SessionRecord changed=new SessionRecord();changed.getSessionState().setLocalRegistrationId(77);overlay.storeRawSession(own,peer,changed);
        try(LegacyOmemoRecords tx=isolated.begin(alice.toString(),101)) { tx.save(INCOMING,"",1,new byte[]{7,8,9});tx.commit(); }
        Map<String,byte[]> before=tree(root.toPath());
        File copied=directory.resolve("private-bootstrap-copy").toFile();
        MonogramLegacyBackup.materialize(root,copied,alice,isolated,(owner,device)->new HashSet<>(Collections.singleton(bob)));
        Map<String,byte[]> after=tree(root.toPath());assertEquals(before.keySet(),after.keySet());
        for(String path:before.keySet()) assertArrayEquals(before.get(path),after.get(path));
        File[] owners=new File(copied,"OMEMO_Store").listFiles();assertNotNull(owners);assertEquals(1,owners.length);assertEquals(alice.asUrlEncodedString(),owners[0].getName());
        MonogramSignalStore restored=new MonogramSignalStore(new SignalFileBasedOmemoStore(directory.resolve("empty-target-bootstrap").toFile()),isolated);
        assertArrayEquals(identity.serialize(),restored.loadOmemoIdentityKeyPair(own).serialize());
        assertArrayEquals(secondIdentity.serialize(),restored.loadOmemoIdentityKeyPair(secondOwn).serialize());
        assertNotNull(restored.loadOmemoPreKey(own,0));assertNull(restored.loadOmemoPreKey(own,1));
        assertEquals(399,restored.loadOmemoMessageCounter(own,peer));assertEquals(77,restored.loadRawSession(own,peer).getSessionState().getLocalRegistrationId());
        assertEquals(new Date(12345),restored.getDateOfLastReceivedMessage(own,peer));
        assertEquals(new TreeSet<>(Arrays.asList(101,102)),restored.localDeviceIdsOf(alice));assertNull(restored.loadOmemoIdentityKeyPair(otherOwner));
        try(LegacyOmemoRecords tx=isolated.begin(alice.toString(),101)) { assertArrayEquals(new byte[]{7,8,9},tx.load(INCOMING,"",1));assertArrayEquals(new byte[]{1},tx.load(PURGED,"",0)); }
    }
    @Test void corruptBootstrapIsRejectedBeforeItsDeviceSnapshotCommits() throws Exception {
        File root=directory.resolve("bad-bootstrap").toFile();SignalFileBasedOmemoStore files=new SignalFileBasedOmemoStore(root);
        OmemoDevice own=new OmemoDevice(alice,101);files.storeOmemoIdentityKeyPair(own,new SignalOmemoKeyUtil().generateOmemoIdentityKeyPair());
        File key=new File(root,"OMEMO_Store/"+alice.asUrlEncodedString()+"/101/identityKeyPair");Files.write(key.toPath(),new byte[]{1,2,3});
        LegacyOmemoTestDatabase isolated=new LegacyOmemoTestDatabase();
        assertThrows(Exception.class,()->MonogramLegacyBackup.materialize(root,directory.resolve("bad-copy").toFile(),alice,isolated,(owner,device)->Collections.emptySet()));
        assertTrue(isolated.localDevices(alice.toString()).isEmpty());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(key.toPath()));
    }
    private static Map<String,byte[]> tree(Path root) throws Exception {
        Map<String,byte[]> files=new TreeMap<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            for(Path path:(Iterable<Path>)paths::iterator) if(Files.isRegularFile(path)) files.put(root.relativize(path).toString(),Files.readAllBytes(path));
        }
        return files;
    }
}
