// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;
import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.tgnet.TLRPC;

/** Device-local previews: no preview proxy, XMPP payload, cookies, JavaScript or remote image locations. */
public final class MonogramLinkPreviews {
    static final int LOCAL_DC=Integer.MIN_VALUE+43;
    private static final long LIFETIME=7L*24*60*60*1000;
    private static final Pattern URL=Pattern.compile("https://[^\\s<>\\\"\\u0000-\\u001f]+",Pattern.CASE_INSENSITIVE);
    private static final Pattern META=Pattern.compile("<meta\\b[^>]{0,8192}>",Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR=Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TITLE=Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private final int account;
    private final SharedPreferences preferences;
    private final MonogramStore store;
    private final ThreadPoolExecutor network=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r -> new Thread(r,"Monogram previews"),new ThreadPoolExecutor.AbortPolicy());
    private final Set<Integer> pending=ConcurrentHashMap.newKeySet();
    private volatile long privacyEpoch;
    private String cachePolicyOwner;
    MonogramLinkPreviews(int account,SharedPreferences preferences,MonogramStore store) {
        this.account=account;this.preferences=preferences;this.store=store;network.allowCoreThreadTimeOut(true);
    }
    private String key(String field) { return "link-previews-"+field+"-"+preferences.getString("jid",""); }
    public boolean enabled() { return preferences.getBoolean(key("enabled"),false); }
    public boolean encryptedEnabled() { return preferences.getBoolean(key("encrypted"),false); }
    public void enabled(boolean value) { privacyEpoch++;preferences.edit().putBoolean(key("enabled"),value).apply();if(!value) { network.getQueue().clear();pending.clear(); } }
    public void encryptedEnabled(boolean value) { privacyEpoch++;preferences.edit().putBoolean(key("encrypted"),value).apply(); }
    void cancel() { privacyEpoch++;network.getQueue().clear();pending.clear(); }
    private File directory() { return new File(ApplicationLoader.applicationContext.getCacheDir(),"link-previews-"+account); }
    void clear() {
        cancel();store.clearLinkPreviews();File[] files=directory().listFiles();if(files!=null) for(File file:files) file.delete();
    }
    static String firstUrl(String body) {
        if(body==null || body.length()>65536) return null;
        Matcher found=URL.matcher(body);
        if(!found.find()) return null;
        String value=found.group();
        while(!value.isEmpty() && ".,;:!?)]}".indexOf(value.charAt(value.length()-1))>=0) value=value.substring(0,value.length()-1);
        try { return MonogramPreviewHttp.safeUrl(value).toASCIIString(); } catch(IOException invalid) { return null; }
    }
    static boolean candidate(TLRPC.Message message) {
        return message!=null && message.id>0 && !(message instanceof TLRPC.TL_messageService)
                && (message.media==null || message.media instanceof TLRPC.TL_messageMediaEmpty || message.media instanceof TLRPC.TL_messageMediaWebPage) && firstUrl(message.message)!=null;
    }
    synchronized void prepareCache() {
        String policyKey=key("fetch-policy");
        if(policyKey.equals(cachePolicyOwner)) return;
        if(preferences.getInt(policyKey,0)<3) {
            store.clearFailedLinkPreviews();store.expireYouTubeLinkPreviews();preferences.edit().putInt(policyKey,3).apply();
        }
        cachePolicyOwner=policyKey;
    }
    void maybeFetch(TLRPC.Message message,boolean encrypted,BooleanSupplier sameAccount,java.util.concurrent.Executor owner,Runnable refreshed) {
        if(!enabled() || encrypted && !encryptedEnabled() || !candidate(message) || !pending.add(message.id)) return;
        prepareCache();
        final long privacy=privacyEpoch;final String url=firstUrl(message.message);final int id=message.id;
        if(store.linkPreviewFresh(url,System.currentTimeMillis())) { pending.remove(id);owner.execute(refreshed);return; }
        BooleanSupplier allowed=() -> privacy==privacyEpoch && sameAccount.getAsBoolean() && enabled() && (!encrypted || encryptedEnabled());
        try { network.execute(() -> {
            boolean cached=store.linkPreviewFresh(url,System.currentTimeMillis());
            TLRPC.WebPage page=null;long expires=System.currentTimeMillis()+60*60*1000;
            try { if(!cached && allowed.getAsBoolean()) { page=fetch(url,allowed);expires=System.currentTimeMillis()+LIFETIME; } }
            catch(Exception unavailable) { /* Optional metadata must never interrupt message delivery or expose URL/key details in logs. */ }
            final TLRPC.WebPage result=page;final long expiry=expires;
            owner.execute(() -> {
                try {
                    if(!allowed.getAsBoolean()) return;
                    if(!cached) store.saveLinkPreview(url,result,expiry);
                    // A correction/deletion may have replaced this payload while the network was in flight.
                    refreshed.run();
                } finally { pending.remove(id); }
            });
        }); } catch(RejectedExecutionException busy) { pending.remove(id); }
    }
    private TLRPC.WebPage fetch(String url,BooleanSupplier allowed) throws Exception {
        URI pageUrl=MonogramPreviewHttp.safeUrl(url);Map<String,String> fields=null;
        String metadataUrl=youtubeMetadataUrl(pageUrl);
        try {
            MonogramPreviewHttp.Response response=MonogramPreviewHttp.fetchHtml(url,metadataUrl==null ? 512*1024 : 1024*1024,allowed);
            String contentType=response.type.toLowerCase(Locale.ROOT);
            if(!contentType.startsWith("text/html") && !contentType.startsWith("application/xhtml+xml")) throw new IOException("Not an HTML preview");
            Charset charset=StandardCharsets.UTF_8;
            Matcher encoding=Pattern.compile("charset\\s*=\\s*[\"']?([-a-zA-Z0-9_]+)",Pattern.CASE_INSENSITIVE).matcher(response.type);
            if(encoding.find()) try { charset=Charset.forName(encoding.group(1)); } catch(IllegalArgumentException ignored) {}
            fields=metadata(new String(response.bytes,charset));pageUrl=metadataUrl==null ? response.url : pageUrl;
            if(fields.getOrDefault("og:title",fields.getOrDefault("title","")).isEmpty() || metadataUrl!=null && !fields.containsKey("og:title")) fields=null;
        } catch(IOException unavailable) { if(metadataUrl==null) throw unavailable; }
        if(fields==null && metadataUrl!=null) {
            MonogramPreviewHttp.Response response=MonogramPreviewHttp.fetch(metadataUrl,64*1024,allowed);
            if(!response.type.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new IOException("Not video metadata");
            fields=youtubeMetadata(new String(response.bytes,StandardCharsets.UTF_8));
        }
        if(fields==null) throw new IOException("No preview metadata");
        TLRPC.TL_webPage page=new TLRPC.TL_webPage();page.id=identifier(url);page.url=pageUrl.toASCIIString();page.display_url=pageUrl.getHost();page.hash=0;
        page.type=previewType(fields,metadataUrl!=null);page.flags=1;page.site_name=fields.getOrDefault("og:site_name",page.display_url);page.flags|=2;
        page.title=fields.getOrDefault("og:title",fields.getOrDefault("title",""));if(!page.title.isEmpty()) page.flags|=4;
        page.description=fields.getOrDefault("og:description",fields.getOrDefault("description",""));if(!page.description.isEmpty()) page.flags|=8;
        if(metadataUrl!=null) {
            // Only this locally constructed, validated first-party player URL reaches the native player.
            page.embed_url="https://www.youtube.com/embed/"+youtubeVideoId(MonogramPreviewHttp.safeUrl(url));page.embed_type="text/html";
            page.embed_width=1280;page.embed_height=720;page.flags|=32|64;
        }
        String image=fields.get("og:image");
        if(image!=null && !image.isEmpty() && allowed.getAsBoolean()) try {
            URI target=MonogramPreviewHttp.safeUrl(pageUrl.resolve(image).toASCIIString());
            MonogramPreviewHttp.Response thumbnail=MonogramPreviewHttp.fetch(target.toString(),2*1024*1024,allowed);
            String type=thumbnail.type.toLowerCase(Locale.ROOT).split(";",2)[0].trim();
            if(!Arrays.asList("image/jpeg","image/png","image/webp").contains(type)) throw new IOException("Unsupported thumbnail");
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(thumbnail.bytes,0,thumbnail.bytes.length,bounds);
            if(bounds.outWidth<1 || bounds.outHeight<1 || (long)bounds.outWidth*bounds.outHeight>40_000_000) throw new IOException("Invalid preview dimensions");
            BitmapFactory.Options decode=new BitmapFactory.Options();decode.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/decode.inSampleSize>640) { decode.inSampleSize*=2; }
            Bitmap bitmap=BitmapFactory.decodeByteArray(thumbnail.bytes,0,thumbnail.bytes.length,decode);if(bitmap==null) throw new IOException("Invalid preview image");
            try {
                if(!allowed.getAsBoolean()) throw new IOException("Preview cancelled");
                File directory=directory();if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Preview cache unavailable");
                File temporary=File.createTempFile("thumbnail-",".tmp",directory),destination=imageFile(account,page.id);
                try {
                    try(FileOutputStream output=new FileOutputStream(temporary)) { if(!bitmap.compress(Bitmap.CompressFormat.JPEG,82,output)) throw new IOException("Preview conversion failed"); }
                    if(!allowed.getAsBoolean() || !temporary.renameTo(destination)) throw new IOException("Preview cancelled");
                } finally { temporary.delete(); }
                TLRPC.TL_photo photo=new TLRPC.TL_photo();photo.id=page.id;photo.dc_id=LOCAL_DC;photo.file_reference=new byte[0];photo.date=(int)(System.currentTimeMillis()/1000);
                TLRPC.TL_photoSize_layer127 size=new TLRPC.TL_photoSize_layer127();size.type="x";size.w=bitmap.getWidth();size.h=bitmap.getHeight();size.size=(int)destination.length();
                size.location=new TLRPC.TL_fileLocation_layer82();size.location.volume_id=page.id;size.location.local_id=account;size.location.dc_id=LOCAL_DC;size.location.file_reference=new byte[0];photo.sizes.add(size);
                page.photo=photo;page.flags|=16;page.has_large_media="video".equals(page.type);trimImages(directory);
            } finally { bitmap.recycle(); }
        } catch(Exception noImage) { /* A text preview still works when a site's image is unsafe/unavailable. */ }
        if(page.title.isEmpty() && page.description.isEmpty() && page.photo==null) throw new IOException("No preview metadata");
        return page;
    }
    /** Fixed public first-party endpoint; never discover/fetch arbitrary oEmbed URLs from remote markup. */
    static String youtubeMetadataUrl(URI url) {
        String id=youtubeVideoId(url);if(id==null) return null;
        return "https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D"+id+"&format=json";
    }
    private static String youtubeVideoId(URI url) {
        if(url==null || url.getHost()==null) return null;
        String host=url.getHost().toLowerCase(Locale.ROOT),path=url.getPath(),id=null;
        if(host.equals("youtu.be")) id=path.length()>1 ? path.substring(1).split("/",2)[0] : null;
        else if(host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com") || host.equals("music.youtube.com")) {
            if(path.equals("/watch") && url.getRawQuery()!=null) {
                for(String part:url.getRawQuery().split("&")) if(part.startsWith("v=")) try { id=java.net.URLDecoder.decode(part.substring(2),"UTF-8");break; } catch(Exception invalid) { return null; }
            } else if(path.matches("/(shorts|live|embed)/[^/]+/?")) id=path.split("/")[2];
        }
        if(id==null || !id.matches("[A-Za-z0-9_-]{11}")) return null;
        return id;
    }
    static String previewType(Map<String,String> fields,boolean youtube) {
        String type=fields.getOrDefault("og:type","").toLowerCase(Locale.ROOT);
        return youtube || type.equals("video") || type.startsWith("video.") ? "video" : "article";
    }
    static Map<String,String> youtubeMetadata(String json) throws IOException {
        try {
            org.json.JSONObject object=new org.json.JSONObject(json);Map<String,String> fields=new HashMap<>();
            String title=clean(object.getString("title"),160);if(title.isEmpty()) throw new IOException("Missing video title");
            fields.put("og:title",title);fields.put("og:site_name","YouTube");
            // oEmbed author_name is not a description. Leave the description absent when HTML is unavailable.
            String image=object.optString("thumbnail_url","");if(!image.isEmpty()) fields.put("og:image",image);
            return fields;
        } catch(org.json.JSONException invalid) { throw new IOException("Invalid video metadata",invalid); }
    }
    private static void trimImages(File directory) {
        File[] files=directory.listFiles((folder,name) -> name.endsWith(".jpg"));if(files==null) return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());long bytes=0;
        for(int i=0;i<files.length;i++) { bytes+=files[i].length();if(i>=128 || bytes>16L*1024*1024) files[i].delete(); }
    }
    static Map<String,String> metadata(String html) {
        Map<String,String> result=new HashMap<>();Matcher tags=META.matcher(html);
        while(tags.find()) {
            Map<String,String> attributes=new HashMap<>();Matcher values=ATTR.matcher(tags.group());
            while(values.find()) attributes.put(values.group(1).toLowerCase(Locale.ROOT),values.group(2)!=null ? values.group(2) : values.group(3)!=null ? values.group(3) : values.group(4));
            String name=attributes.getOrDefault("property",attributes.getOrDefault("name","")).toLowerCase(Locale.ROOT),value=attributes.get("content");
            if(value!=null && Arrays.asList("og:title","og:description","og:site_name","og:image","og:type","description").contains(name) && !result.containsKey(name))
                result.put(name,clean(value,name.equals("og:image") ? 4096 : name.contains("description") ? 600 : 160));
        }
        Matcher title=TITLE.matcher(html);if(title.find()) result.put("title",clean(title.group(1),160));return result;
    }
    private static String clean(String value,int limit) {
        String text=(android.os.Build.VERSION.SDK_INT>=24 ? Html.fromHtml(value,Html.FROM_HTML_MODE_LEGACY) : Html.fromHtml(value)).toString().replaceAll("[\\p{Cc}\\p{Cf}\\s]+"," ").trim();
        return text.length()>limit ? text.substring(0,limit) : text;
    }
    private static long identifier(String url) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));long id=0;for(int i=0;i<8;i++) id=id<<8 | hash[i]&255L;return id==0 ? 1 : id;
    }
    private static File imageFile(int account,long id) { return new File(new File(ApplicationLoader.applicationContext.getCacheDir(),"link-previews-"+account),Long.toHexString(id)+".jpg"); }
    public static File localThumbnail(TLRPC.PhotoSize size) {
        if(size==null || size.location==null || size.location.dc_id!=LOCAL_DC) return null;
        int account=size.location.local_id;if(account<0 || account>=org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) return null;
        return imageFile(account,size.location.volume_id);
    }
    /** Returning a local location prevents native image loading from contacting Telegram or the linked site. */
    public static ImageLocation thumbnail(TLRPC.PhotoSize size,TLRPC.Photo photo) {
        if(photo==null || photo.dc_id!=LOCAL_DC || size==null || size.location==null || size.location.dc_id!=LOCAL_DC) return null;
        int account=size.location.local_id;if(account<0 || account>=org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) return null;
        File file=localThumbnail(size);
        return file==null ? null : ImageLocation.getForPath(file.getAbsolutePath());
    }
}
