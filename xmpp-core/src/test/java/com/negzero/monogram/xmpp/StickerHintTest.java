package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StickerHintTest {
    static final String URL="aesgcm://example.org/opaque.webp#"+new String(new char[88]).replace('\0','a');
    static Message wire(String marker) throws Exception {
        FileShare.initialize();return PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='bob@example.org/phone' to='alice@example.org' type='chat' id='sticker'>"+marker+"</message>");
    }
    static XmppMessage direct(Message wire,String body,boolean encrypted,boolean readable) throws Exception {
        return XmppMessage.from(wire,JidCreate.entityBareFrom("alice@example.org"),body,encrypted,readable,null,null);
    }
    @Test void legacyMarkerIsOnlyAPresentationHintForIndependentlyDecryptedAttachments() throws Exception {
        Message wire=wire("");StickerHint.addTo(wire);
        XmppMessage valid=direct(wire,URL,true,true);
        assertTrue(valid.displayStickerHint);assertNull(valid.sharing);assertEquals(URL,valid.attachment.wireUrl);
        assertTrue(valid.withSenderFingerprint("fixture").displayStickerHint);
        assertFalse(direct(wire,"ordinary text",true,true).displayStickerHint);
        assertFalse(direct(wire,null,true,false).displayStickerHint);
        assertFalse(direct(wire,URL,false,true).displayStickerHint);
        assertFalse(direct(wire(""),URL,true,true).displayStickerHint);
        for(String marker:new String[]{"<sticker xmlns='urn:xmpp:stickers:0' pack='untrusted'/>","<sticker xmlns='urn:xmpp:stickers:0'><source>untrusted</source></sticker>","<sticker xmlns='urn:xmpp:stickers:0'>not empty</sticker>","<sticker xmlns='urn:xmpp:stickers:0'/><sticker xmlns='urn:xmpp:stickers:0'/>"})
            assertFalse(direct(wire(marker),URL,true,true).displayStickerHint);
    }
    @Test void roomHintsDoNotCreateFileMetadataAndPlainModernBoundariesRemainSeparate() throws Exception {
        Message room=wire("");room.setType(Message.Type.groupchat);room.setFrom(JidCreate.entityFullFrom("room@conference.example.org/Bob"));StickerHint.addTo(room);
        XmppRoomMessage result=XmppRoomMessage.from(room,JidCreate.entityBareFrom("room@conference.example.org"),JidCreate.entityBareFrom("alice@example.org"),"Alice",URL,true,true);
        assertNotNull(result);assertTrue(result.displayStickerHint);assertNull(result.sharing);
        assertTrue(result.withOccupantId("occupant").displayStickerHint);
        assertFalse(StickerHint.from(room,false,true,FileLink.parse(URL)));
        assertFalse(StickerHint.from(room,true,true,FileLink.parse("https://example.org/plain.webp")));
    }
}
