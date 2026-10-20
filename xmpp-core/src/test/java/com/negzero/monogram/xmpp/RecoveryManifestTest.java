package com.negzero.monogram.xmpp;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryManifestTest {
    String hex(char value,int size) { char[] chars=new char[size];Arrays.fill(chars,value);return new String(chars); }
    RecoveryManifest manifest(List<RecoveryManifest.Archive> archives) throws Exception {
        return new RecoveryManifest("alice@example.org",UUID.randomUUID().toString(),101,202,hex('a',64),hex('b',64),"4c523031"+hex('1',32),"4d523031"+hex('2',32),false,archives);
    }
    @Test void repeatedMigrationKeepsAllMutableArchiveIdsIncludingSameDeviceAndDifferentOwnDevices() throws Exception {
        RecoveryManifest.Archive oldest=new RecoveryManifest.Archive(UUID.randomUUID().toString(),RecoveryManifest.Protocol.LEGACY,101,hex('a',64));
        RecoveryManifest.Archive historical=new RecoveryManifest.Archive(UUID.randomUUID().toString(),RecoveryManifest.Protocol.LEGACY,303,hex('c',64));
        RecoveryManifest first=manifest(Arrays.asList(oldest,historical));
        RecoveryManifest.Archive retired=new RecoveryManifest.Archive(UUID.randomUUID().toString(),RecoveryManifest.Protocol.LEGACY,101,hex('a',64));
        RecoveryManifest next=first.next(UUID.randomUUID().toString(),"4c523031"+hex('3',32),"4d523031"+hex('4',32),Collections.singletonList(retired),false);
        ByteArrayOutputStream out=new ByteArrayOutputStream();next.write(out);RecoveryManifest replay=RecoveryManifest.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(3,replay.archives.size());assertEquals(retired.id,replay.archives.get(0).id);assertEquals(oldest.id,replay.archives.get(1).id);assertEquals(historical.id,replay.archives.get(2).id);
        assertEquals(303,replay.archives.get(2).device);assertEquals(first.legacyFingerprint,replay.legacyFingerprint);assertEquals(first.modernDevice,replay.modernDevice);assertFalse(replay.reconnectApproved);
    }
    @Test void duplicateArchiveIdsTraversalForeignOwnerAndTrailingDataFailClosed() throws Exception {
        RecoveryManifest.Archive entry=new RecoveryManifest.Archive(UUID.randomUUID().toString(),RecoveryManifest.Protocol.MODERN,202,hex('a',64));
        assertThrows(IOException.class,() -> manifest(Arrays.asList(entry,entry)));
        assertThrows(IllegalArgumentException.class,() -> new RecoveryManifest.Archive("../keys",RecoveryManifest.Protocol.LEGACY,101,hex('a',64)));
        assertThrows(IOException.class,() -> new RecoveryManifest("alice@example.org/resource",UUID.randomUUID().toString(),101,202,hex('a',64),hex('b',64),"4c523031"+hex('1',32),"4d523031"+hex('2',32),false,Collections.emptyList()));
        ByteArrayOutputStream out=new ByteArrayOutputStream();manifest(Collections.singletonList(entry)).write(out);out.write(9);
        assertThrows(IOException.class,() -> RecoveryManifest.read(new ByteArrayInputStream(out.toByteArray())));
    }
}
