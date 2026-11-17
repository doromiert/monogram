package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppProfileCacheTest {
    static class Connection extends XMPPTCPConnection {
        String card;
        StanzaError.Condition failure=StanzaError.Condition.forbidden;
        Connection() throws Exception {
            super(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());
            connected=true;authenticated=true;user=JidCreate.entityFullFrom("alice@example.org/test");setReplyTimeout(1000);
        }
        @Override protected void sendStanzaInternal(Stanza stanza) throws SmackException.NotConnectedException,InterruptedException {
            IQ request=(IQ)stanza;IQ answer;
            if("vCard".equals(request.getChildElementName()) && card!=null) {
                try { answer=PacketParserUtils.parseStanza("<iq type='result' id='"+request.getStanzaId()+"'>"+card+"</iq>"); }
                catch(Exception invalid) { throw new AssertionError(invalid); }
            } else answer=IQ.createErrorResponse(request,StanzaError.getBuilder(failure));
            answer.setStanzaId(request.getStanzaId());answer.setFrom(request.getTo()==null ? user.asBareJid() : request.getTo());answer.setTo(user);
            invokeStanzaCollectorsAndNotifyRecvListeners(answer);
        }
    }
    XmppProfile cached() throws Exception { return new XmppProfile("<vCard xmlns='vcard-temp'><FN>Old name</FN></vCard>","Cached nickname",new byte[]{1,2,3}); }
    @Test void deniedOrUnavailableSourcesPreserveCachedFieldsAndStillAllowIndependentUpdates() throws Exception {
        for(StanzaError.Condition error:new StanzaError.Condition[]{StanzaError.Condition.forbidden,StanzaError.Condition.service_unavailable,StanzaError.Condition.feature_not_implemented}) {
            Connection connection=new Connection();connection.failure=error;
            XmppProfiles profiles=new XmppProfiles(connection,jid -> {});XmppProfile cached=cached();
            assertSame(cached,profiles.load("bob@example.org",cached));
            connection.card="<vCard xmlns='vcard-temp'><FN>New name</FN></vCard>";
            XmppProfile changed=profiles.load("bob@example.org",cached);
            assertEquals("New name",changed.field("FN"));assertEquals(cached.nickname,changed.nickname);assertArrayEquals(cached.avatar,changed.avatar);
        }
    }
    @Test void explicitRemovalClearsCachedFieldsWithoutTreatingLookupFailuresAsRemoval() throws Exception {
        Connection connection=new Connection();connection.failure=StanzaError.Condition.item_not_found;connection.card="<vCard xmlns='vcard-temp'/>";
        XmppProfile cleared=new XmppProfiles(connection,jid -> {}).load("bob@example.org",cached());
        assertEquals("",cleared.nickname);assertEquals(0,cleared.avatar.length);
        connection.card="<vCard xmlns='vcard-temp'><FN>New name</FN><PHOTO><BINVAL>!invalid!</BINVAL></PHOTO></vCard>";
        assertArrayEquals(cached().avatar,new XmppProfiles(connection,jid -> {}).load("bob@example.org",cached()).avatar);
        XmppProfile cached=cached();XmppProfile pepRemoved=XmppProfiles.mergeCached(cached,null,null,new byte[0]);
        assertEquals(cached.xml(),pepRemoved.xml());assertEquals(cached.nickname,pepRemoved.nickname);assertEquals(0,pepRemoved.avatar.length);
    }

    static class AvatarConnection extends Connection {
        int reads,writes;int largestWrite;
        AvatarConnection() throws Exception { card="<vCard xmlns='vcard-temp'><FN>Alice</FN></vCard>"; }
        @Override protected void sendStanzaInternal(Stanza stanza) throws SmackException.NotConnectedException,InterruptedException {
            if(stanza instanceof Presence) return;
            IQ request=(IQ)stanza;
            if("http://jabber.org/protocol/disco#info".equals(request.getChildElementNamespace())) {
                try {
                    IQ answer=PacketParserUtils.parseStanza("<iq type='result' id='"+request.getStanzaId()+"'><query xmlns='http://jabber.org/protocol/disco#info'/></iq>");
                    answer.setFrom(request.getTo()==null ? user.asBareJid() : request.getTo());answer.setTo(user);
                    invokeStanzaCollectorsAndNotifyRecvListeners(answer);return;
                } catch(Exception invalid) { throw new AssertionError(invalid); }
            }
            if("vCard".equals(request.getChildElementName())) {
                if(request.getType()==IQ.Type.set) {
                    writes++;largestWrite=Math.max(largestWrite,request.toXML().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    assertTrue(largestWrite<64*1024,"server would disconnect this oversized stanza");
                    IQ answer=IQ.createResultIQ(request);answer.setFrom(request.getTo()==null ? user.asBareJid() : request.getTo());answer.setTo(user);
                    invokeStanzaCollectorsAndNotifyRecvListeners(answer);return;
                }
                reads++;assertEquals(0,writes,"a confirmed avatar save must not depend on a follow-up fetch");
            }
            super.sendStanzaInternal(stanza);
        }
    }
    @Test void avatarWritesStayBelowServerLimitsAndRejectOversizedInputsBeforeSending() throws Exception {
        AvatarConnection connection=new AvatarConnection();XmppProfiles profiles=new XmppProfiles(connection,jid->{});
        byte[] png=new byte[XmppProfile.MAX_PUBLIC_AVATAR_BYTES];
        XmppProfile saved=profiles.avatar(png,cached());
        assertEquals("Cached nickname",saved.nickname);
        assertArrayEquals(png,saved.avatar);assertEquals(1,connection.reads);assertEquals(1,connection.writes);
        assertTrue(connection.largestWrite<64*1024);assertTrue(connection.isConnected());
        assertThrows(IllegalArgumentException.class,()->profiles.avatar(new byte[XmppProfile.MAX_PUBLIC_AVATAR_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->profiles.roomAvatar("room@example.org",new byte[XmppProfile.MAX_PUBLIC_AVATAR_BYTES+1]));
        assertEquals(1,connection.reads);assertEquals(1,connection.writes);
        AvatarConnection room=new AvatarConnection();XmppProfile changed=new XmppProfiles(room,jid->{}).roomAvatar("room@example.org",png);
        assertArrayEquals(png,changed.avatar);assertEquals(1,room.reads);assertEquals(1,room.writes);
    }
    @Test void profileWireBudgetCountsUtf8AndKeepsTheConnectionUsable() throws Exception {
        AvatarConnection connection=new AvatarConnection();
        connection.card="<vCard xmlns='vcard-temp'><DESC>"+String.join("",java.util.Collections.nCopies(22000,"猫"))+"</DESC></vCard>";
        XmppProfiles profiles=new XmppProfiles(connection,jid->{});
        assertThrows(IllegalArgumentException.class,()->profiles.edit("Alice",null,null));
        assertEquals(0,connection.writes);assertTrue(connection.isConnected());
    }
}
