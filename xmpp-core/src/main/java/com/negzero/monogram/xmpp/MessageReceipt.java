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
    private static boolean valid(String id) { return id != null && !id.isEmpty() && id.length() <= 4096; }
    private static String id(ExtensionElement extension) {
        if (extension instanceof DeliveryReceipt) return ((DeliveryReceipt) extension).getId();
        if (extension instanceof DisplayedExtension) return ((DisplayedExtension) extension).getId();
        return extension instanceof StandardExtensionElement ? ((StandardExtensionElement) extension).getAttributeValue("id") : null;
    }
}
