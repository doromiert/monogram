// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.Jid;

/** A live stanza's request, retained only until its readable message is stored. */
final class DeliveryAcknowledgement {
    final Jid sender;
    final EntityBareJid own,peer;
    final String id;
    final Message.Type type;
    private DeliveryAcknowledgement(Message stanza,EntityBareJid own,EntityBareJid peer) {
        sender=stanza.getFrom();this.own=own;this.peer=peer;id=stanza.getStanzaId();type=stanza.getType();
    }
    static DeliveryAcknowledgement from(Message stanza,EntityBareJid own) {
        if(own==null || stanza.getFrom()==null || !MessageReceipt.valid(stanza.getStanzaId())
                || stanza.getType()!=Message.Type.chat && stanza.getType()!=Message.Type.normal
                || stanza.hasExtension("x","http://jabber.org/protocol/muc#user")) return null;
        EntityBareJid peer=stanza.getFrom().asEntityBareJidIfPossible();
        if(peer==null || own.equals(peer) || stanza.getTo()!=null && !own.equals(stanza.getTo().asEntityBareJidIfPossible())) return null;
        java.util.List<ExtensionElement> requests=stanza.getExtensions("request",DeliveryReceipt.NAMESPACE);
        if(requests.size()!=1) return null;
        for(ExtensionElement extension:stanza.getExtensions()) {
            String namespace=extension.getNamespace();
            if(DeliveryReceipt.NAMESPACE.equals(namespace) && !"request".equals(extension.getElementName())
                    || MessageReceipt.MARKERS.equals(namespace) && !"markable".equals(extension.getElementName())
                    || "urn:xmpp:carbons:2".equals(namespace) || "urn:xmpp:forward:0".equals(namespace)
                    || "urn:xmpp:mam:2".equals(namespace)) return null;
        }
        ExtensionElement request=requests.get(0);
        if(request instanceof StandardExtensionElement) {
            StandardExtensionElement element=(StandardExtensionElement)request;
            if(!element.getAttributes().isEmpty() || !element.getElements().isEmpty()
                    || element.getText()!=null && !element.getText().trim().isEmpty()) return null;
        } else if(!(request instanceof DeliveryReceiptRequest)) return null;
        return new DeliveryAcknowledgement(stanza,own,peer);
    }
    boolean accepts(XmppMessage message) {
        return message!=null && message.readable && !message.outgoing && !message.archived
                && message.reactions==null && message.retraction==null && peer.equals(message.peer);
    }
    Message response() {
        Message response=new Message(sender,type);
        response.addExtension(new DeliveryReceipt(id));return response;
    }
}
