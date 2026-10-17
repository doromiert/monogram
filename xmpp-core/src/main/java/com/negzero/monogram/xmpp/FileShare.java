// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;

/** XEP-0447 HTTP file descriptions; encryption belongs to the containing content envelope. */
public final class FileShare {
    public static final String NAMESPACE = "urn:xmpp:sfs:0", METADATA = "urn:xmpp:file:metadata:0";
    public static final String URL_DATA = "http://jabber.org/protocol/url-data", HASHES = "urn:xmpp:hashes:2", OOB = "jabber:x:oob";
    private static final String[] SIMPLE_METADATA = {"name", "media-type", "size", "date", "desc", "height", "length", "width"};
    public final FileLink link;
    public final String name, mime;
    public final long size;
    public final boolean manual;
    private final StandardExtensionElement element;
    private final Map<String, byte[]> hashes = new LinkedHashMap<>();

    public static void initialize() {
        ReplyReference.initialize();
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("file-sharing", NAMESPACE, StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("x", OOB, StandardExtensionElementProvider.INSTANCE);
    }
    public FileShare(String xml) throws Exception { this(StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml))); }
    private FileShare(StandardExtensionElement element) throws Exception {
        if (!"file-sharing".equals(element.getElementName()) || !NAMESPACE.equals(element.getNamespace()) || element.toXML().length() > 65536) throw new IOException("Invalid file description");
        this.element = element;
        String disposition = element.getAttributeValue("disposition");
        if (disposition != null && !"attachment".equals(disposition) && !"inline".equals(disposition)) throw new IOException("Invalid file disposition");
        manual = "attachment".equals(element.getAttributeValue("disposition"));
        StandardExtensionElement sources = only(element, "sources", NAMESPACE), file = only(element, "file", METADATA);
        if (sources == null || file == null) throw new IOException("Missing file description/source");
        StandardExtensionElement url = only(sources, "url-data", URL_DATA);
        if (url == null) throw new IOException("Unsupported or ambiguous file source");
        link = FileLink.parse(url.getAttributeValue("target"));
        String filename = text(file, "name"), type = text(file, "media-type").trim(), length = text(file, "size").trim();
        name = filename.isEmpty() ? link.fileName() : filename.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        if (name.length() > 200 || name.equals(".") || name.equals("..") || type.length() > 200) throw new IOException("Invalid file metadata");
        mime = type.isEmpty() ? "application/octet-stream" : type;
        size = length.isEmpty() ? -1 : Long.parseLong(length);
        if (size < -1 || !length.isEmpty() && size < 0) throw new IOException("Invalid file size");
        for (StandardExtensionElement hash : file.getElements()) {
            if (!"hash".equals(hash.getElementName()) || !HASHES.equals(hash.getNamespace())) continue;
            String algorithm = hash.getAttributeValue("algo"); Digest digest = digest(algorithm);
            if (digest == null) continue;
            if (hash.getText() == null || hash.getText().length() > 128) throw new IOException("Invalid file hash");
            byte[] value = java.util.Base64.getDecoder().decode(hash.getText().replaceAll("[\\t\\n\\r ]", ""));
            if (value.length != digest.getDigestSize() || hashes.put(algorithm, value) != null) throw new IOException("Invalid/duplicate file hash");
        }
    }
    private static StandardExtensionElement only(StandardExtensionElement parent, String name, String namespace) throws IOException {
        List<StandardExtensionElement> matches = parent.getElements(name, namespace);
        if (matches == null) return null;
        if (matches.size() > 1) throw new IOException("Ambiguous file metadata");
        return matches.isEmpty() ? null : matches.get(0);
    }
    private static String text(StandardExtensionElement file, String name) throws IOException {
        StandardExtensionElement field = only(file, name, METADATA);
        return field == null || field.getText() == null ? "" : field.getText();
    }
    private static Digest digest(String name) {
        if ("sha-256".equals(name)) return new SHA256Digest();
        if ("sha3-256".equals(name)) return new SHA3Digest(256);
        if ("id-blake2b256".equals(name)) return new Blake2bDigest(256);
        return null;
    }
    public String xml() { return element.toXML().toString(); }
    /**
     * True when this is the same attachment described with an equivalent, known SFS shape.
     * Unknown metadata remains significant unless its serialized representation is unchanged.
     */
    public boolean sameAttachment(FileShare other) {
        if (other == null) return false;
        if (xml().equals(other.xml())) return true;
        if (!knownDescription(element) || !knownDescription(other.element)) return false;
        if (!link.wireUrl.equals(other.link.wireUrl) || manual != other.manual || size != other.size
                || !name.equals(other.name) || !mime.equals(other.mime)
                || !same(element.getAttributeValue("disposition"), other.element.getAttributeValue("disposition"))
                || !same(element.getAttributeValue("id"), other.element.getAttributeValue("id"))) return false;
        StandardExtensionElement firstFile = metadataFile(element), secondFile = metadataFile(other.element);
        for (String field : SIMPLE_METADATA) if (!same(metadataText(firstFile, field), metadataText(secondFile, field))) return false;
        if (hashes.size() != other.hashes.size()) return false;
        for (Map.Entry<String, byte[]> hash : hashes.entrySet()) {
            byte[] value = other.hashes.get(hash.getKey());
            if (value == null || !java.security.MessageDigest.isEqual(hash.getValue(), value)) return false;
        }
        return true;
    }
    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }
    private static String metadataText(StandardExtensionElement description, String name) {
        for (StandardExtensionElement child : description.getElements()) {
            if (name.equals(child.getElementName()) && METADATA.equals(child.getNamespace())) return child.getText();
        }
        return null;
    }
    private static StandardExtensionElement metadataFile(StandardExtensionElement description) {
        for (StandardExtensionElement child : description.getElements()) {
            if ("file".equals(child.getElementName()) && METADATA.equals(child.getNamespace())) return child;
        }
        return null;
    }
    private static boolean knownDescription(StandardExtensionElement description) {
        if (!rootAttributes(description) || !blank(description)) return false;
        int files = 0, sources = 0;
        for (StandardExtensionElement child : description.getElements()) {
            if ("file".equals(child.getElementName()) && METADATA.equals(child.getNamespace())) { if (++files != 1 || !knownFile(child)) return false; }
            else if ("sources".equals(child.getElementName()) && NAMESPACE.equals(child.getNamespace())) { if (++sources != 1 || !knownSources(child)) return false; }
            else return false;
        }
        return files == 1 && sources == 1;
    }
    private static boolean knownSources(StandardExtensionElement sources) {
        if (!attributes(sources) || !blank(sources)) return false;
        int urls = 0;
        for (StandardExtensionElement child : sources.getElements()) {
            if (!"url-data".equals(child.getElementName()) || !URL_DATA.equals(child.getNamespace()) || ++urls != 1
                    || !attributes(child, "target") || !blank(child) || !child.getElements().isEmpty()) return false;
        }
        return urls == 1;
    }
    private static boolean knownFile(StandardExtensionElement file) {
        if (!attributes(file) || !blank(file)) return false;
        Map<String, Boolean> fields = new LinkedHashMap<>();
        Map<String, Boolean> algorithms = new LinkedHashMap<>();
        for (StandardExtensionElement child : file.getElements()) {
            if (METADATA.equals(child.getNamespace()) && simpleMetadata(child.getElementName())) {
                if (!attributes(child) || !child.getElements().isEmpty() || fields.put(child.getElementName(), Boolean.TRUE) != null) return false;
            } else if (HASHES.equals(child.getNamespace()) && "hash".equals(child.getElementName())) {
                String algorithm = child.getAttributeValue("algo");
                if (!attributes(child, "algo") || digest(algorithm) == null || algorithms.put(algorithm, Boolean.TRUE) != null || !child.getElements().isEmpty()) return false;
            } else return false;
        }
        return true;
    }
    private static boolean simpleMetadata(String name) {
        for (String field : SIMPLE_METADATA) if (field.equals(name)) return true;
        return false;
    }
    private static boolean rootAttributes(StandardExtensionElement element) {
        if (element.getAttributes().size() > 2) return false;
        for (String name : element.getAttributes().keySet()) if (!"id".equals(name) && !"disposition".equals(name)) return false;
        return true;
    }
    private static boolean attributes(StandardExtensionElement element, String... expected) {
        Map<String, String> attributes = element.getAttributes();
        if (attributes.size() != expected.length) return false;
        for (String name : expected) if (!attributes.containsKey(name)) return false;
        return true;
    }
    private static boolean blank(StandardExtensionElement element) {
        return element.getText() == null || element.getText().trim().isEmpty();
    }
    /** A reused URL cannot substitute bytes with different expected integrity metadata. */
    public String cacheKey() {
        if (size < 0 && hashes.isEmpty()) return link.wireUrl;
        StringBuilder key = new StringBuilder("file-share\n").append(link.wireUrl).append('\n').append(size);
        for (Map.Entry<String, byte[]> hash : new java.util.TreeMap<>(hashes).entrySet()) {
            key.append('\n').append(hash.getKey()).append(':').append(java.util.Base64.getEncoder().encodeToString(hash.getValue()));
        }
        return key.toString();
    }
    public String body(String caption) { return caption == null || caption.isEmpty() ? link.wireUrl : caption + "\n" + link.wireUrl; }
    public static FileShare describe(FileLink link, File file, String name, String mime) throws Exception {
        return describe(link, file, name, mime, new FileTransfer.Job());
    }
    public static FileShare describe(FileLink link, File file, String name, String mime, FileTransfer.Job job) throws Exception {
        job.check();
        SHA256Digest digest = new SHA256Digest();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = in.read(buffer)) != -1) { job.check(); digest.update(buffer, 0, read); }
        }
        byte[] hash = new byte[32]; digest.doFinal(hash, 0);
        StandardExtensionElement metadata = StandardExtensionElement.builder("file", METADATA)
                .addElement("name", name).addElement("media-type", mime).addElement("size", Long.toString(file.length()))
                .addElement(StandardExtensionElement.builder("hash", HASHES).addAttribute("algo", "sha-256").setText(java.util.Base64.getEncoder().encodeToString(hash)).build()).build();
        return new FileShare(StandardExtensionElement.builder("file-sharing", NAMESPACE).addAttribute("disposition", "inline").addElement(metadata)
                .addElement(StandardExtensionElement.builder("sources", NAMESPACE).addElement(StandardExtensionElement.builder("url-data", URL_DATA).addAttribute("target", link.wireUrl).build()).build()).build());
    }
    /** Verify downloaded plaintext before committing it, or before reusing a cached attachment. */
    public void verify(File file, FileTransfer.Job job) throws Exception {
        job.check();
        if (size >= 0 && file.length() != size) throw new FileTransfer.Failure("INTEGRITY");
        Map<String, Digest> digests = new LinkedHashMap<>();
        for (String algorithm : hashes.keySet()) digests.put(algorithm, digest(algorithm));
        if (digests.isEmpty()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = in.read(buffer)) != -1) { job.check(); for (Digest digest : digests.values()) digest.update(buffer, 0, read); }
        }
        for (Map.Entry<String, Digest> hash : digests.entrySet()) {
            byte[] value = new byte[hash.getValue().getDigestSize()]; hash.getValue().doFinal(value, 0);
            if (!java.security.MessageDigest.isEqual(value, hashes.get(hash.getKey()))) throw new FileTransfer.Failure("INTEGRITY");
        }
    }
    public static FileShare from(Message message) {
        List<ExtensionElement> files = message.getExtensions("file-sharing", NAMESPACE);
        try {
            if (files.isEmpty()) {
                List<ExtensionElement> references = message.getExtensions("x", OOB);
                if (references.size() != 1 || !(references.get(0) instanceof StandardExtensionElement)) return null;
                StandardExtensionElement url = only((StandardExtensionElement) references.get(0), "url", OOB);
                if (url == null || url.getText() == null) return null;
                return new FileShare(StandardExtensionElement.builder("file-sharing", NAMESPACE)
                        .addElement(StandardExtensionElement.builder("file", METADATA).build())
                        .addElement(StandardExtensionElement.builder("sources", NAMESPACE).addElement(StandardExtensionElement.builder("url-data", URL_DATA).addAttribute("target", url.getText()).build()).build()).build());
            }
            if (files.size() != 1 || !(files.get(0) instanceof StandardExtensionElement)) return null;
            return new FileShare((StandardExtensionElement) files.get(0));
        }
        catch (Exception invalid) { return null; }
    }
    public void addTo(Message message, String body) {
        int start = body.length() - link.wireUrl.length();
        if (start < 0 || !body.endsWith(link.wireUrl)) throw new IllegalArgumentException("Missing file fallback");
        if (start > 0 && body.charAt(start - 1) == '\n') start--;
        int fallbackStart = body.codePointCount(0, start);
        message.addExtension(element);
        message.addExtension(StandardExtensionElement.builder("x", OOB).addElement("url", link.wireUrl).build());
        int end = body.codePointCount(0, body.length());
        for (String format : new String[]{NAMESPACE, OOB}) message.addExtension(StandardExtensionElement.builder("fallback", ReplyReference.FALLBACK).addAttribute("for", format)
                .addElement(StandardExtensionElement.builder("body", ReplyReference.FALLBACK).addAttribute("start", Integer.toString(fallbackStart)).addAttribute("end", Integer.toString(end)).build()).build());
    }
    /** Keep actual caption text; only marked file-URL spans and validated reply spans may disappear. */
    public String caption(Message message, String body, ReplyReference reply) {
        return captionMarkup(message,body,reply,MessageMarkup.body(body)).body;
    }
    public MessageMarkup captionMarkup(Message message,String body,ReplyReference reply,MessageMarkup markup) {
        if(body==null) return MessageMarkup.body("");
        List<int[]> spans = new ArrayList<>(); int length = body.codePointCount(0, body.length());
        try {
            for (ExtensionElement extension : message.getExtensions("fallback", ReplyReference.FALLBACK)) {
                if (!(extension instanceof StandardExtensionElement)) continue;
                StandardExtensionElement fallback = (StandardExtensionElement) extension;
                String format = fallback.getAttributeValue("for");
                if (!NAMESPACE.equals(format) && !OOB.equals(format)) continue;
                for (StandardExtensionElement part : fallback.getElements("body", ReplyReference.FALLBACK)) {
                    if (part.getAttributeValue("xml:lang") != null) continue;
                    String a = part.getAttributeValue("start"), b = part.getAttributeValue("end");
                    int start = a == null && b == null ? 0 : Integer.parseInt(a), end = a == null && b == null ? length : Integer.parseInt(b);
                    if (start < 0 || end < start || end > length || spans.size() >= 64) throw new IllegalArgumentException();
                    String content = body.substring(body.offsetByCodePoints(0, start), body.offsetByCodePoints(0, end));
                    if (!content.trim().equals(link.wireUrl)) throw new IllegalArgumentException();
                    spans.add(new int[]{start, end});
                }
            }
        } catch (RuntimeException malformed) { return withoutReply(markup,reply); }
        if (spans.isEmpty() && (body.equals(link.wireUrl) || reply != null && reply.withoutFallback(body).equals(link.wireUrl))) return markup.slice(0,0);
        if (reply != null && !reply.ranges.isEmpty()) {
            try {
                for (String range : reply.ranges.split(",")) {
                    String[] ends = range.split(":"); int a = Integer.parseInt(ends[0]), b = "*".equals(ends[1]) ? length : Integer.parseInt(ends[1]);
                    if (a < 0 || b < a || b > length) throw new IllegalArgumentException();
                    spans.add(new int[]{a, b});
                }
            } catch (RuntimeException malformed) { return markup; }
        }
        return markup.without(spans);
    }
    private static MessageMarkup withoutReply(MessageMarkup markup,ReplyReference reply) {
        if(reply==null || reply.ranges.isEmpty()) return markup;
        try {
            int length=markup.body.codePointCount(0,markup.body.length());List<int[]> spans=new ArrayList<>();
            for(String range:reply.ranges.split(",")) { String[] ends=range.split(":");spans.add(new int[]{Integer.parseInt(ends[0]),"*".equals(ends[1]) ? length : Integer.parseInt(ends[1])}); }
            return markup.without(spans);
        } catch(RuntimeException invalid) { return markup; }
    }
}
