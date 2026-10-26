package com.negzero.monogram.xmpp;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.jivesoftware.smack.packet.Message;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Disconnected session: recovery delivery is exercised without contacting any server. */
class XmppRecoveredRoomRouteTest {
    @Test void restoredRoomMetadataBlocksRetainedDirectCorrectionsForBothEncryptionFormats() throws Exception {
        Field keyRoot=XmppSession.class.getDeclaredField("keyRoot");keyRoot.setAccessible(true);
        if(keyRoot.get(null)==null) {
            String integration=System.getenv("MONOGRAM_IT_STATE");
            File root=integration==null ? Files.createTempDirectory("monogram-recovered-route").toFile() : new File(integration);
            XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
        }
        File trust=Files.createTempFile("monogram-recovered-route-trust",".json").toFile();trust.delete();trust.deleteOnExit();
        List<XmppMessage> delivered=new ArrayList<>();
        XmppSession.Listener listener=new XmppSession.Listener() {
            public void message(EntityBareJid sender,String id,String body,boolean encrypted) { fail("Unexpected legacy listener bridge"); }
            // Leave unaccepted: the test must never mutate journals or send protocol replies.
            public void message(XmppMessage message,Runnable accepted) { delivered.add(message); }
        };
        EntityBareJid own=JidCreate.entityBareFrom("route-owner@example.org");
        try(XmppSession session=new XmppSession(new Account(own.toString(),"127.0.0.1",5222,"RecoveredRoute"),"unused",910071,trust,listener)) {
            Method legacy=XmppSession.class.getDeclaredMethod("deliverLegacy",LegacyIncomingJournal.Received.class);legacy.setAccessible(true);
            Method modern=XmppSession.class.getDeclaredMethod("deliverDecodedModern",XmppMessage.class,XmppOmemo2.Received.class,DeliveryAcknowledgement.class);modern.setAccessible(true);
            String room="friends@conference.example.org";
            // These entries were accepted before the sender was known to be a room.
            List<XmppMessage> retained=new ArrayList<>();
            for(String nickname:new String[]{"OldNick","ReusedNick"}) {
                Message wire=new Message(own,Message.Type.chat);wire.setFrom(JidCreate.entityFullFrom(room+"/"+nickname));
                wire.setBody("Must not replace a bare-room message");wire.setStanzaId("pm-"+nickname);
                new MessageCorrection("shared-original-id").addTo(wire);
                XmppMessage old=XmppMessage.from(wire,own,wire.getBody(),true,true,null,null);
                XmppMessage current=XmppMessage.modern(wire,wire,own,null,null);
                assertNotNull(old.correction);assertNotNull(current.correction);
                retained.add(old);retained.add(current);
            }
            // The native controller restores room identities before invoking recoverIncoming().
            session.rooms.remember(room);
            for(XmppMessage message:retained) {
                if(message.encryption==XmppMessage.Encryption.LEGACY_OMEMO)
                    legacy.invoke(session,new LegacyIncomingJournal.Received(message,null,null,null,null,null,false));
                else modern.invoke(session,message,null,null);
            }
            assertTrue(delivered.isEmpty(),"Retained PMs must not become room-wide DM corrections after restart or nickname reuse");

            Message direct=new Message(own,Message.Type.chat);direct.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));direct.setBody("Ordinary retained DM");
            XmppMessage old=XmppMessage.from(direct,own,direct.getBody(),true,true,null,null);
            XmppMessage current=XmppMessage.modern(direct,direct,own,null,null);
            legacy.invoke(session,new LegacyIncomingJournal.Received(old,null,null,null,null,null,false));
            modern.invoke(session,current,null,null);
            assertEquals(2,delivered.size());assertSame(old,delivered.get(0));assertSame(current,delivered.get(1));
        }
    }
}
