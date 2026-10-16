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
    public final FileLink attachment;
    public final FileShare sharing;
    public final String caption;
    public final Map<String,String> ids;
    public final boolean outgoing, encrypted, readable, archived;
    public final XmppMessage.Encryption encryption;
    public final long timestamp;
    private XmppRoomMessage(EntityBareJid room,String sender,String body,Map<String,String> ids,
                            boolean outgoing,boolean encrypted,boolean readable,long timestamp,boolean modern,boolean archived,String realSender,MessageReactions reactions,String occupantId,FileLink attachment,FileShare sharing,String caption) {
        this.realSender=realSender; this.reactions=reactions; this.occupantId=occupantId; this.attachment=attachment; this.sharing=sharing; this.caption=caption;
        this.room=room; this.sender=sender; this.body=body; this.ids=Collections.unmodifiableMap(ids);
        this.encryption=modern ? XmppMessage.Encryption.OMEMO2 : encrypted ? XmppMessage.Encryption.LEGACY_OMEMO : XmppMessage.Encryption.PLAIN;
        this.archived=archived; this.outgoing=outgoing; this.encrypted=encrypted; this.readable=readable; this.timestamp=timestamp;
    }
    public static XmppRoomMessage from(Message wire,EntityBareJid room,EntityBareJid own,String nickname,
                                       String body,boolean encrypted,boolean readable) {
        return archived(wire,room,own,nickname,body,encrypted,readable,null,null);
    }
    static XmppRoomMessage archived(Message wire,EntityBareJid room,EntityBareJid own,String nickname,
                                       String body,boolean encrypted,boolean readable,String archiveId,Date archiveTime) {
        if (wire.getType()!=Message.Type.groupchat || wire.getFrom()==null || !room.equals(wire.getFrom().asBareJid())
                || !wire.getFrom().hasResource() || wire.getTo()!=null && !own.equals(wire.getTo().asEntityBareJidIfPossible()) && !(archiveTime!=null && room.equals(wire.getTo().asEntityBareJidIfPossible()))) return null;
        MessageReactions reactions=!encrypted && readable ? MessageReactions.from(wire) : null;
        if(wire.hasExtension("reactions",MessageReactions.NAMESPACE) && (reactions==null || body!=null)) return null;
        FileShare sharing=!encrypted && readable ? FileShare.from(wire) : null;
        if(sharing!=null && sharing.link.encrypted()) sharing=null;
        if(readable && body==null && reactions==null && sharing==null) return null;
        if(sharing!=null && body==null) body="";
        FileLink attachment=sharing==null ? null : sharing.link;
        if(attachment==null && readable && body!=null) {
            String first=body.split("\n",-1)[0];
            boolean file=encrypted && (body.equals(first) || XmppMessage.validThumbnail(body.substring(Math.min(body.length(),first.length()+1))));
            org.jivesoftware.smack.packet.ExtensionElement oob=wire.getExtension("x","jabber:x:oob");
            if(!encrypted && oob instanceof org.jivesoftware.smack.packet.StandardExtensionElement) {
                org.jivesoftware.smack.packet.StandardExtensionElement url=((org.jivesoftware.smack.packet.StandardExtensionElement)oob).getFirstElement("url");
                file=url!=null && body.equals(url.getText());
            }
            if(file) try { FileLink parsed=FileLink.parse(first); if(parsed.encrypted()==encrypted) attachment=parsed; }
                catch(java.io.IOException invalid) { /* Malformed attachment references stay visible as text. */ }
        }
        String caption=sharing==null ? "" : sharing.caption(wire,body,null);
        Date delay=archiveTime!=null ? archiveTime : DelayInformationManager.getDelayTimestamp(wire);
        MUCUser user=MUCUser.from(wire);
        Jid real=user==null || user.getItem()==null ? null : user.getItem().getJid();
        boolean liveSelf=delay==null && wire.getFrom().getResourceOrNull().toString().equals(nickname);
        // A reflected message may contain a sender-supplied item claiming our account.
        if(real!=null && own.equals(real.asBareJid()) && !liveSelf) real=null;
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
        if (ids.isEmpty()) ids.put("local",UUID.randomUUID().toString());
        return new XmppRoomMessage(room,wire.getFrom().toString(),body,ids,outgoing,encrypted,readable,
                delay==null ? System.currentTimeMillis() : delay.getTime(),wire.hasExtension("encrypted",XmppOmemo2.NS),archiveTime!=null,real==null ? null : real.asBareJid().toString(),reactions,null,attachment,sharing,caption);
    }
    XmppRoomMessage identified(String real,EntityBareJid own) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),own.toString().equals(real),encrypted,readable,
                timestamp,encryption==XmppMessage.Encryption.OMEMO2,archived,real,reactions,occupantId,attachment,sharing,caption);
    }
    XmppRoomMessage withOccupantId(String id) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),outgoing,encrypted,readable,timestamp,
                encryption==XmppMessage.Encryption.OMEMO2,archived,realSender,reactions,id,attachment,sharing,caption);
    }
    static XmppRoomMessage sentReactions(EntityBareJid room,EntityBareJid own,String nickname,String id,MessageReactions reactions) throws Exception {
        Message message=new Message(own,Message.Type.groupchat);
        message.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nickname)); message.setStanzaId(id);
        message.addExtension(new OriginIdElement(id)); reactions.addTo(message);
        return from(message,room,own,nickname,null,false,true).identified(own.toString(),own);
    }
    static XmppRoomMessage sentFile(EntityBareJid room,EntityBareJid own,String nickname,String id,String body,boolean encrypted,FileShare sharing) throws Exception {
        Message message=new Message(own,Message.Type.groupchat);
        message.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nickname)); message.setStanzaId(id);
        message.addExtension(new OriginIdElement(id)); if(sharing!=null) sharing.addTo(message,body);
        return from(message,room,own,nickname,body,encrypted,true).identified(own.toString(),own);
    }
    static XmppRoomMessage sent(EntityBareJid room,EntityBareJid own,String nickname,String id,String body,boolean encrypted) throws Exception {
        Message message=new Message(own,Message.Type.groupchat);
        message.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nickname));
        message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        return from(message,room,own,nickname,body,encrypted,true);
    }
}
