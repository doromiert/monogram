package com.negzero.monogram.xmpp;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class EncryptionFallbackTest {
    @Test void prefersModernAndNeverProbesAnUnneededMode() throws Exception {
        List<XmppMessage.Encryption> calls=new ArrayList<>();
        assertEquals(XmppMessage.Encryption.OMEMO2,EncryptionFallback.select(mode->{calls.add(mode);return true;}));
        assertEquals(Collections.singletonList(XmppMessage.Encryption.OMEMO2),calls);
    }
    @Test void encryptionFailureUsesLegacyAndNoSupportUsesPlaintext() throws Exception {
        List<XmppMessage.Encryption> calls=new ArrayList<>();
        assertEquals(XmppMessage.Encryption.LEGACY_OMEMO,EncryptionFallback.select(mode->{calls.add(mode);if(mode==XmppMessage.Encryption.OMEMO2)throw new java.security.GeneralSecurityException();return true;}));
        assertEquals(Arrays.asList(XmppMessage.Encryption.OMEMO2,XmppMessage.Encryption.LEGACY_OMEMO),calls);
        assertEquals(XmppMessage.Encryption.PLAIN,EncryptionFallback.select(mode->false));
    }
    @Test void roomWithoutModernAndWithoutUsableLegacyFallsBackToPlain() throws Exception {
        assertEquals(XmppMessage.Encryption.PLAIN,EncryptionFallback.select(mode->{
            if(mode==XmppMessage.Encryption.OMEMO2)return false;
            throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES);
        }));
    }
    @Test void inaccessibleEncryptionKeysCanUseAnOlderProtocol() throws Exception {
        assertEquals(XmppMessage.Encryption.LEGACY_OMEMO,EncryptionFallback.select(mode->{
            if(mode==XmppMessage.Encryption.OMEMO2) throw new org.jivesoftware.smack.XMPPException.XMPPErrorException(null,
                    org.jivesoftware.smack.packet.StanzaError.getBuilder(org.jivesoftware.smack.packet.StanzaError.Condition.forbidden).build());
            return true;
        }));
    }
    @Test void connectionStorageTimeoutAndMembershipRacesCannotDowngrade() {
        for(Exception failure:Arrays.asList(new org.jivesoftware.smack.SmackException.NotConnectedException(),new java.io.IOException("storage failure"),new java.security.GeneralSecurityException("wrapped storage failure",new java.io.IOException()),
                new OmemoSendException(OmemoSendException.Reason.LOOKUP_TIMEOUT),new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED))) {
            List<XmppMessage.Encryption> calls=new ArrayList<>();
            assertSame(failure,assertThrows(Exception.class,()->EncryptionFallback.select(mode->{calls.add(mode);throw failure;})));
            assertEquals(1,calls.size());
        }
    }
}
