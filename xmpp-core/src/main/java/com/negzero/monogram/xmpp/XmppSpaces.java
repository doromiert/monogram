// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.BareJid;

/** XEP-0503 spaces are ordinary pubsub leaf nodes containing bookmarks and nested node subscriptions. */
public final class XmppSpaces implements AutoCloseable {
    public static final String NS="urn:xmpp:spaces:0",SUBSCRIPTION="urn:xmpp:pubsub:subscription:0";
    private static final String PUBSUB="http://jabber.org/protocol/pubsub",CONFIG=PUBSUB+"#node_config",META=PUBSUB+"#meta-data";
    public static final class Reference {
        public final String service,node,name;
        public Reference(String service,String node,String name) throws Exception {
            org.jxmpp.jid.Jid jid=XmppDeveloperTools.target(service);if(jid.hasResource())throw new IllegalArgumentException("Use a bare pubsub service address.");
            if(node==null || node.isEmpty() || node.length()>1024 || node.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Enter a space node name.");
            this.service=jid.toString();this.node=node;this.name=name==null || name.isEmpty()?node:name;
        }
        public String key(){return service+"\n"+node;}
    }
    public static final class Entry {
        public final String id,name,channel;
        public final Reference space;
        private Entry(String id,String name,String channel,Reference space){this.id=id;this.name=name;this.channel=channel;this.space=space;}
    }
    public static final class Contents {
        public final Reference space;
        public final List<Entry> entries;
        public Contents(Reference space,List<Entry> entries){this.space=space;this.entries=Collections.unmodifiableList(new ArrayList<>(entries));}
    }
    private final XMPPConnection connection;
    private final Runnable changed;
    private final Map<String,Reference> watched=new java.util.concurrent.ConcurrentHashMap<>();
    private final StanzaListener events=this::event;
    public XmppSpaces(XMPPConnection connection,Runnable changed){
        this.connection=connection;this.changed=changed;
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("conference",XmppBookmarks.NS,org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("subscription",SUBSCRIPTION,org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        ServiceDiscoveryManager.getInstanceFor(connection).addFeature(NS);
        connection.addAsyncStanzaListener(events,s->s instanceof Message && s.hasExtension("event",XmppMix.EVENT));
    }
    private void check(){if(!connection.isAuthenticated())throw new IllegalStateException("Reconnect your XMPP account first.");}
    private PubSubManager manager(Reference space)throws Exception{check();return PubSubManager.getInstanceFor(connection,org.jxmpp.jid.impl.JidCreate.bareFrom(space.service));}
    public void watch(Reference space){watched.put(space.key(),space);}
    private void event(Stanza stanza){
        if(stanza.getFrom()==null || stanza.getFrom().hasResource() || connection.getUser()==null || stanza.getTo()!=null && !connection.getUser().asBareJid().equals(stanza.getTo().asBareJid()))return;
        try{StandardExtensionElement event=XmppMix.xml(stanza.getExtension("event",XmppMix.EVENT));
            for(StandardExtensionElement payload:event.getElements()) {String node=payload.getAttributeValue("node");if(node!=null && watched.containsKey(stanza.getFrom()+"\n"+node)){changed.run();break;}}
        }catch(Exception invalid){/* Ignore malformed or unrelated pubsub notifications. */}
    }
    public Reference information(Reference requested)throws Exception{
        check();DiscoverInfo info=ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(org.jxmpp.jid.impl.JidCreate.from(requested.service),requested.node);
        String title=null,type=null;
        for(ExtensionElement extension:info.getExtensions("x","jabber:x:data")) {
            StandardExtensionElement x=XmppMix.xml(extension);String formType=null;
            for(StandardExtensionElement f:XmppMix.children(x,"field","jabber:x:data"))if("FORM_TYPE".equals(f.getAttributeValue("var")))formType=XmppMix.text(f,"value","jabber:x:data");
            if(!META.equals(formType))continue;
            for(StandardExtensionElement f:XmppMix.children(x,"field","jabber:x:data")){String key=f.getAttributeValue("var"),value=XmppMix.text(f,"value","jabber:x:data");if("pubsub#type".equals(key))type=value;else if("pubsub#title".equals(key))title=value;}
        }
        if(!NS.equals(type))throw new IllegalArgumentException("This pubsub node is not an XEP-0503 space.");
        return new Reference(requested.service,requested.node,title==null?requested.name:title);
    }
    public List<Reference> discover(String service)throws Exception{
        check();org.jxmpp.jid.Jid jid=XmppDeveloperTools.target(service);List<Reference> result=new ArrayList<>();
        List<DiscoverItems.Item> items=ServiceDiscoveryManager.getInstanceFor(connection).discoverItems(jid).getItems();
        if(items.size()>500)throw new IllegalStateException("This service has too many nodes to browse. Enter a space node directly.");
        for(DiscoverItems.Item item:items)if(item.getNode()!=null)try{result.add(information(new Reference(item.getEntityID().toString(),item.getNode(),item.getName())));}catch(IllegalArgumentException notSpace){/* Other pubsub data is not a space. */}
        return result;
    }
    static List<Entry> entries(List<PayloadItem<ExtensionElement>> items)throws Exception{
        if(items.size()>2000)throw new IllegalArgumentException("This space has too many entries.");
        List<Entry> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(PayloadItem<ExtensionElement> item:items){
            if(item.getPayload()==null || item.getId()==null)continue;
            StandardExtensionElement value=XmppMix.xml(item.getPayload());
            if(!ids.add(item.getId()))throw new IllegalArgumentException("Duplicate space entry.");
            if("conference".equals(value.getElementName()) && XmppBookmarks.NS.equals(value.getNamespace())){
                String jid=XmppMix.channel(item.getId()).toString(),name=value.getAttributeValue("name");result.add(new Entry(item.getId(),name==null||name.isEmpty()?jid:name,jid,null));
            }else if("subscription".equals(value.getElementName()) && SUBSCRIPTION.equals(value.getNamespace())){
                Reference ref=new Reference(value.getAttributeValue("server"),value.getAttributeValue("node"),null);result.add(new Entry(item.getId(),ref.name,null,ref));
            }
            // Avatar/banner, links and future payloads are not chat channels.
        }
        return result;
    }
    public Contents load(Reference requested)throws Exception{
        Reference space=information(requested);watch(space);
        return new Contents(space,entries(XmppMix.items(connection,org.jxmpp.jid.impl.JidCreate.bareFrom(space.service),space.node,2000)));
    }
    public String subscribe(Reference space)throws Exception{
        information(space);Subscription subscription=manager(space).getLeafNode(space.node).subscribe(connection.getUser().asBareJid().toString());watch(space);
        return subscription.getState()==null?"unknown":subscription.getState().toString();
    }
    public void unsubscribe(Reference space)throws Exception{manager(space).getLeafNode(space.node).unsubscribe(connection.getUser().asBareJid().toString());watched.remove(space.key());}
    static DataForm configuration(String title,boolean publiclyVisible){
        if(title==null || title.trim().isEmpty() || title.length()>1024)throw new IllegalArgumentException("Enter a space name.");
        DataForm.Builder form=DataForm.builder(DataForm.Type.submit).setFormType(CONFIG);
        for(String[] field:new String[][]{{"pubsub#type",NS},{"pubsub#title",title.trim()},{"pubsub#node_type","leaf"},{"pubsub#access_model",publiclyVisible?"open":"whitelist"},{"pubsub#publish_model","publishers"},{"pubsub#max_items","2000"}})
            form.addField(FormField.textSingleBuilder(field[0]).setValue(field[1]).build());
        for(String[] field:new String[][]{{"pubsub#notify_retract","true"},{"pubsub#persist_items","true"},{"pubsub#purge_offline","false"}})
            form.addField(FormField.booleanBuilder(field[0]).setValue(Boolean.parseBoolean(field[1])).build());
        return form.build();
    }
    public Reference create(Reference requested,boolean publiclyVisible)throws Exception{
        check();StandardExtensionElement config=XmppMix.xml(configuration(requested.name,publiclyVisible));
        MixIq iq=new MixIq(StandardExtensionElement.builder("pubsub",PUBSUB)
                .addElement(StandardExtensionElement.builder("create",PUBSUB).addAttribute("node",requested.node).build())
                .addElement(StandardExtensionElement.builder("configure",PUBSUB).addElement(config).build()).build());
        iq.setTo(org.jxmpp.jid.impl.JidCreate.from(requested.service));iq.setType(IQ.Type.set);connection.createStanzaCollectorAndSend(iq).nextResultOrThrow();
        try {manager(requested).getLeafNode(requested.node).subscribe(connection.getUser().asBareJid().toString());}
        catch(Exception failed){throw new XmppRequestException("The space was created at "+requested.service+" / "+requested.node+", but following it failed. Open that space to retry",failed);}
        watch(requested);return requested;
    }
    public void addChannel(Reference space,String channel,String title)throws Exception{
        String jid=XmppMix.channel(channel).toString();if(title==null || title.length()>1024)throw new IllegalArgumentException("Invalid channel name");
        manager(space).getLeafNode(space.node).publish(new PayloadItem<>(jid,StandardExtensionElement.builder("conference",XmppBookmarks.NS).addAttribute("name",title).build()));
    }
    public void addSpace(Reference parent,Reference child)throws Exception{
        if(parent.key().equals(child.key()))throw new IllegalArgumentException("A space cannot contain itself.");information(child);
        String id=UUID.nameUUIDFromBytes(child.key().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        manager(parent).getLeafNode(parent.node).publish(new PayloadItem<>(id,StandardExtensionElement.builder("subscription",SUBSCRIPTION).addAttribute("server",child.service).addAttribute("node",child.node).build()));
    }
    public void remove(Reference space,String id)throws Exception{manager(space).getLeafNode(space.node).deleteItem(id);}
    public void access(Reference space,String address,boolean allowed)throws Exception{
        BareJid jid=XmppMix.channel(address);check();
        if(jid.equals(connection.getUser().asBareJid()))throw new IllegalArgumentException("Choose another contact. Your owner permissions are managed by the pubsub service.");
        manager(space).getLeafNode(space.node).modifyAffiliationAsOwner(Collections.singletonList(new Affiliation(jid,allowed?Affiliation.Type.member:Affiliation.Type.none)));
    }
    @Override public void close(){connection.removeAsyncStanzaListener(events);watched.clear();}
}
