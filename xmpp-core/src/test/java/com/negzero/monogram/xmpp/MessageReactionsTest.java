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
}
