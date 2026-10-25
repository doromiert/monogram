// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smackx.commands.AdHocCommand.Action;
import org.jivesoftware.smackx.commands.AdHocCommand.Status;
import org.jivesoftware.smackx.commands.packet.AdHocCommandData;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jivesoftware.smackx.xdata.*;
import org.jivesoftware.smackx.xdata.form.FillableForm;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;

/** XEP-0030 browser and explicitly initiated XEP-0050 command sessions. Blocking API. */
public final class XmppDeveloperTools {
    public static final String COMMANDS="http://jabber.org/protocol/commands";
    private final XMPPConnection connection;
    public XmppDeveloperTools(XMPPConnection connection) { this.connection=Objects.requireNonNull(connection); }
    public static final class Discovery {
        public final DiscoverInfo info;
        public final DiscoverItems items;
        public final String infoError,itemsError;
        Discovery(DiscoverInfo info,DiscoverItems items,String infoError,String itemsError) {
            this.info=info;this.items=items;this.infoError=infoError;this.itemsError=itemsError;
        }
    }
    public Discovery discover(String address,String node) throws Exception {
        Jid jid=target(address);String selected=node(node);check();
        ServiceDiscoveryManager manager=ServiceDiscoveryManager.getInstanceFor(connection);
        DiscoverInfo info=null;DiscoverItems items=null;String infoError=null,itemsError=null;
        try { info=manager.discoverInfo(jid,selected); } catch(Exception error) { infoError=XmppRequestException.describe(error); }
        check();
        try { items=manager.discoverItems(jid,selected); } catch(Exception error) { itemsError=XmppRequestException.describe(error); }
        check();return new Discovery(info,items,infoError,itemsError);
    }
    public static Jid target(String address) throws Exception {
        if(address==null || address.trim().isEmpty()) throw new IllegalArgumentException("Enter an XMPP address or server domain.");
        String value=address.trim(),bare=value.split("/",2)[0];
        if(bare.indexOf('@')!=bare.lastIndexOf('@') || bare.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Enter a valid XMPP address or server domain.");
        return JidCreate.from(value);
    }
    private static String node(String node) {
        if(node==null || node.isEmpty()) return null;
        if(node.length()>4096) throw new IllegalArgumentException("The node is too long.");
        return node;
    }
    private void check() { if(!connection.isAuthenticated()) throw new IllegalStateException("XMPP is disconnected. Reconnect and try again."); }
    public static final class Command {
        private final XMPPConnection owner;
        public final Jid target;
        public final String node;
        private volatile AdHocCommandData response;
        private volatile boolean uncertain;
        private Command(XMPPConnection owner,Jid target,String node) { this.owner=owner;this.target=target;this.node=node; }
        public AdHocCommandData response() { return response; }
        public boolean active() { return !uncertain && response!=null && response.getStatus()==Status.executing; }
    }
    public Command start(String address,String node) throws Exception {
        Jid jid=target(address);String selected=node(node);
        if(selected==null) throw new IllegalArgumentException("Choose a command node.");
        Command command=new Command(connection,jid,selected);
        exchange(command,Action.execute,null);return command;
    }
    public Command advance(Command command,Action action,Map<String,List<String>> answers) throws Exception {
        synchronized(command) {
            if(command.owner!=connection || !command.active()) throw new IllegalStateException("This command session has ended. Start a new command.");
            if(!actions(command.response).contains(action)) throw new IllegalArgumentException("This action is not offered by the command.");
            DataForm form=null;
            if(action!=Action.cancel && action!=Action.prev) form=submit(command.response.getForm(),answers);
            exchange(command,action,form);return command;
        }
    }
    private void exchange(Command command,Action action,DataForm form) throws Exception {
        check();AdHocCommandData request=new AdHocCommandData();request.setType(IQ.Type.set);
        request.setTo(command.target);request.setNode(command.node);request.setAction(action);
        if(command.response!=null) request.setSessionID(command.response.getSessionID());
        request.setForm(form);
        try {
            IQ result=connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
            if(!(result instanceof AdHocCommandData)) throw new IllegalStateException("The service returned an invalid command response.");
            AdHocCommandData received=(AdHocCommandData)result;
            if(!command.node.equals(received.getNode()) || received.getStatus()==null
                    || received.getStatus()==Status.executing && (received.getSessionID()==null || received.getSessionID().isEmpty())
                    || command.response!=null && !Objects.equals(command.response.getSessionID(),received.getSessionID()))
                throw new IllegalStateException("The service returned an invalid command session.");
            command.response=received;
        } catch(org.jivesoftware.smack.XMPPException.XMPPErrorException rejected) { throw rejected; }
        catch(Exception error) { command.uncertain=true;throw error; }
    }
    public static List<Action> actions(AdHocCommandData response) {
        if(response==null || response.getStatus()!=Status.executing) return Collections.emptyList();
        LinkedHashSet<Action> actions=new LinkedHashSet<>();
        for(Action action:response.getActions()) if(action==Action.next || action==Action.prev || action==Action.complete) actions.add(action);
        // An omitted <actions/> means execute defaults to complete (XEP-0050 section 3.4).
        if(actions.isEmpty()) actions.add(Action.complete);
        actions.add(Action.cancel);return new ArrayList<>(actions);
    }
    public static List<FormField.Option> options(FormField field) {
        return field instanceof FormFieldWithOptions ? ((FormFieldWithOptions)field).getOptions() : Collections.emptyList();
    }
    /** Preserve hidden fields, validate required answers and let Smack validate XEP-0122/JIDs. */
    public static DataForm submit(DataForm prototype,Map<String,List<String>> answers) {
        if(prototype==null || prototype.getType()!=DataForm.Type.form) return null;
        FillableForm filled=new FillableForm(prototype);
        for(FormField field:prototype.getFields()) {
            FormField.Type type=field.getType();String key=field.getFieldName();
            if(type==FormField.Type.fixed || FormField.FORM_TYPE.equals(key)) continue;
            List<String> values=type==FormField.Type.hidden ? field.getValuesAsString()
                    : answers!=null && answers.containsKey(key) ? answers.get(key) : field.getValuesAsString();
            if(values==null) values=Collections.emptyList();
            if(field.isRequired() && (values.isEmpty() || values.stream().allMatch(String::isEmpty)))
                throw new IllegalArgumentException("Fill in "+(field.getLabel()==null ? key : field.getLabel())+".");
            if(values.stream().anyMatch(value -> value.length()>65536)) throw new IllegalArgumentException("An answer is too long.");
            if(type==FormField.Type.jid_single || type==FormField.Type.jid_multi) for(String value:values) {
                if(value.isEmpty() && !field.isRequired()) continue;
                try {target(value);} catch(Exception invalid) {throw new IllegalArgumentException("Enter a valid XMPP address for "+key+".");}
            }
            List<FormField.Option> options=options(field);
            if(!options.isEmpty()) for(String value:values) {
                if(value.isEmpty() && !field.isRequired()) continue;
                if(options.stream().noneMatch(option -> option.getValueString().equals(value))) throw new IllegalArgumentException("Choose an offered option for "+key+".");
            }
            if(type==FormField.Type.list_multi || type==FormField.Type.jid_multi || type==FormField.Type.text_multi) {
                // An empty multi-value field is a deliberate clear, not an omitted/default answer.
                if(values.isEmpty()) {
                    // FillableForm rejects empty fields; append them to the final submit below.
                    continue;
                }
                filled.setAnswer(key,values);
            } else if(type==FormField.Type.bool && (values.isEmpty() || values.get(0).isEmpty())) {
                filled.setAnswer(key,false);
            } else if(type==FormField.Type.jid_single && (values.isEmpty() || values.get(0).isEmpty())) {
                // FilledForm cannot represent an optional empty JID; append it below.
            } else filled.setAnswer(key,values.isEmpty() ? "" : values.get(0));
        }
        DataForm submitted=filled.getDataFormToSubmit();DataForm.Builder result=submitted.asBuilder();
        for(FormField field:prototype.getFields()) {
            if(field.getType()==FormField.Type.fixed || submitted.hasField(field.getFieldName())) continue;
            FormField.Type type=field.getType();String key=field.getFieldName();
            if(type==FormField.Type.list_multi) result.addField(FormField.listMultiBuilder(key).build());
            else if(type==FormField.Type.jid_multi) result.addField(FormField.jidMultiBuilder(key).build());
            else if(type==FormField.Type.text_multi) result.addField(FormField.textMultiBuilder(key).build());
            else if(type==FormField.Type.jid_single) result.addField(FormField.jidSingleBuilder(key).build());
        }
        return result.build();
    }
}
