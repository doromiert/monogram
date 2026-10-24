// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.XMPPException.XMPPErrorException;

/** Request context without retaining stanza bodies, upload credentials or encryption keys. */
public final class XmppRequestException extends Exception {
    public XmppRequestException(String operation, Exception cause) {
        super(operation+": "+describe(cause),cause);
    }
    /** Fixed local descriptions: never display SASL text, credentials or raw server responses. */
    public static String describeLogin(Throwable error) {
        java.util.ArrayDeque<Throwable> pending=new java.util.ArrayDeque<>();
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if(error!=null) pending.add(error);
        boolean tls=false,dns=false,timeout=false,connection=false;
        while(!pending.isEmpty() && seen.size()<32) {
            Throwable cause=pending.removeFirst();if(!seen.add(cause)) continue;
            if(cause instanceof org.jivesoftware.smack.sasl.SASLErrorException) {
                org.jivesoftware.smack.sasl.packet.SaslNonza.SASLFailure failure=
                        ((org.jivesoftware.smack.sasl.SASLErrorException)cause).getSASLFailure();
                String condition=failure==null ? "" : failure.getSASLErrorString();
                if("not-authorized".equals(condition))
                    return "The server rejected your sign-in (not-authorized). Check your full XMPP address and password, and whether the account is active.";
                if("account-disabled".equals(condition)) return "The server has disabled this account. Contact the server administrator.";
                if("temporary-auth-failure".equals(condition)) return "The server's sign-in service is temporarily unavailable. Try again later.";
                if("credentials-expired".equals(condition)) return "Your credentials have expired. Update them on the server's account website.";
                return "The server rejected authentication. Check your account details or contact the server administrator.";
            }
            if(cause instanceof org.jivesoftware.smack.SmackException.SecurityRequiredException
                    || cause instanceof org.jivesoftware.smack.SmackException.SecurityNotPossibleException)
                return "The server did not offer a usable secure connection. Monogram requires TLS before signing in.";
            tls|=cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException;
            dns|=cause instanceof java.net.UnknownHostException;
            timeout|=cause instanceof java.net.SocketTimeoutException || cause instanceof org.jivesoftware.smack.SmackException.NoResponseException;
            connection|=cause instanceof java.net.ConnectException || cause instanceof org.jivesoftware.smack.SmackException.ConnectionException;
            if(cause.getCause()!=null) pending.addLast(cause.getCause());
            if(cause instanceof org.jivesoftware.smack.SmackException.EndpointConnectionException) {
                org.jivesoftware.smack.SmackException.EndpointConnectionException endpoints=
                        (org.jivesoftware.smack.SmackException.EndpointConnectionException)cause;
                for(org.jivesoftware.smack.util.rce.RemoteConnectionEndpointLookupFailure lookup:endpoints.getLookupFailures())
                    if(lookup.getException()!=null && pending.size()<32) pending.addLast(lookup.getException());
                for(org.jivesoftware.smack.util.rce.RemoteConnectionException<?> endpoint:endpoints.getConnectionExceptions())
                    if(endpoint.getException()!=null && pending.size()<32) pending.addLast(endpoint.getException());
            }
        }
        if(tls) return "Could not establish a verified TLS connection. Check the device date and the server's certificate; connection security is still enforced.";
        if(dns) return "Could not find the XMPP server. Check your connection, server domain and any advanced host override.";
        if(timeout) return "The XMPP server did not reply in time. Check your connection or try again later.";
        if(connection) return "Could not connect to the XMPP server. Check your network and the server's availability.";
        return "Could not finish sign-in ("+(error==null ? "unknown error" : error.getClass().getSimpleName())+").";
    }
    public static String describe(Throwable error) {
        if(error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException) {
            if(error.getCause()!=null) return describe(error.getCause());
        }
        if(error instanceof OmemoSendException && ((OmemoSendException)error).reason==OmemoSendException.Reason.CONTACT_AUTHORIZATION_REQUIRED)
            return "contact authorization is required";
        if(error instanceof Omemo2Native.Failure) return "OMEMO2 ratchet failed ("+((Omemo2Native.Failure)error).code+")";
        if(error instanceof Omemo2Bundle.InvalidKeyId)return error.getMessage();
        if(error instanceof java.security.GeneralSecurityException) {
            // Only expose fixed local diagnostics, never arbitrary provider/server messages.
            String detail=error.getMessage();
            if(detail!=null) switch(detail) {
                case "Invalid OMEMO2 content":
                case "OMEMO2 bundle unavailable":
                case "OMEMO2 identity changed; refresh before approving":
                case "Incomplete OMEMO2 state; existing identity was preserved":
                case "Unsupported protected content":
                case "OMEMO2 message is not addressed to this device":
                case "OMEMO2 message is not addressed to an active or recovery device": return detail;
            }
        }
        if(error instanceof XmppRequestException) return error.getMessage();
        if(error instanceof XMPPErrorException) {
            org.jivesoftware.smack.packet.StanzaError stanza=((XMPPErrorException)error).getStanzaError();
            return "server replied "+stanza.getCondition()+" ("+stanza.getType()+")";
        }
        if(error instanceof org.jivesoftware.smack.SmackException.NoResponseException) return "server did not reply";
        return error.getClass().getSimpleName();
    }
}
