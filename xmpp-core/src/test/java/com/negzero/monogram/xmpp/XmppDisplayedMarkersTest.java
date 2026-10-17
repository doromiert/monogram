package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppDisplayedMarkersTest {
    final EntityBareJid own=JidCreate.entityBareFrom("alice@example.org");
    XmppDisplayedMarkersTest() throws Exception {}
    Message message() throws Exception {
        Message message=new Message(own,Message.Type.chat);message.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));
        message.setStanzaId("original-stanza-id");message.setBody("message");
        message.addExtension(StandardExtensionElement.builder("markable",MessageReceipt.MARKERS).build());return message;
    }
    @Test void onlyOriginalIncomingStanzaIdBecomesMarkableMetadataAndSurvivesClones() throws Exception {
        Message message=message();message.addExtension(new OriginIdElement("different-origin"));
        XmppMessage plain=XmppMessage.from(message,own,message.getBody(),false,true,"different-archive",null);
        assertEquals("original-stanza-id",plain.markableId);
        assertEquals(plain.markableId,plain.withSenderFingerprint("fingerprint").markableId);
        assertEquals(plain.markableId,XmppMessage.from(message,own,null,true,false,null,null).markableId);
        assertEquals(plain.markableId,XmppMessage.modern(message,null,own,null,null).markableId);
        Message protectedContent=new Message(own,Message.Type.chat);protectedContent.setFrom(message.getFrom());protectedContent.setBody("protected");
        assertEquals(plain.markableId,XmppMessage.modern(message,protectedContent,own,null,null).withSenderFingerprint("modern").markableId);
        message.setFrom(own);message.setTo(JidCreate.entityBareFrom("bob@example.org"));
        assertNull(XmppMessage.from(message,own,message.getBody(),false,true,null,null).markableId);
    }
    @Test void opaqueOriginAndArchiveFallbacksCannotBecomeMarkerReferences() throws Exception {
        Message message=message();message.setStanzaId(null);message.addExtension(new OriginIdElement("origin-only"));
        XmppMessage encrypted=XmppMessage.from(message,own,null,true,false,"archive-only",null);
        assertNull(encrypted.markableId);assertEquals("origin-only",encrypted.ids.get("origin"));
        message.removeExtension("origin-id","urn:xmpp:sid:0");
        encrypted=XmppMessage.from(message,own,null,true,false,null,null);
        assertNotNull(encrypted.ids.get("local"));assertNull(encrypted.markableId);
    }
    @Test void malformedRequestsAndFeedbackNeverRequestAnotherMarker() throws Exception {
        for(int invalid=0;invalid<6;invalid++) {
            Message message=message();
            if(invalid==0) message.addExtension(StandardExtensionElement.builder("markable",MessageReceipt.MARKERS).build());
            if(invalid==1) message.addExtension(StandardExtensionElement.builder("displayed",MessageReceipt.MARKERS).addAttribute("id","previous").build());
            if(invalid==2) message.addExtension(StandardExtensionElement.builder("received","urn:xmpp:receipts").addAttribute("id","previous").build());
            if(invalid==3) { message.removeExtension("markable",MessageReceipt.MARKERS);message.addExtension(StandardExtensionElement.builder("markable",MessageReceipt.MARKERS).addAttribute("id","forged").build()); }
            if(invalid==4) { message.removeExtension("markable",MessageReceipt.MARKERS);message.addExtension(StandardExtensionElement.builder("markable",MessageReceipt.MARKERS).setText("payload").build()); }
            if(invalid==5) message.setStanzaId(" ");
            assertNull(XmppMessage.from(message,own,message.getBody(),false,true,null,null).markableId,"Invalid variant "+invalid);
        }
    }
    @Test void invalidReferencesCannotReachXml() {
        for(String invalid:new String[]{null,""," ","bad\u0000id","broken\uD800",new String(new char[4097]).replace('\0','x')}) assertFalse(MessageReceipt.valid(invalid));
        assertTrue(MessageReceipt.valid("opaque-id"));assertTrue(MessageReceipt.valid("emoji-\uD83D\uDE00"));
    }
}
