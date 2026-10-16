package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.xdata.form.FillableForm;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppRoomsIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device,BlockingQueue<XmppRoomMessage> messages) throws Exception {
        return new XmppSession(new Account("room"+user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Room-"+device),
                "monogram-test",device,new File(root,"room-trust-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) { fail("Room content must not become a direct conversation"); }
            public void roomMessage(XmppRoomMessage message) { messages.add(message); }
        });
    }
    XmppRoomMessage readable(BlockingQueue<XmppRoomMessage> messages,String body) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {
            XmppRoomMessage message=messages.poll(200,TimeUnit.MILLISECONDS);
            if(message!=null && message.readable && body.equals(message.body)) return message;
        }
        fail("Room message did not arrive: "+body); return null;
    }
    @Test void joinNativeRoomTransportEncryptOfflineMembersAndRejoinHistory() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"));
        BlockingQueue<XmppRoomMessage> aliceMessages=new LinkedBlockingQueue<>(),bobMessages=new LinkedBlockingQueue<>();
        String room="friends@conference.localhost";
        try(XmppSession alice=session("alice",701,aliceMessages); XmppSession bob=session("bob",702,bobMessages)) {
            alice.connect(); bob.connect();
            assertTrue(alice.rooms.services().contains(JidCreate.domainBareFrom("conference.localhost")));
            MultiUserChat peer=MultiUserChatManager.getInstanceFor(bob.connection()).getMultiUserChat(JidCreate.entityBareFrom(room));
            peer.create(Resourcepart.from("Bob"));
            FillableForm form=peer.getConfigurationForm().getFillableForm();
            form.setAnswer("muc#roomconfig_persistentroom",true);
            form.setAnswer("muc#roomconfig_membersonly",true);
            form.setAnswer("muc#roomconfig_whois","anyone"); peer.sendConfigurationForm(form);
            peer.grantMembership(JidCreate.bareFrom("roomalice@localhost"));
            peer.leave();
            bob.rooms.join(room,"Bob",null); alice.rooms.join(room,"Alice",null);
            assertEquals(2,alice.rooms.state(room).occupants.size());
            assertThrows(org.jivesoftware.smack.XMPPException.XMPPErrorException.class,() -> alice.rooms.join("missing@conference.localhost","Alice",null));
            assertThrows(UnsupportedOperationException.class,() -> alice.send(room+"/Bob","must not strip occupant",false));
            assertThrows(UnsupportedOperationException.class,() -> alice.send(room,"must not become a direct chat",false));
            XmppRoomMessage sent=alice.rooms.send(room,"room hello",false);
            XmppRoomMessage incoming=readable(bobMessages,"room hello");
            assertEquals(room+"/Alice",incoming.sender); assertFalse(incoming.outgoing); assertFalse(incoming.encrypted);
            assertEquals(sent.ids.get("origin"),incoming.ids.get("origin"));
            assertThrows(org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException.class,() -> alice.rooms.send(room,"requires trust",true));
            for(String jid:new String[]{"roomalice@localhost","roombob@localhost"}) {
                for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:alice.devices(jid)) {
                    if(device.getJid().toString().equals(alice.ownJid()) && device.getDeviceId()==alice.ownDeviceId()) continue;
                    alice.decide(device,alice.fingerprint(device),true);
                }
                for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:bob.devices(jid)) {
                    if(device.getJid().toString().equals(bob.ownJid()) && device.getDeviceId()==bob.ownDeviceId()) continue;
                    bob.decide(device,bob.fingerprint(device),true);
                }
            }
            alice.rooms.send(room,"private room hello",true);
            assertTrue(readable(bobMessages,"private room hello").encrypted);
            bob.rooms.leave(room);
            alice.rooms.send(room,"encrypted while Bob is absent",true);
            bob.rooms.join(room,"Bob",null);
            assertTrue(readable(bobMessages,"encrypted while Bob is absent").encrypted,"Offline room members must receive ciphertext addressed to their device");
            java.util.List<String> presence=new java.util.concurrent.CopyOnWriteArrayList<>();
            alice.connection().addAsyncStanzaListener(stanza -> presence.add(stanza.toXML().toString()),stanza -> stanza instanceof org.jivesoftware.smack.packet.Presence);
            try { alice.rooms.nickname(room,"Alice2"); } catch(Exception failure) { throw new AssertionError(presence.toString(),failure); }
            alice.rooms.send(room,"after nickname",false);
            assertEquals(room+"/Alice2",readable(bobMessages,"after nickname").sender);
            alice.rooms.leave(room); assertFalse(alice.rooms.state(room).joined);
            assertThrows(IllegalStateException.class,() -> alice.rooms.send(room,"must not send",false));
        }
    }
}
