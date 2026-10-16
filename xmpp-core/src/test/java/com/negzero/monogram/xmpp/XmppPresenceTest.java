package com.negzero.monogram.xmpp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class XmppPresenceTest {
    @Test void offlineTimestampRequiresValidNonzeroServerDuration() {
        assertEquals(970,XmppContacts.lastSeenTimestamp(1000,30));
        for(long seconds:new long[]{0,-1,1000,1001,Long.MAX_VALUE,Long.MIN_VALUE}) assertEquals(0,XmppContacts.lastSeenTimestamp(1000,seconds));
    }
}
