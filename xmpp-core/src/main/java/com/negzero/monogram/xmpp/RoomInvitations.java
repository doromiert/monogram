// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.impl.JidCreate;

/** Correlate late mediated-invitation errors without retaining message bodies. */
final class RoomInvitations {
    static final String NS="http://jabber.org/protocol/muc#user";
    static final class Pending {
        final EntityBareJid room,target;
        final BareJid account;
        final long time=System.nanoTime();
        Pending(EntityBareJid room,EntityBareJid target,BareJid account) { this.room=room;this.target=target;this.account=account; }
    }
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    synchronized void sent(Message message,EntityBareJid room,BareJid account) {
        if(message.getType()!=Message.Type.normal || !room.equals(message.getTo()) || !message.hasExtension("x",NS)) return;
        try {
            StandardExtensionElement x=StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(message.getExtension("x",NS).toXML().toString()));
            StandardExtensionElement invite=x.getFirstElement("invite",NS);
            if(invite==null || invite.getAttributeValue("to")==null) return;
            EntityBareJid target=JidCreate.entityBareFrom(invite.getAttributeValue("to"));
            if(message.getStanzaId()==null || message.getStanzaId().isEmpty()) message.setStanzaId("monogram-invite-"+java.util.UUID.randomUUID());
            expire();
            if(pending.size()>=256) pending.remove(pending.keySet().iterator().next());
            pending.put(message.getStanzaId(),new Pending(room,target,account));
        } catch(Exception malformed) { /* Do not prevent sending because an unrecognized extension cannot be tracked. */ }
    }
    synchronized Pending rejected(Message message) {
        expire();
        if(message.getType()!=Message.Type.error || message.getError()==null || message.getFrom()==null || !message.getFrom().isEntityBareJid()) return null;
        Pending invitation=pending.get(message.getStanzaId());
        if(invitation==null || !invitation.room.equals(message.getFrom())
                || message.getTo()!=null && !invitation.account.equals(message.getTo().asBareJid())) return null;
        pending.remove(message.getStanzaId());return invitation;
    }
    private void expire() { long now=System.nanoTime();pending.values().removeIf(value -> now-value.time>TimeUnit.MINUTES.toNanos(5)); }
    synchronized void clear() { pending.clear(); }
}
