package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.StanzaError;
import static org.junit.jupiter.api.Assertions.*;

class XmppRequestExceptionTest {
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
