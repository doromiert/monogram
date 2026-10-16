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
        BlockingQueue<XmppMessage> copies = new LinkedBlockingQueue<>();
        XmppSession.Listener observer = new XmppSession.Listener() {
            @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {}
            @Override public void message(XmppMessage message) { copies.add(message); }
        };
        try (XmppSession alice = session("alice", 101, ignore);
             XmppSession bob = session("bob", 202, (sender, id, body, encrypted) -> { received.add(body); encryption.add(encrypted); });
             XmppSession second = session("alice", 303, observer)) {
            alice.connect(); bob.connect(); second.connect();
            String sentId = alice.send("bob@localhost", "from another device", false);
            XmppMessage sent = copies.poll(15, TimeUnit.SECONDS);
            assertNotNull(sent); assertTrue(sent.outgoing); assertFalse(sent.encrypted);
            assertEquals("from another device", sent.body);
            assertEquals(sentId, sent.ids.get("stanza"));
            assertEquals("bob@localhost", sent.peer.toString());
            Message targeted = new Message(alice.connection().getUser(), Message.Type.chat);
            targeted.setStanzaId("carbon-incoming"); targeted.setBody("to another device");
            bob.connection().sendStanza(targeted);
            XmppMessage incoming = copies.poll(15, TimeUnit.SECONDS);
            assertNotNull(incoming); assertFalse(incoming.outgoing);
            assertEquals("to another device", incoming.body);
            assertEquals("bob@localhost", incoming.peer.toString());

            Message forged = new Message(second.connection().getUser(), Message.Type.chat);
            Message inner = new Message(JidCreate.entityBareFrom("alice@localhost"), Message.Type.chat);
            inner.setFrom(JidCreate.entityBareFrom("mallory@localhost")); inner.setBody("forged carbon");
            forged.addExtension(new org.jivesoftware.smackx.carbons.packet.CarbonExtension(
                    org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.received,
                    new org.jivesoftware.smackx.forward.packet.Forwarded<Message>(inner)));
            bob.connection().sendStanza(forged);
            assertNull(copies.poll(500, TimeUnit.MILLISECONDS), "A contact must not be able to forge a carbon");
            alice.connection().sendStanza(forged);
            assertNull(copies.poll(500, TimeUnit.MILLISECONDS), "Another client resource is not an authoritative carbon sender");

            java.util.Map<String, XmppMessage> archive = new java.util.LinkedHashMap<>();
            String before = null;
            long previous = Long.MAX_VALUE;
            for (int pageNumber = 0; pageNumber < 30; pageNumber++) {
                XmppSession.ArchivePage page = second.archiveBefore("bob@localhost", before, 2);
                assertTrue(page.messages.size() <= 2);
                for (int i = page.messages.size() - 1; i >= 0; i--) {
                    XmppMessage message = page.messages.get(i);
                    assertTrue(message.archived);
                    assertEquals("bob@localhost", message.peer.toString());
                    assertTrue(message.timestamp <= previous, "Archive timestamps must retain their order");
                    previous = message.timestamp;
                    assertNull(archive.put(message.ids.get("archive"), message), "Paging must not repeat an archive UID");
                }
                if (page.complete || page.firstId == null) break;
                assertNotEquals(before, page.firstId); before = page.firstId;
            }
            assertTrue(archive.values().stream().anyMatch(m -> "ordinary Jabber".equals(m.body) && m.outgoing));
            assertTrue(archive.values().stream().anyMatch(m -> sentId.equals(m.ids.get("stanza")) && m.outgoing));
            assertTrue(archive.values().stream().anyMatch(m -> "to another device".equals(m.body) && !m.outgoing));
            assertTrue(archive.values().stream().anyMatch(m -> m.encrypted && !m.readable && m.body == null),
                    "A new device must identify encrypted history it cannot decrypt");
            String oldest = new java.util.ArrayList<>(archive.keySet()).get(archive.size() - 1);
            java.util.Set<String> forwardIds = new java.util.HashSet<>();
            String after = oldest;
            for (int pageNumber = 0; pageNumber < 30; pageNumber++) {
                XmppSession.ArchivePage page = second.archiveAfter(after, 2);
                for (XmppMessage message : page.messages) assertTrue(forwardIds.add(message.ids.get("archive")));
                if (page.complete || page.lastId == null) break;
                assertNotEquals(after, page.lastId); after = page.lastId;
            }
            java.util.Set<String> expected = new java.util.HashSet<>(archive.keySet()); expected.remove(oldest);
            assertEquals(expected, forwardIds, "Forward catch-up must cover the same archive without repeating its anchor");
            String bobBefore = null;
            for (int pageNumber = 0; pageNumber < 30; pageNumber++) {
                XmppSession.ArchivePage page = bob.archiveBefore(null, bobBefore, 2);
                if (page.complete || page.firstId == null) break;
                assertNotEquals(bobBefore, page.firstId); bobBefore = page.firstId;
            }
            verifyDevices(alice, "alice@localhost");
            received.clear(); encryption.clear();
            alice.send("bob@localhost", "live encryption after archive replay", true);
            assertEquals("live encryption after archive replay", received.poll(15, TimeUnit.SECONDS));
            assertEquals(true, encryption.poll(1, TimeUnit.SECONDS));
        }
    }
}
