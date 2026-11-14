package com.negzero.monogram.xmpp;
import static org.junit.jupiter.api.Assertions.*;import java.util.*;import org.junit.jupiter.api.Test;import org.jivesoftware.smack.packet.Message;import org.jxmpp.jid.impl.JidCreate;
class LegacyInlineTest {
 static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
 @Test void authenticatedLegacyBodyContainsArtworkAndNormalizesOnAnotherDevice()throws Exception {
  InlineEmoji.initialize();String body="🙂🙂";InlineEmoji.Asset a=new InlineEmoji.Asset("image/png",PNG);InlineEmoji e=new InlineEmoji(body,Arrays.asList(new InlineEmoji.Range(0,1,a),new InlineEmoji.Range(1,2,a)));
  String wire=LegacyInline.encode(body,e);if(System.getenv("MONOGRAM_INLINE_FIXTURE")!=null)java.nio.file.Files.write(java.nio.file.Paths.get(System.getenv("MONOGRAM_INLINE_FIXTURE")),wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));Message decoded=LegacyInline.decode(wire);assertEquals(body,decoded.getBody());assertEquals(2,InlineEmoji.from(decoded,body).ranges.size());
  Message outer=new Message(JidCreate.entityBareFrom("bob@example.invalid"),Message.Type.chat);outer.setFrom(JidCreate.entityFullFrom("alice@example.invalid/phone"));outer.setStanzaId("fixture-inline");
  XmppMessage result=XmppMessage.from(outer,JidCreate.entityBareFrom("bob@example.invalid"),wire,true,true,null,null);assertNotNull(result);assertEquals(body,result.body);assertEquals(2,result.inlineEmoji.ranges.size());assertArrayEquals(PNG,result.inlineEmoji.ranges.get(0).asset.data());
  assertThrows(Exception.class,()->LegacyInline.decode(wire.replace("iVBOR","jVBOR")));assertNull(LegacyInline.decode(body));
 }
}
