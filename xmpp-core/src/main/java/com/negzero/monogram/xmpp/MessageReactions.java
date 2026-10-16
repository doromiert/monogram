// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;

/** XEP-0444: each update replaces this sender's complete set, including an empty set. */
public final class MessageReactions {
    public static final String NAMESPACE="urn:xmpp:reactions:0";
    public final String id;
    public final Set<String> emojis;
    public MessageReactions(String id,Collection<String> emojis) {
        if(id==null || id.isEmpty() || id.length()>4096 || emojis==null || emojis.size()>32)
            throw new IllegalArgumentException("Invalid reaction reference");
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(String emoji:emojis) {
            if(emoji==null || emoji.isEmpty() || emoji.length()>128 || emoji.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
                throw new IllegalArgumentException("Invalid reaction");
            unique.add(emoji);
        }
        this.id=id; this.emojis=Collections.unmodifiableSet(unique);
    }
    public static void initialize() {
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("reactions",NAMESPACE,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
    }
    public void addTo(Message message) {
        StandardExtensionElement.Builder element=StandardExtensionElement.builder("reactions",NAMESPACE).addAttribute("id",id);
        for(String emoji:emojis) element.addElement("reaction",emoji);
        message.addExtension(element.build());
    }
    public static MessageReactions from(Message message) {
        List<ExtensionElement> found=message.getExtensions("reactions",NAMESPACE);
        if(found.size()!=1 || !(found.get(0) instanceof StandardExtensionElement)) return null;
        StandardExtensionElement element=(StandardExtensionElement)found.get(0);
        if(element.getText()!=null && !element.getText().trim().isEmpty() || element.getElements().size()>32) return null;
        List<String> emojis=new ArrayList<>();
        for(StandardExtensionElement child:element.getElements()) {
            if(!"reaction".equals(child.getElementName()) || !NAMESPACE.equals(child.getNamespace()) || !child.getElements().isEmpty()) return null;
            emojis.add(child.getText());
        }
        try { return new MessageReactions(element.getAttributeValue("id"),emojis); }
        catch(IllegalArgumentException invalid) { return null; }
    }
}
