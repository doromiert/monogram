package com.negzero.monogram.xmpp;

import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppInvitationsTest {
    DataForm result(String uri,String landing) {
        return DataForm.builder(DataForm.Type.result)
            .addField(FormField.textSingleBuilder("uri").setValue(uri).build())
            .addField(FormField.textSingleBuilder("landing-url").setValue(landing).build()).build();
    }
    @Test void acceptsRegistrationOnlyAndNeverInventsAnExternalLandingPage() throws Exception {
        org.jxmpp.jid.DomainBareJid server=JidCreate.domainBareFrom("example.org");
        String register="xmpp:example.org?register;preauth=secret";
        assertEquals("https://example.org/invite/#secret",XmppInvitations.read(result(register,"https://example.org/invite/#secret"),server).link);
        assertEquals(register,XmppInvitations.read(result(register,"http://example.org/invite/"),server).link);
        assertEquals(register,XmppInvitations.read(result(register,"javascript:alert(1)"),server).link);
        assertEquals("xmpp:alice@example.org?roster;preauth=secret;ibr=y",XmppInvitations.read(result("xmpp:alice@example.org?roster;preauth=secret;ibr=y",""),server).link);
        for(String invalid:new String[]{"xmpp:alice@example.org?roster;preauth=secret","xmpp:example.org?register","xmpp:other.org?register;preauth=secret","https://example.org/?register;preauth=secret","xmpp:example.org?register;preauth=a;preauth=b","xmpp:example.org?register;preauth=secret#fragment"})
            assertThrows(XmppInvitations.InvitationException.class,() -> XmppInvitations.read(result(invalid,"https://example.org"),server));
    }
}
