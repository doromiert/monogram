package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.muc.MUCAffiliation;
import org.jivesoftware.smackx.muc.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppRoomMembershipTest {
    /** Responses are withheld until all three affiliation IQs and room-info IQ are in flight. */
    static final class Connection extends XMPPTCPConnection {
        final EntityBareJid room=JidCreate.entityBareFrom("friends@conference.example.org");
        final Map<MUCAffiliation,BareJid> addresses=new LinkedHashMap<>();
        final List<IQ> pending=new ArrayList<>();
        int sent,removed;
        boolean supported=true;
        MUCAffiliation rejected,missing;
        Connection() throws Exception {
            super(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());
            connected=true; authenticated=true; user=JidCreate.entityFullFrom("alice@example.org/test"); setReplyTimeout(1000);
            addresses.put(MUCAffiliation.member,JidCreate.bareFrom("offline@example.org"));
            addresses.put(MUCAffiliation.admin,JidCreate.bareFrom("admin@example.org"));
            addresses.put(MUCAffiliation.owner,user.asBareJid());
        }
        @Override protected void sendStanzaInternal(Stanza stanza) throws SmackException.NotConnectedException,InterruptedException {
            assertTrue(stanza instanceof MUCAdmin || stanza instanceof DiscoverInfo);
            IQ query=(IQ)stanza; pending.add(query); sent++;
            if(pending.size()<4) return;
            assertTrue(pending.get(3) instanceof DiscoverInfo,"Room information must be requested before waiting for affiliation replies");
            for(IQ request:pending) {
                IQ answer;
                if(request instanceof DiscoverInfo) {
                    DiscoverInfo info=new DiscoverInfo(); info.addIdentity(new DiscoverInfo.Identity("conference","Room","text"));
                    info.addFeature("muc_membersonly"); if(supported) info.addFeature("muc_nonanonymous"); answer=info;
                } else {
                    MUCAffiliation affiliation=((MUCAdmin)request).getItems().get(0).getAffiliation();
                    if(affiliation==missing) continue;
                    if(affiliation==rejected) answer=IQ.createErrorResponse(request,StanzaError.getBuilder(StanzaError.Condition.forbidden));
                    else { MUCAdmin list=new MUCAdmin(); list.addItem(new MUCItem(affiliation,addresses.get(affiliation))); answer=list; }
                }
                if(answer.getType()!=IQ.Type.error) answer.setType(IQ.Type.result);
                answer.setStanzaId(request.getStanzaId()); answer.setFrom(room); answer.setTo(user);
                invokeStanzaCollectorsAndNotifyRecvListeners(answer);
            }
            pending.clear();
        }
        @Override public void removeStanzaCollector(org.jivesoftware.smack.StanzaCollector collector) {
            removed++; super.removeStanzaCollector(collector);
        }
    }
    @Test void pipelinesAllQueriesKeepsOfflineAffiliationsAndRefreshesEveryPass() throws Exception {
        Connection connection=new Connection();
        Set<BareJid> expected=new HashSet<>(connection.addresses.values());
        assertEquals(expected,XmppRooms.memberLists(connection,connection.room));
        connection.addresses.put(MUCAffiliation.member,JidCreate.bareFrom("new-member@example.org"));
        expected=new HashSet<>(connection.addresses.values());
        assertEquals(expected,XmppRooms.memberLists(connection,connection.room));
        assertEquals(8,connection.sent); assertEquals(8,connection.removed);
    }
    @Test void rejectedIncompleteOrUnsupportedRoomsCannotYieldPartialRecipients() throws Exception {
        Connection rejected=new Connection(); rejected.rejected=MUCAffiliation.admin;
        XmppRequestException failure=assertThrows(XmppRequestException.class,() -> XmppRooms.memberLists(rejected,rejected.room));
        assertTrue(failure.getMessage().contains("Room admin list: server replied forbidden")); assertEquals(4,rejected.removed);
        Connection unsupported=new Connection(); unsupported.supported=false;
        assertEquals(OmemoSendException.Reason.ROOM_UNSUPPORTED,assertThrows(OmemoSendException.class,() -> XmppRooms.memberLists(unsupported,unsupported.room)).reason);
        assertEquals(4,unsupported.removed);
        Connection missing=new Connection(); missing.missing=MUCAffiliation.owner; missing.setReplyTimeout(250);
        assertEquals(OmemoSendException.Reason.LOOKUP_TIMEOUT,assertThrows(OmemoSendException.class,() -> XmppRooms.memberLists(missing,missing.room)).reason);
        assertEquals(4,missing.removed);
    }
}
