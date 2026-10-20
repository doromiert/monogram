package com.negzero.monogram.xmpp;

import java.util.*;
import org.junit.jupiter.api.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.MessageMarkup.Style.*;

public class MessageMarkupTest {
    @BeforeEach public void providers() { MessageMarkup.initialize(); }
    private Message direct(String body) throws Exception {
        Message message=new Message(JidCreate.entityFullFrom("alice@example.org/phone"),Message.Type.chat);
        message.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));message.setStanzaId("original");message.setBody(body);return message;
    }
    private MessageMarkup markup(String body) {
        return new MessageMarkup(body,Arrays.asList(new MessageMarkup.Range(2,8,Arrays.asList(STRONG,EMPHASIS),null)));
    }
    @Test public void wireRangesUseCodepointsAndConvertToNativeUtf16WithoutSplittingEmoji() throws Exception {
        String body="😀 secret tail";Message message=direct(body);markup(body).addTo(message);
        Message parsed=PacketParserUtils.parseMessage(PacketParserUtils.getParserFor(message.toXML().toString()));
        MessageMarkup restored=MessageMarkup.from(parsed,body);assertNotNull(restored);assertEquals(1,restored.ranges.size());
        MessageMarkup.Range range=restored.ranges.get(0);
        assertEquals(2,range.start);assertEquals(8,range.end);assertEquals(3,range.utf16Start(body));assertEquals(9,range.utf16End(body));
        assertEquals(EnumSet.of(STRONG,EMPHASIS),range.styles);assertEquals("secret",body.substring(range.utf16Start(body),range.utf16End(body)));
        assertThrows(UnsupportedOperationException.class,() -> restored.ranges.clear());
        assertThrows(UnsupportedOperationException.class,() -> range.styles.clear());
        assertThrows(IllegalArgumentException.class,() -> restored.addTo(direct("another body")));
        assertTrue(MessageMarkup.from(parsed,"another body").ranges.isEmpty());
    }
    @Test public void validatesNestedBlockAndSpanBoundariesAndRejectsCrossingOrOutOfBodyRanges() throws Exception {
        String body="quote with code";
        Message message=direct(body);
        new MessageMarkup(body,Arrays.asList(new MessageMarkup.Range(0,15,Collections.singleton(QUOTE),null),
                new MessageMarkup.Range(6,10,Collections.singleton(CODE),null))).addTo(message);
        assertEquals(2,MessageMarkup.from(message,body).ranges.size());
        assertThrows(IllegalArgumentException.class,() -> new MessageMarkup(body,Arrays.asList(
                new MessageMarkup.Range(0,8,Collections.singleton(STRONG),null),new MessageMarkup.Range(6,10,Collections.singleton(EMPHASIS),null))));
        assertThrows(IllegalArgumentException.class,() -> new MessageMarkup(body,Arrays.asList(
                new MessageMarkup.Range(0,15,Collections.singleton(STRONG),null),new MessageMarkup.Range(6,10,Collections.singleton(QUOTE),null))));
        assertThrows(IllegalArgumentException.class,() -> new MessageMarkup(body,Arrays.asList(new MessageMarkup.Range(0,16,Collections.singleton(PRE),null))));
        assertThrows(IllegalArgumentException.class,() -> new MessageMarkup(body,Collections.nCopies(257,new MessageMarkup.Range(0,1,Collections.singleton(STRONG),null))));
    }
    @Test public void malformedAndUnknownMetadataNeverReplaceReadableBody() throws Exception {
        Message message=direct("Readable text");
        StandardExtensionElement unknown=StandardExtensionElement.builder("future",MessageMarkup.NAMESPACE).addAttribute("start","nonsense").build();
        StandardExtensionElement span=StandardExtensionElement.builder("span",MessageMarkup.NAMESPACE).addAttribute("start","0").addAttribute("end","8")
                .addElement(StandardExtensionElement.builder("strong",MessageMarkup.NAMESPACE).addElement(unknown).build()).build();
        message.addExtension(StandardExtensionElement.builder("markup",MessageMarkup.NAMESPACE).addElement(unknown).addElement(span).build());
        assertEquals(Collections.singleton(STRONG),MessageMarkup.from(message,message.getBody()).ranges.get(0).styles);
        message.removeExtension("markup",MessageMarkup.NAMESPACE);
        message.addExtension(StandardExtensionElement.builder("markup",MessageMarkup.NAMESPACE).addElement(
                StandardExtensionElement.builder("span",MessageMarkup.NAMESPACE).addAttribute("start","-1").addAttribute("end","99999999999999")
                        .addElement(StandardExtensionElement.builder("strong",MessageMarkup.NAMESPACE).build()).build()).build());
        assertTrue(MessageMarkup.from(message,message.getBody()).ranges.isEmpty());
        XmppMessage parsed=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),message.getBody(),false,true,null,null);
        assertEquals("Readable text",parsed.body);assertTrue(parsed.markup.ranges.isEmpty());
    }
    @Test public void onlyAuthenticatedContentCanSupplyEncryptedFormatting() throws Exception {
        String body="😀 secret tail";Message outer=direct(body);markup(body).addTo(outer);
        XmppMessage plain=XmppMessage.from(outer,JidCreate.entityBareFrom("alice@example.org"),body,false,true,null,null);assertNotNull(plain.markup);
        XmppMessage legacy=XmppMessage.from(outer,JidCreate.entityBareFrom("alice@example.org"),body,true,true,null,null);assertTrue(legacy.markup.ranges.isEmpty());
        Message clear=direct(body);
        XmppMessage noProtectedMarkup=XmppMessage.modern(outer,clear,JidCreate.entityBareFrom("alice@example.org"),null,null);assertTrue(noProtectedMarkup.markup.ranges.isEmpty());
        new MessageMarkup(body,Collections.singletonList(new MessageMarkup.Range(2,8,Collections.singleton(DELETED),null))).addTo(clear);
        XmppMessage protectedMarkup=XmppMessage.modern(outer,clear,JidCreate.entityBareFrom("alice@example.org"),"mam-one",new Date(1000));
        assertEquals(Collections.singleton(DELETED),protectedMarkup.markup.ranges.get(0).styles);
        assertSame(protectedMarkup.markup,protectedMarkup.withSenderFingerprint("fingerprint").markup);
        assertTrue(protectedMarkup.archived);
    }
    @Test public void roomIdentityCopiesKeepPlainMarkupButIgnoreEncryptedOuterMarkup() throws Exception {
        String body="😀 secret tail";Message message=new Message(JidCreate.entityFullFrom("alice@example.org/phone"),Message.Type.groupchat);
        message.setFrom(JidCreate.entityFullFrom("room@conference.example.org/Bob"));message.setBody(body);message.setStanzaId("room-message");markup(body).addTo(message);
        org.jxmpp.jid.EntityBareJid room=JidCreate.entityBareFrom("room@conference.example.org"),own=JidCreate.entityBareFrom("alice@example.org");
        XmppRoomMessage plain=XmppRoomMessage.from(message,room,own,"Alice",body,false,true);
        assertNotNull(plain.markup);assertSame(plain.markup,plain.identified("bob@example.org",own).withOccupantId("stable").withSkippedRecipients(Collections.emptyMap()).withSenderFingerprint("fp").markup);
        assertTrue(XmppRoomMessage.from(message,room,own,"Alice",body,true,true).markup.ranges.isEmpty());
    }
    @Test public void stylingParserProjectsNestedSpansEmojiAndKeepsInvalidMarkersAndUrlsLiteral() {
        MessageMarkup display=MessageMarkup.body("😀 *strong _nested_* ~gone~ `*literal*`").display();
        assertEquals("😀 strong nested gone *literal*",display.body);
        assertTrue(display.ranges.stream().anyMatch(range -> range.styles.equals(EnumSet.of(STRONG,EMPHASIS))));
        assertTrue(display.ranges.stream().anyMatch(range -> range.styles.contains(CODE)));
        for(String literal:Arrays.asList("https://example.org/a_b_c","word*not strong*","**","***","* bad*","*bad *","*cross\nline*"))
            assertEquals(literal,MessageMarkup.body(literal).display().body);
    }
    @Test public void nativeStylesRoundTripWithoutLiteralTextChangesAndPersistenceRetainsUnstyled() throws Exception {
        String body="😀 bold italic done";
        MessageMarkup nativeMarkup=new MessageMarkup(body,Arrays.asList(
                new MessageMarkup.Range(2,7,Collections.singleton(STRONG),null),
                new MessageMarkup.Range(7,13,EnumSet.of(STRONG,EMPHASIS),null)));
        MessageMarkup wire=nativeMarkup.styled();assertNotEquals(body,wire.body);
        assertEquals(body,wire.display().body);
        Message message=direct(wire.body);wire.addTo(message);
        Message parsed=PacketParserUtils.parseMessage(PacketParserUtils.getParserFor(message.toXML().toString()));
        assertEquals(body,MessageMarkup.from(parsed,wire.body).display().body);
        assertEquals(body,MessageMarkup.decode(wire.body,wire.encode()).display().body);
        MessageMarkup literal=new MessageMarkup("keep *literal* text",Collections.emptyList()).styled();
        Message plain=direct(literal.body);literal.addTo(plain);
        assertTrue(plain.hasExtension("unstyled",MessageMarkup.STYLING));
        assertEquals(literal.body,MessageMarkup.from(plain,literal.body).display().body);
        assertEquals(literal.body,MessageMarkup.decode(literal.body,literal.encode()).display().body);
        assertNull(MessageMarkup.decode(body,"not base64"));assertNull(MessageMarkup.decode(body,wire.encode()+"AAAA"));
    }
    @Test public void greentextRemainsLiteralAndDoesNotBecomeAQuote() {
        for(String body:Arrays.asList(">greentext",">first\n>second\n>>nested",">")) {
            MessageMarkup display=MessageMarkup.body(body).display();
            assertEquals(body,display.body);
            assertTrue(display.ranges.stream().noneMatch(range -> range.styles.contains(QUOTE)));
        }
        MessageMarkup spaced=MessageMarkup.body("> quote\n>greentext\n> > nested quote").display();
        assertEquals("quote\n>greentext\nnested quote",spaced.body);
        assertEquals(2,spaced.ranges.stream().filter(range -> range.styles.contains(QUOTE)).count());
        String body=">literal inside explicit quote\n> another line";
        MessageMarkup explicit=new MessageMarkup(body,Collections.singletonList(new MessageMarkup.Range(0,body.length(),Collections.singleton(QUOTE),null)));
        assertEquals(body,explicit.display().body);
        assertEquals(body,explicit.styled().display().body);
        assertTrue(explicit.styled().display().ranges.stream().anyMatch(range -> range.styles.contains(QUOTE)));
    }
    @Test public void preAndQuoteBodyStylesRoundTripAndMetadataOnlyFallbackNeverChangesLiteralContent() {
        for(MessageMarkup.Style style:Arrays.asList(PRE,QUOTE)) {
            String body="first\nsecond";MessageMarkup markup=new MessageMarkup(body,Collections.singletonList(new MessageMarkup.Range(0,body.length(),Collections.singleton(style),style==PRE ? "java" : null)));
            MessageMarkup wire=markup.styled();assertNotEquals(body,wire.body);assertEquals(body,wire.display().body);
            assertTrue(wire.display().ranges.stream().anyMatch(range -> range.styles.contains(style)));
        }
        String literal="before *literal* after";
        MessageMarkup unsafe=new MessageMarkup(literal,Collections.singletonList(new MessageMarkup.Range(0,6,Collections.singleton(STRONG),null))).styled();
        assertEquals(literal,unsafe.body);assertEquals(literal,unsafe.display().body);
    }
    @Test public void replyFallbackAndFileCaptionProjectionUseExactRawCodepointIntervals() throws Exception {
        org.jxmpp.jid.EntityBareJid own=JidCreate.entityBareFrom("alice@example.org");
        String caption="😀 *caption*";MessageMarkup styled=MessageMarkup.body(caption);
        FileShare sharing=new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>photo.jpg</name><media-type>image/jpeg</media-type></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='https://example.org/photo.jpg'/></sources></file-sharing>");
        ReplyReference reply=new ReplyReference("bob@example.org","previous").quoted("😀 quoted *caption*");
        String payload=sharing.body(caption),body=reply.wireBody(payload);
        Message message=direct(body);reply.addTo(message);sharing.addTo(message,body);
        styled.inBody(body,body.codePointCount(0,body.length()-payload.length())).addTo(message);
        XmppMessage parsed=XmppMessage.from(message,own,body,false,true,"archive",new Date(1000));
        assertEquals(body,parsed.body);assertEquals(caption,parsed.captionMarkup.body);
        assertEquals("😀 caption",parsed.captionMarkup.display().body);
        assertEquals("😀 caption",parsed.withSenderFingerprint("fingerprint").captionMarkup.display().body);
        MessageMarkup mapped=new MessageMarkup("a😀bXXc",Collections.singletonList(new MessageMarkup.Range(1,6,Collections.singleton(STRONG),null)))
                .without(Arrays.asList(new int[]{0,1},new int[]{3,5}));
        assertEquals("😀bc",mapped.body);assertEquals(0,mapped.ranges.get(0).start);assertEquals(3,mapped.ranges.get(0).end);
        XmppMessage sent=XmppMessage.sent(own,"bob@example.org","sent",caption,XmppMessage.Encryption.PLAIN,reply,null).withMarkup(styled);
        assertEquals("😀 caption",sent.markup.without(Collections.singletonList(new int[]{0,reply.wireBody("").codePointCount(0,reply.wireBody("").length())})).display().body);
    }
    @Test public void legacyIgnoresForgedOuterUnstyledAndMarkupAndUnreadableHasNoProjection() throws Exception {
        Message message=direct("*secret*");
        MessageMarkup forged=new MessageMarkup(message.getBody(),Collections.emptyList()).styled();forged.addTo(message);
        XmppMessage legacy=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),message.getBody(),true,true,null,null);
        assertEquals("secret",legacy.markup.display().body);
        XmppMessage sent=XmppMessage.sent(JidCreate.entityBareFrom("alice@example.org"),"bob@example.org","literal",forged.body,true).withMarkup(forged);
        assertEquals(legacy.markup.display().body,sent.markup.display().body);
        MessageMarkup metadataOnly=new MessageMarkup("word",Collections.singletonList(new MessageMarkup.Range(1,3,Collections.singleton(STRONG),null))).styled();
        XmppMessage fallback=XmppMessage.sent(JidCreate.entityBareFrom("alice@example.org"),"bob@example.org","fallback",metadataOnly.body,true).withMarkup(metadataOnly);
        assertEquals("word",fallback.markup.display().body);assertTrue(fallback.markup.display().ranges.isEmpty());
        org.jxmpp.jid.EntityBareJid room=JidCreate.entityBareFrom("room@conference.example.org"),own=JidCreate.entityBareFrom("alice@example.org");
        XmppRoomMessage roomSent=XmppRoomMessage.sent(room,own,"Alice","literal-room",forged.body,true).withMarkup(forged);
        assertEquals(legacy.markup.display().body,roomSent.markup.display().body);
        XmppMessage unreadable=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),null,true,false,null,null);
        assertNull(unreadable.markup);assertNull(unreadable.captionMarkup);
    }
}
