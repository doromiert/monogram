package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppRegistrationIntegrationTest {
    final int port=Integer.parseInt(System.getenv("MONOGRAM_IT_PORT"));
    XMPPTCPConnection login(String user,String password) throws Exception {
        XMPPTCPConnection connection=new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder().setXmppDomain("localhost").setHost("127.0.0.1").setPort(port)
                .setUsernameAndPassword(user,password).setSecurityMode(ConnectionConfiguration.SecurityMode.required).setSendPresence(false).build());
        try { connection.connect().login();return connection; }
        catch(Exception failed) { connection.disconnect();throw failed; }
    }
    @Test void validatesTokenCreatesAccountAndDoesNotReuseConsumedInvitations() throws Exception {
        XMPPTCPConnection admin=login("roomalice","monogram-test");
        try {
            RegistrationInvitation invitation=RegistrationInvitation.parse(new XmppInvitations(admin).begin().invitation.uri);
            Account guest=new Account("invitedguest@localhost","127.0.0.1",port,"Registration");
            RegistrationInvitation invalid=RegistrationInvitation.parse("xmpp:localhost?register;preauth=invalid-test-token");
            assertThrows(XMPPErrorException.class,() -> XmppRegistration.create(invalid,guest,"guest-password"));
            XmppRegistration.create(invitation,guest,"guest-password");
            XMPPTCPConnection created=login("invitedguest","guest-password");assertTrue(created.isAuthenticated());created.disconnect();
            Account another=new Account("secondguest@localhost","127.0.0.1",port,"Registration");
            assertThrows(XMPPErrorException.class,() -> XmppRegistration.create(invitation,another,"guest-password"));
        } finally { admin.disconnect(); }
    }
}
