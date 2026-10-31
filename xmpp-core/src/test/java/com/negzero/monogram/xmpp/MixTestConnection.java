package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.function.Function;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;

/** In-memory transport exercising Smack IQ collectors and real stanza providers, without a live account. */
final class MixTestConnection extends AbstractXMPPConnection implements AutoCloseable {
    final List<Stanza> sent=Collections.synchronizedList(new ArrayList<>());
    volatile Function<IQ,String> response=this::standard;
    boolean pam=true;
    String rosterItems="<item jid='general@mix.example.org' name='General'><channel xmlns='urn:xmpp:mix:roster:0' participant-id='self'/></item>";
    String participantItems="<item id='self'><participant xmlns='urn:xmpp:mix:core:1'><jid>alice@example.org</jid><nick>Alice</nick></participant></item><item id='bob'><participant xmlns='urn:xmpp:mix:core:1'><jid>bob@example.org</jid><nick>Bob</nick></participant></item>";
    MixTestConnection() throws Exception {
        super(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").setHost("127.0.0.1").setUsernameAndPassword("alice","test").build());
        connected=true;authenticated=true;user=JidCreate.entityFullFrom("alice@example.org/test");setReplyTimeout(1000);
    }
    String standard(IQ iq){
        String xml=XmppXml.string(iq),ns=iq.getChildElementNamespace();
        if("http://jabber.org/protocol/disco#info".equals(ns)) {
            if(iq.getTo()!=null && iq.getTo().equals(user.asBareJid()))return "<query xmlns='http://jabber.org/protocol/disco#info'>"+(pam?"<feature var='urn:xmpp:mix:pam:2'/>":"")+"</query>";
            if(xml.contains("node="))return "<query xmlns='http://jabber.org/protocol/disco#info'><identity category='pubsub' type='leaf'/><x xmlns='jabber:x:data' type='result'><field var='FORM_TYPE'><value>http://jabber.org/protocol/pubsub#meta-data</value></field><field var='pubsub#type'><value>urn:xmpp:spaces:0</value></field><field var='pubsub#title'><value>Team</value></field></x></query>";
            return "<query xmlns='http://jabber.org/protocol/disco#info'><identity category='conference' type='mix'/><feature var='urn:xmpp:mix:core:1'/><feature var='urn:xmpp:mix:core:1#create-channel'/><feature var='urn:xmpp:mam:2'/></query>";
        }
        if("jabber:iq:roster".equals(ns))return "<query xmlns='jabber:iq:roster'>"+rosterItems+"</query>";
        if(XmppMix.PAM.equals(ns))return xml.contains("client-leave")?"<client-leave xmlns='urn:xmpp:mix:pam:2'><leave xmlns='urn:xmpp:mix:core:1'/></client-leave>":"<client-join xmlns='urn:xmpp:mix:pam:2'><join xmlns='urn:xmpp:mix:core:1' id='self'><subscribe node='urn:xmpp:mix:nodes:messages'/><subscribe node='urn:xmpp:mix:nodes:participants'/><nick>Alice</nick></join></client-join>";
        if("http://jabber.org/protocol/pubsub".equals(ns)) {
            if(xml.contains("<subscribe"))return "<pubsub xmlns='http://jabber.org/protocol/pubsub'><subscription node='team' jid='alice@example.org' subscription='subscribed'/></pubsub>";
            if(xml.contains(XmppMix.PARTICIPANTS))return "<pubsub xmlns='http://jabber.org/protocol/pubsub'><items node='"+XmppMix.PARTICIPANTS+"'>"+participantItems+"</items></pubsub>";
            if(xml.contains(XmppMix.INFO))return "<pubsub xmlns='http://jabber.org/protocol/pubsub'><items node='"+XmppMix.INFO+"'><item id='now'><x xmlns='jabber:x:data' type='result'><field var='Name'><value>General</value></field><field var='Description'><value>A channel</value></field></x></item></items></pubsub>";
            return "<pubsub xmlns='http://jabber.org/protocol/pubsub'/>";
        }
        return "";
    }
    @Override protected void sendStanzaInternal(Stanza stanza){
        sent.add(stanza);if(!(stanza instanceof IQ) || !((IQ)stanza).isRequestIQ())return;IQ request=(IQ)stanza;
        String payload=response.apply(request);if(payload==null)return;
        try{String from=request.getTo()==null?user.asBareJid().toString():request.getTo().toString();inject("<iq xmlns='jabber:client' type='result' id='"+request.getStanzaId()+"' from='"+from+"' to='"+user+"'>"+payload+"</iq>");}catch(Exception failed){throw new AssertionError(failed);}
    }
    void inject(String xml)throws Exception{processStanza(PacketParserUtils.parseStanza(xml));}
    @Override public boolean isSecureConnection(){return true;}
    @Override public boolean isUsingCompression(){return false;}
    @Override public void sendNonza(Nonza nonza){}
    @Override protected void connectInternal(){}
    @Override protected void loginInternal(String name,String password,Resourcepart resource){}
    @Override public void instantShutdown(){authenticated=false;connected=false;}
    @Override protected void shutdown(){instantShutdown();}
    @Override public void close(){instantShutdown();}
}
