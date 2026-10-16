package com.negzero.monogram.xmpp;

import java.util.Date;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.impl.JidCreate;

public final class MessageFixture {
    public static XmppMessage modern(String id, String archive, String body, boolean outgoing, long time,
                                     ReplyReference reply, String correction, FileShare sharing) throws Exception {
        Message outer = new Message(JidCreate.entityBareFrom(outgoing ? "bob@example.org" : "alice@example.org"),Message.Type.chat);
        outer.setFrom(JidCreate.entityFullFrom((outgoing ? "alice@example.org" : "bob@example.org")+"/phone"));
        outer.setStanzaId(id); outer.addExtension(new OriginIdElement(id));
        Message content = null;
        if (body != null) {
            content = new Message(outer); content.setBody(body);
            if (reply != null) reply.addTo(content);
            if (correction != null) new MessageCorrection(correction).addTo(content);
            if (sharing != null) sharing.addTo(content,body);
        }
        return XmppMessage.modern(outer,content,JidCreate.entityBareFrom("alice@example.org"),archive,new Date(time));
    }
    public static XmppSession.ArchivePage page(XmppMessage... messages) {
        return new XmppSession.ArchivePage(java.util.Arrays.asList(messages), messages[0].ids.get("archive"),
                messages[messages.length - 1].ids.get("archive"), true);
    }
    public static XmppSession.ArchivePage page(boolean complete, String first, String last, XmppMessage... messages) {
        return new XmppSession.ArchivePage(java.util.Arrays.asList(messages), first, last, complete);
    }
    public static XmppMessage message(String stanzaId, String originId, String archiveId, String body,
                                      boolean readable, boolean outgoing, long time) throws Exception {
        return messageFrom(stanzaId, originId, archiveId, body, readable, outgoing, time, null);
    }
    public static XmppMessage messageFrom(String stanzaId, String originId, String archiveId, String body,
                                         boolean readable, boolean outgoing, long time, String resource) throws Exception {
        return replyMessage(stanzaId, originId, archiveId, body, readable, outgoing, time, resource, true, null);
    }
    public static XmppMessage replyMessage(String stanzaId, String originId, String archiveId, String body,
            boolean readable, boolean outgoing, long time, String resource, boolean encrypted, ReplyReference reply) throws Exception {
        return correction(stanzaId, originId, archiveId, body, readable, outgoing, time, resource, encrypted, reply, null);
    }
    public static XmppMessage correction(String stanzaId, String originId, String archiveId, String body,
            boolean readable, boolean outgoing, long time, String resource, boolean encrypted, ReplyReference reply, String corrected) throws Exception {
        Message stanza = new Message(JidCreate.entityBareFrom(outgoing ? "bob@example.org" : "alice@example.org"), Message.Type.chat);
        String from = outgoing ? "alice@example.org" : "bob@example.org";
        stanza.setFrom(resource == null ? JidCreate.entityBareFrom(from) : JidCreate.entityFullFrom(from + "/" + resource));
        stanza.setStanzaId(stanzaId);
        if (originId != null) stanza.addExtension(new OriginIdElement(originId));
        if (reply != null) reply.addTo(stanza);
        if (corrected != null) new MessageCorrection(corrected).addTo(stanza);
        return XmppMessage.from(stanza, JidCreate.entityBareFrom("alice@example.org"), body, encrypted, readable, archiveId, new Date(time));
    }
    public static XmppRoomMessage roomArchive(String room,String nick,String stanza,String sid,String mam,String body,long time,boolean encrypted) throws Exception {
        org.jivesoftware.smack.packet.Message wire=new org.jivesoftware.smack.packet.Message(org.jxmpp.jid.impl.JidCreate.entityBareFrom(room),org.jivesoftware.smack.packet.Message.Type.groupchat);
        wire.setFrom(org.jxmpp.jid.impl.JidCreate.entityFullFrom(room+"/"+nick)); wire.setStanzaId(stanza);
        if(sid!=null) wire.addExtension(new org.jivesoftware.smackx.sid.element.StanzaIdElement(sid,room));
        return XmppRoomMessage.archived(wire,org.jxmpp.jid.impl.JidCreate.entityBareFrom(room),org.jxmpp.jid.impl.JidCreate.entityBareFrom("alice@example.org"),"",body,encrypted,body!=null,mam,new java.util.Date(time));
    }
    public static XmppMessage reaction(String wire,String target,java.util.Collection<String> emojis,boolean outgoing,boolean modern,long time) throws Exception {
        Message message=new Message(JidCreate.entityBareFrom(outgoing ? "bob@example.org" : "alice@example.org"),Message.Type.chat);
        message.setFrom(JidCreate.entityFullFrom((outgoing ? "alice@example.org" : "bob@example.org")+"/phone")); message.setStanzaId(wire);
        message.addExtension(new OriginIdElement(wire)); new MessageReactions(target,emojis).addTo(message);
        return modern ? XmppMessage.modern(message,message,JidCreate.entityBareFrom("alice@example.org"),null,new Date(time))
                : XmppMessage.from(message,JidCreate.entityBareFrom("alice@example.org"),null,false,true,null,new Date(time));
    }
    public static XmppRoomMessage roomFile(String room,String nick,String wire,String body,boolean encrypted,FileShare sharing) throws Exception {
        Message message=new Message(JidCreate.entityBareFrom("alice@example.org"),Message.Type.groupchat);
        message.setFrom(JidCreate.entityFullFrom(room+"/"+nick)); message.setStanzaId(wire);
        if(sharing!=null) sharing.addTo(message,body);
        return XmppRoomMessage.from(message,JidCreate.entityBareFrom(room),JidCreate.entityBareFrom("alice@example.org"),"Alice",body,encrypted,true);
    }
    public static XmppRoomMessage roomReaction(String room,String nick,String wire,String target,String occupant,java.util.Collection<String> emojis,long time) throws Exception {
        Message message=new Message(JidCreate.entityBareFrom("alice@example.org"),Message.Type.groupchat);
        message.setFrom(JidCreate.entityFullFrom(room+"/"+nick)); message.setStanzaId(wire); new MessageReactions(target,emojis).addTo(message);
        return XmppRoomMessage.archived(message,JidCreate.entityBareFrom(room),JidCreate.entityBareFrom("alice@example.org"),"Alice",null,false,true,wire,new Date(time)).withOccupantId(occupant);
    }
    public static XmppRoomMessage disclosedReaction(String room,String nick,String wire,String target,String occupant,String real,java.util.Collection<String> emojis,long time) throws Exception {
        return roomReaction(room,nick,wire,target,occupant,emojis,time).identified(real,JidCreate.entityBareFrom("alice@example.org"));
    }

}
