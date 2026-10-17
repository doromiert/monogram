package org.telegram.messenger.xmpp;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ImageLoader;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramUploadProgressTest.Loader.class)
public class MonogramUploadProgressTest {
    @Implements(value=ImageLoader.class,isInAndroidSdk=false)
    public static class Loader {
        static final ImageLoader instance=Shadow.newInstanceOf(ImageLoader.class);
        static { ReflectionHelpers.setField(instance,"fileProgresses",new ConcurrentHashMap<String,long[]>()); }
        @Implementation public void __constructor__() {}
        @Implementation public static ImageLoader getInstance() { return instance; }
    }
    @Test public void queuedAndPreparedFilesNeverInheritMissingOrCompletedUploadProgress() throws Exception {
        File file=File.createTempFile("upload-progress",".bin",RuntimeEnvironment.getApplication().getCacheDir());
        try {
            Files.write(file.toPath(),new byte[]{1,2,3});
            TLRPC.Message message=new TLRPC.TL_message();message.attachPath=file.getAbsolutePath();
            assertNull(ImageLoader.getInstance().getFileProgressSizes(message.attachPath));
            assertEquals(3,MonogramFileRequest.prepareProgress(message,file));
            assertEquals(0f,ImageLoader.getInstance().getFileProgress(message.attachPath),0f);
            ImageLoader.getInstance().setFileProgress(message.attachPath,3,3);
            MonogramFileRequest.prepareProgress(message,file);
            assertArrayEquals(new long[]{0,3},ImageLoader.getInstance().getFileProgressSizes(message.attachPath));
            message.media=new TLRPC.TL_messageMediaDocument();message.media.document=new TLRPC.TL_document();message.media.document.size=123;
            message.attachPath=file.getAbsolutePath()+"-converted.mp4";
            assertEquals(123,MonogramFileRequest.prepareProgress(message,new File(message.attachPath)));
            assertArrayEquals(new long[]{0,123},ImageLoader.getInstance().getFileProgressSizes(message.attachPath));
        } finally { file.delete(); }
    }
}
