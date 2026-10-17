package com.negzero.monogram.xmpp;

import java.io.File;
import org.jxmpp.jid.EntityBareJid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppInvitationsIntegrationTest {
    @Test void discoversAuthorizedAdminAndUserRegistrationInvitations() throws Exception {
        File root=new File(System.getenv("MONOGRAM_IT_STATE"));XmppSession.initializeOmemo(new File(root,"keys"));
        int device=3900;
        for(String user:new String[]{"roomalice","alice"}) {
            device++;
            try(XmppSession session=new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Invites-"+device),
                    "monogram-test",device,new File(root,"invite-trust-"+device),new XmppSession.Listener() {
                        public void message(EntityBareJid from,String id,String body,boolean encrypted) {}
                    })) {
                session.connect();XmppInvitations.Request request=session.invitations.begin();
                assertNotNull(request.invitation);assertTrue(request.invitation.uri.startsWith("xmpp:"));
                assertTrue(request.invitation.uri.contains("preauth="));assertFalse(request.invitation.expires.isEmpty());
                if("roomalice".equals(user)) assertTrue(request.invitation.uri.contains("?register;"));
                else assertTrue(request.invitation.uri.contains("ibr=y"));
            }
        }
    }
}
