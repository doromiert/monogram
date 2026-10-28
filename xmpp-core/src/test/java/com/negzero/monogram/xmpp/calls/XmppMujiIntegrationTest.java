package com.negzero.monogram.xmpp.calls;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Three real local Prosody participants and synthetic SDP: signaling, not microphone/media proof. */
@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppMujiIntegrationTest {
    static final class Member implements AutoCloseable {
        final XmppCallsIntegrationTest.Client client;final XmppMuji muji;
        final AtomicInteger descriptions=new AtomicInteger(),candidates=new AtomicInteger();final AtomicBoolean block=new AtomicBoolean();
        volatile List<XmppMuji.Participant> participants=Collections.emptyList();volatile String error;
        Member(String user,String resource)throws Exception {
            client=new XmppCallsIntegrationTest.Client(user,resource);
            muji=new XmppMuji(client.connection,client.calls,peer->block.get());
            muji.setListener(new XmppMuji.Listener(){
                public void conference(String room,List<XmppMuji.Participant> list,String failed){participants=list;error=failed;}
                public void localDescriptionRequested(XmppCalls.Call call,boolean offer){XmppCalls engine=muji.leg(call.fullPeer);if(engine!=null)engine.localDescription(call.sid,offer,JingleCodecTest.sdp(call.video,offer).replace("a=group:BUNDLE 0 1","a=group:BUNDLE voice webcam").replace("a=group:BUNDLE 0","a=group:BUNDLE voice").replace("a=mid:0","a=mid:voice").replace("a=mid:1","a=mid:webcam"));}
                public void remoteCandidate(XmppCalls.Call call,String mid,int line,String value){assertEquals("voice",mid);candidates.incrementAndGet();}
                public void remoteDescription(XmppCalls.Call call,boolean offer,String sdp){descriptions.incrementAndGet();XmppCalls engine=muji.leg(call.fullPeer);if(engine!=null)engine.remoteDescriptionApplied(call.sid);}
            });
        }
        public void close(){muji.close();client.close();}
    }
    private static void eventually(java.util.function.BooleanSupplier test)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){if(test.getAsBoolean())return;Thread.sleep(20);}assertTrue(test.getAsBoolean(),"MUJI condition did not complete");
    }
    @Test void threeMembersMeshWithoutJmiAndLeavingOneDoesNotEndTheOtherPair()throws Exception {roundtrip(false);}
    @Test void threeVideoMembersNegotiateBothContentsIndependently()throws Exception {roundtrip(true);}
    private void roundtrip(boolean video)throws Exception {
        try(Member alice=new Member("callsalice","Muji-A" );Member bob=new Member("callsbob","Muji-B");Member carol=new Member("typingalice","Muji-C")) {
            org.jxmpp.jid.EntityBareJid room=JidCreate.entityBareFrom("muji-"+UUID.randomUUID()+"@conference.localhost");
            MultiUserChat muc=MultiUserChatManager.getInstanceFor(alice.client.connection).getMultiUserChat(room);muc.create(Resourcepart.from("Alice"));
            org.jivesoftware.smackx.xdata.form.FillableForm form=muc.getConfigurationForm().getFillableForm();form.setAnswer("muc#roomconfig_membersonly",true);form.setAnswer("muc#roomconfig_whois","anyone");muc.sendConfigurationForm(form);
            muc.grantMembership(bob.client.connection.getUser().asEntityBareJid());muc.grantMembership(carol.client.connection.getUser().asEntityBareJid());
            MultiUserChatManager.getInstanceFor(bob.client.connection).getMultiUserChat(room).join(Resourcepart.from("Bob"));
            MultiUserChatManager.getInstanceFor(carol.client.connection).getMultiUserChat(room).join(Resourcepart.from("Carol"));
            alice.muji.join(room.toString(),video).get(5,TimeUnit.SECONDS);bob.muji.join(room.toString(),video).get(5,TimeUnit.SECONDS);carol.muji.join(room.toString(),video).get(5,TimeUnit.SECONDS);
            eventually(()->alice.descriptions.get()>=2 && bob.descriptions.get()>=2 && carol.descriptions.get()>=2);
            assertNull(alice.error);assertNull(bob.error);assertNull(carol.error);assertNull(alice.client.calls.current());assertTrue(alice.client.incoming.isEmpty());
            assertEquals(2,alice.participants.size());assertNotNull(bob.muji.leg(carol.client.connection.getUser().toString()));
            XmppCalls pair=alice.muji.leg(bob.client.connection.getUser().toString());
            pair.candidate(pair.current().sid,"voice",0,"candidate:1 1 udp 12345 192.0.2.1 1234 typ host generation 0 ufrag abcd");
            eventually(()->bob.candidates.get()==1);assertEquals(0,carol.candidates.get());
            assertThrows(IllegalStateException.class,()->alice.client.calls.start(bob.client.connection.getUser().asBareJid().toString(),false));
            try(XmppCallsIntegrationTest.Client outsider=new XmppCallsIntegrationTest.Client("receiptalice","Muji-outsider")) {
                org.jivesoftware.smack.packet.StandardExtensionElement payload=org.jivesoftware.smack.packet.StandardExtensionElement.builder("jingle",JingleCodec.NS).addAttribute("action","session-initiate").addAttribute("sid","outsider")
                    .addElement(org.jivesoftware.smack.packet.StandardExtensionElement.builder("muji",XmppMuji.NS).addAttribute("room",room.toString()).build()).build();
                JingleCodec.Packet request=new JingleCodec.Packet(payload);request.setType(IQ.Type.set);request.setTo(alice.client.connection.getUser());
                org.jivesoftware.smack.XMPPException.XMPPErrorException denied=assertThrows(org.jivesoftware.smack.XMPPException.XMPPErrorException.class,()->outsider.connection.createStanzaCollectorAndSend(request).nextResultOrThrow());
                assertEquals(StanzaError.Condition.not_authorized,denied.getStanzaError().getCondition());assertEquals(2,alice.participants.size());
            }
            alice.muji.leave();eventually(()->alice.muji.room()==null && bob.muji.leg(alice.client.connection.getUser().toString())==null);
            assertNotNull(bob.muji.leg(carol.client.connection.getUser().toString()));
            bob.block.set(true);bob.muji.refreshPermissions();eventually(()->bob.muji.leg(carol.client.connection.getUser().toString())==null);
        }
    }
}
