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
        shadows={MonogramPacksTest.Account.class, MonogramPacksTest.App.class, MonogramPacksTest.Ui.class, MonogramPacksTest.PackUi.class, MonogramPacksTest.EmojiUi.class,MonogramPacksTest.Vault.class})
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
    public static class PackUi {
        @Implementation public static void refresh(int account) {}
        @Implementation protected static org.json.JSONObject request(String token,String method,String key,String value,MonogramPacks.Request operation) throws Exception {
            if(method.equals("getStickerSet")) {
                if(value.equals("Broken")) throw new java.io.IOException("fixture failure");
                org.json.JSONArray items=new org.json.JSONArray();
                for(int i=0;i<8;i++) items.put(new org.json.JSONObject().put("file_id",String.valueOf(i)).put("emoji","🙂"));
                return new org.json.JSONObject().put("result",new org.json.JSONObject().put("name",value).put("title",value).put("sticker_type","regular").put("stickers",items));
            }
            int now=networkActive.incrementAndGet();networkPeak.accumulateAndGet(now,Math::max);
            try {
                parallelReady.countDown();
                if(!parallelReady.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Downloads were serialized");
                return new org.json.JSONObject().put("result",new org.json.JSONObject().put("file_path",value+".png"));
            } finally { networkActive.decrementAndGet(); }
        }
        @Implementation protected static byte[] download(String url,int limit,MonogramPacks.Request operation) {
            return java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
        }
    }
    static java.util.concurrent.CountDownLatch parallelReady;
    static java.util.concurrent.atomic.AtomicInteger networkActive=new java.util.concurrent.atomic.AtomicInteger(),networkPeak=new java.util.concurrent.atomic.AtomicInteger();
    @Implements(value=AccountVault.class,isInAndroidSdk=false)
    public static class Vault {
        @Implementation protected String encrypt(String value) { return "fixture"; }
        @Implementation protected String decrypt(String value) { return "fixture"; }
    }
    @Implements(value=org.telegram.ui.Components.AnimatedEmojiDrawable.class,isInAndroidSdk=false)
    public static class EmojiUi { @Implementation public static void clearMonogramPacks(int account) {} }
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        controller=Shadow.newInstanceOf(MonogramController.class); jid="alice@example.org";
    }
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test public void archiveImportUsesFreshNativeIdsAndFailurePreservesInstalledPack() throws Exception {
        jid="archive@example.org";
        MonogramPacks.Pack original=new MonogramPacks.Pack();original.id=0x4d50000300000001L;original.name="Friends";original.source="local:archive";
        MonogramPacks.Item item=new MonogramPacks.Item();item.id=0x4d50000300000002L;item.name="Wave";item.emoji="👋";item.mime="image/png";item.file="original.png";item.width=item.height=1;original.items.add(item);
        String scope=ReflectionHelpers.callStaticMethod(MonogramPacks.class,"scope",ReflectionHelpers.ClassParameter.from(int.class,0));
        File directory=ReflectionHelpers.callStaticMethod(MonogramPacks.class,"directory",ReflectionHelpers.ClassParameter.from(String.class,scope));directory.mkdirs();
        byte[] image=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
        java.nio.file.Files.write(new File(directory,item.file).toPath(),image);
        ArrayList<MonogramPacks.Pack> packs=new ArrayList<>();packs.add(original);
        ReflectionHelpers.callStaticMethod(MonogramPacks.class,"write",ReflectionHelpers.ClassParameter.from(String.class,scope),ReflectionHelpers.ClassParameter.from(ArrayList.class,packs));
        java.util.concurrent.CountDownLatch exported=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<File> archive=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        MonogramPacks.exportArchive(0,original.id,(file,failure)->{archive.set(file);error.set(failure);exported.countDown();});
        assertTrue(exported.await(10,java.util.concurrent.TimeUnit.SECONDS));assertNull(error.get());assertNotNull(archive.get());
        for(int pass=0;pass<2;pass++) {
            android.net.Uri uri=android.net.Uri.parse("content://pack-fixture/archive"+pass);
            byte[] input=pass==0 ? java.nio.file.Files.readAllBytes(archive.get().toPath()) : new byte[]{1,2,3};
            org.robolectric.Shadows.shadowOf(ApplicationLoader.applicationContext.getContentResolver()).registerInputStream(uri,new java.io.ByteArrayInputStream(input));
            java.util.concurrent.CountDownLatch imported=new java.util.concurrent.CountDownLatch(1);
            // Do not count the previous job's terminal snapshot as this import's completion.
            java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean();
            MonogramPacks.ImportObserver observer=progress->{if(progress.active)active.set(true);else if(active.get())imported.countDown();};
            MonogramPacks.addImportObserver(0,observer);MonogramPacks.startArchiveImport(0,uri);
            assertTrue(imported.await(10,java.util.concurrent.TimeUnit.SECONDS));MonogramPacks.removeImportObserver(0,observer);
            assertEquals(2,MonogramPacks.packs(0).size());
            assertEquals(pass==0,MonogramPacks.importProgress(0).complete);
        }
        MonogramPacks.Pack restored=MonogramPacks.packs(0).get(0);
        assertNotEquals(original.id,restored.id);assertNotEquals(item.id,restored.items.get(0).id);
        assertEquals(item.name,restored.items.get(0).name);assertTrue(restored.privatePack);assertFalse(restored.connected);
        assertTrue(restored.source.startsWith("xmsp:"));assertNotNull(MonogramPacks.document(0,restored.items.get(0).id));archive.get().delete();
    }
    @Test public void detachAndNativeEditsPersistWithoutStickerOrPackCountCaps() throws Exception {
        jid="editor@example.org";
        String scope=ReflectionHelpers.callStaticMethod(MonogramPacks.class,"scope",ReflectionHelpers.ClassParameter.from(int.class,0));
        ArrayList<MonogramPacks.Pack> packs=new ArrayList<>();
        for(int p=0;p<105;p++) {
            MonogramPacks.Pack pack=new MonogramPacks.Pack();pack.id=0x4d50001000000000L+p+1;
            pack.name="Pack "+p;pack.source="https://t.me/addstickers/Pack_"+p;pack.connected=p==0;
            for(int i=0;i<(p==0 ? 260 : 1);i++) {
                MonogramPacks.Item item=new MonogramPacks.Item();item.id=0x4d50002000000000L+p*1000+i+1;
                item.name="Sticker "+i;item.emoji="🙂";item.mime="image/png";item.file="artwork.png";item.width=item.height=1;pack.items.add(item);
            }
            packs.add(pack);
        }
        ReflectionHelpers.callStaticMethod(MonogramPacks.class,"write",ReflectionHelpers.ClassParameter.from(String.class,scope),ReflectionHelpers.ClassParameter.from(ArrayList.class,packs));
        long id=packs.get(0).id,first=packs.get(0).items.get(0).id,last=packs.get(0).items.get(259).id;
        try { MonogramPacks.rename(0,id,0,"Changed",false);fail("Connected pack was edited"); } catch(IllegalStateException expected) {}
        MonogramPacks.detach(0,id);
        MonogramPacks.Pack detached=MonogramPacks.pack(0,id);
        assertFalse(detached.connected);assertFalse(MonogramPacks.telegramSource(detached));
        assertEquals(260,detached.items.size());assertEquals(first,detached.items.get(0).id);
        assertEquals("artwork.png",detached.items.get(0).file);
        MonogramPacks.rename(0,id,0,"My pack",false);
        MonogramPacks.rename(0,id,last,"My sticker",false);
        MonogramPacks.rename(0,id,last,"👋",true);
        MonogramPacks.moveItem(0,id,last,0);
        MonogramPacks.removeItem(0,id,first);
        java.util.Map<?,?> cache=ReflectionHelpers.getStaticField(MonogramPacks.class,"CACHE");cache.clear();
        detached=MonogramPacks.pack(0,id);
        assertEquals(105,MonogramPacks.packs(0).size());assertEquals(259,detached.items.size());
        assertEquals("My pack",detached.name);assertEquals(last,detached.items.get(0).id);
        assertEquals("My sticker",detached.items.get(0).name);assertEquals("👋",detached.items.get(0).emoji);
        TLRPC.TL_inputStickerSetID input=new TLRPC.TL_inputStickerSetID();input.id=id;
        assertTrue(MonogramPacks.find(0,input).set.creator);
        jid="other@example.org";assertNull(MonogramPacks.pack(0,id));
    }
    @Test public void bulkQueueContinuesAfterFailureAndDownloadsFourAtATimeInOriginalOrder() throws Exception {
        jid="bulk@example.org";parallelReady=new java.util.concurrent.CountDownLatch(4);networkActive.set(0);networkPeak.set(0);
        MonogramPacks.saveToken(0,"123456:abcdefghijklmnopqrstuvwx");
        java.util.concurrent.CountDownLatch finished=new java.util.concurrent.CountDownLatch(1);
        MonogramPacks.ImportObserver observer=progress->{if(!progress.active) finished.countDown();};
        MonogramPacks.addImportObserver(0,observer);
        try {
            MonogramPacks.startImport(0,"https://t.me/addstickers/First\nhttps://t.me/addstickers/Broken\nhttps://t.me/addstickers/Last");
            assertTrue(finished.await(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(4,networkPeak.get());assertEquals(0,networkActive.get());
            assertEquals(2,MonogramPacks.packs(0).size());
            for(MonogramPacks.Pack pack:MonogramPacks.packs(0)) {
                assertEquals(8,pack.items.size());
                for(int i=0;i<8;i++) assertEquals("Item "+(i+1),pack.items.get(i).name);
            }
            assertTrue(MonogramPacks.importProgress(0).error.contains("2 / 3 packs imported"));
        } finally { MonogramPacks.removeImportObserver(0,observer); }
    }
    private static class Connection extends javax.net.ssl.HttpsURLConnection {
        boolean disconnected;
        Connection() throws Exception { super(new java.net.URL("https://example.org")); }
        public void disconnect() { disconnected=true; }
        public boolean usingProxy() { return false; }
        public void connect() {}
        public String getCipherSuite() { return "fixture"; }
        public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        public java.security.cert.Certificate[] getServerCertificates() { return null; }
    }
    @Test public void cancellationDisconnectsEveryParallelConnection() throws Exception {
        MonogramPacks.Request root=ReflectionHelpers.callConstructor(MonogramPacks.Request.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        Connection[] connections=new Connection[4];
        for(int i=0;i<4;i++) {
            MonogramPacks.Request child=ReflectionHelpers.callConstructor(MonogramPacks.Request.class,ReflectionHelpers.ClassParameter.from(int.class,0));
            ReflectionHelpers.setField(child,"parent",root);connections[i]=new Connection();
            ReflectionHelpers.callInstanceMethod(child,"attach",ReflectionHelpers.ClassParameter.from(javax.net.ssl.HttpsURLConnection.class,connections[i]));
        }
        root.cancel();for(Connection connection:connections) assertTrue(connection.disconnected);
    }
    @Test public void bulkLinksValidateAllInputAndDeduplicateCanonicalPackNames() {
        ArrayList<String> links=MonogramPacks.parseLinks("https://t.me/addstickers/Cats\nhttps://telegram.me/addemoji/Smiles, https://t.me/addstickers/cats");
        assertEquals(2,links.size());assertEquals("https://t.me/addstickers/Cats",links.get(0));
        assertEquals("https://t.me/addemoji/Smiles",links.get(1));
        try { MonogramPacks.parseLinks("https://t.me/addstickers/Cats\nhttps://evil.example/pack");fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void packLinksAreStrictAndNamesRemainSeparateFromEmojiTags() throws Exception {
        assertFalse(MonogramPacks.parseLink("https://t.me/addstickers/Cat_pack").emojis);
        assertFalse(MonogramPacks.parseLink("https://t.me/addstickers/NeuAnaxKulupeNaklejei").emojis);
        assertTrue(MonogramPacks.parseLink("https://telegram.me/addemoji/Cat_pack").emojis);
        for(String link:new String[]{"http://t.me/addstickers/a","https://t.me.evil/addstickers/a","https://user@t.me/addstickers/a","https://t.me/addemoji/a?token=secret","https://t.me/addemoji/../a","https://t.me/addemoji/a/b"}) {
            try { MonogramPacks.parseLink(link); fail(link); } catch(IllegalArgumentException expected) { }
        }
        assertEquals("My friends",MonogramPacks.name(" My friends "));
        for(String invalid:new String[]{"","\n","bad\u0000name"}) try { MonogramPacks.name(invalid);fail(); } catch(IllegalArgumentException expected) { }
    }
    @Test public void importDiagnosticsDistinguishFailuresWithoutReturningApiSecrets() throws Exception {
        String secret="123456789:secret-token-do-not-display";
        String rejected=MonogramPacks.telegramError(401,new org.json.JSONObject().put("error_code",401).put("description",secret)).getMessage();
        assertTrue(rejected.contains("Bot token was rejected"));assertTrue(rejected.contains("HTTP 401"));assertFalse(rejected.contains(secret));
        String missing=MonogramPacks.telegramError(400,new org.json.JSONObject().put("error_code",400).put("description","STICKERSET_INVALID "+secret)).getMessage();
        assertTrue(missing.contains("could not find"));assertFalse(missing.contains(secret));
        assertTrue(MonogramPacks.telegramError(429,null).getMessage().contains("rate limiting"));
        java.lang.reflect.Constructor<MonogramPacks.ImportProgress> constructor=MonogramPacks.ImportProgress.class.getDeclaredConstructor(String.class,String.class,String.class,int.class,int.class,long.class,long.class,boolean.class,boolean.class);
        constructor.setAccessible(true);
        MonogramPacks.ImportProgress progress=constructor.newInstance("Downloading sticker","Pack",null,4,120,0L,-1L,true,false);
        String timedOut=MonogramPacks.importError(new java.net.SocketTimeoutException(secret),progress);
        assertTrue(timedOut.contains("timed out"));assertTrue(timedOut.contains("item 5/120"));assertFalse(timedOut.contains(secret));
        assertFalse(MonogramPacks.importError(new java.io.IOException(secret),progress).contains(secret));
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
