package com.negzero.monogram.xmpp.calls;

import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.calls.JingleCodec.*;

class JingleCodecTest {
    static final String FP="12:34:56:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF";
    static String sdp(boolean video,boolean offer) {
        String common="a=ice-ufrag:abcd\r\na=ice-pwd:abcdefghijklmnopqrstuvwxyz\r\na=fingerprint:sha-256 "+FP+"\r\na=setup:"+(offer?"actpass":"active")+"\r\na=sendrecv\r\na=rtcp-mux\r\n";
        return "v=0\r\no=- 1 2 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0"+(video?" 1":"")+"\r\n"
                +"m=audio 9 UDP/TLS/RTP/SAVPF 111 0\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\n"+common
                +"a=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\na=rtpmap:0 PCMU/8000\r\na=rtcp-fb:111 transport-cc\r\na=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\na=ssrc:42 cname:stream\r\na=ssrc:42 msid:stream audio\r\n"
                +(video?"m=video 9 UDP/TLS/RTP/SAVPF 96 97\r\nc=IN IP4 0.0.0.0\r\na=mid:1\r\n"+common+"a=rtpmap:96 VP8/90000\r\na=rtpmap:97 rtx/90000\r\na=fmtp:97 apt=96\r\na=rtcp-fb:96 nack pli\r\na=ssrc-group:FID 43 44\r\na=ssrc:43 cname:stream\r\na=ssrc:43 msid:stream video\r\na=ssrc:44 cname:stream\r\na=ssrc:44 msid:stream video\r\n":"");
    }
    @Test void audioAndVideoRoundtripPreservesPayloadsFeedbackSourcesGroupingAndDirections() throws Exception {
        initialize();for(boolean video:new boolean[]{false,true}) for(boolean offer:new boolean[]{false,true}) {
            CallDescription before=JingleSdp.parse(sdp(video,offer));Packet wire=new Packet(description(before,offer));wire.setType(IQ.Type.set);
            Packet parsed=(Packet)PacketParserUtils.parseStanza(wire.toXML().toString());CallDescription after=JingleSdp.fromJingle(parsed.element,offer);
            assertEquals(before.bundle,after.bundle);assertEquals(before.video(),after.video());
            for(int i=0;i<before.contents.size();i++) {
                Content a=before.contents.get(i),b=after.contents.get(i);assertEquals(a.name,b.name);assertEquals(a.direction,b.direction);assertEquals(a.fingerprint,b.fingerprint);
                for(String attribute:a.attributes) if(attribute.startsWith("rtpmap:") || attribute.startsWith("fmtp:") || attribute.startsWith("rtcp-fb:") || attribute.startsWith("extmap:") || attribute.startsWith("ssrc:") || attribute.startsWith("ssrc-group:")) assertTrue(b.attributes.contains(attribute),"Lost negotiated media attribute");
            }
            assertEquals(after.contents.size(),JingleSdp.parse(JingleSdp.serialize(after)).contents.size());
        }
        String scoped=sdp(false,true).replace("a=group:BUNDLE 0\r\n","a=group:BUNDLE 0\r\na=inactive\r\n").replace("a=sendrecv","a=recvonly");
        assertEquals("recvonly",JingleSdp.parse(scoped).contents.get(0).direction,"Media direction overrides the session default");
        assertThrows(IllegalArgumentException.class,() -> JingleSdp.parse(sdp(false,true).replace("a=sendrecv","a=sendonly\r\na=recvonly")));
        CallDescription onlySend=JingleSdp.parse(sdp(true,false).replace("a=sendrecv","a=sendonly").replace("extmap:1 ","extmap:1/sendonly "));
        CallDescription mapped=JingleSdp.fromJingle(description(onlySend,false),false);assertEquals("sendonly",mapped.contents.get(0).direction);assertTrue(mapped.contents.get(0).attributes.stream().anyMatch(x -> x.startsWith("extmap:1/sendonly ")));
    }
    @Test void candidatesPreserveTurnTcpAndRejectWrongGenerationOrInjectedLines() {
        String value="candidate:9 1 tcp 12345 192.0.2.1 1234 typ relay raddr 192.0.2.2 rport 9999 tcptype passive generation 0 ufrag abcd";
        assertEquals(value,candidate(candidate(value,"abcd"),"abcd"));
        assertThrows(IllegalArgumentException.class,() -> candidate(value,"other"));
        assertThrows(IllegalArgumentException.class,() -> candidate(value+"\r\na=evil","abcd"));
        assertThrows(IllegalArgumentException.class,() -> candidate(value.replace("1234 typ","70000 typ"),"abcd"));
    }
    @Test void invalidOrOversizedDescriptionsFailClosed() {
        for(String invalid:Arrays.asList(sdp(false,true).replace(FP,"00:11"),sdp(false,true).replace("sha-256","sha-1"),sdp(false,true).replace("a=rtcp-mux\r\n",""),sdp(false,true).replace("BUNDLE 0","BUNDLE unknown"),sdp(false,true).replace("a=mid:0","a=mid:0\r\na=mid:0"),new String(new char[MAX_XML+1]).replace('\0','x'))) assertThrows(IllegalArgumentException.class,() -> JingleSdp.parse(invalid));
    }
    @Test void wireParserBoundsNestedAndOversizedUnknownElements() {
        initialize();StringBuilder deep=new StringBuilder("<iq type='set'><jingle xmlns='"+NS+"'>");for(int i=0;i<10;i++) deep.append("<nested>");for(int i=0;i<10;i++) deep.append("</nested>");deep.append("</jingle></iq>");
        assertThrows(Exception.class,() -> PacketParserUtils.parseStanza(deep.toString()));
        String large="<iq type='set'><jingle xmlns='"+NS+"' sid='"+new String(new char[MAX_XML+1]).replace('\0','x')+"'/></iq>";
        assertThrows(Exception.class,() -> PacketParserUtils.parseStanza(large));
    }
    @Test void externalServicesRequireSupportedSecureTransportAndRealTurnCredentials() {
        StandardExtensionElement.Builder b=StandardExtensionElement.builder("service",XmppIceServers.NS).addAttribute("type","turns").addAttribute("host","turn.example.org").addAttribute("port","5349").addAttribute("transport","tcp");
        assertNull(XmppIceServers.parse(b.build()));b.addAttribute("username","temporary").addAttribute("password","secret");assertEquals("turns:turn.example.org:5349?transport=tcp",XmppIceServers.parse(b.build()).urls.get(0));
        b.addAttribute("transport","udp");assertNull(XmppIceServers.parse(b.build()));b.addAttribute("transport","tcp").addAttribute("host","bad@host");assertNull(XmppIceServers.parse(b.build()));
    }
}
