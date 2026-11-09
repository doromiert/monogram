// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jxmpp.jid.impl.JidCreate;

/** Captured server-verified room route. Local journal data, never an outgoing extension. */
final class RoomCryptoContext {
    final Message outer;
    final String owner,room,author,nickname,occupant;
    final long timestamp;
    final boolean archived;
    final Map<String,String> ids;
    RoomCryptoContext(LegacyIncomingJournal.Context route,String owner) throws Exception {
        this(route.outer,owner,route.room,route.author,route.nickname,route.occupantId,route.timestamp,route.archived,route.ids);
    }
    private RoomCryptoContext(Message outer,String owner,String room,String author,String nickname,String occupant,
            long timestamp,boolean archived,Map<String,String> ids) throws Exception {
        if(room==null || outer.getType()!=Message.Type.groupchat || outer.getFrom()==null || !outer.getFrom().hasResource()
                || !room.equals(outer.getFrom().asBareJid().toString()) || timestamp<0
                || !JidCreate.entityBareFrom(owner).toString().equals(owner)
                || !JidCreate.entityBareFrom(author).toString().equals(author)
                || outer.getTo()!=null && !outer.getTo().asBareJid().toString().equals(owner)
                    && !(archived && outer.getTo().asBareJid().toString().equals(room)))
            throw new java.security.GeneralSecurityException("Invalid protected room route");
        this.outer=outer;this.owner=owner;this.room=room;this.author=author;this.nickname=nickname==null ? "" : nickname;
        this.occupant=occupant;this.timestamp=timestamp;this.archived=archived;this.ids=new LinkedHashMap<>(ids);
    }
    XmppRoomMessage model(Message content,XmppMessage.Encryption protocol,String fingerprint,String archiveId,Date time) throws Exception {
        if(content==null)return null;
        XmppRoomMessage model=XmppRoomMessage.protectedContent(outer,content,JidCreate.entityBareFrom(owner),nickname,protocol,archiveId,time);
        if(model==null)throw new java.security.GeneralSecurityException("Invalid protected room message");
        Map<String,String> aliases=new LinkedHashMap<>(ids);if(archiveId!=null)aliases.put("mam",archiveId);
        return model.canonical(aliases,time==null ? timestamp : time.getTime(),archived || archiveId!=null,
                owner.equals(author),author,true,occupant).withSenderFingerprint(fingerprint);
    }
    void write(DataOutputStream out) throws IOException {
        text(out,owner);text(out,room);text(out,author);text(out,nickname);text(out,occupant);
        out.writeLong(timestamp);out.writeBoolean(archived);
        if(ids.size()>16)throw new IOException("Too many room aliases");out.writeByte(ids.size());
        for(Map.Entry<String,String> entry:ids.entrySet()) {text(out,entry.getKey());text(out,entry.getValue());}
    }
    static RoomCryptoContext read(DataInputStream in,Message outer,String owner) throws Exception {
        String stored=text(in),room=text(in),author=text(in),nickname=text(in),occupant=text(in);
        if(owner!=null && !owner.equals(stored))throw new IOException("Room journal owner changed");
        long timestamp=in.readLong();boolean archived=in.readBoolean();int count=in.readUnsignedByte();
        if(count>16)throw new IOException("Too many room aliases");Map<String,String> ids=new LinkedHashMap<>();
        for(int i=0;i<count;i++) {String key=text(in),value=text(in);if(key==null || value==null || ids.put(key,value)!=null)throw new IOException("Invalid room alias");}
        return new RoomCryptoContext(outer,stored,room,author,nickname,occupant,timestamp,archived,ids);
    }
    private static void text(DataOutputStream out,String value) throws IOException {
        if(value==null) {out.writeInt(-1);return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>16384)throw new IOException("Room context field too long");out.writeInt(bytes.length);out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int size=in.readInt();if(size==-1)return null;if(size<0 || size>16384 || size>in.available())throw new IOException("Invalid room context field");
        byte[] bytes=new byte[size];in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
