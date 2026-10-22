// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.List;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.provider.ProviderManager;

/**
 * Shape-checked transport for Monogram's data-only checklist envelopes.
 *
 * <p>This class intentionally treats {@code data} as opaque. Callers must decode it with the Android
 * checklist converter only after receiving it in plaintext or an authenticated OMEMO2 payload; an
 * extension on an outer legacy-OMEMO stanza is not authenticated metadata.</p>
 */
public final class ChecklistContent {
    public static final String NAMESPACE = "urn:monogram:checklist:1";
    public static final String DOCUMENT = "checklist";
    public static final String UPDATE = "checklist-update";
    public static final int MAX_DATA = 65536;
    public static final int MAX_TARGET = 1024;

    public final String data;
    public final String target;
    public final boolean update;

    private ChecklistContent(String data, String target, boolean update) {
        this.data = data(data);
        this.target = update ? target(target) : null;
        this.update = update;
    }

    public static void initialize() {
        ProviderManager.addExtensionProvider(DOCUMENT, NAMESPACE, StandardExtensionElementProvider.INSTANCE);
        ProviderManager.addExtensionProvider(UPDATE, NAMESPACE, StandardExtensionElementProvider.INSTANCE);
    }

    public static ChecklistContent document(String encodedData) {
        return new ChecklistContent(encodedData, null, false);
    }

    public static ChecklistContent update(String targetReference, String encodedData) {
        return new ChecklistContent(encodedData, targetReference, true);
    }

    public void addTo(Message message) {
        if (message == null) throw new IllegalArgumentException("Missing message");
        StandardExtensionElement.Builder element = StandardExtensionElement.builder(update ? UPDATE : DOCUMENT, NAMESPACE)
                .addAttribute("data", data);
        if (update) element.addAttribute("target", target);
        message.addExtension(element.build());
    }

    /**
     * Returns null for an absent or malformed payload, including duplicate document/update
     * extensions. This validates XML shape only; it makes no claim that the extension is trusted.
     */
    public static ChecklistContent from(Message message) {
        if (message == null) return null;
        List<ExtensionElement> documents = message.getExtensions(DOCUMENT, NAMESPACE);
        List<ExtensionElement> updates = message.getExtensions(UPDATE, NAMESPACE);
        if (documents.size() + updates.size() != 1) return null;
        boolean update = updates.size() == 1;
        ExtensionElement extension = update ? updates.get(0) : documents.get(0);
        if (!(extension instanceof StandardExtensionElement)) return null;
        StandardExtensionElement element = (StandardExtensionElement) extension;
        if (!element.getElements().isEmpty() || !empty(element.getText())) return null;
        if (update) {
            if (element.getAttributes().size() != 2) return null;
            try { return update(element.getAttributeValue("target"), element.getAttributeValue("data")); }
            catch (IllegalArgumentException invalid) { return null; }
        }
        if (element.getAttributes().size() != 1) return null;
        try { return document(element.getAttributeValue("data")); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }

    private static String data(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_DATA) throw new IllegalArgumentException("Invalid checklist data");
        int padding = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean base64 = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_';
            if (base64) {
                if (padding != 0) throw new IllegalArgumentException("Invalid checklist data");
                continue;
            }
            if (c == '=' && ++padding <= 2 && i >= value.length() - 2) continue;
            throw new IllegalArgumentException("Invalid checklist data");
        }
        return value;
    }

    private static String target(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TARGET) throw new IllegalArgumentException("Invalid checklist target");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e || c == '\'' || c == '"' || c == '<' || c == '>' || c == '&')
                throw new IllegalArgumentException("Invalid checklist target");
        }
        return value;
    }
}
