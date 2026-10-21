package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;

class ArticleMarkupTest {
    Message message(String body) throws Exception { Message m=new Message(JidCreate.entityBareFrom("bob@example.org"),Message.Type.chat);m.setFrom(JidCreate.entityFullFrom("alice@example.org/phone"));m.setBody(body);return m; }
    @Test void wireAndPersistenceKeepMarkdownAndReplyOffsets() throws Exception {
        MessageMarkup.initialize();String body="# Heading\n\n| A | B |\n| --- | --- |\n| 😀 | 2 |";
        MessageMarkup markup=MessageMarkup.body(body).asMarkdown();Message m=message(body);markup.addTo(m);
        Message parsed=PacketParserUtils.parseMessage(PacketParserUtils.getParserFor(m.toXML().toString()));
        MessageMarkup received=MessageMarkup.from(parsed,body);assertTrue(received.markdown);assertEquals(body,received.display().body);
        assertTrue(MessageMarkup.decode(body,received.encode()).markdown);
        MessageMarkup prefixed=received.inBody("> quote\n\n"+body,9);assertTrue(prefixed.markdown);assertTrue(prefixed.slice(9,9+body.codePointCount(0,body.length())).markdown);
        assertFalse(MessageMarkup.from(parsed,"different body").markdown);
    }
    @Test void omemo2EnvelopeRetainsContentHintAndLegacyHintCannotReplaceDecryptedBody() throws Exception {
        MessageMarkup.initialize();String body="| secret |\n| --- |\n| 42 |";Message clear=message(body);MessageMarkup.body(body).asMarkdown().addTo(clear);
        byte[] content=Omemo2Payload.envelope(clear,new java.security.SecureRandom());Message decoded=Omemo2Payload.content(clear,content);
        assertTrue(MessageMarkup.from(decoded,body).markdown);
        Message outer=message("encrypted fallback");MessageMarkup.body(body).asMarkdown().addContentHint(outer);
        assertTrue(MessageMarkup.legacy(outer,body).markdown);assertEquals(body,MessageMarkup.legacy(outer,body).body);
        outer.removeExtension("content",MessageMarkup.CONTENT);
        outer.addExtension(StandardExtensionElement.builder("content",MessageMarkup.CONTENT).addAttribute("type","text/markdown").setText("replacement").build());
        assertFalse(MessageMarkup.legacy(outer,body).markdown);assertEquals(body,MessageMarkup.legacy(outer,body).body);
    }
}
