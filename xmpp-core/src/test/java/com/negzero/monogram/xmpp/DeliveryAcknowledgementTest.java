package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryAcknowledgementTest {
    final EntityBareJid own=JidCreate.entityBareFromOrThrowUnchecked("alice@example.org");
    Message message() throws Exception {
        Message message=new Message(own,Message.Type.chat);message.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));
        message.setStanzaId("exact-original");message.setBody("Stored body");message.addExtension(new DeliveryReceiptRequest());return message;
    }
    @Test void preservesExactStanzaIdFullResourceAndTypeWithoutFeedback() throws Exception {
        Message message=message();message.setType(Message.Type.normal);message.addExtension(new OriginIdElement("not-this-origin"));
        DeliveryAcknowledgement candidate=DeliveryAcknowledgement.from(message,own);assertNotNull(candidate);
        Message response=candidate.response();assertEquals(message.getFrom(),response.getTo());assertEquals(Message.Type.normal,response.getType());
        assertEquals("exact-original",DeliveryReceipt.from(response).getId());assertNull(response.getBody());assertEquals(1,response.getExtensions().size());
        assertTrue(candidate.accepts(XmppMessage.from(message,own,message.getBody(),false,true,null,null)));
        assertFalse(candidate.accepts(XmppMessage.from(message,own,null,true,false,null,null)));
        assertFalse(candidate.accepts(XmppMessage.from(message,own,message.getBody(),false,true,"mam-id",null)));
    }
    @Test void malformedRequestsFeedbackAndNonDirectRoutesNeverQualify() throws Exception {
        for(int variant=0;variant<13;variant++) {
            Message message=message();
            switch(variant) {
                case 0: message.setStanzaId(null);break;
                case 1: message.setStanzaId("bad\u0000id");break;
                case 2: message.addExtension(new DeliveryReceiptRequest());break;
                case 3: message.removeExtension("request",DeliveryReceipt.NAMESPACE);message.addExtension(StandardExtensionElement.builder("request",DeliveryReceipt.NAMESPACE).addAttribute("id","forged").build());break;
                case 4: message.removeExtension("request",DeliveryReceipt.NAMESPACE);message.addExtension(StandardExtensionElement.builder("request",DeliveryReceipt.NAMESPACE).setText("payload").build());break;
                case 5: message.addExtension(new DeliveryReceipt("previous"));break;
                case 6: message.addExtension(StandardExtensionElement.builder("displayed",MessageReceipt.MARKERS).addAttribute("id","previous").build());break;
                case 7: message.setType(Message.Type.groupchat);break;
                case 8: message.setFrom(own);break;
                case 9: message.setTo(JidCreate.entityBareFrom("other@example.org"));break;
                case 10: message.addExtension(StandardExtensionElement.builder("x","http://jabber.org/protocol/muc#user").build());break;
                case 11: message.addExtension(StandardExtensionElement.builder("received","urn:xmpp:carbons:2").build());break;
                case 12: message.addExtension(StandardExtensionElement.builder("result","urn:xmpp:mam:2").build());break;
            }
            assertNull(DeliveryAcknowledgement.from(message,own),"Variant "+variant);
        }
    }
    @Test void readableControlEventsAreNotStoredUserMessagesForReceipts() throws Exception {
        Message message=message();new MessageReactions("previous",java.util.Collections.singleton("👍")).addTo(message);
        message.setBody(null);
        XmppMessage reaction=XmppMessage.from(message,own,null,false,true,null,null);assertNotNull(reaction);
        assertFalse(DeliveryAcknowledgement.from(message,own).accepts(reaction));
        message=message();message.addExtension(StandardExtensionElement.builder("retract",MessageRetraction.NAMESPACE).addAttribute("id","previous").build());
        XmppMessage retraction=XmppMessage.from(message,own,message.getBody(),false,true,null,null);assertNotNull(retraction);
        assertFalse(DeliveryAcknowledgement.from(message,own).accepts(retraction));
    }
}
