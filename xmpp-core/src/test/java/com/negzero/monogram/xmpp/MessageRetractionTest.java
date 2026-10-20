package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageRetractionTest {
    private Message wire(String payload) throws Exception {
        MessageRetraction.initialize();
        return PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='bob@example.org/phone' to='alice@example.org' type='chat' id='event'>"+payload+"</message>");
    }
    @Test void bothFormatsParseAndFallbackNeverBecomesMessageContent() throws Exception {
        for(boolean legacy:new boolean[]{false,true}) {
            Message message=wire(""); new MessageRetraction("original",legacy).addTo(message);
            message=PacketParserUtils.parseStanza(message.toXML().toString());
            MessageRetraction request=MessageRetraction.from(message);
            assertEquals("original",request.id); assertEquals(legacy,request.legacy);
            XmppMessage model=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),"Attacker-controlled fallback",false,true,null,null);
            assertNotNull(model.retraction); assertNull(model.body); assertNull(model.attachment);
        }
        for(String payload:new String[]{"<retract xmlns='urn:xmpp:message-retract:1'/>",
                "<retract xmlns='urn:xmpp:message-retract:1' id='target'><extra/></retract>",
                "<retract xmlns='urn:xmpp:message-retract:1' id='target'/><apply-to xmlns='urn:xmpp:fasten:0' id='other'><retract xmlns='urn:xmpp:message-retract:0'/></apply-to>"}) {
            Message malformed=wire(payload);
            assertNull(XmppMessage.from(malformed,JidCreate.entityBareFrom("alice@example.org"),"Do not display",false,true,null,null));
        }
    }
    @Test void protectedRetractionCannotBeReplacedByOuterMetadata() throws Exception {
        Message clear=wire(""); new MessageRetraction("original",false).addTo(clear);
        clear.removeExtension("store","urn:xmpp:hints");
        Message authenticated=Omemo2Payload.content(clear,Omemo2Payload.envelope(clear,new java.security.SecureRandom()));
        Message outer=wire(""); new MessageRetraction("forged",false).addTo(outer);
        XmppMessage model=XmppMessage.modern(outer,authenticated,JidCreate.entityBareFrom("alice@example.org"),null,null);
        assertEquals("original",model.retraction.id); assertNull(model.body);
        XmppMessage legacy=XmppMessage.from(outer,JidCreate.entityBareFrom("alice@example.org"),"Legacy encrypted body",true,true,null,null);
        assertNotNull(legacy.retraction); assertNull(legacy.body);
        Message encrypted=wire("<body>Encrypted fallback</body>");
        new MessageRetraction("original",true).addMetadataTo(encrypted);
        assertEquals("Encrypted fallback",encrypted.getBody());
    }
}
