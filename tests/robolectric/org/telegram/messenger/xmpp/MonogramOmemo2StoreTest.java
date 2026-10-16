package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.Omemo2Records;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class)
public class MonogramOmemo2StoreTest {
    private static final String ACCOUNT = "alice@example.org", PEER = "bob@example.org";
    @Test public void productionPathIsExcludedFromAutomaticBackups() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationContext = context;
        java.io.File path = new java.io.File(context.getNoBackupFilesDir(),"monogram-omemo2-0.db");
        try (MonogramOmemo2Store database = new MonogramOmemo2Store(0)) {
            assertEquals(path.getCanonicalPath(),new java.io.File(database.getWritableDatabase().getPath()).getCanonicalPath());
        } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(path); }
    }
    @Test public void atomicRecordsPersistWithAccountDeviceAndProtocolIsolation() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("omemo2-test.db");
        byte[] identity = {1,2,3}, pre = {4,5,6}, session = {7,8,9};
        try (MonogramOmemo2Store database = new MonogramOmemo2Store(context,"omemo2-test.db")) {
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) {
                tx.save(Omemo2Records.IDENTITY,"",0,identity); tx.save(Omemo2Records.PRE_KEY,"",11,pre);
                tx.save(Omemo2Records.SESSION,PEER,202,session); tx.commit();
            }
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) {
                tx.save(Omemo2Records.PRE_KEY,"",11,null); tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{10});
                // A failed native operation closes without committing all callback writes.
            }
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) {
                assertArrayEquals(identity,tx.load(Omemo2Records.IDENTITY,"",0));
                assertArrayEquals(pre,tx.load(Omemo2Records.PRE_KEY,"",11));
                assertArrayEquals(session,tx.load(Omemo2Records.SESSION,PEER,202));
            }
            for (String owner : new String[]{ACCOUNT,"other@example.org"}) {
                try (Omemo2Records tx = database.begin(owner,102)) { assertNull(tx.load(Omemo2Records.IDENTITY,"",0)); }
            }
            try (Omemo2Records tx = database.begin("other@example.org",101)) { assertNull(tx.load(Omemo2Records.IDENTITY,"",0)); }
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) {
                assertNull(tx.load(Omemo2Records.SESSION,"different@example.org",202));
                assertNull(tx.load(Omemo2Records.SESSION,PEER,203));
                assertNull(tx.load(Omemo2Records.SIGNED_PRE_KEY,"",11));
                tx.save(Omemo2Records.PRE_KEY,"",11,null); tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{10}); tx.commit();
            }
        }
        try (MonogramOmemo2Store reopened = new MonogramOmemo2Store(context,"omemo2-test.db")) {
            try (Omemo2Records tx = reopened.begin(ACCOUNT,101)) {
                assertArrayEquals(identity,tx.load(Omemo2Records.IDENTITY,"",0)); assertNull(tx.load(Omemo2Records.PRE_KEY,"",11));
                assertArrayEquals(new byte[]{10},tx.load(Omemo2Records.SESSION,PEER,202));
            }
        } finally { context.deleteDatabase("omemo2-test.db"); }
    }
    @Test public void trustChangesInvalidateOnlyTheAffectedModernSessionAtomically() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("omemo2-trust.db");
        byte[] pin = new byte[32], changed = new byte[32]; Arrays.fill(changed,(byte)7);
        try (MonogramOmemo2Store database = new MonogramOmemo2Store(context,"omemo2-trust.db")) {
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) {
                tx.save(Omemo2Records.TRUSTED,PEER,202,pin); tx.save(Omemo2Records.TRUSTED,PEER,203,pin);
                tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{1}); tx.save(Omemo2Records.SESSION_IDENTITY,PEER,202,pin);
                tx.save(Omemo2Records.SESSION,PEER,203,new byte[]{2}); tx.commit();
            }
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) { tx.save(Omemo2Records.TRUSTED,PEER,202,changed); }
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) {
                assertArrayEquals(pin,tx.load(Omemo2Records.TRUSTED,PEER,202)); assertArrayEquals(new byte[]{1},tx.load(Omemo2Records.SESSION,PEER,202));
                tx.save(Omemo2Records.TRUSTED,PEER,202,pin); assertNotNull(tx.load(Omemo2Records.SESSION,PEER,202));
                tx.save(Omemo2Records.TRUSTED,PEER,202,changed); tx.commit();
            }
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) {
                assertNull(tx.load(Omemo2Records.SESSION,PEER,202)); assertNull(tx.load(Omemo2Records.SESSION_IDENTITY,PEER,202));
                assertArrayEquals(new byte[]{2},tx.load(Omemo2Records.SESSION,PEER,203)); assertArrayEquals(changed,tx.load(Omemo2Records.TRUSTED,PEER,202));
                tx.save(Omemo2Records.SESSION,PEER,202,new byte[]{3}); tx.save(Omemo2Records.TRUSTED,PEER,202,null);
                assertNull(tx.load(Omemo2Records.SESSION,PEER,202)); tx.commit();
            }
            try (Omemo2Records tx = database.begin(ACCOUNT,101)) { assertNull(tx.load(Omemo2Records.TRUSTED,PEER,202)); }
        } finally { context.deleteDatabase("omemo2-trust.db"); }
    }
    @Test public void closedTransactionsAndMalformedTrustCannotBeUsed() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication(); context.deleteDatabase("omemo2-boundary.db");
        try (MonogramOmemo2Store database = new MonogramOmemo2Store(context,"omemo2-boundary.db")) {
            Omemo2Records tx = database.begin(ACCOUNT,101);
            try {
                try { tx.save(Omemo2Records.TRUSTED,PEER,202,new byte[31]); fail("Invalid identity pin"); }
                catch (IllegalArgumentException expected) {}
                try { tx.load(Omemo2Records.SESSION,PEER+"/resource",202); fail("Full JID in direct-session scope"); }
                catch (Exception expected) {}
            } finally { tx.close(); }
            try { tx.load(Omemo2Records.IDENTITY,"",0); fail("Closed transaction accepted"); }
            catch (IllegalStateException expected) {}
        } finally { context.deleteDatabase("omemo2-boundary.db"); }
    }
}
