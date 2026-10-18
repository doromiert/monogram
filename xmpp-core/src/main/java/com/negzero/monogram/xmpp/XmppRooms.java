// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.*;
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
    private final RoomInvitations outgoingInvitations=new RoomInvitations();
    private final org.jivesoftware.smack.StanzaListener invitationInterceptor=stanza -> trackInvitation((Message)stanza);
    private final org.jivesoftware.smack.StanzaListener invitationErrors=stanza -> rejectInvitation((Message)stanza);
    private final org.jivesoftware.smack.StanzaListener metadataChanges=stanza -> changedMetadata((Message)stanza);
    static boolean metadataHint(Message message,BareJid account) {
        if(message.getType()!=Message.Type.groupchat || message.getFrom()==null || !message.getFrom().isEntityBareJid()
                || staleInvitation(message) || message.getTo()!=null && !account.equals(message.getTo().asBareJid())) return false;
        java.util.List<ExtensionElement> extensions=message.getExtensions("x",org.jivesoftware.smackx.muc.packet.MUCUser.NAMESPACE);
        return extensions.size()==1 && extensions.get(0) instanceof org.jivesoftware.smackx.muc.packet.MUCUser
                && ((org.jivesoftware.smackx.muc.packet.MUCUser)extensions.get(0)).getStatus().contains(org.jivesoftware.smackx.muc.packet.MUCUser.Status.create(104));
    }
    private void changedMetadata(Message message) {
        if(closed || connection.getUser()==null || !metadataHint(message,connection.getUser().asBareJid())) return;
        MultiUserChat chat=chats.get(message.getFrom().asEntityBareJidIfPossible());
        if(chat!=null && chat.isJoined()) listener.roomMetadataChanged(chat.getRoom().toString());
    }
    private void rejectInvitation(Message error) {
        RoomInvitations.Pending invitation=outgoingInvitations.rejected(error);
        if(!closed && invitation!=null) listener.roomInviteFailed(invitation.room.toString(),invitation.target.toString(),error.getError().getCondition().toString());
    }
    private void trackInvitation(Message message) {
        if(closed || connection.getUser()==null || message.getTo()==null || !message.getTo().isEntityBareJid()) return;
        MultiUserChat chat=chats.get(message.getTo().asEntityBareJidIfPossible());
        if(chat!=null && chat.isJoined()) outgoingInvitations.sent(message,chat.getRoom(),connection.getUser().asBareJid());
    }
    private volatile boolean closed;
    private final ThreadPoolExecutor bundleLookups=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r -> {
        Thread thread=new Thread(r,"Monogram room bundles");thread.setDaemon(true);return thread;
    });
    private final Set<Future<?>> pendingBundles=ConcurrentHashMap.newKeySet();
    interface DeviceLookup { Set<OmemoDevice> devices(BareJid contact) throws Exception; }
    private final DeviceLookup deviceLookup;
    XmppSession legacySession;
    XmppRooms(XMPPConnection connection,OmemoManager omemo,XmppSession.Listener listener,DeviceLookup deviceLookup) {
        this.connection=connection; this.omemo=omemo; this.listener=listener; this.deviceLookup=deviceLookup;
        recoveryLookups.allowCoreThreadTimeOut(true);connection.addConnectionListener(recoveryLifecycle);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("occupant-id",OCCUPANT_ID,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        connection.addStanzaInterceptor(invitationInterceptor,stanza -> stanza instanceof Message && stanza.hasExtension("x",RoomInvitations.NS));
        connection.addAsyncStanzaListener(invitationErrors,stanza -> stanza instanceof Message && ((Message)stanza).getType()==Message.Type.error);
        connection.addAsyncStanzaListener(metadataChanges,stanza -> stanza instanceof Message && stanza.hasExtension("x",org.jivesoftware.smackx.muc.packet.MUCUser.NAMESPACE));
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
    }
    LegacyIncomingJournal.Context legacyContext(Message wire,boolean live,String archiveId,Date time) throws Exception {
        if(closed || connection.getUser()==null || wire.getFrom()==null || MessageReactions.publicLegacy(wire)) return null;
        MultiUserChat chat=chats.get(wire.getFrom().asEntityBareJidIfPossible());
        if(chat==null || !chat.isJoined()) return null;
        String author;
        if(archiveId!=null || staleInvitation(wire)) {
            // Delayed/history nicknames cannot be resolved through today's occupant map.
            org.jivesoftware.smackx.muc.packet.MUCUser user=org.jivesoftware.smackx.muc.packet.MUCUser.from(wire);
            author=user==null || user.getItem()==null || user.getItem().getJid()==null ? null : user.getItem().getJid().asBareJid().toString();
        } else {
            org.jivesoftware.smackx.muc.Occupant occupant=chat.getOccupant(wire.getFrom().asEntityFullJidIfPossible());
            author=occupant==null || occupant.getJid()==null ? null : occupant.getJid().asBareJid().toString();
        }
        if(author==null) return null;
        String nickname=chat.getNickname()==null ? "" : chat.getNickname().toString();
        XmppRoomMessage snapshot=XmppRoomMessage.archived(wire,chat.getRoom(),connection.getUser().asEntityBareJid(),nickname,null,true,false,archiveId,time);
        if(snapshot==null) return null;
        return LegacyIncomingJournal.Context.room(wire,author,nickname,identify(chat,wire,snapshot),live);
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
    public RoomInfo information(String room) throws Exception { return MultiUserChatManager.getInstanceFor(connection).getRoomInfo(JidCreate.entityBareFrom(room)); }
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
        if(from==null) return false;
        MultiUserChat chat=chats.get(from.asEntityBareJid());
        return chat!=null && chat.isJoined() && !from.getResourcepart().equals(chat.getNickname()) && chat.getOccupant(from)!=null;
    }
    boolean ownChatStateOccupant(EntityFullJid from) {
        if(from==null) return false;
        MultiUserChat chat=chats.get(from.asEntityBareJid());
        return chat!=null && from.getResourcepart().equals(chat.getNickname());
    }
    String chatStateIdentity(EntityFullJid from) {
        if(!acceptsChatState(from)) return null;
        MultiUserChat chat=chats.get(from.asEntityBareJid());
        Presence presence=chat.getOccupantPresence(from);
        if(presence==null || !presence.isAvailable()) return null;
        if(stableOccupants.contains(chat.getRoom())) {
            String id=occupantId(presence);
            return id==null ? null : "id:"+id;
        }
        org.jivesoftware.smackx.muc.Occupant occupant=chat.getOccupant(from);
        return occupant==null ? null : occupant.getJid()==null ? from.toString() : occupant.getJid().asBareJid().toString();
    }
    boolean matchesChatStateIdentity(Message message) {
        EntityFullJid from=message.getFrom().asEntityFullJidIfPossible();
        String current=chatStateIdentity(from);
        return current!=null && (!stableOccupants.contains(from.asEntityBareJid()) || current.equals("id:"+occupantId(message)));
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
                    if (pending!=null) {
                        pending=identify(created,message,pending);
                        try { LegacyIncomingJournal.Context context=legacyContext(message,false,null,null);if(context!=null) pending=pending.withAliases(legacySession.legacyAliases(context)); }
                        catch(Exception invalidRoute) { /* Do not alias a historical nickname through today's occupant. */ }
                        listener.roomMessagePending(pending);
                    }
                } else deliver(created,message,encrypted ? null : message.getBody(),encrypted,!encrypted);
            });
            created.addParticipantListener(presence -> {
                if(chatStates!=null && presence.getType()==Presence.Type.unavailable) {
                    org.jivesoftware.smackx.muc.packet.MUCUser muc=org.jivesoftware.smackx.muc.packet.MUCUser.from(presence);
                    if(muc!=null && muc.getStatus().contains(org.jivesoftware.smackx.muc.packet.MUCUser.Status.create(110))) chatStates.roomLeft(created.getRoom());
                    else chatStates.unavailable(presence);
                }
                queueState(created);
            });
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
        if (!chat.isJoined()) {
            if(chatStates!=null) chatStates.roomLeft(chat.getRoom());
            chat.join(chat.getEnterConfigurationBuilder(Resourcepart.from(nickname))
                    .withPassword(password).requestMaxStanzasHistory(features.containsFeature("urn:xmpp:mam:2") ? 0 : 50).build());
        }
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
    public Map<String,String> affiliations(String jid) throws Exception { return affiliations(jid,true); }
    public Map<String,String> roster(String jid) throws Exception { return affiliations(jid,false); }
    private Map<String,String> affiliations(String jid,boolean includeOutcasts) throws Exception {
        MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Join the room before managing memberships.");
        Map<MUCAffiliation,org.jivesoftware.smack.StanzaCollector> queries=new LinkedHashMap<>();
        Map<String,String> result=new TreeMap<>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(connection.getReplyTimeout());
        try {
            for(MUCAffiliation affiliation:includeOutcasts ? new MUCAffiliation[]{MUCAffiliation.member,MUCAffiliation.admin,MUCAffiliation.owner,MUCAffiliation.outcast}
                    : new MUCAffiliation[]{MUCAffiliation.member,MUCAffiliation.admin,MUCAffiliation.owner}) {
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
        private final List<Runnable> accepted;
        private final java.util.concurrent.atomic.AtomicBoolean stored=new java.util.concurrent.atomic.AtomicBoolean();
        public ArchivePage(List<XmppRoomMessage> messages,String firstId,String lastId,boolean complete) {
            this(messages,firstId,lastId,complete,Collections.emptyList());
        }
        private ArchivePage(List<XmppRoomMessage> messages,String firstId,String lastId,boolean complete,List<Runnable> accepted) {
            this.messages=Collections.unmodifiableList(new ArrayList<>(messages)); this.firstId=firstId; this.lastId=lastId; this.complete=complete;
            this.accepted=Collections.unmodifiableList(new ArrayList<>(accepted));
        }
        public void stored() { if(stored.compareAndSet(false,true)) for(Runnable action:accepted) action.run(); }
    }
    public boolean hasArchive(String jid) throws Exception {
        return org.jivesoftware.smackx.mam.MamManager.getInstanceFor(chat(jid)).isSupported();
    }
    public ArchivePage archiveSince(String jid,Date since,int limit) throws Exception {
        if(since==null || since.getTime()<0) throw new IllegalArgumentException("Invalid archive date");
        return archive(jid,null,null,limit,new Date(since.getTime()));
    }
    public ArchivePage archive(String jid,String before,String after,int limit) throws Exception {
        return archive(jid,before,after,limit,null);
    }
    private ArchivePage archive(String jid,String before,String after,int limit,Date since) throws Exception {
        MultiUserChat chat=chat(jid); if(!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        org.jivesoftware.smackx.mam.MamManager.MamQueryArgs.Builder args=org.jivesoftware.smackx.mam.MamManager.MamQueryArgs.builder()
                .setResultPageSizeTo(Math.min(Math.max(limit,1),100));
        if(before!=null) args.beforeUid(before); if(after!=null) args.afterUid(after);
        if(since!=null) args.limitResultsSince(since);
        org.jivesoftware.smackx.mam.MamManager.MamQuery query=org.jivesoftware.smackx.mam.MamManager.getInstanceFor(chat).queryArchive(args.build());
        List<XmppRoomMessage> messages=new ArrayList<>();List<Runnable> accepted=new ArrayList<>();
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
                    LegacyIncomingJournal.Context context=legacyContext(wire,false,result.getId(),time);
                    if(context!=null) {
                        LegacyIncomingJournal.Received decoded=legacySession.receiveLegacyArchive(context);
                        if(decoded.roomMessage!=null) messages.add(decoded.roomMessage);
                        else {
                            XmppRoomMessage placeholder=XmppRoomMessage.archived(wire,chat.getRoom(),own,"",null,true,false,result.getId(),time);
                            if(placeholder!=null) messages.add(placeholder.withAliases(legacySession.legacyAliases(context)));
                        }
                        accepted.add(() -> legacySession.acceptLegacy(decoded));continue;
                    }
                } catch(org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException
                        | org.jivesoftware.smackx.omemo.exceptions.NoRawSessionException
                        | org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException unavailable) {
                    // Keep an explicit placeholder; the local cache preserves previously decoded content.
                }
            }
            XmppRoomMessage message=XmppRoomMessage.archived(wire,chat.getRoom(),own,"",body,encrypted,readable,result.getId(),time);
            if(message!=null) {
                message=identify(chat,wire,message.withSenderFingerprint(fingerprint));
                if(encrypted && !modern) try { LegacyIncomingJournal.Context context=legacyContext(wire,false,result.getId(),time);if(context!=null) message=message.withAliases(legacySession.legacyAliases(context)); }
                catch(Exception invalidRoute) { /* Historical author is required for an unreadable cipher alias. */ }
                messages.add(message);
            }
        }
        org.jivesoftware.smackx.rsm.packet.RSMSet rsm=query.getPage().getMamFinIq().getRSMSet();
        return new ArchivePage(messages,rsm==null ? null : rsm.getFirst(),rsm==null ? null : rsm.getLast(),query.isComplete(),accepted);
    }
    public void leave(String jid) throws Exception {
        MultiUserChat chat=chat(jid);
        if(chatStates!=null) chatStates.roomLeft(chat.getRoom());
        if (chat.isJoined()) chat.leave(); listener.roomChanged(new State(chat));
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
    public XmppRoomMessage correctFile(String jid,FileShare sharing,String caption,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic) throws Exception {
        // Legacy room OMEMO does not authenticate attachment metadata.
        if(sharing==null || sharing.link.encrypted() || correction==null) throw new IllegalArgumentException("Unsupported encrypted caption correction");
        return send(jid,sharing.body(caption),false,sharing,reply,correction,excluded,automatic);
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
    public XmppRoomMessage send(String jid,String body,boolean encrypted,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic,MessageMarkup markup) throws Exception {
        return send(jid,body,encrypted,null,reply,correction,excluded,automatic,markup);
    }
    public XmppRoomMessage send(String jid,String body,boolean encrypted,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic,MessageMarkup markup,InlineEmoji inline) throws Exception {
        return send(jid,body,encrypted,null,reply,correction,excluded,automatic,markup,inline);
    }
    public XmppRoomMessage sendFile(String jid,FileLink link,FileShare sharing,String caption,boolean encrypted,ReplyReference reply,Set<String> excluded,boolean automatic,MessageMarkup markup) throws Exception {
        if(link.encrypted()!=encrypted || encrypted && sharing!=null || sharing!=null && !sharing.link.wireUrl.equals(link.wireUrl)) throw new IllegalArgumentException("Attachment encryption mode mismatch");
        if(markup!=null && (sharing==null || !markup.body.equals(caption==null ? "" : caption))) throw new IllegalArgumentException("Markup belongs to another caption");
        String body=sharing==null ? link.wireUrl : sharing.body(caption);
        return send(jid,body,encrypted,sharing,reply,null,excluded,automatic,markup==null ? null : markup.inBody(body,0));
    }
    public XmppRoomMessage correctFile(String jid,FileShare sharing,String caption,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic,MessageMarkup markup) throws Exception {
        if(sharing==null || sharing.link.encrypted() || correction==null) throw new IllegalArgumentException("Unsupported encrypted caption correction");
        if(markup!=null && !markup.body.equals(caption==null ? "" : caption)) throw new IllegalArgumentException("Markup belongs to another caption");
        String body=sharing.body(caption);
        return send(jid,body,false,sharing,reply,correction,excluded,automatic,markup==null ? null : markup.inBody(body,0));
    }
    private XmppRoomMessage send(String jid,String body,boolean encrypted,FileShare sharing,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic) throws Exception {
        return send(jid,body,encrypted,sharing,reply,correction,excluded,automatic,null);
    }
    private XmppRoomMessage send(String jid,String body,boolean encrypted,FileShare sharing,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic,MessageMarkup markup) throws Exception {
        return send(jid,body,encrypted,sharing,reply,correction,excluded,automatic,markup,null);
    }
    private XmppRoomMessage send(String jid,String body,boolean encrypted,FileShare sharing,ReplyReference reply,MessageCorrection correction,Set<String> excluded,boolean automatic,MessageMarkup markup,InlineEmoji inline) throws Exception {
        if(inline!=null && !body.equals(inline.body)) throw new IllegalArgumentException("Emoji belong to another body");
        if(markup!=null && !markup.body.equals(body)) throw new IllegalArgumentException("Markup belongs to another body");
        if (body==null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        MultiUserChat chat=chat(jid);
        if (!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        Map<String,Unavailable> failures=new LinkedHashMap<>();
        try {
        String wireBody=reply==null ? body : reply.wireBody(body);
        Map<String,String> skipped=new TreeMap<>();
        Message message=roomMessage(chat,jid,wireBody,encrypted,excluded,automatic,skipped,failures);
        if(reply!=null) reply.addTo(message); if(correction!=null) correction.addTo(message);
        if(!encrypted && markup!=null) markup.inBody(wireBody,wireBody.codePointCount(0,wireBody.length()-body.length())).addTo(message);
        if(sharing!=null) sharing.addTo(message,wireBody);
        if(!encrypted && inline!=null) inline.inBody(wireBody,wireBody.codePointCount(0,wireBody.length()-body.length())).addTo(message);
        String id=UUID.randomUUID().toString(); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        XmppRoomMessage sent=XmppRoomMessage.sentFile(chat.getRoom(),connection.getUser().asEntityBareJid(),chat.getNickname().toString(),id,body,encrypted,sharing,reply,correction);
        if(stableOccupants.contains(chat.getRoom())) sent=sent.withOccupantId(occupantId(chat.getOccupantPresence(JidCreate.entityFullFrom(chat.getRoom(),chat.getNickname()))));
        if(markup!=null) sent=sent.withMarkup(markup);
        if(inline!=null) sent=sent.withInlineEmoji(inline);
        sent=sent.withSenderFingerprint(encrypted ? omemo.getOwnFingerprint().toString() : null);
        chatStates.decorate(message); chat.sendMessage(message); chatStates.sent(message); return sent.withSkippedRecipients(skipped);
        } finally { if(automatic && encrypted) recoverUnavailable(failures); }
    }
    private Message roomMessage(MultiUserChat chat,String jid,String wireBody,boolean encrypted,Set<String> excluded,boolean automatic,Map<String,String> skipped,Map<String,Unavailable> failures) throws Exception {
        Message message;
        if (encrypted) {
            Set<BareJid> members;
            try { members=members(jid); }
            catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("The room server did not answer the membership check.",timeout); }
            Set<BareJid> recipients=recipients(members,connection.getUser().asBareJid(),excluded);
            OmemoMessage.Sent ciphertext=encryptRecipients(recipients,wireBody,skipped,automatic,failures);
            try { if (!members(jid).equals(members)) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED); }
            catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("The room server did not answer the final membership check.",timeout); }
            message=ciphertext.buildMessage(connection.getStanzaFactory().buildMessageStanza().ofType(Message.Type.groupchat),chat.getRoom());
        } else { message=new Message(chat.getRoom(),Message.Type.groupchat); message.setBody(wireBody); }
        return message;
    }
    private static final int MAX_UNAVAILABLE=512,MAX_RECOVERED=32,MAX_BUNDLE_CHARS=256*1024,MAX_RECOVERED_CHARS=1024*1024;
    private static final String DEVICE_SEPARATOR=" · device ";
    private static final class CachedUnavailable extends Exception { CachedUnavailable(String reason) { super(reason); } }
    private static final class Unavailable {
        final Exception error;final String reason;long lastQueued;
        Unavailable(Exception error) { this.error=error;this.reason=unavailableReason(error); }
    }
    private static final class RecoveredBundle {
        final org.jivesoftware.smackx.omemo.element.OmemoBundleElement bundle;final Unavailable failure;final EntityFullJid account;
        final long generation,time=System.nanoTime();final String ownIdentity,identity;final int chars;
        RecoveredBundle(org.jivesoftware.smackx.omemo.element.OmemoBundleElement bundle,Unavailable failure,EntityFullJid account,long generation,String ownIdentity,String identity,int chars) {
            this.bundle=bundle;this.failure=failure;this.account=account;this.generation=generation;this.ownIdentity=ownIdentity;this.identity=identity;this.chars=chars;
        }
    }
    private final Object unavailableLock=new Object();
    private final LinkedHashMap<String,Unavailable> unavailable=new LinkedHashMap<>();
    private final LinkedHashMap<String,RecoveredBundle> recoveredBundles=new LinkedHashMap<>();
    private final Map<String,Future<?>> recovering=new HashMap<>();
    private final ThreadPoolExecutor recoveryLookups=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r -> { Thread thread=new Thread(r,"Monogram room recovery");thread.setDaemon(true);return thread; });
    private java.util.function.Consumer<Map<String,String>> unavailableChanged;
    private long recoveryGeneration,recoverySequence;private int recoveredChars;
    private final org.jivesoftware.smack.ConnectionListener recoveryLifecycle=new org.jivesoftware.smack.ConnectionListener() {
        @Override public void connectionClosed() { cancelRecovery(); }
        @Override public void connectionClosedOnError(Exception error) { cancelRecovery(); }
    };
    /** Install before connect. This cache contains delivery failures, never trust or presence decisions. */
    public void setUnavailableCache(Map<String,String> restored,java.util.function.Consumer<Map<String,String>> changed) {
        synchronized(unavailableLock) {
            cancelRecovery();unavailable.clear();unavailableChanged=changed;
            if(restored!=null) for(Map.Entry<String,String> entry:restored.entrySet()) {
                if(unavailable.size()>=MAX_UNAVAILABLE) break;String key=entry.getKey(),reason=entry.getValue();
                if(!validUnavailableKey(key) || reason==null || reason.isEmpty() || reason.length()>512 || reason.chars().anyMatch(Character::isISOControl)) continue;
                unavailable.put(key,new Unavailable(new CachedUnavailable(reason)));
            }
        }
    }
    private static boolean validUnavailableKey(String key) {
        if(key==null || key.length()>3200) return false;
        try { int split=key.lastIndexOf(DEVICE_SEPARATOR);String jid=split<0?key:key.substring(0,split);
            if(!JidCreate.entityBareFrom(jid).toString().equals(jid)) return false;
            if(split>=0) { String id=key.substring(split+DEVICE_SEPARATOR.length());int device=Integer.parseInt(id);if(device<=0 || !Integer.toString(device).equals(id)) return false; }
            return true;
        }catch(Exception invalid) { return false; }
    }
    private void changedUnavailable() {
        if(unavailableChanged==null || closed) return;Map<String,String> snapshot=new TreeMap<>();unavailable.forEach((key,value) -> snapshot.put(key,value.reason));
        try { unavailableChanged.accept(Collections.unmodifiableMap(snapshot)); }catch(RuntimeException ignored) { /* Disposable cache persistence must never fail a send. */ }
    }
    public void retryUnavailable() { synchronized(unavailableLock) { cancelRecovery();boolean changed=!unavailable.isEmpty();unavailable.clear();if(changed) changedUnavailable(); } }
    private void cancelRecovery() { synchronized(unavailableLock) { recoveryGeneration++;for(Future<?> future:new ArrayList<>(recovering.values())) future.cancel(true);recovering.clear();recoveryLookups.purge();recoveredBundles.clear();recoveredChars=0; } }
    private void removeRecovered(String key) { RecoveredBundle previous=recoveredBundles.remove(key);if(previous!=null) recoveredChars-=previous.chars; }
    private void forgetUnavailable(String key) { synchronized(unavailableLock) { if(unavailable.remove(key)!=null) { removeRecovered(key);Future<?> job=recovering.remove(key);if(job!=null) job.cancel(true);changedUnavailable(); } } }
    private Unavailable rememberUnavailable(String key,Exception error,Map<String,Unavailable> attempt) {
        synchronized(unavailableLock) {
            Unavailable previous=unavailable.get(key);
            if(previous==null) {
                if(unavailable.size()>=MAX_UNAVAILABLE) { String oldest=unavailable.keySet().iterator().next();unavailable.remove(oldest);removeRecovered(oldest);Future<?> job=recovering.remove(oldest);if(job!=null) job.cancel(true); }
                previous=new Unavailable(error);unavailable.put(key,previous);changedUnavailable();
            }
            attempted(attempt,key,previous);return previous;
        }
    }
    private static void attempted(Map<String,Unavailable> attempt,String key,Unavailable failure) {
        if(!attempt.containsKey(key) && attempt.size()>=MAX_UNAVAILABLE) attempt.remove(attempt.keySet().iterator().next());
        attempt.put(key,failure);
    }
    private static String unavailableReason(Exception error) {
        String text=error instanceof CachedUnavailable ? error.getMessage() : XmppRequestException.describe(error);
        if(text==null || text.trim().isEmpty()) text="OMEMO recipient unavailable";
        text=text.replaceAll("[\\p{Cntrl}]"," ");return text.substring(0,Math.min(512,text.length()));
    }
    private static boolean unavailable(Exception error) {
        return error instanceof CachedUnavailable || error instanceof org.jivesoftware.smack.SmackException.NoResponseException
            || error instanceof org.jivesoftware.smack.XMPPException.XMPPErrorException
            || error instanceof org.jivesoftware.smackx.omemo.exceptions.CannotEstablishOmemoSessionException
            || error instanceof OmemoSendException && (((OmemoSendException)error).reason==OmemoSendException.Reason.CONTACT_AUTHORIZATION_REQUIRED || ((OmemoSendException)error).reason==OmemoSendException.Reason.NO_DEVICES);
    }
    private void checkUnavailable(String key,Map<String,Unavailable> attempt) throws Exception {
        synchronized(unavailableLock) { Unavailable last=unavailable.get(key);if(last!=null) { attempted(attempt,key,last);throw last.error; } }
    }
    private String storedIdentity(OmemoDevice device) throws Exception {
        try { return org.jivesoftware.smackx.omemo.MonogramLegacyOmemoService.instance().records().getFingerprint(omemo.getOwnDevice(),device).toString(); }
        catch(org.jivesoftware.smackx.omemo.exceptions.NoIdentityKeyException absent) { return null; }
    }
    private String ownIdentity() throws Exception { return org.jivesoftware.smackx.omemo.MonogramLegacyOmemoService.instance().records().getFingerprint(omemo.getOwnDevice()).toString(); }
    private org.jivesoftware.smackx.omemo.element.OmemoBundleElement takeRecovered(OmemoDevice device) throws Exception {
        String key=deviceKey(device);
        synchronized(unavailableLock) { if(!recoveredBundles.containsKey(key)) return null; }
        String identity=storedIdentity(device),own=ownIdentity();
        synchronized(unavailableLock) {
            RecoveredBundle ready=recoveredBundles.get(key);if(ready==null) return null;removeRecovered(key);
            if(ready.failure!=unavailable.get(key) || ready.generation!=recoveryGeneration || closed || !ready.account.equals(connection.getUser())
                    || !ready.ownIdentity.equals(own) || !Objects.equals(ready.identity,identity) || System.nanoTime()-ready.time>TimeUnit.MINUTES.toNanos(1)) return null;
            return ready.bundle;
        }
    }
    private boolean recoveryCurrent(String key,Unavailable failure,long generation,EntityFullJid account) {
        return !closed && recoveryGeneration==generation && unavailable.get(key)==failure && connection.isAuthenticated() && account.equals(connection.getUser());
    }
    private void recoverUnavailable(Map<String,Unavailable> attempt) {
        EntityFullJid account=connection.getUser();if(closed || account==null || !connection.isAuthenticated()) return;
        synchronized(unavailableLock) {
            List<Map.Entry<String,Unavailable>> candidates=new ArrayList<>(attempt.entrySet());
            candidates.sort(Comparator.comparingLong(entry -> entry.getValue().lastQueued));
            for(Map.Entry<String,Unavailable> entry:candidates) {
                String key=entry.getKey();Unavailable failure=entry.getValue();long generation=recoveryGeneration;
                if(!recoveryCurrent(key,failure,generation,account) || recovering.containsKey(key)) continue;
                RecoveredBundle ready=recoveredBundles.get(key);if(ready!=null && System.nanoTime()-ready.time<TimeUnit.MINUTES.toNanos(1)) continue;
                FutureTask<Void> task=new FutureTask<Void>(() -> { recoverUnavailable(key,failure,generation,account);return null; }) {
                    @Override protected void done() { synchronized(unavailableLock) { if(recovering.get(key)==this) recovering.remove(key); } }
                };
                recovering.put(key,task);
                try { recoveryLookups.execute(task);failure.lastQueued=++recoverySequence; }catch(RejectedExecutionException busy) { recovering.remove(key);task.cancel(false); }
            }
        }
    }
    private void recoverUnavailable(String key,Unavailable failure,long generation,EntityFullJid account) {
        try {
            int split=key.lastIndexOf(DEVICE_SEPARATOR);BareJid recipient=JidCreate.entityBareFrom(split<0?key:key.substring(0,split));
            synchronized(unavailableLock) { if(!recoveryCurrent(key,failure,generation,account)) return; }
            if(split<0) {
                // Own-list refresh can publish and wait under Smack's manager lock; initialization restores it locally.
                if(recipient.equals(account.asBareJid())) return;
                Set<OmemoDevice> devices=deviceLookup.devices(recipient);
                if(devices==null || devices.isEmpty() || devices.stream().anyMatch(device -> !device.getJid().equals(recipient) || device.getDeviceId()<=0)) return;
                synchronized(unavailableLock) { if(recoveryCurrent(key,failure,generation,account)) { unavailable.remove(key);removeRecovered(key);changedUnavailable(); } }
            } else {
                OmemoDevice device=new OmemoDevice(recipient,Integer.parseInt(key.substring(split+DEVICE_SEPARATOR.length())));String own,identity;
                synchronized(omemo) { own=ownIdentity();identity=storedIdentity(device); }
                org.jivesoftware.smackx.omemo.element.OmemoBundleElement bundle=org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.fetchBundle(connection,device);
                if(bundle==null) return;int chars=bundle.toXML().length();if(chars>MAX_BUNDLE_CHARS || bundle.getPreKeys().isEmpty() || bundle.getPreKeys().size()>1000) return;
                org.whispersystems.libsignal.IdentityKey publicIdentity=new org.whispersystems.libsignal.IdentityKey(bundle.getIdentityKey(),0);
                if(!org.whispersystems.libsignal.ecc.Curve.verifySignature(publicIdentity.getPublicKey(),bundle.getSignedPreKey(),bundle.getSignedPreKeySignature())) return;
                if(org.jivesoftware.smackx.omemo.MonogramLegacyOmemoService.instance().records().keyUtil().BUNDLE.bundles(bundle,device).isEmpty()) return;
                synchronized(omemo) {
                    if(!own.equals(ownIdentity()) || !Objects.equals(identity,storedIdentity(device))) return;
                    synchronized(unavailableLock) {
                        if(!recoveryCurrent(key,failure,generation,account)) return;removeRecovered(key);
                        while(!recoveredBundles.isEmpty() && (recoveredBundles.size()>=MAX_RECOVERED || recoveredChars+chars>MAX_RECOVERED_CHARS)) removeRecovered(recoveredBundles.keySet().iterator().next());
                        recoveredBundles.put(key,new RecoveredBundle(bundle,failure,account,generation,own,identity,chars));recoveredChars+=chars;
                    }
                }
            }
        }catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(Exception ignored) { /* Failed recovery retains the remembered failure for the next post-send probe. */ }
    }
    private OmemoMessage.Sent encryptRecipients(Set<BareJid> recipients,String body,Map<String,String> skipped,boolean automatic,Map<String,Unavailable> failures) throws Exception {
        Map<BareJid,Set<OmemoDevice>> deviceLists=new LinkedHashMap<>();
        Set<OmemoDevice> devices=new HashSet<>();
        Set<BareJid> targets=new LinkedHashSet<>(recipients);
        if(!automatic) targets.add(connection.getUser().asBareJid());
        Map<BareJid,Callable<Set<OmemoDevice>>> coldLists=new LinkedHashMap<>();
        Map<BareJid,Lookup<Set<OmemoDevice>>> lists=new HashMap<>();
        EntityFullJid account=connection.getUser();
        for(BareJid recipient:targets) {
            if(closed || account==null) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            if(automatic) {
                Set<OmemoDevice> cached=omemo.getDevicesOf(recipient);
                if(!cached.isEmpty()) { lists.put(recipient,new Lookup<>(cached,null));continue; }
                try { checkUnavailable(recipient.toString(),failures); }
                catch(Exception backoff) { lists.put(recipient,new Lookup<>(null,backoff));continue; }
            }
            if(recipient.equals(account.asBareJid())) {
                // Own-list refresh retains Smack's publication and device cleanup lifecycle.
                try { lists.put(recipient,new Lookup<>(deviceLookup.devices(recipient),null)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw interrupted; }
                catch(Exception error) { lists.put(recipient,new Lookup<>(null,error)); }
            } else coldLists.put(recipient,() -> deviceLookup.devices(recipient));
        }
        lists.putAll(parallelLookups(coldLists,bundleLookups,pendingBundles,lookupDeadline(),
                () -> !closed && connection.isAuthenticated() && account.equals(connection.getUser()),(recipient,result) -> {
            if(automatic && result.error!=null && unavailable(result.error))
                rememberUnavailable(recipient.toString(),result.error,failures);
            else if(automatic && result.error==null && result.value.isEmpty())
                rememberUnavailable(recipient.toString(),new OmemoSendException(OmemoSendException.Reason.NO_DEVICES),failures);
        }));
        for(BareJid recipient:targets) {
            if(closed) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            Set<OmemoDevice> found;String key=recipient.toString();Lookup<Set<OmemoDevice>> result=lists.get(recipient);
            if(!automatic) {
                try { if(result.error!=null) throw result.error;found=result.value; }
                catch(org.jivesoftware.smack.SmackException.NoResponseException timeout) { throw lookupTimeout("Device list lookup timed out for "+recipient,timeout); }
                catch(org.jivesoftware.smack.XMPPException.XMPPErrorException rejected) { throw new XmppRequestException("OMEMO device list for "+recipient,rejected); }
                if(found.isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES,key);
                deviceLists.put(recipient,new HashSet<>(found));devices.addAll(found);continue;
            }
            try {
                if(result.error!=null) throw result.error;found=result.value;
                forgetUnavailable(key);
            } catch(Exception error) {
                if(!unavailable(error)) throw error;
                rememberUnavailable(key,error,failures);found=omemo.getDevicesOf(recipient);
                if(found.isEmpty()) skipped.put(key,unavailableReason(error));
            }
            if(found.isEmpty() && !recipient.equals(account.asBareJid()) && !skipped.containsKey(key)) {
                skipped.put(key,"No published OMEMO devices");rememberUnavailable(key,new OmemoSendException(OmemoSendException.Reason.NO_DEVICES),failures);
            }
            deviceLists.put(recipient,new HashSet<>(found));devices.addAll(found);
        }
        devices.remove(omemo.getOwnDevice());
        Map<OmemoDevice,Callable<org.jivesoftware.smackx.omemo.element.OmemoBundleElement>> cold=new LinkedHashMap<>();
        Map<OmemoDevice,Lookup<org.jivesoftware.smackx.omemo.element.OmemoBundleElement>> prepared=new HashMap<>();
        synchronized(omemo) {
            for(OmemoDevice device:devices) {
                boolean needs=org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.needsSession(omemo,device);
                if(automatic && needs) {
                    org.jivesoftware.smackx.omemo.element.OmemoBundleElement recovered=takeRecovered(device);
                    if(recovered!=null) { prepared.put(device,new Lookup<>(recovered,null));continue; }
                    try { checkUnavailable(deviceKey(device),failures); }
                    catch(Exception unavailable) { prepared.put(device,new Lookup<>(null,unavailable));continue; }
                }
                if(needs)
                    cold.put(device,() -> org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.fetchBundle(connection,device));
            }
        }
        // One PEP bundle may need a node lookup and an items lookup; let real IQ failures reach backoff.
        long deadline=lookupDeadline();
        prepared.putAll(parallelLookups(cold,bundleLookups,pendingBundles,deadline,() -> !closed,(device,result) -> {
            if(!automatic) return;
            if(result.error!=null && unavailable(result.error)) rememberUnavailable(deviceKey(device),result.error,failures);
            else if(result.error==null && result.value==null) rememberUnavailable(deviceKey(device),new org.jivesoftware.smackx.omemo.exceptions.CannotEstablishOmemoSessionException(device,new java.io.IOException("No published key bundle")),failures);
        }));
        synchronized(omemo) {
            if(closed) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            for(Map.Entry<BareJid,Set<OmemoDevice>> list:deviceLists.entrySet())
                if(!list.getValue().equals(omemo.getDevicesOf(list.getKey()))) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED,"Recipient devices changed while preparing encryption; retry the message.");
            Set<OmemoDevice> available=new HashSet<>();
            for(OmemoDevice device:devices) {
                String key=deviceKey(device);
                org.jivesoftware.smackx.omemo.trust.OmemoFingerprint fingerprint;
                try {
                    Lookup<org.jivesoftware.smackx.omemo.element.OmemoBundleElement> lookup=prepared.get(device);
                    // Another receive/send may have created a valid session while this network fetch failed.
                    if(lookup!=null && lookup.error!=null && org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.needsSession(omemo,device)) throw lookup.error;
                    if(org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.needsSession(omemo,device) && lookup==null)
                        throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED,"An OMEMO session changed while preparing encryption; retry the message.");
                    fingerprint=org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.prepare(omemo,device,lookup==null ? null : lookup.value);forgetUnavailable(key);
                }
                catch(Exception error) {
                    if(!automatic) {
                        if(error instanceof org.jivesoftware.smack.SmackException.NoResponseException)
                            throw lookupTimeout("An OMEMO bundle lookup timed out for "+key,error);
                        if(error instanceof org.jivesoftware.smackx.omemo.exceptions.CannotEstablishOmemoSessionException) {
                            OmemoSendException failed=new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE,"Could not prepare a required OMEMO device: "+key);
                            failed.initCause(error);throw failed;
                        }
                        throw error;
                    }
                    if(!unavailable(error)) throw error;
                    rememberUnavailable(key,error,failures); skipped.put(key,unavailableReason(error)); continue;
                }
                if(automatic) {
                    if(!omemo.isDecidedOmemoIdentity(device,fingerprint)) throw new OmemoSendException(OmemoSendException.Reason.TRUST_REQUIRED,key);
                    if(!omemo.isTrustedOmemoIdentity(device,fingerprint)) throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES,key);
                }
                available.add(device);
            }
            if(recipients.stream().anyMatch(jid -> !jid.equals(connection.getUser().asBareJid()))
                    && available.stream().noneMatch(device -> !device.getJid().equals(connection.getUser().asBareJid())))
                throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES,"No reachable trusted recipient remains; message was not sent.");
            OmemoMessage.Sent ciphertext=org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.encrypt(omemo,available,body);
            if(!ciphertext.getSkippedDevices().isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE);
            if(!automatic && !ciphertext.getIntendedDevices().containsAll(devices))
                throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE,"Encryption omitted a required room device; no message was sent.");
            return ciphertext;
        }
    }
    private long lookupDeadline() {
        long budget=Math.min(TimeUnit.MINUTES.toNanos(1),2*TimeUnit.MILLISECONDS.toNanos(Math.min(connection.getReplyTimeout(),30000))+TimeUnit.SECONDS.toNanos(1));
        return System.nanoTime()+budget;
    }
    private static String deviceKey(OmemoDevice device) { return device.getJid()+" · device "+device.getDeviceId(); }
    static final class Lookup<T> {
        final T value;final Exception error;
        Lookup(T value,Exception error) { this.value=value;this.error=error; }
    }
    /** Only complete batches yield results: a global timeout must never become a partial recipient set. */
    static <K,V> Map<K,Lookup<V>> parallelLookups(Map<K,Callable<V>> jobs,ExecutorService executor,Set<Future<?>> outstanding,long deadline,java.util.function.BooleanSupplier current,java.util.function.BiConsumer<K,Lookup<V>> completed) throws Exception {
        CompletionService<Lookup<V>> completion=new ExecutorCompletionService<>(executor);
        Map<Future<Lookup<V>>,K> running=new HashMap<>();Map<K,Lookup<V>> results=new LinkedHashMap<>();
        Iterator<Map.Entry<K,Callable<V>>> next=jobs.entrySet().iterator();
        try {
            while(next.hasNext() || !running.isEmpty()) {
                if(!current.getAsBoolean()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
                while(next.hasNext() && running.size()<4) {
                    if(System.nanoTime()>=deadline) throw new TimeoutException("Room device preparation timed out");
                    Map.Entry<K,Callable<V>> job=next.next();Callable<V> operation=job.getValue();
                    Future<Lookup<V>> future=completion.submit(() -> {
                        try { return new Lookup<>(operation.call(),null); }
                        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw interrupted; }
                        catch(Exception failure) { return new Lookup<>(null,failure); }
                    });
                    running.put(future,job.getKey());outstanding.add(future);
                    if(!current.getAsBoolean()) future.cancel(true);
                }
                long remaining=deadline-System.nanoTime();if(remaining<=0) throw new TimeoutException("Room device preparation timed out");
                Future<Lookup<V>> finished=completion.poll(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);
                if(finished==null) continue;
                K key=running.remove(finished);outstanding.remove(finished);Lookup<V> result=finished.get();results.put(key,result);
                completed.accept(key,result);
            }
            return results;
        } catch(TimeoutException | RejectedExecutionException timedOut) {
            throw lookupTimeout("OMEMO device preparation did not finish; no message was sent.",timedOut);
        } catch(CancellationException cancelled) {
            throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
        } catch(ExecutionException failed) {
            Throwable cause=failed.getCause();if(cause instanceof InterruptedException) Thread.currentThread().interrupt();
            if(cause instanceof Exception) throw (Exception)cause;
            if(cause instanceof Error) throw (Error)cause;
            throw new IllegalStateException(cause);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw interrupted;
        } finally {
            for(Future<?> future:running.keySet()) { future.cancel(true);outstanding.remove(future); }
            if(executor instanceof ThreadPoolExecutor) ((ThreadPoolExecutor)executor).purge();
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
        Map<String,Unavailable> failures=new LinkedHashMap<>();
        try {
        Message wire=roomMessage(chat,jid,MessageRetraction.FALLBACK_BODY,encrypted,excluded,automatic,new TreeMap<>(),failures);
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
        } finally { if(automatic && encrypted) recoverUnavailable(failures); }
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
    @Override public void close() { closed=true;cancelRecovery();recoveryLookups.shutdownNow();connection.removeConnectionListener(recoveryLifecycle); for(Future<?> future:pendingBundles) future.cancel(true); bundleLookups.shutdownNow(); pendingBundles.clear(); connection.removeStanzaInterceptor(invitationInterceptor); connection.removeAsyncStanzaListener(invitationErrors); connection.removeAsyncStanzaListener(metadataChanges); outgoingInvitations.clear(); presenceUpdates.shutdownNow(); pendingPresence.clear(); chats.clear(); passwords.clear(); stableOccupants.clear(); }
}
