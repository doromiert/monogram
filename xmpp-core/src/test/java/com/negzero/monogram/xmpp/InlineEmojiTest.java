package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;

class InlineEmojiTest {
    private static final byte[] IMAGE=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDPsAAAAASUVORK5CYII=");
    private InlineEmoji emoji(String body) { return new InlineEmoji(body,Arrays.asList(new InlineEmoji.Range(2,3,new InlineEmoji.Asset("image/png",IMAGE)),new InlineEmoji.Range(6,7,new InlineEmoji.Asset("image/png",IMAGE)))); }
    private Message message(String body) throws Exception {
        FileShare.initialize();MessageMarkup.initialize();Message message=new Message(JidCreate.entityBareFrom("bob@example.org"),Message.Type.chat);
        message.setFrom(JidCreate.entityFullFrom("alice@example.org/phone"));message.setStanzaId("inline-fixture");message.setBody(body);return message;
    }
    @Test void standardSelfContainedImagesRoundTripPlainAndProtectedWithoutLegacyMetadataLeaks() throws Exception {
        String body="A 😀 B 😀";InlineEmoji inline=emoji(body);Message original=message(body);inline.addTo(original);
        assertTrue(original.hasExtension("html",InlineEmoji.HTML));assertEquals(1,original.getExtensions("data",InlineEmoji.BOB).size());
        InlineEmoji plain=InlineEmoji.from(PacketParserUtils.parseStanza(original.toXML().toString()),body);
        assertNotNull(plain);assertEquals(2,plain.ranges.size());assertEquals(6,plain.ranges.get(1).start);assertArrayEquals(IMAGE,plain.ranges.get(0).asset.data());
        try(Omemo2Payload.Sealed sealed=Omemo2Payload.seal(Omemo2Payload.envelope(original,new java.security.SecureRandom()),new java.security.SecureRandom())) {
            Message decoded=Omemo2Payload.content(original,Omemo2Payload.open(sealed.ciphertext,sealed.material));
            XmppMessage protectedMessage=XmppMessage.modern(original,decoded,JidCreate.entityBareFrom("bob@example.org"),"archive",new Date(1000));
            assertNotNull(protectedMessage.inlineEmoji);assertEquals(2,protectedMessage.inlineEmoji.ranges.size());
            assertNotNull(protectedMessage.withSenderFingerprint("fixture").canonical(protectedMessage.ids,1000,true,null).inlineEmoji);
        }
        assertNull(XmppMessage.from(original,JidCreate.entityBareFrom("bob@example.org"),body,true,true,null,null).inlineEmoji);
        XmppMessage own=XmppMessage.sent(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","own",body,true).withInlineEmoji(inline);
        assertNotNull(own.withSenderFingerprint("fixture").inlineEmoji,"Legacy sender may retain its own private local rendering");
        Message authentic=message(body);Message decoded=Omemo2Payload.content(authentic,Omemo2Payload.envelope(authentic,new java.security.SecureRandom()));
        assertNull(XmppMessage.modern(original,decoded,JidCreate.entityBareFrom("bob@example.org"),null,null).inlineEmoji,"Only authenticated content may supply images");
    }
    @Test void malformedCidUnsafeSourcesAndOverlapsFallBackToReadableUnicode() throws Exception {
        String body="A 😀 B 😀";Message original=message(body);emoji(body).addTo(original);String xml=original.toXML().toString();
        for(String unsafe:new String[]{xml.replace("src='cid:","src='https://evil.example/"),xml.replace("src=\"cid:","src=\"https://evil.example/"),xml.replace("iVBOR", "jVBOR"),xml.replace("A ","Changed ")}) {
            if(unsafe.equals(xml)) continue;Message parsed=PacketParserUtils.parseStanza(unsafe);assertNull(InlineEmoji.from(parsed,body));
        }
        InlineEmoji.Asset asset=new InlineEmoji.Asset("image/png",IMAGE);
        assertThrows(IllegalArgumentException.class,()->new InlineEmoji(body,Arrays.asList(new InlineEmoji.Range(2,4,asset),new InlineEmoji.Range(3,5,asset))));
        assertThrows(IllegalArgumentException.class,()->new InlineEmoji.Asset(asset.cid,"image/png",new byte[]{1,2,3}));
        assertThrows(IllegalArgumentException.class,()->new InlineEmoji.Asset("text/html",IMAGE));
        assertThrows(IllegalArgumentException.class,()->new InlineEmoji.Asset("image/png",new byte[InlineEmoji.MAX_BYTES+1]));
    }
    @Test void repeatedEmojiUseExactReplyAndStylingCodePointMaps() throws Exception {
        InlineEmoji.Asset asset=new InlineEmoji.Asset("image/png",IMAGE);
        String wire="*A 😀* B 😀";
        InlineEmoji styled=new InlineEmoji(wire,Arrays.asList(new InlineEmoji.Range(3,4,asset),new InlineEmoji.Range(8,9,asset)));
        InlineEmoji literal=MessageMarkup.body(wire).preservingBody().displayInline(styled);
        assertEquals(wire,literal.body);assertEquals(3,literal.ranges.get(0).start);
        Message literalMessage=message(wire);MessageMarkup.body(wire).preservingBody().addTo(literalMessage);styled.addTo(literalMessage);
        assertEquals(wire,MessageMarkup.from(literalMessage,wire).display().body);
        InlineEmoji displayed=MessageMarkup.body(wire).displayInline(styled);assertEquals("A 😀 B 😀",displayed.body);assertEquals(2,displayed.ranges.get(0).start);assertEquals(6,displayed.ranges.get(1).start);
        String prefix="> 😀 quoted\n\n";int length=prefix.codePointCount(0,prefix.length());
        InlineEmoji quoted=displayed.inBody(prefix+displayed.body,length);
        InlineEmoji restored=quoted.without(Arrays.asList(new int[]{0,length}));assertEquals(displayed.body,restored.body);assertEquals(6,restored.ranges.get(1).start);
        assertEquals(1,restored.slice(5,7).ranges.size());
        assertEquals(1,restored.without(Arrays.asList(new int[]{2,3})).ranges.size(),"An image whose fallback was removed must disappear too");
    }
}
