// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.delay.DelayInformationManager;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jivesoftware.smackx.sid.element.*;
import org.jxmpp.jid.*;

/** Room senders are full occupant addresses, never direct-chat contacts. */
public final class XmppRoomMessage {
    public final EntityBareJid room;
    public final String sender, body, realSender, occupantId;
    public final MessageReactions reactions;
    public final MessageRetraction retraction;
    public final String senderFingerprint;
    public final ReplyReference reply;
    public final MessageCorrection correction;
    public final boolean realSenderVerified;
    public final FileLink attachment;
    public final FileShare sharing;
    public final String caption;
    public final MessageMarkup markup,captionMarkup;
    public final InlineEmoji inlineEmoji;
    public final Map<String,String> ids;
    public final Map<String,String> skippedRecipients;
    public final boolean outgoing, encrypted, readable, archived;
    public final XmppMessage.Encryption encryption;
    public final long timestamp;
    private XmppRoomMessage(EntityBareJid room,String sender,String body,Map<String,String> ids,
                            boolean outgoing,boolean encrypted,boolean readable,long timestamp,boolean modern,boolean archived,String realSender,MessageReactions reactions,String occupantId,FileLink attachment,FileShare sharing,String caption,ReplyReference reply,MessageCorrection correction,boolean realSenderVerified,Map<String,String> skipped,MessageRetraction retraction,String senderFingerprint,MessageMarkup markup,MessageMarkup captionMarkup,InlineEmoji inlineEmoji) {
        this.inlineEmoji=inlineEmoji;this.markup=markup;this.captionMarkup=captionMarkup;this.retraction=retraction; this.senderFingerprint=senderFingerprint; this.skippedRecipients=Collections.unmodifiableMap(new TreeMap<>(skipped));
        this.reply=reply; this.correction=correction; this.realSenderVerified=realSenderVerified; this.realSender=realSender; this.reactions=reactions; this.occupantId=occupantId; this.attachment=attachment; this.sharing=sharing; this.caption=caption;
        this.room=room; this.sender=sender; this.body=body; this.ids=Collections.unmodifiableMap(ids);
        this.encryption=modern ? XmppMessage.Encryption.OMEMO2 : encrypted ? XmppMessage.Encryption.LEGACY_OMEMO : XmppMessage.Encryption.PLAIN;
        this.archived=archived; this.outgoing=outgoing; this.encrypted=encrypted; this.readable=readable; this.timestamp=timestamp;
    }
    XmppRoomMessage withAliases(Map<String,String> aliases) { return canonical(aliases,timestamp,archived,outgoing,realSender,realSenderVerified,occupantId); }
    XmppRoomMessage canonical(Map<String,String> ids,long timestamp,boolean archived,boolean outgoing,String realSender,boolean verified,String occupantId) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),outgoing,encrypted,readable,timestamp,
                encryption==XmppMessage.Encryption.OMEMO2,archived,realSender,reactions,occupantId,attachment,sharing,caption,
                reply,correction,verified,skippedRecipients,retraction,senderFingerprint,markup,captionMarkup,inlineEmoji);
    }
    public static XmppRoomMessage from(Message wire,EntityBareJid room,EntityBareJid own,String nickname,
                                       String body,boolean encrypted,boolean readable) {
        return archived(wire,room,own,nickname,body,encrypted,readable,null,null);
    }
    static XmppRoomMessage archived(Message wire,EntityBareJid room,EntityBareJid own,String nickname,
                                       String body,boolean encrypted,boolean readable,String archiveId,Date archiveTime) {
        if (wire.getType()!=Message.Type.groupchat || wire.getFrom()==null || !room.equals(wire.getFrom().asBareJid())
                || !wire.getFrom().hasResource() || wire.getTo()!=null && !own.equals(wire.getTo().asEntityBareJidIfPossible()) && !(archiveTime!=null && room.equals(wire.getTo().asEntityBareJidIfPossible()))) return null;
        if (encrypted && MessageReactions.publicLegacy(wire)) { encrypted=false; readable=true; body=null; }
        MessageRetraction retraction;
        try { retraction=MessageRetraction.from(wire); } catch(IllegalArgumentException invalid) { return null; }
        if(retraction!=null) body=null;
        MessageReactions reactions=!encrypted && readable ? MessageReactions.from(wire) : null;
        ReplyReference reply=ReplyReference.from(wire,readable ? body : null);
        MessageCorrection correction=readable ? MessageCorrection.from(wire) : null;
        if(wire.hasExtension("reactions",MessageReactions.NAMESPACE)) {
            if(reactions==null || !MessageReactions.fallback(wire,body) || !MessageReactions.replyMatches(wire,body,reactions.id)
                    || correction!=null || retraction!=null || wire.hasExtension("file-sharing",FileShare.NAMESPACE)) return null;
            body=null; reply=null;
        }
        FileShare sharing=!encrypted && readable ? FileShare.from(wire) : null;
        if(sharing!=null && sharing.link.encrypted()) sharing=null;
        if(readable && body==null && reactions==null && sharing==null && retraction==null) return null;
        if(sharing!=null && body==null) body="";
        FileLink attachment=sharing==null ? null : sharing.link;
        String mediaBody=reply==null ? body : reply.withoutFallback(body);
        if(attachment==null && readable && mediaBody!=null) {
            String first=mediaBody.split("\n",-1)[0];
            boolean file=encrypted && (mediaBody.equals(first) || XmppMessage.validThumbnail(mediaBody.substring(Math.min(mediaBody.length(),first.length()+1))));
            org.jivesoftware.smack.packet.ExtensionElement oob=wire.getExtension("x","jabber:x:oob");
            if(!encrypted && oob instanceof org.jivesoftware.smack.packet.StandardExtensionElement) {
                org.jivesoftware.smack.packet.StandardExtensionElement url=((org.jivesoftware.smack.packet.StandardExtensionElement)oob).getFirstElement("url");
                file=url!=null && mediaBody.equals(url.getText());
            }
            if(file) try { FileLink parsed=FileLink.parse(first); if(parsed.encrypted()==encrypted) attachment=parsed; }
                catch(java.io.IOException invalid) { /* Malformed attachment references stay visible as text. */ }
        }
        String caption=sharing==null ? "" : sharing.caption(wire,body,reply);
        MessageMarkup markup=readable ? encrypted ? MessageMarkup.body(body) : MessageMarkup.from(wire,body) : null;
        MessageMarkup captionMarkup=sharing==null || markup==null ? null : sharing.captionMarkup(wire,body,reply,markup);
        InlineEmoji inlineEmoji=!encrypted && readable && reactions==null && retraction==null ? InlineEmoji.from(wire,body) : null;
        Date delay=archiveTime!=null ? archiveTime : DelayInformationManager.getDelayTimestamp(wire);
        MUCUser user=MUCUser.from(wire);
        // Live message extensions can be sender-supplied; only MAM rewrites this item authoritatively.
        Jid real=archiveTime==null || user==null || user.getItem()==null ? null : user.getItem().getJid();
        boolean liveSelf=delay==null && wire.getFrom().getResourceOrNull().toString().equals(nickname);
        boolean outgoing=real!=null ? own.equals(real.asBareJid()) : liveSelf;
        Map<String,String> ids=new LinkedHashMap<>();
        if (wire.getStanzaId()!=null) ids.put("stanza",wire.getStanzaId());
        OriginIdElement origin=OriginIdElement.getOriginId(wire);
        if (origin!=null) ids.put("origin",origin.getId());
        for (org.jivesoftware.smack.packet.ExtensionElement extension:wire.getExtensions(StanzaIdElement.ELEMENT,StanzaIdElement.NAMESPACE)) {
            if (extension instanceof StanzaIdElement && room.toString().equals(((StanzaIdElement)extension).getBy()))
                ids.put("archive",((StanzaIdElement)extension).getId());
        }
        if(archiveId!=null) ids.put("mam",archiveId);
        if (ids.isEmpty()) ids.put("local",encrypted || reactions!=null ? XmppMessage.opaqueId(wire) : UUID.randomUUID().toString());
        return new XmppRoomMessage(room,wire.getFrom().toString(),body,ids,outgoing,encrypted,readable,
                delay==null ? System.currentTimeMillis() : delay.getTime(),wire.hasExtension("encrypted",XmppOmemo2.NS),archiveTime!=null,real==null ? null : real.asBareJid().toString(),reactions,null,attachment,sharing,caption,reply,correction,real!=null,Collections.emptyMap(),retraction,null,markup,captionMarkup,inlineEmoji);
    }
    public XmppRoomMessage withInlineEmoji(InlineEmoji value) {
        if(value==null) return this;
        InlineEmoji full=value;
        if(!body.equals(value.body)) {
            if(!body.endsWith(value.body)) throw new IllegalArgumentException("Emoji belong to another body");
            full=value.inBody(body,body.codePointCount(0,body.length()-value.body.length()));
        }
        MessageMarkup localMarkup=outgoing && encryption==XmppMessage.Encryption.LEGACY_OMEMO && markup!=null ? markup.preservingBody() : markup;
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),outgoing,encrypted,readable,timestamp,
                encryption==XmppMessage.Encryption.OMEMO2,archived,realSender,reactions,occupantId,attachment,sharing,caption,
                reply,correction,realSenderVerified,skippedRecipients,retraction,senderFingerprint,localMarkup,captionMarkup,full);
    }
    public XmppRoomMessage withMarkup(MessageMarkup value) {
        if(value==null) return this;
        MessageMarkup full=value;
        if(!body.equals(value.body)) {
            String payload=sharing!=null && value.body.equals(caption) ? sharing.body(value.body) : value.body;
            if(!body.endsWith(payload)) throw new IllegalArgumentException("Markup belongs to another body");
            full=value.inBody(body,body.codePointCount(0,body.length()-payload.length()));
        }
        if(encryption==XmppMessage.Encryption.LEGACY_OMEMO) full=MessageMarkup.body(body);
        MessageMarkup captionValue=null;
        if(sharing!=null) {
            String payload=sharing.body(caption);
            if(!body.endsWith(payload)) throw new IllegalArgumentException("Caption belongs to another body");
            int start=body.codePointCount(0,body.length()-payload.length());
            captionValue=full.slice(start,start+caption.codePointCount(0,caption.length()));
        }
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),outgoing,encrypted,readable,timestamp,
                encryption==XmppMessage.Encryption.OMEMO2,archived,realSender,reactions,occupantId,attachment,sharing,caption,reply,correction,realSenderVerified,skippedRecipients,retraction,senderFingerprint,full,captionValue,inlineEmoji);
    }
    public XmppRoomMessage withSenderFingerprint(String fingerprint) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),outgoing,encrypted,readable,timestamp,
                encryption==XmppMessage.Encryption.OMEMO2,archived,realSender,reactions,occupantId,attachment,sharing,caption,reply,correction,realSenderVerified,skippedRecipients,retraction,fingerprint,markup,captionMarkup,inlineEmoji);
    }
    XmppRoomMessage identified(String real,EntityBareJid own) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),own.toString().equals(real),encrypted,readable,
                timestamp,encryption==XmppMessage.Encryption.OMEMO2,archived,real,reactions,occupantId,attachment,sharing,caption,reply,correction,true,skippedRecipients,retraction,senderFingerprint,markup,captionMarkup,inlineEmoji);
    }
    XmppRoomMessage withOccupantId(String id) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),outgoing,encrypted,readable,timestamp,
                encryption==XmppMessage.Encryption.OMEMO2,archived,realSender,reactions,id,attachment,sharing,caption,reply,correction,realSenderVerified,skippedRecipients,retraction,senderFingerprint,markup,captionMarkup,inlineEmoji);
    }
    XmppRoomMessage withSkippedRecipients(Map<String,String> skipped) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),outgoing,encrypted,readable,timestamp,
                encryption==XmppMessage.Encryption.OMEMO2,archived,realSender,reactions,occupantId,attachment,sharing,caption,reply,correction,realSenderVerified,skipped,retraction,senderFingerprint,markup,captionMarkup,inlineEmoji);
    }
    static XmppRoomMessage sentReactions(EntityBareJid room,EntityBareJid own,String nickname,String id,MessageReactions reactions) throws Exception {
        Message message=new Message(own,Message.Type.groupchat);
        message.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nickname)); message.setStanzaId(id);
        message.addExtension(new OriginIdElement(id)); reactions.addTo(message);
        return from(message,room,own,nickname,null,false,true).identified(own.toString(),own);
    }
    static XmppRoomMessage sentFile(EntityBareJid room,EntityBareJid own,String nickname,String id,String body,boolean encrypted,FileShare sharing) throws Exception {
        return sentFile(room,own,nickname,id,body,encrypted,sharing,null,null);
    }
    static XmppRoomMessage sentFile(EntityBareJid room,EntityBareJid own,String nickname,String id,String body,boolean encrypted,FileShare sharing,ReplyReference reply,MessageCorrection correction) throws Exception {
        Message message=new Message(own,Message.Type.groupchat);
        message.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nickname)); message.setStanzaId(id);
        message.addExtension(new OriginIdElement(id)); if(sharing!=null) sharing.addTo(message,reply==null ? body : reply.wireBody(body));
        if(reply!=null) reply.addTo(message); if(correction!=null) correction.addTo(message);
        return from(message,room,own,nickname,reply==null ? body : reply.wireBody(body),encrypted,true).identified(own.toString(),own);
    }
    static XmppRoomMessage sent(EntityBareJid room,EntityBareJid own,String nickname,String id,String body,boolean encrypted) throws Exception {
        Message message=new Message(own,Message.Type.groupchat);
        message.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nickname));
        message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        return from(message,room,own,nickname,body,encrypted,true);
    }
}
