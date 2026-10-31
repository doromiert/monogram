// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.roster.packet.RosterPacket;
import org.jivesoftware.smack.roster.provider.RosterPacketProvider;
import org.jivesoftware.smack.util.PacketParserUtils;

/** Smack 4.4.8 drops MIX annotations. Keep its normal roster parser and retain annotations beside it. */
final class MixRoster {
    static final String NS="urn:xmpp:mix:roster:0";
    private static final Map<RosterPacket,Map<String,String>> annotations=Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean initialized;
    static synchronized void initialize() {
        if(initialized)return;initialized=true;
        ProviderManager.addIQProvider("query",RosterPacket.NAMESPACE,new IQProvider<RosterPacket>() {
            @Override public RosterPacket parse(org.jivesoftware.smack.xml.XmlPullParser parser,int depth,XmlEnvironment env)
                    throws java.io.IOException,org.jivesoftware.smack.xml.XmlPullParserException,org.jivesoftware.smack.parsing.SmackParsingException {
                StandardExtensionElement tree=StandardExtensionElementProvider.INSTANCE.parse(parser,depth,env);
                RosterPacket packet=RosterPacketProvider.INSTANCE.parse(PacketParserUtils.getParserFor(XmppXml.string(tree)));
                Map<String,String> found=new LinkedHashMap<>();
                for(StandardExtensionElement item:XmppMix.children(tree,"item",RosterPacket.NAMESPACE)) {
                    StandardExtensionElement channel=item.getFirstElement("channel",NS);
                    if(channel==null)continue;
                    String jid=item.getAttributeValue("jid"),id=channel.getAttributeValue("participant-id");
                    try {found.put(XmppMix.channel(jid).toString(),XmppMix.participantId(id));}catch(Exception invalid){/* Not a valid MIX annotation. */}
                }
                annotations.put(packet,Collections.unmodifiableMap(found));return packet;
            }
        });
    }
    static Map<String,String> channels(RosterPacket packet) {return annotations.getOrDefault(packet,Collections.emptyMap());}
    static MixIq request() {
        MixIq iq=new MixIq(StandardExtensionElement.builder("query",RosterPacket.NAMESPACE)
                .addElement(StandardExtensionElement.builder("annotate",NS).build()).build());
        iq.setType(IQ.Type.get);return iq;
    }
}
