// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.impl.JidCreate;

/** Publishes only this device in the separate experimental namespace; never touches classical nodes. */
final class PqOmemoPep {
    static final String NS = PqOmemoBundle.NS, DEVICES = NS + ":devices", BUNDLES = NS + ":bundles";
    private static final String PUBSUB = "http://jabber.org/protocol/pubsub", FORM = "jabber:x:data";
    private final XMPPConnection connection;
    private final PqOmemoDevice device;
    PqOmemoPep(XMPPConnection connection,PqOmemoDevice device) { this.connection = connection; this.device = device; }

    StandardExtensionElement item(String peer,String node,String id) throws Exception {
        try {
            LeafNode leaf = PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(peer)).getLeafNode(node);
            List<PayloadItem<ExtensionElement>> items = id == null ? leaf.getItems(Collections.singletonList("current")) : leaf.getItems(Collections.singletonList(id));
            if (items.isEmpty()) return null;
            if (items.size() != 1 || !(id == null ? "current" : id).equals(items.get(0).getId())) throw PqOmemoBundle.invalid();
            ExtensionElement value = items.get(0).getPayload(); if (value == null) throw PqOmemoBundle.invalid();
            return StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(value.toXML().toString()));
        } catch (XMPPErrorException absent) {
            // A timeout, access restriction or unsupported own-list query is not an empty list.
            if (absent.getStanzaError().getCondition() == StanzaError.Condition.item_not_found) return null;
            throw new XmppRequestException("Could not fetch experimental PQ OMEMO keys",absent);
        }
    }
    Set<Integer> devices(String peer) throws Exception { return Omemo2DeviceList.ids(item(peer,DEVICES,null),NS); }
    void initialize(PqOmemoBundle bundle) throws Exception {
        requireAccount();
        devices(device.jid); // Establish that reading our list succeeds before publishing anything.
        publish(BUNDLES,Integer.toString(device.id),bundle.xml(),"max");
        announce(); ensureOpen(DEVICES); ensureOpen(BUNDLES);
    }
    void announce() throws Exception {
        Omemo2DeviceList.announce(new Omemo2DeviceList.Store() {
            public StandardExtensionElement read() throws Exception { return item(device.jid,DEVICES,null); }
            public void publish(StandardExtensionElement value) throws Exception { PqOmemoPep.this.publish(DEVICES,"current",value,"1"); }
        },device.id,NS);
    }
    void publishBundle(PqOmemoBundle bundle) throws Exception { publish(BUNDLES,Integer.toString(device.id),bundle.xml(),"max"); }
    private void requireAccount() {
        if (!connection.isAuthenticated() || connection.getUser() == null || !device.jid.equals(connection.getUser().asBareJid().toString()))
            throw new IllegalStateException("PQ OMEMO account changed");
    }
    private StandardExtensionElement form(String type,String maximum) {
        StandardExtensionElement.Builder form = StandardExtensionElement.builder("x",FORM).addAttribute("type","submit");
        for (String[] field : new String[][]{{"FORM_TYPE",type},{"pubsub#access_model","open"},{"pubsub#persist_items","true"},{"pubsub#max_items",maximum}}) {
            StandardExtensionElement.Builder entry = StandardExtensionElement.builder("field",FORM).addAttribute("var",field[0]);
            if (field[0].equals("FORM_TYPE")) entry.addAttribute("type","hidden");
            form.addElement(entry.addElement("value",field[1]).build());
        }
        return form.build();
    }
    private void iq(String namespace,StandardExtensionElement... elements) throws Exception {
        requireAccount();
        IQ request = new IQ("pubsub",namespace) {
            @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                xml.rightAngleBracket(); for (StandardExtensionElement child : elements) xml.append(child.toXML(namespace)); return xml;
            }
        };
        request.setType(IQ.Type.set); connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
    }
    private void publish(String node,String id,StandardExtensionElement value,String maximum) throws Exception {
        if (!(DEVICES.equals(node) && "current".equals(id)) && !(BUNDLES.equals(node) && Integer.toString(device.id).equals(id)))
            throw new IllegalArgumentException("Cannot publish another device's keys");
        StandardExtensionElement published = StandardExtensionElement.builder("publish",PUBSUB).addAttribute("node",node)
                .addElement(StandardExtensionElement.builder("item",PUBSUB).addAttribute("id",id).addElement(value).build()).build();
        StandardExtensionElement options = StandardExtensionElement.builder("publish-options",PUBSUB).addElement(form(PUBSUB + "#publish-options",maximum)).build();
        try { iq(PUBSUB,published,options); }
        catch (XMPPErrorException error) {
            if (error.getStanzaError().getCondition() != StanzaError.Condition.conflict && error.getStanzaError().getCondition() != StanzaError.Condition.not_acceptable) throw error;
            String owner = PUBSUB + "#owner";
            iq(owner,StandardExtensionElement.builder("configure",owner).addAttribute("node",node).addElement(form(PUBSUB + "#node_config",maximum)).build());
            iq(PUBSUB,published,options);
        }
    }
    private void ensureOpen(String node) throws Exception {
        Node published = PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(device.jid)).getNode(node);
        if (published.getNodeConfiguration().getAccessModel() == AccessModel.open) return;
        String owner = PUBSUB + "#owner";
        StandardExtensionElement form = StandardExtensionElement.builder("x",FORM).addAttribute("type","submit")
                .addElement(StandardExtensionElement.builder("field",FORM).addAttribute("var","FORM_TYPE").addAttribute("type","hidden").addElement("value",PUBSUB + "#node_config").build())
                .addElement(StandardExtensionElement.builder("field",FORM).addAttribute("var","pubsub#access_model").addElement("value","open").build()).build();
        iq(owner,StandardExtensionElement.builder("configure",owner).addAttribute("node",node).addElement(form).build());
        if (published.getNodeConfiguration().getAccessModel() != AccessModel.open) throw new java.security.GeneralSecurityException("PQ key access remains restricted");
    }
}
