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
}
