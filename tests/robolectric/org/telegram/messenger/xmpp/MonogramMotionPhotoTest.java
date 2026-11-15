package org.telegram.messenger.xmpp;

import android.content.Context;
import android.graphics.BitmapFactory;
import com.negzero.monogram.xmpp.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMotionPhotoTest.Video.class,MonogramMotionPhotoTest.Controller.class})
public class MonogramMotionPhotoTest {
    @Implements(value=MediaController.class,isInAndroidSdk=false)
    public static class Controller { @Implementation protected static void __staticInitializer__() {} }
    @Implements(value=SendMessagesHelper.class,isInAndroidSdk=false)
    public static class Video {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation public static void fillVideoAttribute(String path,TLRPC.TL_documentAttributeVideo attribute,VideoEditedInfo edited) {
            attribute.w=48;attribute.h=32;attribute.duration=1;
        }
    }
    private File clip() { return new File("../tests/fixtures/motion-photo/clip.mp4"); }
    private File still() throws Exception {
        File jpeg=File.createTempFile("full-resolution-", ".jpg");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(3200,2400,android.graphics.Bitmap.Config.ARGB_8888);
        try(OutputStream output=new FileOutputStream(jpeg)) { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,output)); }
        finally { bitmap.recycle(); }
        return jpeg;
    }
    private File motion() throws Exception {
        File jpeg=still(),motion=File.createTempFile("motion-photo-", "MP.jpg");
        try(OutputStream output=new FileOutputStream(motion)) { MediaController.writeMotionPhoto(jpeg,clip(),output,null); }
        finally { jpeg.delete(); }
        return motion;
    }
    @Test public void actualWriterPreservesFullStillAndExactVideoAndProducesDesktopFixture() throws Exception {
        File file=motion();
        try {
            MonogramMotionPhotos.Clip parsed=MonogramMotionPhotos.inspect(file);assertNotNull(parsed);
            assertEquals(clip().length(),parsed.length);
            File extracted=MonogramMotionPhotos.extract(file,parsed);
            assertArrayEquals(Files.readAllBytes(clip().toPath()),Files.readAllBytes(extracted.toPath()));
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);assertEquals(3200,bounds.outWidth);assertEquals(2400,bounds.outHeight);
            File exported=File.createTempFile("motion-export-", "MP.jpg");
            try(OutputStream output=new FileOutputStream(exported)) { MediaController.writeMotionPhoto(file,extracted,output,null); }
            try(RandomAccessFile header=new RandomAccessFile(exported,"r")) {
                header.seek(4);assertEquals(header.readUnsignedShort()+2,exported.length()-file.length());
            }
            assertNotNull(MonogramMotionPhotos.inspect(exported));exported.delete();
            File shared=new File("../.state/test-motion-photoMP.jpg");Files.copy(file.toPath(),shared.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            extracted.delete();
        } finally { file.delete(); }
    }
    @Test public void verifiedReceivedMediaRetainsBothPartsAndNativeProjectionAcrossReopen() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        String database="motion-reopen.db";context.deleteDatabase(database);File source=motion();int id;long self;File cached;
        try {
            FileLink link=FileLink.parse("https://files.example.org/originalMP.jpg");
            FileShare sharing=FileShare.describe(link,source,"originalMP.jpg","image/jpeg");
            try(MonogramStore store=new MonogramStore(context,database)) {
                self=store.peer("alice@example.org");store.peer("bob@example.org");
                id=store.save(XmppMessage.sentFile(org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","motion",sharing,"caption",null));
                cached=store.attachmentFile(sharing);cached.getParentFile().mkdirs();Files.copy(source.toPath(),cached.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                sharing.verify(cached,new FileTransfer.Job());
                TLRPC.MessageMedia media=MonogramAttachments.decodedMedia(id,100,cached,sharing.name,sharing.mime);
                assertTrue(media.live_photo);assertEquals(3200,media.photo.sizes.get(0).w);assertEquals(48,media.document.attributes.get(0).w);
                assertTrue(store.attachDownloaded(id,link.wireUrl,sharing,cached,media));
                assertFalse(store.attachDownloaded(id,"https://files.example.org/wrong.jpg",sharing,cached,media));
            }
            try(MonogramStore store=new MonogramStore(context,database)) {
                TLRPC.Message projected=store.getMessage(id,self);
                assertTrue(projected.media.live_photo);assertEquals("caption",projected.message);
                assertEquals(cached.getAbsolutePath(),projected.params.get(MonogramMotionPhotos.SOURCE));
                assertEquals(MonogramMotionPhotos.videoFile(cached).getAbsolutePath(),projected.attachPath);
                assertEquals(projected.attachPath,projected.media.document.localPath);
                assertTrue(MonogramAttachments.isCachedFile(context,new File(projected.attachPath)));
                assertEquals("mp4",MonogramAttachments.mediaExtension(new File(projected.attachPath)));
                assertArrayEquals(Files.readAllBytes(clip().toPath()),Files.readAllBytes(new File(projected.attachPath).toPath()));
                assertTrue(MonogramMotionPhotos.isMotion(projected));
                assertFalse(MonogramAttachments.needsMediaRefresh(projected));
                assertTrue(new File(projected.attachPath).delete());assertTrue(MonogramAttachments.needsMediaRefresh(projected));
                MonogramAttachments.decodedMedia(id,100,cached,sharing.name,sharing.mime);
                assertTrue(new File(projected.attachPath).isFile());
                Files.write(cached.toPath(),new byte[]{1,2,3});
                try { sharing.verify(cached,new FileTransfer.Job());fail("Corrupt entire-container accepted"); } catch(FileTransfer.Failure expected) { assertEquals("INTEGRITY",expected.reason); }
                MonogramMotionPhotos.videoFile(cached).delete();cached.delete();
            }
        } finally { source.delete();context.deleteDatabase(database); }
    }
    @Test public void immutableRetrySnapshotKeepsCompleteOriginalAndOnlyExplicitEditsRebuild() throws Exception {
        File original=motion();
        try {
            TLRPC.TL_message message=new TLRPC.TL_message();message.params=new java.util.HashMap<>();message.params.put(MonogramMotionPhotos.SOURCE,original.getAbsolutePath());
            message.media=MonogramAttachments.remoteMedia(1,"originalMP.jpg","image/jpeg",original.length());
            MonogramFileRequest request=new MonogramFileRequest(message,original,"image/jpeg","");
            File snapshot=MonogramMotionPhotos.snapshot(request,original.getParentFile());
            assertArrayEquals(Files.readAllBytes(original.toPath()),Files.readAllBytes(snapshot.toPath()));
            assertTrue(request.uploadName().endsWith("MP.jpg"));snapshot.delete();
            File jpeg=still();message.params.put(MonogramMotionPhotos.REBUILD,"1");message.params.put(MonogramMotionPhotos.STILL,jpeg.getAbsolutePath());
            File rebuilt=MonogramMotionPhotos.snapshot(new MonogramFileRequest(message,clip(),"image/jpeg",""),original.getParentFile());
            assertNotNull(MonogramMotionPhotos.inspect(rebuilt));rebuilt.delete();
            MonogramFileRequest cancelled=new MonogramFileRequest(message,clip(),"image/jpeg","");cancelled.job.cancel();
            try { MonogramMotionPhotos.snapshot(cancelled,original.getParentFile());fail("Cancelled rebuild accepted"); }
            catch(InterruptedIOException expected) { }
            jpeg.delete();
            VideoEditedInfo edits=new VideoEditedInfo();assertFalse(MonogramMotionPhotos.edited(edits));edits.startTime=1;assertTrue(MonogramMotionPhotos.edited(edits));
        } finally { original.delete(); }
    }
    @Test public void nonJpegCoverNormalizesAtFullResolutionAndRetainsOriginalForRetry() throws Exception {
        File png=File.createTempFile("motion-source-", ".png");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(3200,2400,android.graphics.Bitmap.Config.ARGB_8888);
        try(OutputStream output=new FileOutputStream(png)) { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)); }
        finally { bitmap.recycle(); }
        byte[] original=Files.readAllBytes(png.toPath());
        File normalized=MonogramMotionPhotos.jpegStill(png,png.getParentFile(),new FileTransfer.Job());
        try {
            assertTrue(MonogramMotionPhotos.isJpeg(normalized));
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(normalized.getAbsolutePath(),bounds);
            assertEquals(3200,bounds.outWidth);assertEquals(2400,bounds.outHeight);
            assertArrayEquals(original,Files.readAllBytes(png.toPath()));
        } finally { normalized.delete();png.delete(); }
    }
    @Test public void malformedOffsetsAndTruncatedVideoStayOrdinaryStillImages() throws Exception {
        File file=motion();
        try {
            byte[] bytes=Files.readAllBytes(file.toPath());
            try(RandomAccessFile truncate=new RandomAccessFile(file,"rw")) { truncate.setLength(bytes.length-10); }
            assertNull(MonogramMotionPhotos.inspect(file));
            String encoded=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] invalid=encoded.replace("Item:Length=\"1511\"","Item:Length=\"9999\"").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            Files.write(file.toPath(),invalid);assertNull(MonogramMotionPhotos.inspect(file));
            byte[] broken=bytes.clone();int offset=(int)(bytes.length-clip().length());broken[offset+4]='x';Files.write(file.toPath(),broken);assertNull(MonogramMotionPhotos.inspect(file));
            Files.write(file.toPath(),new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,0,2});assertNull(MonogramMotionPhotos.inspect(file));
        } finally { file.delete(); }
    }
}
