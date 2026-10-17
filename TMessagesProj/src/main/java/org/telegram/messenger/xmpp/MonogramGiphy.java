// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Direct, opt-in GIPHY search; media uses the native HTTP cache and XMPP upload path. */
public final class MonogramGiphy {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-giphy-" + account, Context.MODE_PRIVATE);
    }
    public static boolean configured(int account) { return preferences(account).contains("key"); }
    public static void saveKey(int account, String value) throws Exception {
        String key = value.trim();
        if (!key.isEmpty() && !key.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException("Enter a valid GIPHY API key.");
        SharedPreferences.Editor editor = preferences(account).edit();
        if (key.isEmpty()) editor.remove("key");
        else editor.putString("key", new AccountVault(account).encrypt(key));
        if (!editor.commit()) throw new java.io.IOException("Could not save the API key.");
    }
    private static final class SearchFailure extends Exception {
        SearchFailure(String message) { super(message); }
    }
    public interface Callback { void complete(Page page, String error); }
    public static final class Request {
        private volatile boolean cancelled;
        private volatile HttpsURLConnection connection;
        public void cancel() { cancelled = true; HttpsURLConnection active = connection; if (active != null) active.disconnect(); }
    }
    public static final class Page {
        public final ArrayList<TLRPC.BotInlineResult> results = new ArrayList<>();
        public String nextOffset = "";
    }
    public static Request search(int account, String query, String offset, Callback callback) {
        Request request = new Request();
        String cacheKey = "monogram-giphy:" + org.telegram.messenger.Utilities.MD5(
                preferences(account).getString("key", "") + "\n" + (query == null ? "" : query) + "\n" + (offset == null ? "" : offset));
        org.telegram.messenger.MessagesStorage storage = org.telegram.messenger.MessagesStorage.getInstance(account);
        storage.getBotCache(cacheKey, (response, ignored) -> {
            if (request.cancelled) return;
            if (response instanceof TLRPC.TL_messages_botResults) {
                TLRPC.TL_messages_botResults cached = (TLRPC.TL_messages_botResults) response;
                Page page = new Page(); page.results.addAll(cached.results);
                page.nextOffset = cached.next_offset == null ? "" : cached.next_offset;
                AndroidUtilities.runOnUIThread(() -> { if (!request.cancelled) callback.complete(page, null); });
            } else fetch(request, account, query, offset, (page, error) -> {
                if (page != null && !request.cancelled) storage.saveBotCache(cacheKey, cachedPage(page));
                callback.complete(page, error);
            });
        });
        return request;
    }
    static TLRPC.TL_messages_botResults cachedPage(Page page) {
        TLRPC.TL_messages_botResults cached = new TLRPC.TL_messages_botResults();
        cached.gallery = true; cached.cache_time = 300;
        cached.results.addAll(page.results);
        if (!page.nextOffset.isEmpty()) { cached.flags |= 2; cached.next_offset = page.nextOffset; }
        return cached;
    }
    private static void fetch(Request request, int account, String query, String offset, Callback callback) {
        WORKER.execute(() -> {
            Page page = null;
            String error = null;
            try {
                String saved = preferences(account).getString("key", null);
                if (saved == null) throw new SearchFailure("Add a GIPHY API key in Settings → GIF search.");
                String key = new AccountVault(account).decrypt(saved);
                String text = query == null ? "" : query;
                if (text.codePointCount(0, text.length()) > 50) throw new SearchFailure("GIF searches can contain up to 50 characters.");
                int start = offset == null || offset.isEmpty() ? 0 : Integer.parseInt(offset);
                if (start < 0 || start > (text.isEmpty() ? 499 : 4999)) throw new SearchFailure("No more GIFs available.");
                Uri.Builder url = Uri.parse("https://api.giphy.com/v1/gifs/" + (text.isEmpty() ? "trending" : "search")).buildUpon()
                        .appendQueryParameter("api_key", key).appendQueryParameter("limit", "25")
                        .appendQueryParameter("offset", Integer.toString(start)).appendQueryParameter("rating", "r");
                if (!text.isEmpty()) url.appendQueryParameter("q", text);
                if (request.cancelled) return;
                HttpsURLConnection connection = (HttpsURLConnection) new URL(url.build().toString()).openConnection();
                request.connection = connection;
                connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                if (request.cancelled) return;
                int status = connection.getResponseCode();
                if (status != 200) {
                    error = status == 401 || status == 403 ? "GIPHY rejected the API key. Replace it in Settings → GIF search."
                            : status == 429 ? "GIPHY's request limit was reached. Try again later." : "GIPHY search is temporarily unavailable.";
                } else {
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (request.cancelled) return;
                            if (body.size() + count > 4 * 1024 * 1024) throw new java.io.IOException();
                            body.write(buffer, 0, count);
                        }
                        page = parse(new JSONObject(body.toString("UTF-8")), text.isEmpty());
                    }
                }
            } catch (SearchFailure invalid) {
                error = invalid.getMessage();
            } catch (Exception unavailable) {
                // Never log exceptions containing a request URL or API key.
                error = "Could not load GIFs. Check your connection and saved GIPHY API key.";
            } finally {
                if (request.connection != null) request.connection.disconnect();
            }
            Page result = page; String failure = error;
            AndroidUtilities.runOnUIThread(() -> { if (!request.cancelled) callback.complete(result, failure); });
        });
    }
    static Page parse(JSONObject response, boolean trending) throws Exception {
        Page page = new Page();
        JSONArray data = response.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject gif = data.getJSONObject(i);
            String id = gif.optString("id");
            JSONObject images = gif.optJSONObject("images");
            if (id.isEmpty() || id.length() > 128 || images == null) continue;
            TLRPC.WebDocument content = document(images.optJSONObject("original"));
            if (content == null) continue;
            TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
            result.id = "giphy-" + id; result.type = "gif"; result.title = gif.optString("title");
            result.flags = 2 | 16 | 32;
            result.content = content;
            result.thumb = document(images.optJSONObject("fixed_width"));
            if (result.thumb == null) result.thumb = content;
            result.send_message = new TLRPC.TL_botInlineMessageMediaAuto();
            result.send_message.message = "";
            page.results.add(result);
        }
        JSONObject pagination = response.optJSONObject("pagination");
        if (pagination != null) {
            long next = pagination.optLong("offset", 0) + pagination.optLong("count", data.length());
            if (data.length() > 0 && next > pagination.optLong("offset", 0) && next < pagination.optLong("total_count", next)
                    && next <= (trending ? 499 : 4999)) page.nextOffset = Long.toString(next);
        }
        return page;
    }
    private static TLRPC.WebDocument document(JSONObject image) {
        if (image == null) return null;
        String url = image.optString("url");
        if (!validAssetUrl(url)) return null;
        int width = image.optInt("width"), height = image.optInt("height");
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null;
        TLRPC.TL_webDocumentNoProxy document = new TLRPC.TL_webDocumentNoProxy();
        document.url = url; document.mime_type = "image/gif";
        document.size = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, image.optLong("size")));
        TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
        size.w = width; size.h = height; document.attributes.add(size);
        return document;
    }
    private static boolean validAssetUrl(String url) {
        if (url == null || url.length() > 4096) return false;
        Uri uri = Uri.parse(url); String host = uri.getHost();
        return uri.getFragment() == null && "https".equals(uri.getScheme()) && host != null
                && (host.equals("giphy.com") || host.endsWith(".giphy.com")) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    public static java.io.File sourceFile(String url) {
        if (!validAssetUrl(url)) throw new IllegalArgumentException("Invalid GIF source");
        return new java.io.File(org.telegram.messenger.FileLoader.getDirectory(org.telegram.messenger.FileLoader.MEDIA_DIR_CACHE),
                org.telegram.messenger.Utilities.MD5(url) + "." + org.telegram.messenger.ImageLoader.getHttpUrlExtension(url, "gif"));
    }
    /** The outgoing message already exists in the native outbox while this source is fetched. */
    public static void download(int account, String url, java.util.function.Consumer<Boolean> callback) {
        java.io.File file = sourceFile(url);
        if (file.isFile() && file.length() > 0) { callback.accept(true); return; }
        org.telegram.messenger.NotificationCenter center = org.telegram.messenger.NotificationCenter.getInstance(account);
        class Download implements org.telegram.messenger.NotificationCenter.NotificationCenterDelegate, Runnable {
            boolean done;
            void finish(boolean success) {
                if (done) return;
                done = true;
                center.removeObserver(this, org.telegram.messenger.NotificationCenter.httpFileDidLoad);
                center.removeObserver(this, org.telegram.messenger.NotificationCenter.httpFileDidFailedLoad);
                AndroidUtilities.cancelRunOnUIThread(this);
                callback.accept(success && file.isFile() && file.length() > 0);
            }
            @Override public void didReceivedNotification(int id, int account, Object... args) {
                if (url.equals(args[0])) finish(id == org.telegram.messenger.NotificationCenter.httpFileDidLoad);
            }
            @Override public void run() { finish(false); }
        }
        Download download = new Download();
        center.addObserver(download, org.telegram.messenger.NotificationCenter.httpFileDidLoad);
        center.addObserver(download, org.telegram.messenger.NotificationCenter.httpFileDidFailedLoad);
        AndroidUtilities.runOnUIThread(download, 60000);
        org.telegram.messenger.ImageLoader.getInstance().loadHttpFile(url, "gif", account);
    }
    public static boolean isResult(TLRPC.BotInlineResult result) {
        return result != null && result.id != null && result.id.startsWith("giphy-") && result.content instanceof TLRPC.TL_webDocumentNoProxy;
    }
}
