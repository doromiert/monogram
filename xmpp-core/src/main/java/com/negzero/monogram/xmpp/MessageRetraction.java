// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.List;
import org.jivesoftware.smack.packet.*;

/** XEP-0424 requests carry references, never replacement message content. */
public final class MessageRetraction {
    public static final String NAMESPACE="urn:xmpp:message-retract:1", LEGACY="urn:xmpp:message-retract:0", FASTEN="urn:xmpp:fasten:0";
    public final String id;
    public final boolean legacy;
    public MessageRetraction(String id,boolean legacy) {
        if(id==null || id.isEmpty() || id.length()>4096) throw new IllegalArgumentException("Invalid retraction reference");
        this.id=id; this.legacy=legacy;
    }
    public static void initialize() {
        for(String ns:new String[]{NAMESPACE,LEGACY}) org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider(
                "retract",ns,org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("apply-to",FASTEN,org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
    }
    public void addTo(Message message) {
        StandardExtensionElement retract=legacy ? StandardExtensionElement.builder("retract",LEGACY).build()
                : StandardExtensionElement.builder("retract",NAMESPACE).addAttribute("id",id).build();
        message.addExtension(legacy ? StandardExtensionElement.builder("apply-to",FASTEN).addAttribute("id",id).addElement(retract).build() : retract);
        message.setBody("A previous message was retracted.");
        message.addExtension(StandardExtensionElement.builder("fallback",ReplyReference.FALLBACK).addAttribute("for",legacy ? LEGACY : NAMESPACE).build());
        message.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build());
    }
    public static MessageRetraction from(Message message) {
        List<ExtensionElement> current=message.getExtensions("retract",NAMESPACE);
        List<ExtensionElement> fastened=message.getExtensions("apply-to",FASTEN);
        StandardExtensionElement old=null;
        for(ExtensionElement element:fastened) if(element instanceof StandardExtensionElement
                && ((StandardExtensionElement)element).getFirstElement("retract",LEGACY)!=null) {
            if(old!=null) throw new IllegalArgumentException("Multiple retractions");
            old=(StandardExtensionElement)element;
        }
        if(!current.isEmpty()) {
            if(current.size()!=1 || old!=null || !(current.get(0) instanceof StandardExtensionElement)) throw new IllegalArgumentException("Ambiguous retraction");
            StandardExtensionElement element=(StandardExtensionElement)current.get(0);
            empty(element);
            return new MessageRetraction(element.getAttributeValue("id"),false);
        }
        if(old==null) return null;
        if(old.getElements().size()!=1) throw new IllegalArgumentException("Mixed fastening payload");
        empty(old.getFirstElement("retract",LEGACY));
        return new MessageRetraction(old.getAttributeValue("id"),true);
    }
    private static void empty(StandardExtensionElement element) {
        if(!element.getElements().isEmpty() || element.getText()!=null && !element.getText().trim().isEmpty())
            throw new IllegalArgumentException("Retraction contains unexpected content");
    }
}
