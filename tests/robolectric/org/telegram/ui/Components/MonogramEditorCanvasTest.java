package org.telegram.ui.Components;

import static org.junit.Assert.*;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramEditorCanvasTest {
    @Test public void colorOs17FamilyUsesNativeCanvas() {
        for(String vendor:new String[]{"OnePlus","OPPO","realme"}) {
            assertTrue(EditTextEffects.requiresNativeTextCanvas(37,vendor));
            assertTrue(EditTextEffects.requiresNativeTextCanvas(38,vendor));
            assertFalse(EditTextEffects.requiresNativeTextCanvas(36,vendor));
        }
    }
    @Test public void otherDevicesRetainTheirExistingCanvasPolicy() {
        assertFalse(EditTextEffects.requiresNativeTextCanvas(37,"Google"));
        assertFalse(EditTextEffects.requiresNativeTextCanvas(37,"Samsung"));
        assertFalse(EditTextEffects.requiresNativeTextCanvas(37,null));
        Locale before=Locale.getDefault();
        try { Locale.setDefault(new Locale("tr","TR"));assertTrue(EditTextEffects.requiresNativeTextCanvas(37,"ONEPLUS")); }
        finally { Locale.setDefault(before); }
    }
}
