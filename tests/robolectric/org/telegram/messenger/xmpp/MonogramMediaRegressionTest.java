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
            assertTrue(new MonogramFileRequest(message,file,"video/mp4","").uploadName().startsWith("round_video_"));
            TLRPC.MessageMedia old=MonogramAttachments.remoteMedia(8,"note.mp4","video/mp4",1);
            MonogramAttachments.preserveMediaAttributes(old,media); assertTrue(video.round_message);
        } finally { file.delete(); }
    }
}
