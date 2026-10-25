package org.telegram.messenger;

import static org.junit.Assert.*;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMapsTest.App.class)
public class MonogramMapsTest {
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void resetMapsProvider() {}
    }
    @Test public void defaultAndMissingKeyNeverTouchGoogle() {
        Context context=RuntimeEnvironment.getApplication();
        assertEquals(MonogramMaps.OPEN_STREET_MAP,MonogramMaps.selected(context));
        assertTrue(MonogramMaps.create(false,true,()->{throw new AssertionError("Google probe on OSM path");},()->{throw new AssertionError("Google initialized");}) instanceof OpenStreetMapProvider);
        assertTrue(MonogramMaps.create(true,false,()->{throw new AssertionError("Google probe without key");},()->{throw new AssertionError("Google initialized");}) instanceof OpenStreetMapProvider);
    }
    @Test public void savedGoogleChoiceSurvivesUnavailableServicesWithoutLaunchingAnything() {
        Context context=RuntimeEnvironment.getApplication();
        assertTrue(MonogramMaps.select(context,MonogramMaps.GOOGLE));
        assertEquals(MonogramMaps.GOOGLE,MonogramMaps.selected(context));
        assertFalse(MonogramMaps.hasGoogleServices(context));
        assertTrue(MonogramMaps.create(true,true,()->MonogramMaps.hasGoogleServices(context),()->{throw new AssertionError("Google initialized without GMS");}) instanceof OpenStreetMapProvider);
        assertEquals(MonogramMaps.GOOGLE,MonogramMaps.selected(context));
        assertNull(org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
    @Test public void initializationAndLinkageFailuresUseOsm() {
        assertTrue(MonogramMaps.create(true,true,()->true,()->{throw new IllegalStateException("Initialization failed");}) instanceof OpenStreetMapProvider);
        assertTrue(MonogramMaps.create(true,true,()->{throw new NoClassDefFoundError("Google absent");},()->{throw new AssertionError();}) instanceof OpenStreetMapProvider);
    }
    @Test public void previewTilesCoverTheViewportAndWrapAtTheDateline() {
        java.util.LinkedHashMap<String,android.graphics.RectF> tiles=MonogramMapPreview.tileRects(0,0,512,256);
        assertEquals(4,tiles.size());android.graphics.RectF union=new android.graphics.RectF();
        for(android.graphics.RectF rect:tiles.values()) union.union(rect);
        assertTrue(union.left<=0 && union.top<=0 && union.right>=512 && union.bottom>=256);
        for(double longitude:new double[]{180,-180}) for(String key:MonogramMapPreview.tileRects(90,longitude,512,256).keySet()) {
            String[] parts=key.split("/");assertEquals("14",parts[0]);
            assertTrue(Integer.parseInt(parts[1])>=0 && Integer.parseInt(parts[1])<16384);
            assertTrue(Integer.parseInt(parts[2])>=0 && Integer.parseInt(parts[2])<16384);
        }
    }
    @Test public void automaticPreviewChoiceCanBeDisabledAndRestored() {
        Context context=RuntimeEnvironment.getApplication();assertTrue(MonogramMaps.previewsEnabled(context));
        MonogramMaps.previewsEnabled(context,false);assertFalse(MonogramMaps.previewsEnabled(context));
        MonogramMaps.previewsEnabled(context,true);assertTrue(MonogramMaps.previewsEnabled(context));
    }
    @Test public void availableGoogleProviderIsRetained() {
        IMapsProvider google=new GoogleMapsProvider();
        assertSame(google,MonogramMaps.create(true,true,()->true,()->google));
    }
}
