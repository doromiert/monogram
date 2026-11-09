// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import eu.siacs.conversations.crypto.axolotl.*;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.pqid.*;

class PqMonoclesInteropTest {
    @BeforeAll static void providers() {
        ProviderManager.addExtensionProvider("encrypted",PqOmemoBundle.NS,StandardExtensionElementProvider.INSTANCE);
        MessageCorrection.initialize(); FileShare.initialize();
    }
    private String receiveMonocles(PqOmemoNativeTest.Pair pair,Message wire) throws Exception {return receiveMonocles(pair,wire,pair.bob.jid);}
    private String receiveMonocles(PqOmemoNativeTest.Pair pair,Message wire,String destination) throws Exception {
        try (PqOmemoRecords tx = pair.b.begin()) {
            PqOmemoSignalStore store = pair.bob.store(tx);
            SignalProtocolAddress remote = pair.alice.address();
            XmppAxolotlSession session = new XmppAxolotlSession(new SessionCipher(store,pair.bob.address(),remote),remote,pair.aliceBundle.fingerprint());
            XmppOmemo2Message decoded = XmppOmemo2Message.fromElement(Element.parse(wire.getExtension("encrypted",PqOmemoBundle.NS).toXML().toString()),Jid.of(pair.alice.jid));
            assertNotNull(decoded);
            XmppOmemo2Message.DecryptedSce clear = decoded.decrypt(session,pair.bob.id,Jid.of(pair.bob.jid),Jid.of(destination),System.currentTimeMillis(),
                    new XmppOmemo2Message.PqIdentityPins() {
                        public byte[] pinnedPqIdentity(String name,String fingerprint) { return pair.alice.jid.equals(name) ? pair.aliceBundle.pqIdentity() : null; }
                        public byte[] pinIfAbsent(String name,String fingerprint,byte[] key) { throw new AssertionError("Unknown identity must not be automatically approved"); }
                    });
            assertNotNull(clear); tx.commit(); return clear.body;
        }
    }
    private Message sendMonocles(PqOmemoNativeTest.Pair pair,String body,List<Element> extra) throws Exception {return sendMonocles(pair,body,extra,pair.alice.jid);}
    private Message sendMonocles(PqOmemoNativeTest.Pair pair,String body,List<Element> extra,String destination) throws Exception {
        try (PqOmemoRecords tx = pair.b.begin()) {
            PqOmemoSignalStore store = pair.bob.store(tx); SignalProtocolAddress remote = pair.alice.address();
            store.requireTrusted(remote,pair.aliceBundle.identity,pair.aliceBundle.pqIdentity());
            if (!store.containsSession(remote)) new SessionBuilder(store,remote,pair.bob.address()).process(pair.aliceBundle.sessionBundle(pair.alice.id,new java.security.SecureRandom()));
            XmppOmemo2Message message = new XmppOmemo2Message(Jid.of(pair.bob.jid),pair.bob.id,(sender,id,entries) -> {
                try {
                    PqIdentityKeyPair pq = pair.bob.pq(tx); byte[] publicKey = pq.getPublicKey().serialize();
                    byte[] transcript = PqAuth.transcript(sender.toString(),id,store.getIdentityKeyPair().getPublicKey(),publicKey,entries);
                    return new XmppOmemo2Message.PqAuthSignature(publicKey,pq.sign(transcript),store.getIdentityKeyPair().getPrivateKey().calculateSignature(transcript));
                } catch (Exception error) { throw new CryptoFailedException(error); }
            });
            message.encrypt(body,extra,Jid.of(destination),false);
            assertTrue(message.addDevice(new XmppAxolotlSession(new SessionCipher(store,pair.bob.address(),remote),remote,pair.aliceBundle.fingerprint())));
            StandardExtensionElement encrypted = StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(message.toElement().toString()));
            message.wipeMessageKey();
            Message wire = new Message(JidCreate.entityBareFrom(pair.alice.jid),Message.Type.chat); wire.setFrom(JidCreate.entityFullFrom(pair.bob.jid + "/monocles"));
            wire.setStanzaId(UUID.randomUUID().toString()); wire.addExtension(encrypted); tx.commit(); return wire;
        }
    }
    @Test void monogramInitiatesThenExchangesMessagesWithUnmodifiedMonoclesCodec() throws Exception {
        PqOmemoNativeTest.Pair pair = new PqOmemoNativeTest.Pair();
        assertEquals("Monogram → Monocles 😀",receiveMonocles(pair,pair.send(pair.alice,pair.bob,pair.bobBundle,"Monogram → Monocles 😀")));
        for (int i = 0; i < 8; i++) {
            Message reply = sendMonocles(pair,"Monocles reply " + i,null);
            assertEquals("Monocles reply " + i,pair.receive(pair.alice,pair.bob,reply).getBody());
            assertEquals("Monogram reply " + i,receiveMonocles(pair,pair.send(pair.alice,pair.bob,pair.bobBundle,"Monogram reply " + i)));
        }
    }
    @Test void monoclesCanInitiateAndProtectCaptionMetadata() throws Exception {
        PqOmemoNativeTest.Pair pair = new PqOmemoNativeTest.Pair();
        Element replacement = new Element("replace","urn:xmpp:message-correct:0").setAttribute("id","earlier-message");
        Message reply = sendMonocles(pair,"Monocles initiates",Collections.singletonList(replacement));
        Message decoded = pair.receive(pair.alice,pair.bob,reply);
        assertEquals("Monocles initiates",decoded.getBody()); assertEquals("earlier-message",MessageCorrection.from(decoded).id);
        assertEquals("response",receiveMonocles(pair,pair.send(pair.alice,pair.bob,pair.bobBundle,"response")));
    }
    @Test void sameSessionsInteroperateInDirectChatsAndRoomsAndBindTheRoom() throws Exception {
        PqOmemoNativeTest.Pair pair=new PqOmemoNativeTest.Pair();String room="room@conference.example.org";
        assertEquals("DM before room",receiveMonocles(pair,pair.send(pair.alice,pair.bob,pair.bobBundle,"DM before room")));
        for(int i=0;i<3;i++) {
            Message clear=new Message(JidCreate.entityBareFrom(room),Message.Type.groupchat);
            clear.setFrom(JidCreate.entityBareFrom(pair.alice.jid));clear.setBody("group "+i);clear.setStanzaId(UUID.randomUUID().toString());
            Message wire=new Message(JidCreate.entityBareFrom(pair.bob.jid),Message.Type.groupchat);
            wire.setFrom(JidCreate.entityFullFrom(room+"/Alice"));wire.setStanzaId(clear.getStanzaId());
            wire.addExtension(PqOmemoMessage.encrypt(pair.alice,clear,Collections.singletonList(new PqOmemoMessage.Target(pair.bob.jid,pair.bob.id,pair.bobBundle)),new java.security.SecureRandom()));
            assertEquals("group "+i,receiveMonocles(pair,wire,room));
            Message reply=sendMonocles(pair,"group reply "+i,Arrays.asList(new Element("replace","urn:xmpp:message-correct:0").setAttribute("id","old"),new Element("optional","urn:example:future-feature")),room);
            reply.setType(Message.Type.groupchat);reply.setFrom(JidCreate.entityFullFrom(room+"/Bob"));
            assertThrows(Exception.class,() -> PqOmemoMessage.decrypt(pair.alice,reply,pair.bob.jid,"wrong@conference.example.org",System.currentTimeMillis()));
            Message content=PqOmemoMessage.decrypt(pair.alice,reply,pair.bob.jid,room,System.currentTimeMillis());
            assertEquals("group reply "+i,content.getBody());assertEquals("old",MessageCorrection.from(content).id);
            XmppRoomMessage model=XmppRoomMessage.protectedContent(reply,content,JidCreate.entityBareFrom(pair.alice.jid),"Alice",XmppMessage.Encryption.PQ_OMEMO,null,null);
            assertNotNull(model);assertEquals(XmppMessage.Encryption.PQ_OMEMO,model.encryption);
        }
        assertEquals("DM after room",receiveMonocles(pair,pair.send(pair.alice,pair.bob,pair.bobBundle,"DM after room")));
    }

}
