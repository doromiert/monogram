package com.negzero.monogram.xmpp;

import java.util.*;
import java.security.SecureRandom;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PollDefinitionTest {
    static final String POLL="<poll xmlns='urn:xmpp:polls:0' type='single'><question>Where 😀?</question><option emoji='🍕'>Pizza</option><option emoji='🍜'>Noodles</option></poll>";
    static Message message(String content)throws Exception {
        PollDefinition.initialize();ReplyReference.initialize();
        return PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='alice@example.org/phone' to='bob@example.org' type='chat' id='poll-123'>"+content+"</message>");
    }
    @Test void structuredDataNeverParsesTheBodyAndWorksWithoutIt()throws Exception {
        PollDefinition expected=PollDefinition.from(message(POLL));assertEquals("Where 😀?",expected.question);assertEquals("🍜",expected.options.get(1).emoji);
        for(String body:Arrays.asList("", "Translated body with entirely different choices","📊 No body parsing allowed")) {
            Message message=message("<body>"+body+"</body>"+POLL+"<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:polls:0'><body/></fallback>");
            PollDefinition poll=PollDefinition.from(message);assertTrue(poll.sameDefinition(expected));assertEquals("",poll.accompanyingBody(message));
        }
        Message unmarked=message("<body>Accompanying text</body>"+POLL);assertEquals("Accompanying text",expected.accompanyingBody(unmarked));
        Message roundtrip=message("");expected.addTo(roundtrip);assertTrue(expected.sameDefinition(PollDefinition.from(roundtrip)));assertNull(roundtrip.getBody());
    }
    @Test void fieldsModesUniquenessAndBoundsAreValidatedTogether()throws Exception {
        for(String invalid:Arrays.asList(POLL.replace("single","quiz"),POLL.replace("type='single'","type='single' closed='yes'"),POLL.replace("</question>","</question><question>Duplicate</question>"),POLL.replace("emoji='🍜'","emoji='🍕'"),POLL.replace("emoji='🍜'","emoji='hello'"),POLL.replace("Noodles","<nested/>"),POLL.replace("Noodles",repeat("x",1025)),POLL.replace("Where 😀?",repeat("😀",4097)),POLL+POLL)) {
            Message message=message(invalid);assertThrows(IllegalArgumentException.class,()->PollDefinition.from(message));
        }
        PollDefinition original=PollDefinition.from(message(POLL));assertDoesNotThrow(()->new PollDefinition(repeat("😀",4096),"",original.options,false,false));
        assertThrows(IllegalArgumentException.class,()->new PollDefinition("\ud800","",original.options,false,false));
        assertThrows(IllegalArgumentException.class,()->new PollDefinition("Question","",Arrays.asList(new PollDefinition.Option("❤","A"),new PollDefinition.Option("❤️","B")),false,false));
        assertEquals("❤️",ReactionEmoji.canonical("❤"));assertEquals("👍🏽",ReactionEmoji.canonical("👍🏽"));assertNull(ReactionEmoji.canonical("hello"));
    }
    @Test void serializedByteBoundIsIndependentOfFieldAndOptionCounts()throws Exception {
        List<PollDefinition.Option> choices=new ArrayList<>();
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(ReactionEmoji.class.getResourceAsStream("reaction-emoji.tsv"),java.nio.charset.StandardCharsets.UTF_8))) {
            for(String line;choices.size()<32&&(line=reader.readLine())!=null;)choices.add(new PollDefinition.Option(line.split("\t")[1],repeat("中",1024)));
        }
        assertEquals(32,choices.size());assertThrows(IllegalArgumentException.class,()->new PollDefinition("Question","",choices,false,false));
        assertThrows(IllegalArgumentException.class,()->new PollDefinition.Option("🍕"+repeat("\ufe0f",128),"Choice"));
    }
    @Test void codePointFallbackRangesDoNotHideUnmarkedOrMalformedText()throws Exception {
        PollDefinition poll=PollDefinition.from(message(POLL));
        for(String[] example:new String[][]{{"start='0' end='2'","after"},{"start='0' end='99'","😀 after"},{"start='1'","😀 after"},{"xml:lang='fr'","😀 after"}}) {
            Message message=message("<body>😀 after</body>"+POLL+"<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:polls:0'><body "+example[0]+"/></fallback>");assertEquals(example[1],poll.accompanyingBody(message));
        }
        assertTrue(poll.sameDefinition(poll.withClosed(true)));
        List<PollDefinition.Option> reversed=new ArrayList<>(poll.options);Collections.reverse(reversed);assertFalse(poll.sameDefinition(new PollDefinition(poll.question,"",reversed,false,true)));
    }
    @Test void protectedPollsAndFallbacklessPollsRoundtripInSce()throws Exception {
        for(String content:Arrays.asList(POLL,"<body>Localizable fallback</body>"+POLL+"<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:polls:0'><body/></fallback>")) {
            Message input=message(content);byte[] plain=Omemo2Payload.envelope(input,new SecureRandom());
            try(Omemo2Payload.Sealed sealed=Omemo2Payload.seal(plain,new SecureRandom())) {
                Message decoded=Omemo2Payload.content(input,Omemo2Payload.open(sealed.ciphertext,sealed.material));
                assertTrue(PollDefinition.from(input).sameDefinition(PollDefinition.from(decoded)));assertEquals("",PollDefinition.from(decoded).accompanyingBody(decoded));
            }
        }
        Message malformed=message(POLL.replace("single","quiz"));assertThrows(java.security.GeneralSecurityException.class,()->Omemo2Payload.envelope(malformed,new SecureRandom()));
        Message mixed=message(POLL);new MessageReactions("target",Collections.singleton("👍")).addTo(mixed);assertThrows(java.security.GeneralSecurityException.class,()->Omemo2Payload.envelope(mixed,new SecureRandom()));
    }
    @Test void bodylessModelsRetainStructuredXmlAcrossCopiesAndIgnoreOuterUntrustedHints()throws Exception {
        Message clear=message(POLL);org.jxmpp.jid.EntityBareJid own=JidCreate.entityBareFrom("bob@example.org");
        XmppMessage plain=XmppMessage.from(clear,own,null,false,true,null,null);assertNotNull(plain);assertEquals("",plain.body);assertNotNull(plain.pollXml);
        assertEquals(plain.pollXml,plain.withSenderFingerprint("fixture").archived("archive",new Date(100000)).pollXml);
        XmppMessage secure=XmppMessage.modern(clear,clear,own,null,null);assertNotNull(secure.pollXml);
        XmppMessage unreadable=XmppMessage.modern(clear,null,own,null,null);assertNull(unreadable.pollXml);assertFalse(unreadable.readable);
        Message invalid=message("<body>Keep text</body>"+POLL.replace("single","quiz"));XmppMessage malformed=XmppMessage.from(invalid,own,invalid.getBody(),false,true,null,null);assertEquals("",malformed.pollXml);assertEquals("Keep text",malformed.body);
        Message room=message(POLL);room.setType(Message.Type.groupchat);room.setFrom(JidCreate.entityFullFrom("room@example.org/Alice"));
        XmppRoomMessage group=XmppRoomMessage.from(room,JidCreate.entityBareFrom("room@example.org"),own,"Bob",null,false,true);assertNotNull(group);assertNotNull(group.pollXml);assertEquals(group.pollXml,group.withOccupantId("actor").pollXml);
    }
    @Test void pollAndReplyFallbackOffsetsStayIndependentWhenPollFallbackComesFirst()throws Exception {
        Message wire=message(POLL);String fallback="Translated poll fallback 😀\n\n",quote="> Quoted 🦊\n",caption="Accompanying text";
        wire.setBody(fallback+quote+caption);new ReplyReference("alice@example.org","original").addTo(wire);
        int start=fallback.codePointCount(0,fallback.length()),end=start+quote.codePointCount(0,quote.length());
        wire.addExtension(StandardExtensionElement.builder("fallback",ReplyReference.FALLBACK).addAttribute("for",PollDefinition.NAMESPACE).addElement(StandardExtensionElement.builder("body",ReplyReference.FALLBACK).addAttribute("start","0").addAttribute("end",Integer.toString(start)).build()).build());
        wire.addExtension(StandardExtensionElement.builder("fallback",ReplyReference.FALLBACK).addAttribute("for",ReplyReference.NAMESPACE).addElement(StandardExtensionElement.builder("body",ReplyReference.FALLBACK).addAttribute("start",Integer.toString(start)).addAttribute("end",Integer.toString(end)).build()).build());
        XmppMessage decoded=XmppMessage.from(wire,JidCreate.entityBareFrom("bob@example.org"),wire.getBody(),false,true,null,null);
        assertEquals(quote+caption,decoded.body);assertEquals(caption,decoded.reply.withoutFallback(decoded.body));assertNotNull(decoded.pollXml);
    }
    static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}
}
