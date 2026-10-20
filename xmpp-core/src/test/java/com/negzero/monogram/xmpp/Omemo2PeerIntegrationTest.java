package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.packet.Message;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real TLS/PEP exchange with a separate Python crypto implementation, never a real account. */
@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2_PEER",matches=".+")
class Omemo2PeerIntegrationTest {
    private Message receive(Omemo2IntegrationTest.Client alice) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Message packet = alice.messages.poll(2,TimeUnit.SECONDS); if (packet == null) continue;
            XmppOmemo2.Received decoded = alice.service.receive(packet); alice.acknowledge(decoded);
            if (decoded.content != null) return decoded.content;
        }
        throw new AssertionError("Independent peer did not return authenticated content; inspect its fixture log");
    }
    private void exchange(boolean peerFirst) throws Exception {
        String mode = peerFirst ? "peer-first" : "native-first", prefix = peerFirst ? "firstpeer" : "peer";
        File state = new File(System.getenv("MONOGRAM_IT_STATE"));
        File log = new File(state,mode+".peer.log"), ready = new File(state,mode+".ready.json");
        try (Omemo2IntegrationTest.Client alice = new Omemo2IntegrationTest.Client(prefix+"alice",peerFirst ? 808 : 707)) {
            ProcessBuilder builder = new ProcessBuilder(System.getenv("MONOGRAM_OMEMO2_PEER"),"tests/interop/omemo2_peer.py");
            // Surefire runs in xmpp-core; the fixture's guarded state path is relative to the repository.
            builder.directory(new File("..").getCanonicalFile()); builder.environment().put("MONOGRAM_PEER_MODE",mode);
            builder.redirectErrorStream(true).redirectOutput(log); Process peer = builder.start();
            try {
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
                while (!ready.isFile() && peer.isAlive() && System.nanoTime()<deadline) Thread.sleep(50);
                assertTrue(ready.isFile(),() -> "Independent peer startup: "+read(log));
                String bob = prefix+"bob@localhost"; alice.trust(bob);
                Message original = new Message(JidCreate.entityFullFrom(bob+"/Independent"),Message.Type.chat);
                original.setFrom(alice.connection.getUser()); original.setStanzaId("independent-caption");
                if (peerFirst) {
                    original.setBody("start-independent-peer"); alice.connection.sendStanza(original);
                    assertEquals("Independent peer starts 🦄",receive(alice).getBody());
                    original.setBody("Native reply after independent key exchange 😀");
                } else {
                    String url = "aesgcm://example.org/private.png#"+new String(new char[88]).replace('\0','a');
                    FileShare file = new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>Friends.png</name><media-type>image/png</media-type></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+url+"'/></sources></file-sharing>");
                    original.setBody(file.body("Private caption 😀")); file.addTo(original,original.getBody());
                    new ReplyReference(bob,"quoted").addTo(original); new MessageCorrection("original").addTo(original);
                }
                original.setFrom(alice.connection.getUser());
                Message encrypted = alice.service.encrypt(original); alice.connection.sendStanza(encrypted);
                Message decoded = receive(alice); assertEquals(original.getBody(),decoded.getBody());
                if (!peerFirst) {
                    FileShare file = FileShare.from(decoded); assertNotNull(file); assertEquals("Friends.png",file.name);
                    assertTrue(file.link.encrypted()); assertEquals("Private caption 😀",file.caption(decoded,decoded.getBody(),ReplyReference.from(decoded,decoded.getBody())));
                    assertEquals("quoted",ReplyReference.from(decoded,decoded.getBody()).id); assertEquals("original",MessageCorrection.from(decoded).id);
                }
                MessageReactions.initialize();
                for(java.util.List<String> emojis:java.util.Arrays.asList(java.util.Arrays.asList("👍","🐢"),java.util.Collections.<String>emptyList())) {
                    Message reaction=new Message(original.getTo(),Message.Type.chat); reaction.setFrom(alice.connection.getUser()); reaction.setStanzaId(java.util.UUID.randomUUID().toString());
                    new MessageReactions("independent-caption",emojis).addTo(reaction);
                    Message wire=alice.service.encrypt(reaction); assertFalse(wire.toXML().toString().contains("urn:xmpp:reactions:0"));
                    alice.connection.sendStanza(wire);
                    Message returned=receive(alice); assertNull(returned.getBody());
                    MessageReactions result=MessageReactions.from(returned); assertNotNull(result); assertEquals("independent-caption",result.id); assertEquals(new java.util.LinkedHashSet<>(emojis),result.emojis);
                }
                assertFalse(new File(state,mode+".error").exists(),() -> read(log));
            } finally { peer.destroy(); if (!peer.waitFor(5,TimeUnit.SECONDS)) { peer.destroyForcibly(); peer.waitFor(5,TimeUnit.SECONDS); } }
        }
    }
    private static String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return e.toString(); }
    }
    @Test void independentPeerDecryptsPrivateCaptionAndEncryptsItsReply() throws Exception { exchange(false); }
    @Test void independentPeerCanInitiateANewSessionAndReceiveNativeReply() throws Exception { exchange(true); }
}
