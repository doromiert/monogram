package org.telegram.messenger.xmpp;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.telegram.messenger.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramMediaGalleryTest {
    Context context;MonogramStickerExportTest.Gallery gallery;File source;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        gallery=new MonogramStickerExportTest.Gallery();ProviderInfo info=new ProviderInfo();info.authority="media";
        gallery.attachInfo(context,info);gallery.output=File.createTempFile("gallery-result-",".bin",context.getCacheDir());
        ShadowContentResolver.registerProviderInternal("media",gallery);
        source=MonogramAttachments.cache(context,"gallery-test.db","https://example.org/media");source.getParentFile().mkdirs();
    }
    @After public void cleanup() {source.delete();gallery.output.delete();}
    Uri save(int type) throws Exception {
        java.lang.reflect.Method save=MediaController.class.getDeclaredMethod("saveFileInternal",int.class,File.class,String.class);
        save.setAccessible(true);return (Uri)save.invoke(null,type,source,null);
    }
    @Test public void hashNamedPhotosAndVideosPublishWithRealMimeAndExtension() throws Exception {
        Files.write(source.toPath(),MonogramStickerExportTest.PNG);
        assertTrue(MonogramAttachments.isCachedFile(context,source));assertNotNull(save(0));
        assertEquals("image/png",gallery.inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertTrue(gallery.inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".png"));
        assertArrayEquals(MonogramStickerExportTest.PNG,Files.readAllBytes(gallery.output.toPath()));
        assertEquals(Integer.valueOf(0),gallery.updated.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        Files.write(source.toPath(),new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'});
        assertNotNull(save(0));assertEquals("video/mp4",gallery.inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),gallery.collection);
        assertTrue(gallery.inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".mp4"));
        Files.write(source.toPath(),new byte[]{0,0,0,24,'f','t','y','p','h','e','i','c'});
        assertEquals("heic",MonogramAttachments.mediaExtension(source));
    }
    @Test public void privateFilesAndEscapingSymlinksAreNotAuthorizedAndFailedExportsAreRemoved() throws Exception {
        File secret=new File(context.getFilesDir(),"gallery-secret.bin");Files.write(secret.toPath(),MonogramStickerExportTest.PNG);
        try {
            assertFalse(MonogramAttachments.isCachedFile(context,secret));
            Files.createSymbolicLink(source.toPath(),secret.toPath());
            assertFalse(MonogramAttachments.isCachedFile(context,source));source.delete();
            Files.write(source.toPath(),MonogramStickerExportTest.PNG);gallery.fail=true;
            assertNull(save(0));assertTrue(gallery.deleted);assertNull(gallery.updated);
        } finally {secret.delete();}
    }
}
