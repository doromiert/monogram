// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp.calls;

import java.io.IOException;
import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.xml.*;

/** Bounded RTP/ICE/DTLS wire mapping. Adapted from Monocles stanzas/{Content,RtpDescription,
 * IceUdpTransportInfo,Group}.java, codeberg.org/monocles/monocles_chat commit
 * 129dbcc7cdc919c73a65e50ee121efecc36c5e7e. Uses Smack XML, immutable snapshots and strict bounds. */
public final class JingleCodec {
    public static final String NS="urn:xmpp:jingle:1",RTP="urn:xmpp:jingle:apps:rtp:1",ICE="urn:xmpp:jingle:transports:ice-udp:1",
            DTLS="urn:xmpp:jingle:apps:dtls:0",GROUP="urn:xmpp:jingle:apps:grouping:0",JMI="urn:xmpp:jingle-message:0",
            FEEDBACK="urn:xmpp:jingle:apps:rtp:rtcp-fb:0",HDREXT="urn:xmpp:jingle:apps:rtp:rtp-hdrext:0",SSMA="urn:xmpp:jingle:apps:rtp:ssma:0";
    public static final int MAX_XML=262144,MAX_CANDIDATES=128;
    private JingleCodec() {}
    public static final class Content {
        public final String name,media,direction,ufrag,password,fingerprint,setup;
        public final List<String> attributes;
        public Content(String name,String media,String direction,String ufrag,String password,String fingerprint,String setup,List<String> attributes) {
            this.name=token(name,64);if(!Arrays.asList("audio","video").contains(media)) throw invalid("Unsupported media");this.media=media;
            if(!Arrays.asList("sendrecv","sendonly","recvonly","inactive").contains(direction)) throw invalid("Invalid direction");this.direction=direction;
            this.ufrag=token(ufrag,256);this.password=token(password,256);
            if(ufrag.length()<4 || password.length()<22) throw invalid("Invalid ICE credentials");
            if(fingerprint==null || !fingerprint.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){31}")) throw invalid("DTLS SHA-256 fingerprint required");
            this.fingerprint=fingerprint.toUpperCase(Locale.ROOT);
            if(!Arrays.asList("actpass","active","passive").contains(setup)) throw invalid("Invalid DTLS role");this.setup=setup;
            if(attributes.size()>512) throw invalid("Too many media attributes");
            List<String> copy=new ArrayList<>();for(String a:attributes) { line(a);copy.add(a); }this.attributes=Collections.unmodifiableList(copy);
            if(!copy.contains("rtcp-mux")) throw invalid("RTCP mux required");
        }
    }
    public static final class CallDescription {
        public final List<Content> contents;public final List<String> bundle;
        public CallDescription(List<Content> contents,List<String> bundle) {
            if(contents.isEmpty() || contents.size()>2) throw invalid("Expected audio and optional video");
            Set<String> names=new HashSet<>(),media=new HashSet<>();boolean audio=false;
            for(Content c:contents) { if(!names.add(c.name) || !media.add(c.media)) throw invalid("Duplicate content");audio|="audio".equals(c.media); }
            if(!audio || bundle.size()!=names.size() || !new HashSet<>(bundle).equals(names)) throw invalid("Complete BUNDLE required");
            this.contents=Collections.unmodifiableList(new ArrayList<>(contents));this.bundle=Collections.unmodifiableList(new ArrayList<>(bundle));
        }
        public boolean video() { for(Content c:contents) if("video".equals(c.media)) return true;return false; }
    }
    public static final class Packet extends IQ {
        public final StandardExtensionElement element;
        public Packet(StandardExtensionElement element) { super(element.getElementName(),element.getNamespace());this.element=element; }
        @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder x) {
            for(Map.Entry<String,String> a:element.getAttributes().entrySet()) x.attribute(a.getKey(),a.getValue());x.rightAngleBracket();
            if(element.getText()!=null) x.escape(element.getText());for(StandardExtensionElement c:element.getElements()) x.append(c.toXML(element.getNamespace()));return x;
        }
    }
    public static synchronized void initialize() {
        ProviderManager.addIQProvider("jingle",NS,new IQProvider<Packet>() {
            @Override public Packet parse(XmlPullParser p,int depth,XmlEnvironment env) throws IOException,XmlPullParserException { return new Packet(read(p,new int[]{0,0},0)); }
        });
        for(String action:Arrays.asList("propose","proceed","accept","reject","retract","ringing")) ProviderManager.addExtensionProvider(action,JMI,new ExtensionElementProvider<StandardExtensionElement>() {
            @Override public StandardExtensionElement parse(XmlPullParser p,int depth,XmlEnvironment env) throws IOException,XmlPullParserException { return read(p,new int[]{0,0},0); }
        });
    }
    static StandardExtensionElement read(XmlPullParser p,int[] budget,int nesting) throws IOException,XmlPullParserException {
        if(nesting>8 || ++budget[1]>2048 || p.getAttributeCount()>24) throw new IOException("Call XML exceeds limits");
        String tag=p.getName(),ns=p.getNamespace();budget[0]+=tag.length()+ns.length();StandardExtensionElement.Builder b=StandardExtensionElement.builder(tag,ns);
        for(int i=0;i<p.getAttributeCount();i++) { if(p.getAttributeNamespace(i)!=null && !p.getAttributeNamespace(i).isEmpty()) throw new IOException("Namespaced call attributes are unsupported");String v=p.getAttributeValue(i);budget[0]+=p.getAttributeName(i).length()+v.length();if(budget[0]>MAX_XML) throw new IOException("Call XML exceeds limits");b.addAttribute(p.getAttributeName(i),v); }
        int depth=p.getDepth();StringBuilder text=new StringBuilder();
        while(true) {
            XmlPullParser.Event event=p.next();
            if(event==XmlPullParser.Event.START_ELEMENT) b.addElement(read(p,budget,nesting+1));
            else if(event==XmlPullParser.Event.TEXT_CHARACTERS) { String t=p.getText();budget[0]+=t.length();if(budget[0]>MAX_XML) throw new IOException("Call XML exceeds limits");text.append(t); }
            else if(event==XmlPullParser.Event.END_ELEMENT && p.getDepth()==depth) break;
            else if(event==XmlPullParser.Event.END_DOCUMENT) throw new IOException("Incomplete call XML");
        }
        if(text.length()>0) b.setText(text.toString());return b.build();
    }
    public static StandardExtensionElement description(CallDescription d,boolean localInitiator) {
        StandardExtensionElement.Builder root=StandardExtensionElement.builder("jingle",NS);
        for(Content c:d.contents) root.addElement(content(c,localInitiator,true));
        StandardExtensionElement.Builder group=StandardExtensionElement.builder("group",GROUP).addAttribute("semantics","BUNDLE");
        for(String mid:d.bundle) group.addElement(StandardExtensionElement.builder("content",GROUP).addAttribute("name",mid).build());root.addElement(group.build());return root.build();
    }
    static StandardExtensionElement content(Content c,boolean localInitiator,boolean withDescription) {
        StandardExtensionElement.Builder b=StandardExtensionElement.builder("content",NS).addAttribute("creator","initiator").addAttribute("name",c.name);
        String senders="sendrecv".equals(c.direction)?"both":"inactive".equals(c.direction)?"none":("sendonly".equals(c.direction)==localInitiator?"initiator":"responder");b.addAttribute("senders",senders);
        if(withDescription) b.addElement(rtp(c,localInitiator));b.addElement(transport(c,true));return b.build();
    }
    static StandardExtensionElement rtp(Content c) { return rtp(c,true); }
    static StandardExtensionElement rtp(Content c,boolean localInitiator) {
        StandardExtensionElement.Builder d=StandardExtensionElement.builder("description",RTP).addAttribute("media",c.media);
        Map<String,StandardExtensionElement.Builder> payloads=new LinkedHashMap<>();
        for(String a:c.attributes) if(a.startsWith("rtpmap:")) {
            String[] parts=a.substring(7).split(" ",2);if(parts.length!=2) throw invalid("Invalid rtpmap");String[] enc=parts[1].split("/");
            if(enc.length<2 || enc.length>3) throw invalid("Invalid rtpmap");number(parts[0],0,127);number(enc[1],1,384000);
            StandardExtensionElement.Builder p=StandardExtensionElement.builder("payload-type",RTP).addAttribute("id",parts[0]).addAttribute("name",token(enc[0],64)).addAttribute("clockrate",enc[1]);
            if(enc.length==3) { number(enc[2],1,8);p.addAttribute("channels",enc[2]); }if(payloads.put(parts[0],p)!=null) throw invalid("Duplicate payload");
        }
        if(payloads.isEmpty() || payloads.size()>64) throw invalid("Missing RTP codecs");
        for(String a:c.attributes) {
            if(a.startsWith("fmtp:")) {
                String[] pair=a.substring(5).split(" ",2);StandardExtensionElement.Builder p=payloads.get(pair[0]);if(p==null || pair.length<2) throw invalid("Unknown fmtp payload");
                for(String param:pair[1].split(";")) { String[] kv=param.trim().split("=",2);p.addElement(StandardExtensionElement.builder("parameter",RTP).addAttribute("name",token(kv[0],128)).addAttribute("value",kv.length==2?kv[1]:"").build()); }
            } else if(a.startsWith("rtcp-fb:")) {
                String[] pair=a.substring(8).split(" ",3);if(pair.length<2) throw invalid("Invalid feedback");
                StandardExtensionElement.Builder fb;
                if("trr-int".equals(pair[1])) { if(pair.length!=3) throw invalid("Missing feedback interval");number(pair[2],0,65535);fb=StandardExtensionElement.builder("rtcp-fb-trr-int",FEEDBACK).addAttribute("value",pair[2]); }
                else { fb=StandardExtensionElement.builder("rtcp-fb",FEEDBACK).addAttribute("type",token(pair[1],64));if(pair.length==3) fb.addAttribute("subtype",token(pair[2],64)); }
                if("*".equals(pair[0])) d.addElement(fb.build());else { StandardExtensionElement.Builder p=payloads.get(pair[0]);if(p==null) throw invalid("Unknown feedback payload");p.addElement(fb.build()); }
            } else if(a.startsWith("extmap:")) {
                String[] pair=a.substring(7).split(" ",3);if(pair.length<2) throw invalid("Invalid RTP extension");String[] id=pair[0].split("/");number(id[0],1,255);
                StandardExtensionElement.Builder ext=StandardExtensionElement.builder("rtp-hdrext",HDREXT).addAttribute("id",id[0]).addAttribute("uri",token(pair[1],512));
                if(id.length>1) ext.addAttribute("senders",id[1].equals("sendonly")?(localInitiator?"initiator":"responder"):id[1].equals("recvonly")?(localInitiator?"responder":"initiator"):"both");d.addElement(ext.build());
            } else if(a.startsWith("ssrc-group:")) {
                String[] values=a.substring(11).split(" ");StandardExtensionElement.Builder group=StandardExtensionElement.builder("ssrc-group",SSMA).addAttribute("semantics",token(values[0],32));
                for(int i=1;i<values.length;i++) group.addElement(StandardExtensionElement.builder("source",SSMA).addAttribute("ssrc",ssrc(values[i])).build());d.addElement(group.build());
            }
        }
        for(StandardExtensionElement.Builder p:payloads.values()) d.addElement(p.build());
        Map<String,StandardExtensionElement.Builder> sources=new LinkedHashMap<>();
        for(String a:c.attributes) if(a.startsWith("ssrc:")) {
            String[] pair=a.substring(5).split(" ",2);if(pair.length!=2) throw invalid("Invalid SSRC");String id=ssrc(pair[0]);String[] kv=pair[1].split(":",2);
            StandardExtensionElement.Builder source=sources.get(id);if(source==null) { source=StandardExtensionElement.builder("source",SSMA).addAttribute("ssrc",id);sources.put(id,source); }
            source.addElement(StandardExtensionElement.builder("parameter",SSMA).addAttribute("name",token(kv[0],64)).addAttribute("value",kv.length==2?kv[1]:"").build());
        }
        for(StandardExtensionElement.Builder source:sources.values()) d.addElement(source.build());
        if(c.attributes.contains("extmap-allow-mixed")) d.addElement(StandardExtensionElement.builder("extmap-allow-mixed",HDREXT).build());
        d.addElement(StandardExtensionElement.builder("rtcp-mux",RTP).build());return d.build();
    }
    static StandardExtensionElement transport(Content c,boolean fingerprint) {
        StandardExtensionElement.Builder t=StandardExtensionElement.builder("transport",ICE).addAttribute("ufrag",c.ufrag).addAttribute("pwd",c.password);
        if(fingerprint) t.addElement(StandardExtensionElement.builder("fingerprint",DTLS).addAttribute("hash","sha-256").addAttribute("setup",c.setup).setText(c.fingerprint).build());
        int count=0;for(String a:c.attributes) if(a.startsWith("candidate:")) { if(++count>MAX_CANDIDATES) throw invalid("Too many candidates");t.addElement(candidate(a,c.ufrag)); }return t.build();
    }
    public static StandardExtensionElement candidate(String value,String ufrag) {
        line(value);if(value.startsWith("a=")) value=value.substring(2);if(!value.startsWith("candidate:")) throw invalid("Invalid ICE candidate");
        String[] p=value.substring(10).trim().split(" +");if(p.length<8 || !"typ".equals(p[6]) || (p.length-8)%2!=0) throw invalid("Invalid ICE candidate");
        number(p[1],1,2);number(p[3],0,4294967295L);number(p[5],1,65535);
        if(!Arrays.asList("udp","tcp").contains(p[2].toLowerCase(Locale.ROOT)) || !Arrays.asList("host","srflx","prflx","relay").contains(p[7])) throw invalid("Invalid ICE transport");
        StandardExtensionElement.Builder b=StandardExtensionElement.builder("candidate",ICE).addAttribute("id",UUID.randomUUID().toString()).addAttribute("foundation",token(p[0],64)).addAttribute("component",p[1]).addAttribute("protocol",p[2].toLowerCase(Locale.ROOT)).addAttribute("priority",p[3]).addAttribute("ip",token(p[4],255)).addAttribute("port",p[5]).addAttribute("type",p[7]).addAttribute("generation","0");
        for(int i=8;i<p.length;i+=2) {
            String key=p[i],v=token(p[i+1],256);
            if("ufrag".equals(key) && !ufrag.equals(v)) throw invalid("Stale ICE generation");
            if("raddr".equals(key)) b.addAttribute("rel-addr",v);else if("rport".equals(key)) { number(v,1,65535);b.addAttribute("rel-port",v); }
            else if("tcptype".equals(key)) { if(!Arrays.asList("active","passive","so").contains(v)) throw invalid("Invalid TCP candidate");b.addAttribute("tcptype",v); }
            else if("generation".equals(key) && !"0".equals(v)) throw invalid("Unsupported ICE generation");
        }return b.build();
    }
    public static String candidate(StandardExtensionElement c,String ufrag) {
        String result="candidate:"+attr(c,"foundation")+" "+attr(c,"component")+" "+attr(c,"protocol")+" "+attr(c,"priority")+" "+attr(c,"ip")+" "+attr(c,"port")+" typ "+attr(c,"type");
        for(String[] pair:new String[][]{{"rel-addr","raddr"},{"rel-port","rport"},{"tcptype","tcptype"},{"generation","generation"}}) if(c.getAttributeValue(pair[0])!=null) result+=" "+pair[1]+" "+attr(c,pair[0]);
        result+=" ufrag "+token(ufrag,256);candidate(result,ufrag);return result;
    }
    static String attr(StandardExtensionElement e,String name) { String v=e.getAttributeValue(name);if(v==null) throw invalid("Missing "+name);return token(v,1024); }
    static String ssrc(String v) { number(v,0,4294967295L);return v; }
    static long number(String v,long min,long max) { try { long n=Long.parseLong(v);if(n<min || n>max) throw invalid("Number out of range");return n; }catch(NumberFormatException bad) { throw invalid("Invalid number"); } }
    static String token(String s,int max) { if(s==null || s.isEmpty() || s.length()>max || s.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) throw invalid("Invalid call token");return s; }
    static void line(String s) { if(s==null || s.length()>4096 || s.indexOf('\r')>=0 || s.indexOf('\n')>=0 || s.indexOf('\0')>=0) throw invalid("Invalid SDP line"); }
    static IllegalArgumentException invalid(String text) { return new IllegalArgumentException(text); }
}
