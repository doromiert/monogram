// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smackx.commands.AdHocCommand;
import org.jivesoftware.smackx.commands.AdHocCommandManager;
import org.jivesoftware.smackx.commands.RemoteCommand;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jivesoftware.smackx.xdata.form.FillableForm;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Localpart;

/** Blocking XEP-0401 account invitations. Only explicit UI actions execute commands. */
public final class XmppInvitations {
    static final String CREATE="urn:xmpp:invite#create-account", INVITE="urn:xmpp:invite#invite";
    public static final class Invitation {
        public final String uri,link,expires;
        Invitation(String uri,String link,String expires) { this.uri=uri;this.link=link;this.expires=expires; }
    }
    public static final class InvitationException extends Exception {
        public InvitationException(String message) { super(message); }
    }
    public static final class Request {
        public final Invitation invitation;
        public final boolean usernameRequired,rosterSubscription;
        private final XmppInvitations owner;
        private final RemoteCommand command;
        private boolean consumed;
        Request(XmppInvitations owner,RemoteCommand command,Invitation invitation) {
            this.owner=owner;this.command=command;this.invitation=invitation;
            DataForm form=command.getForm();
            FormField username=form==null ? null : form.getField("username");
            usernameRequired=invitation==null && username!=null && username.isRequired();
            rosterSubscription=invitation==null && form!=null && form.getField("roster-subscription")!=null;
        }
    }
    private final XMPPConnection connection;
    XmppInvitations(XMPPConnection connection) { this.connection=connection; }
    public Request begin() throws Exception {
        AdHocCommandManager manager=AdHocCommandManager.getAddHocCommandsManager(connection);
        DomainBareJid server=connection.getXMPPServiceDomain();String node=null;
        for(DiscoverItems.Item item:manager.discoverCommands(server).getItems()) {
            if(!server.equals(item.getEntityID())) continue;
            if(CREATE.equals(item.getNode())) { node=CREATE;break; }
            if(INVITE.equals(item.getNode())) node=INVITE;
        }
        if(node==null) throw new InvitationException("Your server does not advertise account invitations for this account. Use its invite page or ask its administrator.");
        RemoteCommand command=manager.getRemoteCommand(server,node);command.execute();
        if(command.isCompleted()) return new Request(this,command,read(command.getForm(),server));
        try {
            DataForm form=command.getForm();
            if(command.getStatus()!=AdHocCommand.Status.executing || form==null || form.getType()!=DataForm.Type.form || form.getField("username")==null)
                throw new InvitationException("The server returned an unsupported invitation form. Use its invite page.");
            for(FormField field:form.getFields())
                if(field.isRequired() && field.getType()!=FormField.Type.hidden && !"username".equals(field.getFieldName()) && !"roster-subscription".equals(field.getFieldName()))
                    throw new InvitationException("The server needs additional invitation fields. Use its invite page.");
            return new Request(this,command,null);
        } catch(Exception failed) { try { command.cancel(); } catch(Exception ignored) {} throw failed; }
    }
    public Invitation complete(Request request,String username,boolean subscribe) throws Exception {
        synchronized(request) {
            if(request.owner!=this || request.consumed || request.invitation!=null) throw new InvitationException("This invitation request is no longer active. Create a new one.");
            username=username.trim();
            if(username.isEmpty() && request.usernameRequired) throw new InvitationException("Enter a username for the new account.");
            if(!username.isEmpty()) {
                try { username=Localpart.from(username).toString(); }
                catch(Exception invalid) { throw new InvitationException("Enter only the new account's username, without @server or spaces."); }
            }
            FillableForm form=new FillableForm(request.command.getForm());
            form.setAnswer("username",username);
            if(request.rosterSubscription) form.setAnswer("roster-subscription",subscribe);
            request.consumed=true;
            request.command.complete(form);
            if(!request.command.isCompleted()) throw new InvitationException("The server did not finish creating the invitation. Use its invite page.");
            return read(request.command.getForm(),connection.getXMPPServiceDomain());
        }
    }
    public void cancel(Request request) {
        if(request==null || request.owner!=this || request.invitation!=null) return;
        synchronized(request) {
            if(request.consumed) return;request.consumed=true;
            try { request.command.cancel(); } catch(Exception ignored) {}
        }
    }
    static Invitation read(DataForm form,DomainBareJid server) throws InvitationException {
        try {
            String uri=value(form,"uri"),landing=value(form,"landing-url"),expires=value(form,"expire");
            if(expires.isEmpty()) expires=value(form,"expiry");
            URI parsed=new URI(uri);
            if(!"xmpp".equalsIgnoreCase(parsed.getScheme()) || parsed.getRawFragment()!=null) throw new IllegalArgumentException();
            String raw=parsed.getRawSchemeSpecificPart();int query=raw.indexOf('?');
            if(query<=0) throw new IllegalArgumentException();
            if(!server.equals(JidCreate.bareFrom(decode(raw.substring(0,query))).getDomain())) throw new IllegalArgumentException();
            String[] parts=raw.substring(query+1).split(";",-1);Map<String,String> parameters=new HashMap<>();
            for(int i=1;i<parts.length;i++) { int split=parts[i].indexOf('=');if(split<=0) throw new IllegalArgumentException();
                if(parameters.put(decode(parts[i].substring(0,split)),decode(parts[i].substring(split+1)))!=null) throw new IllegalArgumentException(); }
            if(parameters.getOrDefault("preauth","").isEmpty()) throw new IllegalArgumentException();
            if(!"register".equals(parts[0]) && !("roster".equals(parts[0]) && "y".equals(parameters.get("ibr"))))
                throw new InvitationException("Your server returned a contact-only invite. This account is not allowed to invite new registrations; ask the server administrator.");
            String link=uri;
            if(!landing.isEmpty()) {
                URI web=new URI(landing);
                if("https".equalsIgnoreCase(web.getScheme()) && web.getHost()!=null && web.getUserInfo()==null) link=landing;
            }
            return new Invitation(uri,link,expires);
        } catch(InvitationException error) { throw error; }
        catch(Exception invalid) { throw new InvitationException("The server did not return a valid account invitation link."); }
    }
    private static String value(DataForm form,String key) {
        FormField field=form==null ? null : form.getField(key);
        if(field==null || field.getValues().isEmpty()) return "";
        if(field.getValues().size()!=1) throw new IllegalArgumentException();
        String value=field.getValues().get(0).toString();
        if(value.length()>16384 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return value;
    }
    private static String decode(String value) throws Exception { return URLDecoder.decode(value.replace("+","%2B"),"UTF-8"); }
}
