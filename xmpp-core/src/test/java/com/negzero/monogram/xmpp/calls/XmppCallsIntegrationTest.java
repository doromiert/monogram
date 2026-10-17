package com.negzero.monogram.xmpp.calls;

import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smack.packet.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.tcp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Smack/Prosody dispatch and correlation, with synthetic SDP only; this does not exercise media. */
@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppCallsIntegrationTest {
    static final class Client implements AutoCloseable,XmppCalls.Listener {
        final XMPPTCPConnection connection;final XmppCalls calls;
        final BlockingQueue<XmppCalls.Call> incoming=new LinkedBlockingQueue<>();
        final BlockingQueue<String> remote=new LinkedBlockingQueue<>(),candidates=new LinkedBlockingQueue<>(),states=new LinkedBlockingQueue<>(),muted=new LinkedBlockingQueue<>();
        final AtomicInteger results=new AtomicInteger(),descriptionsRequested=new AtomicInteger();
        final ArrayDeque<String> diagnostics=new ArrayDeque<>();
        synchronized void diagnostic(String value) { if(diagnostics.size()==32) diagnostics.removeFirst();diagnostics.addLast(value); }
        String diagnostic() { XmppCalls.Call call=calls.current();synchronized(this) { return "auth="+connection.isAuthenticated()+" call="+(call==null?"none":call.sid+"/"+call.fullPeer)+" events="+diagnostics; } }
        void packet(String direction,Stanza stanza) {
            if(stanza instanceof Message) {
                Message message=(Message)stanza;for(ExtensionElement extension:message.getExtensions()) if(JingleCodec.JMI.equals(extension.getNamespace())) diagnostic(direction+" "+message.getType()+" "+message.getFrom()+" -> "+message.getTo()+" "+extension.getElementName()+"/"+extension.getClass().getSimpleName()+" delayed="+message.hasExtension("urn:xmpp:delay"));
            } else if(stanza instanceof JingleCodec.Packet) diagnostic(direction+" IQ "+((IQ)stanza).getType()+" "+((JingleCodec.Packet)stanza).element.getAttributeValue("action"));
        }
        Client(String username,String resource) throws Exception {
            connection=new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder().setXmppDomain("localhost").setHost("127.0.0.1")
                    .setPort(Integer.parseInt(System.getenv("MONOGRAM_IT_PORT"))).setUsernameAndPassword(username,"monogram-test")
                    .setResource(resource).setSecurityMode(ConnectionConfiguration.SecurityMode.required).build());
            calls=new XmppCalls(connection);calls.setListener(this);
            connection.addAsyncStanzaListener(stanza -> packet("in",stanza),stanza -> stanza instanceof Message || stanza instanceof JingleCodec.Packet);
            connection.addStanzaSendingListener(stanza -> packet("out",stanza),stanza -> stanza instanceof Message || stanza instanceof JingleCodec.Packet);
            connection.setParsingExceptionCallback(broken -> diagnostic("parse-error "+broken.getParsingException().getClass().getSimpleName()));
            connection.addStanzaSendingListener(s -> results.incrementAndGet(),s -> s instanceof IQ && ((IQ)s).getType()==IQ.Type.result);
            connection.connect().login();assertTrue(connection.isSecureConnection());
            // This reply follows the initial available presence on the same TCP stream.
            org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(connection.getXMPPServiceDomain());
        }
        public void incoming(XmppCalls.Call call) { incoming.add(call); }
        public void localDescriptionRequested(XmppCalls.Call call,boolean offer) { descriptionsRequested.incrementAndGet();calls.localDescription(call.sid,offer,JingleCodecTest.sdp(call.video,offer)); }
        public void remoteDescription(XmppCalls.Call call,boolean offer,String sdp) { remote.add(sdp);calls.remoteDescriptionApplied(call.sid); }
        public void remoteCandidate(XmppCalls.Call call,String mid,int index,String candidate) { candidates.add(mid+":"+index); }
        public void remoteMediaState(XmppCalls.Call call,boolean audio,boolean video) { muted.add(audio+":"+video); }
        public void state(XmppCalls.Call call,XmppCalls.State state,String reason) { diagnostic("state "+state+":"+reason);states.add(state.name()+":"+reason); }
        void state(String prefix) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);String next;
            while(System.nanoTime()<deadline) { next=states.poll(200,TimeUnit.MILLISECONDS);if(next!=null && next.startsWith(prefix)) return;if(next!=null && next.startsWith("ENDED:")) fail("Signaling ended unexpectedly: "+next); }fail("Expected signaling state "+prefix);
        }
        @Override public void close() { calls.close();connection.disconnect(); }
    }
    static <T> T next(BlockingQueue<T> queue,Client... clients) throws Exception {
        T result=queue.poll(10,TimeUnit.SECONDS);assertNotNull(result,() -> "Expected disposable local call signaling event: "+Arrays.stream(clients).map(Client::diagnostic).collect(java.util.stream.Collectors.joining(" | ")));return result;
    }
    private void roundtrip(boolean video) throws Exception {
        try(Client alice=new Client("callsalice",video?"CallsVideoAlice":"CallsAudioAlice");Client bob=new Client("callsbob",video?"CallsVideoBob":"CallsAudioBob")) {
            XmppCalls.Call outgoing=alice.calls.start("callsbob@localhost",video);XmppCalls.Call incoming=next(bob.incoming,alice,bob);
            assertEquals(outgoing.sid,incoming.sid);assertEquals(video,incoming.video);assertEquals(alice.connection.getUser().toString(),incoming.fullPeer);assertFalse(incoming.outgoing);
            assertEquals(0,bob.descriptionsRequested.get(),"An incoming proposal must not acquire media before acceptance");bob.calls.accept(incoming.sid);
            String offer=next(bob.remote,alice,bob),answer=next(alice.remote,alice,bob);assertEquals(video,JingleSdp.parse(offer).video());assertEquals(video,JingleSdp.parse(answer).video());
            assertEquals(bob.connection.getUser().toString(),alice.calls.current().fullPeer);
            String mid=video?"1":"0";int index=video?1:0;
            alice.calls.candidate(outgoing.sid,mid,index,"candidate:1 1 udp 12345 192.0.2.1 1234 typ host generation 0 ufrag abcd");assertEquals(mid+":"+index,next(bob.candidates,alice,bob));
            bob.calls.candidate(incoming.sid,"0",0,"candidate:2 1 tcp 12346 192.0.2.2 2345 typ relay tcptype passive generation 0 ufrag abcd");assertEquals("0:0",next(alice.candidates,alice,bob));
            bob.calls.mediaMuted(incoming.sid,true,video);assertEquals("true:"+video,next(alice.muted,alice,bob));
            alice.calls.mediaConnected(outgoing.sid);bob.calls.mediaConnected(incoming.sid);alice.state("ACTIVE:");bob.state("ACTIVE:");
            alice.calls.end(outgoing.sid);alice.state("ENDED:success");bob.state("ENDED:remote-ended");assertNull(alice.calls.current());assertNull(bob.calls.current());
            XmppCallsTest.await(() -> alice.results.get()>=2 && bob.results.get()>=3);
            assertTrue(alice.results.get()>=2,"Answer and candidate IQs must receive correlated responses");assertTrue(bob.results.get()>=3,"Initiate, candidate and terminate IQs must be acknowledged");
        }
    }
    @Test void audioProposalOfferAnswerCandidatesMuteAndTerminateUseRealSmackTransport() throws Exception { roundtrip(false); }
    @Test void videoProposalOfferAnswerCandidatesMuteAndTerminateUseRealSmackTransport() throws Exception { roundtrip(true); }
}
