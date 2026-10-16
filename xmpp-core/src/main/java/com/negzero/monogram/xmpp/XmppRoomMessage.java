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
    public final String sender, body, realSender;
    public final MessageReactions reactions;
    public final Map<String,String> ids;
    public final boolean outgoing, encrypted, readable, archived;
    public final XmppMessage.Encryption encryption;
    public final long timestamp;
    private XmppRoomMessage(EntityBareJid room,String sender,String body,Map<String,String> ids,
                            boolean outgoing,boolean encrypted,boolean readable,long timestamp,boolean modern,boolean archived,String realSender,MessageReactions reactions) {
        this.realSender=realSender; this.reactions=reactions;
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
        if(readable && body==null && reactions==null) return null;
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
                delay==null ? System.currentTimeMillis() : delay.getTime(),wire.hasExtension("encrypted",XmppOmemo2.NS),archiveTime!=null,real==null ? null : real.asBareJid().toString(),reactions);
    }
    XmppRoomMessage identified(String real,EntityBareJid own) {
        return new XmppRoomMessage(room,sender,body,new LinkedHashMap<>(ids),own.toString().equals(real),encrypted,readable,
                timestamp,encryption==XmppMessage.Encryption.OMEMO2,archived,real,reactions);
    }
    static XmppRoomMessage sent(EntityBareJid room,EntityBareJid own,String nickname,String id,String body,boolean encrypted) throws Exception {
        Message message=new Message(own,Message.Type.groupchat);
        message.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nickname));
        message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        return from(message,room,own,nickname,body,encrypted,true);
    }
}
