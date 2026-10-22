// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.impl.JidCreate;

/** An invitation is data, never permission to contact a server or create an account. */
public final class RegistrationInvitation {
    public final String uri;
    public final DomainBareJid server;
    public final Localpart username;
    private final String token;
    private RegistrationInvitation(String uri,DomainBareJid server,Localpart username,String token) {
        this.uri=uri;this.server=server;this.username=username;this.token=token;
    }
    String token() { return token; }
    /** Explicit registration on a chosen server; no invitation token or credentials are fabricated. */
    public static RegistrationInvitation forServer(String server) throws Exception {
        DomainBareJid domain=JidCreate.domainBareFrom(server);
        return new RegistrationInvitation("xmpp:"+domain+"?register",domain,null,"");
    }
    public boolean requiresToken() { return !token.isEmpty(); }
    public static RegistrationInvitation parse(String uri) throws XmppInvitations.InvitationException {
        try {
            if(uri==null || uri.length()>16384 || uri.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            URI parsed=new URI(uri);
            if(!"xmpp".equalsIgnoreCase(parsed.getScheme()) || parsed.getRawFragment()!=null) throw new IllegalArgumentException();
            String raw=parsed.getRawSchemeSpecificPart();int query=raw.indexOf('?');
            if(query<=0) throw new IllegalArgumentException();
            Jid address=JidCreate.from(decode(raw.substring(0,query)));
            if(!address.isEntityBareJid() && !address.isDomainBareJid()) throw new IllegalArgumentException();
            String[] parts=raw.substring(query+1).split(";",-1);Map<String,String> parameters=new HashMap<>();
            for(int i=1;i<parts.length;i++) {
                int split=parts[i].indexOf('=');if(split<=0) throw new IllegalArgumentException();
                if(parameters.put(decode(parts[i].substring(0,split)),decode(parts[i].substring(split+1)))!=null) throw new IllegalArgumentException();
            }
            String token=parameters.getOrDefault("preauth","");
            if(token.isEmpty() || token.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            if(!"register".equals(parts[0]) && !("roster".equals(parts[0]) && address.isEntityBareJid() && "y".equals(parameters.get("ibr"))))
                throw new XmppInvitations.InvitationException("This is not an account-registration invitation. Ask for a link that allows a new account.");
            Localpart username="register".equals(parts[0]) && address.isEntityBareJid() ? address.asEntityBareJidOrThrow().getLocalpart() : null;
            return new RegistrationInvitation(uri,address.asDomainBareJid(),username,token);
        } catch(XmppInvitations.InvitationException error) { throw error; }
        catch(Exception invalid) { throw new XmppInvitations.InvitationException("Enter a valid xmpp: account-invitation link."); }
    }
    public void validate(Account account) throws XmppInvitations.InvitationException {
        if(!server.equals(account.jid.asDomainBareJid()) || username!=null && !username.equals(account.jid.getLocalpart()))
            throw new XmppInvitations.InvitationException(username==null ? "Use the server named in this invitation." : "This invitation reserves a specific account. Keep its original address.");
    }
    private static String decode(String value) throws Exception { return URLDecoder.decode(value.replace("+","%2B"),"UTF-8"); }
}
