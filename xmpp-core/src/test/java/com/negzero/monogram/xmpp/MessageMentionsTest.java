package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;

public class MessageMentionsTest {
    @BeforeEach public void providers() { MessageMarkup.initialize(); }
    private Message message(String body) throws Exception {
        Message message=new Message(JidCreate.entityFullFrom("alice@example.org/phone"),Message.Type.chat);
        message.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));message.setBody(body);message.setStanzaId("mention");return message;
    }
    private MessageMarkup mention(String body,int start,int end) {
        return MessageMarkup.body(body).withMentions(Collections.singleton(new MessageMentions.Mention(start,end,"alice@example.org")));
    }
    @Test public void referenceRoundTripUsesCodepointsAndDoesNotChangeVisibleNickname() throws Exception {
        String body="😀 Alice, hello";Message wire=message(body);mention(body,2,7).addTo(wire);
        Message parsed=PacketParserUtils.parseMessage(PacketParserUtils.getParserFor(wire.toXML().toString()));
        MessageMarkup restored=MessageMarkup.from(parsed,body);assertEquals(1,restored.mentions.size());
        MessageMentions.Mention mention=restored.mentions.get(0);assertEquals(2,mention.start);assertEquals(7,mention.end);assertEquals("alice@example.org",mention.address);
        assertEquals("Alice",body.substring(body.offsetByCodePoints(0,mention.start),body.offsetByCodePoints(0,mention.end)));
        assertThrows(UnsupportedOperationException.class,()->restored.mentions.clear());
    }
    @Test public void stylingReplyCaptionAndPersistenceRebaseMentionsExactly() {
        String text="😀 Alice hello";
        MessageMarkup nativeText=new MessageMarkup(text,Collections.singleton(new MessageMarkup.Range(2,7,Collections.singleton(MessageMarkup.Style.STRONG),null)))
                .withMentions(Collections.singleton(new MessageMentions.Mention(2,7,"alice@example.org")));
        MessageMarkup wire=nativeText.styled();assertEquals(text,wire.display().body);assertEquals(2,wire.display().mentions.get(0).start);assertEquals(7,wire.display().mentions.get(0).end);
        MessageMarkup full=wire.inBody("prefix\n"+wire.body,7);assertEquals(7+wire.mentions.get(0).start,full.mentions.get(0).start);
        assertEquals(wire.body,full.slice(7,full.body.codePointCount(0,full.body.length())).body);
        assertEquals(wire.mentions.get(0).start,full.slice(7,full.body.codePointCount(0,full.body.length())).mentions.get(0).start);
        assertEquals(1,MessageMarkup.decode(wire.body,wire.encode()).mentions.size());
        assertTrue(nativeText.without(Collections.singleton(new int[]{0,8})).mentions.isEmpty());
        assertNotNull(MessageMarkup.decode("old",java.util.Base64.getEncoder().encodeToString(new byte[]{1,1,0,0})));
    }
    @Test public void malformedReferencesAndAnonymousOccupantAddressesDoNotCreateMentions() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new MessageMentions.Mention(0,5,"room@conference.example.org/Alice"));
        assertThrows(IllegalArgumentException.class,()->mention("Alice",0,99));
        Message wire=message("Alice");wire.addExtension(StandardExtensionElement.builder("reference",MessageMentions.NAMESPACE).addAttribute("type","mention")
                .addAttribute("uri","https://example.org/").addAttribute("begin","0").addAttribute("end","5").build());
        assertTrue(MessageMarkup.from(wire,"Alice").mentions.isEmpty());
        wire.addExtension(StandardExtensionElement.builder("reference",MessageMentions.NAMESPACE).addAttribute("type","mention")
                .addAttribute("uri","xmpp:alice@example.org").addAttribute("begin","-1").addAttribute("end","5").build());
        assertTrue(MessageMarkup.from(wire,"Alice").mentions.isEmpty());assertEquals("Alice",MessageMarkup.from(wire,"Alice").body);
    }
    @Test public void encryptedOuterReferencesAreIgnoredButAuthenticatedSceReferencesSurvive() throws Exception {
        String body="Alice hello";Message outer=message(body);mention(body,0,5).addTo(outer);
        assertTrue(XmppMessage.from(outer,JidCreate.entityBareFrom("alice@example.org"),body,true,true,null,null).markup.mentions.isEmpty());
        Message decoded=Omemo2Payload.content(outer,Omemo2Payload.envelope(outer,new java.security.SecureRandom()));
        assertEquals(1,MessageMarkup.from(decoded,body).mentions.size());
        assertEquals(1,XmppMessage.modern(outer,decoded,JidCreate.entityBareFrom("alice@example.org"),null,null).markup.mentions.size());
        Message clear=message(body);Message clean=Omemo2Payload.content(clear,Omemo2Payload.envelope(clear,new java.security.SecureRandom()));
        assertTrue(XmppMessage.modern(outer,clean,JidCreate.entityBareFrom("alice@example.org"),null,null).markup.mentions.isEmpty());
    }
}
