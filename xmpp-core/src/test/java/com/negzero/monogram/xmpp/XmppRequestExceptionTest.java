package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.StanzaError;
import static org.junit.jupiter.api.Assertions.*;

class XmppRequestExceptionTest {
    @Test void loginAuthenticationRejectionDoesNotSuggestCertificateFailureOrEchoServerText() {
        org.jivesoftware.smack.sasl.packet.SaslNonza.SASLFailure sasl=
                new org.jivesoftware.smack.sasl.packet.SaslNonza.SASLFailure("not-authorized",java.util.Collections.singletonMap("en","private authentication details"));
        String text=XmppRequestException.describeLogin(new java.util.concurrent.ExecutionException(
                new org.jivesoftware.smack.sasl.SASLErrorException("PLAIN",sasl)));
        assertTrue(text.contains("not-authorized"));assertTrue(text.contains("active"));
        assertFalse(text.contains("TLS"));assertFalse(text.contains("private authentication details"));
    }
    @Test void loginFindsTlsAndNetworkCausesWithoutExposingProviderMessages() {
        String secret="private endpoint or credentials";
        assertTrue(XmppRequestException.describeLogin(new java.io.IOException(new javax.net.ssl.SSLHandshakeException(secret))).contains("verified TLS"));
        assertTrue(XmppRequestException.describeLogin(new java.io.IOException(new java.net.UnknownHostException(secret))).contains("find the XMPP server"));
        assertTrue(XmppRequestException.describeLogin(new java.net.SocketTimeoutException(secret)).contains("reply in time"));
        assertFalse(XmppRequestException.describeLogin(new java.io.IOException(secret)).contains(secret));
    }
    @Test void loginHandlesUnrecognizedSaslConditionsAndCyclicCausesWithoutEchoingThem() {
        String text=XmppRequestException.describeLogin(new org.jivesoftware.smack.sasl.SASLErrorException("PLAIN",
                new org.jivesoftware.smack.sasl.packet.SaslNonza.SASLFailure("private server details")));
        assertFalse(text.contains("private server details"));assertTrue(text.contains("rejected authentication"));
        RuntimeException first=new RuntimeException(),second=new RuntimeException(first);first.initCause(second);
        assertEquals("Could not finish sign-in (RuntimeException).",XmppRequestException.describeLogin(first));
    }
    @Test void preservesKnownOmemoFailuresWithoutExposingArbitraryCryptoDetails() {
        assertEquals("Invalid OMEMO2 content",XmppRequestException.describe(new java.security.GeneralSecurityException("Invalid OMEMO2 content")));
        assertEquals("OMEMO2 bundle unavailable",XmppRequestException.describe(new java.security.GeneralSecurityException("OMEMO2 bundle unavailable")));
        assertEquals("GeneralSecurityException",XmppRequestException.describe(new java.security.GeneralSecurityException("secret key material")));
    }
    @Test void keepsRejectedOperationAndConditionWithoutEchoingServerContent() {
        XMPPErrorException rejected=new XMPPErrorException(null,StanzaError.getBuilder(StanzaError.Condition.forbidden)
                .setDescriptiveEnText("private server details").build());
        XmppRequestException failure=new XmppRequestException("Room admin list",rejected);
        assertEquals("Room admin list: server replied forbidden (auth)",XmppRequestException.describe(failure));
        assertSame(rejected,failure.getCause());
        assertEquals("File upload slot: server replied forbidden (auth)",new XmppRequestException("File upload slot",rejected).getMessage());
        assertEquals("IOException",XmppRequestException.describe(new java.io.IOException("https://private-upload.invalid/token")));
    }
}
