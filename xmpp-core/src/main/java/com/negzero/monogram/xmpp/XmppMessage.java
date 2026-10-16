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
    public final FileLink attachment;
    public final FileShare sharing;
    public final String caption;
    public final ReplyReference reply;
    public final MessageCorrection correction;
    public final boolean encrypted, readable, outgoing, archived;
    public final long timestamp;

    private XmppMessage(EntityBareJid peer, String sender, Map<String, String> ids, String body, boolean encrypted,
                        boolean readable, boolean outgoing, boolean archived, long timestamp, FileLink attachment, ReplyReference reply, MessageCorrection correction, FileShare sharing, String caption) {
        this.peer = peer; this.sender = sender; this.ids = Collections.unmodifiableMap(ids); this.body = body;
        this.encrypted = encrypted; this.readable = readable; this.outgoing = outgoing;
        this.archived = archived; this.timestamp = timestamp; this.attachment = attachment; this.reply = reply; this.correction = correction;
        this.sharing = sharing; this.caption = caption;
    }

    static XmppMessage from(Message message, EntityBareJid own, String body, boolean encrypted,
                            boolean readable, String archiveId, Date archiveTime) {
        if (message.getType() != Message.Type.chat && message.getType() != Message.Type.normal) return null;
        // Room private messages need occupant/session identity, not this direct-chat bare-JID model.
        if (message.hasExtension("x", "http://jabber.org/protocol/muc#user")) return null;
        EntityBareJid from = message.getFrom() == null ? null : message.getFrom().asEntityBareJidIfPossible();
        EntityBareJid to = message.getTo() == null ? null : message.getTo().asEntityBareJidIfPossible();
        if (from == null || (!own.equals(from) && to != null && !own.equals(to))) return null;
        boolean outgoing = own.equals(from);
        EntityBareJid peer = outgoing ? to : from;
        FileShare sharing = !encrypted && readable ? FileShare.from(message) : null;
        if (sharing != null && sharing.link.encrypted()) sharing = null;
        if (peer == null || (readable && body == null && sharing == null)) return null;
        if (sharing != null && body == null) body = "";
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
        ReplyReference reply = ReplyReference.from(message, readable ? body : null);
        FileLink attachment = null;
        String mediaText = reply == null ? body : reply.withoutFallback(body);
        if (readable && body != null) {
            String first = mediaText.split("\n", -1)[0];
            boolean mediaBody = encrypted && (mediaText.equals(first) || validThumbnail(mediaText.substring(Math.min(mediaText.length(), first.length() + 1))));
            org.jivesoftware.smack.packet.ExtensionElement oob = message.getExtension("x", "jabber:x:oob");
            if (!encrypted && oob instanceof org.jivesoftware.smack.packet.StandardExtensionElement) {
                org.jivesoftware.smack.packet.StandardExtensionElement url = ((org.jivesoftware.smack.packet.StandardExtensionElement) oob).getFirstElement("url");
                mediaBody = url != null && mediaText.equals(url.getText());
            }
            if (!encrypted && message.hasExtension("file-sharing", FileShare.NAMESPACE)) mediaBody = false;
            if (mediaBody) {
                try {
                    FileLink parsed = FileLink.parse(first);
                    if (parsed.encrypted() == encrypted) attachment = parsed;
                } catch (java.io.IOException ignored) { /* Keep malformed references as text, without opening them. */ }
            }
        }
        String caption = "";
        if (sharing != null) { attachment = sharing.link; caption = sharing.caption(message, body, reply); }
        Date delay = archiveTime != null ? archiveTime : DelayInformationManager.getDelayTimestamp(message);
        return new XmppMessage(peer, message.getFrom().toString(), ids, body, encrypted, readable, outgoing, archiveId != null,
                delay == null ? System.currentTimeMillis() : delay.getTime(), attachment, reply, MessageCorrection.from(message), sharing, caption);
    }

    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, boolean encrypted) throws Exception {
        return sent(own, peer, id, body, encrypted, null);
    }
    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, boolean encrypted, ReplyReference reply) throws Exception {
        return sent(own, peer, id, body, encrypted, reply, null);
    }
    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, boolean encrypted, ReplyReference reply, MessageCorrection correction) throws Exception {
        Message message = new Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(peer), Message.Type.chat);
        message.setFrom(own); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        if (reply != null) { reply.addTo(message); body = reply.wireBody(body); }
        if (correction != null) correction.addTo(message);
        return from(message, own.asEntityBareJidIfPossible(), body, encrypted, true, null, null);
    }
    public static XmppMessage sentFile(org.jxmpp.jid.Jid own, String peer, String id, FileLink link, boolean encrypted) throws Exception {
        return sentFile(own, peer, id, link, encrypted, null);
    }
    public static XmppMessage sentFile(org.jxmpp.jid.Jid own, String peer, String id, FileLink link, boolean encrypted, ReplyReference reply) throws Exception {
        Message message = new Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(peer), Message.Type.chat);
        message.setFrom(own); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        if (reply != null) reply.addTo(message);
        if (!encrypted) message.addExtension(org.jivesoftware.smack.packet.StandardExtensionElement.builder("x", "jabber:x:oob").addElement("url", link.wireUrl).build());
        return from(message, own.asEntityBareJidIfPossible(), reply == null ? link.wireUrl : reply.wireBody(link.wireUrl), encrypted, true, null, null);
    }
    public static XmppMessage sentFile(org.jxmpp.jid.Jid own, String peer, String id, FileShare sharing, String caption, ReplyReference reply) throws Exception {
        if (sharing.link.encrypted()) throw new IllegalArgumentException("Encrypted metadata requires a content envelope");
        Message message = new Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(peer), Message.Type.chat);
        message.setFrom(own); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        String body = sharing.body(caption);
        if (reply != null) { reply.addTo(message); body = reply.wireBody(body); }
        sharing.addTo(message, body);
        return from(message, own.asEntityBareJidIfPossible(), body, false, true, null, null);
    }

    private static boolean validThumbnail(String value) {
        if (value.length() > 16384 || value.indexOf('\n') >= 0) return false;
        String prefix = value.startsWith("data:image/jpeg;base64,") ? "data:image/jpeg;base64," : "data:image/jpeg,";
        if (!value.startsWith(prefix)) return false;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(value.substring(prefix.length()));
            return bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                    && (bytes[bytes.length - 2] & 255) == 255 && (bytes[bytes.length - 1] & 255) == 217;
        } catch (IllegalArgumentException e) { return false; }
    }
    private static void put(Map<String, String> ids, String kind, String id) {
        if (id != null && !id.isEmpty()) ids.put(kind, id);
    }
}
