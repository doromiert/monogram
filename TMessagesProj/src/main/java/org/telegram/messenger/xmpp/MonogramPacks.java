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
    private static final java.util.concurrent.ExecutorService DOWNLOADS=java.util.concurrent.Executors.newFixedThreadPool(4,r -> {
        Thread thread=new Thread(r,"monogram-sticker-download");thread.setDaemon(true);return thread;
    });
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
        public boolean privatePack = true, connected = false;
        public final ArrayList<Item> items = new ArrayList<>();
    }
    public interface Callback { void done(String error); }
    public interface ArchiveCallback { void done(File archive, String error); }
    public interface ImportObserver { void changed(ImportProgress progress); }
    public static final class ImportProgress {
        public final String stage, packName, error;
        public final int completed, total;
        public final long currentBytes, totalBytes;
        public final boolean active, cancelled, complete;
        private ImportProgress(String stage, String packName, String error, int completed, int total,
                long currentBytes, long totalBytes, boolean active, boolean cancelled) {
            this.stage = stage; this.packName = packName; this.error = error; this.completed = completed; this.total = total;
            this.currentBytes = currentBytes; this.totalBytes = totalBytes; this.active = active; this.cancelled = cancelled;
            complete = !active && !cancelled && error == null;
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<String, ImportJob> IMPORTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<ImportObserver>>> IMPORT_OBSERVERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> IMPORT_NOTIFICATIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final class ImportJob {
        final int account;
        final String owner;
        final Request request;
        volatile ImportProgress progress;
        volatile int packIndex=1,packCount=1;
        ImportJob(int account) {
            this.account = account; request = new Request(account); owner = request.owner; request.importJob = this;
            progress = new ImportProgress("Queued", "", null, 0, 0, 0, -1, true, false);
        }
        synchronized void update(String stage, String packName, int completed, int total, long bytes, long length) {
            if (!progress.active || request.cancelled) return;
            if(packCount>1) stage="Pack "+packIndex+" / "+packCount+" · "+stage;
            progress = new ImportProgress(stage, packName, null, completed, total, bytes, length, true, false);
            notifyImport(this);
        }
        synchronized void bytes(long bytes, long length) {
            ImportProgress old = progress;
            if(!old.active || request.cancelled) return;
            progress=new ImportProgress(old.stage,old.packName,null,old.completed,old.total,bytes,length,true,false);
            notifyImport(this);
        }
        synchronized void finished(String error) {
            ImportProgress old = progress;
            boolean cancelled = request.cancelled && !request.committed;
            progress = new ImportProgress(cancelled ? "Cancelled" : error == null ? "Complete" : "Import failed", old.packName,
                    cancelled ? null : error, error == null && !cancelled ? old.total : old.completed, old.total,
                    old.currentBytes, old.totalBytes, false, cancelled);
            notifyImport(this);
        }
    }
    public static ImportProgress importProgress(int account) { ImportJob job = IMPORTS.get(owner(account)); return job == null ? null : job.progress; }
    public static void addImportObserver(int account, ImportObserver observer) {
        if (observer == null) return;
        String key = owner(account);
        java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<ImportObserver>> observers = IMPORT_OBSERVERS.computeIfAbsent(key, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>());
        for (java.lang.ref.WeakReference<ImportObserver> entry : observers) if (entry.get() == observer) return;
        observers.add(new java.lang.ref.WeakReference<>(observer));
        ImportJob job = IMPORTS.get(key); if (job != null) notifyImport(job);
    }
    public static void removeImportObserver(int account, ImportObserver observer) {
        // Removal also works after logout/account replacement without retaining the previous owner.
        for (java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<ImportObserver>> observers : IMPORT_OBSERVERS.values())
            for (java.lang.ref.WeakReference<ImportObserver> entry : observers) if (entry.get() == null || entry.get() == observer) observers.remove(entry);
    }
    public static void cancelImport(int account) {
        ImportJob job = IMPORTS.get(owner(account)); if (job == null || !job.progress.active) return;
        job.request.cancel();
        synchronized (job) {
            if (job.request.committed || !job.progress.active) return;
            ImportProgress old = job.progress;
            job.progress = new ImportProgress("Cancelling…", old.packName, null, old.completed, old.total, old.currentBytes, old.totalBytes, true, false);
        }
        notifyImport(job);
    }
    private static void notifyImport(ImportJob job) {
        if (!IMPORT_OBSERVERS.containsKey(job.owner) || !IMPORT_NOTIFICATIONS.add(job.owner)) return;
        AndroidUtilities.runOnUIThread(() -> {
            IMPORT_NOTIFICATIONS.remove(job.owner);
            if (!job.owner.equals(owner(job.account))) return;
            ImportJob current = IMPORTS.get(job.owner); if (current == null) return;
            java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<ImportObserver>> observers = IMPORT_OBSERVERS.get(job.owner);
            if (observers == null) return;
            for (java.lang.ref.WeakReference<ImportObserver> entry : observers) {
                ImportObserver observer = entry.get();
                if (observer == null) observers.remove(entry);
                else try { observer.changed(current.progress); } catch (RuntimeException ignored) { }
            }
        }, 100);
    }
    public static final class Request {
        private final int account;
        private final String owner;
        private volatile boolean cancelled, committed;
        private final Set<HttpsURLConnection> connections=new HashSet<>();
        private Request parent;
        private java.util.function.BiConsumer<Long,Long> byteProgress;
        private ImportJob importJob;
        private boolean mediaDownload;
        private long downloadLength = -1;
        private Request(int account) { this.account = account; owner = owner(account); }
        public synchronized void cancel() {
            if (importJob != null && committed && importJob.packIndex==importJob.packCount) return;
            cancelled = true; for(HttpsURLConnection connection:connections) connection.disconnect();
        }
        private void check() throws ImportFailure {
            if(parent!=null) parent.check();
            if (cancelled || !owner.equals(owner(account))) throw new ImportFailure("Pack import cancelled.");
        }
        private synchronized void attach(HttpsURLConnection active) throws ImportFailure { check(); if(parent!=null) parent.attach(active); connections.add(active); }
        private synchronized void detach(HttpsURLConnection active) { connections.remove(active); if(parent!=null) parent.detach(active); }
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
        for (ImportJob job : IMPORTS.values()) if (job.account == account) {
            job.request.cancel(); IMPORTS.remove(job.owner, job); IMPORT_OBSERVERS.remove(job.owner); IMPORT_NOTIFICATIONS.remove(job.owner);
        }
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
            Pack pack = new Pack(); pack.id = old.id; pack.name = old.name; pack.source = old.source; pack.emojis = old.emojis; pack.installed = old.installed; pack.privatePack = old.privatePack; pack.connected = old.connected;
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
            Set<Long> used = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i); Pack pack = new Pack();
                pack.id = object.getLong("id"); if (!localId(pack.id) || !used.add(pack.id)) throw new IOException(); pack.name = name(object.getString("name")); pack.source = object.optString("source");
                pack.emojis = object.getBoolean("emojis"); pack.installed = object.optBoolean("installed", true);
                pack.privatePack = object.optBoolean("privatePack", true); pack.connected = object.optBoolean("connected", false);
                if (pack.connected) parseLink(pack.source);
                JSONArray items = object.getJSONArray("items");
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
                    .put("emojis", pack.emojis).put("installed", pack.installed).put("privatePack", pack.privatePack).put("connected", pack.connected); JSONArray items = new JSONArray();
            for (Item item : pack.items) items.put(new JSONObject().put("id", item.id).put("name", item.name).put("emoji", item.emoji)
                    .put("mime", item.mime).put("file", item.file).put("width", item.width).put("height", item.height));
            array.put(object.put("items", items));
        }
        byte[] data = new JSONObject().put("packs", array).toString().getBytes("UTF-8");
        if (data.length > MAX_MANIFEST) throw new IOException("Pack metadata exceeds the safe byte-size limit.");
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
    public static String name(String value) {
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
    public static synchronized void reorder(int account, boolean emojis, List<Long> order) throws Exception {
        String scope = scope(account);
        ArrayList<Pack> packs = copy(read(scope));
        Map<Long, Integer> positions = new HashMap<>();
        for (Long id : order) if (id != null && !positions.containsKey(id)) positions.put(id, positions.size());
        ArrayList<Pack> ordered = new ArrayList<>();
        for (Pack pack : packs) if (pack.installed && pack.emojis == emojis) ordered.add(pack);
        ordered.sort(Comparator.comparingInt(pack -> positions.getOrDefault(pack.id, Integer.MAX_VALUE)));
        int index = 0;
        for (int i = 0; i < packs.size(); i++) {
            Pack pack = packs.get(i);
            if (pack.installed && pack.emojis == emojis) packs.set(i, ordered.get(index++));
        }
        write(scope, packs);
    }
    public static synchronized TLRPC.TL_messages_stickerSet find(int account, TLRPC.InputStickerSet input) {
        if (input == null) return null;
        for (Pack pack : read(scope(account))) if (input.id == pack.id || input.short_name != null && input.short_name.equals("monogram_" + pack.id)) return set(scope(account), pack);
        return null;
    }
    private static TLRPC.TL_messages_stickerSet set(String scope, Pack pack) {
        TLRPC.TL_messages_stickerSet set = new TLRPC.TL_messages_stickerSet(); set.set = new TLRPC.TL_stickerSet();
        set.set.id = pack.id; set.set.creator = true; set.set.title = pack.name; set.set.short_name = "monogram_" + pack.id; set.set.emojis = pack.emojis;
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
    public static synchronized Pack pack(int account, long id) {
        for (Pack pack : packs(account)) if (pack.id == id) return pack;
        return null;
    }
    public static boolean telegramSource(Pack pack) {
        if (pack == null || pack.source == null) return false;
        try { parseLink(pack.source); return true; } catch (IllegalArgumentException ignored) { return false; }
    }
    private static void requireEditable(Pack pack) {
        if (pack.connected || telegramSource(pack)) throw new IllegalStateException("Detach this pack from Telegram before editing it.");
    }
    public static void detach(int account, long id) throws Exception {
        synchronized (MonogramPacks.class) {
            String scope=scope(account); ArrayList<Pack> packs=copy(read(scope)); boolean found=false;
            for (Pack pack:packs) if(pack.id==id) {
                pack.connected=false; pack.source="local:"+UUID.randomUUID(); found=true; break;
            }
            if(!found) throw new IllegalArgumentException("Pack not found.");
            write(scope,packs);
        }
        refresh(account);
    }
    public static void moveItem(int account,long packId,long itemId,int position) throws Exception {
        synchronized (MonogramPacks.class) {
            String scope=scope(account); ArrayList<Pack> packs=copy(read(scope)); boolean found=false;
            for(Pack pack:packs) if(pack.id==packId) {
                requireEditable(pack);
                if(position<0 || position>=pack.items.size()) throw new IllegalArgumentException("Invalid sticker position.");
                for(int i=0;i<pack.items.size();i++) if(pack.items.get(i).id==itemId) {
                    pack.items.add(position,pack.items.remove(i)); found=true; break;
                }
            }
            if(!found) throw new IllegalArgumentException("Sticker not found.");
            write(scope,packs);
        }
        refresh(account);
    }
    public static void removeItem(int account,long packId,long itemId) throws Exception {
        synchronized (MonogramPacks.class) {
            String scope=scope(account); ArrayList<Pack> packs=copy(read(scope)); boolean found=false;
            for(Pack pack:packs) if(pack.id==packId) {
                requireEditable(pack);
                for(int i=0;i<pack.items.size();i++) if(pack.items.get(i).id==itemId) {
                    // Keep the final document resolvable for old messages, as with Remove pack.
                    if(pack.items.size()==1) pack.installed=false; else pack.items.remove(i);
                    found=true;break;
                }
            }
            if(!found) throw new IllegalArgumentException("Sticker not found.");
            write(scope,packs);
        }
        refresh(account);
    }
    public static void rename(int account, long packId, long itemId, String value, boolean emoji) throws Exception {
        synchronized (MonogramPacks.class) {
            String scope = scope(account); ArrayList<Pack> packs = copy(read(scope)); boolean found=false;
            for (Pack pack : packs) if (pack.id == packId) {
                requireEditable(pack);
                if (itemId == 0) { pack.name = name(value);found=true; }
                else for (Item item : pack.items) if (item.id == itemId) { if (emoji) item.emoji = tag(value); else item.name = name(value);found=true;break; }
                break;
            }
            if(!found) throw new IllegalArgumentException("Pack or sticker not found.");
            write(scope,packs);
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
    static ArrayList<String> parseLinks(String text) {
        java.util.LinkedHashMap<String,String> links=new java.util.LinkedHashMap<>();
        for(String value:text.trim().split("[\\s,]+")) {
            Link parsed=parseLink(value);
            String canonical="https://t.me/"+(parsed.emojis ? "addemoji/" : "addstickers/")+parsed.name;
            links.putIfAbsent(canonical.toLowerCase(java.util.Locale.ROOT),canonical);
        }
        return new ArrayList<>(links.values());
    }
    private static ImportJob beginImport(int account) {
        ImportJob job = new ImportJob(account), previous = IMPORTS.putIfAbsent(job.owner, job);
        if (previous != null && (previous.progress.active || !IMPORTS.replace(job.owner, previous, job)))
            throw new IllegalStateException("A pack is already being imported. Wait for it to finish or cancel it.");
        return job;
    }
    /** Policy only: callers must enforce encrypted transport for private pack sharing. */
    public static synchronized void privatePack(int account, long id, boolean value) throws Exception {
        String scope = scope(account); ArrayList<Pack> packs = copy(read(scope));
        for (Pack pack : packs) if (pack.id == id) { pack.privatePack = value; write(scope, packs); return; }
        throw new IllegalArgumentException("Pack is no longer available.");
    }
    /** Returns an unencrypted app-private snapshot; the caller deletes it after saving/sharing. */
    public static Request exportArchive(int account, long packId, ArchiveCallback callback) {
        final Request request = new Request(account); final String expectedScope = scope(account);
        WORKER.execute(() -> {
            File output = null; String error = null;
            try {
                request.check(); Pack selected = null;
                synchronized (MonogramPacks.class) {
                    for (Pack pack : copy(read(expectedScope))) if (pack.id == packId) { selected = pack; break; }
                }
                if (selected == null) throw new ImportFailure("Pack is no longer available.");
                File directory = new File(ApplicationLoader.applicationContext.getCacheDir(), "monogram-pack-exports/" + expectedScope);
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
                output = File.createTempFile("pack-", ".xmsp", directory);
                MonogramPackArchive.write(selected, directory(expectedScope), output, request::check);
                request.check();
            } catch (ImportFailure failure) { error = failure.getMessage(); }
            catch (Exception failure) { error = "Could not export the pack. Check its artwork and available storage."; }
            if (error != null && output != null) { output.delete(); output = null; }
            final File archive = output; final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (request.cancelled || !request.owner.equals(owner(account))) { if (archive != null) archive.delete(); return; }
                callback.done(archive, failure);
            });
        });
        return request;
    }
    /** Account-owned, token-free import of a plaintext snapshot selected through SAF. */
    public static void startArchiveImport(int account, Uri source) {
        if (source == null || !"content".equals(source.getScheme())) throw new IllegalArgumentException("Choose an .xmsp file using the file picker.");
        final ImportJob job = beginImport(account); final Request operation = job.request;
        final String expectedScope = scope(account); notifyImport(job);
        WORKER.execute(() -> {
            File staging = null; ArrayList<File> moved = new ArrayList<>(); String error = null;
            try {
                operation.check(); job.update("Reading archive", "", 0, 0, 0, -1);
                staging = File.createTempFile("monogram-xmsp-", "", ApplicationLoader.applicationContext.getCacheDir());
                if (!staging.delete() || !staging.mkdir()) throw new IOException();
                File archive = new File(staging, "input.xmsp");
                try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(source);
                     FileOutputStream output = new FileOutputStream(archive)) {
                    if (input == null) throw new IOException();
                    byte[] buffer = new byte[16384]; long total = 0; int count;
                    while ((count = input.read(buffer)) != -1) {
                        operation.check(); total += count;
                        if (total > MonogramPackArchive.MAX_ARCHIVE) throw new ImportFailure("Archive exceeds the 105 MB safe size limit.");
                        output.write(buffer, 0, count); job.bytes(total, -1);
                    }
                    output.getFD().sync();
                }
                Pack pack = MonogramPackArchive.read(archive, staging, operation::check,
                        (name, completed, total) -> job.update("Checking archive artwork", name, completed, total, 0, -1));
                pack.source = "xmsp:" + UUID.randomUUID();
                job.update("Saving pack", pack.name, pack.items.size(), pack.items.size(), 0, -1);
                synchronized (MonogramPacks.class) {
                    operation.check(); ArrayList<Pack> packs = copy(read(expectedScope)); Set<Long> pending = new HashSet<>();
                    pack.id = id(expectedScope, pending);
                    File targetDir = directory(expectedScope); if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new IOException();
                    Set<String> files = new HashSet<>();
                    synchronized (operation) {
                        operation.check();
                        for (Item item : pack.items) {
                            item.id = id(expectedScope, pending);
                            if (files.add(item.file)) {
                                File target = new File(targetDir, item.file);
                                if (target.exists() || !new File(staging, item.file).renameTo(target)) throw new IOException();
                                moved.add(target);
                            }
                        }
                        operation.check(); packs.add(0, pack); write(expectedScope, packs); operation.committed = true; moved.clear();
                    }
                }
            } catch (Exception failure) {
                error = failure instanceof ImportFailure ? failure.getMessage() : "Invalid or unsupported .xmsp archive, or insufficient storage. The existing packs were preserved.";
            } finally {
                for (File file : moved) file.delete();
                if (staging != null) { File[] files = staging.listFiles(); if (files != null) for (File file : files) file.delete(); staging.delete(); }
            }
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (!job.owner.equals(owner(account)) || IMPORTS.get(job.owner) != job) return;
                try { if (operation.committed) refresh(account); } finally { job.finished(failure); }
            });
        });
    }
    /** Account-owned import: leaving a screen only removes its observer, never cancels this job. */
    public static void startImport(int account, String link) {
        final ArrayList<String> links=parseLinks(link);
        final String expectedScope = scope(account), encryptedToken = preferences(account).getString("token", null);
        if (encryptedToken == null) throw new IllegalStateException("Add your own Telegram bot token first. The import uses Telegram’s official Bot API.");
        final ImportJob job = beginImport(account);
        job.packCount=links.size();
        final Request operation = job.request;
        final String expectedOwner = job.owner;
        notifyImport(job);
        WORKER.execute(() -> {
            ArrayList<String> errors=new ArrayList<>(); int saved=0;
            for(int packIndex=0;packIndex<links.size();packIndex++) {
            String currentLink=links.get(packIndex); Link parsed=parseLink(currentLink);
            job.packIndex=packIndex+1; operation.committed=false;
            String error = null; ArrayList<File> temporary = new ArrayList<>();
            try {
                operation.check();
                job.update("Loading pack", "", 0, 0, 0, -1);
                String token = new AccountVault(account).decrypt(encryptedToken);
                JSONObject result = request(token, "getStickerSet", "name", parsed.name, operation).getJSONObject("result");
                if (!parsed.name.equalsIgnoreCase(result.getString("name")) || parsed.emojis != "custom_emoji".equals(result.optString("sticker_type"))) throw new IOException();
                Set<Long> pending = new HashSet<>(); Pack pack = new Pack(); pack.id = id(expectedScope, pending); pack.name = name(result.getString("title")); pack.source = currentLink; pack.emojis = parsed.emojis;
                JSONArray stickers = result.getJSONArray("stickers"); if (stickers.length() < 1) throw new ImportFailure("This pack has no stickers.");
                synchronized (MonogramPacks.class) {
                    for (Pack old : read(expectedScope)) if (old.source.equals(currentLink) && old.installed) throw new ImportFailure("This pack is already installed.");
                }
                File dir = directory(expectedScope); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException();
                pack.items.addAll(downloadItems(account,token,stickers,pack,dir,expectedScope,pending,operation,job));
                for(Item item:pack.items) temporary.add(new File(dir,item.file));
                job.update("Saving pack", pack.name, stickers.length(), stickers.length(), 0, -1);
                synchronized (MonogramPacks.class) {
                    if (!expectedOwner.equals(owner(account))) throw new ImportFailure("The account changed. Import again on the intended account.");
                    ArrayList<Pack> packs = new ArrayList<>(read(expectedScope));
                    for (Pack old : packs) if (old.source.equals(currentLink) && old.installed) throw new ImportFailure("This pack is already installed.");
                    synchronized (operation) {
                        operation.check(); packs.add(0, pack); write(expectedScope, packs); operation.committed = true; temporary.clear();
                    }
                }
            } catch (Exception failure) { error = importError(failure, job.progress); }
            finally { for (File file : temporary) file.delete(); }
            if(operation.committed) saved++;
            if(error!=null) errors.add(parsed.name+": "+error);
            if(operation.cancelled || !expectedOwner.equals(owner(account))) break;
            }
            final boolean anySaved=saved>0;
            operation.committed=anySaved && !operation.cancelled;
            final String failure=errors.isEmpty() ? null : saved+" / "+links.size()+" packs imported.\n"+android.text.TextUtils.join("\n",errors);
            AndroidUtilities.runOnUIThread(() -> {
                if (!expectedOwner.equals(owner(account)) || IMPORTS.get(expectedOwner) != job) return;
                try { if (anySaved) refresh(account); }
                finally { job.finished(failure); }
            });
        });
    }
    private static final class DownloadedItem {
        final int index; final Item item;
        DownloadedItem(int index,Item item) { this.index=index;this.item=item; }
    }
    private static ArrayList<Item> downloadItems(int account,String token,JSONArray stickers,Pack pack,File dir,String scope,
            Set<Long> pending,Request operation,ImportJob job) throws Exception {
        Request group=new Request(account);group.parent=operation;
        java.util.concurrent.CompletionService<DownloadedItem> completion=new java.util.concurrent.ExecutorCompletionService<>(DOWNLOADS);
        ArrayList<java.util.concurrent.Future<DownloadedItem>> active=new ArrayList<>();
        ArrayList<File> temporary=new ArrayList<>();
        ArrayList<Item> items=new ArrayList<>(java.util.Collections.nCopies(stickers.length(),null));
        java.util.concurrent.atomic.AtomicLong total=new java.util.concurrent.atomic.AtomicLong();
        long[] received=new long[stickers.length()],lengths=new long[stickers.length()];java.util.Arrays.fill(lengths,-1);
        int[] done={0};int next=0,finished=0;
        try {
            while(finished<stickers.length()) {
                operation.check();
                while(next<stickers.length() && active.size()<4) {
                    final int index=next++;final JSONObject sticker=stickers.getJSONObject(index);
                    active.add(completion.submit(() -> {
                        Request child=new Request(account);child.parent=group;
                        JSONObject remote=request(token,"getFile","file_id",sticker.getString("file_id"),child).getJSONObject("result");
                        String path=remote.getString("file_path");
                        if(!path.matches("[A-Za-z0-9_/-]+\\.(webp|png|tgs|webm)") || path.contains("..")) throw new IOException();
                        Item item=new Item();item.id=id(scope,pending);item.emoji=tag(sticker.optString("emoji","🙂"));item.name="Item "+(index+1);
                        String ext=path.substring(path.lastIndexOf('.'));item.file=UUID.randomUUID()+ext;
                        item.mime=ext.equals(".tgs") ? "application/x-tgsticker" : ext.equals(".webm") ? "video/webm" : ext.equals(".png") ? "image/png" : "image/webp";
                        item.width=dimension(sticker.optInt("width",512));item.height=dimension(sticker.optInt("height",512));
                        child.mediaDownload=true;
                        child.byteProgress=(bytes,length) -> {
                            synchronized(received) {
                                received[index]=bytes;lengths[index]=length;long downloaded=0,expected=0;
                                for(int i=0;i<received.length;i++) { downloaded+=received[i];if(lengths[i]<0) expected=-1;else if(expected>=0) expected+=lengths[i]; }
                                job.update("Downloading files",pack.name,done[0],stickers.length(),downloaded,expected);
                            }
                        };
                        byte[] data=download("https://api.telegram.org/file/bot"+token+"/"+path,MAX_FILE,child);
                        child.check();
                        try { validateMedia(data,item.mime); } catch(Exception invalid) { throw new ImportFailure("Sticker "+(index+1)+" has invalid or unsupported media."); }
                        if(total.addAndGet(data.length)>MAX_PACK) throw new ImportFailure("A pack may use up to 100 MB on this phone.");
                        File target=new File(dir,item.file);synchronized(temporary) { temporary.add(target); }
                        try(FileOutputStream output=new FileOutputStream(target)) { output.write(data);output.getFD().sync(); }
                        child.check();
                        synchronized(received) { done[0]++;job.update("Files downloaded",pack.name,done[0],stickers.length(),total.get(),-1); }
                        return new DownloadedItem(index,item);
                    }));
                }
                java.util.concurrent.Future<DownloadedItem> ready=completion.take();active.remove(ready);
                DownloadedItem item;
                try { item=ready.get(); } catch(java.util.concurrent.ExecutionException failed) {
                    Throwable cause=failed.getCause();if(cause instanceof Exception) throw (Exception)cause;throw new IOException(cause);
                }
                items.set(item.index,item.item);finished++;
            }
            operation.check();return items;
        } catch(Exception failed) {
            group.cancel();
            // Await every writer before cleanup; interrupting a Future alone does not stop its I/O.
            for(java.util.concurrent.Future<DownloadedItem> future:active) try { future.get(); } catch(Exception ignored) {}
            synchronized(temporary) { for(File file:temporary) file.delete(); }
            throw failed;
        }
    }
    /** Copy selected files into account-private storage; source paths and names are never uploaded. */
    public static Request create(int account, String title, boolean emojis, ArrayList<Uri> sources, Callback callback) {
        return create(account,title,emojis,sources,0,callback);
    }
    public static Request addItems(int account,long packId,ArrayList<Uri> sources,Callback callback) {
        Pack pack=pack(account,packId);
        if(pack==null) { callback.done("Pack not found.");return new Request(account); }
        return create(account,pack.name,pack.emojis,sources,packId,callback);
    }
    private static Request create(int account,String title,boolean emojis,ArrayList<Uri> sources,long targetPackId,Callback callback) {
        return create(account,title,emojis,sources,targetPackId,"🙂",null,callback);
    }
    public static Request saveSticker(int account,long packId,String title,Uri source,String stickerName,String emoji,Callback callback) {
        ArrayList<Uri> sources=new ArrayList<>();sources.add(source);
        return create(account,title,false,sources,packId,emoji,stickerName,callback);
    }
    private static Request create(int account,String title,boolean emojis,ArrayList<Uri> sources,long targetPackId,String emoji,String stickerName,Callback callback) {
        final Request operation = new Request(account);
        final String expectedOwner = owner(account);
        final String expectedScope = scope(account);
        final String packName;
        try { packName = name(title); if (sources.isEmpty()) throw new IllegalArgumentException("Select at least one file."); }
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
                    String ext; Item item = new Item(); item.id = id(expectedScope, pending); item.name = stickerName == null ? "Item " + (pack.items.size() + 1) : name(stickerName); item.emoji = tag(emoji);
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
                    synchronized (operation) {
                        operation.check();
                        if(targetPackId==0) packs.add(0,pack);
                        else {
                            packs=copy(packs); Pack target=null;
                            for(Pack candidate:packs) if(candidate.id==targetPackId && candidate.installed) target=candidate;
                            if(target==null) throw new ImportFailure("Pack no longer available.");
                            requireEditable(target);
                            long combined=total;
                            for(Item item:target.items) combined+=new File(dir,item.file).length();
                            if(combined>MAX_PACK) throw new ImportFailure("A pack may use up to 100 MB on this phone.");
                            target.items.addAll(pack.items);
                        }
                        write(expectedScope, packs); temporary.clear();
                    }
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
    // Only fixed, redacted categories leave this boundary; API descriptions can contain secrets.
    static IOException telegramError(int status, JSONObject response) {
        int code = response == null ? status : response.optInt("error_code", status);
        String description = response == null ? "" : response.optString("description", "").toLowerCase(Locale.ROOT);
        String reason;
        if (code == 401 || status == 401 || description.contains("unauthorized")) reason = "Bot token was rejected. Replace it in pack settings.";
        else if (description.contains("stickerset_invalid") || description.contains("sticker set not found")) reason = "Telegram could not find that sticker pack. Check its link.";
        else if (description.contains("file is too big") || code == 413 || status == 413) reason = "File exceeds Telegram's bot download size limit.";
        else if (code == 429 || status == 429) reason = "Telegram is rate limiting this bot. Try again later.";
        else if (code == 403 || status == 403) reason = "Telegram denied this bot access.";
        else if (code == 404 || status == 404) reason = "Telegram API or file was not found. Check the bot token and retry.";
        else if (code >= 500 || status >= 500) reason = "Telegram service is temporarily unavailable.";
        else reason = "Telegram rejected the request.";
        return new ImportFailure(reason + " (HTTP " + status + ", API " + code + ")");
    }
    static String importError(Exception failure, ImportProgress progress) {
        String reason;
        if (failure instanceof ImportFailure) reason = failure.getMessage();
        else if (failure instanceof java.net.SocketTimeoutException) reason = "Connection timed out. Check the connection and retry.";
        else if (failure instanceof java.net.UnknownHostException || failure instanceof java.net.ConnectException) reason = "Could not reach Telegram. Check the connection and retry.";
        else if (failure instanceof javax.net.ssl.SSLException) reason = "A secure connection to Telegram could not be established.";
        else if (progress.stage.endsWith("Checking file")) reason = "Downloaded file is invalid or uses unsupported media.";
        else if (failure instanceof org.json.JSONException) reason = "Telegram returned incomplete or unsupported pack data.";
        else reason = "Import failed. Check the connection and available storage, then retry.";
        return reason + " Stage: " + progress.stage + (progress.total > 0 ? " (item " + Math.min(progress.completed + 1, progress.total) + "/" + progress.total + ")." : ".");
    }
    private static JSONObject request(String token, String method, String key, String value, Request operation) throws Exception {
        String url = "https://api.telegram.org/bot" + token + "/" + method + "?" + key + "=" + Uri.encode(value);
        JSONObject object = new JSONObject(new String(download(url, MAX_MANIFEST, operation), "UTF-8"));
        if (!object.optBoolean("ok")) throw telegramError(200, object); return object;
    }
    private static byte[] download(String value, int limit, Request operation) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
        try { operation.attach(connection);
            int status = connection.getResponseCode();
            if (status != 200) {
                JSONObject error = null;
                try (InputStream input = connection.getErrorStream()) {
                    if (input != null) error = new JSONObject(new String(bytes(input, 65536, operation), "UTF-8"));
                } catch (Exception ignored) { operation.check(); }
                throw telegramError(status, error);
            }
            if (connection.getContentLength() > limit) throw new ImportFailure(operation.mediaDownload ? "File exceeds the 20 MB Telegram download limit." : "Telegram response exceeds the safe size limit.");
            if (operation.mediaDownload) {
                operation.downloadLength = connection.getContentLength() >= 0 ? connection.getContentLength() : -1;
                if(operation.importJob!=null) operation.importJob.bytes(0, operation.downloadLength);
                if(operation.byteProgress!=null) operation.byteProgress.accept(0L,operation.downloadLength);
            }
            try (InputStream input = connection.getInputStream()) { return bytes(input, limit, operation); }
        } finally { operation.detach(connection); connection.disconnect(); }
    }
    private static byte[] bytes(InputStream input, int limit) throws Exception { return bytes(input, limit, null); }
    private static byte[] bytes(InputStream input, int limit, Request operation) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) { if (operation != null) operation.check(); if (body.size() + count > limit) throw new ImportFailure(operation != null && operation.mediaDownload ? "File exceeds the 20 MB Telegram download limit." : "Input exceeds the safe size limit."); body.write(buffer, 0, count);
            if(operation!=null && operation.byteProgress!=null) operation.byteProgress.accept((long)body.size(),operation.downloadLength);
            if (operation != null && operation.importJob != null && operation.mediaDownload) operation.importJob.bytes(body.size(), operation.downloadLength);
        }
        return body.toByteArray();
    }
}
