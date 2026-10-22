package org.telegram.ui;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.utils.settings.MonogramAppearance;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramNavigationTest.App.class})
public class MonogramNavigationTest {
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App {@Implementation public static void __staticInitializer__(){}}
    @Before public void setup(){ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();}
    @Test public void reorderedDestinationsAndSeparatorsSurviveReconstruction(){
        MonogramAppearance.menuOrder.set("settings,divider3,saved,profile,settings,unknown");
        assertEquals(Arrays.asList("settings","divider3","saved","profile"),MonogramNavigation.order().subList(0,4));
        assertEquals(1,Collections.frequency(MonogramNavigation.order(),"settings"));
        assertFalse(MonogramNavigation.order().contains("unknown"));
        assertTrue(MonogramNavigation.order().contains("contacts"));
        for(String key:MonogramNavigation.order())assertEquals(key,MonogramNavigation.key(MonogramNavigation.index(key)));
    }
    @Test public void hidingDestinationsDoesNotDiscardTheirCustomOrder(){
        MonogramAppearance.menuOrder.set("saved,appearance,settings,contacts");MonogramAppearance.menuHidden.set("contacts,saved,appearance");
        assertTrue(MonogramNavigation.hidden().containsAll(Arrays.asList("contacts","saved","appearance")));
        assertEquals("saved",MonogramNavigation.order().get(0));
        assertEquals("appearance",MonogramNavigation.order().get(1));
        MonogramAppearance.menuHidden.set("");assertEquals("saved",MonogramNavigation.order().get(0));
        assertEquals("appearance",MonogramNavigation.order().get(1));
    }
}
