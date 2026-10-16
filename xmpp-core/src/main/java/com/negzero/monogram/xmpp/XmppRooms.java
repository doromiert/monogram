// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.omemo.*;
import org.jivesoftware.smackx.omemo.element.OmemoElement_VAxolotl;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;

/** Blocking room discovery, membership and messaging without a server helper. */
public final class XmppRooms implements AutoCloseable {
    public static final class Occupant {
        public final String address,nickname,realJid;
        Occupant(EntityFullJid address,org.jivesoftware.smackx.muc.Occupant occupant) {
            this.address=address.toString(); nickname=address.getResourcepart().toString();
            realJid=occupant==null || occupant.getJid()==null ? null : occupant.getJid().asBareJid().toString();
        }
    }
    public static final class State {
        public final EntityBareJid room;
        public final String nickname,subject,title,role,affiliation;
        public final boolean joined;
        public final List<Occupant> occupants;
        State(MultiUserChat chat) { this(chat,null); }
        State(MultiUserChat chat,String title) {
            this.title=title; room=chat.getRoom(); nickname=chat.getNickname()==null ? "" : chat.getNickname().toString();
            subject=chat.getSubject()==null ? "" : chat.getSubject(); joined=chat.isJoined();
            org.jivesoftware.smackx.muc.Occupant own=chat.getNickname()==null ? null : chat.getOccupant(JidCreate.entityFullFrom(chat.getRoom(),chat.getNickname()));
            role=own==null || own.getRole()==null ? "" : own.getRole().toString();
            affiliation=own==null || own.getAffiliation()==null ? "" : own.getAffiliation().toString();
            List<Occupant> found=new ArrayList<>();
            for (EntityFullJid jid:chat.getOccupants()) found.add(new Occupant(jid,chat.getOccupant(jid)));
            occupants=Collections.unmodifiableList(found);
        }
    }
    private final XMPPConnection connection;
    private final OmemoManager omemo;
    private final XmppSession.Listener listener;
    private final Map<EntityBareJid,MultiUserChat> chats=new ConcurrentHashMap<>();
    private final Map<EntityBareJid,String> passwords=new ConcurrentHashMap<>();
    private static final String OCCUPANT_ID="urn:xmpp:occupant-id:0";
    private final Set<EntityBareJid> stableOccupants=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService presenceUpdates=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"Monogram room presence"); thread.setDaemon(true); return thread;
    });
    private final Map<EntityBareJid,java.util.concurrent.ScheduledFuture<?>> pendingPresence=new ConcurrentHashMap<>();
    private volatile boolean closed;
    XmppRooms(XMPPConnection connection,OmemoManager omemo,XmppSession.Listener listener) {
        this.connection=connection; this.omemo=omemo; this.listener=listener;
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("occupant-id",OCCUPANT_ID,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        MultiUserChatManager.getInstanceFor(connection).setAutoJoinOnReconnect(false);
        omemo.addOmemoMucMessageListener((chat,stanza,clear) -> {
            if (!clear.isKeyTransportMessage() && stanza instanceof Message && !stanza.hasExtension("encrypted",XmppOmemo2.NS) && chats.get(chat.getRoom())==chat)
                deliver(chat,(Message)stanza,clear.getBody(),true,true);
        });
    }
    public List<DomainBareJid> services() throws Exception { return MultiUserChatManager.getInstanceFor(connection).getMucServiceDomains(); }
    public Collection<HostedRoom> discover(String service) throws Exception {
        return MultiUserChatManager.getInstanceFor(connection).getRoomsHostedBy(JidCreate.domainBareFrom(service)).values();
    }
    public boolean known(Jid jid) { return jid!=null && chats.containsKey(jid.asEntityBareJidIfPossible()); }
    public State state(String jid) throws Exception { return new State(chat(jid)); }
    private MultiUserChat chat(String jid) throws Exception {
        MultiUserChat chat=chats.get(JidCreate.entityBareFrom(jid));
        if (chat==null) throw new IllegalArgumentException("Unknown room");
        return chat;
    }
    public void remember(String jid) throws Exception { managed(jid); }
    private MultiUserChat managed(String jid) throws Exception {
        EntityBareJid room=JidCreate.entityBareFrom(jid);
        return chats.computeIfAbsent(room,key -> {
            MultiUserChat created=MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(key);
            created.addMessageListener(message -> {
                boolean encrypted=message.hasExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE) || message.hasExtension("encrypted",XmppOmemo2.NS);
                deliver(created,message,encrypted ? null : message.getBody(),encrypted,!encrypted);
            });
            created.addParticipantListener(presence -> queueState(created));
            created.addSubjectUpdatedListener((subject,from) -> queueState(created));
            return created;
        });
    }
    private void queueState(MultiUserChat chat) {
        if(closed) return;
        try {
            pendingPresence.compute(chat.getRoom(),(room,pending) -> pending!=null && !pending.isDone() ? pending
                    : presenceUpdates.schedule(() -> { pendingPresence.remove(room); if(!closed) listener.roomChanged(new State(chat)); },200,java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch(java.util.concurrent.RejectedExecutionException stopped) { if(!closed) throw stopped; }
    }
    public State join(String jid,String nickname,String password) throws Exception {
        MultiUserChat chat=managed(jid);
        RoomInfo info=MultiUserChatManager.getInstanceFor(connection).getRoomInfo(chat.getRoom());
        org.jivesoftware.smackx.disco.packet.DiscoverInfo features=org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(chat.getRoom());
        if(features.containsFeature(OCCUPANT_ID)) stableOccupants.add(chat.getRoom());
        else stableOccupants.remove(chat.getRoom());
        if (!chat.isJoined()) chat.join(chat.getEnterConfigurationBuilder(Resourcepart.from(nickname))
                .withPassword(password).requestMaxStanzasHistory(features.containsFeature("urn:xmpp:mam:2") ? 0 : 50).build());
        if(password!=null) passwords.put(chat.getRoom(),password);
        State state=new State(chat,info.getName()); listener.roomChanged(state); return state;
    }
    private void deliver(MultiUserChat chat,Message wire,String body,boolean encrypted,boolean readable) {
        if (!encrypted && body==null && !wire.hasExtension("reactions",MessageReactions.NAMESPACE)) return;
        XmppRoomMessage message=XmppRoomMessage.from(wire,chat.getRoom(),connection.getUser().asEntityBareJid(),
                chat.getNickname()==null ? "" : chat.getNickname().toString(),body,encrypted,readable);
        if (message!=null) listener.roomMessage(identify(chat,wire,message));
    }
    private XmppRoomMessage identify(MultiUserChat chat,Message wire,XmppRoomMessage message) {
        if(stableOccupants.contains(chat.getRoom())) message=message.withOccupantId(occupantId(wire));
        if(message.realSender!=null) return message;
        EntityBareJid own=connection.getUser().asEntityBareJid();
        if(stableOccupants.contains(chat.getRoom()) && chat.getNickname()!=null) {
            Presence self=chat.getOccupantPresence(JidCreate.entityFullFrom(chat.getRoom(),chat.getNickname()));
            String expected=occupantId(self),received=occupantId(wire);
            if(expected!=null && expected.equals(received)) return message.identified(own.toString(),own);
        }
        // Current presence cannot identify a delayed message sent by a former holder of this nickname.
        if(!message.archived && org.jivesoftware.smackx.delay.DelayInformationManager.getDelayTimestamp(wire)==null) {
            org.jivesoftware.smackx.muc.Occupant occupant=chat.getOccupant(wire.getFrom().asEntityFullJidIfPossible());
            if(occupant!=null && occupant.getJid()!=null) return message.identified(occupant.getJid().asBareJid().toString(),own);
        }
        return message;
    }
    private static String occupantId(Stanza stanza) {
        if(stanza==null) return null;
        List<ExtensionElement> found=stanza.getExtensions("occupant-id",OCCUPANT_ID);
        if(found.size()!=1 || !(found.get(0) instanceof StandardExtensionElement)) return null;
        String id=((StandardExtensionElement)found.get(0)).getAttributeValue("id");
        return id==null || id.isEmpty() || id.length()>128 ? null : id;
    }
    public static final class ArchivePage {
        public final List<XmppRoomMessage> messages;
        public final String firstId,lastId;
        public final boolean complete;
        public ArchivePage(List<XmppRoomMessage> messages,String firstId,String lastId,boolean complete) {
            this.messages=Collections.unmodifiableList(new ArrayList<>(messages)); this.firstId=firstId; this.lastId=lastId; this.complete=complete;
        }
    }
    public boolean hasArchive(String jid) throws Exception {
        return org.jivesoftware.smackx.mam.MamManager.getInstanceFor(chat(jid)).isSupported();
    }
    public ArchivePage archive(String jid,String before,String after,int limit) throws Exception {
        MultiUserChat chat=chat(jid); if(!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        org.jivesoftware.smackx.mam.MamManager.MamQueryArgs.Builder args=org.jivesoftware.smackx.mam.MamManager.MamQueryArgs.builder()
                .setResultPageSizeTo(Math.min(Math.max(limit,1),100));
        if(before!=null) args.beforeUid(before); if(after!=null) args.afterUid(after);
        org.jivesoftware.smackx.mam.MamManager.MamQuery query=org.jivesoftware.smackx.mam.MamManager.getInstanceFor(chat).queryArchive(args.build());
        List<XmppRoomMessage> messages=new ArrayList<>();
        EntityBareJid own=connection.getUser().asEntityBareJid();
        for(org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension result:query.getMamResultExtensions()) {
            org.jivesoftware.smackx.forward.packet.Forwarded<Message> forwarded=result.getForwarded();
            if(forwarded.getDelayInformation()==null || result.getId()==null) throw new java.io.IOException("Room archive result has no timestamp or ID");
            Message wire=forwarded.getForwardedStanza(); Date time=forwarded.getDelayInformation().getStamp();
            boolean modern=wire.hasExtension("encrypted",XmppOmemo2.NS);
            org.jivesoftware.smackx.omemo.element.OmemoElement element=wire.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
            boolean encrypted=modern || element!=null,readable=!encrypted; String body=encrypted ? null : wire.getBody();
            if(XmppRoomMessage.archived(wire,chat.getRoom(),own,"",body,encrypted,readable,result.getId(),time)==null) continue;
            if(element!=null && !modern) {
                if(element.isKeyTransportElement()) continue;
                org.jivesoftware.smackx.muc.packet.MUCUser muc=org.jivesoftware.smackx.muc.packet.MUCUser.from(wire);
                Jid author=muc==null || muc.getItem()==null ? null : muc.getItem().getJid();
                // A historical nickname cannot be resolved through today's occupant list.
                if(author!=null) try {
                    OmemoMessage.Received clear=omemo.decrypt(author.asBareJid(),element);
                    if(clear.isKeyTransportMessage()) continue;
                    body=clear.getBody(); readable=true;
                } catch(org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException
                        | org.jivesoftware.smackx.omemo.exceptions.NoRawSessionException
                        | org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException unavailable) {
                    // Keep an explicit placeholder; the local cache preserves previously decoded content.
                }
            }
            XmppRoomMessage message=XmppRoomMessage.archived(wire,chat.getRoom(),own,"",body,encrypted,readable,result.getId(),time);
            if(message!=null) messages.add(identify(chat,wire,message));
        }
        org.jivesoftware.smackx.rsm.packet.RSMSet rsm=query.getPage().getMamFinIq().getRSMSet();
        return new ArchivePage(messages,rsm==null ? null : rsm.getFirst(),rsm==null ? null : rsm.getLast(),query.isComplete());
    }
    public void leave(String jid) throws Exception {
        MultiUserChat chat=chat(jid); if (chat.isJoined()) chat.leave(); listener.roomChanged(new State(chat));
    }
    public void nickname(String jid,String nickname) throws Exception {
        Resourcepart.from(nickname); MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        String previous=chat.getNickname().toString(),password=passwords.get(chat.getRoom());
        // shortcut: Smack 4.4.8 deadlocks on self-presence 303; use leave/rejoin until the library is upgraded.
        leave(jid);
        try { join(jid,nickname,password); }
        catch(Exception failed) { try { join(jid,previous,password); } catch(Exception restore) { failed.addSuppressed(restore); } throw failed; }
    }
    public Set<BareJid> members(String jid) throws Exception {
        MultiUserChat chat=chat(jid);
        if (!chat.isJoined() || !omemo.multiUserChatSupportsOmemo(chat)) throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        Set<BareJid> members=new HashSet<>();
        // Include offline members; Smack's encrypt(MultiUserChat,...) covers only present occupants.
        for (Affiliate member:chat.getMembers()) members.add(member.getJid().asBareJid());
        for (Affiliate member:chat.getAdmins()) members.add(member.getJid().asBareJid());
        for (Affiliate member:chat.getOwners()) members.add(member.getJid().asBareJid());
        for (EntityFullJid occupant:chat.getOccupants()) {
            org.jivesoftware.smackx.muc.Occupant user=chat.getOccupant(occupant);
            if (user==null || user.getJid()==null || !members.contains(user.getJid().asBareJid()))
                throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        }
        if (!members.contains(connection.getUser().asBareJid())) throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        return members;
    }
    public XmppRoomMessage send(String jid,String body,boolean encrypted) throws Exception {
        if (body==null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        MultiUserChat chat=chat(jid);
        if (!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        Message message;
        if (encrypted) {
            Set<BareJid> recipients=members(jid);
            for (BareJid recipient:recipients) if (!omemo.contactSupportsOmemo(recipient)) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES);
            OmemoMessage.Sent ciphertext=omemo.encrypt(recipients,body);
            if (!ciphertext.getSkippedDevices().isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE);
            for (BareJid recipient:recipients) {
                if (recipient.equals(connection.getUser().asBareJid())) continue;
                boolean included=false;
                for (OmemoDevice device:ciphertext.getIntendedDevices()) if (device.getJid().equals(recipient)) included=true;
                if (!included) throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES);
            }
            if (!members(jid).equals(recipients)) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            message=ciphertext.buildMessage(connection.getStanzaFactory().buildMessageStanza().ofType(Message.Type.groupchat),chat.getRoom());
        } else { message=new Message(chat.getRoom(),Message.Type.groupchat); message.setBody(body); }
        String id=UUID.randomUUID().toString(); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        XmppRoomMessage sent=XmppRoomMessage.sent(chat.getRoom(),connection.getUser().asEntityBareJid(),chat.getNickname().toString(),id,body,encrypted);
        chat.sendMessage(message); return sent;
    }
    /** Explicit plaintext metadata, matching XEP-0444 clients that use legacy OMEMO for text. */
    public XmppRoomMessage sendReactions(String jid,MessageReactions reactions) throws Exception {
        MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        Message message=new Message(chat.getRoom(),Message.Type.groupchat);
        String id=UUID.randomUUID().toString(); message.setStanzaId(id); reactions.addTo(message);
        message.addExtension(new OriginIdElement(id));
        message.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build());
        chat.sendMessage(message); return XmppRoomMessage.sentReactions(chat.getRoom(),connection.getUser().asEntityBareJid(),chat.getNickname().toString(),id,reactions);
    }
    @Override public void close() { closed=true; presenceUpdates.shutdownNow(); pendingPresence.clear(); chats.clear(); passwords.clear(); stableOccupants.clear(); }
}
