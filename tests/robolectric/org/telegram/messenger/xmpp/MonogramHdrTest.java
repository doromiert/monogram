package org.telegram.messenger.xmpp;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaFormat;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.ui.Stories.recorder.StoryEntry;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonogramHdrTest {
    @Test public void gainmapDetectionHandlesNullAndRecycledBitmaps() {
        assertFalse(MonogramHdr.hasGainmap(null));
        Bitmap bitmap=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);
        assertFalse(MonogramHdr.hasGainmap(bitmap));
        bitmap.recycle();assertFalse(MonogramHdr.hasGainmap(bitmap));
        // Actual gain-map JPEG parsing is checked on Android by MonogramHdrDeviceTest;
        // Robolectric's host Skia does not parse this image's gain-map metadata.
    }
    @Test public void onlyHdrColorSpacesRequestHighPrecision() {
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.outColorSpace=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020_PQ);
        assertEquals(Bitmap.Config.RGBA_F16,MonogramHdr.preferredConfig(options,Bitmap.Config.ARGB_8888));
        options.outColorSpace=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020_HLG);
        assertEquals(Bitmap.Config.RGBA_F16,MonogramHdr.preferredConfig(options,Bitmap.Config.ARGB_8888));
        options.outColorSpace=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3);
        assertEquals(Bitmap.Config.ARGB_8888,MonogramHdr.preferredConfig(options,Bitmap.Config.ARGB_8888));
    }
    @Test public void hdrWindowRestoresItsModeAfterTheLastVisibleOwnerLeaves() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().visible().get();
        View root=activity.getWindow().getDecorView().getRootView();
        Display display=root.getDisplay();assertNotNull(display);
        Shadows.shadowOf(display).setDisplayHdrCapabilities(display.getDisplayId(),1000f,500f,.1f,Display.HdrCapabilities.HDR_TYPE_HDR10);
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)root.getLayoutParams();
        int original=params.getColorMode();Object one=new Object(),two=new Object();
        MonogramHdr.update(one,root,true);assertEquals(ActivityInfo.COLOR_MODE_HDR,params.getColorMode());
        MonogramHdr.update(two,root,true);MonogramHdr.release(one);
        assertEquals(ActivityInfo.COLOR_MODE_HDR,params.getColorMode());
        MonogramHdr.release(two);assertEquals(original,params.getColorMode());
        MonogramHdr.update(one,root,false);assertEquals(original,params.getColorMode());
        activity.finish();
    }
    @Test public void originalHdrVideoSkipsConversionOnlyWithoutActualEdits() {
        VideoEditedInfo video=new VideoEditedInfo();video.hdrInfo=new StoryEntry.HDRInfo();
        video.hdrInfo.colorTransfer=MediaFormat.COLOR_TRANSFER_ST2084;
        video.originalWidth=video.resultWidth=1920;video.originalHeight=video.resultHeight=1080;
        video.originalDuration=1000000;video.startTime=-1;video.endTime=-1;
        assertTrue(MonogramHdr.keepOriginalVideo(video));
        video.hdrInfo.colorTransfer=MediaFormat.COLOR_TRANSFER_HLG;assertTrue(MonogramHdr.keepOriginalVideo(video));
        video.startTime=1;assertFalse(MonogramHdr.keepOriginalVideo(video));video.startTime=-1;
        video.resultWidth=1280;assertFalse(MonogramHdr.keepOriginalVideo(video));video.resultWidth=1920;
        video.muted=true;assertFalse(MonogramHdr.keepOriginalVideo(video));video.muted=false;
        video.paintPath="edited";assertFalse(MonogramHdr.keepOriginalVideo(video));video.paintPath=null;
        video.hdrInfo=null;assertFalse(MonogramHdr.keepOriginalVideo(video));
    }
}
