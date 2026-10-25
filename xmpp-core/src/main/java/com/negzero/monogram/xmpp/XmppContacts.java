// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.function.BiConsumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.roster.*;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

/** Standard roster/presence authorization; only an explicit per-contact policy auto-approves requests. */
public final class XmppContacts implements AutoCloseable {
    public enum Action { REQUEST, ALLOW, ACCEPT, DENY, CANCEL, REVOKE }
    public static final class State {
        public final boolean listed,seesTheirPresence,sharesOwnPresence,outgoingPending,preApproved,incomingPending,allowRequests;
        State(RosterEntry entry,boolean incomingPending,boolean allowRequests) {
            this.allowRequests=allowRequests;
            preApproved=entry!=null && entry.isApproved();
            listed=entry!=null; seesTheirPresence=listed && entry.canSeeHisPresence();
            sharesOwnPresence=listed && entry.canSeeMyPresence(); outgoingPending=listed && entry.isSubscriptionPending();
            this.incomingPending=incomingPending && !sharesOwnPresence;
        }
    }
    public static final class PresenceState {
        public static final PresenceState UNKNOWN = new PresenceState(false, false, 0);
        public final boolean visible, online, available;
        public final Presence.Mode mode;
        public final long lastSeen;
        public PresenceState(boolean visible, boolean online, long lastSeen) {
            this(visible,online,online,null,lastSeen);
        }
        private PresenceState(boolean visible, boolean online, boolean available, Presence.Mode mode, long lastSeen) {
            this.visible=visible; this.online=online; this.available=available; this.mode=mode; this.lastSeen=lastSeen;
        }
    }
    private final XMPPConnection connection;
    private final XmppRooms rooms;
    private final Roster roster;
    private final BiConsumer<String,Boolean> incoming;
    private volatile java.util.Set<String> allowedContacts=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Explicit per-contact approval policy. Wire authorization still uses standard subscribed stanzas. */
    public java.util.Set<String> allowedContacts() {return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(allowedContacts));}
    public void restoreAllowedContacts(java.util.Collection<String> addresses) {
        java.util.Set<String> valid=java.util.concurrent.ConcurrentHashMap.newKeySet();
        for(String address:addresses)try {
            org.jxmpp.jid.Jid parsed=JidCreate.from(address);if(parsed.isEntityBareJid())valid.add(parsed.toString());
        } catch(Exception invalid) { /* Ignore invalid local policy entries. */ }
        allowedContacts=valid;
    }
    private final java.util.Set<String> pendingRequests=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final SubscribeListener subscriptions;
    private final StanzaListener cancellations;
    XmppContacts(XMPPConnection connection,XmppRooms rooms,BiConsumer<String,Boolean> incoming) {
        this.connection=connection; this.rooms=rooms; this.incoming=incoming; roster=Roster.getInstanceFor(connection);
        org.jivesoftware.smackx.iqlast.LastActivityManager.getInstanceFor(connection).disable();
        subscriptions=(from,presence) -> {
            if(connection.getUser()!=null && from.isEntityBareJid() && !rooms.known(from) && !from.equals(connection.getUser().asBareJid())) {
                if(allowedContacts.contains(from.toString())) {
                    pendingRequests.remove(from.toString());incoming.accept(from.toString(),false);
                    return SubscribeListener.SubscribeAnswer.Approve;
                }
                pendingRequests.add(from.toString());incoming.accept(from.toString(),true);
            }
            return null;
        };
        roster.addSubscribeListener(subscriptions);
        cancellations=stanza -> {
            if(stanza.getFrom()!=null && stanza.getFrom().isEntityBareJid() && !rooms.known(stanza.getFrom())) {
                pendingRequests.remove(stanza.getFrom().toString());incoming.accept(stanza.getFrom().toString(),false);
            }
        };
        connection.addAsyncStanzaListener(cancellations,stanza -> stanza instanceof Presence && ((Presence)stanza).getType()==Presence.Type.unsubscribe);
    }
    private EntityBareJid contact(String address) throws Exception {
        org.jxmpp.jid.Jid jid=JidCreate.from(address);
        if(!jid.isEntityBareJid() || rooms.known(jid) || connection.getUser()==null || jid.equals(connection.getUser().asBareJid()))
            throw new IllegalArgumentException("A contact's bare XMPP address is required");
        return jid.asEntityBareJidIfPossible();
    }
    public State state(String address) throws Exception {
        EntityBareJid jid=contact(address);
        return new State(roster.getEntry(jid),pendingRequests.contains(jid.toString()),allowedContacts.contains(jid.toString()));
    }
    /** Roster aggregates all resources; a room occupant never grants a direct presence subscription. */
    public PresenceState presence(String address) throws Exception {
        EntityBareJid jid=contact(address);
        RosterEntry entry=roster.getEntry(jid);
        if(!connection.isAuthenticated() || !roster.isLoaded() || entry==null || !entry.canSeeHisPresence()) return PresenceState.UNKNOWN;
        return aggregate(roster.getAllPresences(jid),System.currentTimeMillis()/1000);
    }
    static PresenceState aggregate(java.util.List<Presence> resources,long now) {
        boolean available=false,allIdle=true;
        long latestIdle=0;
        Presence.Mode mode=Presence.Mode.xa;
        for(Presence resource:resources) {
            if(!resource.isAvailable()) continue;
            available=true;
            Presence.Mode current=resource.getMode();
            org.jivesoftware.smackx.last_interaction.element.IdleElement idle=
                    org.jivesoftware.smackx.last_interaction.element.IdleElement.fromPresence(resource);
            if(idle==null && (current==Presence.Mode.available || current==Presence.Mode.chat))
                return new PresenceState(true,true,true,current,0);
            long since=idle==null ? 0 : idle.getSince().getTime()/1000;
            if(since<=0 || since>now) allIdle=false;
            else latestIdle=Math.max(latestIdle,since);
            if(current==Presence.Mode.dnd) mode=Presence.Mode.dnd;
            else if(mode!=Presence.Mode.dnd && current!=Presence.Mode.xa) mode=Presence.Mode.away;
        }
        return new PresenceState(true,false,available,available ? mode : null,allIdle ? latestIdle : 0);
    }
    public PresenceState lastSeen(String address) throws Exception {
        PresenceState before=presence(address);
        if(!before.visible || before.available) return before;
        long seconds=org.jivesoftware.smackx.iqlast.LastActivityManager.getInstanceFor(connection).getLastActivity(contact(address)).lastActivity;
        PresenceState after=presence(address);
        if(!after.visible || after.available) return after;
        return new PresenceState(true,false,lastSeenTimestamp(System.currentTimeMillis()/1000,seconds));
    }
    static long lastSeenTimestamp(long now,long seconds) {
        // Zero means an available resource, not an offline user's logout time.
        return seconds>0 && seconds<now ? now-seconds : 0;
    }
    private void ensureEntry(EntityBareJid jid) throws Exception {
        if(!roster.isLoaded()) roster.reloadAndWait();
        if(roster.getEntry(jid)==null) roster.createItem(jid,null,null);
    }
    public void act(String address,Action action) throws Exception {
        EntityBareJid jid=contact(address);
        switch(action) {
            case REQUEST:
                ensureEntry(jid); RosterUtil.askForSubscriptionIfRequired(roster,jid); break;
            case ALLOW:
                ensureEntry(jid);allowedContacts.add(jid.toString());
                if(pendingRequests.remove(jid.toString())) presence(jid,Presence.Type.subscribed);
                incoming.accept(jid.toString(),false);break;
            case ACCEPT:
                ensureEntry(jid);presence(jid,Presence.Type.subscribed);pendingRequests.remove(jid.toString());incoming.accept(jid.toString(),false);
                RosterUtil.askForSubscriptionIfRequired(roster,jid);break;
            case DENY:
                allowedContacts.remove(jid.toString());presence(jid,Presence.Type.unsubscribed);pendingRequests.remove(jid.toString());incoming.accept(jid.toString(),false);break;
            case REVOKE:
                allowedContacts.remove(jid.toString());presence(jid,Presence.Type.unsubscribed);
                // Some servers retain hidden approved= state after unsubscribed. Reassert
                // fresh roster metadata to clear that state without removing the contact
                // or canceling the independent subscription to their presence.
                roster.reloadAndWait();RosterEntry entry=roster.getEntry(jid);
                if(entry!=null) {
                    java.util.List<String> groups=new java.util.ArrayList<>();for(RosterGroup group:entry.getGroups())groups.add(group.getName());
                    roster.createItem(jid,entry.getName(),groups.toArray(new String[0]));
                }
                pendingRequests.remove(jid.toString());incoming.accept(jid.toString(),false);break;
            case CANCEL: presence(jid,Presence.Type.unsubscribe); break;
        }
    }
    private void presence(EntityBareJid jid,Presence.Type type) throws Exception {
        connection.sendStanza(connection.getStanzaFactory().buildPresenceStanza().to(jid).ofType(type).build());
    }
    @Override public void close() { roster.removeSubscribeListener(subscriptions); connection.removeAsyncStanzaListener(cancellations); }
}
