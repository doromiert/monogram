package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler;
import org.jivesoftware.smack.iqrequest.IQRequestHandler.Mode;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.StanzaError;
import org.jivesoftware.smackx.commands.AdHocCommand.Action;
import org.jivesoftware.smackx.commands.AdHocCommand.Status;
import org.jivesoftware.smackx.commands.AdHocCommandNote;
import org.jivesoftware.smackx.commands.packet.AdHocCommandData;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.AbstractNodeInformationProvider;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppDeveloperToolsIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Developer-"+device),"monogram-test",device,new File(root,"developer-"+device),(from,id,body,encrypted)->{});
    }
    @Test void discoveryAndMultiStepCommandsRoundTripOverRealXmppWithoutImplicitExecution() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
        try(XmppSession client=session("presentationalice",1721);XmppSession service=session("presentationbob",1722)) {
            client.connect();service.connect();String address=service.connection().getUser().toString();
            AtomicInteger runs=new AtomicInteger(),submits=new AtomicInteger(),cancels=new AtomicInteger();
            ServiceDiscoveryManager discovery=ServiceDiscoveryManager.getInstanceFor(service.connection());
            discovery.setNodeInformationProvider(XmppDeveloperTools.COMMANDS,new AbstractNodeInformationProvider() {
                public List<DiscoverItems.Item> getNodeItems() {DiscoverItems.Item item=new DiscoverItems.Item(service.connection().getUser());item.setNode("urn:test:developer");item.setName("Test configuration");return Collections.singletonList(item);}
            });
            service.connection().registerIQRequestHandler(new AbstractIqRequestHandler("command",XmppDeveloperTools.COMMANDS,IQ.Type.set,Mode.sync) {
                public IQ handleIQRequest(IQ iq) {
                    AdHocCommandData request=(AdHocCommandData)iq;
                    if(!"urn:test:developer".equals(request.getNode())) return IQ.createErrorResponse(iq,StanzaError.getBuilder(StanzaError.Condition.item_not_found));
                    AdHocCommandData reply=new AdHocCommandData();reply.setType(IQ.Type.result);reply.setStanzaId(iq.getStanzaId());reply.setTo(iq.getFrom());reply.setNode(request.getNode());reply.setSessionID("test-session");
                    Action action=request.getAction();
                    if(action==Action.execute) {runs.incrementAndGet();first(reply);}
                    else {
                        assertEquals("test-session",request.getSessionID());
                        if(action==Action.next) {
                            submits.incrementAndGet();assertEquals(DataForm.Type.submit,request.getForm().getType());
                            assertEquals("original-token",request.getForm().getField("token").getFirstValue());
                            assertEquals("Alice",request.getForm().getField("name").getFirstValue());
                            reply.setStatus(Status.executing);reply.addAction(Action.prev);reply.addAction(Action.complete);reply.setExecuteAction(Action.complete);
                            reply.setForm(DataForm.builder(DataForm.Type.form).addField(FormField.booleanBuilder("confirm").setRequired().build()).build());
                        } else if(action==Action.prev) {assertNull(request.getForm());first(reply);}
                        else if(action==Action.complete) {
                            assertEquals("1",request.getForm().getField("confirm").getFirstValue());reply.setStatus(Status.completed);
                            reply.addNote(new AdHocCommandNote(AdHocCommandNote.Type.info,"Saved"));
                            reply.setForm(DataForm.builder(DataForm.Type.result).addField(FormField.textSingleBuilder("result").setValue("Done").build()).build());
                        } else {assertEquals(Action.cancel,action);assertNull(request.getForm());cancels.incrementAndGet();reply.setStatus(Status.canceled);}
                    }
                    return reply;
                }
                private void first(AdHocCommandData reply) {
                    reply.setStatus(Status.executing);reply.addAction(Action.next);reply.setExecuteAction(Action.next);
                    reply.setForm(DataForm.builder(DataForm.Type.form).setFormType("urn:test:configuration")
                        .addField(FormField.hiddenBuilder("token").setValue("original-token").build())
                        .addField(FormField.textSingleBuilder("name").setRequired().build()).build());
                }
            });
            XmppDeveloperTools tools=new XmppDeveloperTools(client.connection());
            XmppDeveloperTools.Discovery root=tools.discover("localhost",null);assertNotNull(root.info);assertNotNull(root.items);
            XmppDeveloperTools.Discovery commands=tools.discover(address,XmppDeveloperTools.COMMANDS);
            assertNull(commands.itemsError);assertEquals("urn:test:developer",commands.items.getItems().get(0).getNode());assertEquals(0,runs.get());
            XmppDeveloperTools.Command command=tools.start(address,"urn:test:developer");assertTrue(command.active());assertEquals(1,runs.get());
            assertThrows(IllegalArgumentException.class,()->tools.advance(command,Action.complete,Collections.emptyMap()));
            assertThrows(IllegalArgumentException.class,()->tools.advance(command,Action.next,Collections.emptyMap()));assertEquals(0,submits.get());
            Map<String,List<String>> answers=Collections.singletonMap("name",Collections.singletonList("Alice"));
            tools.advance(command,Action.next,answers);tools.advance(command,Action.prev,Collections.emptyMap());
            tools.advance(command,Action.next,answers);tools.advance(command,Action.complete,Collections.singletonMap("confirm",Collections.singletonList("1")));
            assertFalse(command.active());assertEquals("Done",command.response().getForm().getField("result").getFirstValue());
            assertThrows(IllegalStateException.class,()->tools.advance(command,Action.complete,Collections.emptyMap()));
            XmppDeveloperTools.Command cancel=tools.start(address,"urn:test:developer");
            assertThrows(IllegalStateException.class,()->new XmppDeveloperTools(service.connection()).advance(cancel,Action.cancel,Collections.emptyMap()));
            tools.advance(cancel,Action.cancel,Collections.emptyMap());assertEquals(1,cancels.get());assertFalse(cancel.active());
            assertThrows(org.jivesoftware.smack.XMPPException.XMPPErrorException.class,()->tools.start(address,"missing"));
            XmppDeveloperTools.Discovery missing=tools.discover(address,"missing");assertNotNull(missing.infoError);assertNotNull(missing.itemsError);
        }
    }
}
