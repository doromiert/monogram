package org.telegram.ui;

import org.telegram.messenger.camera.Size;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramVideoSizingTest {
    private Size size(int width,int height,int count,int selected) throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        PhotoViewer viewer=(PhotoViewer)unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),PhotoViewer.class);
        ReflectionHelpers.setField(viewer,"originalWidth",width);
        ReflectionHelpers.setField(viewer,"originalHeight",height);
        ReflectionHelpers.setField(viewer,"compressionsCount",count);
        ReflectionHelpers.setField(viewer,"selectedCompression",selected);
        return ReflectionHelpers.callInstanceMethod(viewer,"calculateResultVideoSize");
    }
    @Test public void originalQualityKeeps854PixelSourceWithoutTranscoding() throws Exception {
        assertEquals(new Size(854,480),size(854,480,2,1));
        assertEquals(new Size(480,854),size(480,854,2,1));
        assertEquals(new Size(638,358),size(638,358,1,0));
    }
    @Test public void actualResizingAlignsBothDimensionsInsteadOfSubtractingHeightFromWidth() throws Exception {
        Size landscape=size(854,480,2,0),portrait=size(480,854,2,0);
        assertEquals(new Size(480,268),landscape);
        assertEquals(new Size(268,480),portrait);
    }
}
