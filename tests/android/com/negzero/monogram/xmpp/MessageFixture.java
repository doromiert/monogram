package com.negzero.monogram.xmpp;

import java.util.Date;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.impl.JidCreate;

public final class MessageFixture {
    public static XmppSession.ArchivePage page(XmppMessage... messages) {
        return new XmppSession.ArchivePage(java.util.Arrays.asList(messages), messages[0].ids.get("archive"),
                messages[messages.length - 1].ids.get("archive"), true);
    }
    public static XmppMessage message(String stanzaId, String originId, String archiveId, String body,
                                      boolean readable, boolean outgoing, long time) throws Exception {
        return messageFrom(stanzaId, originId, archiveId, body, readable, outgoing, time, null);
    }
    public static XmppMessage messageFrom(String stanzaId, String originId, String archiveId, String body,
                                         boolean readable, boolean outgoing, long time, String resource) throws Exception {
        Message stanza = new Message(JidCreate.entityBareFrom(outgoing ? "bob@example.org" : "alice@example.org"), Message.Type.chat);
        String from = outgoing ? "alice@example.org" : "bob@example.org";
        stanza.setFrom(resource == null ? JidCreate.entityBareFrom(from) : JidCreate.entityFullFrom(from + "/" + resource));
        stanza.setStanzaId(stanzaId);
        if (originId != null) stanza.addExtension(new OriginIdElement(originId));
        return XmppMessage.from(stanza, JidCreate.entityBareFrom("alice@example.org"), body, true, readable, archiveId, new Date(time));
    }
}
