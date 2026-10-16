// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.bookmarks.*;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.pep.PepManager;
import org.jivesoftware.smackx.pep.PepListener;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.JidCreate;

/** Private, per-item XEP-0402 bookmarks. Unknown XML survives edits. */
public final class XmppBookmarks implements AutoCloseable {
    public static final String NS="urn:xmpp:bookmarks:1";
    private static final String PUBSUB="http://jabber.org/protocol/pubsub",FORMS="jabber:x:data";
    public static final class Bookmark {
        public final String jid,name,nickname,password;
        public final boolean autojoin;
        private final StandardExtensionElement value;
        private Bookmark(String jid,StandardExtensionElement value) throws Exception {
            Jid parsed=JidCreate.from(jid);
            if(parsed.hasResource() || parsed.asEntityBareJidIfPossible()==null || !"conference".equals(value.getElementName()) || !NS.equals(value.getNamespace()))
                throw new IllegalArgumentException("Invalid room bookmark");
            this.jid=parsed.toString(); this.value=value;
            name=attribute(value,"name"); nickname=text(value,"nick"); password=text(value,"password");
            String auto=attribute(value,"autojoin");
            if(!auto.isEmpty() && !Arrays.asList("0","1","true","false").contains(auto)) throw new IllegalArgumentException("Invalid autojoin flag");
            autojoin=auto.equals("true") || auto.equals("1");
        }
        public static Bookmark parse(String jid,String xml) throws Exception { return new Bookmark(jid,StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml))); }
        public static Bookmark create(String jid,String nickname,boolean autojoin) throws Exception {
            return new Bookmark(jid,StandardExtensionElement.builder("conference",NS).build()).edit(nickname,autojoin);
        }
        public Bookmark edit(String nickname,boolean autojoin) throws Exception {
            if(!nickname.isEmpty()) org.jxmpp.jid.parts.Resourcepart.from(nickname);
            StandardExtensionElement.Builder edited=StandardExtensionElement.builder("conference",NS);
            for(Map.Entry<String,String> attr:value.getAttributes().entrySet()) if(!attr.getKey().equals("autojoin")) edited.addAttribute(attr.getKey(),attr.getValue());
            edited.addAttribute("autojoin",Boolean.toString(autojoin));
            for(StandardExtensionElement child:value.getElements()) if(!child.getElementName().equals("nick") || !NS.equals(child.getNamespace())) edited.addElement(child);
            if(!nickname.isEmpty()) edited.addElement(StandardExtensionElement.builder("nick",NS).setText(nickname).build());
            return new Bookmark(jid,edited.build());
        }
        public String xml() { return value.toXML().toString(); }
        private static String attribute(StandardExtensionElement value,String key) { String found=value.getAttributeValue(key); return found==null ? "" : found; }
        private static String text(StandardExtensionElement value,String key) { StandardExtensionElement child=value.getFirstElement(key,NS); return child==null || child.getText()==null ? "" : child.getText(); }
    }
    public static final class Snapshot {
        public final List<Bookmark> bookmarks;
        public final boolean writable;
        Snapshot(List<Bookmark> bookmarks,boolean writable) { this.bookmarks=Collections.unmodifiableList(bookmarks); this.writable=writable; }
    }
    private final XMPPConnection connection;
    private final PepListener listener;
    private boolean writable;
    public XmppBookmarks(XMPPConnection connection,Runnable changed) {
        this.connection=connection;
        ProviderManager.addExtensionProvider("conference",NS,StandardExtensionElementProvider.INSTANCE);
        ServiceDiscoveryManager.getInstanceFor(connection).addFeature(NS+"+notify");
        ServiceDiscoveryManager.getInstanceFor(connection).addFeature("storage:bookmarks+notify");
        org.jivesoftware.smackx.iqprivate.PrivateDataManager.addPrivateDataProvider("storage","storage:bookmarks",new Bookmarks.Provider());
        listener=(from,event,message) -> {
            if(connection.getUser()!=null && connection.getUser().asEntityBareJid().equals(from) && (NS.equals(event.getEvent().getNode()) || "storage:bookmarks".equals(event.getEvent().getNode()))) changed.run();
        };
        PepManager.getInstanceFor(connection).addPepListener(listener);
    }
    public synchronized Snapshot load() throws Exception {
        EntityBareJid own=connection.getUser().asEntityBareJid();
        ServiceDiscoveryManager disco=ServiceDiscoveryManager.getInstanceFor(connection);
        boolean options=disco.supportsFeature(own,PUBSUB+"#publish-options");
        boolean unified=disco.supportsFeature(own,NS+"#compat") || disco.supportsFeature(own,NS+"#compat-pep");
        List<Bookmark> found=new ArrayList<>();
        try {
            LeafNode node=PubSubManager.getInstanceFor(connection,own).getLeafNode(NS);
            List<PayloadItem<ExtensionElement>> items=node.getItems();
            for(PayloadItem<ExtensionElement> item:items) {
                if(item.getPayload()==null) throw new IllegalArgumentException("Bookmark has no payload");
                found.add(Bookmark.parse(item.getId(),item.getPayload().toXML().toString()));
            }
            try { node.subscribe(own); } catch(XMPPErrorException unsupported) { /* PEP +notify still supplies updates on servers without explicit self-subscriptions. */ }
            if(!found.isEmpty() || unified) { writable=options; return new Snapshot(found,writable); }
        } catch(XMPPErrorException missing) {
            if(missing.getStanzaError().getCondition()!=StanzaError.Condition.item_not_found) throw missing;
        }
        // Read both historical storage variants used by ordinary XMPP clients.
        try {
            List<PayloadItem<ExtensionElement>> items=PubSubManager.getInstanceFor(connection,own).getLeafNode("storage:bookmarks").getItems();
            for(PayloadItem<ExtensionElement> item:items) if(item.getPayload()!=null) {
                StandardExtensionElement storage=StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(item.getPayload().toXML().toString()));
                for(StandardExtensionElement old:storage.getElements()) if("conference".equals(old.getElementName()) && "storage:bookmarks".equals(old.getNamespace())) {
                    StandardExtensionElement.Builder conference=StandardExtensionElement.builder("conference",NS);
                    for(String attr:new String[]{"name","autojoin"}) if(old.getAttributeValue(attr)!=null) conference.addAttribute(attr,old.getAttributeValue(attr));
                    for(String child:new String[]{"nick","password"}) { StandardExtensionElement value=old.getFirstElement(child,"storage:bookmarks"); if(value!=null && value.getText()!=null) conference.addElement(child,value.getText()); }
                    found.add(new Bookmark(old.getAttributeValue("jid"),conference.build()));
                }
            }
        } catch(XMPPErrorException unsupported) { if(!unavailable(unsupported)) throw unsupported; }
        if(found.isEmpty()) try {
            Bookmarks legacy=(Bookmarks)org.jivesoftware.smackx.iqprivate.PrivateDataManager.getInstanceFor(connection).getPrivateData("storage","storage:bookmarks");
            for(BookmarkedConference old:legacy.getBookmarkedConferences()) {
                StandardExtensionElement.Builder conference=StandardExtensionElement.builder("conference",NS).addAttribute("autojoin",Boolean.toString(old.isAutoJoin()));
                if(old.getName()!=null) conference.addAttribute("name",old.getName());
                if(old.getNickname()!=null) conference.addElement("nick",old.getNickname().toString());
                if(old.getPassword()!=null) conference.addElement("password",old.getPassword());
                found.add(new Bookmark(old.getJid().toString(),conference.build()));
            }
        } catch(XMPPErrorException unsupported) { if(!unavailable(unsupported)) throw unsupported; }
        // Existing legacy lists remain authoritative until the server offers unified bookmarks.
        writable=options && found.isEmpty(); return new Snapshot(found,writable);
    }
    private static boolean unavailable(XMPPErrorException error) {
        StanzaError.Condition condition=error.getStanzaError().getCondition();
        return condition==StanzaError.Condition.item_not_found || condition==StanzaError.Condition.feature_not_implemented || condition==StanzaError.Condition.service_unavailable;
    }
    private static StandardExtensionElement form(String type) {
        StandardExtensionElement.Builder form=StandardExtensionElement.builder("x",FORMS).addAttribute("type","submit");
        for(String[] field:new String[][]{{"FORM_TYPE",type},{"pubsub#persist_items","true"},{"pubsub#max_items","max"},{"pubsub#send_last_published_item","never"},{"pubsub#access_model","whitelist"}}) {
            StandardExtensionElement.Builder value=StandardExtensionElement.builder("field",FORMS).addAttribute("var",field[0]);
            if(field[0].equals("FORM_TYPE")) value.addAttribute("type","hidden");
            form.addElement(value.addElement("value",field[1]).build());
        }
        return form.build();
    }
    private void iq(String namespace,StandardExtensionElement... values) throws Exception {
        IQ request=new IQ("pubsub",namespace) {
            @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                xml.rightAngleBracket(); for(StandardExtensionElement value:values) xml.append(value.toXML(namespace)); return xml;
            }
        };
        request.setType(IQ.Type.set); connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
    }
    public synchronized Bookmark save(Bookmark bookmark) throws Exception {
        if(!writable) throw new UnsupportedOperationException("Private PEP bookmark publishing is unavailable");
        // Refresh this item before editing so a concurrent client’s extension metadata survives.
        try {
            List<PayloadItem<ExtensionElement>> current=PubSubManager.getInstanceFor(connection,connection.getUser().asEntityBareJid()).getLeafNode(NS).getItems(Collections.singletonList(bookmark.jid));
            if(!current.isEmpty()) bookmark=Bookmark.parse(bookmark.jid,current.get(0).getPayload().toXML().toString()).edit(bookmark.nickname,bookmark.autojoin);
        } catch(XMPPErrorException missing) { if(missing.getStanzaError().getCondition()!=StanzaError.Condition.item_not_found) throw missing; }
        StandardExtensionElement publish=StandardExtensionElement.builder("publish",PUBSUB).addAttribute("node",NS)
                .addElement(StandardExtensionElement.builder("item",PUBSUB).addAttribute("id",bookmark.jid).addElement(bookmark.value).build()).build();
        StandardExtensionElement options=StandardExtensionElement.builder("publish-options",PUBSUB).addElement(form(PUBSUB+"#publish-options")).build();
        try { iq(PUBSUB,publish,options); }
        catch(XMPPErrorException conflict) {
            if(conflict.getStanzaError().getCondition()!=StanzaError.Condition.conflict) throw conflict;
            iq(PUBSUB+"#owner",StandardExtensionElement.builder("configure",PUBSUB+"#owner").addAttribute("node",NS).addElement(form(PUBSUB+"#node_config")).build());
            iq(PUBSUB,publish,options);
        }
        return bookmark;
    }
    @Override public void close() { PepManager.getInstanceFor(connection).removePepListener(listener); }
}
