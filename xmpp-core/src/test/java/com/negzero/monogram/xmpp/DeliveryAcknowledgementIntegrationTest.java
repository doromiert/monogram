package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class DeliveryAcknowledgementIntegrationTest {
    static final String ALICE="receiptalice@localhost",BOB="receiptbob@localhost";
    static final class Inbox implements XmppSession.Listener {
        final BlockingQueue<XmppMessage> messages=new LinkedBlockingQueue<>();
        final BlockingQueue<Runnable> accepted=new LinkedBlockingQueue<>();
        public void message(EntityBareJid peer,String id,String body,boolean encrypted) {}
        public void messagePending(XmppMessage message) {}
        public void message(XmppMessage message) { messages.add(message); }
        public void message(XmppMessage message,Runnable stored) { messages.add(message);accepted.add(stored); }
        XmppMessage next() throws Exception { XmppMessage message=messages.poll(15,TimeUnit.SECONDS);assertNotNull(message);return message; }
        Runnable acceptance() throws Exception { Runnable value=accepted.poll(5,TimeUnit.SECONDS);assertNotNull(value);return value; }
    }
    XmppSession session(String user,int id,boolean modern,Inbox inbox) throws Exception {
        File root=new File(System.getenv("MONOGRAM_IT_STATE"));XmppSession.initializeOmemo(new File(root,"keys"));
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Receipt-"+id),
                "monogram-test",id,new File(root,"receipt-trust-"+id),
                modern ? new Omemo2Device(user+"@localhost",id+1000,new Omemo2TestDatabase()) : null,inbox);
    }
    void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }
    void authorize(XmppSession alice,XmppSession bob) throws Exception {
        Roster a=Roster.getInstanceFor(alice.connection()),b=Roster.getInstanceFor(bob.connection());
        a.setSubscriptionMode(Roster.SubscriptionMode.accept_all);b.setSubscriptionMode(Roster.SubscriptionMode.accept_all);
        a.createItemAndRequestSubscription(JidCreate.entityBareFrom(BOB),"Bob",null);
        b.createItemAndRequestSubscription(JidCreate.entityBareFrom(ALICE),"Alice",null);
        await(() -> b.isSubscribedToMyPresence(JidCreate.entityBareFromOrThrowUnchecked(ALICE))
                && a.isSubscribedToMyPresence(JidCreate.entityBareFromOrThrowUnchecked(BOB)));
    }
    java.util.Set<org.jivesoftware.smackx.omemo.internal.OmemoDevice> discoverLegacy(XmppSession session,String peer) throws Exception {
        for(int attempt=0;;attempt++) {
            try { return session.devices(peer); }
            catch(OmemoSendException changed) {
                if(changed.reason!=OmemoSendException.Reason.ROOM_CHANGED
                        || !(changed.getCause() instanceof java.util.ConcurrentModificationException) || attempt>=4) throw changed;
                // Initial presence can deliver a newer PEP list while this explicit lookup is in flight.
                Thread.sleep(50);
            }
        }
    }
    BlockingQueue<Message> receipts(XmppSession session) {
        BlockingQueue<Message> result=new LinkedBlockingQueue<>();
        session.connection().addSyncStanzaListener(stanza -> result.add((Message)stanza),stanza -> stanza instanceof Message
                && stanza.hasExtension("received",DeliveryReceipt.NAMESPACE));return result;
    }
    Message raw(XmppSession to,String id) {
        Message message=new Message(to.connection().getUser(),Message.Type.chat);message.setStanzaId(id);message.setBody(id);
        message.addExtension(new DeliveryReceiptRequest());return message;
    }
    @Test @EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
    void plainLegacyAndModernWaitForStorageAndDuplicateCallbacksCannotReplyTwice() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try(XmppSession alice=session("receiptalice",9501,true,a);XmppSession bob=session("receiptbob",9502,true,b)) {
            alice.connect();bob.connect();authorize(alice,bob);BlockingQueue<Message> replies=receipts(alice);
            for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:discoverLegacy(alice,BOB)) alice.decide(device,alice.fingerprint(device),true);
            for(XmppSession owner:new XmppSession[]{alice,bob})
                for(String peer:new String[]{ALICE,BOB})
                    for(java.util.Map.Entry<Integer,Omemo2Bundle> entry:owner.modern().discover(peer).entrySet())
                        owner.modern().device.decide(peer,entry.getKey(),entry.getValue().identity(),true);
            for(XmppMessage.Encryption mode:XmppMessage.Encryption.values()) {
                String id=alice.send(BOB,"Stored before receipt "+mode,mode,null);
                XmppMessage delivered=b.next();assertTrue(delivered.readable,() -> "Unreadable "+mode+": "+bob.modernFailure());
                assertEquals(id,delivered.ids.get("stanza"));
                Runnable stored=b.acceptance();assertNull(replies.poll(250,TimeUnit.MILLISECONDS),"No "+mode+" receipt before storage");
                stored.run();stored.run();Message reply=replies.poll(10,TimeUnit.SECONDS);assertNotNull(reply,"Missing "+mode+" receipt");
                assertEquals(id,DeliveryReceipt.from(reply).getId());assertEquals(alice.connection().getUser().asEntityBareJid(),reply.getTo().asEntityBareJidIfPossible());
                assertFalse(reply.hasExtension("request",DeliveryReceipt.NAMESPACE));assertNull(reply.getBody());
                assertNull(replies.poll(200,TimeUnit.MILLISECONDS),"Duplicate callback cannot send a second receipt");
            }
            XmppSession.ArchivePage page=bob.archiveBefore(ALICE,null,100);assertFalse(page.messages.isEmpty());page.stored();bob.recoverIncoming();
            assertNull(replies.poll(300,TimeUnit.MILLISECONDS),"History/recovery cannot send new delivery receipts");
        }
    }
    @Test void malformedUnsupportedCarbonHistoryAndRevokedAuthorizationCannotReply() throws Exception {
        Inbox a=new Inbox(),b=new Inbox(),other=new Inbox();
        try(XmppSession alice=session("receiptalice",9501,false,a);XmppSession bob=session("receiptbob",9502,false,b)) {
            alice.connect();bob.connect();authorize(alice,bob);BlockingQueue<Message> replies=receipts(alice);
            Message malformed=raw(bob,"malformed-request");malformed.removeExtension("request",DeliveryReceipt.NAMESPACE);
            malformed.addExtension(StandardExtensionElement.builder("request",DeliveryReceipt.NAMESPACE).addAttribute("unexpected","value").build());
            alice.connection().sendStanza(malformed);assertEquals("malformed-request",b.next().ids.get("stanza"));
            assertTrue(b.accepted.isEmpty());assertNull(replies.poll(200,TimeUnit.MILLISECONDS));
            Message reaction=raw(bob,"reaction-request");reaction.setBody(null);
            new MessageReactions("previous",java.util.Collections.singleton("👍")).addTo(reaction);
            alice.connection().sendStanza(reaction);assertNotNull(b.next().reactions);
            assertTrue(b.accepted.isEmpty());assertNull(replies.poll(200,TimeUnit.MILLISECONDS),"Reactions cannot request delivery receipts");
            Message unsupported=raw(bob,"unsupported-cipher");unsupported.addExtension(StandardExtensionElement.builder("encrypted",XmppOmemo2.NS).addElement("payload","invalid").build());
            alice.connection().sendStanza(unsupported);assertFalse(b.next().readable);b.acceptance().run();
            assertNull(replies.poll(200,TimeUnit.MILLISECONDS),"Unreadable ciphertext is not delivery success");
            // Explicit full-resource routing ensures Bob receives only the carbon while the other device gets the live stanza.
            try(XmppSession phone=session("receiptbob",9503,false,other)) {
                phone.connect();alice.connection().sendStanza(raw(phone,"carbon-receipt-request"));
                assertEquals("carbon-receipt-request",other.next().ids.get("stanza"));
                assertEquals("carbon-receipt-request",b.next().ids.get("stanza"));
                assertTrue(b.accepted.isEmpty(),"Received carbons must not acquire receipt acceptance actions");
                assertNull(replies.poll(200,TimeUnit.MILLISECONDS));other.acceptance().run();
                assertNotNull(replies.poll(5,TimeUnit.SECONDS));assertNull(replies.poll(200,TimeUnit.MILLISECONDS));
            }
            alice.connection().sendStanza(raw(bob,"authorization-revoked-after-arrival"));
            assertEquals("authorization-revoked-after-arrival",b.next().ids.get("stanza"));Runnable stored=b.acceptance();
            bob.contacts.act(ALICE,XmppContacts.Action.REVOKE);
            await(() -> !Roster.getInstanceFor(bob.connection()).isSubscribedToMyPresence(JidCreate.entityBareFromOrThrowUnchecked(ALICE)));
            stored.run();assertNull(replies.poll(300,TimeUnit.MILLISECONDS),"Authorization must be checked after storage");
            XmppSession.ArchivePage page=bob.archiveBefore(ALICE,null,100);page.stored();
            assertNull(replies.poll(300,TimeUnit.MILLISECONDS),"MAM cannot reply to receipt requests");
        }
    }
}
