// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.XMPPException.XMPPErrorException;

/** Request context without retaining stanza bodies, upload credentials or encryption keys. */
public final class XmppRequestException extends Exception {
    public XmppRequestException(String operation, Exception cause) {
        super(operation+": "+describe(cause),cause);
    }
    public static String describe(Throwable error) {
        if(error instanceof XmppRequestException) return error.getMessage();
        if(error instanceof XMPPErrorException) {
            org.jivesoftware.smack.packet.StanzaError stanza=((XMPPErrorException)error).getStanzaError();
            return "server replied "+stanza.getCondition()+" ("+stanza.getType()+")";
        }
        if(error instanceof org.jivesoftware.smack.SmackException.NoResponseException) return "server did not reply";
        return error.getClass().getSimpleName();
    }
}
