// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Account-local private packs. Importing never publishes files, keys or a manifest to PEP. */
public final class MonogramPacks {
    public static final long THUMB_VOLUME = 0x4d54000000000000L;
    private static final long DOCUMENT_PREFIX = 0x4d50000000000000L;
    private static final long ID_MASK = 0x0000ffffffffffffL;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final int MAX_FILE = 20 * 1024 * 1024, MAX_PACK = 100 * 1024 * 1024, MAX_MANIFEST = 4 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Map<String, ArrayList<Pack>> CACHE = new HashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong> GENERATIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> INVALID = new HashSet<>();
    public static String owner(int account) { return scope(account) + "#" + GENERATIONS.computeIfAbsent(account, ignored -> new java.util.concurrent.atomic.AtomicLong()).get(); }
    private MonogramPacks() {}
    public static final class Item {
        public long id;
        public String name, emoji, mime, file;
        public int width, height;
    }
    public static final class Pack {
        public long id;
        public String name, source;
        public boolean emojis, installed = true;
        public final ArrayList<Item> items = new ArrayList<>();
    }
    public interface Callback { void done(String error); }
    public static final class Request {
        private final int account;
        private final String owner;
        private volatile boolean cancelled;
        private HttpsURLConnection connection;
        private Request(int account) { this.account = account; owner = owner(account); }
        public synchronized void cancel() { cancelled = true; if (connection != null) connection.disconnect(); }
        private void check() throws ImportFailure {
            if (cancelled || !owner.equals(owner(account))) throw new ImportFailure("Pack import cancelled.");
        }
        private synchronized void attach(HttpsURLConnection active) throws ImportFailure { check(); connection = active; }
        private synchronized void detach(HttpsURLConnection active) { if (connection == active) connection = null; }
    }
    private static String scope(int account) {
        String jid = MonogramController.get(account).accountJid();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((jid == null ? "" : jid).getBytes("UTF-8"));
            StringBuilder value = new StringBuilder(account + "-");
            for (byte octet : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", octet & 255));
            return value.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static File directory(String scope) { return new File(ApplicationLoader.applicationContext.getFilesDir(), "monogram-packs/" + scope); }
    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-packs-" + scope(account), Context.MODE_PRIVATE);
    }
    public static synchronized void logout(int account) {
        GENERATIONS.computeIfAbsent(account, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        preferences(account).edit().remove("token").apply();
        CACHE.remove(scope(account));
        AnimatedEmojiDrawable.clearMonogramPacks(account);
    }
    public static boolean configured(int account) { return preferences(account).contains("token"); }
    public static void saveToken(int account, String value) throws Exception {
        String token = value.trim();
        if (!token.isEmpty() && !token.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,200}")) throw new IllegalArgumentException("Enter a valid Telegram bot token.");
        SharedPreferences.Editor editor = preferences(account).edit();
        if (token.isEmpty()) editor.remove("token"); else editor.putString("token", new AccountVault(account).encrypt(token));
        if (!editor.commit()) throw new IOException("Could not save bot token.");
    }
    public static synchronized ArrayList<Pack> packs(int account) { return copy(read(scope(account))); }
    public static synchronized boolean storageError(int account) { read(scope(account)); return INVALID.contains(scope(account)); }
    private static ArrayList<Pack> copy(ArrayList<Pack> source) {
        ArrayList<Pack> result = new ArrayList<>();
        for (Pack old : source) {
            Pack pack = new Pack(); pack.id = old.id; pack.name = old.name; pack.source = old.source; pack.emojis = old.emojis; pack.installed = old.installed;
            for (Item oldItem : old.items) {
                Item item = new Item(); item.id = oldItem.id; item.name = oldItem.name; item.emoji = oldItem.emoji; item.mime = oldItem.mime;
                item.file = oldItem.file; item.width = oldItem.width; item.height = oldItem.height; pack.items.add(item);
            }
            result.add(pack);
        }
        return result;
    }
    private static ArrayList<Pack> read(String scope) {
        if (CACHE.containsKey(scope)) return CACHE.get(scope);
        ArrayList<Pack> packs = new ArrayList<>();
        AtomicFile manifest = new AtomicFile(new File(directory(scope), "packs.json"));
        try (InputStream input = manifest.openRead()) {
            JSONArray array = new JSONObject(new String(bytes(input, MAX_MANIFEST), "UTF-8")).getJSONArray("packs");
            if (array.length() > 100) throw new IOException();
            Set<Long> used = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i); Pack pack = new Pack();
                pack.id = object.getLong("id"); if (!localId(pack.id) || !used.add(pack.id)) throw new IOException(); pack.name = name(object.getString("name")); pack.source = object.optString("source");
                pack.emojis = object.getBoolean("emojis"); pack.installed = object.optBoolean("installed", true);
                JSONArray items = object.getJSONArray("items"); if (items.length() > 200) throw new IOException();
                for (int j = 0; j < items.length(); j++) {
                    JSONObject entry = items.getJSONObject(j); Item item = new Item();
                    item.id = entry.getLong("id"); item.name = name(entry.getString("name")); item.emoji = tag(entry.optString("emoji", "🙂"));
                    item.mime = entry.getString("mime"); item.file = entry.getString("file");
                    item.width = dimension(entry.optInt("width", 512)); item.height = dimension(entry.optInt("height", 512));
                    if (!localId(item.id) || !used.add(item.id) || !MonogramStickers.supported(item.mime) || !item.file.matches("[A-Za-z0-9_-]+\\.(webp|png|tgs|webm)")) throw new IOException();
                    pack.items.add(item);
                }
                if (!localId(pack.id) || pack.items.isEmpty()) throw new IOException();
                packs.add(pack);
            }
        } catch (FileNotFoundException absent) { }
        catch (Exception invalid) { INVALID.add(scope); packs.clear(); }

        CACHE.put(scope, packs); return packs;
    }
    private static synchronized void write(String scope, ArrayList<Pack> packs) throws Exception {
        if (INVALID.contains(scope)) throw new IOException("Saved pack data could not be read. It has been preserved; restart after restoring a valid backup.");
        Set<Long> ids = new HashSet<>();
        for (Pack pack : packs) {
            if (!localId(pack.id) || !ids.add(pack.id)) throw new IOException("Duplicate pack ID.");
            for (Item item : pack.items) if (!localId(item.id) || !ids.add(item.id)) throw new IOException("Duplicate item ID.");
        }
        JSONArray array = new JSONArray();
        for (Pack pack : packs) {
            JSONObject object = new JSONObject().put("id", pack.id).put("name", pack.name).put("source", pack.source)
                    .put("emojis", pack.emojis).put("installed", pack.installed); JSONArray items = new JSONArray();
            for (Item item : pack.items) items.put(new JSONObject().put("id", item.id).put("name", item.name).put("emoji", item.emoji)
                    .put("mime", item.mime).put("file", item.file).put("width", item.width).put("height", item.height));
            array.put(object.put("items", items));
        }
        byte[] data = new JSONObject().put("packs", array).toString().getBytes("UTF-8");
        if (data.length > MAX_MANIFEST || packs.size() > 100) throw new IOException("Too many packs.");
        File dir = directory(scope); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException();
        AtomicFile manifest = new AtomicFile(new File(dir, "packs.json")); FileOutputStream output = null;
        try { output = manifest.startWrite(); output.write(data); manifest.finishWrite(output); }
        catch (Exception failure) { if (output != null) manifest.failWrite(output); throw failure; }
        CACHE.put(scope, packs);
    }
    public static boolean isLocalId(long id) { return localId(id); }
    static boolean localId(long id) { return (id & ~ID_MASK) == DOCUMENT_PREFIX && (id & ID_MASK) != 0; }
    private static synchronized long id(String scope, Set<Long> pending) {
        Set<Long> used = new HashSet<>(pending);
        for (Pack pack : read(scope)) { used.add(pack.id); for (Item item : pack.items) used.add(item.id); }
        long id; do { id = DOCUMENT_PREFIX | (RANDOM.nextLong() & ID_MASK); } while (!localId(id) || used.contains(id));
        pending.add(id); return id;
    }
    public static boolean isThumb(long volume) { return (volume & ~ID_MASK) == THUMB_VOLUME; }
    private static long thumbVolume(String scope, long id) {
        long account = Long.parseLong(scope.substring(scope.indexOf('-') + 1, scope.indexOf('-') + 9), 16);
        return THUMB_VOLUME | account << 16 | (id >>> 32 & 0xffffL);
    }
    static String name(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Names must contain 1–100 characters.");
        return name;
    }
    private static String tag(String value) {
        if (value == null || value.isEmpty()) return "🙂";
        if (value.length() > 64 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Emoji tags are too long.");
        return value;
    }
    private static int dimension(int value) { return Math.min(512, Math.max(1, value)); }
    public static synchronized File thumb(int account, long volume, int local) {
        long id = DOCUMENT_PREFIX | (volume & 0xffffL) << 32 | (local & 0xffffffffL);
        String scope = scope(account); if (volume != thumbVolume(scope, id)) return null;
        for (Pack pack : read(scope)) for (Item item : pack.items) if (item.id == id) return new File(directory(scope), item.file);
        return null;
    }
    public static synchronized TLRPC.Document document(int account, long id) {
        for (Pack pack : read(scope(account))) for (Item item : pack.items) if (item.id == id) return document(scope(account), pack, item);
        return null;
    }
    private static TLRPC.Document document(String scope, Pack pack, Item item) {
        File file = new File(directory(scope), item.file);
        TLRPC.TL_document doc = new TLRPC.TL_document(); doc.id = item.id; doc.dc_id = Integer.MIN_VALUE;
        doc.file_reference = new byte[0]; doc.mime_type = item.mime; doc.size = file.length(); doc.localPath = file.getAbsolutePath();
        TLRPC.TL_inputStickerSetID set = new TLRPC.TL_inputStickerSetID(); set.id = pack.id;
        if (pack.emojis) {
            TLRPC.TL_documentAttributeCustomEmoji attr = new TLRPC.TL_documentAttributeCustomEmoji(); attr.free = true; attr.alt = item.emoji; attr.stickerset = set; doc.attributes.add(attr);
        } else {
            TLRPC.TL_documentAttributeSticker attr = new TLRPC.TL_documentAttributeSticker(); attr.alt = item.emoji; attr.stickerset = set; doc.attributes.add(attr);
        }
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename(); filename.file_name = item.file; doc.attributes.add(filename);
        TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize(); size.w = item.width; size.h = item.height; doc.attributes.add(size);
        TLRPC.TL_photoSize thumb = new TLRPC.TL_photoSize(); thumb.type = "s"; thumb.w = item.width; thumb.h = item.height; thumb.size = (int) doc.size;
        thumb.location = new TLRPC.TL_fileLocationToBeDeprecated(); thumb.location.volume_id = thumbVolume(scope, item.id); thumb.location.local_id = (int) item.id; thumb.location.dc_id = Integer.MIN_VALUE;
        doc.thumbs.add(thumb); doc.flags |= 1; return doc;
    }
    public static synchronized ArrayList<TLRPC.TL_messages_stickerSet> sets(int account, boolean emojis) {
        ArrayList<TLRPC.TL_messages_stickerSet> result = new ArrayList<>(); String scope = scope(account);
        for (Pack pack : read(scope)) if (pack.installed && pack.emojis == emojis) result.add(set(scope, pack));
        return result;
    }
    public static synchronized TLRPC.TL_messages_stickerSet find(int account, TLRPC.InputStickerSet input) {
        if (input == null) return null;
        for (Pack pack : read(scope(account))) if (input.id == pack.id || input.short_name != null && input.short_name.equals("monogram_" + pack.id)) return set(scope(account), pack);
        return null;
    }
    private static TLRPC.TL_messages_stickerSet set(String scope, Pack pack) {
        TLRPC.TL_messages_stickerSet set = new TLRPC.TL_messages_stickerSet(); set.set = new TLRPC.TL_stickerSet();
        set.set.id = pack.id; set.set.title = pack.name; set.set.short_name = "monogram_" + pack.id; set.set.emojis = pack.emojis;
        set.set.flags |= 1; set.set.installed_date = pack.installed ? 1 : 0; set.set.count = pack.items.size();
        LinkedHashMap<String, TLRPC.TL_stickerPack> tags = new LinkedHashMap<>();
        for (Item item : pack.items) {
            set.documents.add(document(scope, pack, item));
            TLRPC.TL_stickerPack tag = tags.get(item.emoji);
            if (tag == null) { tag = new TLRPC.TL_stickerPack(); tag.emoticon = item.emoji; tags.put(item.emoji, tag); }
            tag.documents.add(item.id);
        }
        set.packs.addAll(tags.values()); return set;
    }
    public static void refresh(int account) {
        MediaDataController.getInstance(account).loadStickers(MediaDataController.TYPE_IMAGE, false, true);
        MediaDataController.getInstance(account).loadStickers(MediaDataController.TYPE_EMOJIPACKS, false, true);
        for (TLRPC.TL_messages_stickerSet set : sets(account, true)) AnimatedEmojiDrawable.getDocumentFetcher(account).putDocuments(set.documents);
    }
    public static void rename(int account, long packId, long itemId, String value, boolean emoji) throws Exception {
        synchronized (MonogramPacks.class) {
            String scope = scope(account); ArrayList<Pack> packs = copy(read(scope));
            for (Pack pack : packs) if (pack.id == packId) {
                if (itemId == 0) pack.name = name(value);
                else for (Item item : pack.items) if (item.id == itemId) { if (emoji) item.emoji = tag(value); else item.name = name(value); }
                write(scope, packs); break;
            }
        }
        refresh(account);
    }
    public static void installed(int account, long id, boolean installed) throws Exception {
        synchronized (MonogramPacks.class) { String scope = scope(account); ArrayList<Pack> packs = copy(read(scope));
            for (Pack pack : packs) if (pack.id == id) pack.installed = installed; write(scope, packs); }
        refresh(account);
    }
    public static void remove(int account, long id) throws Exception {
        synchronized (MonogramPacks.class) { String scope = scope(account); ArrayList<Pack> packs = copy(read(scope));
            // Retain files for already sent messages and their inline emoji references.
            for (Pack pack : packs) if (pack.id == id) pack.installed = false; write(scope, packs); }
        refresh(account);
    }
    static final class Link { final String name; final boolean emojis; Link(String n, boolean e) { name = n; emojis = e; } }
    static Link parseLink(String value) {
        Uri uri = Uri.parse(value.trim()); String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !("t.me".equalsIgnoreCase(host) || "telegram.me".equalsIgnoreCase(host))
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Use an https://t.me/addstickers/name or addemoji/name link.");
        java.util.List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !(path.get(0).equals("addstickers") || path.get(0).equals("addemoji")) || !path.get(1).matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid Telegram pack link.");
        return new Link(path.get(1), path.get(0).equals("addemoji"));
    }
    public static Request importTelegram(int account, String link, Callback callback) {
        final Request operation = new Request(account);
        final Link parsed;
        try { parsed = parseLink(link); } catch (Exception invalid) { callback.done(invalid.getMessage()); return operation; }
        final String expectedOwner = owner(account);
        final String expectedScope = scope(account), encryptedToken = preferences(account).getString("token", null);
        if (encryptedToken == null) { callback.done("Add your own Telegram bot token first. The import uses Telegram’s official Bot API."); return operation; }
        WORKER.execute(() -> {
            String error = null; ArrayList<File> temporary = new ArrayList<>();
            try {
                String token = new AccountVault(account).decrypt(encryptedToken);
                JSONObject result = request(token, "getStickerSet", "name", parsed.name, operation).getJSONObject("result");
                if (!parsed.name.equalsIgnoreCase(result.getString("name")) || parsed.emojis != "custom_emoji".equals(result.optString("sticker_type"))) throw new IOException();
                Set<Long> pending = new HashSet<>(); Pack pack = new Pack(); pack.id = id(expectedScope, pending); pack.name = name(result.getString("title")); pack.source = link; pack.emojis = parsed.emojis;
                JSONArray stickers = result.getJSONArray("stickers"); if (stickers.length() < 1 || stickers.length() > 200) throw new ImportFailure("Packs must contain 1–200 items.");
                File dir = directory(expectedScope); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException();
                long total = 0;
                for (int i = 0; i < stickers.length(); i++) {
                    operation.check();
                    JSONObject sticker = stickers.getJSONObject(i); String fileId = sticker.getString("file_id");
                    JSONObject remote = request(token, "getFile", "file_id", fileId, operation).getJSONObject("result");
                    String path = remote.getString("file_path");
                    if (!path.matches("[A-Za-z0-9_/-]+\\.(webp|png|tgs|webm)") || path.contains("..")) throw new IOException();
                    Item item = new Item(); item.id = id(expectedScope, pending); item.emoji = tag(sticker.optString("emoji", "🙂")); item.name = "Item " + (i + 1);
                    String ext = path.substring(path.lastIndexOf('.')); item.file = UUID.randomUUID() + ext;
                    item.mime = ext.equals(".tgs") ? "application/x-tgsticker" : ext.equals(".webm") ? "video/webm" : ext.equals(".png") ? "image/png" : "image/webp";
                    item.width = dimension(sticker.optInt("width", 512)); item.height = dimension(sticker.optInt("height", 512));
                    File target = new File(dir, item.file); temporary.add(target);
                    byte[] data = download("https://api.telegram.org/file/bot" + token + "/" + path, MAX_FILE, operation);
                    validateMedia(data, item.mime);
                    total += data.length; if (total > MAX_PACK) throw new ImportFailure("A pack may use up to 100 MB on this phone.");
                    try (FileOutputStream output = new FileOutputStream(target)) { output.write(data); output.getFD().sync(); }
                    pack.items.add(item);
                }
                synchronized (MonogramPacks.class) {
                    if (!expectedOwner.equals(owner(account))) throw new ImportFailure("The account changed. Import again on the intended account.");
                    ArrayList<Pack> packs = new ArrayList<>(read(expectedScope));
                    for (Pack old : packs) if (old.source.equals(link) && old.installed) throw new ImportFailure("This pack is already installed.");
                    synchronized (operation) { operation.check(); packs.add(0, pack); write(expectedScope, packs); temporary.clear(); }
                }
            } catch (ImportFailure failure) { error = failure.getMessage(); }
            catch (Exception unavailable) { error = "Could not import the pack. Check the link, bot token and connection. Telegram permits bot file downloads up to 20 MB per item."; }
            finally { for (File file : temporary) file.delete(); }
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> { if (!expectedOwner.equals(owner(account)) || operation.cancelled) return; if (failure == null) refresh(account); callback.done(failure); });
        });
        return operation;
    }
    /** Copy selected files into account-private storage; source paths and names are never uploaded. */
    public static Request create(int account, String title, boolean emojis, ArrayList<Uri> sources, Callback callback) {
        final Request operation = new Request(account);
        final String expectedOwner = owner(account);
        final String expectedScope = scope(account);
        final String packName;
        try { packName = name(title); if (sources.isEmpty() || sources.size() > 200) throw new IllegalArgumentException("Select 1–200 files."); }
        catch (Exception invalid) { callback.done(invalid.getMessage()); return operation; }
        final ArrayList<Uri> selected = new ArrayList<>(sources);
        WORKER.execute(() -> {
            ArrayList<File> temporary = new ArrayList<>(); String error = null;
            try {
                File dir = directory(expectedScope); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException();
                Set<Long> pending = new HashSet<>(); Pack pack = new Pack(); pack.id = id(expectedScope, pending); pack.name = packName; pack.source = "local:" + UUID.randomUUID(); pack.emojis = emojis;
                long total = 0;
                for (Uri source : selected) {
                    operation.check();
                    if (!"content".equals(source.getScheme())) throw new ImportFailure("Choose files using the file picker.");
                    byte[] data;
                    try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(source)) {
                        if (input == null) throw new IOException(); data = bytes(input, MAX_FILE, operation);
                    }
                    String ext; Item item = new Item(); item.id = id(expectedScope, pending); item.name = "Item " + (pack.items.size() + 1); item.emoji = "🙂";
                    if (data.length > 4 && (data[0] & 255) == 31 && (data[1] & 255) == 139) { ext = ".tgs"; item.mime = "application/x-tgsticker"; item.width = item.height = 512; }
                    else if (data.length > 4 && (data[0] & 255) == 26 && (data[1] & 255) == 69 && (data[2] & 255) == 223 && (data[3] & 255) == 163) {
                        ext = ".webm"; item.mime = "video/webm"; item.width = item.height = 512;
                    } else {
                        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 12000 || bounds.outHeight > 12000 || (long) bounds.outWidth * bounds.outHeight > 50000000) throw new ImportFailure("Use a supported image, TGS animation or WebM video.");
                        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 1;
                        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2;
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, options);
                        if (bitmap == null) throw new IOException();
                        android.graphics.Bitmap scaled = null;
                        try {
                            double scale = Math.min(1.0, 512.0 / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                            item.width = Math.max(1, (int) Math.round(bitmap.getWidth() * scale)); item.height = Math.max(1, (int) Math.round(bitmap.getHeight() * scale));
                            scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, item.width, item.height, true);
                            ByteArrayOutputStream image = new ByteArrayOutputStream();
                            if (!scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, image)) throw new IOException();
                            data = image.toByteArray();
                        } finally { if (scaled != null && scaled != bitmap) scaled.recycle(); bitmap.recycle(); }
                        ext = ".png"; item.mime = "image/png";
                    }
                    validateMedia(data, item.mime);
                    total += data.length; if (total > MAX_PACK) throw new ImportFailure("A pack may use up to 100 MB on this phone.");
                    item.file = UUID.randomUUID() + ext; File target = new File(dir, item.file); temporary.add(target);
                    try (FileOutputStream output = new FileOutputStream(target)) { output.write(data); output.getFD().sync(); }
                    pack.items.add(item);
                }
                synchronized (MonogramPacks.class) {
                    if (!expectedOwner.equals(owner(account))) throw new ImportFailure("The account changed. Create the pack again on the intended account.");
                    ArrayList<Pack> packs = new ArrayList<>(read(expectedScope));
                    synchronized (operation) { operation.check(); packs.add(0, pack); write(expectedScope, packs); temporary.clear(); }
                }
            } catch (ImportFailure failure) { error = failure.getMessage(); }
            catch (Exception unavailable) { error = "Could not create the pack. Check the selected files and available storage."; }
            finally { for (File file : temporary) file.delete(); }
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> { if (!expectedOwner.equals(owner(account)) || operation.cancelled) return; if (failure == null) refresh(account); callback.done(failure); });
        });
        return operation;
    }
    static void validateMedia(byte[] data, String mime) throws Exception {
        if (data.length < 8 || data.length > MAX_FILE) throw new IOException();
        if ("application/x-tgsticker".equals(mime)) {
            try (java.util.zip.GZIPInputStream input = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data))) {
                JSONObject animation = new JSONObject(new String(bytes(input, 2 * 1024 * 1024), "UTF-8"));
                if (animation.optInt("w") != 512 || animation.optInt("h") != 512 || animation.optDouble("fr") <= 0 || animation.optDouble("fr") > 60
                        || animation.optDouble("op") - animation.optDouble("ip") > animation.optDouble("fr") * 3 || !animation.has("layers")) throw new IOException();
            }
        } else if ("image/webp".equals(mime)) {
            if (data.length < 12 || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F' || data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') throw new IOException();
        } else if ("image/png".equals(mime)) {
            if ((data[0] & 255) != 137 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G' || data[4] != 13 || data[5] != 10 || data[6] != 26 || data[7] != 10) throw new IOException();
        } else if ("video/webm".equals(mime)) {
            if ((data[0] & 255) != 26 || (data[1] & 255) != 69 || (data[2] & 255) != 223 || (data[3] & 255) != 163) throw new IOException();
        } else throw new IOException();
    }
    private static final class ImportFailure extends IOException { ImportFailure(String text) { super(text); } }
    private static JSONObject request(String token, String method, String key, String value, Request operation) throws Exception {
        String url = "https://api.telegram.org/bot" + token + "/" + method + "?" + key + "=" + Uri.encode(value);
        JSONObject object = new JSONObject(new String(download(url, MAX_MANIFEST, operation), "UTF-8"));
        if (!object.optBoolean("ok")) throw new IOException(); return object;
    }
    private static byte[] download(String value, int limit, Request operation) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
        try { operation.attach(connection); if (connection.getResponseCode() != 200) throw new IOException();
            if (connection.getContentLength() > limit) throw new IOException();
            try (InputStream input = connection.getInputStream()) { return bytes(input, limit, operation); }
        } finally { operation.detach(connection); connection.disconnect(); }
    }
    private static byte[] bytes(InputStream input, int limit) throws Exception { return bytes(input, limit, null); }
    private static byte[] bytes(InputStream input, int limit, Request operation) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) { if (operation != null) operation.check(); if (body.size() + count > limit) throw new IOException(); body.write(buffer, 0, count); }
        return body.toByteArray();
    }
}
