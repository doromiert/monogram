// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.jxmpp.jid.EntityBareJid;

/** Transport validates room support and the actor; nicknames alone never identify a reader. */
public final class RoomDisplayedMarker {
    public static final String LOCAL_ID="room-displayed";
    public final EntityBareJid room;
    public final String id,actor,occupant;
    public final boolean own;
    private RoomDisplayedMarker(EntityBareJid room,String id,String actor,String occupant,boolean own) {
        this.room=room;this.id=id;this.actor=actor;this.occupant=occupant;this.own=own;
    }
    static RoomDisplayedMarker from(Message message,EntityBareJid room,String actor,boolean own) {
        if(actor==null || actor.isEmpty() || message.getType()!=Message.Type.groupchat || message.getFrom()==null
                || message.getFrom().asEntityFullJidIfPossible()==null || !room.equals(message.getFrom().asEntityBareJidIfPossible())
                || message.getBody()!=null || message.getSubject()!=null || message.hasExtension("encrypted",XmppOmemo2.NS)
                || message.hasExtension("encrypted","eu.siacs.conversations.axolotl")) return null;
        List<ExtensionElement> markers=message.getExtensions("displayed",MessageReceipt.MARKERS);
        if(markers.size()!=1) return null;
        String id=MessageReceipt.id(markers.get(0));
        return MessageReceipt.valid(id) ? new RoomDisplayedMarker(room,id,actor,message.getFrom().toString(),own) : null;
    }
    static String assignedId(Message message,EntityBareJid room) {
        String found=null;
        for(ExtensionElement extension:message.getExtensions(StanzaIdElement.ELEMENT,StanzaIdElement.NAMESPACE)) {
            if(!(extension instanceof StanzaIdElement)) continue;
            StanzaIdElement value=(StanzaIdElement)extension;
            if(!room.toString().equals(value.getBy())) continue;
            if(found!=null || !MessageReceipt.valid(value.getId())) return null;
            found=value.getId();
        }
        return found;
    }
    static XmppRoomMessage tag(XmppRoomMessage message,Message wire,boolean supported) {
        if(message==null || !supported) return message;
        String id=assignedId(wire,message.room);if(id==null) return message;
        Map<String,String> aliases=new LinkedHashMap<>(message.ids);aliases.put(LOCAL_ID,id);return message.withAliases(aliases);
    }
}
