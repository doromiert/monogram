// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.List;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;

/** XEP-0308 references the original client stanza ID, including on repeated edits. */
public final class MessageCorrection {
    public static final String NAMESPACE = "urn:xmpp:message-correct:0";
    public final String id;
    public MessageCorrection(String id) {
        if (id == null || id.isEmpty() || id.length() > 4096) throw new IllegalArgumentException("Missing correction ID");
        this.id = id;
    }
    public static void initialize() {
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("replace", NAMESPACE,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
    }
    public void addTo(Message message) {
        message.addExtension(StandardExtensionElement.builder("replace", NAMESPACE).addAttribute("id", id).build());
    }
    public static MessageCorrection from(Message message) {
        List<ExtensionElement> replacements = message.getExtensions("replace", NAMESPACE);
        if (replacements.size() != 1 || !(replacements.get(0) instanceof StandardExtensionElement)) return null;
        StandardExtensionElement replacement = (StandardExtensionElement) replacements.get(0);
        if (!replacement.getElements().isEmpty() || replacement.getText() != null && !replacement.getText().trim().isEmpty()) return null;
        try { return new MessageCorrection(replacement.getAttributeValue("id")); }
        catch (IllegalArgumentException malformed) { return null; }
    }
}
