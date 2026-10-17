package org.telegram.messenger.xmpp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.DownloadController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramDownloadPresetTest {
    @Test public void largeDownloadLimitsSurviveSavingAndReloading() {
        long limit = 4000L * 1024 * 1024;
        DownloadController.Preset original = new DownloadController.Preset(
                new int[]{13, 5, 9, 1}, 10 * 1024 * 1024, limit, limit - 1, true, false, true, false, 100, false);
        DownloadController.Preset restored = new DownloadController.Preset(original.toString(), original.toString());
        assertArrayEquals(original.sizes, restored.sizes);
        assertArrayEquals(original.mask, restored.mask);
        assertEquals(original.toString(), restored.toString());
    }
}
