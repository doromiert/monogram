package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jxmpp.jid.impl.JidCreate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONOGRAM_IT", matches = "1")
class XmppIntegrationTest {
    final File state = new File(System.getenv("MONOGRAM_IT_STATE"));
    final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    final BlockingQueue<Boolean> encryption = new LinkedBlockingQueue<>();
    final int port = Integer.parseInt(System.getenv("MONOGRAM_IT_PORT"));
    XmppSession session(String username, int device, XmppSession.Listener listener) throws Exception {
        return new XmppSession(new Account(username + "@localhost", "127.0.0.1", port, "Monogram-" + device),
                "monogram-test", device, new File(state, username + "-trust"), listener);
    }
    void verifyDevices(XmppSession sender, String jid) throws Exception {
        java.util.Set<OmemoDevice> devices = sender.devices(jid);
        assertFalse(devices.isEmpty(), "Peer must publish an OMEMO device");
        for (OmemoDevice device : devices) {
            OmemoFingerprint fingerprint = sender.fingerprint(device);
            assertNotNull(fingerprint);
            sender.decide(device, fingerprint, true);
        }
    }
    IQ helper(XmppSession session, String child, IQ.Type type, String attributes, Message message) throws Exception {
        IQ iq = new IQ(child, "urn:monogram:schedule:0") {
            @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                if (attributes != null) xml.append(attributes);
                xml.rightAngleBracket();
                if (message != null) xml.append(message.toXML());
                return xml;
            }
        };
        iq.setType(type);
        return session.connection().createStanzaCollectorAndSend(iq).nextResultOrThrow();
    }
    @Test void tlsPlaintextOmemoPersistenceAndOptionalScheduler() throws Exception {
        XmppSession.initializeOmemo(new File(state, "keys"));
        XmppSession.Listener ignore = (sender, id, body, encrypted) -> {};
        OmemoFingerprint aliceFingerprint;
        try (XmppSession alice = session("alice", 101, ignore);
             XmppSession bob = session("bob", 202, (sender, id, body, encrypted) -> {
                 received.add(body); encryption.add(encrypted);
             })) {
            Roster.getInstanceFor(alice.connection()).setSubscriptionMode(Roster.SubscriptionMode.accept_all);
            Roster.getInstanceFor(bob.connection()).setSubscriptionMode(Roster.SubscriptionMode.accept_all);
            alice.connect(); bob.connect();
            assertTrue(alice.connection().isSecureConnection());
            assertTrue(bob.connection().isSecureConnection());
            aliceFingerprint = alice.ownFingerprint();
            Roster.getInstanceFor(alice.connection()).createItemAndRequestSubscription(JidCreate.bareFrom("bob@localhost"), "Bob", null);
            Roster.getInstanceFor(bob.connection()).createItemAndRequestSubscription(JidCreate.bareFrom("alice@localhost"), "Alice", null);
            assertTrue(alice.hasArchive());
            alice.send("bob@localhost", "ordinary Jabber", false);
            assertEquals("ordinary Jabber", received.poll(15, TimeUnit.SECONDS));
            assertEquals(false, encryption.poll(1, TimeUnit.SECONDS));

            assertFalse(alice.devices("bob@localhost").isEmpty());
            assertThrows(UndecidedOmemoIdentityException.class, () -> alice.send("bob@localhost", "must not leak", true));
            assertNull(received.poll(300, TimeUnit.MILLISECONDS));
            verifyDevices(alice, "bob@localhost");
            verifyDevices(bob, "alice@localhost");
            AtomicReference<String> outgoing = new AtomicReference<>();
            alice.connection().addStanzaInterceptor(stanza -> outgoing.set(stanza.toXML().toString()), stanza -> stanza instanceof Message);
            alice.send("bob@localhost", "encrypted hello", true);
            assertEquals("encrypted hello", received.poll(15, TimeUnit.SECONDS));
            assertEquals(true, encryption.poll(1, TimeUnit.SECONDS));
            assertTrue(outgoing.get().contains("eu.siacs.conversations.axolotl"));
            assertFalse(outgoing.get().contains("encrypted hello"));

            assertTrue(ServiceDiscoveryManager.getInstanceFor(alice.connection()).serverSupportsFeature("urn:monogram:schedule:0"));
            Message scheduled = new Message(JidCreate.entityBareFrom("bob@localhost"), Message.Type.chat);
            scheduled.setStanzaId("scheduled-test"); scheduled.setBody("scheduled hello");
            // The module must replace any forged sender with the authenticated account.
            scheduled.setFrom(JidCreate.entityBareFrom("mallory@localhost"));
            IQ queued = helper(alice, "schedule", IQ.Type.set, " at='" + (System.currentTimeMillis() / 1000 + 2) + "'", scheduled);
            assertTrue(queued.toXML().toString().contains("scheduled"));
            assertEquals("scheduled hello", received.poll(15, TimeUnit.SECONDS));
            assertEquals(false, encryption.poll(1, TimeUnit.SECONDS));
            IQ listed = helper(alice, "scheduled", IQ.Type.get, null, null);
            assertFalse(listed.toXML().toString().contains("<item"));

            IQ cancelled = helper(alice, "schedule", IQ.Type.set, " at='" + (System.currentTimeMillis() / 1000 + 60) + "'", scheduled);
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("id=['\"]([^'\"]+)['\"]").matcher(((UnparsedIQ)cancelled).getContent());
            assertTrue(id.find());
            String jobId = id.group(1);
            assertThrows(Exception.class, () -> helper(bob, "cancel", IQ.Type.set, " id='" + jobId + "'", null));
            helper(alice, "cancel", IQ.Type.set, " id='" + jobId + "'", null);
            assertFalse(helper(alice, "scheduled", IQ.Type.get, null, null).toXML().toString().contains("<item"));
        }
        try (XmppSession restarted = session("alice", 101, ignore)) {
            restarted.connect();
            assertEquals(aliceFingerprint, restarted.ownFingerprint(), "Restart must preserve device identity");
            restarted.send("bob@localhost", "offline encrypted delivery", true);
        }
        try (XmppSession bob = session("bob", 202, (sender, id, body, encrypted) -> { received.add(body); encryption.add(encrypted); })) {
            bob.connect();
            assertEquals("offline encrypted delivery", received.poll(15, TimeUnit.SECONDS));
            assertEquals(true, encryption.poll(1, TimeUnit.SECONDS));
        }
    }
}
