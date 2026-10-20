// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jxmpp.jid.impl.JidCreate;

/** XEP-0461 references and XEP-0428 fallback ranges, counted in Unicode code points. */
public final class ReplyReference {
    public static final String NAMESPACE = "urn:xmpp:reply:0", FALLBACK = "urn:xmpp:fallback:0";
    public static void initialize() {
        // Smack 4.4 implements the old empty marker and discards modern fallback ranges.
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("fallback", FALLBACK,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("reply", NAMESPACE,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
    }
    public final String to, id, ranges;
    private final String prefix;
    private final boolean structured;
    public ReplyReference(String to, String id) { this(to, id, "", ""); }
    public ReplyReference(String to, String id, String ranges) { this(to, id, ranges, ""); }
    private ReplyReference(String to, String id, String ranges, String prefix) { this(to,id,ranges,prefix,true); }
    private ReplyReference(String to,String id,String ranges,String prefix,boolean structured) {
        if (structured && (id == null || id.isEmpty() || id.length() > 4096)) throw new IllegalArgumentException("Missing reply ID");
        if (to != null) {
            try {
                org.jxmpp.jid.Jid jid = JidCreate.from(to);
                if (jid.asEntityBareJidIfPossible() == null) throw new IllegalArgumentException("Invalid reply author");
                to = jid.toString();
            } catch (org.jxmpp.stringprep.XmppStringprepException invalid) { throw new IllegalArgumentException("Invalid reply author", invalid); }
        }
        this.to = to; this.id = id; this.ranges = ranges == null ? "" : ranges; this.prefix = prefix; this.structured=structured;
    }
    /** Plain quoted text for rooms/old imports with no interoperable reply ID. */
    public static ReplyReference quoteOnly(String text) { return new ReplyReference(null,"","","",false).quoted(text); }
    public ReplyReference quoted(String text) {
        if (text == null || text.isEmpty()) return this;
        int count = text.codePointCount(0, text.length());
        if (count > 512) text = text.substring(0, text.offsetByCodePoints(0, 512)) + "…";
        String prefix = "> " + text.replace("\n", "\n> ") + "\n";
        return new ReplyReference(to, id, "0:" + prefix.codePointCount(0, prefix.length()), prefix,structured);
    }
    public String wireBody(String body) { return prefix + body; }
    public void addTo(Message message) {
        if(!structured) return;
        StandardExtensionElement.Builder reply = StandardExtensionElement.builder("reply", NAMESPACE).addAttribute("id", id);
        if (to != null) reply.addAttribute("to", to);
        message.addExtension(reply.build());
        if (!ranges.isEmpty()) {
            StandardExtensionElement.Builder fallback = StandardExtensionElement.builder("fallback", FALLBACK).addAttribute("for", NAMESPACE);
            for (String range : ranges.split(",")) {
                String[] ends = range.split(":");
                StandardExtensionElement.Builder body = StandardExtensionElement.builder("body", FALLBACK);
                if (!"*".equals(ends[1])) body.addAttribute("start", ends[0]).addAttribute("end", ends[1]);
                fallback.addElement(body.build());
            }
            message.addExtension(fallback.build());
        }
    }
    public static ReplyReference from(Message message, String body) {
        List<ExtensionElement> replies = message.getExtensions("reply", NAMESPACE);
        if (replies.size() != 1 || !(replies.get(0) instanceof StandardExtensionElement)) return null;
        StandardExtensionElement reply = (StandardExtensionElement) replies.get(0);
        String ranges = "";
        try {
            ReplyReference reference = new ReplyReference(reply.getAttributeValue("to"), reply.getAttributeValue("id"));
            List<int[]> parts = new ArrayList<>();
            int length = body == null ? -1 : body.codePointCount(0, body.length());
            for (ExtensionElement extension : message.getExtensions("fallback", FALLBACK)) {
                if (!(extension instanceof StandardExtensionElement)) continue;
                StandardExtensionElement fallback = (StandardExtensionElement) extension;
                if (!NAMESPACE.equals(fallback.getAttributeValue("for"))) continue;
                if (fallback.getElements().isEmpty()) { if (parts.size() >= 64) throw new IllegalArgumentException("Too many fallback regions"); parts.add(new int[]{0, length}); }
                for (StandardExtensionElement element : fallback.getElements()) {
                    if (!"body".equals(element.getElementName()) || !FALLBACK.equals(element.getNamespace())) continue;
                    // This adapter displays the default body, never remove another language's fallback.
                    if (element.getAttributeValue("xml:lang") != null) continue;
                    String start = element.getAttributeValue("start"), end = element.getAttributeValue("end");
                    int a = start == null && end == null ? 0 : Integer.parseInt(start);
                    int b = start == null && end == null ? length : Integer.parseInt(end);
                    if (end != null && b < 0) throw new IllegalArgumentException("Invalid fallback end");
                    if (a < 0 || b != -1 && (b < a || length >= 0 && b > length) || parts.size() >= 64) throw new IllegalArgumentException("Invalid fallback bounds");
                    parts.add(new int[]{a, b});
                }
            }
            parts.sort(Comparator.comparingInt(part -> part[0]));
            StringBuilder encoded = new StringBuilder();
            for (int[] part : parts) {
                if (encoded.length() > 0) encoded.append(',');
                encoded.append(part[0]).append(':').append(part[1] == -1 ? "*" : Integer.toString(part[1]));
            }
            ranges = encoded.toString();
            return new ReplyReference(reference.to, reference.id, ranges);
        } catch (RuntimeException malformed) {
            // A malformed fallback must not hide message text; retain a valid reply reference.
            try { return new ReplyReference(reply.getAttributeValue("to"), reply.getAttributeValue("id")); }
            catch (IllegalArgumentException invalid) { return null; }
        }
    }
    public String fallbackText(String body) {
        if (body == null || ranges.isEmpty()) return "";
        try {
            int length = body.codePointCount(0, body.length()), position = 0;
            StringBuilder result = new StringBuilder();
            for (String range : ranges.split(",")) {
                String[] ends = range.split(":"); int a = Integer.parseInt(ends[0]), b = "*".equals(ends[1]) ? length : Integer.parseInt(ends[1]);
                if (a < 0 || a > b || b > length) return "";
                a = Math.max(a, position);
                if (b > a) result.append(body, body.offsetByCodePoints(0, a), body.offsetByCodePoints(0, b));
                position = Math.max(position, b);
            }
            return result.toString();
        } catch (RuntimeException malformed) { return ""; }
    }
    public String withoutFallback(String body) {
        if (body == null || ranges.isEmpty()) return body;
        try {
            int length = body.codePointCount(0, body.length()), position = 0;
            StringBuilder result = new StringBuilder();
            for (String range : ranges.split(",")) {
                String[] ends = range.split(":"); int a = Integer.parseInt(ends[0]), b = "*".equals(ends[1]) ? length : Integer.parseInt(ends[1]);
                if (a < 0 || a > b || b > length) return body;
                if (a > position) result.append(body, body.offsetByCodePoints(0, position), body.offsetByCodePoints(0, a));
                position = Math.max(position, b);
            }
            return result.append(body.substring(body.offsetByCodePoints(0, position))).toString();
        } catch (RuntimeException malformed) { return body; }
    }
}
