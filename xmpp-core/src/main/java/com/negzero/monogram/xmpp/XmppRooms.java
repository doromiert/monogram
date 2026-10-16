// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.omemo.*;
import org.jivesoftware.smackx.omemo.element.OmemoElement_VAxolotl;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;

/** Blocking room discovery, membership and messaging without a server helper. */
public final class XmppRooms implements AutoCloseable {
    public static final class Occupant {
        public final String address,nickname,realJid;
        Occupant(EntityFullJid address,org.jivesoftware.smackx.muc.Occupant occupant) {
            this.address=address.toString(); nickname=address.getResourcepart().toString();
            realJid=occupant==null || occupant.getJid()==null ? null : occupant.getJid().asBareJid().toString();
        }
    }
    public static final class State {
        public final EntityBareJid room;
        public final String nickname,subject,title;
        public final boolean joined;
        public final List<Occupant> occupants;
        State(MultiUserChat chat) { this(chat,null); }
        State(MultiUserChat chat,String title) {
            this.title=title; room=chat.getRoom(); nickname=chat.getNickname()==null ? "" : chat.getNickname().toString();
            subject=chat.getSubject()==null ? "" : chat.getSubject(); joined=chat.isJoined();
            List<Occupant> found=new ArrayList<>();
            for (EntityFullJid jid:chat.getOccupants()) found.add(new Occupant(jid,chat.getOccupant(jid)));
            occupants=Collections.unmodifiableList(found);
        }
    }
    private final XMPPConnection connection;
    private final OmemoManager omemo;
    private final XmppSession.Listener listener;
    private final Map<EntityBareJid,MultiUserChat> chats=new ConcurrentHashMap<>();
    private final Map<EntityBareJid,String> passwords=new ConcurrentHashMap<>();
    XmppRooms(XMPPConnection connection,OmemoManager omemo,XmppSession.Listener listener) {
        this.connection=connection; this.omemo=omemo; this.listener=listener;
        MultiUserChatManager.getInstanceFor(connection).setAutoJoinOnReconnect(false);
        omemo.addOmemoMucMessageListener((chat,stanza,clear) -> {
            if (!clear.isKeyTransportMessage() && stanza instanceof Message && !stanza.hasExtension("encrypted",XmppOmemo2.NS) && chats.get(chat.getRoom())==chat)
                deliver(chat,(Message)stanza,clear.getBody(),true,true);
        });
    }
    public List<DomainBareJid> services() throws Exception { return MultiUserChatManager.getInstanceFor(connection).getMucServiceDomains(); }
    public Collection<HostedRoom> discover(String service) throws Exception {
        return MultiUserChatManager.getInstanceFor(connection).getRoomsHostedBy(JidCreate.domainBareFrom(service)).values();
    }
    public boolean known(Jid jid) { return jid!=null && chats.containsKey(jid.asEntityBareJidIfPossible()); }
    public State state(String jid) throws Exception { return new State(chat(jid)); }
    private MultiUserChat chat(String jid) throws Exception {
        MultiUserChat chat=chats.get(JidCreate.entityBareFrom(jid));
        if (chat==null) throw new IllegalArgumentException("Unknown room");
        return chat;
    }
    public void remember(String jid) throws Exception { managed(jid); }
    private MultiUserChat managed(String jid) throws Exception {
        EntityBareJid room=JidCreate.entityBareFrom(jid);
        return chats.computeIfAbsent(room,key -> {
            MultiUserChat created=MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(key);
            created.addMessageListener(message -> {
                boolean encrypted=message.hasExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE) || message.hasExtension("encrypted",XmppOmemo2.NS);
                deliver(created,message,encrypted ? null : message.getBody(),encrypted,!encrypted);
            });
            created.addParticipantListener(presence -> listener.roomChanged(new State(created)));
            created.addSubjectUpdatedListener((subject,from) -> listener.roomChanged(new State(created)));
            return created;
        });
    }
    public State join(String jid,String nickname,String password) throws Exception {
        MultiUserChat chat=managed(jid);
        RoomInfo info=MultiUserChatManager.getInstanceFor(connection).getRoomInfo(chat.getRoom());
        if (!chat.isJoined()) chat.join(chat.getEnterConfigurationBuilder(Resourcepart.from(nickname))
                .withPassword(password).requestMaxStanzasHistory(100).build());
        if(password!=null) passwords.put(chat.getRoom(),password);
        State state=new State(chat,info.getName()); listener.roomChanged(state); return state;
    }
    private void deliver(MultiUserChat chat,Message wire,String body,boolean encrypted,boolean readable) {
        if (!encrypted && body==null) return;
        XmppRoomMessage message=XmppRoomMessage.from(wire,chat.getRoom(),connection.getUser().asEntityBareJid(),
                chat.getNickname()==null ? "" : chat.getNickname().toString(),body,encrypted,readable);
        if (message!=null) listener.roomMessage(message);
    }
    public void leave(String jid) throws Exception {
        MultiUserChat chat=chat(jid); if (chat.isJoined()) chat.leave(); listener.roomChanged(new State(chat));
    }
    public void nickname(String jid,String nickname) throws Exception {
        Resourcepart.from(nickname); MultiUserChat chat=chat(jid);
        if(!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        String previous=chat.getNickname().toString(),password=passwords.get(chat.getRoom());
        // shortcut: Smack 4.4.8 deadlocks on self-presence 303; use leave/rejoin until the library is upgraded.
        leave(jid);
        try { join(jid,nickname,password); }
        catch(Exception failed) { try { join(jid,previous,password); } catch(Exception restore) { failed.addSuppressed(restore); } throw failed; }
    }
    public Set<BareJid> members(String jid) throws Exception {
        MultiUserChat chat=chat(jid);
        if (!chat.isJoined() || !omemo.multiUserChatSupportsOmemo(chat)) throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        Set<BareJid> members=new HashSet<>();
        // Include offline members; Smack's encrypt(MultiUserChat,...) covers only present occupants.
        for (Affiliate member:chat.getMembers()) members.add(member.getJid().asBareJid());
        for (Affiliate member:chat.getAdmins()) members.add(member.getJid().asBareJid());
        for (Affiliate member:chat.getOwners()) members.add(member.getJid().asBareJid());
        for (EntityFullJid occupant:chat.getOccupants()) {
            org.jivesoftware.smackx.muc.Occupant user=chat.getOccupant(occupant);
            if (user==null || user.getJid()==null || !members.contains(user.getJid().asBareJid()))
                throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        }
        if (!members.contains(connection.getUser().asBareJid())) throw new OmemoSendException(OmemoSendException.Reason.ROOM_UNSUPPORTED);
        return members;
    }
    public XmppRoomMessage send(String jid,String body,boolean encrypted) throws Exception {
        if (body==null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        MultiUserChat chat=chat(jid);
        if (!chat.isJoined()) throw new IllegalStateException("Room is not joined");
        Message message;
        if (encrypted) {
            Set<BareJid> recipients=members(jid);
            for (BareJid recipient:recipients) if (!omemo.contactSupportsOmemo(recipient)) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES);
            OmemoMessage.Sent ciphertext=omemo.encrypt(recipients,body);
            if (!ciphertext.getSkippedDevices().isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE);
            for (BareJid recipient:recipients) {
                if (recipient.equals(connection.getUser().asBareJid())) continue;
                boolean included=false;
                for (OmemoDevice device:ciphertext.getIntendedDevices()) if (device.getJid().equals(recipient)) included=true;
                if (!included) throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES);
            }
            if (!members(jid).equals(recipients)) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            message=ciphertext.buildMessage(connection.getStanzaFactory().buildMessageStanza().ofType(Message.Type.groupchat),chat.getRoom());
        } else { message=new Message(chat.getRoom(),Message.Type.groupchat); message.setBody(body); }
        String id=UUID.randomUUID().toString(); message.setStanzaId(id); message.addExtension(new OriginIdElement(id));
        XmppRoomMessage sent=XmppRoomMessage.sent(chat.getRoom(),connection.getUser().asEntityBareJid(),chat.getNickname().toString(),id,body,encrypted);
        chat.sendMessage(message); return sent;
    }
    @Override public void close() { chats.clear(); passwords.clear(); }
}
