package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;

class Omemo2PayloadTest {
    private Message message(String body) throws Exception {
        Message m = new Message(JidCreate.entityBareFrom("bob@example.org"),Message.Type.chat);
        m.setFrom(JidCreate.entityFullFrom("alice@example.org/phone")); m.setBody(body); return m;
    }
    @Test void payloadAuthenticationPrecedesPaddingAndDerivationMatchesIndependentJceHkdf() throws Exception {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(new javax.crypto.spec.SecretKeySpec(new byte[32],"HmacSHA256")); byte[] prk = mac.doFinal(key);
        java.io.ByteArrayOutputStream expanded = new java.io.ByteArrayOutputStream(); byte[] previous = new byte[0];
        for (int block=1;block<=3;block++) {
            mac.init(new javax.crypto.spec.SecretKeySpec(prk,"HmacSHA256")); mac.update(previous); mac.update("OMEMO Payload".getBytes(StandardCharsets.US_ASCII));
            previous = mac.doFinal(new byte[]{(byte)block}); expanded.write(previous);
        }
        assertArrayEquals(java.util.Arrays.copyOf(expanded.toByteArray(),80),Omemo2Payload.derive(key));
        byte[] body = "Private caption 😀".getBytes(StandardCharsets.UTF_8);
        try (Omemo2Payload.Sealed sealed = Omemo2Payload.seal(body,new SecureRandom())) {
            assertEquals(48,sealed.material.length); assertArrayEquals(body,Omemo2Payload.open(sealed.ciphertext,sealed.material));
            sealed.ciphertext[sealed.ciphertext.length-1] ^= 1;
            assertThrows(java.security.GeneralSecurityException.class,() -> Omemo2Payload.open(sealed.ciphertext,sealed.material));
        }
    }
    @Test void encryptedFileCaptionRepliesAndCorrectionsStayInsideTheEnvelope() throws Exception {
        FileShare.initialize(); MessageCorrection.initialize();
        Message original = message("Caption 😀");
        String link = "aesgcm://example.org/private.png#"+new String(new char[88]).replace('\0','a');
        FileShare sharing = new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>Friends.png</name></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+link+"'/></sources></file-sharing>");
        ReplyReference reply = new ReplyReference("bob@example.org","original").quoted("Quoted 🦄");
        original.setBody(reply.wireBody(sharing.body("Caption 😀"))); reply.addTo(original); sharing.addTo(original,original.getBody()); new MessageCorrection("original").addTo(original);
        byte[] envelope = Omemo2Payload.envelope(original,new SecureRandom()); assertTrue(envelope.length>=512);
        try (Omemo2Payload.Sealed encrypted = Omemo2Payload.seal(envelope,new SecureRandom())) {
            Message decoded = Omemo2Payload.content(original,Omemo2Payload.open(encrypted.ciphertext,encrypted.material));
            FileShare file = FileShare.from(decoded); assertNotNull(file); assertTrue(file.link.encrypted());
            assertEquals("Caption 😀",file.caption(decoded,decoded.getBody(),ReplyReference.from(decoded,decoded.getBody())));
            assertEquals("original",MessageCorrection.from(decoded).id);
            Message outer = message("Server-supplied fallback"); outer.setStanzaId("wire-id");
            new ReplyReference("mallory@example.org","forged-reply").addTo(outer); new MessageCorrection("forged-edit").addTo(outer);
            XmppMessage model = XmppMessage.modern(outer,decoded,JidCreate.entityBareFrom("bob@example.org"),"archive-id",new java.util.Date(1234000));
            assertEquals(XmppMessage.Encryption.OMEMO2,model.encryption); assertTrue(model.encrypted); assertTrue(model.readable);
            assertEquals(link,model.attachment.wireUrl); assertEquals("Caption 😀",model.caption);
            assertEquals("original",model.reply.id); assertEquals("original",model.correction.id);
            assertEquals("wire-id",model.ids.get("stanza")); assertEquals("archive-id",model.ids.get("archive")); assertEquals(1234000,model.timestamp);
            Message forged = message(original.getBody()); forged.setFrom(JidCreate.entityBareFrom("mallory@example.org"));
            assertThrows(java.security.GeneralSecurityException.class,() -> Omemo2Payload.content(forged,envelope));
        }
    }
    @Test void outerMediaAndEditsCannotBeImportedAsProtectedContentOrUnreadableMetadata() throws Exception {
        FileShare.initialize(); MessageCorrection.initialize();
        Message original = message("Authenticated text");
        Message decoded = Omemo2Payload.content(original,Omemo2Payload.envelope(original,new SecureRandom()));
        Message outer = message("Server fallback"); outer.setStanzaId("ciphertext");
        FileShare forged = new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>Forged.png</name></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='https://example.org/forged.png'/></sources></file-sharing>");
        outer.setBody(forged.body("Server fallback")); forged.addTo(outer,outer.getBody());
        new ReplyReference("mallory@example.org","forged-reply").addTo(outer); new MessageCorrection("forged-edit").addTo(outer);
        org.jxmpp.jid.EntityBareJid own = JidCreate.entityBareFrom("bob@example.org");
        XmppMessage good = XmppMessage.modern(outer,decoded,own,null,null);
        assertEquals("Authenticated text",good.body); assertNull(good.attachment); assertNull(good.sharing); assertNull(good.reply); assertNull(good.correction);
        XmppMessage missing = XmppMessage.modern(outer,null,own,"archive",new java.util.Date(1234000));
        assertTrue(missing.encrypted); assertFalse(missing.readable); assertNull(missing.body); assertNull(missing.sharing); assertNull(missing.attachment); assertNull(missing.reply); assertNull(missing.correction);
        assertEquals(XmppMessage.Encryption.OMEMO2,missing.encryption);
        decoded.setFrom(JidCreate.entityBareFrom("mallory@example.org"));
        assertThrows(IllegalArgumentException.class,() -> XmppMessage.modern(outer,decoded,own,null,null));
    }
    @Test void validOptionalAffixesAreCheckedAndMalformedOrServerMetadataIsRejected() throws Exception {
        Message outer = message("Text"); byte[] encoded = Omemo2Payload.envelope(outer,new SecureRandom()); String xml = new String(encoded,StandardCharsets.UTF_8);
        String withTime = xml.replace("</envelope>","<time stamp='2026-10-16T12:00:00Z'/></envelope>");
        long sent = org.jxmpp.util.XmppDateTime.parseXEP0082Date("2026-10-16T12:00:00Z").getTime();
        assertEquals("Text",Omemo2Payload.content(outer,withTime.getBytes(StandardCharsets.UTF_8),sent).getBody());
        assertThrows(java.security.GeneralSecurityException.class,() -> Omemo2Payload.content(outer,withTime.getBytes(StandardCharsets.UTF_8),sent+3600000));
        for (String invalid : new String[]{xml.replace("jid='bob@example.org'","jid='mallory@example.org'"),xml.replace("<rpad>","<rpad/><rpad>"),xml.replace("</content>","<encrypted xmlns='urn:xmpp:omemo:2'/></content>")}) {
            assertThrows(java.security.GeneralSecurityException.class,() -> Omemo2Payload.content(outer,invalid.getBytes(StandardCharsets.UTF_8)));
        }
        outer.setType(Message.Type.groupchat); assertThrows(java.security.GeneralSecurityException.class,() -> Omemo2Payload.envelope(outer,new SecureRandom()));
    }
    @Test void formattedAndLiteralBodiesRoundTripOnlyInsideAuthenticatedContentAndOldPlainBodyStillWorks() throws Exception {
        MessageMarkup.initialize();
        MessageMarkup markup=new MessageMarkup("Private 😀",java.util.Collections.singletonList(new MessageMarkup.Range(0,9,java.util.Collections.singleton(MessageMarkup.Style.STRONG),null))).styled();
        for(MessageMarkup value:java.util.Arrays.asList(markup,new MessageMarkup("literal *markers*",java.util.Collections.emptyList()).styled())) {
            Message clear=message(value.body);value.addTo(clear);
            byte[] payload=Omemo2Payload.envelope(clear,new SecureRandom());
            try(Omemo2Payload.Sealed encrypted=Omemo2Payload.seal(payload,new SecureRandom())) {
                Message outer=message("Server fallback");
                assertFalse(outer.hasExtension("markup",MessageMarkup.NAMESPACE));assertFalse(outer.hasExtension("unstyled",MessageMarkup.STYLING));
                Message decoded=Omemo2Payload.content(outer,Omemo2Payload.open(encrypted.ciphertext,encrypted.material));
                assertEquals(value.display().body,MessageMarkup.from(decoded,decoded.getBody()).display().body);
                assertEquals(clear.getExtensions("markup",MessageMarkup.NAMESPACE).size(),decoded.getExtensions("markup",MessageMarkup.NAMESPACE).size());
                assertEquals(clear.getExtensions("unstyled",MessageMarkup.STYLING).size(),decoded.getExtensions("unstyled",MessageMarkup.STYLING).size());
            }
        }
        Message original=message("Unformatted original overload");
        Message decoded=Omemo2Payload.content(original,Omemo2Payload.envelope(original,new SecureRandom()));
        assertEquals(original.getBody(),decoded.getBody());
        assertFalse(decoded.hasExtension("markup",MessageMarkup.NAMESPACE));assertFalse(decoded.hasExtension("unstyled",MessageMarkup.STYLING));
    }
    @Test void malformedKnownFormattingPreservesInboundTextWhileOutboundAndUnknownNamespacesStayStrict() throws Exception {
        MessageMarkup.initialize();Message original=message("Text");
        String envelope=new String(Omemo2Payload.envelope(original,new SecureRandom()),StandardCharsets.UTF_8);
        String[] invalid={
                "<markup xmlns='urn:xmpp:markup:0'><span start='0' end='99'><strong/></span></markup>",
                "<markup xmlns='urn:xmpp:markup:0'><span start='-1' end='2'><strong/></span></markup>",
                "<unstyled xmlns='urn:xmpp:styling:0'>text</unstyled>",
                "<unstyled xmlns='urn:xmpp:styling:0'/><unstyled xmlns='urn:xmpp:styling:0'/>",
                "<markup xmlns='urn:unknown:format'/>",
                "<unstyled xmlns='urn:unknown:format'/>"};
        for(String child:invalid) {
            String inbound=envelope.replace("</content>",child+"</content>");
            if(child.contains("urn:unknown:format"))
                assertThrows(java.security.GeneralSecurityException.class,() -> Omemo2Payload.content(original,inbound.getBytes(StandardCharsets.UTF_8)));
            else {
                Message readable=Omemo2Payload.content(original,inbound.getBytes(StandardCharsets.UTF_8));
                assertEquals("Text",readable.getBody());
                assertFalse(readable.hasExtension("markup",MessageMarkup.NAMESPACE));assertFalse(readable.hasExtension("unstyled",MessageMarkup.STYLING));
            }
            Message outbound=PacketParserUtils.parseStanza("<message xmlns='jabber:client'><body>Text</body>"+child+"</message>");
            outbound.setFrom(original.getFrom());outbound.setTo(original.getTo());outbound.setType(Message.Type.chat);
            assertThrows(java.security.GeneralSecurityException.class,() -> Omemo2Payload.envelope(outbound,new SecureRandom()));
        }
        String nested="<future xmlns='urn:unknown:format'/>";
        for(int depth=0;depth<18;depth++) nested="<future xmlns='urn:unknown:format'>"+nested+"</future>";
        String tooDeep=envelope.replace("</content>","<markup xmlns='urn:xmpp:markup:0'>"+nested+"</markup></content>");
        assertEquals("Text",Omemo2Payload.content(original,tooDeep.getBytes(StandardCharsets.UTF_8)).getBody());
    }
}
