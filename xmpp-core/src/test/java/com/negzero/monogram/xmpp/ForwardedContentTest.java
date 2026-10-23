package com.negzero.monogram.xmpp;

import java.util.Date;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForwardedContentTest {
    @Test void canonicalUnicodeFormPreservesContentAndRejectsAmbiguousHeaders() {
        ForwardedContent original=ForwardedContent.of("Alice 🧑‍💻",1700000000,"hello\n\nworld");
        ForwardedContent parsed=ForwardedContent.parse(original.wireBody());
        assertNotNull(parsed);assertEquals(original.name,parsed.name);assertEquals(original.body,parsed.body);assertEquals(original.date,parsed.date);
        assertNull(ForwardedContent.parse(original.wireBody().replace("2023-11-14","2023-02-30")));
        assertNull(ForwardedContent.parse(original.wireBody().replace("\n\n","\r\n\r\n")));
        assertNull(ForwardedContent.parse(original.wireBody().replace("Alice","Al\nce")));
        assertNull(ForwardedContent.parse(original.wireBody().replace("Alice","Al\u202Eice")));
        assertEquals("Alice",ForwardedContent.of("\u202eAlice\n",0,"").name);
        assertEquals("ordinary",ForwardedContent.payload("ordinary"));
    }
    @Test void legacy156ForwardedFileStillLoadsButMustNotBeUsedForNewWireSends() throws Exception {
        String url="aesgcm://files.example.org/new.bin#"+new String(new char[88]).replace('\0','a');
        String body=ForwardedContent.of("Original",1700000000,url).wireBody();
        Message message=new Message(JidCreate.entityBareFrom("alice@example.org"),Message.Type.chat);
        message.setFrom(JidCreate.entityFullFrom("bob@example.org/mobile"));message.setStanzaId("new-forward");
        XmppMessage direct=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),body,true,true,null,null);
        assertEquals(url,direct.attachment.wireUrl);assertEquals(body,direct.body);assertEquals("bob@example.org/mobile",direct.sender);
        message.setType(Message.Type.groupchat);message.setFrom(JidCreate.entityFullFrom("room@conference.example.org/Bob"));
        XmppRoomMessage room=XmppRoomMessage.from(message,JidCreate.entityBareFrom("room@conference.example.org"),JidCreate.entityBareFrom("alice@example.org"),"Alice",body,true,true);
        assertNotNull(room);assertEquals(url,room.attachment.wireUrl);assertEquals(body,room.body);
    }
    @Test void standaloneXepForwardCannotSupplyLiveSenderIdsOrActions() throws Exception {
        Message message=PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='bob@example.org/mobile' to='alice@example.org' type='chat' id='outer'><body>Comment</body><forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-10-23T09:00:00Z'/><message xmlns='jabber:client' from='original@example.org/device' type='chat' id='forged'><body>Hello</body><origin-id xmlns='urn:xmpp:sid:0' id='forged-origin'/><retract xmlns='urn:xmpp:message-retract:1' id='victim'/></message></forwarded></message>");
        XmppMessage model=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),message.getBody(),false,true,null,null);
        ForwardedContent parsed=ForwardedContent.parse(model.body);
        assertNotNull(parsed);assertEquals("original@example.org/device",parsed.name);assertEquals("Comment\n\nHello",parsed.body);
        assertEquals("bob@example.org/mobile",model.sender);assertEquals("outer",model.ids.get("stanza"));
        assertFalse(model.ids.containsValue("forged"));assertFalse(model.ids.containsValue("forged-origin"));assertNull(model.retraction);
        XmppMessage legacy=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),"Protected body",true,true,null,null);
        assertEquals("Protected body",legacy.body);
    }
    @Test void modernForwardHeaderLivesInsideProtectedBody() throws Exception {
        MessageMarkup.initialize();String text=ForwardedContent.of("Alice",1700000000,"private").wireBody();
        Message clear=new Message(JidCreate.entityBareFrom("alice@example.org"),Message.Type.chat);
        clear.setFrom(JidCreate.entityFullFrom("bob@example.org/device"));clear.setStanzaId("modern");clear.setBody(text);
        Message decoded=Omemo2Payload.content(clear,Omemo2Payload.envelope(clear,new java.security.SecureRandom()));
        assertEquals(text,decoded.getBody());
        XmppMessage model=XmppMessage.modern(clear,decoded,JidCreate.entityBareFrom("alice@example.org"),null,new Date());
        assertEquals("Alice",ForwardedContent.parse(model.body).name);assertEquals(XmppMessage.Encryption.OMEMO2,model.encryption);
    }
    @Test void fullXmppAddressesAreNotReducedToNicknamesOrTruncated() {
        String address=new String(new char[120]).replace('\0','a')+"@long-server.example.org";
        ForwardedContent forward=ForwardedContent.of(address,1700000000,"hello");
        assertEquals(address,forward.name);assertEquals(address,ForwardedContent.parse(forward.wireBody()).name);
        assertEquals("alice@example.org",ForwardedContent.address("alice@example.org"));
        assertNull(ForwardedContent.address("a display name"));assertNull(ForwardedContent.address("https://example.org"));
        assertEquals("room@conference.example.org/Anon",ForwardedContent.address("room@conference.example.org/Anon"));
    }
    @Test void newLegacyMediaModelsAreAcceptedByStrictUriOnlyReceivers() throws Exception {
        String url="aesgcm://files.example.org/new.jpg#"+new String(new char[88]).replace('\0','a');
        FileLink link=FileLink.parse(url);
        XmppMessage direct=XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","fresh-upload",link,true,null);
        // XEP-0454 / Monocles MessageUtils accepts one URI, optionally one data-URI thumbnail line.
        java.net.URI receiver=new java.net.URI(direct.body);
        assertEquals("aesgcm",receiver.getScheme());assertTrue(receiver.getFragment().matches("[0-9a-f]{88}"));
        assertEquals(1,direct.body.split("\n",-1).length);assertEquals(url,direct.body);
        XmppRoomMessage room=XmppRoomMessage.sentFile(JidCreate.entityBareFrom("room@conference.example.org"),JidCreate.entityBareFrom("alice@example.org"),"Alice","fresh-room-upload",url,true,null,null,null);
        assertEquals(url,room.body);
    }
}
