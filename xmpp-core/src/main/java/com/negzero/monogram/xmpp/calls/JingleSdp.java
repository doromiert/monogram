// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp.calls;

import java.util.*;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import static com.negzero.monogram.xmpp.calls.JingleCodec.*;

/** RTP SDP mapping adapted from Monocles SessionDescription.java and RtpContentMap.java,
 * codeberg.org/monocles/monocles_chat at 129dbcc7cdc919c73a65e50ee121efecc36c5e7e.
 * Replaces Android/Guava XML dependencies with bounded immutable call-only models. */
public final class JingleSdp {
    private JingleSdp() {}
    public static CallDescription parse(String sdp) {
        if(sdp==null || sdp.length()>MAX_XML || sdp.indexOf('\0')>=0) throw invalid("SDP exceeds limits");
        List<String> global=new ArrayList<>();List<List<String>> sections=new ArrayList<>();List<String> current=global;
        for(String line:sdp.split("\r?\n")) {
            if(line.isEmpty()) continue;line(line);
            if(line.startsWith("m=")) { if(sections.size()>=2) throw invalid("Only audio/video supported");current=new ArrayList<>();sections.add(current); }
            if(current.size()>=1024) throw invalid("Too many SDP lines");current.add(line);
        }
        if(!global.contains("v=0")) throw invalid("Invalid SDP version");
        String group=value(global,"a=group:BUNDLE ");if(group==null) throw invalid("BUNDLE required");List<String> bundle=Arrays.asList(group.trim().split(" +"));
        List<Content> contents=new ArrayList<>();for(List<String> section:sections) {
            String[] m=section.get(0).substring(2).split(" +");if(m.length<4 || !"UDP/TLS/RTP/SAVPF".equals(m[2])) throw invalid("DTLS RTP required");
            if("0".equals(m[1])) throw invalid("Rejected media cannot be negotiated");number(m[1],1,65535);
            List<String> attrs=new ArrayList<>();for(String l:section) if(l.startsWith("a=")) attrs.add(l.substring(2));
            if(global.contains("a=extmap-allow-mixed") && !attrs.contains("extmap-allow-mixed")) attrs.add("extmap-allow-mixed");
            String mid=required(section,global,"a=mid:"),ufrag=required(section,global,"a=ice-ufrag:"),pwd=required(section,global,"a=ice-pwd:"),finger=required(section,global,"a=fingerprint:");
            if(!finger.startsWith("sha-256 ")) throw invalid("DTLS SHA-256 required");String direction="sendrecv";
            for(String d:Arrays.asList("sendrecv","sendonly","recvonly","inactive")) if(attrs.contains(d) || global.contains("a="+d)) direction=d;
            Set<String> offered=new LinkedHashSet<>();for(int i=3;i<m.length;i++) { number(m[i],0,127);if(!offered.add(m[i])) throw invalid("Duplicate SDP payload"); }
            Set<String> described=new HashSet<>();for(String a:attrs) if(a.startsWith("rtpmap:")) described.add(a.substring(7).split(" ",2)[0]);if(!offered.equals(described)) throw invalid("SDP codec list mismatch");
            Content c=new Content(mid,m[0],direction,ufrag,pwd,finger.substring(8),required(section,global,"a=setup:"),attrs);rtp(c);transport(c,true);contents.add(c);
        }return new CallDescription(contents,bundle);
    }
    public static String serialize(CallDescription description) {
        StringBuilder out=new StringBuilder("v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\na=group:BUNDLE ");out.append(String.join(" ",description.bundle)).append("\r\na=msid-semantic: WMS *\r\n");
        for(Content c:description.contents) {
            List<String> payloads=new ArrayList<>();for(String a:c.attributes) if(a.startsWith("rtpmap:")) payloads.add(a.substring(7).split(" ",2)[0]);if(payloads.isEmpty()) throw invalid("No RTP codecs");
            out.append("m=").append(c.media).append(" 9 UDP/TLS/RTP/SAVPF ").append(String.join(" ",payloads)).append("\r\nc=IN IP4 0.0.0.0\r\na=mid:").append(c.name)
                    .append("\r\na=ice-ufrag:").append(c.ufrag).append("\r\na=ice-pwd:").append(c.password).append("\r\na=ice-options:trickle\r\na=fingerprint:sha-256 ").append(c.fingerprint)
                    .append("\r\na=setup:").append(c.setup).append("\r\na=").append(c.direction).append("\r\na=rtcp:9 IN IP4 0.0.0.0\r\n");
            for(String a:c.attributes) if(a.equals("rtcp-mux") || a.equals("rtcp-rsize") || a.equals("extmap-allow-mixed") || a.startsWith("rtpmap:") || a.startsWith("fmtp:") || a.startsWith("rtcp-fb:") || a.startsWith("extmap:") || a.startsWith("ssrc:") || a.startsWith("ssrc-group:") || a.startsWith("msid:") || a.startsWith("candidate:")) out.append("a=").append(a).append("\r\n");
        }if(out.length()>MAX_XML) throw invalid("SDP exceeds limits");return out.toString();
    }
    public static CallDescription fromJingle(StandardExtensionElement root,boolean senderInitiator) {
        if(root.toXML().length()>MAX_XML) throw invalid("Jingle exceeds limits");List<Content> contents=new ArrayList<>();List<String> bundle=new ArrayList<>();
        for(StandardExtensionElement e:root.getElements()) {
            if(GROUP.equals(e.getNamespace()) && "group".equals(e.getElementName())) {
                if(!bundle.isEmpty() || !"BUNDLE".equals(attr(e,"semantics"))) throw invalid("Invalid grouping");
                for(StandardExtensionElement g:e.getElements()) { if(!"content".equals(g.getElementName()) || !GROUP.equals(g.getNamespace())) throw invalid("Invalid group member");bundle.add(attr(g,"name")); }
            } else if(NS.equals(e.getNamespace()) && "content".equals(e.getElementName())) {
                if(contents.size()>=2 || !"initiator".equals(attr(e,"creator"))) throw invalid("Invalid content creator");
                StandardExtensionElement d=one(e,"description",RTP),t=one(e,"transport",ICE),fp=one(t,"fingerprint",DTLS);
                if(!"sha-256".equals(attr(fp,"hash"))) throw invalid("DTLS SHA-256 required");
                String senders=e.getAttributeValue("senders");if(senders==null) senders="both";
                if(!Arrays.asList("both","none","initiator","responder").contains(senders)) throw invalid("Invalid senders");
                String direction="both".equals(senders)?"sendrecv":"none".equals(senders)?"inactive":(senders.equals(senderInitiator?"initiator":"responder")?"sendonly":"recvonly");
                List<String> attributes=new ArrayList<>();for(StandardExtensionElement child:d.getElements()) {
                    String name=child.getElementName(),ns=child.getNamespace();
                    if(RTP.equals(ns) && "payload-type".equals(name)) {
                        String id=attr(child,"id");number(id,0,127);String channels=child.getAttributeValue("channels");
                        attributes.add("rtpmap:"+id+" "+attr(child,"name")+"/"+attr(child,"clockrate")+(channels==null || channels.equals("1")?"":"/"+channels));List<String> parameters=new ArrayList<>();
                        for(StandardExtensionElement p:child.getElements()) {
                            if(RTP.equals(p.getNamespace()) && "parameter".equals(p.getElementName())) { String value=p.getAttributeValue("value");line(value==null?"":value);parameters.add(attr(p,"name")+(value==null || value.isEmpty()?"":"="+value)); }
                            else if(FEEDBACK.equals(p.getNamespace())) feedback(attributes,id,p);
                        }
                        if(!parameters.isEmpty()) attributes.add("fmtp:"+id+" "+String.join(";",parameters));
                    } else if(RTP.equals(ns) && "rtcp-mux".equals(name)) attributes.add("rtcp-mux");
                    else if(FEEDBACK.equals(ns)) feedback(attributes,"*",child);
                    else if(HDREXT.equals(ns) && "extmap-allow-mixed".equals(name)) attributes.add("extmap-allow-mixed");
                    else if(HDREXT.equals(ns) && "rtp-hdrext".equals(name)) {
                        String id=attr(child,"id"),send=child.getAttributeValue("senders");String suffix="";
                        if(send!=null && !"both".equals(send)) { if(!Arrays.asList("initiator","responder").contains(send)) throw invalid("Unsupported extension direction");suffix=send.equals(senderInitiator?"initiator":"responder")?"/sendonly":"/recvonly"; }
                        attributes.add("extmap:"+id+suffix+" "+attr(child,"uri"));
                    } else if(SSMA.equals(ns) && "source".equals(name)) {
                        String id=ssrc(attr(child,"ssrc"));for(StandardExtensionElement p:child.getElements()) if("parameter".equals(p.getElementName()) && SSMA.equals(p.getNamespace())) { String v=p.getAttributeValue("value");line(v==null?"":v);attributes.add("ssrc:"+id+" "+attr(p,"name")+(v==null?"":":"+v)); }
                    } else if(SSMA.equals(ns) && "ssrc-group".equals(name)) {
                        List<String> sources=new ArrayList<>();for(StandardExtensionElement p:child.getElements()) sources.add(ssrc(attr(p,"ssrc")));attributes.add("ssrc-group:"+attr(child,"semantics")+" "+String.join(" ",sources));
                    }
                }
                int candidates=0;for(StandardExtensionElement p:t.getElements()) if(ICE.equals(p.getNamespace()) && "candidate".equals(p.getElementName())) { if(++candidates>MAX_CANDIDATES) throw invalid("Too many candidates");attributes.add(candidate(p,attr(t,"ufrag"))); }
                Content content=new Content(attr(e,"name"),attr(d,"media"),direction,attr(t,"ufrag"),attr(t,"pwd"),fp.getText()==null?null:fp.getText().trim(),attr(fp,"setup"),attributes);rtp(content);contents.add(content);
            }
        }return new CallDescription(contents,bundle);
    }
    static StandardExtensionElement one(StandardExtensionElement e,String name,String ns) {
        StandardExtensionElement found=null;for(StandardExtensionElement c:e.getElements()) if(name.equals(c.getElementName()) && ns.equals(c.getNamespace())) { if(found!=null) throw invalid("Duplicate "+name);found=c; }if(found==null) throw invalid("Missing "+name);return found;
    }
    private static void feedback(List<String> a,String id,StandardExtensionElement p) {
        if("rtcp-fb".equals(p.getElementName())) a.add("rtcp-fb:"+id+" "+attr(p,"type")+(p.getAttributeValue("subtype")==null?"":" "+attr(p,"subtype")));
        else if("rtcp-fb-trr-int".equals(p.getElementName())) a.add("rtcp-fb:"+id+" trr-int "+attr(p,"value"));
    }
    private static String value(List<String> lines,String prefix) { String found=null;for(String s:lines) if(s.startsWith(prefix)) { if(found!=null) throw invalid("Duplicate SDP attribute");found=s.substring(prefix.length()); }return found; }
    private static String required(List<String> media,List<String> global,String prefix) { String v=value(media,prefix);if(v==null) v=value(global,prefix);if(v==null) throw invalid("Missing SDP attribute");return v; }
}
