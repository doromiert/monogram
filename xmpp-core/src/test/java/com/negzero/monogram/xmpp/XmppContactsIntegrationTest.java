package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppContactsIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device,BlockingQueue<String> requests,BlockingQueue<String> cancellations,BlockingQueue<String> messages) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Contacts-"+device),
                "monogram-test",device,new File(root,"contact-trust-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) { if(encrypted) messages.add(body); }
            public void contactRequest(String address,boolean pending) { (pending ? requests : cancellations).add(address); }
        });
    }
    void await(BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!ready.getAsBoolean() && System.nanoTime()<end) Thread.sleep(25);
        assertTrue(ready.getAsBoolean());
    }
    @Test void manualRequestsUnlockPresenceProtectedKeysWithoutLosingRosterMetadata() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),com.negzero.monogram.xmpp.LegacyOmemoTestDatabase.shared());
        BlockingQueue<String> aliceRequests=new LinkedBlockingQueue<>(),bobRequests=new LinkedBlockingQueue<>(),aliceCancelled=new LinkedBlockingQueue<>(),bobCancelled=new LinkedBlockingQueue<>(),messages=new LinkedBlockingQueue<>();
        String a="subscribealice@localhost",b="subscribebob@localhost";
        try(XmppSession alice=session("subscribealice",801,aliceRequests,aliceCancelled,messages); XmppSession bob=session("subscribebob",802,bobRequests,bobCancelled,messages)) {
            alice.connect(); bob.connect();
            for(String node:new String[]{"eu.siacs.conversations.axolotl.devicelist","eu.siacs.conversations.axolotl.bundles:"+bob.ownDeviceId()}) {
                Node published=PubSubManager.getInstanceFor(bob.connection(),JidCreate.entityBareFrom(b)).getNode(node);
                org.jivesoftware.smackx.pubsub.form.FillableConfigureForm form=published.getNodeConfiguration().getFillableForm(); form.setAccessModel(AccessModel.presence); published.sendConfigurationForm(form);
            }
            assertEquals(org.jivesoftware.smack.packet.StanzaError.Condition.forbidden,assertThrows(org.jivesoftware.smack.XMPPException.XMPPErrorException.class,() -> alice.devices(b)).getStanzaError().getCondition());
            assertFalse(alice.contacts.presence(b).visible);
            assertEquals(0,alice.contacts.lastSeen(b).lastSeen);
            Roster roster=Roster.getInstanceFor(alice.connection());
            roster.createItem(JidCreate.bareFrom(b),"Bob alias",new String[]{"Friends"});
            await(() -> roster.getEntry(JidCreate.entityBareFromOrThrowUnchecked(b))!=null && "Bob alias".equals(roster.getEntry(JidCreate.entityBareFromOrThrowUnchecked(b)).getName()));
            alice.contacts.act(b,XmppContacts.Action.REQUEST);
            assertEquals(a,bobRequests.poll(10,TimeUnit.SECONDS));
            await(() -> roster.getEntry(JidCreate.entityBareFromOrThrowUnchecked(b)).isSubscriptionPending());
            assertFalse(bob.contacts.state(a).sharesOwnPresence,"An incoming request must wait for an explicit decision");
            bob.contacts.act(a,XmppContacts.Action.ACCEPT);
            assertEquals(b,aliceRequests.poll(10,TimeUnit.SECONDS));
            alice.contacts.act(b,XmppContacts.Action.ACCEPT);
            await(() -> {
                try { return alice.contacts.state(b).seesTheirPresence && alice.contacts.state(b).sharesOwnPresence && bob.contacts.state(a).seesTheirPresence && bob.contacts.state(a).sharesOwnPresence; }
                catch(Exception unavailable) { return false; }
            });
            assertEquals("Bob alias",roster.getEntry(JidCreate.bareFrom(b)).getName());
            assertTrue(roster.getEntry(JidCreate.bareFrom(b)).getGroups().stream().anyMatch(group -> "Friends".equals(group.getName())));
            for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:alice.devices(b)) alice.decide(device,alice.fingerprint(device),true);
            alice.send(b,"Encrypted after authorization",true); assertEquals("Encrypted after authorization",messages.poll(10,TimeUnit.SECONDS));
            await(() -> { try { return alice.contacts.presence(b).online; } catch(Exception unavailable) { return false; } });
            try(XmppSession phone=session("subscribebob",803,bobRequests,bobCancelled,messages)) {
                phone.connect();
                bob.connection().sendStanza(new org.jivesoftware.smack.packet.Presence(org.jivesoftware.smack.packet.Presence.Type.unavailable));
                await(() -> roster.getPresence(JidCreate.entityBareFromOrThrowUnchecked(b)).getFrom().equals(phone.connection().getUser()));
                assertTrue(alice.contacts.presence(b).online,"Another available resource must keep the contact online");
                assertEquals(0,alice.contacts.lastSeen(b).lastSeen,"Never interpret online idle time as an offline last-seen date");
            }
            await(() -> { try { return !alice.contacts.presence(b).online; } catch(Exception unavailable) { return false; } });
            assertTrue(alice.contacts.presence(b).visible);
            Thread.sleep(1100);
            XmppContacts.PresenceState offline=alice.contacts.lastSeen(b);
            assertTrue(offline.lastSeen>0 && offline.lastSeen<=System.currentTimeMillis()/1000);
            bob.connection().sendStanza(new org.jivesoftware.smack.packet.Presence(org.jivesoftware.smack.packet.Presence.Type.available));
            await(() -> { try { return alice.contacts.presence(b).online; } catch(Exception unavailable) { return false; } });
            aliceRequests.clear(); bobRequests.clear(); aliceCancelled.clear(); bobCancelled.clear();
            bob.contacts.act(a,XmppContacts.Action.REVOKE);
            await(() -> { try { return !alice.contacts.state(b).seesTheirPresence; } catch(Exception failed) { return false; } });
            assertFalse(alice.contacts.presence(b).visible);
            assertTrue(bob.contacts.state(a).seesTheirPresence,"Revoking their access must preserve your independent subscription to them");
            assertEquals(0,alice.contacts.lastSeen(b).lastSeen);
            alice.contacts.act(b,XmppContacts.Action.REQUEST); assertEquals(a,bobRequests.poll(10,TimeUnit.SECONDS));
            alice.contacts.act(b,XmppContacts.Action.CANCEL); assertEquals(a,bobCancelled.poll(10,TimeUnit.SECONDS));
            await(() -> !roster.getEntry(JidCreate.entityBareFromOrThrowUnchecked(b)).isSubscriptionPending());
            alice.contacts.act(b,XmppContacts.Action.REQUEST); assertEquals(a,bobRequests.poll(10,TimeUnit.SECONDS));
            bob.contacts.act(a,XmppContacts.Action.DENY);
            await(() -> !roster.getEntry(JidCreate.entityBareFromOrThrowUnchecked(b)).isSubscriptionPending());
            assertFalse(alice.contacts.state(b).seesTheirPresence);
            assertThrows(IllegalArgumentException.class,() -> alice.contacts.act(a,XmppContacts.Action.REQUEST));
            assertThrows(IllegalArgumentException.class,() -> alice.contacts.act(b+"/phone",XmppContacts.Action.REQUEST));
        }
    }
}
