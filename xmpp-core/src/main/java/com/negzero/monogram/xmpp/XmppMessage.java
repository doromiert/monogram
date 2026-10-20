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
    public enum Encryption { PLAIN, LEGACY_OMEMO, OMEMO2 }
    public final EntityBareJid peer;
    public final String sender;
    public final Map<String, String> ids;
    public final String body;
    public final FileLink attachment;
    public final FileShare sharing;
    /** Unauthenticated legacy presentation only; never pack identity or attachment metadata. */
    public final boolean displayStickerHint;
    public final String caption;
    public final MessageMarkup markup,captionMarkup;
    public final InlineEmoji inlineEmoji;
    public final ReplyReference reply;
    public final MessageCorrection correction;
    public final MessageReactions reactions;
    public final MessageRetraction retraction;
    public final String senderFingerprint;
    public final String markableId;
    public final boolean encrypted, readable, outgoing, archived;
    public final Encryption encryption;
    public final long timestamp;

    private XmppMessage(EntityBareJid peer, String sender, Map<String, String> ids, String body, Encryption encryption,
                        boolean readable, boolean outgoing, boolean archived, long timestamp, FileLink attachment, ReplyReference reply, MessageCorrection correction, FileShare sharing, String caption, MessageReactions reactions, MessageRetraction retraction, String senderFingerprint, String markableId, MessageMarkup markup, MessageMarkup captionMarkup, InlineEmoji inlineEmoji,boolean displayStickerHint) {
        this.peer = peer; this.sender = sender; this.ids = Collections.unmodifiableMap(ids); this.body = body;
        this.encryption = encryption; this.encrypted = encryption != Encryption.PLAIN;
        this.readable = readable; this.outgoing = outgoing;
        this.archived = archived; this.timestamp = timestamp; this.attachment = attachment; this.reply = reply; this.correction = correction;
        this.sharing = sharing; this.caption = caption; this.reactions = reactions; this.retraction=retraction; this.senderFingerprint=senderFingerprint;
        this.markableId=markableId;this.markup=markup;this.captionMarkup=captionMarkup;this.inlineEmoji=inlineEmoji;
        this.displayStickerHint = displayStickerHint && readable && attachment != null && attachment.encrypted() && encryption == Encryption.LEGACY_OMEMO;
    }

    static XmppMessage from(Message message, EntityBareJid own, String body, boolean encrypted,
                            boolean readable, String archiveId, Date archiveTime) {
        // Legacy OMEMO only encrypts the fallback body, not its public reaction metadata.
        if (encrypted && MessageReactions.publicLegacy(message))
            return from(message,message,own,null,Encryption.PLAIN,true,archiveId,archiveTime);
        return from(message,message,own,body,encrypted ? Encryption.LEGACY_OMEMO : Encryption.PLAIN,readable,archiveId,archiveTime);
    }
    /** Only content authenticated by the OMEMO2/SCE decoder may supply private metadata. */
    static XmppMessage modern(Message outer, Message authenticated, EntityBareJid own, String archiveId, Date archiveTime) {
        if (authenticated != null && (!java.util.Objects.equals(outer.getFrom(),authenticated.getFrom())
                || !java.util.Objects.equals(outer.getTo(),authenticated.getTo()) || outer.getType() != authenticated.getType()))
            throw new IllegalArgumentException("Protected routing does not match its stanza");
        XmppMessage message=from(outer,authenticated,own,authenticated == null ? null : authenticated.getBody(),Encryption.OMEMO2,
                authenticated != null,archiveId,archiveTime);
        if(message!=null) try {
            Map<String,String> ids=new LinkedHashMap<>(message.ids);
            ids.put("omemo2-cipher",XmppSession.hex(Omemo2IncomingJournal.envelope(outer).digest));
            message=message.canonical(ids,message.timestamp,message.archived,message.markableId);
        } catch(Exception malformedEnvelope) { /* Preserve the existing unreadable placeholder for unsupported ciphertext. */ }
        return message;
    }
    XmppMessage withAliases(Map<String,String> aliases) { return canonical(aliases,timestamp,archived,markableId); }
    XmppMessage canonical(Map<String,String> ids,long timestamp,boolean archived,String markableId) {
        return new XmppMessage(peer,sender,new LinkedHashMap<>(ids),body,encryption,readable,outgoing,archived,timestamp,
                attachment,reply,correction,sharing,caption,reactions,retraction,senderFingerprint,markableId,markup,captionMarkup,inlineEmoji,displayStickerHint);
    }
    XmppMessage archived(String archiveId,Date time) {
        if(archiveId==null && time==null) return this;
        Map<String,String> merged=new LinkedHashMap<>(ids);
        String cipher=merged.remove("omemo2-cipher");
        if(archiveId!=null) { merged.put("archive",archiveId);merged.put("mam",archiveId); }
        if(cipher!=null) merged.put("omemo2-cipher",cipher);
        return canonical(merged,time==null ? timestamp : time.getTime(),archived || archiveId!=null,markableId);
    }
    private static XmppMessage from(Message message, Message content, EntityBareJid own, String body, Encryption encryption,
                            boolean readable, String archiveId, Date archiveTime) {
        boolean encrypted = encryption != Encryption.PLAIN;
        if (message.getType() != Message.Type.chat && message.getType() != Message.Type.normal) return null;
        // Room private messages need occupant/session identity, not this direct-chat bare-JID model.
        if (message.hasExtension("x", "http://jabber.org/protocol/muc#user")) return null;
        EntityBareJid from = message.getFrom() == null ? null : message.getFrom().asEntityBareJidIfPossible();
        EntityBareJid to = message.getTo() == null ? null : message.getTo().asEntityBareJidIfPossible();
        if (from == null || (!own.equals(from) && to != null && !own.equals(to))) return null;
        boolean outgoing = own.equals(from);
        EntityBareJid peer = outgoing ? to : from;
        MessageRetraction retraction;
        try { retraction=content==null ? null : MessageRetraction.from(content); }
        catch(IllegalArgumentException invalid) { return null; }
        if(retraction!=null) {
            body=null;
        }
        FileShare sharing = encryption != Encryption.LEGACY_OMEMO && readable ? FileShare.from(content) : null;
        if (sharing != null && sharing.link.encrypted() != encrypted) sharing = null;
        MessageReactions reactions = readable && encryption != Encryption.LEGACY_OMEMO && content != null ? MessageReactions.from(content) : null;
        if (content != null && content.hasExtension("reactions", MessageReactions.NAMESPACE) && reactions == null) return null;
        if (reactions != null) {
            if (!MessageReactions.fallback(content,body) || !MessageReactions.replyMatches(content,body,reactions.id)
                    || sharing != null || content.hasExtension("file-sharing",FileShare.NAMESPACE)
                    || MessageCorrection.from(content) != null || retraction != null) return null;
            body=null;
        }
        if (peer == null || (readable && body == null && sharing == null && reactions == null && retraction == null)) return null;
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
        put(ids,XmppReadSync.LOCAL_ID,XmppReadSync.assignedId(message,own));
        if (archiveId != null) { put(ids, "archive", archiveId);put(ids,"mam",archiveId); }
        if (ids.isEmpty() && (encrypted || reactions!=null)) ids.put("local", opaqueId(message));
        ReplyReference reply = content == null || reactions != null ? null : ReplyReference.from(content, readable ? body : null);
        FileLink attachment = null;
        String mediaText = reply == null ? body : reply.withoutFallback(body);
        if (readable && body != null) {
            String first = mediaText.split("\n", -1)[0];
            boolean mediaBody = encrypted && (mediaText.equals(first) || validThumbnail(mediaText.substring(Math.min(mediaText.length(), first.length() + 1))));
            org.jivesoftware.smack.packet.ExtensionElement oob = content.getExtension("x", "jabber:x:oob");
            if (!encrypted && oob instanceof org.jivesoftware.smack.packet.StandardExtensionElement) {
                org.jivesoftware.smack.packet.StandardExtensionElement url = ((org.jivesoftware.smack.packet.StandardExtensionElement) oob).getFirstElement("url");
                mediaBody = url != null && mediaText.equals(url.getText());
            }
            if (content.hasExtension("file-sharing", FileShare.NAMESPACE) && encryption != Encryption.LEGACY_OMEMO) mediaBody = false;
            if (mediaBody) {
                try {
                    FileLink parsed = FileLink.parse(first);
                    if (parsed.encrypted() == encrypted) attachment = parsed;
                } catch (java.io.IOException ignored) { /* Keep malformed references as text, without opening them. */ }
            }
        }
        String caption = "";
        if (sharing != null) { attachment = sharing.link; caption = sharing.caption(content, body, reply); }
        MessageMarkup markup=readable ? encryption==Encryption.LEGACY_OMEMO ? MessageMarkup.body(body) : MessageMarkup.from(content,body) : null;
        MessageMarkup captionMarkup=sharing==null || markup==null ? null : sharing.captionMarkup(content,body,reply,markup);
        InlineEmoji inlineEmoji=readable && encryption!=Encryption.LEGACY_OMEMO && reactions==null && retraction==null ? InlineEmoji.from(content,body) : null;
        boolean displayStickerHint = StickerHint.from(message, encryption == Encryption.LEGACY_OMEMO, readable, attachment);
        Date delay = archiveTime != null ? archiveTime : DelayInformationManager.getDelayTimestamp(message);
        return new XmppMessage(peer, message.getFrom().toString(), ids, body, encryption, readable, outgoing, archiveId != null,
                delay == null ? System.currentTimeMillis() : delay.getTime(), attachment, reply, content == null ? null : MessageCorrection.from(content), sharing, caption, reactions, retraction, null,
                !outgoing && reactions==null && retraction==null ? MessageReceipt.markableId(message) : null,markup,captionMarkup,inlineEmoji,displayStickerHint);
    }

    public XmppMessage withSenderFingerprint(String fingerprint) {
        return new XmppMessage(peer,sender,new LinkedHashMap<>(ids),body,encryption,readable,outgoing,archived,timestamp,
                attachment,reply,correction,sharing,caption,reactions,retraction,fingerprint,markableId,markup,captionMarkup,inlineEmoji,displayStickerHint);
    }
    public XmppMessage withDisplayStickerHint(boolean value) {
        return new XmppMessage(peer,sender,new LinkedHashMap<>(ids),body,encryption,readable,outgoing,archived,timestamp,attachment,reply,correction,sharing,caption,reactions,retraction,senderFingerprint,markableId,markup,captionMarkup,inlineEmoji,value);
    }
    public XmppMessage withInlineEmoji(InlineEmoji value) {
        if(value==null) return this;
        InlineEmoji full=value;
        if(!body.equals(value.body)) {
            if(!body.endsWith(value.body)) throw new IllegalArgumentException("Emoji belong to another body");
            full=value.inBody(body,body.codePointCount(0,body.length()-value.body.length()));
        }
        MessageMarkup localMarkup=outgoing && encryption==Encryption.LEGACY_OMEMO && markup!=null ? markup.preservingBody() : markup;
        return new XmppMessage(peer,sender,new LinkedHashMap<>(ids),body,encryption,readable,outgoing,archived,timestamp,
                attachment,reply,correction,sharing,caption,reactions,retraction,senderFingerprint,markableId,localMarkup,captionMarkup,full,displayStickerHint);
    }
    public XmppMessage withMarkup(MessageMarkup value) {
        if(value==null) return this;
        MessageMarkup full=value;
        if(!body.equals(value.body)) {
            String payload=sharing!=null && value.body.equals(caption) ? sharing.body(value.body) : value.body;
            if(!body.endsWith(payload)) throw new IllegalArgumentException("Markup belongs to another body");
            full=value.inBody(body,body.codePointCount(0,body.length()-payload.length()));
        }
        if(encryption==Encryption.LEGACY_OMEMO) full=MessageMarkup.body(body).withMentions(full.mentions);
        MessageMarkup captionValue=null;
        if(sharing!=null) {
            String payload=sharing.body(caption);
            if(!body.endsWith(payload)) throw new IllegalArgumentException("Caption belongs to another body");
            int start=body.codePointCount(0,body.length()-payload.length());
            captionValue=full.slice(start,start+caption.codePointCount(0,caption.length()));
        }
        return new XmppMessage(peer,sender,new LinkedHashMap<>(ids),body,encryption,readable,outgoing,archived,timestamp,
                attachment,reply,correction,sharing,caption,reactions,retraction,senderFingerprint,markableId,full,
                captionValue,inlineEmoji,displayStickerHint);
    }
    static String opaqueId(Message message) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(message.toXML().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, boolean encrypted) throws Exception {
        return sent(own, peer, id, body, encrypted, null);
    }
    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, boolean encrypted, ReplyReference reply) throws Exception {
        return sent(own, peer, id, body, encrypted, reply, null);
    }
    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, boolean encrypted, ReplyReference reply, MessageCorrection correction) throws Exception {
        return sent(own,peer,id,body,encrypted ? Encryption.LEGACY_OMEMO : Encryption.PLAIN,reply,correction);
    }
    public static XmppMessage sent(org.jxmpp.jid.Jid own, String peer, String id, String body, Encryption encryption, ReplyReference reply, MessageCorrection correction) throws Exception {
        Message message = new Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(peer), Message.Type.chat);
        message.setFrom(own); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        if (reply != null) { reply.addTo(message); body = reply.wireBody(body); }
        if (correction != null) correction.addTo(message);
        message.setBody(body);
        return encryption == Encryption.OMEMO2 ? modern(message,message,own.asEntityBareJidIfPossible(),null,null)
                : from(message, own.asEntityBareJidIfPossible(), body, encryption != Encryption.PLAIN, true, null, null);
    }
    public static XmppMessage sentReactions(org.jxmpp.jid.Jid own,String peer,String id,MessageReactions reactions,Encryption encryption) throws Exception {
        if(encryption==Encryption.LEGACY_OMEMO) throw new IllegalArgumentException("Legacy OMEMO cannot protect reactions");
        Message message=new Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(peer),Message.Type.chat);
        message.setFrom(own); message.setStanzaId(id); message.addExtension(new OriginIdElement(id)); reactions.addTo(message);
        return encryption==Encryption.OMEMO2 ? modern(message,message,own.asEntityBareJidIfPossible(),null,null)
                : from(message,own.asEntityBareJidIfPossible(),null,false,true,null,null);
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
        return sentFile(own,peer,id,sharing,caption,reply,null);
    }
    public static XmppMessage sentFile(org.jxmpp.jid.Jid own, String peer, String id, FileShare sharing, String caption, ReplyReference reply, MessageCorrection correction) throws Exception {
        Message message = new Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(peer), Message.Type.chat);
        message.setFrom(own); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        String body = sharing.body(caption);
        if (reply != null) { reply.addTo(message); body = reply.wireBody(body); }
        if (correction != null) correction.addTo(message);
        sharing.addTo(message, body);
        message.setBody(body);
        return sharing.link.encrypted() ? modern(message,message,own.asEntityBareJidIfPossible(),null,null)
                : from(message, own.asEntityBareJidIfPossible(), body, false, true, null, null);
    }

    static boolean validThumbnail(String value) {
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
