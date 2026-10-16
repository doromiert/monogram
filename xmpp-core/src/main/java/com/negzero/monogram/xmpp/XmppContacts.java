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
    private final XMPPConnection connection;
    private final XmppRooms rooms;
    private final Roster roster;
    private final BiConsumer<String,Boolean> incoming;
    private final SubscribeListener subscriptions;
    private final StanzaListener cancellations;
    XmppContacts(XMPPConnection connection,XmppRooms rooms,BiConsumer<String,Boolean> incoming) {
        this.connection=connection; this.rooms=rooms; this.incoming=incoming; roster=Roster.getInstanceFor(connection);
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
            throw new IllegalArgumentException("A contact's bare Jabber address is required");
        return jid.asEntityBareJidIfPossible();
    }
    public State state(String address) throws Exception { return new State(roster.getEntry(contact(address))); }
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
