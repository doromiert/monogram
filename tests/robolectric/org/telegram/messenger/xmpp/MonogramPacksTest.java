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
        shadows={MonogramPacksTest.Account.class, MonogramPacksTest.App.class, MonogramPacksTest.Ui.class, MonogramPacksTest.PackUi.class})
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
    }
    @Implements(value=MonogramPacks.class,isInAndroidSdk=false)
    public static class PackUi { @Implementation public static void refresh(int account) {} }
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
    @Test public void markerProjectsPngToNativeStickerWithoutRelabellingOrdinaryImages() throws Exception {
        FileShare sharing=new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>opaque.png</name><media-type>image/png</media-type><size>42</size></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='https://example.org/opaque.png'/></sources></file-sharing>");
        TLRPC.TL_message message=new TLRPC.TL_message();message.id=7;message.message="😂";message.attachPath="/private/opaque";
        MonogramStickers.apply(message,sharing);assertNull(message.media);
        MonogramStickers.apply(message,sharing.asSticker());assertNotNull(message.media.document);assertTrue(MessageObject.isStickerDocument(message.media.document));
        assertEquals(42,message.media.document.size);assertEquals(MonogramAttachments.PHOTO_VOLUME,message.media.document.thumbs.get(0).location.volume_id);
        assertEquals(7,message.media.document.thumbs.get(0).location.local_id);assertEquals("/private/opaque",message.media.document.localPath);
    }
}
