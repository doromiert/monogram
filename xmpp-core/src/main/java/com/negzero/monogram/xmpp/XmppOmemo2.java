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
    private final Omemo2IncomingJournal incoming;
    private final List<Omemo2ReceiveOnly> recovery;
    private final SecureRandom random = new SecureRandom();
    // Use Map's keySet signature: Android desugaring lacks ConcurrentHashMap's covariant overload.
    private final Map<String,Omemo2Bundle> bundles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Set<Integer>> lists = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> refreshed=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> watchAttempts=new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService watches=java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread thread=new Thread(r,"Monogram PEP subscriptions"); thread.setDaemon(true); return thread; });
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    private final Consumer<String> changed;
    private final PepListener listener;
    private volatile boolean ready, closed;
    private volatile Exception lastError;
    public XmppOmemo2(XMPPConnection connection, Omemo2Device device, Consumer<String> changed) throws Exception {
        this(connection,device,changed,java.util.Collections.<Omemo2ReceiveOnly>emptyList());
    }
    /** Explicit migration only. Recovery never supplies outgoing sessions or publishes bundles. */
    public XmppOmemo2(XMPPConnection connection, Omemo2Device device, Consumer<String> changed, Omemo2ReceiveOnly recovery) throws Exception {
        this(connection,device,changed,recovery==null?java.util.Collections.<Omemo2ReceiveOnly>emptyList():java.util.Collections.singletonList(recovery));
    }
    public static XmppOmemo2 restored(XMPPConnection connection,Omemo2Device device,Consumer<String> changed,List<Omemo2ReceiveOnly> recovery) throws Exception {
        return new XmppOmemo2(connection,device,changed,recovery);
    }
    private XmppOmemo2(XMPPConnection connection,Omemo2Device device,Consumer<String> changed,List<Omemo2ReceiveOnly> recovery) throws Exception {
        for(Omemo2ReceiveOnly archive:recovery) archive.requireActive(device);
        this.connection=connection;this.device=device;this.changed=changed;this.recovery=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(recovery));
        incoming=new Omemo2IncomingJournal(device);
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
                    if (from.toString().equals(device.jid)) announceOwnDevice();
                    else fetchDevices(from.toString());
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
        ready = false; lastError = null; bundles.clear(); lists.clear(); watched.clear(); refreshed.clear(); watchAttempts.clear();
        // A failed own-list read must abort before publishing anything to the account.
        fetchDevices(device.jid);
        Omemo2Bundle own = device.prepare();
        publish(BUNDLES_NODE,Integer.toString(device.id),own.xml(),"max");
        announceOwnDevice();
        ensureOpenAccess(DEVICES_NODE);ensureOpenAccess(BUNDLES_NODE);
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
    /** UI discovery can retain good fingerprints; encryption still requires every non-blocked target. */
    public synchronized Map<Integer,Omemo2Bundle> discoverFingerprints(String peer,Consumer<Map<Integer,Omemo2Bundle>> progress)throws Exception {
        peer=bare(peer);Map<Integer,Omemo2Bundle> found=new LinkedHashMap<>();List<String> failures=new java.util.ArrayList<>();
        for(int id:new java.util.TreeSet<>(fetchDevices(peer)))if(!peer.equals(device.jid) || id!=device.id) {
            try {found.put(id,bundle(peer,id,true));progress.accept(new LinkedHashMap<>(found));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
            catch(Exception failure){failures.add("Device "+id+": "+XmppRequestException.describe(failure));}
        }
        if(!failures.isEmpty()) {
            Map<org.jivesoftware.smackx.omemo.internal.OmemoDevice,org.jivesoftware.smackx.omemo.trust.OmemoFingerprint> visible=new LinkedHashMap<>();
            for(Map.Entry<Integer,Omemo2Bundle> entry:found.entrySet())visible.put(new org.jivesoftware.smackx.omemo.internal.OmemoDevice(JidCreate.entityBareFrom(peer),entry.getKey()),new org.jivesoftware.smackx.omemo.trust.OmemoFingerprint(XmppSession.hex(entry.getValue().identity())));
            throw new XmppFingerprints.Partial(visible,String.join("; ",failures));
        }
        return found;
    }
    private synchronized Map<Integer,Omemo2Bundle> targets(String peer,boolean optionalOwnCopy) throws Exception {
        Set<Integer> ids = fresh(peer+"\n"+DEVICES_NODE) ? lists.get(peer) : null;
        if (ids == null) ids = fetchDevices(peer);
        Map<Integer,Omemo2Bundle> result = new LinkedHashMap<>();
        for (int id : new java.util.TreeSet<>(ids)) if (!peer.equals(device.jid) || id != device.id) {
            try {
                Omemo2Bundle found=bundle(peer,id,false);
                if(device.trust(peer,id,found.identity())!=Omemo2Device.Trust.REJECTED)result.put(id,found);
            }
            catch(java.security.GeneralSecurityException invalidBundle) {
                if(!optionalOwnCopy || !peer.equals(device.jid)) throw invalidBundle;
                lastError=invalidBundle;
            }
        }
        if(!optionalOwnCopy && !ids.isEmpty() && result.isEmpty())throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES);
        return result;
    }
    private String bare(String jid) throws Exception { if (jid == null || jid.indexOf('/') >= 0) throw new IllegalArgumentException("Bare peer required"); return JidCreate.entityBareFrom(jid).toString(); }
    private synchronized Set<Integer> fetchDevices(String peer) throws Exception {
        Set<Integer> ids = Omemo2DeviceList.ids(item(peer,DEVICES_NODE,null));
        device.devices(peer,ids); lists.put(peer,new HashSet<>(ids)); refreshed.put(peer+"\n"+DEVICES_NODE,System.nanoTime()); return ids;
    }
    private synchronized Omemo2Bundle bundle(String peer, int id, boolean refresh) throws Exception {
        String key = peer+"\n"+id;
        Omemo2Bundle known = refresh || !fresh(peer+"\n"+BUNDLES_NODE+"\n"+id) ? null : bundles.get(key);
        if (known != null) return known;
        StandardExtensionElement root = item(peer,BUNDLES_NODE,Integer.toString(id));
        if (root == null) throw new java.security.GeneralSecurityException("OMEMO2 bundle unavailable");
        Omemo2Bundle found = Omemo2Bundle.parse(root); device.observe(peer,id,found); bundles.put(key,found); refreshed.put(peer+"\n"+BUNDLES_NODE+"\n"+id,System.nanoTime()); changed.accept(peer); return found;
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
            if(condition==StanzaError.Condition.forbidden || condition==StanzaError.Condition.not_authorized
                    || absent.getStanzaError().getExtension("presence-subscription-required",PUBSUB+"#errors")!=null) {
                OmemoSendException denied=new OmemoSendException(OmemoSendException.Reason.CONTACT_AUTHORIZATION_REQUIRED,peer);denied.initCause(absent);throw denied;
            }
            // Unsupported/unavailable is not evidence that our existing account list is empty.
            boolean ownList=device.jid.equals(peer) && DEVICES_NODE.equals(node);
            if (condition == StanzaError.Condition.item_not_found || !ownList && (condition == StanzaError.Condition.feature_not_implemented || condition == StanzaError.Condition.service_unavailable)) return null;
            throw absent;
        }
    }
    private boolean fresh(String key) {
        String node=key.contains("\n"+BUNDLES_NODE) ? key.substring(0,key.lastIndexOf('\n')) : key;
        Long time=refreshed.get(key);
        return watched.contains(node) || time!=null && System.nanoTime()-time<java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
    }
    /** Subscription support must not add extra round trips to each send. */
    private void watch(String peer,String node) {
        String key=peer+"\n"+node; if(closed || watched.contains(key)) return;
        long now=System.nanoTime(); Long last=watchAttempts.get(key);
        if(last!=null && now-last<java.util.concurrent.TimeUnit.MINUTES.toNanos(1)) return;
        watchAttempts.put(key,now);
        try { watches.execute(() -> {
            if(closed) return;
            try {
                Subscription subscription=PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(peer)).getLeafNode(node).subscribe(connection.getUser().asEntityBareJid());
                if(subscription.getState()==Subscription.State.subscribed) watched.add(key);
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(Exception unavailable) { lastError=unavailable; }
        }); } catch(java.util.concurrent.RejectedExecutionException stopped) { /* Session closed. */ }
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
        if (!DEVICES_NODE.equals(node) || !"current".equals(id)) {
            if (!BUNDLES_NODE.equals(node) || !Integer.toString(device.id).equals(id)) throw new IllegalArgumentException("Cannot publish another device's OMEMO data");
        }
        if (!connection.isAuthenticated() || !connection.getUser().asBareJid().toString().equals(device.jid)) throw new IllegalStateException("OMEMO2 account changed");
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
    /** Publish options do not reliably repair an already-created restricted PEP node. */
    private void ensureOpenAccess(String node)throws Exception {
        if(!DEVICES_NODE.equals(node) && !BUNDLES_NODE.equals(node))throw new IllegalArgumentException("Not an OMEMO2 node");
        try {
            Node published=PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(device.jid)).getNode(node);
            if(published.getNodeConfiguration().getAccessModel()==AccessModel.open)return;
            String owner=PUBSUB+"#owner";
            // Submit only this field: retain unrelated node configuration and all device/bundle items.
            StandardExtensionElement form=StandardExtensionElement.builder("x",FORMS).addAttribute("type","submit")
                .addElement(StandardExtensionElement.builder("field",FORMS).addAttribute("var","FORM_TYPE").addAttribute("type","hidden").addElement("value",PUBSUB+"#node_config").build())
                .addElement(StandardExtensionElement.builder("field",FORMS).addAttribute("var","pubsub#access_model").addElement("value","open").build()).build();
            iq(owner,StandardExtensionElement.builder("configure",owner).addAttribute("node",node).addElement(form).build());
            if(published.getNodeConfiguration().getAccessModel()!=AccessModel.open)throw new java.security.GeneralSecurityException("OMEMO2 key access remains restricted");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        catch(Exception failure){throw new XmppRequestException("Could not enable public OMEMO2 key access",failure);}
    }
    private void announceOwnDevice() throws Exception {
        StandardExtensionElement verified=Omemo2DeviceList.announce(new Omemo2DeviceList.Store(){
            public StandardExtensionElement read()throws Exception{return item(device.jid,DEVICES_NODE,null);}
            public void publish(StandardExtensionElement value)throws Exception{XmppOmemo2.this.publish(DEVICES_NODE,"current",value,"1");}
        },device.id);
        Set<Integer> ids=Omemo2DeviceList.ids(verified);
        device.devices(device.jid,ids);lists.put(device.jid,new HashSet<>(ids));refreshed.put(device.jid+"\n"+DEVICES_NODE,System.nanoTime());
    }
    private void requireReady() { if (!ready()) throw new IllegalStateException("OMEMO2 is not initialized"); }
    public Message encrypt(Message plaintext) throws Exception {
        requireReady(); Omemo2Payload.direct(plaintext);
        if (!plaintext.getFrom().asBareJid().toString().equals(device.jid)) throw Omemo2Bundle.invalid();
        String peer = plaintext.getTo().asBareJid().toString();
        Map<String,Map<Integer,Omemo2Bundle>> recipients = new LinkedHashMap<>();
        recipients.put(peer,targets(peer,false)); if (!peer.equals(device.jid)) recipients.put(device.jid,targets(device.jid,true));
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
    public String ownFingerprint() throws Exception {
        try(Omemo2Records tx=device.records.begin()) {
            byte[] identity=tx.load(IDENTITY,"",1);
            if(identity==null) throw new IllegalStateException("OMEMO2 identity is unavailable");
            return XmppSession.hex(identity);
        }
    }
    public static final class Received {
        public final Message content, acknowledgement;
        public final XmppMessage message;
        public final Omemo2IncomingJournal.Token token;
        public final boolean replenish;
        public final String senderFingerprint;
        final Omemo2IncomingJournal journal;
        Received(Message content, Message acknowledgement, boolean replenish,String senderFingerprint,XmppMessage message,Omemo2IncomingJournal.Token token) {
            this(content,acknowledgement,replenish,senderFingerprint,message,token,null);
        }
        Received(Message content, Message acknowledgement, boolean replenish,String senderFingerprint,XmppMessage message,Omemo2IncomingJournal.Token token,Omemo2IncomingJournal journal) {
            this.journal=journal;
            this.content = content; this.acknowledgement = acknowledgement; this.replenish = replenish; this.senderFingerprint=senderFingerprint;this.message=message;this.token=token;
        }
    }
    public List<Received> pendingIncoming() throws Exception {
        List<Received> pending=new java.util.ArrayList<>(incoming.pending());
        for(Omemo2ReceiveOnly archive:recovery) pending.addAll(archive.journal.pending());
        return java.util.Collections.unmodifiableList(pending);
    }
    private Omemo2IncomingJournal journal(Received received) {
        if(received.journal==incoming) return incoming;
        for(Omemo2ReceiveOnly archive:recovery) if(received.journal==archive.journal) return archive.journal;
        if(received.token==null) return incoming;
        throw new IllegalArgumentException("Incoming frame belongs to a different journal");
    }
    public boolean acceptIncoming(Received received) throws Exception { return received==null || journal(received).accept(received.token); }
    public void acknowledgeIncoming(Received received) throws Exception { if(received!=null) journal(received).acknowledge(received.token); }
    /** Persist content before sending its optional acknowledgement or refreshing public bundles. */
    public Received receive(Message outer) throws Exception { return receive(outer,null,null); }
    public Received receive(Message outer, java.util.Date archiveTime) throws Exception { return receive(outer,archiveTime,null); }
    public Received receive(Message outer, java.util.Date archiveTime,String archiveId) throws Exception {
        requireReady(); Omemo2Payload.direct(outer);
        if (!outer.getFrom().asBareJid().toString().equals(device.jid) && !outer.getTo().asBareJid().toString().equals(device.jid)) throw Omemo2Bundle.invalid();
        Omemo2IncomingJournal.Envelope envelope=Omemo2IncomingJournal.envelope(outer);
        List<ExtensionElement> encrypted = outer.getExtensions("encrypted",NS);
        if (encrypted.size() != 1 || !(encrypted.get(0) instanceof StandardExtensionElement)) throw Omemo2Bundle.invalid();
        StandardExtensionElement root = (StandardExtensionElement) encrypted.get(0);
        if (root.toXML().length() > 1024*1024) throw Omemo2Bundle.invalid();
        StandardExtensionElement header = Omemo2Bundle.single(root,"header",NS); int sender = Omemo2Bundle.positive(header.getAttributeValue("sid"));
        String peer = outer.getFrom().asBareJid().toString();
        Map<Integer,StandardExtensionElement> ownKeys=new LinkedHashMap<>();
        StandardExtensionElement selected = null; Set<String> groups = new HashSet<>(); int count = 0;
        for (StandardExtensionElement group : header.getElements()) {
            if (!"keys".equals(group.getElementName()) || !NS.equals(group.getNamespace())) throw Omemo2Bundle.invalid();
            String jid = group.getAttributeValue("jid"); if (!bare(jid).equals(jid) || !groups.add(jid)) throw Omemo2Bundle.invalid(); Set<Integer> ids = new HashSet<>();
            for (StandardExtensionElement key : group.getElements()) {
                if (!"key".equals(key.getElementName()) || !NS.equals(key.getNamespace()) || ++count > 1000) throw Omemo2Bundle.invalid();
                int id = Omemo2Bundle.positive(key.getAttributeValue("rid")); if (!ids.add(id)) throw Omemo2Bundle.invalid();
                if(jid.equals(device.jid)) ownKeys.put(id,key);
                if (jid.equals(device.jid) && id == device.id) selected = key;
            }
        }
        if(!recovery.isEmpty()) return receiveRecovered(outer,archiveTime,archiveId,envelope,peer,sender,ownKeys);
        if (selected == null) throw new java.security.GeneralSecurityException("OMEMO2 message is not addressed to this device");
        String flag = selected.getAttributeValue("kex"); boolean preKey = "true".equals(flag) || "1".equals(flag);
        if (flag != null && !preKey && !flag.equals("false") && !flag.equals("0")) throw Omemo2Bundle.invalid();
        byte[] key = decode(selected.getText(),16384);
        try {
            try(Omemo2Records tx=device.records.begin()) {
                Received cached=incoming.find(tx,envelope.digest,archiveTime,archiveId);if(cached!=null) return cached;
            }
            if (preKey) bundle(peer,sender,true);
            return decryptIncoming(device,incoming,false,outer,archiveTime,archiveId,envelope,peer,sender,key,preKey);
        } finally { Arrays.fill(key,(byte)0); }
    }
    private Received receiveRecovered(Message outer,java.util.Date archiveTime,String archiveId,
            Omemo2IncomingJournal.Envelope envelope,String peer,int sender,Map<Integer,StandardExtensionElement> ownKeys) throws Exception {
        StandardExtensionElement activeKey=ownKeys.get(device.id);
        boolean archiveFirst=activeKey==null || prekey(activeKey);
        List<Omemo2Device> devices=new java.util.ArrayList<>();
        List<Omemo2IncomingJournal> journals=new java.util.ArrayList<>();
        for(Omemo2ReceiveOnly archive:recovery) { devices.add(archive.device);journals.add(archive.journal); }
        int activeAt=archiveFirst?devices.size():0;devices.add(activeAt,device);journals.add(activeAt,incoming);
        Omemo2Native.Failure last=null;
        for(int index=0;index<devices.size();index++) {
            Omemo2Device source=devices.get(index);StandardExtensionElement selected=ownKeys.get(source.id);if(selected==null) continue;
            Omemo2IncomingJournal journal=journals.get(index);
            try(Omemo2Records tx=source.records.begin()) {
                Received cached=journal.find(tx,envelope.digest,archiveTime,archiveId);if(cached!=null) return cached;
            }
            boolean preKey=prekey(selected);byte[] key=decode(selected.getText(),16384);
            try {
                if(source==device && preKey) bundle(peer,sender,true);
                return decryptIncoming(source,journal,source!=device,outer,archiveTime,archiveId,envelope,peer,sender,key,preKey);
            } catch(Omemo2Native.Failure mismatch) {
                if(!Omemo2ReceiveOnly.recoverable(mismatch.code)) throw mismatch;last=mismatch;
            } finally { Arrays.fill(key,(byte)0); }
        }
        if(last!=null) throw last;
        throw new java.security.GeneralSecurityException("OMEMO2 message is not addressed to an active or recovery device");
    }
    private static boolean prekey(StandardExtensionElement selected) throws Exception {
        String flag=selected.getAttributeValue("kex");boolean prekey="true".equals(flag) || "1".equals(flag);
        if(flag!=null && !prekey && !flag.equals("false") && !flag.equals("0")) throw Omemo2Bundle.invalid();return prekey;
    }
    private Received decryptIncoming(Omemo2Device source,Omemo2IncomingJournal journal,boolean receiveOnly,
            Message outer,java.util.Date archiveTime,String archiveId,Omemo2IncomingJournal.Envelope envelope,
            String peer,int sender,byte[] key,boolean preKey) throws Exception {
        byte[] ciphertext=envelope.ciphertext;
        try (Omemo2Records tx = source.records.begin()) {
            Received cached=journal.find(tx,envelope.digest,archiveTime,archiveId);if(cached!=null) return cached;
            byte[] material = Omemo2Native.decrypt(tx,source.id,peer,sender,key,preKey); Message content = null, ack = null;
            try {
                if (ciphertext == null) {
                    if (material.length != 32 || !java.security.MessageDigest.isEqual(material,new byte[32])) throw Omemo2Bundle.invalid();
                } else {
                    byte[] plaintext = Omemo2Payload.open(ciphertext,material);
                    try { content = archiveTime == null ? Omemo2Payload.content(outer,plaintext) : Omemo2Payload.content(outer,plaintext,archiveTime.getTime()); }
                    finally { Arrays.fill(plaintext,(byte)0); }
                }
                if (preKey && !receiveOnly) {
                    byte[][] reply = Omemo2Native.encrypt(tx,source.id,peer,sender,new byte[32]);
                    StandardExtensionElement keys = StandardExtensionElement.builder("keys",NS).addAttribute("jid",peer).addElement(key(sender,reply)).build();
                    ack = new Message(outer.getFrom(),Message.Type.chat); ack.setFrom(JidCreate.entityBareFrom(source.jid));
                    ack.addExtension(StandardExtensionElement.builder("encrypted",NS).addElement(StandardExtensionElement.builder("header",NS)
                            .addAttribute("sid",Integer.toString(source.id)).addElement(keys).build()).build());
                }
                byte[] senderIdentity=tx.load(SESSION_IDENTITY,peer,sender);
                String fingerprint=senderIdentity==null ? null : XmppSession.hex(senderIdentity);
                boolean replenish = !receiveOnly && tx.ids(PRE_KEY).length < 50;
                XmppMessage model=content==null ? null : XmppMessage.modern(outer,content,JidCreate.entityBareFrom(source.jid),archiveId,archiveTime);
                if(model!=null) model=model.withSenderFingerprint(fingerprint);
                Received received;
                if(content!=null || ack!=null) received=journal.append(tx,envelope.digest,outer,content,ack,model,fingerprint,replenish);
                else received=new Received(null,null,replenish,fingerprint,null,null);
                tx.commit();return received;
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
        closed = true; ready = false; watches.shutdownNow(); PepManager.getInstanceFor(connection).removePepListener(listener);
        ServiceDiscoveryManager disco = ServiceDiscoveryManager.getInstanceFor(connection); disco.removeFeature(NS); disco.removeFeature(DEVICES_NODE+"+notify"); disco.removeFeature(BUNDLES_NODE+"+notify");
    }
}
