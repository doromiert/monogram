// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.pep.PepListener;
import org.jivesoftware.smackx.pep.PepManager;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.impl.JidCreate;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

/** Blocking OMEMO2 protocol API. Call on network/crypto workers, never the Android UI thread. */
public final class XmppOmemo2 implements AutoCloseable {
    public static final String NS = Omemo2Bundle.NS, DEVICES_NODE = NS+":devices", BUNDLES_NODE = NS+":bundles";
    private static final String PUBSUB = "http://jabber.org/protocol/pubsub", FORMS = "jabber:x:data";
    private final XMPPConnection connection;
    public final Omemo2Device device;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String,Omemo2Bundle> bundles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Set<Integer>> lists = new ConcurrentHashMap<>();
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    private final Consumer<String> changed;
    private final PepListener listener;
    private volatile boolean ready, closed;
    private volatile Exception lastError;
    public XmppOmemo2(XMPPConnection connection, Omemo2Device device, Consumer<String> changed) {
        this.connection = connection; this.device = device; this.changed = changed;
        FileShare.initialize(); MessageCorrection.initialize();
        ProviderManager.addExtensionProvider("encrypted",NS,StandardExtensionElementProvider.INSTANCE);
        listener = (from,event,message) -> {
            String node = event.getEvent().getNode();
            if (closed || !ready || !DEVICES_NODE.equals(node) && !BUNDLES_NODE.equals(node)) return;
            try {
                // Serialize refreshes so an older in-flight IQ cannot overwrite a newer PEP update.
                synchronized (XmppOmemo2.this) {
                    if (closed || !ready) return;
                    bundles.keySet().removeIf(key -> key.startsWith(from.toString()+"\n"));
                    Set<Integer> ids = fetchDevices(from.toString());
                    if (from.toString().equals(device.jid) && !ids.contains(device.id)) {
                        ids.add(device.id); publishDevices(ids); device.devices(device.jid,ids); lists.put(device.jid,new HashSet<>(ids));
                    }
                    changed.accept(from.toString());
                }
            } catch (Exception error) { lastError = error; }
        };
        PepManager.getInstanceFor(connection).addPepListener(listener);
    }
    public boolean ready() { return ready && !closed; }
    public Exception lastError() { return lastError; }
    public synchronized void initialize() throws Exception {
        if (closed || !connection.isAuthenticated() || !connection.getUser().asBareJid().toString().equals(device.jid)) throw new IllegalStateException("OMEMO2 account changed");
        ready = false; lastError = null; bundles.clear(); lists.clear(); watched.clear();
        Omemo2Bundle own = device.prepare();
        publish(BUNDLES_NODE,Integer.toString(device.id),own.xml(),"max");
        Set<Integer> ids = fetchDevices(device.jid); ids.add(device.id); publishDevices(ids); device.devices(device.jid,ids); lists.put(device.jid,new HashSet<>(ids));
        watch(device.jid,DEVICES_NODE); watch(device.jid,BUNDLES_NODE);
        ServiceDiscoveryManager disco = ServiceDiscoveryManager.getInstanceFor(connection);
        disco.addFeature(NS); disco.addFeature(DEVICES_NODE+"+notify"); disco.addFeature(BUNDLES_NODE+"+notify"); ready = true;
        connection.sendStanza(new Presence(Presence.Type.available));
    }
    public synchronized Map<Integer,Omemo2Bundle> discover(String peer) throws Exception {
        peer = bare(peer); Map<Integer,Omemo2Bundle> result = new LinkedHashMap<>();
        for (int id : new java.util.TreeSet<>(fetchDevices(peer))) if (!peer.equals(device.jid) || id != device.id) result.put(id,bundle(peer,id,true));
        return result;
    }
    private synchronized Map<Integer,Omemo2Bundle> targets(String peer) throws Exception {
        Set<Integer> ids = watched.contains(peer+"\n"+DEVICES_NODE) ? lists.get(peer) : null;
        if (ids == null) ids = fetchDevices(peer);
        Map<Integer,Omemo2Bundle> result = new LinkedHashMap<>();
        for (int id : new java.util.TreeSet<>(ids)) if (!peer.equals(device.jid) || id != device.id) result.put(id,bundle(peer,id,false));
        return result;
    }
    private String bare(String jid) throws Exception { if (jid == null || jid.indexOf('/') >= 0) throw new IllegalArgumentException("Bare peer required"); return JidCreate.entityBareFrom(jid).toString(); }
    private synchronized Set<Integer> fetchDevices(String peer) throws Exception {
        StandardExtensionElement root = item(peer,DEVICES_NODE,null); Set<Integer> ids = new HashSet<>();
        if (root != null) {
            if (!"devices".equals(root.getElementName()) || !NS.equals(root.getNamespace()) || root.toXML().length() > 65536) throw Omemo2Bundle.invalid();
            for (StandardExtensionElement child : root.getElements()) {
                if (!"device".equals(child.getElementName()) || !NS.equals(child.getNamespace())) continue;
                if (ids.size() >= 500 || !ids.add(Omemo2Bundle.positive(child.getAttributeValue("id")))) throw Omemo2Bundle.invalid();
            }
        }
        device.devices(peer,ids); lists.put(peer,new HashSet<>(ids)); return ids;
    }
    private synchronized Omemo2Bundle bundle(String peer, int id, boolean refresh) throws Exception {
        String key = peer+"\n"+id;
        Omemo2Bundle known = refresh || !watched.contains(peer+"\n"+BUNDLES_NODE) ? null : bundles.get(key);
        if (known != null) return known;
        StandardExtensionElement root = item(peer,BUNDLES_NODE,Integer.toString(id));
        if (root == null) throw new java.security.GeneralSecurityException("OMEMO2 bundle unavailable");
        Omemo2Bundle found = Omemo2Bundle.parse(root); device.observe(peer,id,found); bundles.put(key,found); changed.accept(peer); return found;
    }
    private StandardExtensionElement item(String peer, String node, String id) throws Exception {
        try {
            LeafNode leaf = PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(peer)).getLeafNode(node);
            List<PayloadItem<ExtensionElement>> items = id == null ? leaf.getItems(1) : leaf.getItems(java.util.Collections.singletonList(id));
            if (items.isEmpty()) return null;
            if (items.size() != 1 || id != null && !id.equals(items.get(0).getId())) throw Omemo2Bundle.invalid();
            ExtensionElement value = items.get(0).getPayload(); if (value == null) throw Omemo2Bundle.invalid();
            watch(peer,node);
            return StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(value.toXML().toString()));
        } catch (XMPPErrorException absent) {
            StanzaError.Condition condition = absent.getStanzaError().getCondition();
            if (condition == StanzaError.Condition.item_not_found || condition == StanzaError.Condition.feature_not_implemented || condition == StanzaError.Condition.service_unavailable) return null;
            throw absent;
        }
    }
    /** Non-roster contacts need explicit subscriptions; failed subscriptions disable send-side caches. */
    private void watch(String peer, String node) throws Exception {
        String key = peer+"\n"+node; if (watched.contains(key)) return;
        try {
            Subscription subscription = PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(peer)).getLeafNode(node)
                    .subscribe(connection.getUser().asEntityBareJid());
            if (subscription.getState() == Subscription.State.subscribed) watched.add(key);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        catch (Exception unavailable) { lastError = unavailable; }
    }
    private static StandardExtensionElement form(String type, String maximum) {
        StandardExtensionElement.Builder form = StandardExtensionElement.builder("x",FORMS).addAttribute("type","submit");
        for (String[] entry : new String[][]{{"FORM_TYPE",type},{"pubsub#access_model","open"},{"pubsub#persist_items","true"},{"pubsub#max_items",maximum}}) {
            StandardExtensionElement.Builder field = StandardExtensionElement.builder("field",FORMS).addAttribute("var",entry[0]);
            if (entry[0].equals("FORM_TYPE")) field.addAttribute("type","hidden");
            form.addElement(field.addElement("value",entry[1]).build());
        }
        return form.build();
    }
    private void iq(String namespace, StandardExtensionElement... elements) throws Exception {
        IQ request = new IQ("pubsub",namespace) {
            @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                xml.rightAngleBracket(); for (StandardExtensionElement element : elements) xml.append(element.toXML(namespace)); return xml;
            }
        };
        request.setType(IQ.Type.set); connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
    }
    private void publish(String node, String id, StandardExtensionElement value, String maximum) throws Exception {
        StandardExtensionElement published = StandardExtensionElement.builder("publish",PUBSUB).addAttribute("node",node)
                .addElement(StandardExtensionElement.builder("item",PUBSUB).addAttribute("id",id).addElement(value).build()).build();
        StandardExtensionElement options = StandardExtensionElement.builder("publish-options",PUBSUB).addElement(form(PUBSUB+"#publish-options",maximum)).build();
        try { iq(PUBSUB,published,options); }
        catch (XMPPErrorException error) {
            if (error.getStanzaError().getCondition() != StanzaError.Condition.conflict
                    && error.getStanzaError().getCondition() != StanzaError.Condition.not_acceptable) throw error;
            String owner = PUBSUB+"#owner";
            iq(owner,StandardExtensionElement.builder("configure",owner).addAttribute("node",node).addElement(form(PUBSUB+"#node_config",maximum)).build());
            iq(PUBSUB,published,options);
        }
    }
    private void publishDevices(Set<Integer> ids) throws Exception {
        StandardExtensionElement.Builder list = StandardExtensionElement.builder("devices",NS);
        for (int id : new java.util.TreeSet<>(ids)) list.addElement(StandardExtensionElement.builder("device",NS).addAttribute("id",Integer.toString(id)).build());
        publish(DEVICES_NODE,"current",list.build(),"1");
    }
    private void requireReady() { if (!ready()) throw new IllegalStateException("OMEMO2 is not initialized"); }
    public Message encrypt(Message plaintext) throws Exception {
        requireReady(); Omemo2Payload.direct(plaintext);
        if (!plaintext.getFrom().asBareJid().toString().equals(device.jid)) throw Omemo2Bundle.invalid();
        String peer = plaintext.getTo().asBareJid().toString();
        Map<String,Map<Integer,Omemo2Bundle>> recipients = new LinkedHashMap<>();
        recipients.put(peer,targets(peer)); if (!peer.equals(device.jid)) recipients.put(device.jid,targets(device.jid));
        if (recipients.get(peer).isEmpty() && !peer.equals(device.jid)) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES);
        int count = recipients.values().stream().mapToInt(Map::size).sum(); if (count == 0 || count > 1000) throw Omemo2Bundle.invalid();
        byte[] envelope = Omemo2Payload.envelope(plaintext,random);
        try (Omemo2Payload.Sealed sealed = Omemo2Payload.seal(envelope,random); Omemo2Records tx = device.records.begin()) {
            StandardExtensionElement.Builder header = StandardExtensionElement.builder("header",NS).addAttribute("sid",Integer.toString(device.id));
            for (Map.Entry<String,Map<Integer,Omemo2Bundle>> recipient : recipients.entrySet()) {
                if (recipient.getValue().isEmpty()) continue;
                StandardExtensionElement.Builder keys = StandardExtensionElement.builder("keys",NS).addAttribute("jid",recipient.getKey());
                for (Map.Entry<Integer,Omemo2Bundle> target : recipient.getValue().entrySet()) {
                    device.session(tx,recipient.getKey(),target.getKey(),target.getValue());
                    byte[][] encrypted = Omemo2Native.encrypt(tx,device.id,recipient.getKey(),target.getKey(),sealed.material);
                    keys.addElement(key(target.getKey(),encrypted));
                }
                header.addElement(keys.build());
            }
            StandardExtensionElement encrypted = StandardExtensionElement.builder("encrypted",NS).addElement(header.build())
                    .addElement("payload",Base64.getEncoder().encodeToString(sealed.ciphertext)).build();
            Message result = new Message(plaintext.getTo(),plaintext.getType()); result.setFrom(plaintext.getFrom()); result.setStanzaId(plaintext.getStanzaId());
            result.addExtension(encrypted); result.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build());
            tx.commit(); return result;
        } finally { Arrays.fill(envelope,(byte)0); }
    }
    private StandardExtensionElement key(int id, byte[][] encrypted) {
        return StandardExtensionElement.builder("key",NS).addAttribute("rid",Integer.toString(id))
                .addAttribute("kex",encrypted[0][0] == 1 ? "true" : "false").setText(Base64.getEncoder().encodeToString(encrypted[1])).build();
    }
    public static final class Received {
        public final Message content, acknowledgement;
        public final boolean replenish;
        Received(Message content, Message acknowledgement, boolean replenish) { this.content = content; this.acknowledgement = acknowledgement; this.replenish = replenish; }
    }
    /** Persist content before sending its optional acknowledgement or refreshing public bundles. */
    public Received receive(Message outer) throws Exception { return receive(outer,null); }
    public Received receive(Message outer, java.util.Date archiveTime) throws Exception {
        requireReady(); Omemo2Payload.direct(outer);
        if (!outer.getFrom().asBareJid().toString().equals(device.jid) && !outer.getTo().asBareJid().toString().equals(device.jid)) throw Omemo2Bundle.invalid();
        List<ExtensionElement> encrypted = outer.getExtensions("encrypted",NS);
        if (encrypted.size() != 1 || !(encrypted.get(0) instanceof StandardExtensionElement)) throw Omemo2Bundle.invalid();
        StandardExtensionElement root = (StandardExtensionElement) encrypted.get(0);
        if (root.toXML().length() > 1024*1024) throw Omemo2Bundle.invalid();
        StandardExtensionElement header = Omemo2Bundle.single(root,"header",NS); int sender = Omemo2Bundle.positive(header.getAttributeValue("sid"));
        String peer = outer.getFrom().asBareJid().toString();
        StandardExtensionElement selected = null; Set<String> groups = new HashSet<>(); int count = 0;
        for (StandardExtensionElement group : header.getElements()) {
            if (!"keys".equals(group.getElementName()) || !NS.equals(group.getNamespace())) throw Omemo2Bundle.invalid();
            String jid = group.getAttributeValue("jid"); if (!bare(jid).equals(jid) || !groups.add(jid)) throw Omemo2Bundle.invalid(); Set<Integer> ids = new HashSet<>();
            for (StandardExtensionElement key : group.getElements()) {
                if (!"key".equals(key.getElementName()) || !NS.equals(key.getNamespace()) || ++count > 1000) throw Omemo2Bundle.invalid();
                int id = Omemo2Bundle.positive(key.getAttributeValue("rid")); if (!ids.add(id)) throw Omemo2Bundle.invalid();
                if (jid.equals(device.jid) && id == device.id) selected = key;
            }
        }
        if (selected == null) throw new java.security.GeneralSecurityException("OMEMO2 message is not addressed to this device");
        String flag = selected.getAttributeValue("kex"); boolean preKey = "true".equals(flag) || "1".equals(flag);
        if (flag != null && !preKey && !flag.equals("false") && !flag.equals("0")) throw Omemo2Bundle.invalid();
        byte[] key = decode(selected.getText(),16384); if (preKey) bundle(peer,sender,true);
        List<StandardExtensionElement> payloads = root.getElements("payload",NS);
        if (payloads != null && payloads.size() > 1) throw Omemo2Bundle.invalid();
        StandardExtensionElement payload = payloads == null || payloads.isEmpty() ? null : payloads.get(0);
        byte[] ciphertext = payload == null ? null : decode(payload.getText(),Omemo2Payload.MAX+16);
        try (Omemo2Records tx = device.records.begin()) {
            byte[] material = Omemo2Native.decrypt(tx,device.id,peer,sender,key,preKey); Message content = null, ack = null;
            try {
                if (ciphertext == null) {
                    if (material.length != 32 || !java.security.MessageDigest.isEqual(material,new byte[32])) throw Omemo2Bundle.invalid();
                } else {
                    byte[] plaintext = Omemo2Payload.open(ciphertext,material);
                    try { content = archiveTime == null ? Omemo2Payload.content(outer,plaintext) : Omemo2Payload.content(outer,plaintext,archiveTime.getTime()); }
                    finally { Arrays.fill(plaintext,(byte)0); }
                }
                if (preKey) {
                    byte[][] reply = Omemo2Native.encrypt(tx,device.id,peer,sender,new byte[32]);
                    StandardExtensionElement keys = StandardExtensionElement.builder("keys",NS).addAttribute("jid",peer).addElement(key(sender,reply)).build();
                    ack = new Message(outer.getFrom(),Message.Type.chat); ack.setFrom(connection.getUser());
                    ack.addExtension(StandardExtensionElement.builder("encrypted",NS).addElement(StandardExtensionElement.builder("header",NS)
                            .addAttribute("sid",Integer.toString(device.id)).addElement(keys).build()).build());
                }
                boolean replenish = tx.ids(PRE_KEY).length < 50; tx.commit(); return new Received(content,ack,replenish);
            } finally { Arrays.fill(material,(byte)0); }
        }
    }
    public void replenish() throws Exception { requireReady(); publish(BUNDLES_NODE,Integer.toString(device.id),device.prepare().xml(),"max"); }
    private static byte[] decode(String text, int maximum) throws Exception {
        if (text == null || text.length() > maximum*2) throw Omemo2Bundle.invalid();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(text.replaceAll("[\\t\\n\\r ]","")); }
        catch (IllegalArgumentException error) { throw Omemo2Bundle.invalid(); }
        if (bytes.length == 0 || bytes.length > maximum) throw Omemo2Bundle.invalid(); return bytes;
    }
    @Override public void close() {
        closed = true; ready = false; PepManager.getInstanceFor(connection).removePepListener(listener);
        ServiceDiscoveryManager disco = ServiceDiscoveryManager.getInstanceFor(connection); disco.removeFeature(NS); disco.removeFeature(DEVICES_NODE+"+notify"); disco.removeFeature(BUNDLES_NODE+"+notify");
    }
}
