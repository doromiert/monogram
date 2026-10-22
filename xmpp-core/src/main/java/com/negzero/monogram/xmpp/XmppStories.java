// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.time.Instant;
import java.net.URI;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.packet.StanzaError;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.pubsub.form.FillableConfigureForm;
import org.jxmpp.jid.impl.JidCreate;

/** XEP-0501/0472 Atom stories, matching Monocles' one-enclosure representation. */
public final class XmppStories {
    public static final String NODE="urn:xmpp:pubsub-social-feed:stories:0";
    public static final String ATOM="http://www.w3.org/2005/Atom";
    public static final long LIFETIME=86400000L;
    private XmppStories() {}
    public static final class Story {
        public final String id,owner,url,mime,title;
        public final long published;
        public Story(String id,String owner,String url,String mime,String title,long published) {
            if(id==null || id.isEmpty() || id.length()>1024) throw new IllegalArgumentException("Invalid story id");
            try {
                this.owner=JidCreate.entityBareFrom(owner).toString();
                URI uri=new URI(url);
                if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || url.length()>8192) throw new IllegalArgumentException("Invalid story media URL");
            } catch(Exception invalid) { throw new IllegalArgumentException("Invalid story address",invalid); }
            if(mime==null || !(mime.matches("image/(jpeg|png|webp|gif)") || mime.matches("video/(mp4|webm)"))) throw new IllegalArgumentException("Unsupported story media");
            if(title!=null && title.length()>32768 || published<=0 || published>System.currentTimeMillis()+300000) throw new IllegalArgumentException("Invalid story content");
            this.id=id;this.url=url;this.mime=mime;this.title=title==null ? "" : title;this.published=published;
        }
        public boolean expired(long now) { return now-published>=LIFETIME; }
        public StandardExtensionElement entry() {
            String stamp=Instant.ofEpochMilli(published).toString();
            return StandardExtensionElement.builder("entry",ATOM)
                    .addElement("id","urn:uuid:"+id).addElement("title",title.isEmpty()?"Story":title)
                    .addElement("published",stamp).addElement("updated",stamp)
                    .addElement(StandardExtensionElement.builder("author",ATOM).addElement("uri","xmpp:"+owner).build())
                    .addElement(StandardExtensionElement.builder("link",ATOM).addAttribute("rel","enclosure")
                            .addAttribute("href",url).addAttribute("type",mime).addAttribute("title",title).build()).build();
        }
    }
    public static void initialize(XMPPConnection connection) {
        ProviderManager.addExtensionProvider("entry",ATOM,StandardExtensionElementProvider.INSTANCE);
        ServiceDiscoveryManager discovery=ServiceDiscoveryManager.getInstanceFor(connection);
        discovery.addFeature("urn:xmpp:pubsub-social-feed:1");discovery.addFeature(NODE);discovery.addFeature(NODE+"+notify");
    }
    public static Story parse(String owner,String itemId,StandardExtensionElement entry) {
        if(entry==null || !"entry".equals(entry.getElementName()) || !ATOM.equals(entry.getNamespace()) || entry.toXML().length()>65536) return null;
        try {
            StandardExtensionElement author=entry.getFirstElement("author",ATOM);
            if(author!=null) {
                StandardExtensionElement uri=author.getFirstElement("uri",ATOM);
                if(uri!=null) {
                    String value=uri.getText();if(value==null || !value.startsWith("xmpp:")) return null;
                    String address=java.net.URLDecoder.decode(value.substring(5).replace("+","%2B"),"UTF-8");
                    if(!JidCreate.entityBareFrom(owner).equals(JidCreate.entityBareFrom(address))) return null;
                }
            }
            StandardExtensionElement enclosure=null;
            for(StandardExtensionElement child:entry.getElements()) if(ATOM.equals(child.getNamespace()) && "link".equals(child.getElementName()) && "enclosure".equals(child.getAttributeValue("rel"))) {
                if(enclosure!=null) return null; enclosure=child;
            }
            if(enclosure==null) return null;
            StandardExtensionElement stamp=entry.getFirstElement("published",ATOM);
            if(stamp==null) stamp=entry.getFirstElement("updated",ATOM);
            long time=Instant.parse(stamp.getText()).toEpochMilli();
            StandardExtensionElement title=entry.getFirstElement("title",ATOM);
            Story result=new Story(itemId,owner,enclosure.getAttributeValue("href"),enclosure.getAttributeValue("type"),title==null?"":title.getText(),time);
            return result.expired(System.currentTimeMillis()) ? null : result;
        } catch(Exception malformed) { return null; }
    }
    public static List<Story> fetch(XMPPConnection connection,String owner) throws Exception {
        initialize(connection);
        try {
            LeafNode node=PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(owner)).getLeafNode(NODE);
            List<Item> items=node.getItems(100);List<Story> result=new ArrayList<>();List<String> expired=new ArrayList<>();
            for(Item item:items) if(item instanceof PayloadItem) {
                Object payload=((PayloadItem<?>)item).getPayload();
                if(payload instanceof StandardExtensionElement) {
                    Story story=parse(owner,item.getId(),(StandardExtensionElement)payload);
                    if(story!=null) result.add(story);
                    else if(connection.getUser()!=null && connection.getUser().asBareJid().toString().equals(owner)) {
                        try {
                            StandardExtensionElement entry=(StandardExtensionElement)payload;
                            StandardExtensionElement stamp=entry.getFirstElement("published",ATOM);
                            if(stamp!=null && System.currentTimeMillis()-Instant.parse(stamp.getText()).toEpochMilli()>=LIFETIME) expired.add(item.getId());
                        } catch(Exception malformed) { /* Do not delete entries with unknown expiry. */ }
                    }
                }
            }
            if(!expired.isEmpty()) node.deleteItem(expired);
            result.sort(Comparator.comparingLong(story->story.published));return result;
        } catch(XMPPErrorException absent) {
            StanzaError.Condition condition=absent.getStanzaError().getCondition();
            if(condition==StanzaError.Condition.item_not_found || condition==StanzaError.Condition.forbidden) return Collections.emptyList();
            throw absent;
        }
    }
    public static Story publish(XMPPConnection connection,String id,String url,String mime,String title,boolean publicAudience) throws Exception {
        if(publicAudience) throw new IllegalArgumentException("XMPP stories use subscribed-contact visibility");
        initialize(connection);String owner=connection.getUser().asEntityBareJid().toString();
        PubSubManager manager=PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(owner));LeafNode node;
        try { node=manager.getLeafNode(NODE); }
        catch(XMPPErrorException absent) {
            if(absent.getStanzaError().getCondition()!=StanzaError.Condition.item_not_found) throw absent;
            FillableConfigureForm config=manager.getDefaultConfiguration().getFillableForm();
            configure(config,publicAudience);node=(LeafNode)manager.createNode(NODE,config);
        }
        // Never silently publish with the server's possibly public default access model.
        FillableConfigureForm config=node.getNodeConfiguration().getFillableForm();configure(config,publicAudience);node.sendConfigurationForm(config);
        Story story=new Story(id,owner,url,mime,title,System.currentTimeMillis());
        node.publish(new PayloadItem<StandardExtensionElement>(id,story.entry()));return story;
    }
    private static void configure(FillableConfigureForm config,boolean publicAudience) {
        if(publicAudience) throw new IllegalArgumentException("Unsupported story audience");
        config.setAccessModel(AccessModel.presence);
        config.setPersistentItems(true);config.setMaxItems(100);config.setDataType(NODE);
    }
    public static void republish(XMPPConnection connection,Story story) throws Exception {
        if(!connection.getUser().asEntityBareJid().toString().equals(story.owner) || story.expired(System.currentTimeMillis()))
            throw new IllegalArgumentException("Only your active stories can be edited");
        LeafNode node=PubSubManager.getInstanceFor(connection,connection.getUser().asEntityBareJid()).getLeafNode(NODE);
        FillableConfigureForm config=node.getNodeConfiguration().getFillableForm();configure(config,false);node.sendConfigurationForm(config);
        node.publish(new PayloadItem<StandardExtensionElement>(story.id,story.entry()));
    }
    public static void retract(XMPPConnection connection,String id) throws Exception {
        PubSubManager.getInstanceFor(connection,connection.getUser().asEntityBareJid()).getLeafNode(NODE).deleteItem(id);
    }
}
