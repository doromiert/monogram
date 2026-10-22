package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomDisplayedMarkerTest {
    final EntityBareJid room=JidCreate.entityBareFrom("room@conference.example.org"),own=JidCreate.entityBareFrom("alice@example.org");
    RoomDisplayedMarkerTest() throws Exception {}
    Message marker() throws Exception {
        Message wire=new Message(own,Message.Type.groupchat);wire.setFrom(JidCreate.entityFullFrom(room+"/Bob"));
        wire.addExtension(StandardExtensionElement.builder("displayed",MessageReceipt.MARKERS).addAttribute("id","room-id").build());return wire;
    }
    @Test void roomReferencesRequireAnUnambiguousRoomAssignedIdAndDiscoveredSupport() throws Exception {
        Message wire=marker();wire.removeExtension("displayed",MessageReceipt.MARKERS);wire.setStanzaId("sender-id");wire.setBody("hello");
        wire.addExtension(new StanzaIdElement("room-id",room.toString()));
        XmppRoomMessage model=XmppRoomMessage.from(wire,room,own,"Alice","hello",false,true);
        assertNull(RoomDisplayedMarker.tag(model,wire,false).ids.get(RoomDisplayedMarker.LOCAL_ID));
        XmppRoomMessage tagged=RoomDisplayedMarker.tag(model,wire,true);
        assertEquals("room-id",tagged.ids.get(RoomDisplayedMarker.LOCAL_ID));
        assertEquals("room-id",tagged.withAliases(Collections.singletonMap("mam","archive-id")).ids.get(RoomDisplayedMarker.LOCAL_ID));
        wire.addExtension(new StanzaIdElement("second-id",room.toString()));assertNull(RoomDisplayedMarker.assignedId(wire,room));
    }
    @Test void readerIdentityAndMessageShapeMustBeValidBeforeProducingAReceipt() throws Exception {
        assertNull(RoomDisplayedMarker.from(marker(),room,null,false));
        RoomDisplayedMarker read=RoomDisplayedMarker.from(marker(),room,"id:stable-Bob",false);
        assertNotNull(read);assertEquals("room-id",read.id);assertFalse(read.own);
        assertEquals(room+"/Bob",read.occupant);assertEquals("id:stable-Bob",read.actor);
        Message mixed=marker();mixed.setBody("message");assertNull(RoomDisplayedMarker.from(mixed,room,"id:stable-Bob",false));
        Message duplicate=marker();duplicate.addExtension(StandardExtensionElement.builder("displayed",MessageReceipt.MARKERS).addAttribute("id","other").build());assertNull(RoomDisplayedMarker.from(duplicate,room,"id:stable-Bob",false));
        Message foreign=marker();foreign.setFrom(JidCreate.entityFullFrom("other@conference.example.org/Bob"));assertNull(RoomDisplayedMarker.from(foreign,room,"id:stable-Bob",false));
    }
    @Test void onlyOwnSentDisplayedCopiesCanAdvanceOurLocalReadState() throws Exception {
        Message copy=new Message(JidCreate.entityBareFrom("bob@example.org"),Message.Type.chat);copy.setFrom(JidCreate.entityFullFrom(own+"/other-device"));
        copy.addExtension(StandardExtensionElement.builder("displayed",MessageReceipt.MARKERS).addAttribute("id","incoming-wire").build());
        assertEquals("incoming-wire",MessageReceipt.ownDisplayed(copy,own).id);
        copy.setBody("outgoing message");assertNull(MessageReceipt.ownDisplayed(copy,own));copy.setBody(null);
        copy.setFrom(JidCreate.entityFullFrom("mallory@example.org/device"));assertNull(MessageReceipt.ownDisplayed(copy,own));
    }
}
