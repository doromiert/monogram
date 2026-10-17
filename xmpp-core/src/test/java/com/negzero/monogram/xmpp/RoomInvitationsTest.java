package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.impl.JidCreate;

class RoomInvitationsTest {
    private Message invite(String id) throws Exception {
        Message message=new Message(JidCreate.entityBareFrom("room@conference.example.org"),Message.Type.normal);message.setStanzaId(id);
        message.addExtension(StandardExtensionElement.builder("x",RoomInvitations.NS)
                .addElement(StandardExtensionElement.builder("invite",RoomInvitations.NS).addAttribute("to","bob@example.org").build()).build());
        return message;
    }
    private Message error(String id,String from,String to) throws Exception {
        Message message=new Message();message.setType(Message.Type.error);message.setStanzaId(id);
        message.setFrom(JidCreate.from(from));message.setTo(JidCreate.from(to));
        message.setError(StanzaError.getBuilder(StanzaError.Condition.not_allowed));return message;
    }
    @Test void errorsRequireTheSentIdBareRoomAndReceivingAccountAndApplyOnlyOnce() throws Exception {
        RoomInvitations pending=new RoomInvitations();Message sent=invite(null);
        pending.sent(sent,JidCreate.entityBareFrom("room@conference.example.org"),JidCreate.bareFrom("alice@example.org"));
        assertNotNull(sent.getStanzaId());String id=sent.getStanzaId();
        assertNull(pending.rejected(error("other","room@conference.example.org","alice@example.org/phone")));
        assertNull(pending.rejected(error(id,"other@conference.example.org","alice@example.org/phone")));
        assertNull(pending.rejected(error(id,"room@conference.example.org/nick","alice@example.org/phone")));
        assertNull(pending.rejected(error(id,"room@conference.example.org","other@example.org/phone")));
        RoomInvitations.Pending failed=pending.rejected(error(id,"room@conference.example.org","alice@example.org/phone"));
        assertNotNull(failed);assertEquals("bob@example.org",failed.target.toString());
        assertNull(pending.rejected(error(id,"room@conference.example.org","alice@example.org/phone")));
    }
    @Test void retainedInvitationsAreBoundedAndClearedWithTheSession() throws Exception {
        RoomInvitations pending=new RoomInvitations();
        for(int i=0;i<257;i++) pending.sent(invite("invite-"+i),JidCreate.entityBareFrom("room@conference.example.org"),JidCreate.bareFrom("alice@example.org"));
        assertNull(pending.rejected(error("invite-0","room@conference.example.org","alice@example.org")));
        assertNotNull(pending.rejected(error("invite-256","room@conference.example.org","alice@example.org")));
        pending.clear();assertNull(pending.rejected(error("invite-255","room@conference.example.org","alice@example.org")));
    }
}
