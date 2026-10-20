package com.negzero.monogram.xmpp;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
class Omemo2RestoreTest {
    @Test void freshActivePrekeysKeepIdentityAndArchiveDecryptsWithoutRestoringActiveLineage() throws Exception {
        String aliceJid="alice@example.org",bobJid="bob@example.org";
        Omemo2TestDatabase aliceDb=new Omemo2TestDatabase(),archive=new Omemo2TestDatabase(),activeDb=new Omemo2TestDatabase();
        Omemo2Device alice=new Omemo2Device(aliceJid,101,aliceDb),bob=new Omemo2Device(bobJid,202,archive);
        Omemo2Bundle aliceBundle=alice.prepare(),bobBundle=bob.prepare();
        alice.observe(bobJid,202,bobBundle);alice.decide(bobJid,202,bobBundle.identity(),true);
        bob.observe(aliceJid,101,aliceBundle);bob.decide(aliceJid,101,aliceBundle.identity(),true);
        byte[][] queued;
        try(Omemo2Records tx=aliceDb.begin()) { alice.session(tx,bobJid,202,bobBundle);queued=Omemo2Native.encrypt(tx,101,bobJid,202,new byte[]{7,8,9});tx.commit(); }
        try(Omemo2Records source=archive.begin();Omemo2Records target=activeDb.begin()) {
            for(int kind:new int[]{IDENTITY,META,PRE_KEY,PRE_PUBLIC,SIGNED_PRE_KEY,SIGNED_PUBLIC,INCOMING})
                for(int id:source.ids(kind)) target.save(kind,"",id,source.load(kind,"",id));
            target.save(TRUSTED,aliceJid,101,source.load(TRUSTED,aliceJid,101));target.commit();
        }
        Omemo2Device active=new Omemo2Device(bobJid,202,activeDb);
        Omemo2Restore.freshKeys(active,System.currentTimeMillis());
        Omemo2Bundle newBundle=active.prepare();
        assertArrayEquals(bobBundle.identity(),newBundle.identity());assertNotEquals(bobBundle.signedId,newBundle.signedId);
        assertTrue(Arrays.stream(bobBundle.preKeys()).noneMatch(old -> Arrays.stream(newBundle.preKeys()).anyMatch(id -> id==old)));
        try(Omemo2Records tx=activeDb.begin()) {
            assertThrows(Omemo2Native.Failure.class,() -> Omemo2Native.decrypt(tx,202,aliceJid,101,queued[1],true));
        }
        Omemo2ReceiveOnly recovery=new Omemo2ReceiveOnly(active,archive);
        try(Omemo2Records tx=recovery.device.records.begin()) {
            byte[] clear=Omemo2Native.decrypt(tx,202,aliceJid,101,queued[1],true);
            assertArrayEquals(new byte[]{7,8,9},clear);Arrays.fill(clear,(byte)0);tx.commit();
        }
        try(Omemo2Records tx=activeDb.begin()) { assertNull(tx.load(SESSION,aliceJid,101));assertNull(tx.load(SESSION_IDENTITY,aliceJid,101)); }
        try(Omemo2Records tx=archive.begin()) { assertNotNull(tx.load(SESSION,aliceJid,101)); }
        // A fresh outgoing session is built only from new active state, never from archive bytes.
        try(Omemo2Records tx=activeDb.begin()) {
            active.session(tx,aliceJid,101,aliceBundle);byte[][] sent=Omemo2Native.encrypt(tx,202,aliceJid,101,new byte[]{10});
            assertEquals(1,sent[0][0]);tx.commit();
        }
    }
}
