// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements.DisplayedExtension;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jxmpp.jid.EntityBareJid;

/** Direct-chat delivery and displayed notifications; delivery never implies display. */
public final class MessageReceipt {
    public static final String MARKERS = "urn:xmpp:chat-markers:0";
    public final EntityBareJid peer;
    public final String id;
    public final boolean displayed;
    private MessageReceipt(EntityBareJid peer, String id, boolean displayed) {
        this.peer = peer; this.id = id; this.displayed = displayed;
    }
    public static java.util.List<MessageReceipt> from(Message message, EntityBareJid own) {
        java.util.List<MessageReceipt> result = new java.util.ArrayList<>();
        if (own == null || message.getFrom() == null || message.getType() == Message.Type.error
                || message.getType() == Message.Type.groupchat || message.hasExtension("x", "http://jabber.org/protocol/muc#user")) return result;
        EntityBareJid peer = message.getFrom().asEntityBareJidIfPossible();
        if (peer == null || own.equals(peer) || message.getTo() != null && !own.equals(message.getTo().asEntityBareJidIfPossible())) return result;
        java.util.List<ExtensionElement> delivered = message.getExtensions("received", "urn:xmpp:receipts");
        java.util.List<ExtensionElement> displayed = message.getExtensions("displayed", MARKERS);
        String receivedId = delivered.size() == 1 ? id(delivered.get(0)) : null;
        String displayedId = displayed.size() == 1 ? id(displayed.get(0)) : null;
        if (valid(receivedId)) result.add(new MessageReceipt(peer, receivedId, false));
        if (valid(displayedId)) result.add(new MessageReceipt(peer, displayedId, true));
        return result;
    }
    static boolean valid(String id) {
        if(id==null || id.trim().isEmpty() || id.length()>4096) return false;
        for(int offset=0;offset<id.length();) {
            int point=id.codePointAt(offset);offset+=Character.charCount(point);
            if(point!=9 && point!=10 && point!=13 && (point<32 || point>=0xD800 && point<=0xDFFF
                    || point==0xFFFE || point==0xFFFF || point>0x10FFFF)) return false;
        }
        return true;
    }
    /** Direct markers reference the original stanza attribute, never an origin, archive or local ID. */
    static String markableId(Message message) {
        if(message==null || message.getType()!=Message.Type.chat && message.getType()!=Message.Type.normal
                || !valid(message.getStanzaId()) || message.hasExtension("x","http://jabber.org/protocol/muc#user")
                || message.hasExtension("received","urn:xmpp:receipts")) return null;
        java.util.List<ExtensionElement> requests=message.getExtensions("markable",MARKERS);
        if(requests.size()!=1) return null;
        for(ExtensionElement extension:message.getExtensions())
            if(MARKERS.equals(extension.getNamespace()) && !"markable".equals(extension.getElementName())) return null;
        ExtensionElement request=requests.get(0);
        if(request instanceof StandardExtensionElement) {
            StandardExtensionElement value=(StandardExtensionElement)request;
            if(!value.getAttributes().isEmpty() || !value.getElements().isEmpty()
                    || value.getText()!=null && !value.getText().trim().isEmpty()) return null;
        } else if(!(request instanceof org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements.MarkableExtension)) return null;
        return message.getStanzaId();
    }
    private static String id(ExtensionElement extension) {
        if (extension instanceof DeliveryReceipt) return ((DeliveryReceipt) extension).getId();
        if (extension instanceof DisplayedExtension) return ((DisplayedExtension) extension).getId();
        return extension instanceof StandardExtensionElement ? ((StandardExtensionElement) extension).getAttributeValue("id") : null;
    }
}
