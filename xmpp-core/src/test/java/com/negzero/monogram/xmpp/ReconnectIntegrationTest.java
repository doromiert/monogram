package com.negzero.monogram.xmpp;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONOGRAM_IT", matches = "1")
class ReconnectIntegrationTest {
    /** Drop only the synthetic client's TCP connection; the fixture and other clients stay online. */
    private static final class CuttableProxy implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
        volatile boolean muted;
        final ExecutorService threads = Executors.newCachedThreadPool(task -> { Thread t = new Thread(task, "fixture-proxy"); t.setDaemon(true); return t; });
        CuttableProxy(int target) throws IOException {
            threads.execute(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept(), upstream = new Socket("127.0.0.1", target);
                        sockets.add(client); sockets.add(upstream);
                        threads.execute(() -> copy(client, upstream)); threads.execute(() -> copy(upstream, client));
                    } catch (IOException closed) { if (!server.isClosed()) throw new RuntimeException(closed); }
                }
            });
        }
        private void copy(Socket from, Socket to) {
            try {
                byte[] bytes = new byte[8192]; InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream();
                for (int n; (n = in.read(bytes)) != -1;) { if (!muted) { out.write(bytes, 0, n); out.flush(); } }
            } catch (IOException dropped) { /* The test deliberately cuts active sockets. */ }
            finally { closeSocket(from); closeSocket(to); sockets.remove(from); sockets.remove(to); }
        }
        private void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) {} }
        void drop() { for (Socket socket : new ArrayList<>(sockets)) closeSocket(socket); }
        @Override public void close() throws IOException { server.close(); drop(); threads.shutdownNow(); }
    }
    private static void trustDevices(XmppSession session, String jid) throws Exception {
        for (OmemoDevice device : session.devices(jid)) session.decide(device, session.fingerprint(device), true);
    }
    @Test void tcpLossReportsStateAndRestoresEncryptedMessagingWithSameIdentity() throws Exception {
        File state = new File(System.getenv("MONOGRAM_IT_STATE"));
        int port = Integer.parseInt(System.getenv("MONOGRAM_IT_PORT"));
        XmppSession.initializeOmemo(new File(state, "keys"));
        BlockingQueue<String> events = new LinkedBlockingQueue<>(), received = new LinkedBlockingQueue<>();
        try (CuttableProxy proxy = new CuttableProxy(port);
             XmppSession alice = new XmppSession(new Account("alice@localhost", "127.0.0.1", proxy.server.getLocalPort(), "Reconnect-Alice"),
                     "monogram-test", 101, new File(state, "reconnect-alice-trust"), new XmppSession.Listener() {
                         @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {}
                         @Override public void disconnected() { events.add("down"); }
                         @Override public void reconnected(boolean resumed) { events.add("up:" + resumed); }
                     });
             XmppSession bob = new XmppSession(new Account("bob@localhost", "127.0.0.1", port, "Reconnect-Bob"),
                     "monogram-test", 202, new File(state, "reconnect-bob-trust"),
                     (sender, id, body, encrypted) -> { if (encrypted) received.add(body); })) {
            alice.connection().setUseStreamManagementResumption(false);
            ReconnectionManager.getInstanceFor(alice.connection()).disableAutomaticReconnection();
            org.jivesoftware.smack.roster.Roster.getInstanceFor(alice.connection()).setSubscriptionMode(org.jivesoftware.smack.roster.Roster.SubscriptionMode.accept_all);
            org.jivesoftware.smack.roster.Roster.getInstanceFor(bob.connection()).setSubscriptionMode(org.jivesoftware.smack.roster.Roster.SubscriptionMode.accept_all);
            alice.connect(); bob.connect();
            org.jivesoftware.smack.roster.Roster.getInstanceFor(alice.connection()).createItemAndRequestSubscription(org.jxmpp.jid.impl.JidCreate.bareFrom("bob@localhost"), "Bob", null);
            org.jivesoftware.smack.roster.Roster.getInstanceFor(bob.connection()).createItemAndRequestSubscription(org.jxmpp.jid.impl.JidCreate.bareFrom("alice@localhost"), "Alice", null);
            for (XmppSession client : new XmppSession[]{alice, bob}) {
                trustDevices(client, "alice@localhost"); trustDevices(client, "bob@localhost");
            }
            OmemoFingerprint identity = alice.ownFingerprint();
            alice.send("bob@localhost", "before interruption", true);
            assertEquals("before interruption", received.poll(15, TimeUnit.SECONDS));
            proxy.drop();
            assertEquals("down", events.poll(10, TimeUnit.SECONDS));
            alice.connection().connect().login();
            assertEquals("up:false", events.poll(20, TimeUnit.SECONDS));
            assertTrue(alice.connection().isAuthenticated()); assertTrue(alice.connection().isSecureConnection());
            assertTrue(CarbonManager.getInstanceFor(alice.connection()).getCarbonsEnabled());
            assertTrue(alice.hasArchive());
            assertEquals(identity, alice.ownFingerprint()); assertEquals(101, alice.ownDeviceId());
            alice.send("bob@localhost", "after interruption", true);
            assertEquals("after interruption", received.poll(15, TimeUnit.SECONDS));
            assertEquals(120, org.jivesoftware.smackx.ping.PingManager.getInstanceFor(alice.connection()).getPingInterval());
            proxy.muted = true;
            try { assertFalse(org.jivesoftware.smackx.ping.PingManager.getInstanceFor(alice.connection()).pingMyServer(true, 250)); }
            finally { proxy.muted = false; }
            assertEquals("down", events.poll(10, TimeUnit.SECONDS));
            alice.connection().connect().login();
            assertEquals("up:false", events.poll(20, TimeUnit.SECONDS));
            assertEquals(identity, alice.ownFingerprint());
            alice.send("bob@localhost", "after unresponsive stream", true);
            assertEquals("after unresponsive stream", received.poll(15, TimeUnit.SECONDS));
        }
    }
}
