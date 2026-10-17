package com.negzero.monogram.xmpp;

import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import static org.junit.Assert.*;
import static com.negzero.monogram.xmpp.MessageMarkup.Style.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MessageMarkupTest {
    @Before public void providers() { MessageMarkup.initialize(); }
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
        assertNull(MessageMarkup.from(parsed,"another body"));
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
        assertNull(MessageMarkup.from(message,message.getBody()));
        XmppMessage parsed=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),message.getBody(),false,true,null,null);
        assertEquals("Readable text",parsed.body);assertNull(parsed.markup);
    }
    @Test public void onlyAuthenticatedContentCanSupplyEncryptedFormatting() throws Exception {
        String body="😀 secret tail";Message outer=direct(body);markup(body).addTo(outer);
        XmppMessage plain=XmppMessage.from(outer,JidCreate.entityBareFrom("alice@example.org"),body,false,true,null,null);assertNotNull(plain.markup);
        XmppMessage legacy=XmppMessage.from(outer,JidCreate.entityBareFrom("alice@example.org"),body,true,true,null,null);assertNull(legacy.markup);
        Message clear=direct(body);
        XmppMessage noProtectedMarkup=XmppMessage.modern(outer,clear,JidCreate.entityBareFrom("alice@example.org"),null,null);assertNull(noProtectedMarkup.markup);
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
        assertNull(XmppRoomMessage.from(message,room,own,"Alice",body,true,true).markup);
    }
}
