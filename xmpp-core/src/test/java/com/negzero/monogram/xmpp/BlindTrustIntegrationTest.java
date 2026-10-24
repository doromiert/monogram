package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
class BlindTrustIntegrationTest {
    private XmppSession session(String user,int id,ModernSessionIntegrationTest.Inbox inbox)throws Exception {
        File state=new File(System.getenv("MONOGRAM_IT_STATE"));XmppSession.initializeOmemo(new File(state,"keys"),LegacyOmemoTestDatabase.shared());
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Blind-"+id),
            "monogram-test",id,new File(state,user+".trust"),new Omemo2Device(user+"@localhost",id+1000,new Omemo2TestDatabase()),inbox);
    }
    private void roundTrip(String prefix,XmppMessage.Encryption mode)throws Exception {
        String a=prefix+"alice",b=prefix+"bob";ModernSessionIntegrationTest.Inbox ai=new ModernSessionIntegrationTest.Inbox(),bi=new ModernSessionIntegrationTest.Inbox();
        try(XmppSession alice=session(a,611,ai);XmppSession bob=session(b,622,bi)) {
            alice.setBlindTrustPolicy(peer->peer.equals(b+"@localhost"));bob.setBlindTrustPolicy(peer->peer.equals(a+"@localhost"));
            alice.connect();bob.connect();
            alice.send(b+"@localhost","Opt-in "+mode,mode,null);
            XmppMessage first=bi.next();assertTrue(first.readable);assertTrue(first.encrypted);assertEquals("Opt-in "+mode,first.body);bi.store();
            bob.send(a+"@localhost","Reply "+mode,mode,null);
            XmppMessage reply=ai.next();assertTrue(reply.readable);assertEquals("Reply "+mode,reply.body);ai.store();
        }
    }
    @Test void modernSessionsCanSendAndReceiveWithContactOptInWithoutManualFingerprintApproval()throws Exception{roundTrip("blindmodern",XmppMessage.Encryption.OMEMO2);}
    @Test void legacySessionsCanSendAndReceiveWithContactOptInWithoutManualFingerprintApproval()throws Exception{roundTrip("blindlegacy",XmppMessage.Encryption.LEGACY_OMEMO);}
}
