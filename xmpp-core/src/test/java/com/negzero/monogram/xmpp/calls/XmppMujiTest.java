package com.negzero.monogram.xmpp.calls;

import org.junit.jupiter.api.Test;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import static org.junit.jupiter.api.Assertions.*;

class XmppMujiTest {
    private Presence presence(String from,String real,String inner)throws Exception {
        ProviderManager.addExtensionProvider("muji",XmppMuji.NS,StandardExtensionElementProvider.INSTANCE);
        return PacketParserUtils.parseStanza("<presence xmlns='jabber:client' from='"+from+"'><x xmlns='http://jabber.org/protocol/muc#user'><item affiliation='member' role='participant' jid='"+real+"'/></x>"+inner+"</presence>");
    }
    @Test void readinessRequiresServerAttributedFullResourceAndSupportedAudio()throws Exception {
        String ready=XmppMuji.advertisement(true,true).toXML().toString();
        XmppMuji.Participant participant=XmppMuji.parse(presence("room@conference.example.org/Bob","bob@example.org/phone",ready));
        assertNotNull(participant);assertFalse(participant.preparing);assertTrue(participant.video);assertEquals("bob@example.org/phone",participant.realJid);
        assertNull(XmppMuji.parse(presence("room@conference.example.org/Bob","bob@example.org",ready)));
        assertNull(XmppMuji.parse(presence("room@conference.example.org/Bob","bob@example.org/phone",ready.replace("opus","unknown"))));
        Presence duplicate=presence("room@conference.example.org/Bob","bob@example.org/phone",ready);
        duplicate.addExtension(org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor("<x xmlns='http://jabber.org/protocol/muc#user'><item jid='mallory@example.org/phone'/></x>")));
        assertNull(XmppMuji.parse(duplicate));
        Presence left=presence("room@conference.example.org/Bob","bob@example.org/phone",ready);left.setType(Presence.Type.unavailable);assertNull(XmppMuji.parse(left));
        assertTrue(XmppMuji.parse(presence("room@conference.example.org/Bob","bob@example.org/phone",XmppMuji.advertisement(false,false).toXML().toString())).preparing);
    }
    @Test void deterministicInitiatorMatchesMonoclesNickOrderingAndCannotInitiateTwice(){
        assertTrue(XmppMuji.initiate("Room@conference.example.org/Zoe","room@conference.example.org/Alice"));
        assertFalse(XmppMuji.initiate("room@conference.example.org/Alice","Room@conference.example.org/Zoe"));
        assertFalse(XmppMuji.initiate("room@conference.example.org/Alice","room@conference.example.org/Alice"));
    }
}
