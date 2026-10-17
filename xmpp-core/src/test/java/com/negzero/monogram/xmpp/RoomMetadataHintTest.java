package com.negzero.monogram.xmpp;

import java.util.Collections;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomMetadataHintTest {
    @Test void readsAvatarHashesOnlyFromRoomInformationAndRejectsInvalidHashes() throws Exception {
        String hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        org.jivesoftware.smackx.xdata.packet.DataForm valid=org.jivesoftware.smackx.xdata.packet.DataForm.builder().setFormType("http://jabber.org/protocol/muc#roominfo")
                .addField(org.jivesoftware.smackx.xdata.FormField.textSingleBuilder("muc#roominfo_avatarhash").setValue(hash).build()).build();
        assertEquals(Collections.singleton(hash),XmppProfiles.roomAvatarHashes(valid));
        assertTrue(XmppProfiles.roomAvatarHashes(null).isEmpty());
        org.jivesoftware.smackx.xdata.packet.DataForm malformed=valid.asBuilder().removeField("muc#roominfo_avatarhash")
                .addField(org.jivesoftware.smackx.xdata.FormField.textSingleBuilder("muc#roominfo_avatarhash").setValue("not-a-hash").build()).build();
        assertThrows(java.io.IOException.class,() -> XmppProfiles.roomAvatarHashes(malformed));
    }
    Message hint(String from,int code) throws Exception {
        Message message=new Message();message.setType(Message.Type.groupchat);message.setFrom(JidCreate.from(from));
        message.setTo(JidCreate.from("alice@example.org/phone"));
        MUCUser user=new MUCUser();user.addStatusCodes(Collections.singleton(MUCUser.Status.create(code)));message.addExtension(user);
        return message;
    }
    @Test void acceptsOnlyLiveRoomAuthorityConfigurationNotifications() throws Exception {
        org.jxmpp.jid.BareJid account=JidCreate.bareFrom("alice@example.org");
        assertTrue(XmppRooms.metadataHint(hint("room@conference.example.org",104),account));
        assertFalse(XmppRooms.metadataHint(hint("room@conference.example.org/member",104),account));
        assertFalse(XmppRooms.metadataHint(hint("room@conference.example.org",110),account));
        Message wrongAccount=hint("room@conference.example.org",104);wrongAccount.setTo(JidCreate.from("bob@example.org/phone"));
        assertFalse(XmppRooms.metadataHint(wrongAccount,account));
        Message ordinary=hint("room@conference.example.org",104);ordinary.setType(Message.Type.normal);
        assertFalse(XmppRooms.metadataHint(ordinary,account));
        for(String namespace:new String[]{"urn:xmpp:delay","jabber:x:delay","urn:xmpp:forward:0","urn:xmpp:carbons:2","urn:xmpp:mam:2","urn:xmpp:mam:1"}) {
            Message replay=hint("room@conference.example.org",104);replay.addExtension(StandardExtensionElement.builder("delay",namespace).build());
            assertFalse(XmppRooms.metadataHint(replay,account));
        }
    }
}
