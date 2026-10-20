package com.negzero.monogram.xmpp;

import java.util.*;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppRoomRecipientsTest {
    @Test void exclusionIsExplicitKeepsOwnDevicesAndNeverImplicitlyExcludesNewMembers() throws Exception {
        BareJid own=JidCreate.bareFrom("alice@example.org"),bob=JidCreate.bareFrom("bob@example.org"),offline=JidCreate.bareFrom("offline@example.org");
        Set<BareJid> members=new HashSet<>(Arrays.asList(own,bob,offline));
        assertEquals(members,XmppRooms.recipients(members,own,Collections.emptySet()));
        assertEquals(new HashSet<>(Arrays.asList(own,bob)),XmppRooms.recipients(members,own,Collections.singleton(offline.toString())));
        assertEquals(3,members.size());
        assertEquals(Collections.singleton(own),XmppRooms.recipients(Collections.singleton(own),own,Collections.emptySet()));
        BareJid newcomer=JidCreate.bareFrom("new@example.org"); members.add(newcomer);
        assertTrue(XmppRooms.recipients(members,own,Collections.singleton(offline.toString())).contains(newcomer));
        assertThrows(IllegalArgumentException.class,() -> XmppRooms.recipients(members,own,Collections.singleton(own.toString())));
        assertThrows(IllegalArgumentException.class,() -> XmppRooms.recipients(members,own,Collections.singleton("bob@example.org/phone")));
        assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,assertThrows(OmemoSendException.class,
                () -> XmppRooms.recipients(members,own,new HashSet<>(Arrays.asList(bob.toString(),offline.toString(),newcomer.toString())))).reason);
    }
}
