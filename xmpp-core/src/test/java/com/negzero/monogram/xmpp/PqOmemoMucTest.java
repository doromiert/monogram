// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Disposable localhost server only; exercises the actual room/session delivery path. */
@EnabledIfEnvironmentVariable(named="MONOGRAM_PQ_IT",matches="1")
class PqOmemoMucTest {
    final File root=new File(System.getenv("MONOGRAM_PQ_IT_STATE"));
    final BlockingQueue<XmppRoomMessage> received=new LinkedBlockingQueue<>();
    final BlockingQueue<XmppMessage> direct=new LinkedBlockingQueue<>();
    final BlockingQueue<XmppRoomMessage> pending=new LinkedBlockingQueue<>();
    XmppSession session(String user,int id) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_PQ_IT_PORT")),"muc-"+id),
            "monogram-pq-test",id,new File(root,"trust-"+id),new XmppSession.Listener() {
                public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) {}
                public void message(XmppMessage message){direct.add(message);}
                public void messagePending(XmppMessage message){}
                public void roomMessage(XmppRoomMessage message){received.add(message);}
                public void roomMessagePending(XmppRoomMessage message){pending.add(message);}
            });
    }
    XmppRoomMessage await(String body) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            XmppRoomMessage message=received.poll(100,TimeUnit.MILLISECONDS);
            if(message!=null && message.readable && body.equals(message.body))return message;
        }
        fail("Protected room message did not arrive: "+body);return null;
    }
    void trust(XmppSession from,String jid) throws Exception {
        for(PqOmemoDevice.Fingerprint key:from.pq().discover(jid))from.pq().device.trust(jid,key.device,key.fingerprint,true);
    }
    @Test void sharedDmAndMucSessionsDeliverCorrectionsAndAuthenticatedRoomMetadata() throws Exception {
        XmppSession.initializeOmemo(new File(root,"muc-keys"),LegacyOmemoTestDatabase.shared());
        try(XmppSession alice=session("pqroomalice",18701);XmppSession bob=session("pqroombob",18702)) {
            alice.configurePq(new PqOmemoNativeTest.Database());bob.configurePq(new PqOmemoNativeTest.Database());
            alice.connect();bob.connect();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while((!alice.pqAvailable() || !bob.pqAvailable()) && System.nanoTime()<deadline)Thread.sleep(50);
            assertTrue(alice.pqAvailable());assertTrue(bob.pqAvailable());
            trust(alice,bob.ownJid());trust(bob,alice.ownJid());
            String room=alice.rooms.createPrivate("Alice","PQ room").room.toString();
            org.jivesoftware.smackx.muc.MultiUserChat roomAdmin=org.jivesoftware.smackx.muc.MultiUserChatManager.getInstanceFor(alice.connection()).getMultiUserChat(org.jxmpp.jid.impl.JidCreate.entityBareFrom(room));
            org.jivesoftware.smackx.xdata.form.FillableForm form=roomAdmin.getConfigurationForm().getFillableForm();form.setAnswer("muc#roomconfig_enablearchiving",true);roomAdmin.sendConfigurationForm(form);
            alice.rooms.invite(room,bob.ownJid(),"Local test");bob.rooms.join(room,"Bob",null);
            XmppRoomMessage outgoing=alice.rooms.send(room,"hello room",XmppMessage.Encryption.PQ_OMEMO,null,null,Collections.emptySet(),false,null,null);
            assertEquals(XmppMessage.Encryption.PQ_OMEMO,outgoing.encryption);
            XmppRoomMessage incoming=await("hello room");
            assertEquals(alice.ownJid(),incoming.realSender);assertTrue(incoming.realSenderVerified);
            assertEquals(room,incoming.room.toString());assertEquals(XmppMessage.Encryption.PQ_OMEMO,incoming.encryption);
            alice.rooms.send(room,"edited",XmppMessage.Encryption.PQ_OMEMO,null,new MessageCorrection(outgoing.ids.get("origin")),Collections.emptySet(),false,null,null);
            assertEquals(outgoing.ids.get("origin"),await("edited").correction.id);
            bob.rooms.send(room,"reply",XmppMessage.Encryption.PQ_OMEMO,null,null,Collections.emptySet(),false,null,null);
            assertEquals(bob.ownJid(),await("reply").realSender);
            File attachment=new File(root,"attachment.png");java.nio.file.Files.write(attachment.toPath(),new byte[]{1,2,3});
            FileLink link=FileLink.encrypted(new java.net.URL("https://files.example.org/local.bin"),new byte[12],new byte[32]);
            FileShare sharing=FileShare.describe(link,attachment,"picture.png","image/png");
            alice.rooms.sendFile(room,link,sharing,"private caption",XmppMessage.Encryption.PQ_OMEMO,null,Collections.emptySet(),false,null,false);
            XmppRoomMessage file=await(sharing.body("private caption"));
            assertNotNull(file.sharing);assertEquals("private caption",file.caption);assertTrue(file.sharing.link.encrypted());
            bob.rooms.sendReactions(room,new MessageReactions(outgoing.ids.get("origin"),Collections.singletonList("👍")),XmppMessage.Encryption.PQ_OMEMO,Collections.emptySet());
            XmppRoomMessage reaction=received.poll(15,TimeUnit.SECONDS);assertNotNull(reaction);assertNotNull(reaction.reactions);
            assertEquals(XmppMessage.Encryption.PQ_OMEMO,reaction.encryption);assertNull(reaction.body);
            alice.rooms.sendPqRetraction(room,new MessageRetraction(outgoing.ids.get("origin"),false),Collections.emptySet());
            XmppRoomMessage retracted=received.poll(15,TimeUnit.SECONDS);assertNotNull(retracted);assertNotNull(retracted.retraction);
            assertEquals(outgoing.ids.get("origin"),retracted.retraction.id);assertEquals(XmppMessage.Encryption.PQ_OMEMO,retracted.encryption);
            alice.send(bob.ownJid(),"direct after group",XmppMessage.Encryption.PQ_OMEMO,null,null,null);
            XmppMessage dm=direct.poll(15,TimeUnit.SECONDS);assertNotNull(dm);assertEquals("direct after group",dm.body);
            assertEquals(XmppMessage.Encryption.PQ_OMEMO,dm.encryption);
            assertTrue(direct.isEmpty(),"Room traffic must not create direct messages");
            for(PqOmemoDevice.Fingerprint key:bob.pq().discover(alice.ownJid()))bob.pq().device.trust(alice.ownJid(),key.device,key.fingerprint,false);
            // Blocking deliberately discards the old session. Start a fresh authenticated
            // handshake so this models an unapproved first contact, not erased history keys.
            for(PqOmemoDevice.Fingerprint key:alice.pq().discover(bob.ownJid())) {
                alice.pq().device.trust(bob.ownJid(),key.device,key.fingerprint,false);
                alice.pq().device.trust(bob.ownJid(),key.device,key.fingerprint,true);
            }
            pending.clear();
            alice.rooms.send(room,"archive after approval",XmppMessage.Encryption.PQ_OMEMO,null,null,Collections.emptySet(),false,null,null);
            assertNotNull(pending.poll(15,TimeUnit.SECONDS));
            trust(bob,alice.ownJid());
            assertTrue(bob.rooms.hasArchive(room));
            XmppRooms.ArchivePage page=bob.rooms.archive(room,null,null,50);
            XmppRoomMessage archived=page.messages.stream().filter(message -> "archive after approval".equals(message.body)).findFirst().orElse(null);
            assertNotNull(archived,"Archive messages: "+page.messages.size()+"; "+bob.pqFailure()+"; "+page.messages.stream().map(m -> m.realSender+":"+m.realSenderVerified+":"+m.readable).collect(java.util.stream.Collectors.joining(",")));assertTrue(archived.archived);assertEquals(alice.ownJid(),archived.realSender);
            assertTrue(archived.realSenderVerified);assertEquals(XmppMessage.Encryption.PQ_OMEMO,archived.encryption);
            page.stored();assertTrue(bob.pq().pending().isEmpty());

        }
    }
}
