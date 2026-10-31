// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.roster.packet.RosterPacket;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.JidCreate;

/** XEP-0369 core:1 and XEP-0405 pam:2. Membership persists on the server across disconnects. */
public final class XmppMix implements AutoCloseable {
    public static final String CORE="urn:xmpp:mix:core:1",PAM="urn:xmpp:mix:pam:2",
            PARTICIPANTS="urn:xmpp:mix:nodes:participants",MESSAGES="urn:xmpp:mix:nodes:messages",INFO="urn:xmpp:mix:nodes:info";
    static final String EVENT="http://jabber.org/protocol/pubsub#event";
    public static final class Participant {
        public final String id,nickname,jid;
        Participant(String id,String nickname,String jid) {this.id=id;this.nickname=nickname;this.jid=jid;}
    }
    public static final class Channel {
        public final EntityBareJid jid;
        public final String participantId,nickname,title,description;
        public final boolean joined;
        public final List<Participant> participants;
        Channel(EntityBareJid jid,String id,String nick,String title,String description,boolean joined,Collection<Participant> participants) {
            this.jid=jid;this.participantId=id;this.nickname=nick;this.title=title;this.description=description;this.joined=joined;
            this.participants=Collections.unmodifiableList(new ArrayList<>(participants));
        }
    }
    private final XMPPConnection connection;
    private final XmppSession.Listener listener;
    private final Map<EntityBareJid,Channel> channels=new ConcurrentHashMap<>();
    private final ExecutorService updates=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Monogram MIX roster");t.setDaemon(true);return t;});
    private final StanzaListener messages=stanza->receive((Message)stanza),roster=stanza->roster((RosterPacket)stanza,false);
    private final StanzaListener rosterReload=stanza->requestAnnotatedRoster();
    private final java.util.concurrent.atomic.AtomicBoolean refreshingRoster=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean rosterAgain;
    private volatile boolean closed;
    public XmppMix(XMPPConnection connection,XmppSession.Listener listener) {
        this.connection=connection;this.listener=listener;MixRoster.initialize();
        for(String name:Arrays.asList("client-join","client-leave"))MixIq.provider(name,PAM);
        for(String name:Arrays.asList("join","leave","create","destroy","setnick"))MixIq.provider(name,CORE);
        for(String name:Arrays.asList("mix","participant"))ProviderManager.addExtensionProvider(name,CORE,StandardExtensionElementProvider.INSTANCE);
        ServiceDiscoveryManager.getInstanceFor(connection).addFeature(CORE);
        connection.addSyncStanzaListener(messages,stanza->stanza instanceof Message && (((Message)stanza).getType()==Message.Type.groupchat || stanza.hasExtension("event",EVENT)));
        connection.addAsyncStanzaListener(roster,stanza->stanza instanceof RosterPacket && ((IQ)stanza).getType()==IQ.Type.set);
        connection.addStanzaSendingListener(rosterReload,stanza->stanza instanceof RosterPacket && ((IQ)stanza).getType()==IQ.Type.get);
    }
    private void requestAnnotatedRoster() {
        if(closed || !connection.isAuthenticated())return;rosterAgain=true;
        if(!refreshingRoster.compareAndSet(false,true))return;
        try {updates.execute(()->{
            try {do {rosterAgain=false;refreshRoster();}while(rosterAgain && !closed);}
            catch(Exception unavailable){/* A disconnect is retried during the normal reconnect setup. */}
            finally {refreshingRoster.set(false);if(rosterAgain && !closed)requestAnnotatedRoster();}
        });}catch(RejectedExecutionException stopped){refreshingRoster.set(false);}
    }
    public static EntityBareJid channel(String value) throws Exception {
        Jid jid=XmppDeveloperTools.target(value);
        if(!jid.isEntityBareJid())throw new IllegalArgumentException("Enter a bare MIX channel address, such as general@mix.example.org.");
        return jid.asEntityBareJidOrThrow();
    }
    static String participantId(String value) {
        if(value==null || value.isEmpty() || value.length()>1023 || value.codePoints().anyMatch(c->c=='#'||c=='/'||c=='@'||Character.isWhitespace(c)||Character.isISOControl(c)))
            throw new IllegalArgumentException("The MIX server returned an invalid participant ID.");
        return value;
    }
    private EntityBareJid own() {if(closed || !connection.isAuthenticated() || connection.getUser()==null)throw new IllegalStateException("Reconnect your XMPP account first.");return connection.getUser().asEntityBareJid();}
    public boolean known(Jid jid) {return jid!=null && channels.containsKey(jid.asEntityBareJidIfPossible());}
    public Channel state(String address) throws Exception {Channel state=channels.get(channel(address));if(state==null)throw new IllegalArgumentException("Unknown MIX channel");return state;}
    public void remember(String address,String id,String nickname) throws Exception {
        EntityBareJid jid=channel(address);if(id!=null && !id.isEmpty())participantId(id);
        channels.putIfAbsent(jid,new Channel(jid,id==null?"":id,nickname==null?"":nickname,null,null,false,Collections.emptyList()));
    }
    public boolean supported() throws Exception {return ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(own(),PAM);}
    public boolean isChannel(String address) throws Exception {own();return ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(channel(address),CORE);}
    private void requirePam() throws Exception {if(!supported())throw new IllegalStateException("Your account server does not support MIX-PAM (XEP-0405). MIX needs support on both the account server and channel server.");}
    public List<DiscoverItems.Item> discover(String service) throws Exception {
        own();Jid target=XmppDeveloperTools.target(service);
        if(!ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(target,CORE))throw new IllegalArgumentException("This service does not advertise MIX core:1.");
        return ServiceDiscoveryManager.getInstanceFor(connection).discoverItems(target).getItems();
    }
    public List<DiscoverItems.Item> services() throws Exception {
        own();ServiceDiscoveryManager disco=ServiceDiscoveryManager.getInstanceFor(connection);List<DiscoverItems.Item> found=new ArrayList<>();
        for(DiscoverItems.Item item:disco.discoverItems(connection.getXMPPServiceDomain()).getItems()) {
            try {if(disco.supportsFeature(item.getEntityID(),CORE))found.add(item);}catch(XMPPException.XMPPErrorException unsupported){/* Unavailable services are not MIX candidates. */}
            if(found.size()>=100)break;
        }
        return found;
    }
    public void refreshRoster() throws Exception {
        own();if(!supported())return;
        IQ answer=connection.createStanzaCollectorAndSend(MixRoster.request()).nextResultOrThrow();
        if(!(answer instanceof RosterPacket))throw new java.io.IOException("Invalid MIX roster response");
        roster((RosterPacket)answer,true);
    }
    private void roster(RosterPacket packet,boolean snapshot) {
        if(closed || connection.getUser()==null || packet.getFrom()!=null && !packet.getFrom().equals(connection.getUser().asBareJid()))return;
        Map<String,String> annotations=MixRoster.channels(packet);Set<EntityBareJid> present=new HashSet<>();
        for(RosterPacket.Item item:packet.getRosterItems())try {
            EntityBareJid jid=item.getJid().asEntityBareJidIfPossible();if(jid==null)continue;
            Channel old=channels.get(jid);String id=annotations.get(jid.toString());
            if(item.getItemType()==RosterPacket.ItemType.remove) {if(old!=null)replace(old,false);continue;}
            if(id==null)continue;
            present.add(jid);
            Channel state=new Channel(jid,id,old==null?"":old.nickname,item.getName()==null?old==null?null:old.title:item.getName(),old==null?null:old.description,true,old==null?Collections.emptyList():old.participants);
            channels.put(jid,state);changed(state);
        }catch(Exception malformed){/* Invalid roster items cannot create channels. */}
        if(snapshot)for(Channel old:new ArrayList<>(channels.values()))if(!present.contains(old.jid))replace(old,false);
    }
    private void replace(Channel old,boolean joined) {Channel state=new Channel(old.jid,old.participantId,old.nickname,old.title,old.description,joined,old.participants);channels.put(old.jid,state);changed(state);}
    private void changed(Channel state) {if(!closed)listener.roomChanged(new XmppRooms.State(state));}
    static StandardExtensionElement joinRequest(String channel,String nickname) {
        StandardExtensionElement.Builder join=StandardExtensionElement.builder("join",CORE);
        for(String node:Arrays.asList(MESSAGES,PARTICIPANTS,INFO))join.addElement(StandardExtensionElement.builder("subscribe",CORE).addAttribute("node",node).build());
        if(nickname!=null && !nickname.isEmpty())join.addElement("nick",nickname);
        return StandardExtensionElement.builder("client-join",PAM).addAttribute("channel",channel).addElement(join.build()).build();
    }
    static String joinedId(StandardExtensionElement join,EntityBareJid channel) throws Exception {
        String id=join.getAttributeValue("id");
        // pam:2 examples used an encoded JID before core:1 clarified the id attribute.
        if(id==null) {String encoded=join.getAttributeValue("jid");if(encoded!=null && encoded.endsWith("#"+channel))id=encoded.substring(0,encoded.length()-channel.length()-1);}
        return participantId(id);
    }
    private StandardExtensionElement command(Jid target,StandardExtensionElement payload) throws Exception {
        own();MixIq request=new MixIq(payload);request.setTo(target);request.setType(IQ.Type.set);
        IQ response=connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
        if(!(response instanceof MixIq) || !payload.getElementName().equals(((MixIq)response).element.getElementName()) || !payload.getNamespace().equals(((MixIq)response).element.getNamespace()))throw new java.io.IOException("Invalid MIX command response");
        return ((MixIq)response).element;
    }
    public Channel join(String address,String nickname) throws Exception {
        EntityBareJid jid=channel(address);requirePam();
        if(!ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(jid,CORE))throw new IllegalArgumentException("This channel does not advertise MIX core:1.");
        if(nickname==null || nickname.length()>1024)throw new IllegalArgumentException("Enter a channel nickname.");
        StandardExtensionElement response=command(own(),joinRequest(jid.toString(),nickname));
        StandardExtensionElement join=response.getFirstElement("join",CORE);if(join==null)throw new java.io.IOException("MIX join response is missing");
        String id=joinedId(join,jid),nick=text(join,"nick",CORE);if(nick==null)nick=nickname;
        Channel state=new Channel(jid,id,nick,null,null,true,Collections.emptyList());channels.put(jid,state);changed(state);
        boolean messages=false;for(StandardExtensionElement subscription:children(join,"subscribe",CORE))if(MESSAGES.equals(subscription.getAttributeValue("node")))messages=true;
        if(!messages)throw new IllegalStateException("The MIX server joined this channel without a message subscription. Its messages node must be enabled before chatting.");
        try {refresh(jid.toString());}catch(Exception unavailable){/* Membership succeeded; metadata can be retried without joining twice. */}
        return channels.get(jid);
    }
    public void leave(String address) throws Exception {
        Channel state=state(address);requirePam();
        command(own(),StandardExtensionElement.builder("client-leave",PAM).addAttribute("channel",state.jid.toString()).addElement(StandardExtensionElement.builder("leave",CORE).build()).build());
        replace(state,false);
    }
    public String create(String address) throws Exception {
        EntityBareJid jid=channel(address);requirePam();Jid service=jid.asDomainBareJid();
        if(!ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(service,CORE+"#create-channel"))throw new IllegalStateException("This MIX service does not allow this account to create channels.");
        StandardExtensionElement result=command(service,StandardExtensionElement.builder("create",CORE).addAttribute("channel",jid.getLocalpart().toString()).build());
        if(!jid.getLocalpart().toString().equals(result.getAttributeValue("channel")))throw new java.io.IOException("MIX created a different channel; check the server before retrying.");
        return jid.toString();
    }
    public void nickname(String address,String nickname) throws Exception {
        Channel old=state(address);StandardExtensionElement result=command(old.jid,StandardExtensionElement.builder("setnick",CORE).addElement("nick",nickname).build());
        String nick=text(result,"nick",CORE);if(nick==null)throw new java.io.IOException("MIX did not confirm a nickname.");
        Channel state=new Channel(old.jid,old.participantId,nick,old.title,old.description,old.joined,old.participants);channels.put(old.jid,state);changed(state);
    }
    static StandardExtensionElement xml(ExtensionElement element) throws Exception {return StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(XmppXml.string(element)));}
    static List<StandardExtensionElement> children(StandardExtensionElement value,String name,String ns) {
        List<StandardExtensionElement> found=value.getElements(name,ns);return found==null?Collections.emptyList():found;
    }
    static String text(StandardExtensionElement parent,String name,String ns) {StandardExtensionElement e=parent.getFirstElement(name,ns);return e==null?null:e.getText()==null?"":e.getText();}
    static Participant participant(String id,StandardExtensionElement value) throws Exception {
        participantId(id);if(!CORE.equals(value.getNamespace()) || !"participant".equals(value.getElementName()))throw new IllegalArgumentException("Invalid MIX participant");
        String jid=text(value,"jid",CORE),nick=text(value,"nick",CORE);if(jid!=null)jid=channel(jid).toString();
        return new Participant(id,nick==null?id:nick,jid);
    }
    public Channel refresh(String address) throws Exception {return refresh(address,true);}
    private Channel refresh(String address,boolean metadata) throws Exception {
        Channel old=state(address);own();
        List<PayloadItem<ExtensionElement>> items=items(connection,old.jid,PARTICIPANTS,10000);
        if(items.size()>10000)throw new java.io.IOException("MIX participant list is too large");
        Map<String,Participant> participants=new LinkedHashMap<>();
        for(PayloadItem<ExtensionElement> item:items) {
            if(item.getPayload()==null)throw new java.io.IOException("MIX participant payload is missing");
            Participant value=participant(item.getId(),xml(item.getPayload()));if(participants.put(value.id,value)!=null)throw new java.io.IOException("Duplicate MIX participant");
        }
        String title=old.title,description=old.description;
        if(metadata)try {
            List<PayloadItem<ExtensionElement>> info=PubSubManager.getInstanceFor(connection,old.jid).getLeafNode(INFO).getItems(1);
            if(!info.isEmpty() && info.get(0).getPayload()!=null) {
                StandardExtensionElement x=xml(info.get(0).getPayload());
                for(StandardExtensionElement field:XmppMix.children(x,"field","jabber:x:data")) {String key=field.getAttributeValue("var"),value=text(field,"value","jabber:x:data");if("Name".equals(key))title=value;else if("Description".equals(key))description=value;}
            }
        }catch(XMPPException.XMPPErrorException optional){/* Information node is optional; participant lookup errors are not ignored. */}
        Channel latest=channels.get(old.jid);if(latest==null || !latest.joined)return latest;
        Participant self=participants.get(latest.participantId);
        Channel state=new Channel(old.jid,latest.participantId,self==null?latest.nickname:self.nickname,title,description,true,participants.values());channels.put(old.jid,state);changed(state);return state;
    }
    public Set<BareJid> members(String address) throws Exception {
        Channel state=refresh(address,false);if(state==null || !state.joined)throw new IllegalStateException("Join the MIX channel first.");
        Set<BareJid> found=new HashSet<>();for(Participant participant:state.participants) {
            if(participant.jid==null)throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED,"Encrypted MIX requires visible addresses for every participant.");
            found.add(channel(participant.jid));
        }
        if(!found.contains(own()))throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
        Set<String> verified=new LinkedHashSet<>();for(BareJid member:found)verified.add(member.toString());listener.roomMembersVerified(state.jid.toString(),Collections.unmodifiableSet(verified));return found;
    }
    /** A paged participant response must never silently encrypt to only the first page. */
    @SuppressWarnings("unchecked")
    static List<PayloadItem<ExtensionElement>> items(XMPPConnection connection,BareJid service,String node,int limit) throws Exception {
        List<PayloadItem<ExtensionElement>> result=new ArrayList<>();Set<String> seen=new HashSet<>();String after=null;
        for(int page=0;page<100;page++) {
            StandardExtensionElement.Builder payload=StandardExtensionElement.builder("pubsub","http://jabber.org/protocol/pubsub")
                    .addElement(StandardExtensionElement.builder("items","http://jabber.org/protocol/pubsub").addAttribute("node",node).build());
            if(after!=null)payload.addElement(StandardExtensionElement.builder("set","http://jabber.org/protocol/rsm").addElement("after",after).addElement("max","1000").build());
            MixIq request=new MixIq(payload.build());request.setTo(service);request.setType(IQ.Type.get);
            IQ received=connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
            if(!(received instanceof org.jivesoftware.smackx.pubsub.packet.PubSub))throw new java.io.IOException("Invalid pubsub item response");
            org.jivesoftware.smackx.pubsub.packet.PubSub pubsub=(org.jivesoftware.smackx.pubsub.packet.PubSub)received;
            ItemsExtension batch=pubsub.getExtension(PubSubElementType.ITEMS);
            if(batch==null || !node.equals(batch.getNode()))throw new java.io.IOException("Pubsub returned another node");
            for(NamedElement element:batch.getItems()) {
                if(!(element instanceof PayloadItem) || ((PayloadItem<?>)element).getId()==null || !seen.add(((PayloadItem<?>)element).getId()))throw new java.io.IOException("Invalid or repeated pubsub item");
                result.add((PayloadItem<ExtensionElement>)element);if(result.size()>limit)throw new java.io.IOException("Pubsub item list is too large");
            }
            org.jivesoftware.smackx.rsm.packet.RSMSet rsm=org.jivesoftware.smackx.rsm.packet.RSMSet.from(pubsub);
            if(rsm==null)return result;
            if(rsm.getCount()>limit || rsm.getFirstIndex()>result.size()-batch.getItems().size())throw new java.io.IOException("Incomplete pubsub item page");
            if(rsm.getCount()>=0 && result.size()>=rsm.getCount() || batch.getItems().isEmpty()) {
                if(rsm.getCount()>result.size())throw new java.io.IOException("Incomplete pubsub item list");return result;
            }
            String next=rsm.getLast();if(next==null || next.equals(after))throw new java.io.IOException("Pubsub pagination did not advance");after=next;
        }
        throw new java.io.IOException("Too many pubsub item pages");
    }
    private boolean addressed(Message wire) {return connection.getUser()!=null && (wire.getTo()==null || connection.getUser().asBareJid().equals(wire.getTo().asBareJid()));}
    private void receive(Message wire) {
        if(closed || wire.getFrom()==null || !known(wire.getFrom()) || !addressed(wire))return;
        Channel state=channels.get(wire.getFrom().asEntityBareJidIfPossible());if(state==null || !state.joined)return;
        try {
            if(wire.getType()==Message.Type.groupchat) {
                boolean encrypted=wire.hasExtension("encrypted",org.jivesoftware.smackx.omemo.element.OmemoElement_VAxolotl.NAMESPACE)||wire.hasExtension("encrypted",XmppOmemo2.NS);
                XmppRoomMessage message=project(wire,null,encrypted,!encrypted,null,null);
                if(message!=null) {if(encrypted)listener.roomMessagePending(message);else listener.roomMessage(message);}
            } else if(wire.getFrom().isEntityBareJid() && wire.hasExtension("event",EVENT)) {
                StandardExtensionElement event=xml(wire.getExtension("event",EVENT));
                for(StandardExtensionElement items:XmppMix.children(event,"items",EVENT)) {
                    String node=items.getAttributeValue("node");if(!PARTICIPANTS.equals(node) && !INFO.equals(node))continue;
                    // Refetch complete authoritative membership rather than accepting partial event lists for encryption.
                    if(pending.add(state.jid))updates.execute(()->{try{refresh(state.jid.toString());}catch(Exception unavailable){listener.roomMetadataChanged(state.jid.toString());}finally{pending.remove(state.jid);}});
                }
            }
        }catch(Exception malformed){/* Invalid sender metadata cannot become a direct chat or verified room author. */}
    }
    private final Set<EntityBareJid> pending=ConcurrentHashMap.newKeySet();
    Message normalized(Message wire) throws Exception {
        if(wire.getFrom()==null || !known(wire.getFrom()) || wire.getType()!=Message.Type.groupchat)return null;
        List<ExtensionElement> metadata=wire.getExtensions("mix",CORE);if(metadata.size()!=1)return null;
        StandardExtensionElement mix=xml(metadata.get(0));String real=text(mix,"jid",CORE);if(real!=null)real=channel(real).toString();
        if(XmppMix.children(mix,"jid",CORE).size()>1 || XmppMix.children(mix,"nick",CORE).size()>1)return null;
        Message copy=new Message(wire);
        if(!wire.getFrom().hasResource()) {
            // Some MIX archives store the reflected message without the stable-ID resource (XEP-0369 example 28).
            if(real==null)return null;String id;
            {byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(real.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("jid-");for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));id=out.toString();}
            copy.setFrom(JidCreate.entityFullFrom(wire.getFrom().asBareJid()+"/"+id));
        }else participantId(wire.getFrom().getResourceOrNull().toString());
        return copy;
    }
    XmppRoomMessage project(Message wire,String body,boolean encrypted,boolean readable,String archiveId,Date time) throws Exception {
        Message copy=normalized(wire);if(copy==null)return null;Channel channel=state(copy.getFrom().asBareJid().toString());
        if(!channel.joined)return null;
        XmppRoomMessage message=XmppRoomMessage.archived(copy,channel.jid,own(),"",readable && body==null?wire.getBody():body,encrypted,readable,archiveId,time);
        if(message==null)return null;String real=text(xml(wire.getExtension("mix",CORE)),"jid",CORE);
        if(real!=null)real=channel(real).toString();
        String id=copy.getFrom().getResourceOrNull().toString();boolean outgoing=real!=null?own().toString().equals(real):channel.participantId.equals(id);
        Map<String,String> ids=new LinkedHashMap<>(message.ids);
        // A MIX service replaces stanza id with its archive id, unlike a MUC service (XEP-0369 §7.1.6).
        if(wire.getStanzaId()!=null)ids.put("archive",wire.getStanzaId());
        String origin=ids.get("origin");if(real!=null && origin!=null)ids.put("mix-origin",real+"\n"+origin);
        return message.canonical(ids,message.timestamp,message.archived,outgoing,real,real!=null,wire.getFrom().hasResource()?id:null);
    }
    LegacyIncomingJournal.Context legacyContext(Message wire,boolean live,String archiveId,Date time) throws Exception {
        XmppRoomMessage snapshot=project(wire,null,true,false,archiveId,time);if(snapshot==null || snapshot.realSender==null)return null;
        return LegacyIncomingJournal.Context.room(normalized(wire),snapshot.realSender,"",snapshot,live);
    }
    @Override public void close() {closed=true;connection.removeSyncStanzaListener(messages);connection.removeAsyncStanzaListener(roster);connection.removeStanzaSendingListener(rosterReload);updates.shutdownNow();channels.clear();pending.clear();}
}
