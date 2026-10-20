package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplyReferenceTest {
    @org.junit.jupiter.api.BeforeAll static void providers() { ReplyReference.initialize(); }
    private Message stanza(String extra) throws Exception {
        return PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='bob@example.org/phone' to='alice@example.org' type='chat' id='reply-stanza'><body>😀abcDEF</body>" + extra + "</message>");
    }
    private ReplyReference parsed(String extra) throws Exception { Message m = stanza(extra); return ReplyReference.from(m, m.getBody()); }
    private final String reference = "<reply xmlns='urn:xmpp:reply:0' to='bob@example.org/phone' id='original'/ >".replace("/ >", "/>");
    @Test void wireRoundTripUsesUnicodeCodePoints() throws Exception {
        ReplyReference outgoing = new ReplyReference("bob@example.org/phone", "original").quoted("😀 first\nsecond 🦄");
        Message message = stanza(""); message.setBody(outgoing.wireBody("answer 🐈")); outgoing.addTo(message);
        Message wire = PacketParserUtils.parseStanza(message.toXML().toString());
        ReplyReference incoming = ReplyReference.from(wire, wire.getBody());
        assertNotNull(incoming); assertEquals("bob@example.org/phone", incoming.to); assertEquals("original", incoming.id);
        assertEquals("answer 🐈", incoming.withoutFallback(wire.getBody()));
        assertEquals("> 😀 first\n> second 🦄\n", incoming.fallbackText(wire.getBody()));
        assertFalse(incoming.ranges.equals("0:" + (wire.getBody().length() - "answer 🐈".length())));
    }
    @Test void multipleOverlappingAndWholeBodyFallbacks() throws Exception {
        ReplyReference parts = parsed(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'><body start='0' end='2'/><body start='4' end='6'/></fallback>");
        assertEquals("bcF", parts.withoutFallback("😀abcDEF")); assertEquals("😀aDE", parts.fallbackText("😀abcDEF"));
        ReplyReference overlap = parsed(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'><body start='2' end='4'/><body start='0' end='3'/></fallback>");
        assertEquals("DEF", overlap.withoutFallback("😀abcDEF")); assertEquals("😀abc", overlap.fallbackText("😀abcDEF"));
        assertEquals("", parsed(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'/>").withoutFallback("😀abcDEF"));
        assertEquals("", parsed(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'><body/></fallback>").withoutFallback("😀abcDEF"));
    }
    @Test void malformedFallbacksNeverHideContent() throws Exception {
        for (String attrs : new String[]{"start='-1' end='2'", "start='0' end='999'", "start='2' end='1'", "start='0'", "end='1'", "start='0' end='99999999999999999999'"}) {
            ReplyReference bad = parsed(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'><body " + attrs + "/></fallback>");
            assertNotNull(bad); assertEquals("😀abcDEF", bad.withoutFallback("😀abcDEF"));
        }
        assertEquals("😀abcDEF", parsed(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='another:protocol'><body/></fallback>").withoutFallback("😀abcDEF"));
        assertNull(parsed("<reply xmlns='urn:xmpp:reply:0'/>"));
        assertNull(parsed("<reply xmlns='urn:xmpp:reply:0' to='example.org' id='bad'/>"));
        assertNull(parsed(reference + reference));
        assertNotNull(parsed("<reply xmlns='urn:xmpp:reply:0' id='no-author'/ >".replace("/ >", "/>")));
    }
    @Test void encryptedReplayCanRecoverMetadataUsingAlreadyKnownPlaintext() throws Exception {
        Message message = stanza(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'><body start='0' end='4'/></fallback>");
        ReplyReference replay = ReplyReference.from(message, null);
        assertNotNull(replay); assertEquals("DEF", replay.withoutFallback("😀abcDEF"));
        assertEquals("Hi", replay.withoutFallback("Hi"));
        ReplyReference whole = ReplyReference.from(stanza(reference + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'><body/></fallback>"), null);
        assertEquals("", whole.withoutFallback("Known body"));
    }
    @Test void repliesKeepAttachmentDetectionWithoutExposingKeys() throws Exception {
        String encryptedUrl = "aesgcm://example.org/photo.jpg#" + new String(new char[88]).replace('\0', 'a');
        ReplyReference reply = new ReplyReference("bob@example.org", "original").quoted("Original text");
        XmppMessage file = XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"), "bob@example.org", "file", FileLink.parse(encryptedUrl), true, reply);
        assertNotNull(file.reply); assertNotNull(file.attachment); assertEquals(encryptedUrl, file.reply.withoutFallback(file.body));
        XmppMessage plain = XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"), "bob@example.org", "plain", FileLink.parse("https://example.org/photo.jpg"), false, reply);
        assertNotNull(plain.reply); assertNotNull(plain.attachment);
        ReplyReference bounded = reply.quoted(new String(new char[600]).replace('\0', 'x') + "😀");
        assertTrue(bounded.wireBody("Answer").length() < 525);
    }
    @Test void quoteOnlyKeepsLiteralContextWithoutUnsupportedReplyMetadata() throws Exception {
        ReplyReference quote=ReplyReference.quoteOnly("First line\nSecond line");
        org.jivesoftware.smack.packet.Message message=new org.jivesoftware.smack.packet.Message();
        message.setBody(quote.wireBody("Answer")); quote.addTo(message);
        assertEquals("> First line\n> Second line\nAnswer",message.getBody());
        assertFalse(message.hasExtension("reply",ReplyReference.NAMESPACE));
        assertFalse(message.hasExtension("fallback",ReplyReference.FALLBACK));
        assertNull(ReplyReference.from(message,message.getBody()));
    }

}
