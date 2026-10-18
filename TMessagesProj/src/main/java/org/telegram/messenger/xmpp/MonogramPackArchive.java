// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Portable, local plaintext snapshots. Network privacy is enforced by encrypted transport. */
final class MonogramPackArchive {
    static final int MAX_FILE = 20 * 1024 * 1024, MAX_TOTAL = 100 * 1024 * 1024;
    static final int MAX_MANIFEST = 4 * 1024 * 1024, MAX_ARCHIVE = 105 * 1024 * 1024;
    interface Check { void check() throws IOException; }
    interface Progress { void update(String name, int completed, int total); }
    private MonogramPackArchive() {}

    static void write(MonogramPacks.Pack pack, File directory, File destination, Check check) throws Exception {
        if (pack.items.isEmpty()) throw new IOException("Packs must contain at least one item.");
        JSONObject manifest = new JSONObject().put("format", "xmsp").put("version", 1)
                .put("name", MonogramPacks.name(pack.name)).put("kind", pack.emojis ? "emoji" : "stickers");
        JSONArray items = new JSONArray(); LinkedHashMap<String, File> files = new LinkedHashMap<>();
        long total = 0;
        for (MonogramPacks.Item item : pack.items) {
            check.check(); File file = new File(directory, item.file);
            if (!file.isFile() || !file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) throw new IOException("Pack artwork is missing.");
            byte[] data = read(new FileInputStream(file), MAX_FILE, check);
            validateMedia(data, item.mime, dimension(item.width), dimension(item.height));
            String hash = hash(data), path = "assets/" + hash + extension(item.mime);
            if (!files.containsKey(path)) { total += data.length; if (total > MAX_TOTAL) throw new IOException("Pack exceeds 100 MB."); files.put(path, file); }
            items.put(new JSONObject().put("id", UUID.randomUUID().toString()).put("name", MonogramPacks.name(item.name))
                    .put("emoji", tag(item.emoji)).put("mime", item.mime).put("width", dimension(item.width)).put("height", dimension(item.height))
                    .put("size", data.length).put("sha256", hash).put("path", path));
        }
        manifest.put("items", items); byte[] json = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_MANIFEST) throw new IOException("Pack manifest is too large.");
        try (FileOutputStream output = new FileOutputStream(destination); ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "manifest.json", json);
            for (Map.Entry<String, File> file : files.entrySet()) {
                check.check(); byte[] data = read(new FileInputStream(file.getValue()), MAX_FILE, check);
                if (!file.getKey().equals("assets/" + hash(data) + file.getKey().substring(file.getKey().lastIndexOf('.')))) throw new IOException("Pack changed during export. Retry.");
                entry(zip, file.getKey(), data);
            }
            zip.finish(); output.getFD().sync(); check.check();
        }
        if (destination.length() > MAX_ARCHIVE) throw new IOException("Archive exceeds the safe size limit.");
    }

    /** Writes only generated opaque filenames into an empty private staging directory. */
    static MonogramPacks.Pack read(File archive, File staging, Check check, Progress progress) throws Exception {
        if (!archive.isFile() || archive.length() < 1 || archive.length() > MAX_ARCHIVE) throw new IOException("Invalid archive size.");
        try (ZipFile zip = new ZipFile(archive)) {
            LinkedHashMap<String, ZipEntry> entries = new LinkedHashMap<>(); long total = 0, metadataBytes=0;
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                check.check(); ZipEntry entry = all.nextElement(); String path = entry.getName();
                metadataBytes+=128L+2L*path.length();
                if(metadataBytes>MAX_MANIFEST) throw new IOException("Archive directory exceeds the safe metadata byte-size limit.");
                if (entry.isDirectory() || entry.getMethod() != ZipEntry.STORED
                        || entry.getSize() < 1 || entry.getCompressedSize() != entry.getSize()
                        || !(path.equals("manifest.json") || path.matches("assets/[0-9a-f]{64}\\.(png|webp|gif|tgs|webm)"))
                        || entries.put(path, entry) != null) throw new IOException("Invalid or duplicate archive entry.");
                long maximum = path.equals("manifest.json") ? MAX_MANIFEST : MAX_FILE;
                if (entry.getSize() > maximum) throw new IOException("Archive entry exceeds the safe size limit.");
                if (!path.equals("manifest.json")) total += entry.getSize();
                if (total > MAX_TOTAL) throw new IOException("Pack exceeds 100 MB.");
            }
            if (entries.isEmpty() || !entries.keySet().iterator().next().equals("manifest.json")) throw new IOException("Missing first manifest entry.");
            byte[] json = asset(zip, entries.get("manifest.json"), MAX_MANIFEST, check);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json)).toString();
            if (text.isEmpty() || text.charAt(0) == '\ufeff') throw new IOException("Invalid manifest encoding.");
            checkDepth(text); JSONTokener tokener = new JSONTokener(text); Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IOException("Invalid pack manifest.");
            JSONObject manifest = (JSONObject) value;
            if (!"xmsp".equals(string(manifest, "format")) || number(manifest, "version") != 1) throw new IOException("Unsupported XMSP version.");
            String kind = string(manifest, "kind");
            if (!kind.equals("stickers") && !kind.equals("emoji")) throw new IOException("Unsupported pack kind.");
            MonogramPacks.Pack pack = new MonogramPacks.Pack(); pack.name = MonogramPacks.name(string(manifest, "name")); pack.emojis = kind.equals("emoji");
            Object array = manifest.get("items"); if (!(array instanceof JSONArray)) throw new IOException("Missing pack items.");
            JSONArray items = (JSONArray) array;
            if (items.length() < 1) throw new IOException("Packs must contain at least one item.");
            Set<String> ids = new HashSet<>(), used = new HashSet<>(); used.add("manifest.json");
            Map<String, JSONObject> metadata = new LinkedHashMap<>();
            // Validate the whole manifest before creating any artwork files.
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i); String id = string(item, "id");
                if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || !ids.add(id)) throw new IOException("Invalid or duplicate item ID.");
                MonogramPacks.name(string(item, "name")); tag(string(item, "emoji"));
                String mime = string(item, "mime"), hash = string(item, "sha256"), path = string(item, "path");
                if (!hash.matches("[0-9a-f]{64}") || !path.equals("assets/" + hash + extension(mime)) || !entries.containsKey(path)) throw new IOException("Missing or invalid artwork path.");
                long size = number(item, "size"); if (size < 1 || size > MAX_FILE || size != entries.get(path).getSize()) throw new IOException("Artwork size does not match its manifest.");
                dimension(number(item, "width")); dimension(number(item, "height"));
                JSONObject previous = metadata.put(path, item);
                if (previous != null && (number(previous, "width") != number(item, "width") || number(previous, "height") != number(item, "height"))) throw new IOException("Inconsistent artwork dimensions.");
                used.add(path);
            }
            if (!used.equals(entries.keySet())) throw new IOException("Archive contains unreferenced files.");
            Map<String, String> local = new HashMap<>();
            for (int i = 0; i < items.length(); i++) {
                check.check(); JSONObject item = items.getJSONObject(i); String path = string(item, "path"), mime = string(item, "mime");
                progress.update(pack.name, i, items.length());
                if (!local.containsKey(path)) {
                    byte[] data = asset(zip, entries.get(path), MAX_FILE, check);
                    if (!hash(data).equals(string(item, "sha256"))) throw new IOException("Artwork hash does not match its manifest.");
                    validateMedia(data, mime, dimension(number(item, "width")), dimension(number(item, "height")));
                    String filename = UUID.randomUUID() + extension(mime);
                    try (FileOutputStream output = new FileOutputStream(new File(staging, filename))) { output.write(data); output.getFD().sync(); }
                    local.put(path, filename);
                }
                MonogramPacks.Item imported = new MonogramPacks.Item(); imported.name = MonogramPacks.name(string(item, "name")); imported.emoji = tag(string(item, "emoji"));
                imported.mime = mime; imported.width = dimension(number(item, "width")); imported.height = dimension(number(item, "height")); imported.file = local.get(path); pack.items.add(imported);
                progress.update(pack.name, i + 1, items.length());
            }
            check.check(); return pack;
        }
    }
    private static void validateMedia(byte[] data, String mime, int width, int height) throws Exception {
        MonogramPacks.validateMedia(data, mime);
        if (mime.equals("image/png") || mime.equals("image/webp")) {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth != width || bounds.outHeight != height || !mime.equals(bounds.outMimeType)) throw new IOException("Image dimensions or type do not match its manifest.");
        } else if (mime.equals("application/x-tgsticker") && (width != 512 || height != 512)) throw new IOException("Invalid animation dimensions.");
    }
    private static byte[] asset(ZipFile zip, ZipEntry entry, int maximum, Check check) throws Exception {
        byte[] data = read(zip.getInputStream(entry), maximum, check); CRC32 crc = new CRC32(); crc.update(data);
        if (data.length != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Archive checksum mismatch.");
        return data;
    }
    static byte[] read(InputStream input, int maximum, Check check) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[16384]; int n;
            while ((n = in.read(bytes)) != -1) { check.check(); if (out.size() > maximum - n) throw new IOException("Input exceeds the safe size limit."); out.write(bytes, 0, n); }
            check.check(); return out.toByteArray();
        }
    }
    private static void entry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        CRC32 crc = new CRC32(); crc.update(data); ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCompressedSize(data.length); entry.setCrc(crc.getValue());
        zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
    }
    private static String extension(String mime) throws IOException {
        if ("image/gif".equals(mime)) return ".gif"; if ("image/png".equals(mime)) return ".png"; if ("image/webp".equals(mime)) return ".webp";
        if ("application/x-tgsticker".equals(mime)) return ".tgs"; if ("video/webm".equals(mime)) return ".webm";
        throw new IOException("Unsupported sticker media type.");
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    private static String string(JSONObject object, String key) throws Exception {
        Object value = object.get(key); if (!(value instanceof String)) throw new IOException("Invalid manifest text field."); return (String) value;
    }
    private static long number(JSONObject object, String key) throws Exception {
        Object value = object.get(key); if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("Invalid manifest integer field."); return ((Number) value).longValue();
    }
    private static int dimension(long value) throws IOException { if (value < 1 || value > 512) throw new IOException("Invalid artwork dimensions."); return (int) value; }
    private static String tag(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 64 || value.codePoints().anyMatch(Character::isISOControl)) throw new IOException("Invalid emoji tag."); return value;
    }
    private static void checkDepth(String value) throws IOException {
        int depth = 0; boolean string = false, escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (string) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') string = false; }
            else if (c == '"') string = true;
            else if (c == '{' || c == '[') { if (++depth > 16) throw new IOException("Manifest nesting exceeds the safe limit."); }
            else if (c == '}' || c == ']') depth--;
        }
    }
}
