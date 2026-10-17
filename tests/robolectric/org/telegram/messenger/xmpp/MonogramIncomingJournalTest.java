package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import com.negzero.monogram.xmpp.Omemo2IncomingJournal;
import com.negzero.monogram.xmpp.Omemo2Records;
import java.util.Arrays;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramIncomingJournalTest {
    static final String OWNER="alice@example.org",OTHER="carol@example.org",PEER="bob@example.org",FILE="incoming-journal.db";
    Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication();context.deleteDatabase(FILE); }
    @After public void cleanup() { context.deleteDatabase(FILE); }
    private MonogramOmemo2Store open() { return new MonogramOmemo2Store(context,FILE); }
    private void seed(MonogramOmemo2Store store,String owner,int device) throws Exception {
        try(Omemo2Records tx=store.begin(owner,device)) {
            tx.save(Omemo2Records.IDENTITY,"",0,new byte[]{1});tx.save(Omemo2Records.IDENTITY,"",1,new byte[]{2});
            tx.save(Omemo2Records.TRUSTED,PEER,202,new byte[32]);
            tx.save(Omemo2Records.PRE_KEY,"",11,new byte[]{3});tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{4});
            Omemo2IncomingJournal.rotate(tx);tx.save(Omemo2Records.INCOMING,"",1,new byte[]{5});tx.commit();
        }
    }
    @Test public void ratchetAndJournalCommitTogetherAcrossReopenAndFailedAdmissionRollsBothBack() throws Exception {
        try(MonogramOmemo2Store store=open()) {
            seed(store,OWNER,101);
            try(Omemo2Records tx=store.begin(OWNER,101)) {
                tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{6});tx.save(Omemo2Records.PRE_KEY,"",11,null);
                tx.save(Omemo2Records.INCOMING,"",2,new byte[]{7});tx.commit();
            }
        }
        try(MonogramOmemo2Store store=open()) {
            try(Omemo2Records tx=store.begin(OWNER,101)) {
                assertArrayEquals(new byte[]{6},tx.load(Omemo2Records.SESSION,PEER,202));
                assertNull(tx.load(Omemo2Records.PRE_KEY,"",11));assertArrayEquals(new byte[]{7},tx.load(Omemo2Records.INCOMING,"",2));
            }
            try(Omemo2Records tx=store.begin(OWNER,101)) {
                tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{8});
                try { tx.save(Omemo2Records.INCOMING,"",3,new byte[Omemo2IncomingJournal.MAX_ENTRY_BYTES+1]);fail("Oversized journal admission"); }
                catch(IllegalArgumentException expected) {}
            }
            store.getWritableDatabase().execSQL("CREATE TRIGGER reject_incoming BEFORE INSERT ON records WHEN NEW.kind=12 BEGIN SELECT RAISE(ABORT,'journal write refused'); END");
            try(Omemo2Records tx=store.begin(OWNER,101)) {
                tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{9});tx.save(Omemo2Records.PRE_KEY,"",12,new byte[]{10});
                try { tx.save(Omemo2Records.INCOMING,"",3,new byte[]{11});fail("SQLite journal write must fail"); }
                catch(SQLiteException expected) {}
            }
            store.getWritableDatabase().execSQL("DROP TRIGGER reject_incoming");
        }
        try(MonogramOmemo2Store store=open();Omemo2Records tx=store.begin(OWNER,101)) {
            assertArrayEquals(new byte[]{6},tx.load(Omemo2Records.SESSION,PEER,202));
            assertNull(tx.load(Omemo2Records.PRE_KEY,"",12));assertNull(tx.load(Omemo2Records.INCOMING,"",3));
            assertArrayEquals(new int[]{0,1,2},tx.ids(Omemo2Records.INCOMING));
        }
    }
    @Test public void logoutRotatesAllExistingOwnerDevicesAndPreservesOtherOwnersKeysTrustAndDeviceSelection() throws Exception {
        int selected;byte[] firstEpoch,secondEpoch,otherEpoch;
        try(MonogramOmemo2Store store=open()) {
            selected=store.device(OWNER);int second=selected==17 ? 18 : 17;
            seed(store,OWNER,selected);seed(store,OWNER,second);seed(store,OTHER,selected);
            try(Omemo2Records tx=store.begin(OWNER,selected)) { firstEpoch=tx.load(Omemo2Records.INCOMING,"",0); }
            try(Omemo2Records tx=store.begin(OWNER,second)) { secondEpoch=tx.load(Omemo2Records.INCOMING,"",0); }
            try(Omemo2Records tx=store.begin(OTHER,selected)) { otherEpoch=tx.load(Omemo2Records.INCOMING,"",0); }
            store.clearIncoming(OWNER);store.clearIncoming("unknown@example.org");
            for(int device:new int[]{selected,second}) try(Omemo2Records tx=store.begin(OWNER,device)) {
                assertArrayEquals(new int[]{0},tx.ids(Omemo2Records.INCOMING));
                assertFalse(Arrays.equals(device==selected ? firstEpoch : secondEpoch,tx.load(Omemo2Records.INCOMING,"",0)));
                assertArrayEquals(new byte[]{1},tx.load(Omemo2Records.IDENTITY,"",0));assertArrayEquals(new byte[]{2},tx.load(Omemo2Records.IDENTITY,"",1));
                assertArrayEquals(new byte[32],tx.load(Omemo2Records.TRUSTED,PEER,202));
                assertArrayEquals(new byte[]{3},tx.load(Omemo2Records.PRE_KEY,"",11));assertArrayEquals(new byte[]{4},tx.load(Omemo2Records.SESSION,PEER,202));
            }
            try(Omemo2Records tx=store.begin(OTHER,selected)) {
                assertArrayEquals(otherEpoch,tx.load(Omemo2Records.INCOMING,"",0));assertArrayEquals(new byte[]{5},tx.load(Omemo2Records.INCOMING,"",1));
            }
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT owner,device FROM own_devices",null)) {
                assertEquals(1,rows.getCount());assertTrue(rows.moveToFirst());assertEquals(OWNER,rows.getString(0));assertEquals(selected,rows.getInt(1));
            }
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM records WHERE owner='unknown@example.org'",null)) { rows.moveToFirst();assertEquals(0,rows.getInt(0)); }
        }
        try(MonogramOmemo2Store store=open();Omemo2Records tx=store.begin(OWNER,selected)) { assertArrayEquals(new int[]{0},tx.ids(Omemo2Records.INCOMING)); }
    }
    @Test public void onlyJournalSlotsAcceptLargerValuesAndRotationRollsBackWithoutCommit() throws Exception {
        try(MonogramOmemo2Store store=open()) {
            seed(store,OWNER,101);byte[] epoch;
            try(Omemo2Records tx=store.begin(OWNER,101)) { epoch=tx.load(Omemo2Records.INCOMING,"",0); }
            try(Omemo2Records tx=store.begin(OWNER,101)) { Omemo2IncomingJournal.rotate(tx); }
            try(Omemo2Records tx=store.begin(OWNER,101)) {
                assertArrayEquals(epoch,tx.load(Omemo2Records.INCOMING,"",0));assertNotNull(tx.load(Omemo2Records.INCOMING,"",1));
                byte[] larger=new byte[1024*1024+1];tx.save(Omemo2Records.INCOMING,"",Omemo2IncomingJournal.MAX_ENTRIES,larger);
                assertEquals(larger.length,tx.load(Omemo2Records.INCOMING,"",Omemo2IncomingJournal.MAX_ENTRIES).length);
                try { tx.save(Omemo2Records.IDENTITY,"",0,larger);fail("Old record limit changed"); } catch(IllegalArgumentException expected) {}
                for(int id:new int[]{-1,Omemo2IncomingJournal.MAX_ENTRIES+1}) {
                    try { tx.load(Omemo2Records.INCOMING,"",id);fail("Invalid journal slot"); } catch(IllegalArgumentException expected) {}
                }
                try { tx.load(Omemo2Records.INCOMING,PEER,1);fail("Journal must be local"); } catch(IllegalArgumentException expected) {}
                try { tx.save(Omemo2Records.TRUSTED,PEER,202,new byte[31]);fail("Trust size changed"); } catch(IllegalArgumentException expected) {}
            }
        }
    }
}
