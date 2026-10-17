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
        public final String address,nickname,realJid,role,affiliation;
        private final Presence presence;
        Occupant(EntityFullJid address,org.jivesoftware.smackx.muc.Occupant occupant,Presence presence) {
            this.presence=presence;
            this.address=address.toString(); nickname=address.getResourcepart().toString();
            realJid=occupant==null || occupant.getJid()==null ? null : occupant.getJid().asBareJid().toString();
            role=occupant==null || occupant.getRole()==null ? "" : occupant.getRole().toString();
            affiliation=occupant==null || occupant.getAffiliation()==null ? "" : occupant.getAffiliation().toString();
        }
    }
    public static final class State {
        public final EntityBareJid room;
        public final String nickname,subject,title,description,role,affiliation;
        public final boolean joined;
        public final List<Occupant> occupants;
        State(MultiUserChat chat) { this(chat,null,null); }
        State(MultiUserChat chat,String title,String description) {
            this.description=description; this.title=title; room=chat.getRoom(); nickname=chat.getNickname()==null ? "" : chat.getNickname().toString();
            subject=chat.getSubject()==null ? "" : chat.getSubject(); joined=chat.isJoined();
            org.jivesoftware.smackx.muc.Occupant own=chat.getNickname()==null ? null : chat.getOccupant(JidCreate.entityFullFrom(chat.getRoom(),chat.getNickname()));
            role=own==null || own.getRole()==null ? "" : own.getRole().toString();
            affiliation=own==null || own.getAffiliation()==null ? "" : own.getAffiliation().toString();
            List<Occupant> found=new ArrayList<>();
            for (EntityFullJid jid:chat.getOccupants()) found.add(new Occupant(jid,chat.getOccupant(jid),chat.getOccupantPresence(jid)));
            occupants=Collections.unmodifiableList(found);
        }
    }
    private final XMPPConnection connection;
    private final OmemoManager omemo;
    XmppChatStates chatStates;
    private final XmppSession.Listener listener;
    private final Map<EntityBareJid,MultiUserChat> chats=new ConcurrentHashMap<>();
    private final Map<EntityBareJid,String> passwords=new ConcurrentHashMap<>();
    private static final String OCCUPANT_ID="urn:xmpp:occupant-id:0";
    private final Set<EntityBareJid> stableOccupants=ConcurrentHashMap.newKeySet();
    private final Map<EntityBareJid,Boolean> legacyRetractions=new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService presenceUpdates=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"Monogram room presence"); thread.setDaemon(true); return thread;
    });
    private final Map<EntityBareJid,java.util.concurrent.ScheduledFuture<?>> pendingPresence=new ConcurrentHashMap<>();
    private volatile boolean closed;
    interface DeviceLookup { Set<OmemoDevice> devices(BareJid contact) throws Exception; }
    private final DeviceLookup deviceLookup;
    XmppRooms(XMPPConnection connection,OmemoManager omemo,XmppSession.Listener listener,DeviceLookup deviceLookup) {
        this.connection=connection; this.omemo=omemo; this.listener=listener; this.deviceLookup=deviceLookup;
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("occupant-id",OCCUPANT_ID,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        MultiUserChatManager manager=MultiUserChatManager.getInstanceFor(connection);
        manager.setAutoJoinOnReconnect(false);
        manager.addInvitationListener((conn,room,inviter,reason,password,message,invitation) -> {
            if(inviter==null || staleInvitation(message)) return;
            invitation(room.getRoom(),inviter.toString(),reason,password);
        });
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("x","jabber:x:conference",
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature("jabber:x:conference");
        connection.addSyncStanzaListener(stanza -> {
            Message message=(Message)stanza;
            if(closed || connection.getUser()==null || message.getFrom()==null || !message.getFrom().isEntityJid()
                    || message.getFrom().asBareJid().equals(connection.getUser().asBareJid())
                    || message.getType()!=Message.Type.normal && message.getType()!=Message.Type.chat || staleInvitation(message)
                    || message.getTo()!=null && !message.getTo().asBareJid().equals(connection.getUser().asBareJid())) return;
            List<ExtensionElement> extensions=message.getExtensions("x","jabber:x:conference");
            if(extensions.size()!=1 || !(extensions.get(0) instanceof StandardExtensionElement)) return;
            StandardExtensionElement invite=(StandardExtensionElement)extensions.get(0);
            try {
                Jid room=JidCreate.from(invite.getAttributeValue("jid"));
                if(room.isEntityBareJid()) invitation(room.asEntityBareJidOrThrow(),message.getFrom().toString(),invite.getAttributeValue("reason"),invite.getAttributeValue("password"));
            } catch(Exception invalid) { /* An invalid invitation cannot create or join a room. */ }
        },stanza -> stanza instanceof Message && stanza.hasExtension("x","jabber:x:conference"));
        omemo.addOmemoMucMessageListener((chat,stanza,clear) -> {
            if (!clear.isKeyTransportMessage() && stanza instanceof Message && !stanza.hasExtension("encrypted",XmppOmemo2.NS) && chats.get(chat.getRoom())==chat)
                deliver(chat,(Message)stanza,clear.getBody(),true,true,clear.getSendersFingerprint().toString());
        });
    }
    private static boolean staleInvitation(Message message) {
        return message.hasExtension("urn:xmpp:delay") || message.hasExtension("jabber:x:delay") || message.hasExtension("urn:xmpp:forward:0")
                || message.hasExtension("urn:xmpp:carbons:2") || message.hasExtension("urn:xmpp:mam:2") || message.hasExtension("urn:xmpp:mam:1");
    }
    private void invitation(EntityBareJid room,String inviter,String reason,String password) {
        MultiUserChat current=chats.get(room);
        if(!closed && (current==null || !current.isJoined())) listener.roomInvitation(room.toString(),inviter,reason,password);
    }
    public List<DomainBareJid> services() throws Exception { return MultiUserChatManager.getInstanceFor(connection).getMucServiceDomains(); }
    public Collection<HostedRoom> discover(String service) throws Exception {
        return MultiUserChatManager.getInstanceFor(connection).getRoomsHostedBy(JidCreate.domainBareFrom(service)).values();
    }
    public boolean known(Jid jid) { return jid!=null && chats.containsKey(jid.asEntityBareJidIfPossible()); }
    boolean canSendChatState(EntityBareJid room) {
        MultiUserChat chat=chats.get(room);
        if(chat==null || !chat.isJoined() || chat.getNickname()==null) return false;
        org.jivesoftware.smackx.muc.Occupant own=chat.getOccupant(JidCreate.entityFullFrom(room,chat.getNickname()));
        return own!=null && own.getRole()!=null && own.getRole()!=MUCRole.visitor && own.getRole()!=MUCRole.none;
    }
    boolean acceptsChatState(EntityFullJid from) {
        MultiUserChat chat=chats.get(from.asEntityBareJid());
        return chat!=null && chat.isJoined() && !from.getResourcepart().equals(chat.getNickname()) && chat.getOccupant(from)!=null;
    }
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
                if (closed || connection.getUser()==null) return;
                boolean encrypted=message.hasExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE) || message.hasExtension("encrypted",XmppOmemo2.NS);
                if (MessageReactions.publicLegacy(message)) {
                    deliver(created,message,null,false,true);
                } else if (encrypted && !message.hasExtension("encrypted",XmppOmemo2.NS)) {
                    org.jivesoftware.smackx.omemo.element.OmemoElement element=message.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
                    if (element!=null && element.isKeyTransportElement()) return;
                    XmppRoomMessage pending=XmppRoomMessage.from(message,created.getRoom(),connection.getUser().asEntityBareJid(),
                            created.getNickname()==null ? "" : created.getNickname().toString(),null,true,false);
                    if (pending!=null) listener.roomMessagePending(identify(created,message,pending));
                } else deliver(created,message,encrypted ? null : message.getBody(),encrypted,!encrypted);
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
        legacyRetractions.put(chat.getRoom(),!features.containsFeature(MessageRetraction.NAMESPACE) && features.containsFeature(MessageRetraction.LEGACY));
        if(features.containsFeature(OCCUPANT_ID)) stableOccupants.add(chat.getRoom());
        else stableOccupants.remove(chat.getRoom());
        if (!chat.isJoined()) chat.join(chat.getEnterConfigurationBuilder(Resourcepart.from(nickname))
                .withPassword(password).requestMaxStanzasHistory(features.containsFeature("urn:xmpp:mam:2") ? 0 : 50).build());
        if(password!=null) passwords.put(chat.getRoom(),password);
        State state=new State(chat,info.getName(),info.getDescription()); listener.roomChanged(state); return state;
    }
    public State createPrivate(String nickname,String title) throws Exception {
        if(title==null || title.trim().isEmpty()) throw new IllegalArgumentException("Enter a group name.");
        List<DomainBareJid> services=services();
        if(services.isEmpty()) throw new IllegalStateException("Your server did not advertise a group-chat service. Join a room using its full address instead.");
        return create(java.util.UUID.randomUUID()+"@"+services.get(0),nickname,title.trim());
    }
    public State configure(String jid,String title,String description) throws Exception {
        MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Join the room before editing it.");
        org.jivesoftware.smackx.xdata.form.FillableForm form=chat.getConfigurationForm().getFillableForm();
        if(title!=null) {
            if(title.trim().isEmpty()) throw new IllegalArgumentException("Enter a group name.");
            if(form.getField("muc#roomconfig_roomname")==null) throw new IllegalStateException("This server does not support changing the room name.");
            form.setAnswer("muc#roomconfig_roomname",title.trim());
        }
        if(description!=null) {
            if(form.getField("muc#roomconfig_roomdesc")==null) throw new IllegalStateException("This server does not support a room description.");
            form.setAnswer("muc#roomconfig_roomdesc",description);
        }
        chat.sendConfigurationForm(form);
        RoomInfo info=MultiUserChatManager.getInstanceFor(connection).getRoomInfo(chat.getRoom());
        State state=new State(chat,info.getName()==null || info.getName().isEmpty() ? title : info.getName(),
                info.getDescription()==null ? description : info.getDescription()); listener.roomChanged(state); return state;
    }
    public State create(String jid,String nickname,String title) throws Exception {
        MultiUserChat chat=managed(jid);
        chat.create(Resourcepart.from(nickname));
        try {
            org.jivesoftware.smackx.xdata.form.FillableForm form=chat.getConfigurationForm().getFillableForm();
            if(form.getField("muc#roomconfig_membersonly")==null || form.getField("muc#roomconfig_whois")==null)
                throw new IllegalStateException("This server does not offer the private room settings required for OMEMO.");
            form.setAnswer("muc#roomconfig_membersonly",true);
            form.setAnswer("muc#roomconfig_whois","anyone");
            if(form.getField("muc#roomconfig_persistentroom")!=null) form.setAnswer("muc#roomconfig_persistentroom",true);
            if(form.getField("muc#roomconfig_publicroom")!=null) form.setAnswer("muc#roomconfig_publicroom",false);
            if(form.getField("muc#roomconfig_roomname")!=null) form.setAnswer("muc#roomconfig_roomname",title);
            chat.sendConfigurationForm(form);
            RoomInfo info=MultiUserChatManager.getInstanceFor(connection).getRoomInfo(chat.getRoom());
            if(!info.isMembersOnly() || !info.isNonanonymous()) throw new IllegalStateException("The server did not apply the required private room settings.");
            State joined=join(jid,nickname,null);
            return joined.title==null || joined.title.isEmpty() ? new State(chat,title,joined.description) : joined;
        } catch(Exception failed) {
            try { chat.leave(); } catch(Exception ignored) { }
            throw new XmppRequestException("The room was created, but setup could not be completed. Its owner can configure it and rejoin",failed);
        }
    }
    public void invite(String jid,String address,String reason) throws Exception {
        MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Join the room before inviting someone.");
        Jid target=JidCreate.from(address);
        if(!target.isEntityBareJid()) throw new IllegalArgumentException("Enter a bare Jabber address.");
        chat.invite(target.asEntityBareJidOrThrow(),reason);
    }
    public enum MemberAction { KICK, GRANT_VOICE, REVOKE_VOICE, GRANT_MODERATOR, REVOKE_MODERATOR, GRANT_MEMBERSHIP, REVOKE_MEMBERSHIP }
    public void memberAction(String jid,Occupant selected,MemberAction action) throws Exception {
        MultiUserChat chat=chat(jid);
        EntityFullJid from=JidCreate.entityFullFrom(selected.address);
        if(!from.asEntityBareJid().equals(chat.getRoom()) || !chat.isJoined()) throw new IllegalArgumentException("The participant is not in this room.");
        org.jivesoftware.smackx.muc.Occupant member=chat.getOccupant(from);
        if(member==null) throw new IllegalStateException("This participant is no longer in the room.");
        String currentJid=member.getJid()==null ? null : member.getJid().asBareJid().toString();
        Presence currentPresence=chat.getOccupantPresence(from);
        if(!Objects.equals(selected.realJid,currentJid) || selected.realJid==null && selected.presence!=currentPresence
                && (!stableOccupants.contains(chat.getRoom()) || occupantId(selected.presence)==null || !occupantId(selected.presence).equals(occupantId(currentPresence))))
            throw new IllegalStateException("This participant changed. Refresh the list before trying again.");
        if(from.getResourcepart().equals(chat.getNickname())) throw new IllegalArgumentException("Select another participant.");
        switch(action) {
            case KICK: chat.kickParticipant(from.getResourcepart(),"Removed by a room moderator"); break;
            case GRANT_VOICE: chat.grantVoice(from.getResourcepart()); break;
            case REVOKE_VOICE: chat.revokeVoice(from.getResourcepart()); break;
            case GRANT_MODERATOR: chat.grantModerator(from.getResourcepart()); break;
            case REVOKE_MODERATOR: chat.revokeModerator(from.getResourcepart()); break;
            case GRANT_MEMBERSHIP: case REVOKE_MEMBERSHIP:
                if(member.getJid()==null) throw new IllegalStateException("This room does not disclose the participant's Jabber address.");
                if(action==MemberAction.GRANT_MEMBERSHIP) chat.grantMembership(member.getJid().asBareJid());
                else chat.revokeMembership(member.getJid().asBareJid());
                break;
        }
        queueState(chat);
    }
    public Map<String,String> affiliations(String jid) throws Exception {
        MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Join the room before managing memberships.");
        Map<MUCAffiliation,org.jivesoftware.smack.StanzaCollector> queries=new LinkedHashMap<>();
        Map<String,String> result=new TreeMap<>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(connection.getReplyTimeout());
        try {
            for(MUCAffiliation affiliation:new MUCAffiliation[]{MUCAffiliation.member,MUCAffiliation.admin,MUCAffiliation.owner,MUCAffiliation.outcast}) {
                org.jivesoftware.smackx.muc.packet.MUCAdmin query=new org.jivesoftware.smackx.muc.packet.MUCAdmin();
                query.setTo(chat.getRoom()); query.setType(IQ.Type.get);
                query.addItem(new org.jivesoftware.smackx.muc.packet.MUCItem(affiliation));
                queries.put(affiliation,connection.createStanzaCollectorAndSend(query));
            }
            for(Map.Entry<MUCAffiliation,org.jivesoftware.smack.StanzaCollector> query:queries.entrySet()) {
                org.jivesoftware.smackx.muc.packet.MUCAdmin answer;
                try { answer=query.getValue().nextResultOrThrow(Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                catch(Exception failed) { throw new XmppRequestException("Room "+query.getKey()+" list",failed); }
                for(org.jivesoftware.smackx.muc.packet.MUCItem member:answer.getItems()) {
                    if(member.getJid()==null || !member.getJid().isEntityBareJid()) throw new IllegalStateException("The room returned an invalid member address.");
                    String address=member.getJid().toString(),previous=result.put(address,query.getKey().toString());
                    if(previous!=null && !previous.equals(query.getKey().toString())) throw new IllegalStateException("Membership changed while loading. Refresh the list.");
                }
            }
            return Collections.unmodifiableMap(result);
        } finally { for(org.jivesoftware.smack.StanzaCollector query:queries.values()) query.cancel(); }
    }
    public void affiliation(String jid,String address,String affiliation) throws Exception {
        MultiUserChat chat=chat(jid); Jid target=JidCreate.from(address);
        if(!chat.isJoined()) throw new IllegalStateException("Join the room before changing memberships.");
        if(!target.isEntityBareJid()) throw new IllegalArgumentException("Enter a bare Jabber address.");
        if(target.equals(connection.getUser().asBareJid())) throw new IllegalArgumentException("Use another owner to change your own membership.");
        switch(affiliation) {
            case "member": chat.grantMembership(target); break;
            case "none": chat.revokeMembership(target); break;
            case "outcast": chat.banUser(target,"Removed by a room administrator"); break;
            case "admin": chat.grantAdmin(target); break;
            default: throw new IllegalArgumentException("Unsupported membership change.");
        }
        queueState(chat);
    }
    public void subject(String jid,String subject) throws Exception { chat(jid).changeSubject(subject); }
    private void deliver(MultiUserChat chat,Message wire,String body,boolean encrypted,boolean readable) { deliver(chat,wire,body,encrypted,readable,null); }
    private void deliver(MultiUserChat chat,Message wire,String body,boolean encrypted,boolean readable,String fingerprint) {
        XmppRoomMessage message=XmppRoomMessage.from(wire,chat.getRoom(),connection.getUser().asEntityBareJid(),
                chat.getNickname()==null ? "" : chat.getNickname().toString(),body,encrypted,readable);
        if (message!=null) listener.roomMessage(identify(chat,wire,message.withSenderFingerprint(fingerprint)));
    }
    private XmppRoomMessage identify(MultiUserChat chat,Message wire,XmppRoomMessage message) {
        if(stableOccupants.contains(chat.getRoom())) message=message.withOccupantId(occupantId(wire));
        if(message.realSenderVerified) return message;
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
            boolean publicReaction=MessageReactions.publicLegacy(wire);
            boolean encrypted=(modern || element!=null) && !publicReaction,readable=!encrypted;
            String body=encrypted || publicReaction ? null : wire.getBody();
            if(XmppRoomMessage.archived(wire,chat.getRoom(),own,"",body,encrypted,readable,result.getId(),time)==null) continue;
            String fingerprint=null;
            if(element!=null && !modern && !publicReaction) {
                if(element.isKeyTransportElement()) continue;
                org.jivesoftware.smackx.muc.packet.MUCUser muc=org.jivesoftware.smackx.muc.packet.MUCUser.from(wire);
                Jid author=muc==null || muc.getItem()==null ? null : muc.getItem().getJid();
                // A historical nickname cannot be resolved through today's occupant list.
                if(author!=null) try {
                    OmemoMessage.Received clear=omemo.decrypt(author.asBareJid(),element);
                    if(clear.isKeyTransportMessage()) continue;
                    body=clear.getBody(); readable=true; fingerprint=clear.getSendersFingerprint().toString();
                } catch(org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException
                        | org.jivesoftware.smackx.omemo.exceptions.NoRawSessionException
                        | org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException unavailable) {
                    // Keep an explicit placeholder; the local cache preserves previously decoded content.
                }
            }
            XmppRoomMessage message=XmppRoomMessage.archived(wire,chat.getRoom(),own,"",body,encrypted,readable,result.getId(),time);
            if(message!=null) messages.add(identify(chat,wire,message.withSenderFingerprint(fingerprint)));
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
        if (!chat.isJoined()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        Set<BareJid> members=memberLists(connection,chat.getRoom());
        if(closed || !chat.isJoined()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
        for (EntityFullJid occupant:chat.getOccupants()) {
            org.jivesoftware.smackx.muc.Occupant user=chat.getOccupant(occupant);
            if (user==null || user.getJid()==null || !members.contains(user.getJid().asBareJid()))
                throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        }
        if (!members.contains(connection.getUser().asBareJid())) throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        return members;
    }
    static Set<BareJid> memberLists(XMPPConnection connection,EntityBareJid room) throws Exception {
        Map<MUCAffiliation,org.jivesoftware.smack.StanzaCollector> queries=new LinkedHashMap<>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(connection.getReplyTimeout());
        try {
            // Pipeline the same Smack affiliation IQs so one complete check needs one network round trip.
            for(MUCAffiliation affiliation:new MUCAffiliation[]{MUCAffiliation.member,MUCAffiliation.admin,MUCAffiliation.owner}) {
                org.jivesoftware.smackx.muc.packet.MUCAdmin query=new org.jivesoftware.smackx.muc.packet.MUCAdmin();
                query.setTo(room); query.setType(IQ.Type.get);
                query.addItem(new org.jivesoftware.smackx.muc.packet.MUCItem(affiliation));
                queries.put(affiliation,connection.createStanzaCollectorAndSend(query));
            }
            RoomInfo info;
            try { info=MultiUserChatManager.getInstanceFor(connection).getRoomInfo(room); }
            catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("The room server did not answer its room-information query.",timeout); }
            catch(org.jivesoftware.smack.XMPPException.XMPPErrorException rejected) { throw new XmppRequestException("Room information",rejected); }
            if(!info.isNonanonymous() || !info.isMembersOnly()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
            Set<BareJid> members=new HashSet<>();
            for(Map.Entry<MUCAffiliation,org.jivesoftware.smack.StanzaCollector> query:queries.entrySet()) {
                try {
                    long remaining=Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                    org.jivesoftware.smackx.muc.packet.MUCAdmin answer=query.getValue().nextResultOrThrow(remaining);
                    for(org.jivesoftware.smackx.muc.packet.MUCItem member:answer.getItems()) {
                        if(member.getJid()==null) throw new IllegalArgumentException("Room membership response omitted a member address");
                        members.add(member.getJid().asBareJid());
                    }
                } catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("The room server did not answer its "+query.getKey()+" list query.",timeout); }
                catch(org.jivesoftware.smack.XMPPException.XMPPErrorException rejected) { throw new XmppRequestException("Room "+query.getKey()+" list",rejected); }
            }
            return members;
        } finally { for(org.jivesoftware.smack.StanzaCollector query:queries.values()) query.cancel(); }
    }
    public XmppRoomMessage send(String jid,String body,boolean encrypted) throws Exception { return send(jid,body,encrypted,Collections.emptySet()); }
    public XmppRoomMessage send(String jid,String body,boolean encrypted,Set<String> excluded) throws Exception { return send(jid,body,encrypted,excluded,false);
    }
    public XmppRoomMessage send(String jid,String body,boolean encrypted,Set<String> excluded,boolean automatic) throws Exception {
        return send(jid,body,encrypted,null,null,excluded,automatic); }
    public XmppRoomMessage sendFile(String jid,FileLink link,FileShare sharing,String caption,boolean encrypted) throws Exception {
        return sendFile(jid,link,sharing,caption,encrypted,Collections.emptySet());
    }
    public XmppRoomMessage sendFile(String jid,FileLink link,FileShare sharing,String caption,boolean encrypted,Set<String> excluded) throws Exception {
        if(link.encrypted()!=encrypted || encrypted && sharing!=null) throw new IllegalArgumentException("File encryption mismatch");
        return sendFile(jid,link,sharing,caption,encrypted,excluded,false);
    }
    public XmppRoomMessage sendFile(String jid,FileLink link,FileShare sharing,String caption,boolean encrypted,Set<String> excluded,boolean automatic) throws Exception {
        if(link.encrypted()!=encrypted || encrypted && sharing!=null) throw new IllegalArgumentException("File encryption mismatch");
        return sendFile(jid,link,sharing,caption,encrypted,null,excluded,automatic);
    }
    public XmppRoomMessage sendFile(String jid,FileLink link,FileShare sharing,String caption,boolean encrypted,ReplyReference reply,Set<String> excluded,boolean automatic) throws Exception {
        if(link.encrypted()!=encrypted || encrypted && sharing!=null) throw new IllegalArgumentException("File encryption mismatch");
        return send(jid,sharing==null ? link.wireUrl : sharing.body(caption),encrypted,sharing,reply,null,excluded,automatic);
    }
    static Set<BareJid> recipients(Set<BareJid> members,BareJid own,Set<String> excluded) throws Exception {
        Set<BareJid> recipients=new HashSet<>(members);
        for(String address:excluded) {
            Jid parsed=JidCreate.from(address);
            if(parsed.hasResource() || !parsed.isEntityBareJid() || parsed.equals(own)) throw new IllegalArgumentException("Invalid excluded room recipient");
            recipients.remove(parsed.asBareJid());
        }
        if(members.stream().anyMatch(member -> !member.equals(own)) && recipients.stream().noneMatch(member -> !member.equals(own)))
            throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES,"Include at least one other room member in Conversation security.");
        return recipients;
    }
    public XmppRoomMessage send(String jid,String body,boolean encrypted,ReplyReference reply,MessageCorrection correction,Set<String> excluded) throws Exception {
        return send(jid,body,encrypted,reply,correction,excluded,false);
    }
    public XmppRoomMessage send(String jid,String body,boolean encrypted,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic) throws Exception {
        return send(jid,body,encrypted,null,reply,correction,excluded,automatic);
    }
    private XmppRoomMessage send(String jid,String body,boolean encrypted,FileShare sharing,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic) throws Exception {
        if (body==null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        MultiUserChat chat=chat(jid);
        if (!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        String wireBody=reply==null ? body : reply.wireBody(body);
        Map<String,String> skipped=new TreeMap<>();
        Message message=roomMessage(chat,jid,wireBody,encrypted,excluded,automatic,skipped);
        if(reply!=null) reply.addTo(message); if(correction!=null) correction.addTo(message);
        if(sharing!=null) sharing.addTo(message,wireBody);
        String id=UUID.randomUUID().toString(); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        XmppRoomMessage sent=XmppRoomMessage.sentFile(chat.getRoom(),connection.getUser().asEntityBareJid(),chat.getNickname().toString(),id,body,encrypted,sharing,reply,correction);
        if(stableOccupants.contains(chat.getRoom())) sent=sent.withOccupantId(occupantId(chat.getOccupantPresence(JidCreate.entityFullFrom(chat.getRoom(),chat.getNickname()))));
        sent=sent.withSenderFingerprint(encrypted ? omemo.getOwnFingerprint().toString() : null);
        chatStates.decorate(message); chat.sendMessage(message); chatStates.sent(message); return sent.withSkippedRecipients(skipped);
    }
    private Message roomMessage(MultiUserChat chat,String jid,String wireBody,boolean encrypted,Set<String> excluded,boolean automatic,Map<String,String> skipped) throws Exception {
        Message message;
        if (encrypted) {
            Set<BareJid> members;
            try { members=members(jid); }
            catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("The room server did not answer the membership check.",timeout); }
            Set<BareJid> recipients=recipients(members,connection.getUser().asBareJid(),excluded);
            OmemoMessage.Sent ciphertext;
            if(automatic) ciphertext=encryptAvailable(recipients,wireBody,skipped);
            else {
                for (BareJid recipient:recipients) {
                    try { if(deviceLookup.devices(recipient).isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES,recipient.toString()); }
                    catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("Device list lookup timed out for "+recipient,timeout); }
                    catch(org.jivesoftware.smack.XMPPException.XMPPErrorException rejected) { throw new XmppRequestException("OMEMO device list for "+recipient,rejected); }
                }

                try { ciphertext=omemo.encrypt(recipients,wireBody); }
                catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("An OMEMO bundle or session lookup timed out while preparing recipient devices.",timeout); }
                if (!ciphertext.getSkippedDevices().isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE);
                for (BareJid recipient:recipients) {
                    if (recipient.equals(connection.getUser().asBareJid())) continue;
                    boolean included=false;
                    for (OmemoDevice device:ciphertext.getIntendedDevices()) if (device.getJid().equals(recipient)) included=true;
                    if (!included) throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES);
                }
            }
            try { if (!members(jid).equals(members)) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED); }
            catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("The room server did not answer the final membership check.",timeout); }
            message=ciphertext.buildMessage(connection.getStanzaFactory().buildMessageStanza().ofType(Message.Type.groupchat),chat.getRoom());
        } else { message=new Message(chat.getRoom(),Message.Type.groupchat); message.setBody(wireBody); }
        return message;
    }
    private static final class Unavailable {
        final long until=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
        final Exception error;
        Unavailable(Exception error) { this.error=error; }
    }
    private final Map<String,Unavailable> unavailable=new ConcurrentHashMap<>();
    public void retryUnavailable() { unavailable.clear(); }
    private static boolean unavailable(Exception error) {
        return error instanceof org.jivesoftware.smack.SmackException.NoResponseException
            || error instanceof org.jivesoftware.smack.XMPPException.XMPPErrorException
            || error instanceof org.jivesoftware.smackx.omemo.exceptions.CannotEstablishOmemoSessionException
            || error instanceof OmemoSendException && (((OmemoSendException)error).reason==OmemoSendException.Reason.CONTACT_AUTHORIZATION_REQUIRED || ((OmemoSendException)error).reason==OmemoSendException.Reason.NO_DEVICES);
    }
    private void checkUnavailable(String key) throws Exception {
        Unavailable last=unavailable.get(key);
        if(last!=null && System.nanoTime()<last.until) throw last.error;
        unavailable.remove(key);
    }
    private OmemoMessage.Sent encryptAvailable(Set<BareJid> recipients,String body,Map<String,String> skipped) throws Exception {
        synchronized(omemo) {
            Set<OmemoDevice> devices=new HashSet<>();
            for(BareJid recipient:recipients) {
                Set<OmemoDevice> found;
                String key=recipient.toString();
                try {
                    found=omemo.getDevicesOf(recipient);
                    if(found.isEmpty()) { checkUnavailable(key); found=deviceLookup.devices(recipient); }
                    unavailable.remove(key);
                }
                catch(Exception error) {
                    if(!unavailable(error)) throw error;
                    unavailable.putIfAbsent(key,new Unavailable(error)); found=omemo.getDevicesOf(recipient);
                    if(found.isEmpty()) { skipped.put(key,XmppRequestException.describe(error)); continue; }
                }
                if(found.isEmpty() && !recipient.equals(connection.getUser().asBareJid())) {
                    skipped.put(key,"No published OMEMO devices"); unavailable.put(key,new Unavailable(new OmemoSendException(OmemoSendException.Reason.NO_DEVICES)));
                }
                devices.addAll(found);
            }
            devices.remove(omemo.getOwnDevice());
            Set<OmemoDevice> available=new HashSet<>();
            for(OmemoDevice device:devices) {
                String key=device.getJid()+" · device "+device.getDeviceId();
                org.jivesoftware.smackx.omemo.trust.OmemoFingerprint fingerprint;
                try { checkUnavailable(key); fingerprint=org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.prepare(omemo,device); unavailable.remove(key); }
                catch(Exception error) {
                    if(!unavailable(error)) throw error;
                    unavailable.putIfAbsent(key,new Unavailable(error)); skipped.put(key,XmppRequestException.describe(error)); continue;
                }
                if(!omemo.isDecidedOmemoIdentity(device,fingerprint)) throw new OmemoSendException(OmemoSendException.Reason.TRUST_REQUIRED,key);
                if(!omemo.isTrustedOmemoIdentity(device,fingerprint)) throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES,key);
                available.add(device);
            }
            if(recipients.stream().anyMatch(jid -> !jid.equals(connection.getUser().asBareJid()))
                    && available.stream().noneMatch(device -> !device.getJid().equals(connection.getUser().asBareJid())))
                throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES,"No reachable trusted recipient remains; message was not sent.");
            OmemoMessage.Sent ciphertext=org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.encrypt(omemo,available,body);
            if(!ciphertext.getSkippedDevices().isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE);
            return ciphertext;
        }
    }
    private static OmemoSendException lookupTimeout(String detail,Exception cause) {
        OmemoSendException error=new OmemoSendException(OmemoSendException.Reason.LOOKUP_TIMEOUT,detail); error.initCause(cause); return error;
    }
    /** Explicit plaintext metadata, matching XEP-0444 clients that use legacy OMEMO for text. */
    public boolean legacyRetractions(String jid) throws Exception { return legacyRetractions.getOrDefault(chat(jid).getRoom(),false); }
    public void sendRetraction(String jid,MessageRetraction request,String originalSender,String originalStable,boolean encrypted,Set<String> excluded,boolean automatic) throws Exception {
        MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        EntityFullJid own=JidCreate.entityFullFrom(chat.getRoom(),chat.getNickname());
        String stable=stableOccupants.contains(chat.getRoom()) ? occupantId(chat.getOccupantPresence(own)) : null;
        if(!own.toString().equals(originalSender) && (stable==null || !stable.equals(originalStable)))
            throw new IllegalArgumentException("The room cannot verify this message belongs to your current occupant");
        Message wire=roomMessage(chat,jid,MessageRetraction.FALLBACK_BODY,encrypted,excluded,automatic,new TreeMap<>());
        if(!own.equals(JidCreate.entityFullFrom(chat.getRoom(),chat.getNickname()))) throw new IllegalStateException("Room nickname changed before retraction");
        String id=UUID.randomUUID().toString();
        wire.setStanzaId(id); wire.addExtension(new OriginIdElement(id)); request.addMetadataTo(wire);
        wire.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build());
        org.jivesoftware.smack.StanzaCollector collector=connection.createStanzaCollector(stanza -> stanza instanceof Message
                && id.equals(stanza.getStanzaId()) && stanza.getFrom()!=null
                && (((Message)stanza).getType()==Message.Type.error && chat.getRoom().equals(stanza.getFrom().asBareJid())
                || ((Message)stanza).getType()==Message.Type.groupchat && own.equals(stanza.getFrom())));
        try {
            chat.sendMessage(wire);
            Message echo=collector.nextResultOrThrow(); MessageRetraction confirmed=MessageRetraction.from(echo);
            if(confirmed==null || confirmed.legacy!=request.legacy || !confirmed.id.equals(request.id))
                throw new java.io.IOException("Room did not confirm the requested retraction");
        }
        finally { collector.cancel(); }
    }
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
