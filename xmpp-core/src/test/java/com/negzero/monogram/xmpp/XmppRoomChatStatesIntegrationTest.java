package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppRoomChatStatesIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device,BlockingQueue<Boolean> typing,BlockingQueue<XmppRoomMessage> bodies) throws Exception {
        return new XmppSession(new Account("room"+user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"RoomTyping-"+device),
                "monogram-test",device,new File(root,"room-typing-trust-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) { fail("Room typing must not create a direct message"); }
            public void roomMessage(XmppRoomMessage message) { bodies.add(message); }
            public void roomTyping(EntityFullJid occupant,boolean composing) { typing.add(composing); }
        });
    }
    Message notification(XmppSession receiver,EntityFullJid occupant,ChatState state) {
        Message message=new Message(receiver.connection().getUser(),Message.Type.groupchat);
        message.setFrom(occupant); message.addExtension(new ChatStateExtension(state));
        ExtensionElement identity=MultiUserChatManager.getInstanceFor(receiver.connection()).getMultiUserChat(occupant.asEntityBareJid())
                .getOccupantPresence(occupant).getExtension("occupant-id","urn:xmpp:occupant-id:0");
        if(identity!=null) message.addExtension(identity);
        return message;
    }
    void assertState(BlockingQueue<Message> sent,ChatState expected) throws Exception {
        Message message=sent.poll(5,TimeUnit.SECONDS); assertNotNull(message,"Expected "+expected);
        assertEquals(expected,XmppChatStates.read(message)); assertEquals(Message.Type.groupchat,message.getType());
        assertNull(message.getBody()); assertFalse(message.hasExtension("encrypted","eu.siacs.conversations.axolotl"));
        assertFalse(message.hasExtension("request","urn:xmpp:receipts"));
    }
    @Test void joinedRoomPrivacyIdentityExpiryAndSessionBoundaries() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"));
        BlockingQueue<Boolean> aliceTyping=new LinkedBlockingQueue<>(),bobTyping=new LinkedBlockingQueue<>();
        BlockingQueue<XmppRoomMessage> aliceBodies=new LinkedBlockingQueue<>(),bobBodies=new LinkedBlockingQueue<>();
        BlockingQueue<Message> sent=new LinkedBlockingQueue<>();
        try(XmppSession alice=session("alice",3401,aliceTyping,aliceBodies); XmppSession bob=session("bob",3402,bobTyping,bobBodies)) {
            alice.connect(); bob.connect();
            String room=alice.rooms.createPrivate("Alice","Room typing test").room.toString();
            alice.rooms.invite(room,"roombob@localhost",null);
            bob.rooms.join(room,"Bob",null);
            EntityFullJid aliceOccupant=JidCreate.entityFullFrom(room+"/Alice");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!alice.rooms.acceptsChatState(JidCreate.entityFullFrom(room+"/Bob")) && System.nanoTime()<deadline) Thread.sleep(20);
            alice.connection().addStanzaInterceptor(stanza -> sent.add((Message)stanza),stanza -> stanza instanceof Message
                    && room.equals(stanza.getTo().toString()) && stanza.hasExtension(XmppChatStates.NAMESPACE));
            alice.chatStates.input(room,true); assertState(sent,ChatState.composing);
            assertEquals(true,bobTyping.poll(5,TimeUnit.SECONDS));
            alice.chatStates.input(room,true); alice.chatStates.input(room,true);
            assertNull(sent.poll(200,TimeUnit.MILLISECONDS),"Keystrokes cannot repeat standalone composing");
            alice.chatStates.gone(room); assertState(sent,ChatState.inactive);
            assertEquals(false,bobTyping.poll(5,TimeUnit.SECONDS));
            alice.chatStates.setEnabled(false); alice.chatStates.input(room,true);
            assertNull(sent.poll(200,TimeUnit.MILLISECONDS),"Privacy switch must suppress room states");
            Message content=new Message(JidCreate.entityBareFrom(room),Message.Type.groupchat); content.setBody("privacy");
            alice.chatStates.decorate(content); assertFalse(content.hasExtension(XmppChatStates.NAMESPACE));

            for(int invalid=0;invalid<4;invalid++) {
                Message replay=notification(bob,aliceOccupant,ChatState.composing);
                if(invalid==0) replay.addExtension(StandardExtensionElement.builder("delay","urn:xmpp:delay").build());
                if(invalid==1) replay.addExtension(StandardExtensionElement.builder("forwarded","urn:xmpp:forward:0").build());
                if(invalid==2) replay.setTo(JidCreate.entityFullFrom("roombob@localhost/other-session"));
                if(invalid==3) replay.setFrom(JidCreate.entityFullFrom(room+"/unknown"));
                bob.chatStates.receive(replay);
            }
            assertTrue(bobTyping.isEmpty(),"Stale, unknown and foreign-session occupants cannot create typing");
            Message composing=notification(bob,aliceOccupant,ChatState.composing);
            if(composing.hasExtension("occupant-id","urn:xmpp:occupant-id:0")) {
                Message impostor=new Message(bob.connection().getUser(),Message.Type.groupchat); impostor.setFrom(aliceOccupant);
                impostor.addExtension(new ChatStateExtension(ChatState.composing));
                impostor.addExtension(StandardExtensionElement.builder("occupant-id","urn:xmpp:occupant-id:0").addAttribute("id","former-occupant").build());
                bob.chatStates.receive(impostor); assertTrue(bobTyping.isEmpty());
            }
            bob.chatStates.receive(composing); assertEquals(true,bobTyping.poll(5,TimeUnit.SECONDS));
            bob.chatStates.receive(notification(bob,aliceOccupant,ChatState.gone)); assertTrue(bobTyping.isEmpty(),"Room gone must be ignored");
            bob.chatStates.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())+XmppChatStates.TYPING_TIMEOUT_MILLIS+10);
            assertEquals(false,bobTyping.poll(5,TimeUnit.SECONDS));
            bob.chatStates.receive(composing); assertEquals(true,bobTyping.poll(5,TimeUnit.SECONDS));
            bob.rooms.leave(room); assertEquals(false,bobTyping.poll(5,TimeUnit.SECONDS),"Leaving clears all occupants immediately");
            bob.chatStates.receive(composing); assertTrue(bobTyping.isEmpty(),"Old room session cannot recreate typing");
            bob.rooms.join(room,"Bob",null); bob.chatStates.receive(composing); assertEquals(true,bobTyping.poll(5,TimeUnit.SECONDS));
            alice.rooms.leave(room); assertEquals(false,bobTyping.poll(5,TimeUnit.SECONDS),"Departing nickname must clear typing");
            assertTrue(aliceBodies.isEmpty()); assertTrue(bobBodies.isEmpty(),"Chat states must not create history bubbles");
        }
    }
}
