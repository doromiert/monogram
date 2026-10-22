package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.FileLink;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramMediaRegressionTest {
    @Test public void gifLayoutUsesActualDimensionsEvenWithASquarePoster() {
        TLRPC.Document document = new TLRPC.TL_document();
        TLRPC.TL_documentAttributeImageSize source = new TLRPC.TL_documentAttributeImageSize();
        source.w = 480; source.h = 248; document.attributes.add(source);
        TLRPC.TL_photoSize_layer127 poster = new TLRPC.TL_photoSize_layer127();
        poster.w = poster.h = 150; document.thumbs.add(poster);
        android.graphics.Point dimensions = MonogramMediaPresentation.dimensions(document);
        assertEquals(480, dimensions.x); assertEquals(248, dimensions.y);
        android.graphics.PointF bounds = MonogramMediaPresentation.fit(dimensions.x, dimensions.y, 300, 400);
        assertEquals(300f, bounds.x, .001f); assertEquals(155f, bounds.y, .001f);
        // Very wide GIFs must not be forced up to the generic 120dp media minimum.
        bounds = MonogramMediaPresentation.fit(800, 100, 300, 400);
        assertEquals(8f, bounds.x / bounds.y, .001f);
        bounds = MonogramMediaPresentation.fit(100, 800, 300, 400);
        assertEquals(50f, bounds.x, .001f); assertEquals(400f, bounds.y, .001f);
    }
    @Test public void animatedVideoDimensionsOverrideAnImagePlaceholder() {
        TLRPC.Document document = new TLRPC.TL_document();
        TLRPC.TL_documentAttributeImageSize placeholder = new TLRPC.TL_documentAttributeImageSize();
        placeholder.w = placeholder.h = 150; document.attributes.add(placeholder);
        TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
        video.w = 640; video.h = 360; document.attributes.add(video);
        android.graphics.Point dimensions = MonogramMediaPresentation.dimensions(document);
        assertEquals(640, dimensions.x); assertEquals(360, dimensions.y);
        assertNull(MonogramMediaPresentation.fit(0, 0, 300, 400));
    }
    @Test public void animatedMp4IntentSurvivesUploadMetadataAndPrivateCacheBinding() throws Exception {
        TLRPC.Message message=new TLRPC.TL_message();message.id=44;
        message.media=MonogramAttachments.remoteMedia(44,"animation.gif","video/mp4",3);
        assertTrue(MessageObject.isGifMessage(message));assertTrue(MonogramAttachments.needsMediaInfo(message));
        TLRPC.DocumentAttribute video=message.media.document.attributes.stream().filter(a -> a instanceof TLRPC.TL_documentAttributeVideo).findFirst().get();
        video.w=video.h=128;video.duration=1;
        assertFalse(MonogramAttachments.needsMediaInfo(message));assertTrue(MonogramAttachments.needsMediaRefresh(message));
        File file=File.createTempFile("animated-cache-",".mp4");
        try {
            Files.write(file.toPath(),new byte[]{1,2,3});
            assertEquals("animation.gif",new MonogramFileRequest(message,file,"video/mp4","").uploadName());
            MonogramAttachments.apply(message,FileLink.parse("https://files.example.org/animation.gif"),file,message.media,"caption","animation.gif");
            assertTrue(MessageObject.isGifMessage(message));assertEquals("video/mp4",message.media.document.mime_type);
            assertEquals(file.getAbsolutePath(),message.media.document.localPath);
            TLRPC.MessageMedia ordinary=MonogramAttachments.remoteMedia(45,"ordinary.mp4","video/mp4",3);
            assertFalse(MessageObject.isGifDocument(ordinary.document));
            MonogramAttachments.preserveMediaAttributes(message.media,ordinary);
            assertTrue(MessageObject.isGifDocument(ordinary.document));
            TLRPC.Message named=new TLRPC.TL_message();named.media=MonogramAttachments.remoteMedia(46,"original-name.mp4","video/mp4",3);
            assertEquals("original-name.mp4",new MonogramFileRequest(named,file,"video/mp4","").uploadName());
        } finally { file.delete(); }
    }
    @Test public void localDocumentsHavePathScopedKeysEvenWithoutTelegramIds() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        TLRPC.Document first=new TLRPC.TL_document(); first.localPath="/private/account0/gif";
        TLRPC.Document second=new TLRPC.TL_document(); second.localPath="/private/account1/gif";
        String key=ImageLocation.getForDocument(first).getKey(null,null,false);
        assertNotNull(key); assertEquals(key,ImageLocation.getForDocument(first).getKey(null,null,false));
        assertNotEquals(key,ImageLocation.getForDocument(second).getKey(null,null,false));
    }
    @Test public void cachedGifKeepsAnimationSizeAndNativeLocalPath() throws Exception {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        File file=File.createTempFile("cached-gif-",".bin");
        try {
            Files.write(file.toPath(),java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"));
            TLRPC.Message message=new TLRPC.TL_message(); message.id=7;
            MonogramAttachments.apply(message,FileLink.parse("https://files.example.org/test.gif"),file,null,"","test.gif");
            assertTrue(MessageObject.isGifMessage(message)); assertFalse(MessageObject.isPhoto(message));
            assertEquals(file.length(),message.media.document.size); assertEquals(file.getAbsolutePath(),message.media.document.localPath);
            assertNotNull(ImageLocation.getForDocument(message.media.document).getKey(null,null,false));
        } finally { file.delete(); }
    }
    @Test public void roundFilenameNeedsUsableSquareVideoMetadataAndSurvivesAttributeMerge() throws Exception {
        File file=File.createTempFile("round-metadata-",".mp4");
        try {
            Files.write(file.toPath(),new byte[]{0});
            TLRPC.Message message=new TLRPC.TL_message(); message.id=8;
            TLRPC.MessageMedia media=MonogramAttachments.remoteMedia(8,"round_video_note.mp4","video/mp4",1);
            TLRPC.DocumentAttribute video=media.document.attributes.stream().filter(a -> a instanceof TLRPC.TL_documentAttributeVideo).findFirst().get();
            MonogramAttachments.apply(message,FileLink.parse("https://files.example.org/round_video_note.mp4"),file,media,"","round_video_note.mp4");
            assertFalse(video.round_message); assertTrue(MonogramAttachments.needsMediaInfo(message));
            video.w=128; video.h=96; video.duration=1;
            MonogramAttachments.apply(message,FileLink.parse("https://files.example.org/round_video_note.mp4"),file,media,"","round_video_note.mp4");
            assertFalse(video.round_message);
            video.h=128;
            MonogramAttachments.apply(message,FileLink.parse("https://files.example.org/round_video_note.mp4"),file,media,"","round_video_note.mp4");
            assertTrue(video.round_message); assertFalse(MonogramAttachments.needsMediaInfo(message));
            assertTrue(MonogramAttachments.needsMediaRefresh(message));
            media.document.thumbs.add(new TLRPC.TL_photoSize_layer127());
            assertTrue(MonogramAttachments.needsMediaRefresh(message));
            TLRPC.TL_photoStrippedSize stripped = new TLRPC.TL_photoStrippedSize();
            media.document.thumbs.add(stripped);
            assertTrue(MonogramAttachments.needsMediaRefresh(message));
            stripped.bytes = new byte[]{1, 2, 3};
            assertFalse(MonogramAttachments.needsMediaRefresh(message));
            assertTrue(new MonogramFileRequest(message,file,"video/mp4","").uploadName().startsWith("round_video_"));
            TLRPC.MessageMedia old=MonogramAttachments.remoteMedia(8,"note.mp4","video/mp4",1);
            MonogramAttachments.preserveMediaAttributes(old,media); assertTrue(video.round_message);
        } finally { file.delete(); }
    }
    @Test public void deletedAndEmptyVideoThumbnailFilesAreRegenerated() throws Exception {
        File directory = Files.createTempDirectory("video-thumb-cache").toFile();
        android.util.SparseArray<File> original = org.robolectric.util.ReflectionHelpers.getStaticField(org.telegram.messenger.FileLoader.class, "mediaDirs");
        android.util.SparseArray<File> directories = new android.util.SparseArray<>();
        directories.put(org.telegram.messenger.FileLoader.MEDIA_DIR_CACHE, directory);
        org.telegram.messenger.FileLoader.setMediaDirs(directories);
        TLRPC.Message message = new TLRPC.TL_message();
        message.media = MonogramAttachments.remoteMedia(9, "video.mp4", "video/mp4", 100);
        TLRPC.DocumentAttribute video = message.media.document.attributes.stream().filter(a -> a instanceof TLRPC.TL_documentAttributeVideo).findFirst().get();
        video.w = video.h = 128; video.duration = 1;
        TLRPC.PhotoSize thumb = new TLRPC.TL_photoSize_layer127();
        thumb.location = new TLRPC.TL_fileLocationToBeDeprecated(); thumb.location.volume_id = Integer.MIN_VALUE; thumb.location.local_id = -123;
        message.media.document.thumbs.add(thumb);
        File cached = new File(directory, org.telegram.messenger.FileLoader.getAttachFileName(thumb));
        try {
            assertTrue(MonogramAttachments.needsMediaRefresh(message));
            Files.write(cached.toPath(), new byte[0]); assertTrue(MonogramAttachments.needsMediaRefresh(message));
            Files.write(cached.toPath(), new byte[]{1}); assertFalse(MonogramAttachments.needsMediaRefresh(message));
            cached.delete(); assertTrue(MonogramAttachments.needsMediaRefresh(message));
        } finally {
            org.telegram.messenger.FileLoader.setMediaDirs(original);
            cached.delete(); directory.delete();
        }
    }
}
