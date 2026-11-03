// SPDX-License-Identifier: AGPL-3.0-only
package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.PqOmemoRecords;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramPqOmemoStoreTest {
    @Test public void ratchetPrekeyReplayAndInboxChangesCommitOrRollbackTogether() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); String path = "pq-atomic.db"; context.deleteDatabase(path);
        try {
            try (MonogramPqOmemoStore db = new MonogramPqOmemoStore(context,path)) {
                PqOmemoRecords.Factory factory = db.factory("alice@example.org");
                try (PqOmemoRecords tx = factory.begin()) { tx.put("prekey/12",new byte[]{12}); tx.commit(); }
                try (PqOmemoRecords tx = factory.begin()) {
                    tx.put("prekey/12",null); tx.put("session/peer/1",new byte[]{1}); tx.put("replay/12/1/base",new byte[]{1}); tx.put("incoming/digest",new byte[]{2});
                    // Simulate failed authentication or failure to reserve inbox capacity.
                }
                try (PqOmemoRecords tx = factory.begin()) {
                    assertArrayEquals(new byte[]{12},tx.get("prekey/12")); assertNull(tx.get("session/peer/1")); assertTrue(tx.keys("incoming/").isEmpty()); assertTrue(tx.keys("replay/").isEmpty());
                    tx.put("prekey/12",null); tx.put("session/peer/1",new byte[]{1}); tx.put("incoming/digest",new byte[]{2}); tx.put("replay/12/1/base",new byte[]{1}); tx.commit();
                }
            }
            try (MonogramPqOmemoStore db = new MonogramPqOmemoStore(context,path); PqOmemoRecords tx = db.factory("alice@example.org").begin()) {
                assertNull(tx.get("prekey/12")); assertArrayEquals(new byte[]{1},tx.get("session/peer/1"));
                assertArrayEquals(new byte[]{2},tx.get("incoming/digest")); assertArrayEquals(new byte[]{1},tx.get("replay/12/1/base"));
            }
        } finally { context.deleteDatabase(path); }
    }
    @Test public void accountIsolationAndLiteralPrefixQueries() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); String path = "pq-owners.db"; context.deleteDatabase(path);
        try (MonogramPqOmemoStore db = new MonogramPqOmemoStore(context,path)) {
            try (PqOmemoRecords tx = db.factory("alice@example.org").begin()) {
                tx.put("session/a_b/1",new byte[]{1}); tx.put("session/axb/1",new byte[]{2}); tx.commit();
            }
            try (PqOmemoRecords tx = db.factory("bob@example.org").begin()) { assertTrue(tx.keys("").isEmpty()); assertNull(tx.get("session/a_b/1")); }
            try (PqOmemoRecords tx = db.factory("alice@example.org").begin()) { assertEquals(java.util.Collections.singleton("session/a_b/1"),tx.keys("session/a_b/")); }
            assertThrows(IllegalArgumentException.class,() -> db.factory("alice@example.org/phone"));
        } finally { context.deleteDatabase(path); }
    }
    @Test public void callbacksCannotUseATransactionFromAnotherThread() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); String path = "pq-thread.db"; context.deleteDatabase(path);
        try (MonogramPqOmemoStore db = new MonogramPqOmemoStore(context,path); PqOmemoRecords tx = db.factory("alice@example.org").begin()) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread thread = new Thread(() -> { try { tx.put("session/peer/1",new byte[]{1}); } catch(Throwable failed) { error.set(failed); } });
            thread.start(); thread.join(); assertTrue(error.get() instanceof IllegalStateException); assertNull(tx.get("session/peer/1"));
        } finally { context.deleteDatabase(path); }
    }
}
