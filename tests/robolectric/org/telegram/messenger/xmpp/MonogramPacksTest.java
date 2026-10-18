package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.FileShare;
import java.io.File;
import java.util.ArrayList;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramPacksTest.Account.class, MonogramPacksTest.App.class, MonogramPacksTest.Ui.class, MonogramPacksTest.PackUi.class, MonogramPacksTest.EmojiUi.class})
public class MonogramPacksTest {
    static String jid;
    static MonogramController controller;
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Account {
        @Implementation public static MonogramController get(int account) { return controller; }
        @Implementation public String accountJid() { return jid; }
    }
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=org.telegram.messenger.AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable action) { action.run(); }
        @Implementation public static void runOnUIThread(Runnable action, long delay) { action.run(); }
    }
    @Implements(value=MonogramPacks.class,isInAndroidSdk=false)
    public static class PackUi { @Implementation public static void refresh(int account) {} }
    @Implements(value=org.telegram.ui.Components.AnimatedEmojiDrawable.class,isInAndroidSdk=false)
    public static class EmojiUi { @Implementation public static void clearMonogramPacks(int account) {} }
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        controller=Shadow.newInstanceOf(MonogramController.class); jid="alice@example.org";
    }
    @Test public void packLinksAreStrictAndNamesRemainSeparateFromEmojiTags() throws Exception {
        assertFalse(MonogramPacks.parseLink("https://t.me/addstickers/Cat_pack").emojis);
        assertTrue(MonogramPacks.parseLink("https://telegram.me/addemoji/Cat_pack").emojis);
        for(String link:new String[]{"http://t.me/addstickers/a","https://t.me.evil/addstickers/a","https://user@t.me/addstickers/a","https://t.me/addemoji/a?token=secret","https://t.me/addemoji/../a","https://t.me/addemoji/a/b"}) {
            try { MonogramPacks.parseLink(link); fail(link); } catch(IllegalArgumentException expected) { }
        }
        assertEquals("My friends",MonogramPacks.name(" My friends "));
        for(String invalid:new String[]{"","\n","bad\u0000name"}) try { MonogramPacks.name(invalid);fail(); } catch(IllegalArgumentException expected) { }
    }
    @Test public void nativePacksRestoreLocalFilesAndDoNotCrossAccountBoundaries() throws Exception {
        MonogramPacks.Pack pack=new MonogramPacks.Pack(); pack.id=0x4d50000100000001L;pack.name="Friends";pack.source="local:fixture";
        MonogramPacks.Item item=new MonogramPacks.Item();item.id=0x4d50000200000002L;item.name="Alice laughing";item.emoji="😂";item.mime="image/png";item.file="opaque.png";item.width=120;item.height=120;pack.items.add(item);
        ArrayList<MonogramPacks.Pack> packs=new ArrayList<>();packs.add(pack);
        String scope=ReflectionHelpers.callStaticMethod(MonogramPacks.class,"scope",ReflectionHelpers.ClassParameter.from(int.class,0));
        ReflectionHelpers.callStaticMethod(MonogramPacks.class,"write",ReflectionHelpers.ClassParameter.from(String.class,scope),ReflectionHelpers.ClassParameter.from(ArrayList.class,packs));
        TLRPC.Document doc=MonogramPacks.document(0,item.id);assertNotNull(doc);assertTrue(doc.localPath.endsWith("/opaque.png"));assertTrue(MessageObject.isStickerDocument(doc));
        assertEquals("😂",doc.attributes.get(0).alt);assertEquals("Alice laughing",MonogramPacks.packs(0).get(0).items.get(0).name);
        assertEquals(item.id,MonogramPacks.sets(0,false).get(0).packs.get(0).documents.get(0).longValue());
        assertEquals(new File(doc.localPath),MonogramPacks.thumb(0,doc.thumbs.get(0).location.volume_id,doc.thumbs.get(0).location.local_id));
        File image=new File(doc.localPath);image.getParentFile().mkdirs();
        java.nio.file.Files.write(image.toPath(),java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDPsAAAAASUVORK5CYII="));
        org.telegram.messenger.ImageLocation thumbnail=org.telegram.messenger.ImageLocation.getForDocument(doc.thumbs.get(0),doc);
        assertEquals(doc.localPath,thumbnail.path);assertEquals(0,thumbnail.imageType);
        doc.mime_type="application/x-tgsticker";
        assertEquals(org.telegram.messenger.FileLoader.IMAGE_TYPE_LOTTIE,org.telegram.messenger.ImageLocation.getForDocument(doc.thumbs.get(0),doc).imageType);
        doc.mime_type="image/png";
        ArrayList<MonogramPacks.Pack> duplicate=new ArrayList<>(packs);duplicate.add(pack);
        assertThrows(RuntimeException.class,()->ReflectionHelpers.callStaticMethod(MonogramPacks.class,"write",ReflectionHelpers.ClassParameter.from(String.class,scope),ReflectionHelpers.ClassParameter.from(ArrayList.class,duplicate)));
        assertEquals(1,MonogramPacks.sets(0,false).size());
        jid="bob@example.org";assertNull(MonogramPacks.document(0,item.id));assertTrue(MonogramPacks.sets(0,false).isEmpty());
        jid="alice@example.org";assertNotNull(MonogramPacks.document(0,item.id));
    }
    @Test public void failedCreationRollsBackFilesAndCorruptManifestCannotBeOverwritten() throws Exception {
        jid="rollback@example.org";
        android.net.Uri first=android.net.Uri.parse("content://pack-fixture/first"),second=android.net.Uri.parse("content://pack-fixture/invalid");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,encoded);bitmap.recycle();
        org.robolectric.Shadows.shadowOf(ApplicationLoader.applicationContext.getContentResolver()).registerInputStream(first,new java.io.ByteArrayInputStream(encoded.toByteArray()));
        org.robolectric.Shadows.shadowOf(ApplicationLoader.applicationContext.getContentResolver()).registerInputStream(second,new java.io.ByteArrayInputStream(new byte[]{31,(byte)139,8,0,0,0,0,0,0,0,0,0}));
        ArrayList<android.net.Uri> sources=new ArrayList<>();sources.add(first);sources.add(second);
        java.util.concurrent.CountDownLatch finished=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        MonogramPacks.create(0,"Uncommitted",false,sources,result->{error.set(result);finished.countDown();});
        assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS));assertNotNull(error.get());
        String scope=ReflectionHelpers.callStaticMethod(MonogramPacks.class,"scope",ReflectionHelpers.ClassParameter.from(int.class,0));
        File dir=ReflectionHelpers.callStaticMethod(MonogramPacks.class,"directory",ReflectionHelpers.ClassParameter.from(String.class,scope));
        assertEquals(0,dir.listFiles().length);assertTrue(MonogramPacks.packs(0).isEmpty());
        MonogramPacks.Request cancelled=ReflectionHelpers.callConstructor(MonogramPacks.Request.class,ReflectionHelpers.ClassParameter.from(int.class,0));cancelled.cancel();
        assertThrows(RuntimeException.class,()->ReflectionHelpers.callInstanceMethod(cancelled,"check"));
        File manifest=new File(dir,"packs.json");byte[] invalid="broken existing data".getBytes(java.nio.charset.StandardCharsets.UTF_8);java.nio.file.Files.write(manifest.toPath(),invalid);
        java.util.Map cache=ReflectionHelpers.getStaticField(MonogramPacks.class,"CACHE");cache.remove(scope);
        assertTrue(MonogramPacks.storageError(0));
        assertThrows(RuntimeException.class,()->ReflectionHelpers.callStaticMethod(MonogramPacks.class,"write",ReflectionHelpers.ClassParameter.from(String.class,scope),ReflectionHelpers.ClassParameter.from(ArrayList.class,new ArrayList<MonogramPacks.Pack>())));
        assertArrayEquals(invalid,java.nio.file.Files.readAllBytes(manifest.toPath()));
    }
    @Test public void importProgressSurvivesObserverRemovalAndSupportsCancelAndLogout() throws Exception {
        jid="progress@example.org";
        Class<?> jobClass=Class.forName(MonogramPacks.class.getName()+"$ImportJob");
        Object job=ReflectionHelpers.callConstructor(jobClass,ReflectionHelpers.ClassParameter.from(int.class,0));
        String owner=MonogramPacks.owner(0);java.util.Map imports=ReflectionHelpers.getStaticField(MonogramPacks.class,"IMPORTS");imports.put(owner,job);
        java.util.concurrent.atomic.AtomicInteger events=new java.util.concurrent.atomic.AtomicInteger();
        MonogramPacks.ImportObserver observer=progress->events.incrementAndGet();MonogramPacks.addImportObserver(0,observer);
        ReflectionHelpers.callInstanceMethod(job,"update",ReflectionHelpers.ClassParameter.from(String.class,"Downloading file"),ReflectionHelpers.ClassParameter.from(String.class,"Pack"),ReflectionHelpers.ClassParameter.from(int.class,3),ReflectionHelpers.ClassParameter.from(int.class,120),ReflectionHelpers.ClassParameter.from(long.class,25L),ReflectionHelpers.ClassParameter.from(long.class,100L));
        assertTrue(MonogramPacks.importProgress(0).active);assertEquals(3,MonogramPacks.importProgress(0).completed);assertEquals(25,MonogramPacks.importProgress(0).currentBytes);
        MonogramPacks.removeImportObserver(0,observer);int before=events.get();
        ReflectionHelpers.callInstanceMethod(job,"bytes",ReflectionHelpers.ClassParameter.from(long.class,80L),ReflectionHelpers.ClassParameter.from(long.class,100L));
        assertEquals(before,events.get());assertTrue(MonogramPacks.importProgress(0).active);
        MonogramPacks.ImportObserver reopened=progress->events.incrementAndGet();MonogramPacks.addImportObserver(0,reopened);assertTrue(events.get()>before);assertEquals(80,MonogramPacks.importProgress(0).currentBytes);
        MonogramPacks.cancelImport(0);MonogramPacks.Request request=ReflectionHelpers.getField(job,"request");
        assertThrows(RuntimeException.class,()->ReflectionHelpers.callInstanceMethod(request,"check"));
        ReflectionHelpers.callInstanceMethod(job,"finished",ReflectionHelpers.ClassParameter.from(String.class,null));
        assertFalse(MonogramPacks.importProgress(0).active);assertTrue(MonogramPacks.importProgress(0).cancelled);assertFalse(MonogramPacks.importProgress(0).complete);
        Object next=ReflectionHelpers.callConstructor(jobClass,ReflectionHelpers.ClassParameter.from(int.class,0));imports.put(owner,next);
        ReflectionHelpers.callInstanceMethod(next,"update",ReflectionHelpers.ClassParameter.from(String.class,"Saving pack"),ReflectionHelpers.ClassParameter.from(String.class,"Pack"),ReflectionHelpers.ClassParameter.from(int.class,120),ReflectionHelpers.ClassParameter.from(int.class,120),ReflectionHelpers.ClassParameter.from(long.class,0L),ReflectionHelpers.ClassParameter.from(long.class,-1L));
        ReflectionHelpers.callInstanceMethod(next,"finished",ReflectionHelpers.ClassParameter.from(String.class,null));
        assertTrue(MonogramPacks.importProgress(0).complete);assertEquals(120,MonogramPacks.importProgress(0).completed);
        Object queued=ReflectionHelpers.callConstructor(jobClass,ReflectionHelpers.ClassParameter.from(int.class,0));imports.put(owner,queued);
        MonogramPacks.logout(0);assertNull(MonogramPacks.importProgress(0));
        MonogramPacks.Request old=ReflectionHelpers.getField(queued,"request");assertThrows(RuntimeException.class,()->ReflectionHelpers.callInstanceMethod(old,"check"));
        MonogramPacks.removeImportObserver(0,reopened);
    }
    @Test public void knownStickerKindSurvivesPhotoAndVideoMetadataDecodingAndSerialization() throws Exception {
        TLRPC.TL_messageMediaDocument previous=new TLRPC.TL_messageMediaDocument();previous.flags=1;previous.document=new TLRPC.TL_document();
        previous.document.id=7;previous.document.mime_type="image/webp";previous.document.file_reference=new byte[0];previous.document.localPath="/private/cached-sticker";previous.document.size=30;
        TLRPC.TL_documentAttributeSticker sticker=new TLRPC.TL_documentAttributeSticker();sticker.alt="🙂";sticker.stickerset=new TLRPC.TL_inputStickerSetEmpty();previous.document.attributes.add(sticker);
        TLRPC.TL_messageMediaPhoto photo=new TLRPC.TL_messageMediaPhoto();photo.photo=new TLRPC.TL_photoEmpty();
        TLRPC.MessageMedia restored=MonogramStickers.preserve(previous,photo);assertTrue(restored instanceof TLRPC.TL_messageMediaDocument);assertTrue(MessageObject.isStickerDocument(restored.document));
        org.telegram.tgnet.SerializedData data=new org.telegram.tgnet.SerializedData();restored.serializeToStream(data);
        org.telegram.tgnet.SerializedData input=new org.telegram.tgnet.SerializedData(data.toByteArray());TLRPC.MessageMedia read=TLRPC.MessageMedia.TLdeserialize(input,input.readInt32(true),true);data.cleanup();input.cleanup();assertTrue(MessageObject.isStickerDocument(read.document));
        previous.document.mime_type="video/webm";TLRPC.TL_messageMediaDocument video=new TLRPC.TL_messageMediaDocument();video.document=new TLRPC.TL_document();video.document.mime_type="video/webm";
        MonogramAttachments.preserveMediaAttributes(previous,video);assertTrue(MessageObject.isVideoStickerDocument(video.document));
    }
    @Test public void markerProjectsPngToNativeStickerWithoutRelabellingOrdinaryImages() throws Exception {
        FileShare sharing=new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>opaque.png</name><media-type>image/png</media-type><size>42</size></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='https://example.org/opaque.png'/></sources></file-sharing>");
        TLRPC.TL_message message=new TLRPC.TL_message();message.id=7;message.message="😂";message.attachPath="/private/opaque";
        MonogramStickers.apply(message,sharing);assertNull(message.media);
        MonogramStickers.apply(message,sharing.asSticker());assertNotNull(message.media.document);assertTrue(MessageObject.isStickerDocument(message.media.document));
        assertEquals(42,message.media.document.size);assertEquals(MonogramAttachments.PHOTO_VOLUME,message.media.document.thumbs.get(0).location.volume_id);
        assertEquals(7,message.media.document.thumbs.get(0).location.local_id);assertEquals("/private/opaque",message.media.document.localPath);
    }
}
