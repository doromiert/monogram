package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageCorrectionTest {
    private Message stanza(String extension) throws Exception {
        MessageCorrection.initialize();
        return PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='bob@example.org/phone' to='alice@example.org' type='chat' id='edit-event'><body>Corrected 😀</body>" + extension + "</message>");
    }
    @Test void wireRoundTripAndUnreadableArchiveKeepSeparateIdentity() throws Exception {
        Message message = stanza(""); new MessageCorrection("original&lt;&").addTo(message);
        Message parsed = PacketParserUtils.parseStanza(message.toXML().toString());
        assertEquals("original&lt;&", MessageCorrection.from(parsed).id);
        XmppMessage event = XmppMessage.from(parsed, JidCreate.entityBareFrom("alice@example.org"), null, true, false, "archive-event", new java.util.Date(200000));
        assertEquals("original&lt;&", event.correction.id);
        assertEquals("edit-event", event.ids.get("stanza")); assertEquals("archive-event", event.ids.get("archive"));
        assertFalse(event.readable); assertEquals(200000, event.timestamp);
        XmppMessage sent = XmppMessage.sent(JidCreate.entityFullFrom("alice@example.org/phone"), "bob@example.org", "new-event", "Edited", true,
                new ReplyReference("bob@example.org", "reply-original"), new MessageCorrection("first-event"));
        assertEquals("first-event", sent.correction.id); assertEquals("new-event", sent.ids.get("stanza"));
        assertEquals("reply-original", sent.reply.id);
        Message roomPrivate = stanza("<x xmlns='http://jabber.org/protocol/muc#user'/><replace xmlns='urn:xmpp:message-correct:0' id='original'/>");
        assertNull(XmppMessage.from(roomPrivate, JidCreate.entityBareFrom("alice@example.org"), roomPrivate.getBody(), false, true, null, null),
                "Room private messages must not use direct-chat bare-author corrections");
    }
    @Test void malformedCorrectionsCannotSelectAnOriginal() throws Exception {
        String valid = "<replace xmlns='urn:xmpp:message-correct:0' id='original'/>";
        for (String extension : new String[]{"", "<replace xmlns='wrong' id='original'/>", valid + valid,
                "<replace xmlns='urn:xmpp:message-correct:0'/>", "<replace xmlns='urn:xmpp:message-correct:0' id=''/>",
                "<replace xmlns='urn:xmpp:message-correct:0' id='original'><payload/></replace>",
                "<replace xmlns='urn:xmpp:message-correct:0' id='original'>unexpected</replace>"}) {
            assertNull(MessageCorrection.from(stanza(extension)), extension);
        }
        assertThrows(IllegalArgumentException.class, () -> new MessageCorrection(null));
        assertThrows(IllegalArgumentException.class, () -> new MessageCorrection(new String(new char[4097]).replace('\0', 'a')));
    }
}
