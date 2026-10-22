// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.function.BiConsumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.roster.*;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

/** Standard roster/presence authorization. Receiving a request never grants it automatically. */
public final class XmppContacts implements AutoCloseable {
    public enum Action { REQUEST, ACCEPT, DENY, CANCEL, REVOKE }
    public static final class State {
        public final boolean listed,seesTheirPresence,sharesOwnPresence,outgoingPending;
        State(RosterEntry entry) {
            listed=entry!=null; seesTheirPresence=listed && entry.canSeeHisPresence();
            sharesOwnPresence=listed && entry.canSeeMyPresence(); outgoingPending=listed && entry.isSubscriptionPending();
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
    private final SubscribeListener subscriptions;
    private final StanzaListener cancellations;
    XmppContacts(XMPPConnection connection,XmppRooms rooms,BiConsumer<String,Boolean> incoming) {
        this.connection=connection; this.rooms=rooms; this.incoming=incoming; roster=Roster.getInstanceFor(connection);
        org.jivesoftware.smackx.iqlast.LastActivityManager.getInstanceFor(connection).disable();
        subscriptions=(from,presence) -> {
            if(connection.getUser()!=null && from.isEntityBareJid() && !rooms.known(from) && !from.equals(connection.getUser().asBareJid())) incoming.accept(from.toString(),true);
            return null;
        };
        roster.addSubscribeListener(subscriptions);
        cancellations=stanza -> {
            if(stanza.getFrom()!=null && stanza.getFrom().isEntityBareJid() && !rooms.known(stanza.getFrom())) incoming.accept(stanza.getFrom().toString(),false);
        };
        connection.addAsyncStanzaListener(cancellations,stanza -> stanza instanceof Presence && ((Presence)stanza).getType()==Presence.Type.unsubscribe);
    }
    private EntityBareJid contact(String address) throws Exception {
        org.jxmpp.jid.Jid jid=JidCreate.from(address);
        if(!jid.isEntityBareJid() || rooms.known(jid) || connection.getUser()==null || jid.equals(connection.getUser().asBareJid()))
            throw new IllegalArgumentException("A contact's bare XMPP address is required");
        return jid.asEntityBareJidIfPossible();
    }
    public State state(String address) throws Exception { return new State(roster.getEntry(contact(address))); }
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
            case ACCEPT:
                ensureEntry(jid); presence(jid,Presence.Type.subscribed); incoming.accept(jid.toString(),false);
                RosterUtil.askForSubscriptionIfRequired(roster,jid); break;
            case DENY: case REVOKE:
                presence(jid,Presence.Type.unsubscribed); incoming.accept(jid.toString(),false); break;
            case CANCEL: presence(jid,Presence.Type.unsubscribe); break;
        }
    }
    private void presence(EntityBareJid jid,Presence.Type type) throws Exception {
        connection.sendStanza(connection.getStanzaFactory().buildPresenceStanza().to(jid).ofType(type).build());
    }
    @Override public void close() { roster.removeSubscribeListener(subscriptions); connection.removeAsyncStanzaListener(cancellations); }
}
