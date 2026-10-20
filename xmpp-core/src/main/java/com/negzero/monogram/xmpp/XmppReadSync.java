// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.CancellationException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.StanzaCollector;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.pep.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.JidCreate;

/** XEP-0490 own-account PEP state. Never disclose private state through a contact message. */
public final class XmppReadSync implements AutoCloseable {
    public static final String LOCAL_ID="read-sync";
    public static final String NS="urn:xmpp:mds:displayed:0",SID="urn:xmpp:sid:0";
    private static final String PUBSUB="http://jabber.org/protocol/pubsub",FORMS="jabber:x:data";
    public static final class Entry {
        public final String chat,by,id;
        private Entry(String chat,String by,String id) { this.chat=chat;this.by=by;this.id=id; }
        public static Entry create(String chat,String by,String id) throws Exception {
            Jid address=JidCreate.from(chat);EntityBareJid authority=JidCreate.entityBareFrom(by);
            if(address.asEntityBareJidIfPossible()==null || !address.toString().equals(chat) || !authority.toString().equals(by) || !MessageReceipt.valid(id))
                throw new IllegalArgumentException("Invalid private displayed reference");
            return new Entry(chat,by,id);
        }
        public static Entry parse(String chat,String xml) throws Exception {
            if(xml==null || xml.length()>65536 || xml.contains("<!")) throw new IllegalArgumentException("Invalid private displayed payload");
            StandardExtensionElement value=StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml));
            if(!NS.equals(value.getNamespace()) || !"displayed".equals(value.getElementName()) || !value.getAttributes().isEmpty()
                    || value.getElements().size()!=1 || value.getText()!=null && !value.getText().trim().isEmpty())
                throw new IllegalArgumentException("Invalid private displayed payload");
            StandardExtensionElement stanza=value.getElements().get(0);
            if(!SID.equals(stanza.getNamespace()) || !"stanza-id".equals(stanza.getElementName()) || !stanza.getElements().isEmpty()
                    || stanza.getText()!=null && !stanza.getText().trim().isEmpty()) throw new IllegalArgumentException("Invalid private displayed stanza ID");
            return create(chat,stanza.getAttributeValue("by"),stanza.getAttributeValue("id"));
        }
        StandardExtensionElement payload() {
            return StandardExtensionElement.builder("displayed",NS).addElement(StandardExtensionElement.builder("stanza-id",SID)
                    .addAttribute("by",by).addAttribute("id",id).build()).build();
        }
        public String xml() { return XmppXml.string(payload()); }
    }
    /** Keep actual own-server IDs separate from MAM result IDs, which need not match. */
    static String assignedId(Message message,EntityBareJid own) {
        return RoomDisplayedMarker.assignedId(message,own);
    }
    public static final class Snapshot {
        public final List<Entry> entries;
        public final boolean writable,directIds;
        private final long generation;
        Snapshot(List<Entry> entries,boolean writable,boolean directIds,long generation) {
            this.entries=Collections.unmodifiableList(new ArrayList<>(entries));this.writable=writable;this.directIds=directIds;this.generation=generation;
        }
    }
    private final XMPPConnection connection;
    private final PepListener listener;
    private final Object gate=new Object();
    private volatile boolean enabled,closed,writable;
    private long generation;
    public XmppReadSync(XMPPConnection connection,Runnable changed) {
        this.connection=connection;
        ProviderManager.addExtensionProvider("displayed",NS,StandardExtensionElementProvider.INSTANCE);
        listener=(from,event,message) -> {
            if(enabled && !closed && connection.getUser()!=null && connection.getUser().asEntityBareJid().equals(from)
                    && NS.equals(event.getEvent().getNode())) changed.run();
        };
        PepManager.getInstanceFor(connection).addPepListener(listener);
    }
    public void setEnabled(boolean value) {
        synchronized(gate) {
            if(enabled==(value && !closed)) return;
            generation++;writable=false;enabled=value && !closed;
            ServiceDiscoveryManager disco=ServiceDiscoveryManager.getInstanceFor(connection);
            if(enabled) { disco.addFeature(NS);disco.addFeature(NS+"+notify"); }
            else { disco.removeFeature(NS);disco.removeFeature(NS+"+notify"); }
        }
    }
    private EntityBareJid account() {
        if(closed || !enabled || !connection.isAuthenticated() || connection.getUser()==null) throw new CancellationException("Read synchronization is inactive");
        return connection.getUser().asEntityBareJid();
    }
    public Snapshot load() throws Exception {
        final long revision;final EntityBareJid own;
        synchronized(gate) { own=account();revision=generation; }
        ServiceDiscoveryManager disco=ServiceDiscoveryManager.getInstanceFor(connection);
        boolean options=disco.supportsFeature(own,PUBSUB+"#publish-options"),direct=disco.supportsFeature(own,SID);
        List<Entry> entries=new ArrayList<>();
        try {
            List<PayloadItem<ExtensionElement>> items=PubSubManager.getInstanceFor(connection,own).getLeafNode(NS).getItems();
            if(items.size()>10000) throw new IllegalArgumentException("Private displayed node exceeds capacity");
            for(PayloadItem<ExtensionElement> item:items) {
                if(item.getPayload()==null) continue;
                try { entries.add(Entry.parse(item.getId(),item.getPayload().toXML().toString())); }
                catch(Exception invalid) { /* One malformed item cannot hide other chats' valid state. */ }
            }
        } catch(XMPPErrorException missing) {
            StanzaError.Condition condition=missing.getStanzaError().getCondition();
            if(condition!=StanzaError.Condition.item_not_found && condition!=StanzaError.Condition.feature_not_implemented
                    && condition!=StanzaError.Condition.service_unavailable) throw missing;
        }
        synchronized(gate) {
            if(!own.equals(account()) || revision!=generation) throw new CancellationException("Read synchronization account changed");
            writable=options;return new Snapshot(entries,options,direct,revision);
        }
    }
    static StandardExtensionElement form(String type) {
        StandardExtensionElement.Builder form=StandardExtensionElement.builder("x",FORMS).addAttribute("type","submit");
        for(String[] field:new String[][]{{"FORM_TYPE",type},{"pubsub#persist_items","true"},{"pubsub#max_items","max"},
                {"pubsub#send_last_published_item","never"},{"pubsub#access_model","whitelist"}}) {
            StandardExtensionElement.Builder value=StandardExtensionElement.builder("field",FORMS).addAttribute("var",field[0]);
            if(field[0].equals("FORM_TYPE")) value.addAttribute("type","hidden");
            form.addElement(value.addElement("value",field[1]).build());
        }
        return form.build();
    }
    private void send(long revision,String namespace,StandardExtensionElement... payloads) throws Exception {
        IQ request=new IQ("pubsub",namespace) {
            @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                xml.rightAngleBracket();for(StandardExtensionElement payload:payloads) xml.append(payload.toXML(namespace));return xml;
            }
        };
        request.setType(IQ.Type.set);StanzaCollector collector;
        synchronized(gate) {
            request.setTo(account());
            if(revision!=generation) throw new CancellationException("Read synchronization consent changed");
            collector=connection.createStanzaCollectorAndSend(request);
        }
        try { collector.nextResultOrThrow(); } finally { collector.cancel(); }
    }
    public void publish(Entry entry,Snapshot snapshot) throws Exception {
        EntityBareJid own=account();
        if(snapshot==null || !snapshot.writable || !writable) throw new UnsupportedOperationException("Private displayed publishing is unavailable");
        if(!entry.by.equals(own.toString()) && !entry.by.equals(JidCreate.from(entry.chat).asBareJid().toString()))
            throw new IllegalArgumentException("Displayed authority does not belong to this chat");
        StandardExtensionElement publish=StandardExtensionElement.builder("publish",PUBSUB).addAttribute("node",NS)
                .addElement(StandardExtensionElement.builder("item",PUBSUB).addAttribute("id",entry.chat).addElement(entry.payload()).build()).build();
        StandardExtensionElement options=StandardExtensionElement.builder("publish-options",PUBSUB).addElement(form(PUBSUB+"#publish-options")).build();
        try { send(snapshot.generation,PUBSUB,publish,options); }
        catch(XMPPErrorException conflict) {
            if(conflict.getStanzaError().getCondition()!=StanzaError.Condition.conflict) throw conflict;
            send(snapshot.generation,PUBSUB+"#owner",StandardExtensionElement.builder("configure",PUBSUB+"#owner").addAttribute("node",NS).addElement(form(PUBSUB+"#node_config")).build());
            send(snapshot.generation,PUBSUB,publish,options);
        }
    }
    @Override public void close() {
        synchronized(gate) { generation++;closed=true;enabled=false;writable=false; }
        ServiceDiscoveryManager.getInstanceFor(connection).removeFeature(NS);
        ServiceDiscoveryManager.getInstanceFor(connection).removeFeature(NS+"+notify");
        PepManager.getInstanceFor(connection).removePepListener(listener);
    }
}
