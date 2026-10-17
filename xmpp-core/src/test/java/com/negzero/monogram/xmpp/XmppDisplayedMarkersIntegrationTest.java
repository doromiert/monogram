package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.roster.Roster;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppDisplayedMarkersIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device,BlockingQueue<XmppMessage> messages,BlockingQueue<MessageReceipt> receipts) throws Exception {
        return new XmppSession(new Account("typing"+user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Displayed-"+device),
                "monogram-test",device,new File(root,"displayed-trust-"+device),new XmppSession.Listener() {
            public void message(EntityBareJid from,String id,String body,boolean encrypted) {}
            public void message(XmppMessage message) { messages.add(message); }
            public void receipt(MessageReceipt receipt) { receipts.add(receipt); }
        });
    }
    @Test void explicitOptInPresenceAuthorizationDedupAndNoFeedback() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),com.negzero.monogram.xmpp.LegacyOmemoTestDatabase.shared());
        BlockingQueue<XmppMessage> aliceMessages=new LinkedBlockingQueue<>(),bobMessages=new LinkedBlockingQueue<>();
        BlockingQueue<MessageReceipt> aliceReceipts=new LinkedBlockingQueue<>(),bobReceipts=new LinkedBlockingQueue<>();
        BlockingQueue<Message> markers=new LinkedBlockingQueue<>();
        try(XmppSession alice=session("alice",5401,aliceMessages,aliceReceipts); XmppSession bob=session("bob",5402,bobMessages,bobReceipts)) {
            alice.connect();bob.connect();
            EntityBareJid aliceJid=JidCreate.entityBareFrom("typingalice@localhost"),bobJid=JidCreate.entityBareFrom("typingbob@localhost");
            Roster aliceRoster=Roster.getInstanceFor(alice.connection()),bobRoster=Roster.getInstanceFor(bob.connection());
            if(aliceRoster.getEntry(bobJid)!=null) aliceRoster.removeEntry(aliceRoster.getEntry(bobJid));
            aliceRoster.reloadAndWait();
            bob.connection().addSyncStanzaListener(stanza -> markers.add((Message)stanza),stanza -> stanza instanceof Message && stanza.hasExtension("displayed",MessageReceipt.MARKERS));
            String id=bob.send(aliceJid.toString(),"Only explicit displayed markers",false);
            XmppMessage message=aliceMessages.poll(5,TimeUnit.SECONDS);assertNotNull(message);assertEquals(id,message.markableId);
            assertFalse(alice.sendDisplayed(bobJid.toString(),id),"Default must not disclose reads");
            alice.setDisplayedMarkersEnabled(true);
            assertFalse(alice.sendDisplayed(bobJid.toString(),id),"Unknown/unsubscribed recipients cannot infer presence");
            aliceRoster.setSubscriptionMode(Roster.SubscriptionMode.accept_all);bobRoster.setSubscriptionMode(Roster.SubscriptionMode.accept_all);
            aliceRoster.createItemAndRequestSubscription(bobJid,"Bob",null);bobRoster.createItemAndRequestSubscription(aliceJid,"Alice",null);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while((aliceRoster.getEntry(bobJid)==null || !aliceRoster.getEntry(bobJid).canSeeMyPresence()) && System.nanoTime()<deadline) Thread.sleep(20);
            assertTrue(alice.sendDisplayed(bobJid.toString(),id));
            Message wire=markers.poll(5,TimeUnit.SECONDS);assertNotNull(wire);assertNull(wire.getBody());
            assertEquals(1,wire.getExtensions("displayed",MessageReceipt.MARKERS).size());
            assertFalse(wire.hasExtension("markable",MessageReceipt.MARKERS));
            assertFalse(wire.hasExtension("request","urn:xmpp:receipts"));
            assertFalse(wire.hasExtension("received","urn:xmpp:receipts"));
            assertFalse(wire.hasExtension(XmppChatStates.NAMESPACE));
            assertEquals(bobJid,wire.getTo());assertEquals(Message.Type.chat,wire.getType());
            MessageReceipt receipt=null;
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline) {
                MessageReceipt incoming=bobReceipts.poll(100,TimeUnit.MILLISECONDS);
                if(incoming!=null && incoming.displayed) { receipt=incoming;break; }
            }
            assertNotNull(receipt);assertEquals(id,receipt.id);
            assertFalse(alice.sendDisplayed(bobJid.toString(),id),"Successful markers must be coalesced");
            alice.setDisplayedMarkersEnabled(false);assertFalse(alice.sendDisplayed(bobJid.toString(),"next-message"));
            assertThrows(IllegalArgumentException.class,() -> alice.sendDisplayed(bob.connection().getUser().toString(),id));
            assertThrows(IllegalArgumentException.class,() -> alice.sendDisplayed(aliceJid.toString(),id));
            assertThrows(IllegalArgumentException.class,() -> alice.sendDisplayed(bobJid.toString(),""));
            alice.rooms.remember("displayed@conference.localhost");
            assertThrows(IllegalArgumentException.class,() -> alice.sendDisplayed("displayed@conference.localhost",id));
            assertTrue(bobMessages.isEmpty(),"A displayed marker cannot become a chat bubble");
            assertNull(markers.poll(200,TimeUnit.MILLISECONDS),"No duplicate markers");
            assertTrue(aliceReceipts.stream().noneMatch(value -> value.displayed),"Displayed markers cannot generate feedback");
        }
    }
}
