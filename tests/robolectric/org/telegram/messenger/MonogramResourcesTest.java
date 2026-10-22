package org.telegram.messenger;
import static org.junit.Assert.*;
import android.content.res.Resources;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramResourcesTest {
    @Test public void unmatchedIconsUseNativeResourcesAndWrappingIsIdempotent(){
        Resources source=RuntimeEnvironment.getApplication().getResources();Resources wrapped=MonogramResources.wrap(source);
        assertSame(wrapped,MonogramResources.wrap(source));assertSame(wrapped,MonogramResources.wrap(wrapped));
        int icon=android.R.drawable.ic_menu_search;
        assertNotNull(wrapped.getDrawable(icon,null));
        assertEquals(source.getDrawable(icon,null).getIntrinsicWidth(),wrapped.getDrawable(icon,null).getIntrinsicWidth());
        assertEquals(source.getDisplayMetrics().densityDpi,wrapped.getDisplayMetrics().densityDpi);
    }
}
