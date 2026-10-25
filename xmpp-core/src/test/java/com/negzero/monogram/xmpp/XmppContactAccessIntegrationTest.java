package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smack.roster.Roster;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppContactAccessIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device,BlockingQueue<String> requests) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Access-"+device),"monogram-test",device,new File(root,"access-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) {}
            public void contactRequest(String address,boolean pending) {if(pending)requests.add(address);}
        });
    }
    void await(BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!ready.getAsBoolean() && System.nanoTime()<end)Thread.sleep(25);
        assertTrue(ready.getAsBoolean());
    }
    boolean sees(XmppSession session,String address) {try{return session.contacts.state(address).seesTheirPresence;}catch(Exception unavailable){return false;}}
    boolean approved(XmppSession session,String address) {try{return session.contacts.state(address).allowRequests;}catch(Exception unavailable){return false;}}
    @Test void manualAccessCanApproveOnlyOneDirectionAndPreapproveOrRevokeBeforeAnyRequest() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
        String a="aclallowalice@localhost",b="aclallowbob@localhost";
        BlockingQueue<String> ar=new LinkedBlockingQueue<>(),br=new LinkedBlockingQueue<>();
        try(XmppSession alice=session("aclallowalice",1731,ar);XmppSession bob=session("aclallowbob",1732,br)) {
            alice.connect();bob.connect();
            for(String node:new String[]{"eu.siacs.conversations.axolotl.devicelist","eu.siacs.conversations.axolotl.bundles:"+bob.ownDeviceId()}) {
                org.jivesoftware.smackx.pubsub.Node published=org.jivesoftware.smackx.pubsub.PubSubManager.getInstanceFor(bob.connection(),JidCreate.entityBareFrom(b)).getNode(node);
                org.jivesoftware.smackx.pubsub.form.FillableConfigureForm form=published.getNodeConfiguration().getFillableForm();form.setAccessModel(org.jivesoftware.smackx.pubsub.AccessModel.presence);published.sendConfigurationForm(form);
            }
            assertThrows(org.jivesoftware.smack.XMPPException.XMPPErrorException.class,()->alice.devices(b));
            Roster roster=Roster.getInstanceFor(alice.connection());roster.createItem(JidCreate.bareFrom(b),"Keep alias",new String[]{"Friends"});
            bob.contacts.act(a,XmppContacts.Action.ALLOW);await(()->approved(bob,a));
            assertFalse(bob.contacts.state(a).sharesOwnPresence,"Pre-approval waits for the contact to request access");
            assertEquals(java.util.Collections.singleton(a),bob.contacts.allowedContacts());
            // This is explicit permission, not a received request or an active subscription yet.
            assertFalse(bob.contacts.state(a).incomingPending);
            assertNull(ar.poll(200,TimeUnit.MILLISECONDS),"Granting access must not request reciprocal access");
            Roster brs=Roster.getInstanceFor(bob.connection());
            brs.createItem(JidCreate.bareFrom(a),"Keep Alice",new String[]{"Family"});brs.reloadAndWait();
            if(brs.isSubscriptionPreApprovalSupported())brs.preApprove(JidCreate.bareFrom(a));
            bob.contacts.act(a,XmppContacts.Action.REVOKE);await(()->!approved(bob,a));
            assertEquals("Keep Alice",brs.getEntry(JidCreate.bareFrom(a)).getName());
            assertTrue(brs.getEntry(JidCreate.bareFrom(a)).getGroups().stream().anyMatch(group->"Family".equals(group.getName())));
            alice.contacts.act(b,XmppContacts.Action.REQUEST);assertEquals(a,br.poll(10,TimeUnit.SECONDS));
            assertTrue(bob.contacts.state(a).incomingPending);assertFalse(bob.contacts.state(a).sharesOwnPresence);
            bob.contacts.act(a,XmppContacts.Action.ALLOW);await(()->sees(alice,b));
            assertTrue(bob.contacts.state(a).sharesOwnPresence);assertFalse(bob.contacts.state(a).seesTheirPresence);
            assertFalse(alice.contacts.state(b).sharesOwnPresence);assertFalse(bob.contacts.state(a).incomingPending);
            assertFalse(alice.devices(b).isEmpty(),"A real one-way grant must permit presence-protected PEP reads");
            assertNull(ar.poll(200,TimeUnit.MILLISECONDS),"Allow approves only their request");
            assertEquals("Keep alias",roster.getEntry(JidCreate.bareFrom(b)).getName());
            assertTrue(roster.getEntry(JidCreate.bareFrom(b)).getGroups().stream().anyMatch(group->"Friends".equals(group.getName())));
            bob.contacts.act(a,XmppContacts.Action.REVOKE);await(()->!sees(alice,b));
            assertFalse(alice.contacts.presence(b).visible);
            assertThrows(org.jivesoftware.smack.XMPPException.XMPPErrorException.class,()->alice.devices(b));
            bob.contacts.act(a,XmppContacts.Action.ALLOW);await(()->approved(bob,a));
            alice.contacts.act(b,XmppContacts.Action.REQUEST);await(()->sees(alice,b));
            assertNull(br.poll(200,TimeUnit.MILLISECONDS),"An explicitly allowed contact does not need another manual approval");
            await(()->{try{return bob.contacts.state(a).sharesOwnPresence;}catch(Exception unavailable){return false;}});
            assertFalse(bob.contacts.state(a).seesTheirPresence);assertFalse(alice.contacts.state(b).outgoingPending);
            bob.contacts.act(a,XmppContacts.Action.ALLOW);assertNull(ar.poll(200,TimeUnit.MILLISECONDS));
            bob.contacts.act(a,XmppContacts.Action.REVOKE);await(()->!sees(alice,b));
            java.util.Set<String> saved=java.util.Collections.singleton(a);
            bob.contacts.restoreAllowedContacts(java.util.Collections.emptySet());
            try(XmppSession otherDevice=session("aclallowbob",1733,new LinkedBlockingQueue<>())) {
                otherDevice.contacts.restoreAllowedContacts(saved);otherDevice.connect();assertTrue(otherDevice.contacts.state(a).allowRequests);
                alice.contacts.act(b,XmppContacts.Action.REQUEST);await(()->sees(alice,b));
                await(()->{try{return otherDevice.contacts.state(a).sharesOwnPresence;}catch(Exception unavailable){return false;}});
                otherDevice.contacts.act(a,XmppContacts.Action.REVOKE);await(()->!sees(alice,b));
            }
            assertThrows(IllegalArgumentException.class,()->bob.contacts.act(b,XmppContacts.Action.ALLOW));
            assertThrows(IllegalArgumentException.class,()->bob.contacts.act(a+"/device",XmppContacts.Action.ALLOW));
        }
    }
}
