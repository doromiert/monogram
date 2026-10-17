package com.negzero.monogram.xmpp;

import java.util.*;
import java.security.SecureRandom;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageReactionsTest {
    @Test void replacementRemovalAndMalformedSets() throws Exception {
        MessageReactions.initialize();
        Message message=PacketParserUtils.parseStanza("<message from='bob@example.org/phone' to='alice@example.org' type='chat' id='update'><reactions xmlns='urn:xmpp:reactions:0' id='original'><reaction>👋</reaction><reaction>👋</reaction><reaction>👨‍👩‍👧‍👦</reaction></reactions></message>");
        MessageReactions reactions=MessageReactions.from(message);
        assertEquals("original",reactions.id); assertEquals(2,reactions.emojis.size());
        XmppMessage model=XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),null,false,true,null,null);
        assertNotNull(model); assertNull(model.body); assertEquals(reactions.emojis,model.reactions.emojis);
        Message remove=new Message(); new MessageReactions("original",Collections.emptyList()).addTo(remove);
        assertTrue(MessageReactions.from(remove).emojis.isEmpty());
        message.addExtension(StandardExtensionElement.builder("reactions",MessageReactions.NAMESPACE).addAttribute("id","other").build());
        assertNull(MessageReactions.from(message));
        assertNull(XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),null,false,true,null,null));
        assertThrows(IllegalArgumentException.class,() -> new MessageReactions("",Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,() -> new MessageReactions("target",Collections.singleton("bad\nreaction")));
    }
    @Test void modernReactionsAreAuthenticatedAndOuterReactionsCannotChangeThem() throws Exception {
        MessageReactions.initialize();
        Message clear=new Message(JidCreate.entityBareFrom("alice@example.org"),Message.Type.chat);
        clear.setFrom(JidCreate.entityBareFrom("bob@example.org")); clear.setStanzaId("encrypted-update");
        new MessageReactions("original",Arrays.asList("👍","🐢")).addTo(clear);
        byte[] envelope=Omemo2Payload.envelope(clear,new SecureRandom());
        Message authenticated=Omemo2Payload.content(clear,envelope);
        Message outer=new Message(clear.getTo(),Message.Type.chat); outer.setFrom(clear.getFrom()); outer.setStanzaId(clear.getStanzaId());
        new MessageReactions("forged",Collections.singleton("👎")).addTo(outer);
        XmppMessage decoded=XmppMessage.modern(outer,authenticated,JidCreate.entityBareFrom("alice@example.org"),null,null);
        assertEquals("original",decoded.reactions.id); assertEquals(2,decoded.reactions.emojis.size()); assertTrue(decoded.encrypted); assertNull(decoded.body);
        assertNull(XmppMessage.modern(outer,null,JidCreate.entityBareFrom("alice@example.org"),null,null).reactions);
        clear.setBody("Visible messages must not also replace reactions");
        assertNull(XmppMessage.modern(clear,clear,JidCreate.entityBareFrom("alice@example.org"),null,null));
    }
    @Test void roomReactionKeepsAuthorAndRoomAssignedId() throws Exception {
        MessageReactions.initialize();
        Message message=PacketParserUtils.parseStanza("<message type='groupchat' from='friends@conference.example.org/Bob' to='alice@example.org' id='update'><reactions xmlns='urn:xmpp:reactions:0' id='room-original'><reaction>👋</reaction></reactions><x xmlns='http://jabber.org/protocol/muc#user'><item jid='bob@example.org/phone' affiliation='member' role='participant'/></x></message>");
        message.addExtension(new StanzaIdElement("room-update","friends@conference.example.org"));
        XmppRoomMessage room=XmppRoomMessage.from(message,JidCreate.entityBareFrom("friends@conference.example.org"),JidCreate.entityBareFrom("alice@example.org"),"Alice",null,false,true);
        assertNotNull(room); assertEquals("room-original",room.reactions.id); assertNull(room.realSender,"Sender-supplied MUC items are not authoritative"); assertFalse(room.realSenderVerified); assertFalse(room.outgoing);
        assertEquals("room-update",room.ids.get("archive")); assertNull(room.body);
        assertNull(XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),null,false,true,null,null));
    }
    private Message fallbackReaction(String type,String from) throws Exception {
        MessageReactions.initialize(); ReplyReference.initialize();
        return PacketParserUtils.parseStanza("<message xmlns='jabber:client' type='"+type+"' from='"+from+"' to='alice@example.org' id='fallback-update'>"
                +"<body>&gt; Original message\n\n🔥</body><reactions xmlns='urn:xmpp:reactions:0' id='original'><reaction>🔥</reaction></reactions>"
                +"<reply xmlns='urn:xmpp:reply:0' id='original' to='bob@example.org'/>"
                +"<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reactions:0'><body/></fallback></message>");
    }
    @Test void monoclesFallbackIsAReactionForLiveAndArchivedDirectMessages() throws Exception {
        Message wire=fallbackReaction("chat","bob@example.org/phone");
        XmppMessage live=XmppMessage.from(wire,JidCreate.entityBareFrom("alice@example.org"),wire.getBody(),false,true,null,null);
        assertNotNull(live);assertEquals(Collections.singleton("🔥"),live.reactions.emojis);assertNull(live.body);assertNull(live.reply);
        XmppMessage archived=XmppMessage.from(wire,JidCreate.entityBareFrom("alice@example.org"),wire.getBody(),false,true,"mam-reaction",new Date(100000));
        assertNotNull(archived);assertTrue(archived.archived);assertEquals(100000,archived.timestamp);assertEquals("mam-reaction",archived.ids.get("archive"));
        wire.removeExtension("fallback",ReplyReference.FALLBACK);
        assertNull(XmppMessage.from(wire,JidCreate.entityBareFrom("alice@example.org"),wire.getBody(),false,true,null,null));
    }
    @Test void legacyReactionMetadataDoesNotRequireOrClaimDecryption() throws Exception {
        Message wire=fallbackReaction("chat","bob@example.org/phone");
        wire.addExtension(StandardExtensionElement.builder("encrypted","eu.siacs.conversations.axolotl").addElement("payload","opaque-ciphertext").build());
        XmppMessage metadata=XmppMessage.from(wire,JidCreate.entityBareFrom("alice@example.org"),null,true,false,"mam-public",new Date(200000));
        assertNotNull(metadata);assertEquals(Collections.singleton("🔥"),metadata.reactions.emojis);assertTrue(metadata.readable);
        assertFalse(metadata.encrypted);assertEquals(XmppMessage.Encryption.PLAIN,metadata.encryption);assertNull(metadata.body);assertNull(metadata.reply);
        wire.addExtension(StandardExtensionElement.builder("encrypted",XmppOmemo2.NS).build());
        assertFalse(MessageReactions.publicLegacy(wire));
        XmppMessage protectedPending=XmppMessage.modern(wire,null,JidCreate.entityBareFrom("alice@example.org"),null,null);
        assertNotNull(protectedPending);assertNull(protectedPending.reactions);assertFalse(protectedPending.readable);
    }
    @Test void monoclesFallbackAndLegacyMetadataAlsoWorkInRoomsWithoutTrustingNicknameIdentity() throws Exception {
        Message wire=fallbackReaction("groupchat","friends@conference.example.org/Bob");
        wire.addExtension(new StanzaIdElement("server-reaction","friends@conference.example.org"));
        XmppRoomMessage live=XmppRoomMessage.from(wire,JidCreate.entityBareFrom("friends@conference.example.org"),JidCreate.entityBareFrom("alice@example.org"),"Alice",wire.getBody(),false,true);
        assertNotNull(live);assertNotNull(live.reactions);assertNull(live.body);assertNull(live.reply);assertNull(live.realSender);
        wire.addExtension(StandardExtensionElement.builder("encrypted","eu.siacs.conversations.axolotl").addElement("payload","opaque-ciphertext").build());
        XmppRoomMessage archived=XmppRoomMessage.archived(wire,JidCreate.entityBareFrom("friends@conference.example.org"),JidCreate.entityBareFrom("alice@example.org"),"Alice",null,true,false,"mam-room",new Date(300000));
        assertNotNull(archived);assertNotNull(archived.reactions);assertFalse(archived.encrypted);assertNull(archived.realSender);assertFalse(archived.realSenderVerified);
    }
    @Test void partialFallbackAndUnrelatedReplyCannotHideMixedMessageText() throws Exception {
        Message wire=fallbackReaction("chat","bob@example.org/phone");wire.removeExtension("fallback",ReplyReference.FALLBACK);
        wire.addExtension(StandardExtensionElement.builder("fallback",ReplyReference.FALLBACK).addAttribute("for",MessageReactions.NAMESPACE)
                .addElement(StandardExtensionElement.builder("body",ReplyReference.FALLBACK).addAttribute("start","0").addAttribute("end","1").build()).build());
        assertNull(XmppMessage.from(wire,JidCreate.entityBareFrom("alice@example.org"),wire.getBody(),false,true,null,null));
        wire=fallbackReaction("chat","bob@example.org/phone");wire.removeExtension("reply",ReplyReference.NAMESPACE);
        new ReplyReference("bob@example.org","different-target").addTo(wire);
        assertNull(XmppMessage.from(wire,JidCreate.entityBareFrom("alice@example.org"),wire.getBody(),false,true,null,null));
    }

}
