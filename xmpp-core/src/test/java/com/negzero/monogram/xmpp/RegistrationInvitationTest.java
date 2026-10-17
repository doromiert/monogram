package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationInvitationTest {
    @Test void preservesServerReservedNamesAndOpaqueTokensWithoutTrustingOtherActions() throws Exception {
        RegistrationInvitation reserved=RegistrationInvitation.parse("xmpp:juliet@example.org?register;preauth=a%2Bb%26c");
        assertEquals("juliet",reserved.username.toString());assertEquals("a+b&c",reserved.token());
        reserved.validate(new Account("juliet@example.org",null,5222,"Phone"));
        assertThrows(XmppInvitations.InvitationException.class,() -> reserved.validate(new Account("other@example.org",null,5222,"Phone")));
        assertThrows(XmppInvitations.InvitationException.class,() -> reserved.validate(new Account("juliet@other.org",null,5222,"Phone")));
        assertNull(RegistrationInvitation.parse("xmpp:romeo@example.org?roster;preauth=token;ibr=y").username);
        assertNull(RegistrationInvitation.parse("xmpp:example.org?register;preauth=token").username);
        for(String invalid:new String[]{"xmpp:juliet@example.org/phone?register;preauth=token","xmpp:example.org?register;preauth=a;preauth=b","xmpp:romeo@example.org?roster;preauth=token","xmpp:example.org?message;preauth=token","xmpp:example.org?register;preauth=%0A","xmpp:example.org?register;preauth=","https://example.org/invite/#token"})
            assertThrows(XmppInvitations.InvitationException.class,() -> RegistrationInvitation.parse(invalid));
    }
}
