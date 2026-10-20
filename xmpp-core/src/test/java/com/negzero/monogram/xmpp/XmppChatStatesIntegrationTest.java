package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONOGRAM_IT", matches = "1")
class XmppChatStatesIntegrationTest {
    final File root = new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user, int device, XmppSession.Listener listener) throws Exception {
        return new XmppSession(new Account("typing" + user + "@localhost", "127.0.0.1", Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")), "Typing-" + device),
                "monogram-test", device, new File(root, "typing-trust-" + device), listener);
    }
    void state(XmppSession from, XmppSession to, ChatState state, String extra) throws Exception {
        Message message = new Message(to.connection().getUser(), Message.Type.chat);
        message.addExtension(new ChatStateExtension(state));
        if (extra != null) message.addExtension(StandardExtensionElement.builder("delay", "urn:xmpp:delay")
                .addAttribute("stamp", "2000-01-01T00:00:00Z").build());
        from.connection().sendStanza(message);
    }
    void assertState(BlockingQueue<Message> queue, ChatState state) throws Exception {
        Message message = queue.poll(5, TimeUnit.SECONDS); assertNotNull(message, "Expected " + state);
        assertEquals(state, XmppChatStates.read(message)); assertNull(message.getBody());
        assertEquals(1, message.getExtensions().size(), "Standalone states cannot become messages, receipts or encrypted bodies");
    }
    @Test void standaloneTransitionsPrivacyAndResourceAggregation() throws Exception {
        XmppSession.initializeOmemo(new File(root, "keys"),com.negzero.monogram.xmpp.LegacyOmemoTestDatabase.shared());
        BlockingQueue<Boolean> incoming = new LinkedBlockingQueue<>();
        BlockingQueue<XmppMessage> bodies = new LinkedBlockingQueue<>();
        BlockingQueue<Message> sent = new LinkedBlockingQueue<>();
        XmppSession.Listener ignore = (from, id, body, encrypted) -> {};
        try (XmppSession alice = session("alice", 501, new XmppSession.Listener() {
            @Override public void message(org.jxmpp.jid.EntityBareJid from, String id, String body, boolean encrypted) {}
            @Override public void message(XmppMessage message) { bodies.add(message); }
            @Override public void typing(org.jxmpp.jid.EntityBareJid from, boolean composing) { incoming.add(composing); }
        }); XmppSession bob = session("bob", 502, ignore); XmppSession desktop = session("bob", 503, ignore)) {
            alice.connect(); bob.connect(); desktop.connect();
            assertTrue(ServiceDiscoveryManager.getInstanceFor(bob.connection()).supportsFeature(alice.connection().getUser(), XmppChatStates.NAMESPACE));
            bob.connection().addSyncStanzaListener(stanza -> sent.add((Message) stanza), stanza -> stanza instanceof Message
                    && stanza.getFrom() != null && stanza.getFrom().asBareJid().equals(alice.connection().getUser().asBareJid())
                    && ((Message) stanza).hasExtension(XmppChatStates.NAMESPACE) && ((Message) stanza).getBody() == null);
            state(bob, alice, ChatState.composing, null); assertEquals(true, incoming.poll(5, TimeUnit.SECONDS));
            java.util.concurrent.CountDownLatch secondResource = new java.util.concurrent.CountDownLatch(1);
            alice.connection().addSyncStanzaListener(stanza -> secondResource.countDown(), stanza -> stanza instanceof Message
                    && desktop.connection().getUser().equals(stanza.getFrom()) && XmppChatStates.read((Message) stanza) == ChatState.composing);
            state(desktop, alice, ChatState.composing, null);
            assertTrue(secondResource.await(5, TimeUnit.SECONDS));
            state(bob, alice, ChatState.paused, null);
            assertNull(incoming.poll(300, TimeUnit.MILLISECONDS), "One device pausing must not clear another device's typing");
            state(desktop, alice, ChatState.active, null); assertEquals(false, incoming.poll(5, TimeUnit.SECONDS));
            assertTrue(bodies.isEmpty(), "Chat states must never create a conversation message");
            state(bob, alice, ChatState.active, null);
            // A content callback acts as a wire barrier before exercising the learned resource.
            bob.send("typingalice@localhost", "typing negotiation", false);
            assertEquals("typing negotiation", bodies.poll(5, TimeUnit.SECONDS).body);
            alice.chatStates.input("typingbob@localhost", true); assertState(sent, ChatState.composing);
            alice.chatStates.input("typingbob@localhost", true); alice.chatStates.input("typingbob@localhost", true);
            assertNull(sent.poll(300, TimeUnit.MILLISECONDS), "Repeated keystrokes cannot repeat composing on the wire");
            alice.chatStates.input("typingbob@localhost", false); assertState(sent, ChatState.active);
            alice.chatStates.input("typingbob@localhost", true); assertState(sent, ChatState.composing);
            alice.chatStates.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + 6000); assertState(sent, ChatState.paused);
            alice.chatStates.inactive("typingbob@localhost"); assertState(sent, ChatState.inactive);
            alice.chatStates.gone("typingbob@localhost"); assertState(sent, ChatState.gone);
            bob.chatStates.setEnabled(false);
            bob.send("typingalice@localhost", "no states supported", false);
            assertEquals("no states supported", bodies.poll(5, TimeUnit.SECONDS).body);
            alice.chatStates.input("typingbob@localhost", true);
            assertNull(sent.poll(500, TimeUnit.MILLISECONDS), "An implicit peer replying without states must suppress subsequent notifications");
            state(bob, alice, ChatState.active, null); assertState(sent, ChatState.composing);
            alice.chatStates.setEnabled(false); alice.chatStates.input("typingbob@localhost", true);
            BlockingQueue<Message> content = new LinkedBlockingQueue<>();
            bob.connection().addSyncStanzaListener(stanza -> content.add((Message) stanza), stanza -> stanza instanceof Message && "privacy content".equals(((Message) stanza).getBody()));
            desktop.connection().addSyncStanzaListener(stanza -> content.add((Message) stanza), stanza -> stanza instanceof Message && "privacy content".equals(((Message) stanza).getBody()));
            alice.send("typingbob@localhost", "privacy content", false);
            assertFalse(content.poll(5, TimeUnit.SECONDS).hasExtension(XmppChatStates.NAMESPACE));
            assertNull(sent.poll(300, TimeUnit.MILLISECONDS));
            for (int invalid = 0; invalid < 6; invalid++) {
                Message replay = new Message(alice.connection().getUser(), Message.Type.chat);
                replay.setFrom(bob.connection().getUser()); replay.addExtension(new ChatStateExtension(ChatState.composing));
                if (invalid == 0) replay.addExtension(StandardExtensionElement.builder("forwarded", "urn:xmpp:forward:0").build());
                if (invalid == 1) replay.addExtension(StandardExtensionElement.builder("result", "urn:xmpp:mam:2").build());
                if (invalid == 2) replay.addExtension(StandardExtensionElement.builder("received", "urn:xmpp:carbons:2").build());
                if (invalid == 3) replay.setFrom(alice.connection().getUser());
                if (invalid == 4) replay.setTo(org.jxmpp.jid.impl.JidCreate.entityBareFrom("someone-else@localhost"));
                if (invalid == 5) replay.setType(Message.Type.groupchat);
                alice.chatStates.receive(replay);
            }
            assertTrue(incoming.isEmpty(), "Forwarded, self, foreign-recipient and room state must not become direct-chat typing");
            state(bob, alice, ChatState.composing, "delayed");
            assertNull(incoming.poll(300, TimeUnit.MILLISECONDS), "History cannot create live typing");
            state(bob, alice, ChatState.composing, null); assertEquals(true, incoming.poll(5, TimeUnit.SECONDS));
            alice.chatStates.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + XmppChatStates.TYPING_TIMEOUT_MILLIS + 10);
            assertEquals(false, incoming.poll(5, TimeUnit.SECONDS), "Stale resources must expire even if the other client disappears");
            state(bob, alice, ChatState.composing, null); assertEquals(true, incoming.poll(5, TimeUnit.SECONDS));
            alice.close(); assertEquals(false, incoming.poll(5, TimeUnit.SECONDS), "Disconnect must clear typing");
        }
    }
}
