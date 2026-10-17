package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMessageRegressionTest.Device.class,MonogramVideoNotificationTest.Media.class})
public class MonogramVideoNotificationTest {
    @Implements(value=MediaController.class,isInAndroidSdk=false)
    public static class Media {
        @Implementation public void __constructor__() {}
        @Implementation public static MediaController getInstance() { return Shadow.newInstanceOf(MediaController.class); }
        @Implementation public MediaController.VideoConvertMessage getCurrentForegroundConverMessage() { return null; }
    }
    @Test public void onlyTheMatchingFinishedUploadStopsItsEncodingNotification() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        VideoEncodingService service=Robolectric.buildService(VideoEncodingService.class).create().get();
        ReflectionHelpers.setStaticField(VideoEncodingService.class,"instance",service);
        ReflectionHelpers.setField(service,"currentAccount",0);ReflectionHelpers.setField(service,"currentPath","/private/converted.mp4");
        try {
            VideoEncodingService.uploadFinished(1,"/private/converted.mp4");assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
            VideoEncodingService.uploadFinished(0,"/private/another.mp4");assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
            VideoEncodingService.uploadFinished(0,"/private/converted.mp4");assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        } finally { ReflectionHelpers.setStaticField(VideoEncodingService.class,"instance",null); }
        VideoEncodingService.uploadFinished(0,"/private/converted.mp4");
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }
}
