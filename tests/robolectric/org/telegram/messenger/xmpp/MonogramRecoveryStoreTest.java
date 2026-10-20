package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.RecoveryManifest;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.telegram.messenger.ApplicationLoader;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramBackupNativeStoreTest.App.class})
public class MonogramRecoveryStoreTest {
    private final int slot=907;
    File root;
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        root=new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),"monogram-recovery-"+slot);erase(root);
    }
    @After public void cleanup() { erase(root); }
    String repeated(char c,int size) { char[] chars=new char[size];Arrays.fill(chars,c);return new String(chars); }
    RecoveryManifest manifest(String owner,boolean approved) throws Exception {
        return new RecoveryManifest(owner,UUID.randomUUID().toString(),101,202,repeated('a',64),repeated('b',64),"4c523031"+repeated('1',32),"4d523031"+repeated('2',32),approved,Collections.emptyList());
    }
    void write(RecoveryManifest manifest) throws Exception { assertTrue(root.mkdir());try(FileOutputStream out=new FileOutputStream(new File(root,"manifest.bin"))) { manifest.write(out); } }
    @Test public void absentManifestDoesNotAllocateKeysOrCreateRecoveryState() throws Exception {
        assertNull(MonogramRecoveryStore.load(slot,"alice@example.org",0,null,null));assertFalse(root.exists());
    }
    @Test public void partialForeignOwnerAndUnconfirmedMigrationsAbortBeforeAnyActiveStoreIsOpened() throws Exception {
        assertTrue(root.mkdir());try { MonogramRecoveryStore.load(slot,"alice@example.org",101,null,null);fail("partial install"); } catch(IOException expected) { }
        erase(root);write(manifest("bob@example.org",true));
        try { MonogramRecoveryStore.load(slot,"alice@example.org",101,null,null);fail("foreign account"); } catch(IOException expected) { }
        erase(root);write(manifest("alice@example.org",false));
        try { MonogramRecoveryStore.load(slot,"alice@example.org",101,null,null);fail("reconnect requires explicit approval"); } catch(IOException expected) { assertTrue(expected.getMessage().contains("explicitly confirmed")); }
        assertFalse(new File(root,"archives").exists());
    }
    private static void erase(File file) { if(file.isDirectory()) { File[] children=file.listFiles();if(children!=null) for(File child:children) erase(child); }file.delete(); }
}
