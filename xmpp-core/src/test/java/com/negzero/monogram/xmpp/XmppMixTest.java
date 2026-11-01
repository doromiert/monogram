package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smack.roster.packet.RosterPacket;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppMixTest {
    final String jid="general@mix.example.org";
    static class Listener implements XmppSession.Listener {
        final BlockingQueue<XmppRoomMessage> messages=new LinkedBlockingQueue<>();
        final List<XmppRooms.State> states=new CopyOnWriteArrayList<>();
        public void message(EntityBareJid sender,String id,String body,boolean encrypted){fail("MIX must remain a group conversation");}
        public void roomMessage(XmppRoomMessage message){messages.add(message);}
        public void roomChanged(XmppRooms.State state){states.add(state);}
    }
    @Test void serverAndTransportAddressesAreNotRooms() throws Exception {
        try(MixTestConnection connection=new MixTestConnection();XmppRooms rooms=new XmppRooms(connection,null,new Listener(),contact->Collections.emptySet())) {
            rooms.mix.remember(jid,"self","Alice");rooms.remember("room@conference.example.org");
            assertFalse(rooms.mix.known(null));assertFalse(rooms.known(null));
            for(String address:Arrays.asList("example.org","transport.example.org/resource")) {
                org.jxmpp.jid.Jid service=JidCreate.from(address);
                assertFalse(rooms.mix.known(service));assertFalse(rooms.known(service));
                Message message=new Message();message.setFrom(service);message.setBody("Server notice");
                assertNull(rooms.legacyContext(message,true,null,null));
            }
            assertFalse(rooms.known(JidCreate.from("bob@example.org")));
            assertTrue(rooms.mix.known(JidCreate.from(jid+"/participant")));
            assertTrue(rooms.known(JidCreate.from(jid)));
            assertTrue(rooms.known(JidCreate.from("room@conference.example.org/nickname")));
        }
    }
    @Test void joinsViaPamToOwnBareAccountAndKeepsOfflineParticipants()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,new Listener())){
            XmppMix.Channel channel=mix.join(jid,"Alice");assertTrue(channel.joined);assertEquals("self",channel.participantId);assertEquals("General",channel.title);assertEquals(2,channel.participants.size());
            IQ join=(IQ)connection.sent.stream().filter(s->s instanceof IQ && XmppMix.PAM.equals(((IQ)s).getChildElementNamespace())).findFirst().get();
            assertEquals(connection.getUser().asBareJid(),join.getTo());String xml=XmppXml.string(join);assertTrue(xml.contains(XmppMix.MESSAGES));assertTrue(xml.contains(XmppMix.PARTICIPANTS));assertTrue(xml.contains(XmppMix.INFO));assertFalse(xml.contains("muc"));
            assertEquals(new HashSet<>(Arrays.asList(JidCreate.bareFrom("alice@example.org"),JidCreate.bareFrom("bob@example.org"))),mix.members(jid));
            mix.close();assertTrue(connection.sent.stream().noneMatch(s->XmppXml.string(s).contains("client-leave")),"Closing a client must not leave persistent membership");
        }
    }
    @Test void refusesAccountWithoutPamBeforePublishingOrJoiningAnything()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,new Listener())){
            connection.pam=false;assertThrows(IllegalStateException.class,()->mix.join(jid,"Alice"));assertFalse(mix.known(JidCreate.from(jid)));
            assertTrue(connection.sent.stream().noneMatch(s->s instanceof IQ && ((IQ)s).getType()==IQ.Type.set));
        }
    }
    @Test void rosterAnnotationsPreserveSmackContactsAndTrackOtherClientLeaves()throws Exception{
        Listener listener=new Listener();try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,listener)){
            connection.rosterItems+="<item jid='bob@example.org' name='Bob' subscription='both'><group>Friends</group></item>";
            mix.refreshRoster();assertTrue(mix.state(jid).joined);assertEquals("self",mix.state(jid).participantId);
            RosterPacket roster=PacketParserUtils.parseStanza("<iq type='result'><query xmlns='jabber:iq:roster'>"+connection.rosterItems+"</query></iq>");
            assertEquals(2,roster.getRosterItemCount());assertEquals(Collections.singleton("Friends"),new HashSet<>(roster.getRosterItems().get(1).getGroupNames()));assertEquals("self",MixRoster.channels(roster).get(jid));
            connection.rosterItems="";mix.refreshRoster();assertFalse(mix.state(jid).joined);assertFalse(listener.states.get(listener.states.size()-1).joined);
        }
    }
    @Test void messagesUseStableParticipantAndRealJidWithAuthoritativeArchiveId()throws Exception{
        Listener listener=new Listener();try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,listener)){
            mix.join(jid,"Alice");connection.inject(message("bob","bob@example.org","server-1","hello"));
            XmppRoomMessage incoming=listener.messages.poll(2,TimeUnit.SECONDS);assertNotNull(incoming);assertEquals("hello",incoming.body);assertEquals(jid+"/bob",incoming.sender);assertEquals("bob@example.org",incoming.realSender);assertTrue(incoming.realSenderVerified);assertFalse(incoming.outgoing);assertEquals("bob",incoming.occupantId);assertEquals("server-1",incoming.ids.get("archive"));
            Message archive=PacketParserUtils.parseStanza(message(null,"bob@example.org","server-1","hello"));
            XmppRoomMessage historical=mix.project(archive,"hello",false,true,"server-1",new Date(1000));assertNotNull(historical);assertEquals(incoming.ids.get("archive"),historical.ids.get("archive"));assertTrue(historical.archived);
            assertNull(historical.occupantId,"A bare historical sender does not prove today's stable participant ID");
        }
    }
    @Test void malformedAndForeignRoutesCannotInventVerifiedParticipants()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,new Listener())){
            mix.join(jid,"Alice");assertThrows(IllegalArgumentException.class,()->mix.project(PacketParserUtils.parseStanza(message("bob","bob@example.org/phone","x","body")),"body",false,true,null,null));
            assertNull(mix.project(PacketParserUtils.parseStanza(message("bob","bob@example.org","x","body").replace(jid,"other@mix.example.org")),"body",false,true,null,null));
        }
        for(String id:Arrays.asList("","bad/id","bad#id","bad@id","bad id"))assertThrows(IllegalArgumentException.class,()->XmppMix.participantId(id));
        assertThrows(Exception.class,()->XmppMix.channel("a@b/c"));
    }
    @Test void encryptedMembershipFailsClosedForHiddenAddresses()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,new Listener())){
            mix.join(jid,"Alice");connection.participantItems+="<item id='hidden'><participant xmlns='urn:xmpp:mix:core:1'><nick>Hidden</nick></participant></item>";
            assertThrows(OmemoSendException.class,()->mix.members(jid));
        }
    }
    @Test void leaveIsPamCommandAndOnlyChangesLocalMembershipAfterSuccess()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,new Listener())){
            mix.join(jid,"Alice");mix.leave(jid);assertFalse(mix.state(jid).joined);
            IQ leave=(IQ)connection.sent.stream().filter(s->XmppXml.string(s).contains("client-leave")).findFirst().get();assertEquals(connection.getUser().asBareJid(),leave.getTo());assertTrue(XmppXml.string(leave).contains("channel='"+jid+"'"));
        }
    }
    @Test void acceptsBothCoreIdAndOlderPamEncodedJidButNotAnotherChannel()throws Exception{
        StandardExtensionElement join=StandardExtensionElement.builder("join",XmppMix.CORE).addAttribute("jid","self#"+jid).build();assertEquals("self",XmppMix.joinedId(join,JidCreate.entityBareFrom(jid)));
        assertThrows(IllegalArgumentException.class,()->XmppMix.joinedId(join,JidCreate.entityBareFrom("other@mix.example.org")));
    }
    @Test void followsParticipantPaginationAndNeverAcceptsAnIncompleteRecipientPage()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,new Listener())){
            mix.join(jid,"Alice");java.util.concurrent.atomic.AtomicInteger page=new java.util.concurrent.atomic.AtomicInteger();
            connection.response=request->{String xml=XmppXml.string(request);if(!xml.contains(XmppMix.PARTICIPANTS))return connection.standard(request);
                boolean second=page.incrementAndGet()==2;if(second)assertTrue(xml.contains("<after>self</after>"));
                String items=second?"<item id='bob'><participant xmlns='urn:xmpp:mix:core:1'><jid>bob@example.org</jid></participant></item>":"<item id='self'><participant xmlns='urn:xmpp:mix:core:1'><jid>alice@example.org</jid></participant></item>";
                return "<pubsub xmlns='http://jabber.org/protocol/pubsub'><items node='"+XmppMix.PARTICIPANTS+"'>"+items+"</items><set xmlns='http://jabber.org/protocol/rsm'><first index='"+(second?1:0)+"'>"+(second?"bob":"self")+"</first><last>"+(second?"bob":"self")+"</last><count>2</count></set></pubsub>";};
            assertEquals(2,mix.members(jid).size());assertEquals(2,page.get());
            connection.response=request->XmppXml.string(request).contains(XmppMix.PARTICIPANTS)?"<pubsub xmlns='http://jabber.org/protocol/pubsub'><items node='"+XmppMix.PARTICIPANTS+"'/><set xmlns='http://jabber.org/protocol/rsm'><count>2</count></set></pubsub>":connection.standard(request);
            assertThrows(java.io.IOException.class,()->mix.members(jid));
        }
    }
    @Test void failedLeaveDoesNotDiscardServerMembershipOrLocalIdentity()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppMix mix=new XmppMix(connection,new Listener())){
            mix.join(jid,"Alice");connection.response=request->XmppXml.string(request).contains("client-leave")?null:connection.standard(request);
            assertThrows(org.jivesoftware.smack.SmackException.NoResponseException.class,()->mix.leave(jid));assertTrue(mix.state(jid).joined);assertEquals("self",mix.state(jid).participantId);
        }
    }
    @Test void sharedRoomSendUsesMixTransportStableAuthorAndOriginAlias()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppRooms rooms=new XmppRooms(connection,null,new Listener(),contact->Collections.emptySet())){
            rooms.mix.join(jid,"Alice");XmppRoomMessage sent=rooms.send(jid,"Hello MIX",false);
            assertTrue(sent.outgoing);assertEquals(jid+"/self",sent.sender);assertEquals("self",sent.occupantId);assertEquals("alice@example.org\n"+sent.ids.get("origin"),sent.ids.get("mix-origin"));
            Message wire=(Message)connection.sent.stream().filter(s->s instanceof Message && "Hello MIX".equals(((Message)s).getBody())).findFirst().get();
            assertEquals(Message.Type.groupchat,wire.getType());assertEquals(JidCreate.entityBareFrom(jid),wire.getTo());assertTrue(rooms.state(jid).mix);
            assertFalse(rooms.sendDisplayed(jid,"server-id"));
        }
    }
    private String message(String id,String real,String server,String body){return "<message xmlns='jabber:client' type='groupchat' from='"+jid+(id==null?"":"/"+id)+"' to='alice@example.org' id='"+server+"'><body>"+body+"</body><mix xmlns='urn:xmpp:mix:core:1'><nick>Bob</nick><jid>"+real+"</jid></mix></message>";}
}
