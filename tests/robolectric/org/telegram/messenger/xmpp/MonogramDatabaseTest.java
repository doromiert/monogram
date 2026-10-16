package org.telegram.messenger.xmpp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The same isolated database assertions can run when the phone is away. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonogramDatabaseTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void storage() throws Exception {
        MonogramStoreDeviceTest.main(new String[]{temporary.newFolder("store").toString()});
    }
    @Test public void replies() throws Exception {
        MonogramReplyStoreDeviceTest.main(new String[]{temporary.newFolder("replies").toString()});
    }
    @Test public void corrections() throws Exception {
        MonogramCorrectionStoreDeviceTest.main(new String[]{temporary.newFolder("corrections").toString()});
    }
}
