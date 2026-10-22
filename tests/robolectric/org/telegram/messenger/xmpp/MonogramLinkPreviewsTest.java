package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramLinkPreviewsTest {
    private Context context;private MonogramStore store;private long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        context.deleteDatabase("previews.db");store=new MonogramStore(context,"previews.db");
        self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
    }
    @After public void cleanup() { store.close();context.deleteDatabase("previews.db"); }
    private TLRPC.TL_webPage page(String url) {
        TLRPC.TL_webPage page=new TLRPC.TL_webPage();page.id=9;page.flags=4;page.url=url;page.display_url="example.org";page.title="Preview";return page;
    }
    private void rejectUrl(String url) throws Exception {
        try { MonogramPreviewHttp.safeUrl(url);fail("Unsafe URL accepted: "+url); } catch(IOException expected) {}
    }
    @Test public void destinationPolicyRejectsCredentialsCleartextPortsAndLocalAddresses() throws Exception {
        for(String url:Arrays.asList("http://example.org/","https://user:pass@example.org/","https://example.org:8443/","file:///etc/passwd","https://localhost/","https://example.org\\@127.0.0.1/","https://example.org./")) rejectUrl(url);
        for(String address:Arrays.asList("127.0.0.1","10.2.3.4","169.254.169.254","172.16.0.1","192.168.1.1","100.64.0.1","192.0.0.8","198.18.0.1","224.0.0.1","::1","fc00::1","fe80::1","::ffff:127.0.0.1","2001:db8::1","2002:7f00:1::1","64:ff9b::7f00:1"))
            assertFalse(address,MonogramPreviewHttp.publicAddress(InetAddress.getByName(address)));
        assertTrue(MonogramPreviewHttp.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(MonogramPreviewHttp.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
        assertEquals("https://example.org/path?q=yes",MonogramPreviewHttp.safeUrl("https://example.org/path?q=yes#private-key").toString());
        rejectUrl(new URI("https://example.org/").resolve("http://127.0.0.1/").toString());
        assertFalse(MonogramPreviewHttp.publicAddress(InetAddress.getByName(new URI("https://127.0.0.1/").getHost())));
    }
    @Test public void metadataUsesOgFieldsAndDecodesEntitiesAsPlainText() {
        Map<String,String> fields=MonogramLinkPreviews.metadata("<title>Fallback &amp; title</title><meta content='Hello &amp; friends' property='og:title'><meta name=description content=Summary><meta property=og:image content='https://example.org/a.jpg?a=1&amp;b=2'><meta property=og:description content='&lt;b&gt;safe&lt;/b&gt;'>");
        assertEquals("Hello & friends",fields.get("og:title"));assertEquals("Fallback & title",fields.get("title"));
        assertEquals("Summary",fields.get("description"));assertEquals("https://example.org/a.jpg?a=1&b=2",fields.get("og:image"));
        assertEquals("<b>safe</b>",fields.get("og:description"));
        assertEquals("https://example.org/page",MonogramLinkPreviews.firstUrl("look: https://example.org/page)."));
        assertNull(MonogramLinkPreviews.firstUrl("aesgcm://example.org/file#secret"));
    }
    @Test public void boundedHttpBodyHandlesChunksAndRejectsOversizeAmbiguousAndTruncatedResponses() throws Exception {
        Map<String,String> headers=new HashMap<>();headers.put("transfer-encoding","chunked");
        byte[] chunks="3\r\nabc\r\n2;test=1\r\nde\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals("abcde".getBytes(StandardCharsets.US_ASCII),MonogramPreviewHttp.body(new ByteArrayInputStream(chunks),headers,5,()->true));
        try { MonogramPreviewHttp.body(new ByteArrayInputStream(chunks),headers,4,()->true);fail(); } catch(IOException expected) {}
        headers.put("content-length","5");try { MonogramPreviewHttp.body(new ByteArrayInputStream(chunks),headers,5,()->true);fail(); } catch(IOException expected) {}
        headers.remove("transfer-encoding");try { MonogramPreviewHttp.body(new ByteArrayInputStream(new byte[4]),headers,5,()->true);fail(); } catch(EOFException expected) {}
        try { MonogramPreviewHttp.body(new ByteArrayInputStream(new byte[5]),headers,5,()->false);fail(); } catch(IOException expected) {}
    }
    @Test public void htmlMetadataPrefixCanBeReadWithoutDownloadingTheWholePageButImagesStayStrict() throws Exception {
        byte[] page="<title>Preview</title>large document tail".getBytes(StandardCharsets.UTF_8);
        Map<String,String> headers=new HashMap<>();headers.put("content-length",Integer.toString(page.length));
        byte[] prefix=MonogramPreviewHttp.body(new ByteArrayInputStream(page),headers,22,()->true,true);
        assertEquals(22,prefix.length);assertEquals("Preview",MonogramLinkPreviews.metadata(new String(prefix,StandardCharsets.UTF_8)).get("title"));
        try { MonogramPreviewHttp.body(new ByteArrayInputStream(page),headers,22,()->true);fail(); } catch(IOException expected) {}
        headers.clear();headers.put("transfer-encoding","chunked");
        byte[] chunked=(Integer.toHexString(page.length)+"\r\n"+new String(page,StandardCharsets.UTF_8)+"\r\n0\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(prefix,MonogramPreviewHttp.body(new ByteArrayInputStream(chunked),headers,22,()->true,true));
        headers.put("content-length","22");
        try { MonogramPreviewHttp.body(new ByteArrayInputStream(chunked),headers,22,()->true,true);fail(); } catch(IOException expected) {}
        headers.clear();assertArrayEquals(prefix,MonogramPreviewHttp.body(new ByteArrayInputStream(page),headers,22,()->true,true));
    }
    @Test public void youtubeMetadataUsesOnlyFixedFirstPartyEndpointAndIgnoresRemotePlayerHtml() throws Exception {
        for(String url:Arrays.asList("https://www.youtube.com/watch?v=r8Ljx3SP03Y&list=private","https://youtu.be/r8Ljx3SP03Y?t=1","https://m.youtube.com/shorts/r8Ljx3SP03Y"))
            assertEquals("https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dr8Ljx3SP03Y&format=json",MonogramLinkPreviews.youtubeMetadataUrl(new URI(url)));
        assertNull(MonogramLinkPreviews.youtubeMetadataUrl(new URI("https://youtube.com.evil.example/watch?v=r8Ljx3SP03Y")));
        assertNull(MonogramLinkPreviews.youtubeMetadataUrl(new URI("https://www.youtube.com/watch?v=invalid")));
        Map<String,String> fields=MonogramLinkPreviews.youtubeMetadata("{\"title\":\"A &amp; B\",\"author_name\":\"Author\",\"thumbnail_url\":\"https://i.ytimg.com/a.jpg\",\"html\":\"<iframe src='https://example.org/'></iframe>\"}");
        assertEquals("A & B",fields.get("og:title"));assertEquals("YouTube",fields.get("og:site_name"));assertFalse(fields.containsKey("og:description"));assertEquals("video",MonogramLinkPreviews.previewType(fields,true));assertFalse(fields.containsKey("html"));
    }
    @Test public void fetchPolicyUpgradeRetriesOldFailuresWithoutDiscardingSuccessfulPreviews() throws Exception {
        android.content.SharedPreferences preferences=context.getSharedPreferences("preview-policy",Context.MODE_PRIVATE);preferences.edit().clear().putString("jid","alice@example.org").commit();
        MonogramLinkPreviews previews=new MonogramLinkPreviews(0,preferences,store);previews.enabled(true);
        String url="https://example.org/works";store.saveLinkPreview(url,page(url),System.currentTimeMillis()+60000);
        store.saveLinkPreview("https://example.org/failed",null,System.currentTimeMillis()+60000);
        TLRPC.TL_message message=new TLRPC.TL_message();message.id=1;message.message=url;message.media=new TLRPC.TL_messageMediaEmpty();
        AtomicBoolean refreshed=new AtomicBoolean();previews.maybeFetch(message,false,()->true,Runnable::run,()->refreshed.set(true));
        assertTrue(refreshed.get());assertTrue(store.linkPreviewFresh(url,System.currentTimeMillis()));assertFalse(store.linkPreviewFresh("https://example.org/failed",System.currentTimeMillis()));
        previews.cancel();
    }
    @Test public void videoClassificationAndYouTubeCacheRefreshPreserveOtherCachedPreviews() throws Exception {
        Map<String,String> fields=MonogramLinkPreviews.metadata("<meta property='og:type' content='video.other'><meta property='og:description' content='Actual video description'>");
        assertEquals("video",MonogramLinkPreviews.previewType(fields,false));assertEquals("Actual video description",fields.get("og:description"));
        assertEquals("article",MonogramLinkPreviews.previewType(Collections.emptyMap(),false));
        String video="https://www.youtube.com/watch?v=r8Ljx3SP03Y",article="https://example.org/article";
        store.saveLinkPreview(video,page(video),System.currentTimeMillis()+60000);store.saveLinkPreview(article,page(article),System.currentTimeMillis()+60000);
        store.expireYouTubeLinkPreviews();assertFalse(store.linkPreviewFresh(video,System.currentTimeMillis()));assertTrue(store.linkPreviewFresh(article,System.currentTimeMillis()));
        int id=store.save(MessageFixture.replyMessage("cached-video","cached-video",null,video,true,false,100000,"phone",false,null));
        assertTrue(store.getMessage(id,self).media instanceof TLRPC.TL_messageMediaWebPage);
    }
    @Test public void cachedPreviewSurvivesReopenButNeverOverridesMediaOrEditedText() throws Exception {
        int id=store.save(MessageFixture.replyMessage("preview","preview",null,"https://example.org/page",true,false,100000,"phone",false,null));
        String url="https://example.org/page";store.saveLinkPreview(url,page(url),System.currentTimeMillis()+100000);
        assertTrue(store.getMessage(id,self).media instanceof TLRPC.TL_messageMediaWebPage);
        store.close();store=new MonogramStore(context,"previews.db");assertEquals("Preview",store.getMessage(id,self).media.webpage.title);
        store.save(MessageFixture.correction("edit","edit",null,"new text",true,false,200000,"phone",false,null,"preview"));store.reconcileCorrections(peer);
        assertTrue(store.getMessage(id,self).media instanceof TLRPC.TL_messageMediaEmpty);
        TLRPC.TL_message explicit=new TLRPC.TL_message();explicit.id=4;explicit.message=url;explicit.media=new TLRPC.TL_messageMediaPhoto();
        assertFalse(MonogramLinkPreviews.candidate(explicit));
        store.deleteMessages(Collections.singleton(id));assertFalse(MonogramLinkPreviews.candidate(store.getMessage(id,self)));
        store.clearLinkPreviews();assertFalse(store.linkPreviewFresh(url,0));
    }
    @Test public void freshFailuresSuppressRetriesAndPrivacyDefaultsPreventNetworkScheduling() throws Exception {
        android.content.SharedPreferences preferences=context.getSharedPreferences("preview-test",Context.MODE_PRIVATE);preferences.edit().clear().putString("jid","alice@example.org").commit();
        MonogramLinkPreviews previews=new MonogramLinkPreviews(0,preferences,store);assertFalse(previews.enabled());assertFalse(previews.encryptedEnabled());
        TLRPC.TL_message message=new TLRPC.TL_message();message.id=1;message.message="https://example.org/";message.media=new TLRPC.TL_messageMediaEmpty();
        AtomicBoolean refreshed=new AtomicBoolean();
        previews.maybeFetch(message,false,()->true,Runnable::run,()->refreshed.set(true));assertFalse(refreshed.get());
        previews.enabled(true);previews.maybeFetch(message,true,()->true,Runnable::run,()->refreshed.set(true));assertFalse(refreshed.get());
        store.saveLinkPreview("https://example.org/",null,System.currentTimeMillis()+60000);assertTrue(store.linkPreviewFresh("https://example.org/",System.currentTimeMillis()));
        assertTrue(store.getMessage(store.save(MessageFixture.replyMessage("failure","failure",null,message.message,true,false,100000,"phone",false,null)),self).media instanceof TLRPC.TL_messageMediaEmpty);
        previews.encryptedEnabled(true);preferences.edit().putString("jid","other@example.org").commit();assertFalse(previews.enabled());assertFalse(previews.encryptedEnabled());
        previews.cancel();
    }
    @Test public void thumbnailLocationRemainsLocalAcrossNativeSerializationAndDatabaseReopen() throws Exception {
        String url="https://example.org/image-page";TLRPC.TL_webPage page=page(url);page.flags|=16;
        TLRPC.TL_photo photo=new TLRPC.TL_photo();photo.id=99;photo.file_reference=new byte[0];photo.dc_id=MonogramLinkPreviews.LOCAL_DC;
        TLRPC.TL_photoSize_layer127 size=new TLRPC.TL_photoSize_layer127();size.type="x";size.w=size.h=100;size.size=1024;
        size.location=new TLRPC.TL_fileLocation_layer82();size.location.volume_id=99;size.location.local_id=0;size.location.dc_id=MonogramLinkPreviews.LOCAL_DC;
        photo.sizes.add(size);page.photo=photo;
        store.saveLinkPreview(url,page,System.currentTimeMillis()+60000);
        int id=store.save(MessageFixture.replyMessage("thumb","thumb",null,url,true,false,100000,"phone",false,null));
        store.close();store=new MonogramStore(context,"previews.db");
        TLRPC.Photo cached=store.getMessage(id,self).media.webpage.photo;
        assertEquals(MonogramLinkPreviews.LOCAL_DC,cached.dc_id);assertEquals(MonogramLinkPreviews.LOCAL_DC,cached.sizes.get(0).location.dc_id);
        File local=MonogramLinkPreviews.localThumbnail(cached.sizes.get(0));assertNotNull(local);
        assertTrue(local.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator));
        assertEquals("63.jpg",local.getName());assertNull(MonogramLinkPreviews.localThumbnail(new TLRPC.TL_photoSizeEmpty()));
    }
    @Test public void schema27MigrationPreservesMessagesAndAddsEmptyPreviewCache() throws Exception {
        int id=store.save(MessageFixture.replyMessage("preserve","preserve",null,"Keep me",true,false,100000,"phone",true,null));
        store.getWritableDatabase().execSQL("DROP TABLE link_previews");store.getWritableDatabase().execSQL("DROP TABLE IF EXISTS saved_message_tags");store.getWritableDatabase().execSQL("DROP TABLE IF EXISTS saved_tag_names");store.getWritableDatabase().setVersion(27);store.close();store=new MonogramStore(context,"previews.db");
        assertEquals(29,store.getReadableDatabase().getVersion());assertEquals("Keep me",store.getMessage(id,self).message);assertFalse(store.linkPreviewFresh("https://example.org/",0));
    }
    @Test public void keepsDirectMediaRedirectsAndSignedVideoUrlsWithoutAcceptingHtmlPlayers() throws Exception {
        java.net.URI page=new java.net.URI("https://example.org/post");
        java.util.Map<String,String> image=MonogramLinkPreviews.directMetadata(new java.net.URI("https://cdn.example.org/photo.jpg?token=abc"),"image/jpeg",page);
        assertEquals("https://cdn.example.org/photo.jpg?token=abc",image.get("og:image"));
        assertEquals("photo",MonogramLinkPreviews.previewType(image,false));
        java.util.Map<String,String> video=MonogramLinkPreviews.metadata("<meta property='og:video:secure_url' content='https://cdn.example.org/clip.mp4?token=abc&amp;x=2'><meta property='og:video:type' content='video/mp4'>");
        assertEquals("https://cdn.example.org/clip.mp4?token=abc&x=2",MonogramLinkPreviews.videoUrl(video,page));
        video.put("og:video:secure_url","javascript:alert(1)");assertNull(MonogramLinkPreviews.videoUrl(video,page));
        video.put("og:video:secure_url","https://example.org/player.html");assertNull(MonogramLinkPreviews.videoUrl(video,page));
        assertThrows(java.io.IOException.class,()->MonogramLinkPreviews.directMetadata(page,"text/html",page));
    }
}
