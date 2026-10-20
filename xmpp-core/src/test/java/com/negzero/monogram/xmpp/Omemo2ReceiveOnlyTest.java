package com.negzero.monogram.xmpp;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

class Omemo2ReceiveOnlyTest {
    @Test void archiveNeverWritesTrustOrSendingKeysAndUsesCurrentActiveTrust() throws Exception {
        Omemo2TestDatabase live=new Omemo2TestDatabase(),archive=new Omemo2TestDatabase();
        byte[] trusted=new byte[32];Arrays.fill(trusted,(byte)9);
        try(Omemo2Records tx=archive.begin()) { tx.save(TRUSTED,"alice@example.org",101,trusted);tx.save(PRE_KEY,"",11,new byte[]{3});tx.commit(); }
        Omemo2Records.Factory guarded=Omemo2ReceiveOnly.guard(live,archive);
        try(Omemo2Records tx=guarded.begin()) {
            assertNull(tx.load(TRUSTED,"alice@example.org",101),"Archived trust must not override current distrust");
            assertThrows(GeneralSecurityException.class,() -> tx.save(TRUSTED,"alice@example.org",101,trusted));
            assertThrows(GeneralSecurityException.class,() -> tx.save(PRE_KEY,"",12,new byte[]{4}));
            assertThrows(GeneralSecurityException.class,() -> tx.save(SIGNED_PRE_KEY,"",1,new byte[]{4}));
            assertThrows(GeneralSecurityException.class,() -> tx.save(IDENTITY,"",0,new byte[]{4}));
            tx.save(PRE_KEY,"",11,null);tx.save(SESSION,"alice@example.org",101,new byte[]{8});tx.commit();
        }
        try(Omemo2Records tx=live.begin()) { tx.save(TRUSTED,"alice@example.org",101,trusted);tx.commit(); }
        try(Omemo2Records tx=guarded.begin()) { assertArrayEquals(trusted,tx.load(TRUSTED,"alice@example.org",101)); }
        try(Omemo2Records tx=live.begin()) { assertNull(tx.load(SESSION,"alice@example.org",101)); }
    }
    @Test void savedArchiveFramesSuppressOldAcksAndHaveIndependentJournalProvenance() throws Exception {
        Omemo2IncomingJournalTest fixture=new Omemo2IncomingJournalTest();fixture.setup();
        org.jivesoftware.smack.packet.Message outer=fixture.outer("restored",1);
        fixture.save(outer,fixture.clear(outer,"Recovered"),fixture.ack());
        Omemo2TestDatabase live=new Omemo2TestDatabase();byte[] trusted=new byte[32];Arrays.fill(trusted,(byte)0xaa);
        try(Omemo2Records tx=fixture.database.begin()) { tx.save(IDENTITY,"",0,new byte[]{1});tx.commit(); }
        try(Omemo2Records tx=live.begin()) {
            tx.save(IDENTITY,"",0,new byte[]{1});tx.save(IDENTITY,"",1,new byte[32]);
            tx.save(META,"",1,ByteBuffer.allocate(20).putInt(0x4d523031).array());
            tx.save(TRUSTED,fixture.peer,101,trusted);tx.commit();
        }
        Omemo2Device active=new Omemo2Device(fixture.own,202,live);
        Omemo2ReceiveOnly recovery=new Omemo2ReceiveOnly(active,fixture.database);
        XmppOmemo2.Received restored=recovery.journal.pending().get(0);
        assertEquals("Recovered",restored.message.body);assertNull(restored.acknowledgement);assertFalse(restored.replenish);
        assertSame(recovery.journal,restored.journal);assertNotSame(fixture.journal,restored.journal);
        try(Omemo2Records tx=live.begin()) { tx.save(TRUSTED,fixture.peer,101,null);tx.commit(); }
        assertThrows(GeneralSecurityException.class,recovery.journal::pending);
        // No obsolete identity/session is promoted back into the active outgoing store.
        try(Omemo2Records tx=live.begin()) { assertNull(tx.load(SESSION,fixture.peer,101)); }
    }
    @Test void manifestPinnedHistoricalOwnDeviceNeverChangesActiveIdentityOrDevice() throws Exception {
        Omemo2TestDatabase live=new Omemo2TestDatabase(),historical=new Omemo2TestDatabase();
        byte[] previous=new byte[32];Arrays.fill(previous,(byte)1);
        try(Omemo2Records tx=live.begin()) { tx.save(IDENTITY,"",0,new byte[]{1});tx.save(IDENTITY,"",1,new byte[32]);tx.save(META,"",1,ByteBuffer.allocate(20).putInt(0x4d523031).array());tx.commit(); }
        try(Omemo2Records tx=historical.begin()) { tx.save(IDENTITY,"",0,new byte[]{2});tx.save(IDENTITY,"",1,previous);tx.commit(); }
        Omemo2Device active=new Omemo2Device("alice@example.org",101,live);
        Omemo2ReceiveOnly archive=new Omemo2ReceiveOnly(active,303,XmppSession.hex(previous),historical);
        assertEquals(303,archive.device.id);assertEquals(101,active.id);assertTrue(archive.journal.pending().isEmpty());
        try(Omemo2Records tx=live.begin()) { assertArrayEquals(new byte[]{1},tx.load(IDENTITY,"",0)); }
        assertThrows(GeneralSecurityException.class,() -> new Omemo2ReceiveOnly(active,303,String.join("",java.util.Collections.nCopies(64,"f")),historical));
    }
    @Test void retryAllowlistNeverMasksTrustDuplicatesOrStorageErrors() {
        assertTrue(Omemo2ReceiveOnly.recoverable(-1008));assertTrue(Omemo2ReceiveOnly.recoverable(-1003));
        assertFalse(Omemo2ReceiveOnly.recoverable(-1001));assertFalse(Omemo2ReceiveOnly.recoverable(-1010));
        assertFalse(Omemo2ReceiveOnly.recoverable(-1100));assertFalse(Omemo2ReceiveOnly.recoverable(-1));
    }
}
