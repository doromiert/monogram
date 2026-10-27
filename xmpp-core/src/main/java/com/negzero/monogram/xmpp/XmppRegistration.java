// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.iqregister.packet.Registration;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jivesoftware.smackx.xdata.form.FillableForm;

/** Explicit, single-attempt XEP-0445/0077 registration over verified TLS. */
public final class XmppRegistration {
    private static final String FEATURE="urn:xmpp:ibr-token:0";
    private XmppRegistration() {}
    public static void create(RegistrationInvitation invitation,Account account,String password) throws Exception {
        invitation.validate(account);
        if(password==null || password.isEmpty()) throw new XmppInvitations.InvitationException("Choose a password for your new account.");
        ProviderManager.addStreamFeatureProvider("register",FEATURE,new org.jivesoftware.smack.provider.ExtensionElementProvider<org.jivesoftware.smack.packet.ExtensionElement>() {
            @Override public org.jivesoftware.smack.packet.ExtensionElement parse(org.jivesoftware.smack.xml.XmlPullParser parser,int depth,org.jivesoftware.smack.packet.XmlEnvironment environment)
                    throws java.io.IOException,org.jivesoftware.smack.xml.XmlPullParserException {
                return StandardExtensionElementProvider.INSTANCE.parse(parser,depth,environment);
            }
        });
        XMPPTCPConnectionConfiguration.Builder config=XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(invitation.server).setPort(account.port).setSecurityMode(SecurityMode.required)
            .setSendPresence(false).setCompressionEnabled(false);
        if(account.host!=null) config.setHost(account.host);
        if(account.proxy!=null)config.setProxyInfo(account.proxy);
        if(account.certificateTrust!=null)config.setCustomX509TrustManager(account.certificateTrust);
        XMPPTCPConnection connection=new XMPPTCPConnection(config.build());connection.setReplyTimeout(15000);
        try {
            connection.connect();
            if(!connection.isSecureConnection())
                throw new XmppInvitations.InvitationException("Registration requires a secure connection.");
            if(invitation.requiresToken()) {
                if(!connection.hasFeature("register",FEATURE))
                    throw new XmppInvitations.InvitationException("This server does not offer token-based registration over a secure connection. Use its invitation web page.");
                IQ preauth=new IQ("preauth","urn:xmpp:pars:0") {
                    @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                        xml.attribute("token",invitation.token());xml.setEmptyElement();return xml;
                    }
                };
                preauth.setType(IQ.Type.set);preauth.setTo(invitation.server);
                request(connection,preauth);
            }
            Registration query=new Registration();query.setTo(invitation.server);
            Registration information=request(connection,query);
            DataForm offered=DataForm.from(information);
            if(offered!=null) {
                if(offered.getType()!=DataForm.Type.form || offered.getField("username")==null || offered.getField("password")==null)
                    throw additionalFields();
                for(FormField field:offered.getFields())
                    if(field.isRequired() && field.getType()!=FormField.Type.hidden && !"username".equals(field.getFieldName()) && !"password".equals(field.getFieldName())) throw additionalFields();
                FillableForm form=new FillableForm(offered);form.setAnswer("username",account.jid.getLocalpart());form.setAnswer("password",password);
                Registration submit=new Registration(java.util.Collections.emptyMap());submit.addExtension(form.getDataFormToSubmit());
                submit.setType(IQ.Type.set);submit.setTo(invitation.server);
                request(connection,submit);
            } else {
                if(information.getAttributes()==null) throw additionalFields();
                for(String field:information.getAttributes().keySet())
                    if(!"username".equals(field) && !"password".equals(field)) throw additionalFields();
                java.util.Map<String,String> attributes=new java.util.HashMap<>();
                attributes.put("username",account.jid.getLocalpart().toString());attributes.put("password",password);
                Registration submit=new Registration(attributes);submit.setType(IQ.Type.set);submit.setTo(invitation.server);
                request(connection,submit);
            }
        } finally { connection.disconnect(); }
    }
    private static <T extends IQ> T request(XMPPTCPConnection connection,IQ query) throws Exception {
        // Smack's ordinary IQReplyFilter requires an authenticated local JID.
        String id=query.getStanzaId();
        return connection.createStanzaCollectorAndSend(stanza -> stanza instanceof IQ && id.equals(stanza.getStanzaId())
                && (((IQ)stanza).getType()==IQ.Type.result || ((IQ)stanza).getType()==IQ.Type.error)
                && (stanza.getFrom()==null || connection.getXMPPServiceDomain().equals(stanza.getFrom())),query).nextResultOrThrow();
    }
    private static XmppInvitations.InvitationException additionalFields() {
        return new XmppInvitations.InvitationException("This server asks for additional registration fields. Use its invitation web page.");
    }
}
