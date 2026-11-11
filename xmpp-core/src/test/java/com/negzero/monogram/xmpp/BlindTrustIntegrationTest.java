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
            "monogram-test",id,new File(state,user+"-"+id+".trust"),new Omemo2Device(user+"@localhost",id+1000,new Omemo2TestDatabase()),inbox);
    }
    private void roundTrip(String prefix,XmppMessage.Encryption firstMode,XmppMessage.Encryption replyMode)throws Exception {
        String a=prefix+"alice",b=prefix+"bob";ModernSessionIntegrationTest.Inbox ai=new ModernSessionIntegrationTest.Inbox(),bi=new ModernSessionIntegrationTest.Inbox();
        try(XmppSession alice=session(a,611,ai);XmppSession bob=session(b,622,bi)) {
            alice.connect();bob.connect();
            String owner=a+"@localhost",contact=b+"@localhost";
            if(firstMode==XmppMessage.Encryption.OMEMO2) {
                assertFalse(alice.modern().discover(contact).isEmpty());
            } else {
                for(org.jivesoftware.smackx.omemo.internal.OmemoDevice found:alice.devices(contact))
                    assertEquals(org.jivesoftware.smackx.omemo.trust.TrustState.undecided,alice.trustState(found,alice.fingerprint(found)));
            }
            assertThrows(Exception.class,()->alice.send(contact,"No approval yet",firstMode,null));
            alice.setBlindTrustPolicy(contact::equals);bob.setBlindTrustPolicy(owner::equals);
            try(XmppSession other=session(a,633,new ModernSessionIntegrationTest.Inbox())) {
                other.connect();
                if(firstMode==XmppMessage.Encryption.OMEMO2) {
                    Omemo2Bundle bundle=alice.modern().discover(owner).get(1633);
                    assertNotNull(bundle);
                    assertEquals(Omemo2Device.Trust.UNDECIDED,alice.modern().device.trust(owner,1633,bundle.identity()));
                    assertEquals(OmemoSendException.Reason.OWN_DEVICE_TRUST_REQUIRED,assertThrows(OmemoSendException.class,()->
                            EncryptionFallback.select(mode->alice.probeEncryption(contact,mode,false,java.util.Collections.emptySet(),false))).reason);
                    alice.modern().device.decide(owner,1633,bundle.identity(),true);
                } else {
                    org.jivesoftware.smackx.omemo.internal.OmemoDevice own=alice.devices(owner).stream().filter(device->device.getDeviceId()==633).findFirst().orElseThrow(AssertionError::new);
                    assertEquals(OmemoSendException.Reason.OWN_DEVICE_TRUST_REQUIRED,assertThrows(OmemoSendException.class,()->
                            alice.send(contact,"Own copy needs approval",firstMode,null)).reason);
                    alice.decide(own,alice.fingerprint(own),true);
                }
            }
            alice.send(b+"@localhost","Opt-in "+firstMode,firstMode,null);
            XmppMessage first=bi.next();assertTrue(first.readable);assertEquals(firstMode,first.encryption);assertNotNull(first.senderFingerprint);
            assertEquals("Opt-in "+firstMode,first.body);bi.store();
            bob.send(a+"@localhost","Reply "+replyMode,replyMode,null);
            XmppMessage reply=ai.next();assertTrue(reply.readable);assertEquals(replyMode,reply.encryption);assertNotNull(reply.senderFingerprint);
            assertEquals("Reply "+replyMode,reply.body);ai.store();
        }
    }
    @Test void modernSenderCanReadLegacyReplyWithContactOptIn()throws Exception{
        roundTrip("blindmodern",XmppMessage.Encryption.OMEMO2,XmppMessage.Encryption.LEGACY_OMEMO);
    }
    @Test void legacySenderCanReadModernReplyWithContactOptIn()throws Exception{
        roundTrip("blindlegacy",XmppMessage.Encryption.LEGACY_OMEMO,XmppMessage.Encryption.OMEMO2);
    }
}
