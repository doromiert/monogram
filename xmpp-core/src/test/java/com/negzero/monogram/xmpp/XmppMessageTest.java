package com.negzero.monogram.xmpp;

import java.util.Date;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppMessageTest {
    @Test void directionIdsDelayAndArchiveAuthority() throws Exception {
        EntityBareJid own = JidCreate.entityBareFrom("alice@example.org");
        Message stanza = new Message(own, Message.Type.chat);
        stanza.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));
        stanza.setStanzaId("client-id"); stanza.setBody("hello");
        stanza.addExtension(new OriginIdElement("origin-id"));
        stanza.addExtension(new StanzaIdElement("forged", "mallory@example.org"));
        stanza.addExtension(new StanzaIdElement("server-id", own.toString()));
        stanza.addExtension(new DelayInformation(new Date(1234000)));
        XmppMessage live = XmppMessage.from(stanza, own, "hello", false, true, null, null);
        assertEquals("bob@example.org", live.peer.toString());
        assertFalse(live.outgoing); assertFalse(live.archived);
        assertEquals(1234000, live.timestamp);
        assertEquals("client-id", live.ids.get("stanza"));
        assertEquals("origin-id", live.ids.get("origin"));
        assertEquals("server-id", live.ids.get("archive"));
        assertThrows(UnsupportedOperationException.class, () -> live.ids.put("archive", "changed"));
        XmppMessage archive = XmppMessage.from(stanza, own, null, true, false, "mam-id", new Date(2345000));
        assertTrue(archive.encrypted); assertFalse(archive.readable); assertNull(archive.body);
        assertTrue(archive.archived); assertEquals(2345000, archive.timestamp);
        assertEquals("mam-id", archive.ids.get("archive"));
        XmppMessage sent = XmppMessage.sent(own, "bob@example.org", "sent-id", "outgoing", true);
        assertTrue(sent.outgoing); assertEquals("sent-id", sent.ids.get("origin"));
        String fileUrl = "aesgcm://example.org/photo.jpg#" + new String(new char[88]).replace('\0', 'a');
        assertNotNull(XmppMessage.from(stanza, own, fileUrl, true, true, null, null).attachment);
        assertNotNull(XmppMessage.from(stanza, own, fileUrl + "\ndata:image/jpeg;base64,/9j/2Q==", true, true, null, null).attachment);
        assertNull(XmppMessage.from(stanza, own, fileUrl + "\ndata:image/jpeg,not-a-thumbnail", true, true, null, null).attachment);
        assertNull(XmppMessage.from(stanza, own, fileUrl + "\nextra text", true, true, null, null).attachment);
        assertNull(XmppMessage.from(stanza, own, fileUrl, false, true, null, null).attachment);
        stanza.setTo((org.jxmpp.jid.Jid) null);
        assertNotNull(XmppMessage.from(stanza, own, "delivered without to", false, true, null, null));
        stanza.setType(Message.Type.groupchat);
        assertNull(XmppMessage.from(stanza, own, "room", false, true, null, null));
        stanza.setType(Message.Type.chat); stanza.setTo(JidCreate.entityBareFrom("mallory@example.org"));
        assertNull(XmppMessage.from(stanza, own, "unrelated", false, true, null, null));
    }
}
