// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

/** Probe only: a failed/uncertain delivery must never be resent as plaintext. */
public final class EncryptionFallback {
    public interface Probe { boolean available(XmppMessage.Encryption mode) throws Exception; }
    private EncryptionFallback() {}
    public static XmppMessage.Encryption select(Probe probe) throws Exception {
        for(XmppMessage.Encryption mode:new XmppMessage.Encryption[]{XmppMessage.Encryption.OMEMO2,XmppMessage.Encryption.LEGACY_OMEMO}) {
            try { if(probe.available(mode)) return mode; }
            catch(Exception failed) { if(!encryptionFailure(failed)) throw failed; }
        }
        return XmppMessage.Encryption.PLAIN;
    }
    private static boolean encryptionFailure(Exception error) {
        Throwable cause=error;
        for(int depth=0;cause!=null && depth<8;depth++,cause=cause.getCause()) {
            if(cause instanceof java.io.IOException || cause instanceof InterruptedException
                    || cause instanceof org.jivesoftware.smack.SmackException.NoResponseException
                    || cause instanceof org.jivesoftware.smack.SmackException.NotConnectedException) return false;
            if(cause.getCause()==cause) break;
        }
        if(error instanceof OmemoSendException) {
            OmemoSendException.Reason reason=((OmemoSendException)error).reason;
            return reason!=OmemoSendException.Reason.LOOKUP_TIMEOUT && reason!=OmemoSendException.Reason.ROOM_CHANGED;
        }
        if(error instanceof java.security.GeneralSecurityException
                || error.getClass().getName().startsWith("org.jivesoftware.smackx.omemo.exceptions.")) return true;
        if(error instanceof org.jivesoftware.smack.XMPPException.XMPPErrorException) {
            org.jivesoftware.smack.packet.StanzaError.Condition condition=((org.jivesoftware.smack.XMPPException.XMPPErrorException)error).getStanzaError().getCondition();
            return condition==org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found
                    || condition==org.jivesoftware.smack.packet.StanzaError.Condition.feature_not_implemented
                    || condition==org.jivesoftware.smack.packet.StanzaError.Condition.forbidden
                    || condition==org.jivesoftware.smack.packet.StanzaError.Condition.not_authorized
                    || condition==org.jivesoftware.smack.packet.StanzaError.Condition.subscription_required;
        }
        return false;
    }
}
