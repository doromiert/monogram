package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import com.negzero.monogram.xmpp.LegacyIncomingJournal;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramLegacyOmemoStoreTest {
    private static final String OWNER="alice@example.org",OTHER="carol@example.org",PEER="bob@example.org",FILE="legacy-omemo-test.db";
    private Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication();context.deleteDatabase(FILE); }
    @After public void cleanup() { context.deleteDatabase(FILE); }
    private MonogramLegacyOmemoStore open() { return new MonogramLegacyOmemoStore(context,FILE); }
    private void seed(MonogramLegacyOmemoStore store,String owner,int device) throws Exception {
        try(LegacyOmemoRecords tx=store.begin(owner,device)) {
            tx.save(LegacyOmemoRecords.IDENTITY_PAIR,"",0,new byte[]{1,20});
            tx.save(LegacyOmemoRecords.IDENTITY,PEER,202,new byte[]{1,21});
            tx.save(LegacyOmemoRecords.PRE_KEY,"",11,new byte[]{1,22});
            tx.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[]{1,23});
            LegacyIncomingJournal.rotate(tx);tx.save(LegacyOmemoRecords.INCOMING,"",1,new byte[]{24});tx.commit();
        }
    }
    private LegacyOmemoRecords.Retired lineage(String generation) throws Exception {
        return new LegacyOmemoRecords.Retired(PEER,202,String.join("",java.util.Collections.nCopies(64,"a")),generation);
    }
    @Test public void versionOneUpgradePreservesRowsAndAddsEmptyRetiredNamespace() throws Exception {
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);store.getWritableDatabase().execSQL("DROP TABLE retired_records");store.getWritableDatabase().setVersion(1);
        }
        try(MonogramLegacyOmemoStore store=open();LegacyOmemoRecords tx=store.begin(OWNER,101)) {
            assertEquals(2,store.getWritableDatabase().getVersion());assertTrue(tx.retired().isEmpty());
            assertArrayEquals(new byte[]{1,23},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
        }
    }
    @Test public void retiredScopesAreAtomicImmutableAndOwnerIsolatedAndLogoutOnlyRevokesPlaintext() throws Exception {
        LegacyOmemoRecords.Retired branch=lineage("11111111-1111-4111-8111-111111111111"),next=lineage("22222222-2222-4222-8222-222222222222");
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);seed(store,OTHER,101);
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                tx.saveRetired(branch,LegacyOmemoRecords.IDENTITY_PAIR,0,new byte[]{1,60});tx.saveRetired(branch,LegacyOmemoRecords.IDENTITY,0,new byte[]{1,61});
                tx.saveRetired(branch,LegacyOmemoRecords.SESSION,0,new byte[]{1,62});tx.saveRetired(branch,LegacyOmemoRecords.RETIRED_ORIGINAL_SESSION,0,new byte[]{1,63});
                tx.saveRetired(branch,LegacyOmemoRecords.INCOMING,1,new byte[]{64});tx.commit();
            }
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                tx.saveRetired(branch,LegacyOmemoRecords.SESSION,0,new byte[]{1,70});tx.saveRetired(next,LegacyOmemoRecords.IDENTITY,0,new byte[]{1,71});
                tx.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[]{1,72});
            }
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                assertArrayEquals(new byte[]{1,62},tx.loadRetired(branch,LegacyOmemoRecords.SESSION,0));assertNull(tx.loadRetired(next,LegacyOmemoRecords.IDENTITY,0));
                assertArrayEquals(new byte[]{1,23},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
                try { tx.saveRetired(branch,LegacyOmemoRecords.RETIRED_ORIGINAL_SESSION,0,new byte[]{1,90});fail("Original snapshot was mutable"); } catch(IllegalStateException expected) { }
            }
            for(String owner:new String[]{OWNER,OTHER}) try(LegacyOmemoRecords tx=store.begin(owner,owner.equals(OWNER)?102:101)) { assertTrue(tx.retired().isEmpty()); }
            store.clearIncoming(OWNER);
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                assertNull(tx.loadRetired(branch,LegacyOmemoRecords.INCOMING,1));assertNotNull(tx.loadRetired(branch,LegacyOmemoRecords.INCOMING,0));
                assertArrayEquals(new byte[]{1,62},tx.loadRetired(branch,LegacyOmemoRecords.SESSION,0));assertArrayEquals(new byte[]{1,63},tx.loadRetired(branch,LegacyOmemoRecords.RETIRED_ORIGINAL_SESSION,0));
            }
        }
    }
    @Test public void ratchetJournalAndOpaqueTombstonesCommitTogetherAcrossReopen() throws Exception {
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                tx.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[]{1,30});
                tx.save(LegacyOmemoRecords.PRE_KEY,"",11,new byte[]{0});
                tx.save(LegacyOmemoRecords.INCOMING,"",2,new byte[]{31});tx.commit();
            }
        }
        try(MonogramLegacyOmemoStore store=open();LegacyOmemoRecords tx=store.begin(OWNER,101)) {
            assertArrayEquals(new byte[]{1,30},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
            assertArrayEquals(new byte[]{0},tx.load(LegacyOmemoRecords.PRE_KEY,"",11));
            assertArrayEquals(new byte[]{31},tx.load(LegacyOmemoRecords.INCOMING,"",2));
            byte[] detached=tx.load(LegacyOmemoRecords.SESSION,PEER,202);detached[1]=99;
            assertArrayEquals(new byte[]{1,30},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
        }
    }
    @Test public void failedAdmissionAndSqliteFailureRollBackRatchetAndTombstoneMutations() throws Exception {
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                tx.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[]{1,40});
                tx.save(LegacyOmemoRecords.PRE_KEY,"",11,new byte[]{0});
                try { tx.save(LegacyOmemoRecords.INCOMING,"",2,new byte[LegacyIncomingJournal.MAX_ENTRY_BYTES+1]);fail("Oversized admission accepted"); }
                catch(IllegalArgumentException expected) {}
            }
            store.getWritableDatabase().execSQL("CREATE TRIGGER reject_incoming BEFORE INSERT ON records WHEN NEW.kind=11 BEGIN SELECT RAISE(ABORT,'journal refused'); END");
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                tx.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[]{1,41});
                tx.save(LegacyOmemoRecords.PRE_KEY,"",11,new byte[]{0});
                try { tx.save(LegacyOmemoRecords.INCOMING,"",2,new byte[]{42});fail("SQLite admission did not fail"); }
                catch(SQLiteException expected) {}
            }
            store.getWritableDatabase().execSQL("DROP TRIGGER reject_incoming");
        }
        try(MonogramLegacyOmemoStore store=open();LegacyOmemoRecords tx=store.begin(OWNER,101)) {
            assertArrayEquals(new byte[]{1,23},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
            assertArrayEquals(new byte[]{1,22},tx.load(LegacyOmemoRecords.PRE_KEY,"",11));
            assertNull(tx.load(LegacyOmemoRecords.INCOMING,"",2));
        }
    }
    @Test public void scopesEnumerationDeletionAndSignedPrekeyIdentifiersRemainIndependent() throws Exception {
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);seed(store,OWNER,102);seed(store,OTHER,101);
            assertEquals(new TreeSet<>(Arrays.asList(101,102)),store.localDevices(OWNER));
            assertEquals(new TreeSet<>(Arrays.asList(101)),store.localDevices(OTHER));
            assertTrue(store.localDevices("unknown@example.org").isEmpty());
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                tx.save(LegacyOmemoRecords.PRE_KEY,"",Integer.MIN_VALUE,new byte[]{0});
                tx.save(LegacyOmemoRecords.PRE_KEY,"",0,new byte[]{1,50});
                assertArrayEquals(new int[]{Integer.MIN_VALUE,0,11},tx.ids(LegacyOmemoRecords.PRE_KEY,""));
                assertArrayEquals(new int[]{202},tx.ids(LegacyOmemoRecords.SESSION,PEER));
                assertNull(tx.load(LegacyOmemoRecords.SESSION,OTHER,202));
                tx.save(LegacyOmemoRecords.SESSION,PEER,202,null);tx.commit();
            }
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) { assertNull(tx.load(LegacyOmemoRecords.SESSION,PEER,202)); }
            for(String owner:new String[]{OWNER,OTHER}) try(LegacyOmemoRecords tx=store.begin(owner,owner.equals(OWNER) ? 102 : 101)) {
                assertArrayEquals(new byte[]{1,23},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
            }
        }
    }
    @Test public void transactionOwnershipNestingAndRecordBoundariesAreEnforced() throws Exception {
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);
            LegacyOmemoRecords outer=store.begin(OWNER,101);
            try {
                AtomicReference<Throwable> failure=new AtomicReference<>();
                Thread foreign=new Thread(() -> { try { outer.close(); } catch(Throwable expected) { failure.set(expected); } });
                foreign.start();foreign.join();assertTrue(failure.get() instanceof IllegalStateException);
                try(LegacyOmemoRecords inner=store.begin(OWNER,101)) {
                    try { outer.load(LegacyOmemoRecords.IDENTITY_PAIR,"",0);fail("Out-of-order nesting accepted"); }
                    catch(IllegalStateException expected) {}
                    inner.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[]{1,60});inner.commit();
                }
                // A committed inner transaction remains contingent on its outer transaction.
            } finally { outer.close(); }
            try { outer.load(LegacyOmemoRecords.IDENTITY_PAIR,"",0);fail("Closed transaction accepted"); }
            catch(IllegalStateException expected) {}
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                assertArrayEquals(new byte[]{1,23},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
                byte[] large=new byte[1024*1024+2];
                tx.save(LegacyOmemoRecords.INCOMING,"",LegacyIncomingJournal.MAX_ENTRIES,large);
                for(int id:new int[]{-1,LegacyIncomingJournal.MAX_ENTRIES+1}) {
                    try { tx.load(LegacyOmemoRecords.INCOMING,"",id);fail("Invalid journal slot accepted"); } catch(IllegalArgumentException expected) {}
                }
                try { tx.load(LegacyOmemoRecords.SESSION,PEER+"/phone",202);fail("Resource JID accepted"); } catch(Exception expected) {}
                try { tx.load(LegacyOmemoRecords.IDENTITY_PAIR,PEER,0);fail("Local key accepted peer"); } catch(IllegalArgumentException expected) {}
                try { tx.load(LegacyOmemoRecords.DEVICE_LIST,PEER,1);fail("Device list accepted per-device slot"); } catch(IllegalArgumentException expected) {}
                try { tx.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[0]);fail("Empty record accepted"); } catch(IllegalArgumentException expected) {}
            }
            try { store.begin(OWNER+"/phone",101);fail("Resource owner accepted"); } catch(Exception expected) {}
            try { store.begin(OWNER,0);fail("Zero device accepted"); } catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void logoutRotatesOnlyOwnerJournalsWithoutAllocatingOrRemovingCryptographicState() throws Exception {
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);seed(store,OWNER,102);seed(store,OTHER,101);
            byte[] epoch,otherEpoch;
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) { epoch=tx.load(LegacyOmemoRecords.INCOMING,"",0); }
            try(LegacyOmemoRecords tx=store.begin(OTHER,101)) { otherEpoch=tx.load(LegacyOmemoRecords.INCOMING,"",0); }
            store.clearIncoming(OWNER);store.clearIncoming("unknown@example.org");
            assertTrue(store.localDevices("unknown@example.org").isEmpty());
            for(int device:new int[]{101,102}) try(LegacyOmemoRecords tx=store.begin(OWNER,device)) {
                assertArrayEquals(new int[]{0},tx.ids(LegacyOmemoRecords.INCOMING,""));
                assertFalse(Arrays.equals(epoch,tx.load(LegacyOmemoRecords.INCOMING,"",0)));
                assertArrayEquals(new byte[]{1,20},tx.load(LegacyOmemoRecords.IDENTITY_PAIR,"",0));
                assertArrayEquals(new byte[]{1,21},tx.load(LegacyOmemoRecords.IDENTITY,PEER,202));
                assertArrayEquals(new byte[]{1,22},tx.load(LegacyOmemoRecords.PRE_KEY,"",11));
                assertArrayEquals(new byte[]{1,23},tx.load(LegacyOmemoRecords.SESSION,PEER,202));
            }
            try(LegacyOmemoRecords tx=store.begin(OTHER,101)) {
                assertArrayEquals(otherEpoch,tx.load(LegacyOmemoRecords.INCOMING,"",0));
                assertArrayEquals(new byte[]{24},tx.load(LegacyOmemoRecords.INCOMING,"",1));
            }
            try(Cursor rows=store.getReadableDatabase().rawQuery("PRAGMA synchronous",null)) { assertTrue(rows.moveToFirst());assertEquals(2,rows.getInt(0)); }
            assertEquals(MonogramLegacyOmemoStore.VERSION,store.getWritableDatabase().getVersion());
        }
    }
    @Test public void largeExistingSessionBytesSurviveReopenAndOversizedWritesPreservePreviousState() throws Exception {
        byte[] large=new byte[LegacyOmemoRecords.MAX_RECORD_BYTES];
        for(int at=0;at<large.length;at++) large[at]=(byte)(at%251);
        try(MonogramLegacyOmemoStore store=open();LegacyOmemoRecords tx=store.begin(OWNER,101)) {
            tx.save(LegacyOmemoRecords.SESSION,PEER,202,large);tx.commit();
        }
        try(MonogramLegacyOmemoStore store=open()) {
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                assertArrayEquals(large,tx.load(LegacyOmemoRecords.SESSION,PEER,202));
                try { tx.save(LegacyOmemoRecords.SESSION,PEER,202,new byte[LegacyOmemoRecords.MAX_RECORD_BYTES+1]);fail("Oversized session accepted"); }
                catch(IllegalArgumentException expected) {}
            }
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) { assertArrayEquals(large,tx.load(LegacyOmemoRecords.SESSION,PEER,202)); }
        }
    }
    @Test public void explicitPurgeIsAtomicAndScopedAndOpaquePurgeMarkerRemainsEnumerable() throws Exception {
        try(MonogramLegacyOmemoStore store=open()) {
            seed(store,OWNER,101);seed(store,OWNER,102);seed(store,OTHER,101);
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) { tx.purge(); }
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                assertNotNull(tx.load(LegacyOmemoRecords.IDENTITY_PAIR,"",0));
                tx.purge();tx.save(LegacyOmemoRecords.PURGED,"",0,new byte[]{1});tx.commit();
            }
            try(LegacyOmemoRecords tx=store.begin(OWNER,101)) {
                assertNull(tx.load(LegacyOmemoRecords.IDENTITY_PAIR,"",0));assertNull(tx.load(LegacyOmemoRecords.SESSION,PEER,202));
                assertArrayEquals(new int[0],tx.ids(LegacyOmemoRecords.INCOMING,""));
                assertArrayEquals(new byte[]{1},tx.load(LegacyOmemoRecords.PURGED,"",0));
            }
            assertEquals(new TreeSet<>(Arrays.asList(101,102)),store.localDevices(OWNER));
            for(String owner:new String[]{OWNER,OTHER}) try(LegacyOmemoRecords tx=store.begin(owner,owner.equals(OWNER) ? 102 : 101)) {
                assertNotNull(tx.load(LegacyOmemoRecords.IDENTITY_PAIR,"",0));assertNotNull(tx.load(LegacyOmemoRecords.SESSION,PEER,202));
            }
        }
    }
}
