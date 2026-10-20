// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.net.URI;
import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.impl.JidCreate;

/** XEP-0372 current-body mentions, measured in Unicode code points. */
public final class MessageMentions {
    public static final String NAMESPACE="urn:xmpp:reference:0";
    public static final int MAX=64;
    private MessageMentions() {}
    public static final class Mention {
        public final int start,end;
        public final String address;
        public Mention(int start,int end,String address) {
            if(start<0 || end<=start || address==null || address.length()>2048) throw new IllegalArgumentException("Invalid mention");
            try {
                org.jxmpp.jid.Jid jid=JidCreate.from(address);
                if(!jid.isEntityBareJid()) throw new IllegalArgumentException("Mention requires a bare user JID");
                this.address=jid.toString();
            } catch(org.jxmpp.stringprep.XmppStringprepException invalid) { throw new IllegalArgumentException("Invalid mention JID",invalid); }
            this.start=start;this.end=end;
        }
    }
    public static void initialize() {
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("reference",NAMESPACE,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
    }
    static List<Mention> checked(String body,Collection<Mention> source) {
        if(source==null || source.size()>MAX) throw new IllegalArgumentException("Too many mentions");
        int length=body.codePointCount(0,body.length());List<Mention> result=new ArrayList<>(source);
        result.sort(Comparator.comparingInt(mention -> mention.start));int previous=0;
        for(Mention mention:result) { if(mention.start<previous || mention.end>length) throw new IllegalArgumentException("Invalid mention span");previous=mention.end; }
        return Collections.unmodifiableList(result);
    }
    /** Only use cleartext or authenticated decrypted metadata; legacy OMEMO outer references are not trusted. */
    static List<Mention> from(Message message,String body) {
        if(message==null || body==null || !body.equals(message.getBody())) return Collections.emptyList();
        List<ExtensionElement> references=message.getExtensions("reference",NAMESPACE);if(references.size()>MAX) return Collections.emptyList();
        List<Mention> result=new ArrayList<>();
        try {
            for(ExtensionElement extension:references) {
                if(!(extension instanceof StandardExtensionElement)) continue;
                StandardExtensionElement reference=(StandardExtensionElement)extension;
                if(!"mention".equals(reference.getAttributeValue("type"))) continue;
                if(reference.getAttributeValue("anchor")!=null || !reference.getElements().isEmpty()) continue;
                String value=reference.getAttributeValue("uri");if(value==null || value.length()>4096) continue;
                URI uri=new URI(value);
                if(!"xmpp".equalsIgnoreCase(uri.getScheme()) || uri.getRawFragment()!=null) continue;
                String address=uri.getSchemeSpecificPart();if(uri.getRawSchemeSpecificPart().indexOf('?')>=0 || address.startsWith("//")) continue;
                result.add(new Mention(Integer.parseInt(reference.getAttributeValue("begin")),Integer.parseInt(reference.getAttributeValue("end")),address));
            }
            return checked(body,result);
        } catch(Exception invalid) { return Collections.emptyList(); }
    }
    static void addTo(Message message,List<Mention> mentions) {
        for(Mention mention:mentions) {
            String uri;
            try { uri=new URI("xmpp",mention.address,null).toASCIIString().replace("?","%3F"); } catch(java.net.URISyntaxException invalid) { throw new IllegalArgumentException(invalid); }
            message.addExtension(StandardExtensionElement.builder("reference",NAMESPACE).addAttribute("type","mention")
                    .addAttribute("uri",uri).addAttribute("begin",Integer.toString(mention.start)).addAttribute("end",Integer.toString(mention.end)).build());
        }
    }
    static List<Mention> mapped(List<Mention> mentions,int[] map,boolean[] removed,boolean requireIntact) {
        List<Mention> result=new ArrayList<>();
        for(Mention mention:mentions) {
            boolean intact=true;for(int i=mention.start;i<mention.end;i++) if(requireIntact && removed[i]) { intact=false;break; }
            if(intact && map[mention.end]>map[mention.start]) result.add(new Mention(map[mention.start],map[mention.end],mention.address));
        }
        return result;
    }
}
