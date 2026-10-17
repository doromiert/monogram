package com.negzero.monogram.xmpp.calls;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.calls.JingleCodec.*;

class XmppCallsTest {
    static final class Connection extends XMPPTCPConnection {
        final BlockingQueue<Stanza> sent=new LinkedBlockingQueue<>();
        volatile boolean holdDescription,unsupportedInfo;volatile IQ held;
        Connection() throws Exception { super(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());connected=true;authenticated=true;user=JidCreate.entityFullFrom("alice@example.org/phone"); }
        @Override protected void sendStanzaInternal(Stanza stanza) {
            sent.add(stanza);
            if(stanza instanceof org.jivesoftware.smack.roster.packet.RosterPacket && ((IQ)stanza).getType()==IQ.Type.get) {
                org.jivesoftware.smack.roster.packet.RosterPacket answer=new org.jivesoftware.smack.roster.packet.RosterPacket();answer.setType(IQ.Type.result);answer.setStanzaId(stanza.getStanzaId());answer.setFrom(getXMPPServiceDomain());answer.setTo(user);invokeStanzaCollectorsAndNotifyRecvListeners(answer);
            } else if(stanza instanceof IQ && ((IQ)stanza).getType()==IQ.Type.set) {
                String action=stanza instanceof Packet?((Packet)stanza).element.getAttributeValue("action"):null;
                if(holdDescription && ("session-initiate".equals(action) || "session-accept".equals(action))) { held=(IQ)stanza;return; }
                IQ answer=unsupportedInfo && "session-info".equals(action)?IQ.createErrorResponse((IQ)stanza,StanzaError.getBuilder(StanzaError.Condition.feature_not_implemented)):IQ.createResultIQ((IQ)stanza);invokeStanzaCollectorsAndNotifyRecvListeners(answer);
            }
        }
        void feed(Stanza stanza) { invokeStanzaCollectorsAndNotifyRecvListeners(stanza); }
        void resumed() { callConnectionAuthenticatedListener(true); }
    }
    static final class Events implements XmppCalls.Listener {
        final BlockingQueue<XmppCalls.Call> incoming=new LinkedBlockingQueue<>(),requested=new LinkedBlockingQueue<>();
        final BlockingQueue<String> descriptions=new LinkedBlockingQueue<>(),candidates=new LinkedBlockingQueue<>(),ended=new LinkedBlockingQueue<>(),muted=new LinkedBlockingQueue<>();
        public void incoming(XmppCalls.Call call) { incoming.add(call); }
        public void localDescriptionRequested(XmppCalls.Call call,boolean offer) { requested.add(call); }
        public void remoteDescription(XmppCalls.Call call,boolean offer,String sdp) { descriptions.add(sdp); }
        public void remoteCandidate(XmppCalls.Call call,String mid,int index,String candidate) { candidates.add(mid+":"+index); }
        public void remoteMediaState(XmppCalls.Call call,boolean audio,boolean video) { muted.add(audio+":"+video); }
        public void state(XmppCalls.Call call,XmppCalls.State state,String reason) { if(state==XmppCalls.State.ENDED) ended.add(reason); }
    }
    static Message jmi(String action,String sid,String from,boolean video) throws Exception {
        StandardExtensionElement.Builder b=StandardExtensionElement.builder(action,JMI).addAttribute("id",sid);
        if(action.equals("propose")) { b.addElement(StandardExtensionElement.builder("description",RTP).addAttribute("media","audio").build());if(video) b.addElement(StandardExtensionElement.builder("description",RTP).addAttribute("media","video").build()); }
        Message m=new Message(JidCreate.from("alice@example.org/phone"),Message.Type.chat);m.setFrom(JidCreate.from(from));m.addExtension(b.build());return m;
    }
    static Packet iq(String action,String sid,StandardExtensionElement payload,String from) throws Exception {
        StandardExtensionElement.Builder b=StandardExtensionElement.builder("jingle",NS).addAttribute("action",action).addAttribute("sid",sid);if(payload!=null) for(StandardExtensionElement c:payload.getElements()) b.addElement(c);
        Packet p=new Packet(b.build());p.setType(IQ.Type.set);p.setFrom(JidCreate.from(from));p.setTo(JidCreate.from("alice@example.org/phone"));return p;
    }
    static void await(BooleanSupplier condition) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);assertTrue(condition.getAsBoolean()); }
    static <T> T next(BlockingQueue<T> q) throws Exception { T result=q.poll(3,TimeUnit.SECONDS);assertNotNull(result);return result; }
    @Test void outgoingVideoPinsResourceQueuesEarlyIceAndIgnoresStaleSidOrOtherResources() throws Exception {
        Connection c=new Connection();Events events=new Events();try(XmppCalls calls=new XmppCalls(c)) {
            calls.setListener(events);XmppCalls.Call call=calls.start("bob@example.org",true);
            c.feed(jmi("proceed",call.sid,"mallory@example.org/phone",true));assertNull(events.requested.poll(80,TimeUnit.MILLISECONDS));
            c.feed(jmi("proceed",call.sid,"bob@example.org/desktop",true));assertEquals("bob@example.org/desktop",next(events.requested).fullPeer);
            calls.localDescription(call.sid,true,JingleCodecTest.sdp(true,true));await(() -> c.sent.stream().anyMatch(s -> s instanceof Packet && "session-initiate".equals(((Packet)s).element.getAttributeValue("action"))));
            StandardExtensionElement candidate=StandardExtensionElement.builder("transport",ICE).addAttribute("ufrag","abcd").addElement(JingleCodec.candidate("candidate:1 1 udp 12345 192.0.2.1 1234 typ host ufrag abcd","abcd")).build();
            StandardExtensionElement payload=StandardExtensionElement.builder("jingle",NS).addElement(StandardExtensionElement.builder("content",NS).addAttribute("creator","initiator").addAttribute("name","1").addElement(candidate).build()).build();
            c.feed(iq("transport-info",call.sid,payload,"bob@example.org/desktop"));assertNull(events.candidates.poll(80,TimeUnit.MILLISECONDS));
            c.feed(iq("session-accept",call.sid,description(JingleSdp.parse(JingleCodecTest.sdp(true,false)),false),"bob@example.org/other"));assertNull(events.descriptions.poll(80,TimeUnit.MILLISECONDS));
            c.feed(iq("session-accept",call.sid,description(JingleSdp.parse(JingleCodecTest.sdp(true,false)),false),"bob@example.org/desktop"));assertTrue(next(events.descriptions).contains("m=video"));
            calls.remoteDescriptionApplied(call.sid);assertEquals("1:1",next(events.candidates));calls.mediaConnected(call.sid);
            StandardExtensionElement mute=StandardExtensionElement.builder("jingle",NS).addElement(StandardExtensionElement.builder("mute","urn:xmpp:jingle:apps:rtp:info:1").addAttribute("creator","initiator").addAttribute("name","1").build()).build();
            c.feed(iq("session-info",call.sid,mute,"bob@example.org/desktop"));assertEquals("false:true",next(events.muted));
            calls.mediaMuted(call.sid,true,true);await(() -> c.sent.stream().anyMatch(s -> s instanceof Packet && "session-info".equals(((Packet)s).element.getAttributeValue("action"))));calls.end(call.sid);assertEquals("success",next(events.ended));
            c.feed(jmi("proceed",call.sid,"bob@example.org/other",true));assertNull(events.requested.poll(80,TimeUnit.MILLISECONDS));assertNull(calls.current());
        }
    }
    @Test void incomingProposalWaitsForExplicitAcceptAndOtherLocalDeviceAnswerClosesIt() throws Exception {
        Connection c=new Connection();Events events=new Events();try(XmppCalls calls=new XmppCalls(c)) {
            calls.setListener(events);c.feed(jmi("propose","incoming","bob@example.org/desktop",true));XmppCalls.Call call=next(events.incoming);assertTrue(call.video);assertFalse(call.outgoing);assertNull(events.requested.poll(80,TimeUnit.MILLISECONDS));
            c.feed(jmi("accept",call.sid,"alice@example.org/tablet",false));assertEquals("answered-elsewhere",next(events.ended));
            c.feed(jmi("propose","incoming2","bob@example.org/desktop",false));call=next(events.incoming);calls.accept(call.sid);
            final String sid=call.sid;await(() -> c.sent.stream().anyMatch(s -> s instanceof Message && ((Message)s).hasExtension("proceed",JMI)));
            c.feed(iq("session-initiate",sid,description(JingleSdp.parse(JingleCodecTest.sdp(false,true)),true),"bob@example.org/desktop"));next(events.descriptions);next(events.requested);
            calls.localDescription(sid,false,JingleCodecTest.sdp(false,false));await(() -> c.sent.stream().anyMatch(s -> s instanceof Packet && "session-accept".equals(((Packet)s).element.getAttributeValue("action"))));calls.end(sid);next(events.ended);
        }
    }
    @Test void receivedCarbonRingsAndRetractsOnlyBareAddressedProposalsFromValidatedOwnWrapper() throws Exception {
        Connection c=new Connection();Events events=new Events();try(XmppCalls calls=new XmppCalls(c)) {
            calls.setListener(events);c.resumed();Message proposal=jmi("propose","carbon","bob@example.org/desktop",true);proposal.setTo(c.getUser().asBareJid());
            Message wrapper=new Message(c.getUser(),Message.Type.chat);wrapper.setFrom(JidCreate.entityBareFrom("mallory@example.org"));
            wrapper.addExtension(new org.jivesoftware.smackx.carbons.packet.CarbonExtension(org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.received,new org.jivesoftware.smackx.forward.packet.Forwarded<Message>(proposal)));c.feed(wrapper);assertNull(events.incoming.poll(80,TimeUnit.MILLISECONDS));
            wrapper.setFrom(c.getUser().asBareJid());c.feed(wrapper);assertEquals("carbon",next(events.incoming).sid);
            Message retract=jmi("retract","carbon","bob@example.org/desktop",false);retract.setTo(c.getUser().asBareJid());
            wrapper=new Message(c.getUser(),Message.Type.chat);wrapper.setFrom(c.getUser().asBareJid());wrapper.addExtension(new org.jivesoftware.smackx.carbons.packet.CarbonExtension(org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.received,new org.jivesoftware.smackx.forward.packet.Forwarded<Message>(retract)));c.feed(wrapper);assertEquals("cancel",next(events.ended));
            Message targeted=jmi("propose","targeted","bob@example.org/desktop",true);targeted.setTo(JidCreate.entityFullFrom("alice@example.org/tablet"));
            wrapper=new Message(c.getUser(),Message.Type.chat);wrapper.setFrom(c.getUser().asBareJid());wrapper.addExtension(new org.jivesoftware.smackx.carbons.packet.CarbonExtension(org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.received,new org.jivesoftware.smackx.forward.packet.Forwarded<Message>(targeted)));c.feed(wrapper);assertNull(events.incoming.poll(80,TimeUnit.MILLISECONDS));
        }
    }
    @Test void descriptionAckGatesTrickleAndPendingMuteWhileOptionalInfoErrorsDoNotHangUp() throws Exception {
        Connection c=new Connection();c.holdDescription=true;c.unsupportedInfo=true;Events events=new Events();try(XmppCalls calls=new XmppCalls(c)) {
            calls.setListener(events);XmppCalls.Call call=calls.start("bob@example.org",true);c.feed(jmi("proceed",call.sid,"bob@example.org/desktop",true));next(events.requested);
            calls.mediaMuted(call.sid,true,true);calls.candidate(call.sid,"0",0,"candidate:1 1 udp 12345 192.0.2.1 1234 typ host ufrag abcd");calls.localDescription(call.sid,true,JingleCodecTest.sdp(true,true));await(() -> c.held!=null);
            assertFalse(c.sent.stream().anyMatch(stanza -> stanza instanceof Packet && Arrays.asList("transport-info","session-info").contains(((Packet)stanza).element.getAttributeValue("action"))));
            c.feed(IQ.createResultIQ(c.held));await(() -> c.sent.stream().anyMatch(stanza -> stanza instanceof Packet && "transport-info".equals(((Packet)stanza).element.getAttributeValue("action"))));await(() -> c.sent.stream().anyMatch(stanza -> stanza instanceof Packet && "session-info".equals(((Packet)stanza).element.getAttributeValue("action"))));
            assertNull(events.ended.poll(80,TimeUnit.MILLISECONDS));assertEquals(call.sid,calls.current().sid);
            c.feed(iq("session-accept",call.sid,description(JingleSdp.parse(JingleCodecTest.sdp(true,false)),false),"bob@example.org/desktop"));next(events.descriptions);
            StandardExtensionElement all=StandardExtensionElement.builder("jingle",NS).addElement(StandardExtensionElement.builder("mute","urn:xmpp:jingle:apps:rtp:info:1").addAttribute("creator","responder").build()).build();c.feed(iq("session-info",call.sid,all,"bob@example.org/desktop"));assertEquals("true:true",next(events.muted));
            StandardExtensionElement active=StandardExtensionElement.builder("jingle",NS).addElement(StandardExtensionElement.builder("active","urn:xmpp:jingle:apps:rtp:info:1").build()).build();c.feed(iq("session-info",call.sid,active,"bob@example.org/desktop"));assertEquals("false:false",next(events.muted));
            Packet ping=iq("session-info",call.sid,null,"bob@example.org/desktop");c.feed(ping);await(() -> c.sent.stream().anyMatch(stanza -> stanza instanceof IQ && ((IQ)stanza).getType()==IQ.Type.result && ping.getStanzaId().equals(stanza.getStanzaId())));
            calls.end(call.sid);next(events.ended);
        }
    }
    @Test void parsedFreshOfflineProposalMayRingButExpiredHistoryCannot() throws Exception {
        Connection c=new Connection();Events events=new Events();try(XmppCalls calls=new XmppCalls(c)) {
            calls.setListener(events);
            Message expired=jmi("propose","expired","bob@example.org/desktop",true);expired.addExtension(new org.jivesoftware.smackx.delay.packet.DelayInformation(new Date(System.currentTimeMillis()-60000)));
            c.feed(org.jivesoftware.smack.util.PacketParserUtils.parseMessage(org.jivesoftware.smack.util.PacketParserUtils.getParserFor(expired.toXML().toString())));assertNull(events.incoming.poll(80,TimeUnit.MILLISECONDS));
            Message fresh=jmi("propose","fresh","bob@example.org/desktop",true);fresh.addExtension(new org.jivesoftware.smackx.delay.packet.DelayInformation(new Date(System.currentTimeMillis()-2000)));
            c.feed(org.jivesoftware.smack.util.PacketParserUtils.parseMessage(org.jivesoftware.smack.util.PacketParserUtils.getParserFor(fresh.toXML().toString())));assertEquals("fresh",next(events.incoming).sid);calls.reject("fresh");next(events.ended);
        }
    }
    @Test void explicitDiscoveryCoalescesUsesCacheAndInvalidatesOfflineResources() throws Exception {
        Connection c=new Connection();try(XmppCalls calls=new XmppCalls(c)) {
            org.jivesoftware.smack.roster.Roster roster=org.jivesoftware.smack.roster.Roster.getInstanceFor(c);
            Presence online=new Presence(Presence.Type.available);online.setFrom(JidCreate.entityFullFrom("bob@example.org/desktop"));c.feed(online);
            await(() -> roster.getAllPresences(online.getFrom().asBareJid()).stream().anyMatch(Presence::isAvailable));
            CompletableFuture<XmppCalls.Capabilities> first=calls.discover("bob@example.org");assertSame(first,calls.discover("bob@example.org"));
            await(() -> c.sent.stream().anyMatch(s -> s instanceof org.jivesoftware.smackx.disco.packet.DiscoverInfo));
            IQ request=(IQ)c.sent.stream().filter(s -> s instanceof org.jivesoftware.smackx.disco.packet.DiscoverInfo).findFirst().get();
            org.jivesoftware.smackx.disco.packet.DiscoverInfo response=new org.jivesoftware.smackx.disco.packet.DiscoverInfo();response.setType(IQ.Type.result);response.setStanzaId(request.getStanzaId());response.setFrom(request.getTo());response.setTo(c.getUser());
            for(String feature:Arrays.asList(NS,RTP,ICE,DTLS,JMI,"urn:xmpp:jingle:apps:rtp:audio","urn:xmpp:jingle:apps:rtp:video")) response.addFeature(feature);c.feed(response);
            assertTrue(first.get(3,TimeUnit.SECONDS).video);await(() -> calls.cachedCapabilities("bob@example.org").audio);
            assertTrue(calls.discover("bob@example.org").get(1,TimeUnit.SECONDS).audio);assertEquals(1,c.sent.stream().filter(s -> s instanceof org.jivesoftware.smackx.disco.packet.DiscoverInfo).count());
            Presence offline=new Presence(Presence.Type.unavailable);offline.setFrom(online.getFrom());c.feed(offline);await(() -> !calls.cachedCapabilities("bob@example.org").audio);assertTrue(calls.cachedCapabilities("bob@example.org").resources.isEmpty());
        }
    }
    @Test void busyGlareRetractTimeoutAndCloseAreTerminalAndCannotReviveMedia() throws Exception {
        Connection c=new Connection();Events events=new Events();XmppCalls calls=new XmppCalls(c);calls.setListener(events);
        try {
            XmppCalls.Call outgoing=calls.start("bob@example.org",false);
            c.feed(jmi("propose","glare","bob@example.org/desktop",false));await(() -> c.sent.stream().anyMatch(s -> s instanceof Message && s.hasExtension("reject",JMI)));assertEquals(outgoing.sid,calls.current().sid);assertTrue(events.incoming.isEmpty());
            c.feed(jmi("propose","busy","carol@example.org/phone",false));
            await(() -> c.sent.stream().anyMatch(stanza -> stanza instanceof Message && stanza.hasExtension("reject",JMI)
                    && "busy".equals(((StandardExtensionElement)stanza.getExtension("reject",JMI)).getAttributeValue("id"))
                    && "carol@example.org/phone".equals(String.valueOf(stanza.getTo()))));
            assertEquals(outgoing.sid,calls.current().sid);assertTrue(events.incoming.isEmpty());calls.end(outgoing.sid);next(events.ended);
            c.feed(jmi("propose","retract","bob@example.org/desktop",false));next(events.incoming);c.feed(jmi("retract","retract","bob@example.org/desktop",false));assertEquals("cancel",next(events.ended));
            XmppCalls.Call timed=calls.start("bob@example.org",false);await(() -> c.sent.stream().anyMatch(s -> s instanceof Message && s.hasExtension("propose",JMI) && timed.sid.equals(((StandardExtensionElement)s.getExtension("propose",JMI)).getAttributeValue("id"))));
            java.lang.reflect.Method timeout=XmppCalls.class.getDeclaredMethod("timeout",String.class,long.class,String.class);timeout.setAccessible(true);synchronized(calls) { timeout.invoke(calls,timed.sid,20L,"timeout"); }assertEquals("timeout",next(events.ended));
            XmppCalls.Call closed=calls.start("bob@example.org",true);calls.close();assertEquals("closed",next(events.ended));calls.mediaConnected(closed.sid);assertNull(calls.current());assertThrows(IllegalStateException.class,() -> calls.start("bob@example.org",false));
        }finally { calls.close(); }
    }
}
