// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.delay.DelayInformationManager;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.jxmpp.jid.EntityBareJid;

/** One direct message, independent of whether it arrived live, via carbons or MAM. */
public final class XmppMessage {
    public final EntityBareJid peer;
    public final String sender;
    public final Map<String, String> ids;
    public final String body;
    public final boolean encrypted, readable, outgoing, archived;
    public final long timestamp;

    private XmppMessage(EntityBareJid peer, String sender, Map<String, String> ids, String body, boolean encrypted,
                        boolean readable, boolean outgoing, boolean archived, long timestamp) {
        this.peer = peer; this.sender = sender; this.ids = Collections.unmodifiableMap(ids); this.body = body;
        this.encrypted = encrypted; this.readable = readable; this.outgoing = outgoing;
        this.archived = archived; this.timestamp = timestamp;
    }

    static XmppMessage from(Message message, EntityBareJid own, String body, boolean encrypted,
                            boolean readable, String archiveId, Date archiveTime) {
        if (message.getType() != Message.Type.chat && message.getType() != Message.Type.normal) return null;
        EntityBareJid from = message.getFrom() == null ? null : message.getFrom().asEntityBareJidIfPossible();
        EntityBareJid to = message.getTo() == null ? null : message.getTo().asEntityBareJidIfPossible();
        if (from == null || (!own.equals(from) && to != null && !own.equals(to))) return null;
        boolean outgoing = own.equals(from);
        EntityBareJid peer = outgoing ? to : from;
        if (peer == null || (readable && body == null)) return null;
        Map<String, String> ids = new LinkedHashMap<>();
        put(ids, "stanza", message.getStanzaId());
        OriginIdElement origin = OriginIdElement.getOriginId(message);
        if (origin != null) put(ids, "origin", origin.getId());
        // Only our account's server can assert an ID in our direct-message archive.
        for (org.jivesoftware.smack.packet.ExtensionElement extension : message.getExtensions(StanzaIdElement.ELEMENT, StanzaIdElement.NAMESPACE)) {
            if (!(extension instanceof StanzaIdElement)) continue;
            StanzaIdElement id = (StanzaIdElement) extension;
            if (own.toString().equals(id.getBy())) put(ids, "archive", id.getId());
        }
        if (archiveId != null) put(ids, "archive", archiveId);
        Date delay = archiveTime != null ? archiveTime : DelayInformationManager.getDelayTimestamp(message);
        return new XmppMessage(peer, message.getFrom().toString(), ids, body, encrypted, readable, outgoing, archiveId != null,
                delay == null ? System.currentTimeMillis() : delay.getTime());
    }

    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, boolean encrypted) throws Exception {
        Message message = new Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(peer), Message.Type.chat);
        message.setFrom(own); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        return from(message, own.asEntityBareJidIfPossible(), body, encrypted, true, null, null);
    }

    private static void put(Map<String, String> ids, String kind, String id) {
        if (id != null && !id.isEmpty()) ids.put(kind, id);
    }
}
