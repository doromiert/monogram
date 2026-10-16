package com.negzero.monogram.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The real native backend and Android transaction adapter, without account credentials or a network. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class)
public class Omemo2AndroidStoreTest {
    private SQLiteOpenHelper open(Context context, String file) throws Exception {
        Class<?> store = Class.forName("org.telegram.messenger.xmpp.MonogramOmemo2Store");
        Constructor<?> constructor = store.getDeclaredConstructor(Context.class,String.class); constructor.setAccessible(true);
        return (SQLiteOpenHelper) constructor.newInstance(context,file);
    }
    private Omemo2Records begin(SQLiteOpenHelper database, String owner, int device) throws Exception {
        Method begin = database.getClass().getDeclaredMethod("begin",String.class,int.class); begin.setAccessible(true);
        return (Omemo2Records) begin.invoke(database,owner,device);
    }
    private byte[] seed(SQLiteOpenHelper database, String owner, int device) throws Exception {
        byte[][] identity = Omemo2Native.identity();
        byte[][] preKey = Omemo2Native.preKey(11), signed = Omemo2Native.signedPreKey(identity[0],22,System.currentTimeMillis());
        try (Omemo2Records tx = begin(database,owner,device)) {
            tx.save(Omemo2Records.IDENTITY,"",0,identity[0]); tx.save(Omemo2Records.PRE_KEY,"",11,preKey[0]);
            tx.save(Omemo2Records.SIGNED_PRE_KEY,"",22,signed[0]); tx.commit();
        }
        return identity[1];
    }
    @Test public void sqliteCallbacksCommitAndRollbackRealRatchetStateAcrossReopen() throws Exception {
        String library = System.getProperty("monogram.omemo2.library","");
        Assume.assumeTrue("Build the host JNI library first",new java.io.File(library).isFile());
        Context context = RuntimeEnvironment.getApplication(); String alice = "alice@example.org", bob = "bob@example.org";
        context.deleteDatabase("alice-modern.db"); context.deleteDatabase("bob-modern.db");
        try {
            byte[][] first;
            try (SQLiteOpenHelper a = open(context,"alice-modern.db"); SQLiteOpenHelper b = open(context,"bob-modern.db")) {
                byte[] aliceIdentity = seed(a,alice,101);
                byte[][] bobIdentity = Omemo2Native.identity(), pre = Omemo2Native.preKey(11), signed = Omemo2Native.signedPreKey(bobIdentity[0],22,System.currentTimeMillis());
                try (Omemo2Records tx = begin(b,bob,202)) {
                    tx.save(Omemo2Records.IDENTITY,"",0,bobIdentity[0]); tx.save(Omemo2Records.PRE_KEY,"",11,pre[0]); tx.save(Omemo2Records.SIGNED_PRE_KEY,"",22,signed[0]);
                    tx.save(Omemo2Records.TRUSTED,alice,101,aliceIdentity); tx.commit();
                }
                try (Omemo2Records tx = begin(a,alice,101)) {
                    tx.save(Omemo2Records.TRUSTED,bob,202,bobIdentity[1]);
                    Omemo2Native.build(tx,101,bob,202,11,pre[1],22,signed[1],signed[2],bobIdentity[1]); tx.commit();
                }
                try (Omemo2Records tx = begin(a,alice,101)) { first = Omemo2Native.encrypt(tx,101,bob,202,"Photo caption 😀".getBytes("UTF-8")); tx.commit(); }
                try (Omemo2Records tx = begin(b,bob,202)) {
                    assertEquals("Photo caption 😀",new String(Omemo2Native.decrypt(tx,202,alice,101,first[1],true),"UTF-8"));
                    assertNull(tx.load(Omemo2Records.PRE_KEY,"",11));
                    // Simulate failure after native callbacks: no key consumption or ratchet state may escape.
                }
            }
            try (SQLiteOpenHelper a = open(context,"alice-modern.db"); SQLiteOpenHelper b = open(context,"bob-modern.db")) {
                byte[][] reply;
                try (Omemo2Records tx = begin(b,bob,202)) {
                    assertNotNull(tx.load(Omemo2Records.PRE_KEY,"",11)); assertNull(tx.load(Omemo2Records.SESSION,alice,101));
                    assertEquals("Photo caption 😀",new String(Omemo2Native.decrypt(tx,202,alice,101,first[1],true),"UTF-8")); tx.commit();
                }
                try (Omemo2Records tx = begin(b,bob,202)) {
                    assertNull(tx.load(Omemo2Records.PRE_KEY,"",11));
                    reply = Omemo2Native.encrypt(tx,202,alice,101,"Reply 🦄".getBytes("UTF-8")); tx.commit();
                }
                try (Omemo2Records tx = begin(a,alice,101)) {
                    assertEquals("Reply 🦄",new String(Omemo2Native.decrypt(tx,101,bob,202,reply[1],reply[0][0]==1),"UTF-8")); tx.commit();
                }
            }
        } finally { context.deleteDatabase("alice-modern.db"); context.deleteDatabase("bob-modern.db"); }
    }
}
