// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp.calls;

import java.util.*;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.xml.*;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;

/** XEP-0215. URI conversion adapted from Monocles model/disco/external/Services.java,
 * codeberg.org/monocles/monocles_chat at 129dbcc7cdc919c73a65e50ee121efecc36c5e7e.
 * No public fallback server, no insecure TLS certificate override; credentials expire with this connection. */
public final class XmppIceServers {
    public static final String NS="urn:xmpp:extdisco:2";
    public static final class IceServer {
        public final List<String> urls;public final String username,password;
        public IceServer(List<String> urls,String username,String password) { this.urls=Collections.unmodifiableList(new ArrayList<>(urls));this.username=username;this.password=password; }
    }
    private final XMPPConnection connection;private volatile List<IceServer> cached=Collections.emptyList();private volatile long expiry;private volatile long revision;
    public XmppIceServers(XMPPConnection connection) {
        this.connection=connection;
        ProviderManager.addIQProvider("services",NS,new IQProvider<JingleCodec.Packet>() {
            @Override public JingleCodec.Packet parse(XmlPullParser p,int depth,XmlEnvironment env) throws java.io.IOException,XmlPullParserException { return new JingleCodec.Packet(JingleCodec.read(p,new int[]{0,0},0)); }
        });
    }
    public List<IceServer> discover() throws Exception {
        long fence=revision;
        if(System.currentTimeMillis()<expiry) return cached;
        if(!connection.isAuthenticated()) throw new IllegalStateException("XMPP is disconnected");
        if(!ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(connection.getXMPPServiceDomain()).containsFeature(NS)) return Collections.emptyList();
        JingleCodec.Packet request=new JingleCodec.Packet(StandardExtensionElement.builder("services",NS).build());request.setTo(connection.getXMPPServiceDomain());request.setType(IQ.Type.get);
        IQ result=connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
        if(!(result instanceof JingleCodec.Packet)) throw new IllegalArgumentException("Invalid ICE service response");
        StandardExtensionElement root=((JingleCodec.Packet)result).element;List<IceServer> found=new ArrayList<>();long until=System.currentTimeMillis()+60000;
        if(root.getElements().size()>32) throw new IllegalArgumentException("Too many ICE servers");
        for(StandardExtensionElement service:root.getElements()) {
            if(!"service".equals(service.getElementName()) || !NS.equals(service.getNamespace())) continue;
            IceServer parsed=parse(service);if(parsed==null) continue;
            String expires=service.getAttributeValue("expires");
            if(expires!=null) {
                try { String normalized=expires.replaceAll("Z$","+0000").replaceAll("([+-][0-9]{2}):([0-9]{2})$","$1$2");java.text.SimpleDateFormat format=new java.text.SimpleDateFormat(expires.contains(".")?"yyyy-MM-dd'T'HH:mm:ss.SSSZ":"yyyy-MM-dd'T'HH:mm:ssZ",Locale.ROOT);format.setLenient(false);long end=format.parse(normalized).getTime()-30000;if(end<=System.currentTimeMillis()) continue;until=Math.min(until,end); }
                catch(java.text.ParseException invalid) { continue; }
            }
            found.add(parsed);
        }
        synchronized(this) { if(revision!=fence) throw new IllegalStateException("ICE discovery session changed");cached=Collections.unmodifiableList(found);expiry=until;return cached; }
    }
    public synchronized void clear() { revision++;cached=Collections.emptyList();expiry=0; }
    static IceServer parse(StandardExtensionElement e) {
        String type=e.getAttributeValue("type"),host=e.getAttributeValue("host"),port=e.getAttributeValue("port"),transport=e.getAttributeValue("transport");
        if(!Arrays.asList("stun","stuns","turn","turns").contains(type) || host==null || port==null || !Arrays.asList("udp","tcp").contains(transport)) return null;
        try {
            JingleCodec.token(host,253);if(!host.matches("[A-Za-z0-9.:-]+") || host.startsWith("-") || host.contains("..")) return null;JingleCodec.number(port,1,65535);
            if(type.endsWith("s") && !"tcp".equals(transport)) return null;
            String user=e.getAttributeValue("username"),password=e.getAttributeValue("password");
            if(type.startsWith("turn") && (user==null || user.isEmpty() || password==null || password.isEmpty())) return null;
            if(user!=null && (user.length()>4096 || user.indexOf('\0')>=0) || password!=null && (password.length()>4096 || password.indexOf('\0')>=0)) return null;
            String uri=type+":"+(host.contains(":")?"["+host+"]":host)+":"+port+(type.startsWith("turn")?"?transport="+transport:"");return new IceServer(Collections.singletonList(uri),user,password);
        } catch(IllegalArgumentException invalid) { return null; }
    }
}
