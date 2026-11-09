// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.security.SecureRandom;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

class PqOmemoNativeTest {
    static class Database implements PqOmemoRecords.Factory {
        Map<String,byte[]> committed = new TreeMap<>();
        private final java.util.concurrent.locks.ReentrantLock transaction=new java.util.concurrent.locks.ReentrantLock();
        @Override public PqOmemoRecords begin() {
            transaction.lock();
            Map<String,byte[]> staged = new TreeMap<>(); committed.forEach((key,value) -> staged.put(key,value.clone()));
            return new PqOmemoRecords() {
                boolean closed;
                void active() { if (closed) throw new IllegalStateException("closed transaction"); }
                public byte[] get(String key) { active(); byte[] bytes = staged.get(key); return bytes == null ? null : bytes.clone(); }
                public void put(String key,byte[] value) { active(); if (value == null) staged.remove(key); else staged.put(key,value.clone()); }
                public Set<String> keys(String prefix) { active(); Set<String> keys = new TreeSet<>(); for (String key : staged.keySet()) if (key.startsWith(prefix)) keys.add(key); return keys; }
                public void commit() { active(); committed = staged; closed = true; transaction.unlock(); }
                public void close() { if(!closed){closed = true;transaction.unlock();} }
            };
        }
    }
    @BeforeAll static void providers() {
        ProviderManager.addExtensionProvider("encrypted",PqOmemoBundle.NS,StandardExtensionElementProvider.INSTANCE);
    }
    static class Pair {
        final Database a = new Database(), b = new Database();
        PqOmemoDevice alice = new PqOmemoDevice("alice@example.org",a), bob = new PqOmemoDevice("bob@example.org",b);
        final PqOmemoBundle aliceBundle = alice.prepare(), bobBundle = bob.prepare();
        Pair() throws Exception {
            alice.observe(bob.jid,bob.id,bobBundle); bob.observe(alice.jid,alice.id,aliceBundle);
            alice.trust(bob.jid,bob.id,bobBundle.fingerprint(),true); bob.trust(alice.jid,alice.id,aliceBundle.fingerprint(),true);
        }
        Message send(PqOmemoDevice from,PqOmemoDevice to,PqOmemoBundle bundle,String body) throws Exception {
            Message clear = new Message(JidCreate.entityBareFrom(to.jid),Message.Type.chat);
            clear.setFrom(JidCreate.entityFullFrom(from.jid + "/phone")); clear.setBody(body); clear.setStanzaId(UUID.randomUUID().toString());
            StandardExtensionElement cipher = PqOmemoMessage.encrypt(from,clear,Collections.singletonList(new PqOmemoMessage.Target(to.jid,to.id,bundle)),new SecureRandom());
            Message outer = new Message(clear.getTo(),Message.Type.chat); outer.setFrom(clear.getFrom()); outer.setStanzaId(clear.getStanzaId()); outer.addExtension(cipher);
            return PacketParserUtils.parseStanza(outer.toXML().toString());
        }
        Message receive(PqOmemoDevice to,PqOmemoDevice from,Message message) throws Exception {
            return PqOmemoMessage.decrypt(to,message,from.jid,to.jid,System.currentTimeMillis());
        }
    }
    @Test void survivesRestartAndContinuousRatchetWithOutOfOrderMessages() throws Exception {
        Pair p = new Pair(); int bobId = p.bob.id;
        Message first = p.send(p.alice,p.bob,p.bobBundle,"initial");
        assertEquals("initial",p.receive(p.bob,p.alice,first).getBody());
        assertEquals("reply",p.receive(p.alice,p.bob,p.send(p.bob,p.alice,p.aliceBundle,"reply")).getBody());
        p.alice = new PqOmemoDevice(p.alice.jid,p.a); p.bob = new PqOmemoDevice(p.bob.jid,p.b);
        assertEquals(bobId,p.bob.id); assertEquals(p.bobBundle.fingerprint(),p.bob.prepare().fingerprint());
        List<Message> queued = new ArrayList<>();
        for (int i = 0; i < 12; i++) queued.add(p.send(p.alice,p.bob,p.bobBundle,"message " + i));
        for (int i = 11; i >= 0; i--) assertEquals("message " + i,p.receive(p.bob,p.alice,queued.get(i)).getBody());
        assertThrows(DuplicateMessageException.class,() -> p.receive(p.bob,p.alice,first));
    }
    @Test void tamperedCommitmentRollsBackTheRatchetAndConsumedPrekeys() throws Exception {
        Pair p = new Pair(); Message original = p.send(p.alice,p.bob,p.bobBundle,"secret");
        StandardExtensionElement encrypted = original.getExtension("encrypted",PqOmemoBundle.NS);
        String xml = original.toXML().toString(), commit = PqOmemoBundle.one(encrypted,"commit").getText();
        Message bad = PacketParserUtils.parseStanza(xml.replace(commit, PqOmemoBundle.b64(new byte[64])));
        assertThrows(java.security.GeneralSecurityException.class,() -> p.receive(p.bob,p.alice,bad));
        assertEquals("secret",p.receive(p.bob,p.alice,original).getBody());
    }
    @Test void missingOrChangedHandshakeAuthenticationCannotCreateASession() throws Exception {
        Pair p = new Pair(); Message original = p.send(p.alice,p.bob,p.bobBundle,"secret");
        StandardExtensionElement encrypted = original.getExtension("encrypted",PqOmemoBundle.NS);
        StandardExtensionElement auth = PqOmemoBundle.one(PqOmemoBundle.one(encrypted,"header"),"pq-auth");
        String xml = original.toXML().toString();
        Message bad = PacketParserUtils.parseStanza(xml.replace(auth.toXML().toString(),""));
        assertThrows(Exception.class,() -> p.receive(p.bob,p.alice,bad));
        assertTrue(p.b.committed.keySet().stream().noneMatch(key -> key.startsWith("session/")));
        String signature = PqOmemoBundle.one(auth,"pq-sig").getText();
        Message tampered = PacketParserUtils.parseStanza(xml.replace(signature,PqOmemoBundle.b64(new byte[4627])));
        assertThrows(Exception.class,() -> p.receive(p.bob,p.alice,tampered));
        assertEquals("secret",p.receive(p.bob,p.alice,original).getBody());
    }
    @Test void hybridKeyChangesRequireFreshExplicitApproval() throws Exception {
        Pair p = new Pair();
        PqOmemoDevice replacement = new PqOmemoDevice(p.bob.jid,new Database()); PqOmemoBundle changed = replacement.prepare();
        // Same device identifier, different identity; approving a stale dialog must fail.
        p.alice.observe(p.bob.jid,p.bob.id,changed);
        assertFalse(p.alice.fingerprints(p.bob.jid).get(0).trusted);
        assertThrows(java.security.GeneralSecurityException.class,() -> p.alice.trust(p.bob.jid,p.bob.id,p.bobBundle.fingerprint(),true));
        assertThrows(UntrustedIdentityException.class,() -> p.send(p.alice,p.bob,changed,"do not downgrade"));
        p.alice.trust(p.bob.jid,p.bob.id,changed.fingerprint(),false);
        assertTrue(p.alice.fingerprints(p.bob.jid).get(0).blocked);
    }
    @Test void lastResortReplayTuplesPersistAndRollbackAtomically() throws Exception {
        Database database = new Database(); PqOmemoDevice device = new PqOmemoDevice("alice@example.org",database);
        byte[] base = ECKeyPair.generate().getPublicKey().serialize();
        try (PqOmemoRecords tx = database.begin()) {
            tx.put("last-resort/12",new byte[]{1});
            device.store(tx).markKyberPreKeyUsed(12,4,new org.signal.libsignal.protocol.ecc.ECPublicKey(base)); tx.commit();
        }
        PqOmemoDevice restarted = new PqOmemoDevice(device.jid,database);
        try (PqOmemoRecords tx = database.begin()) {
            assertThrows(ReusedBaseKeyException.class,() -> restarted.store(tx).markKyberPreKeyUsed(12,4,new org.signal.libsignal.protocol.ecc.ECPublicKey(base)));
            restarted.store(tx).markKyberPreKeyUsed(12,5,new org.signal.libsignal.protocol.ecc.ECPublicKey(base));
            // No commit: failed payload must not consume this tuple.
        }
        try (PqOmemoRecords tx = database.begin()) {
            restarted.store(tx).markKyberPreKeyUsed(12,5,new org.signal.libsignal.protocol.ecc.ECPublicKey(base)); tx.commit();
        }
    }
    @Test void refusesPartialIdentityInsteadOfReplacingIt() throws Exception {
        Database database = new Database(); new PqOmemoDevice("alice@example.org",database);
        byte[] identity = database.committed.get(PqOmemoDevice.IDENTITY).clone(); database.committed.remove(PqOmemoDevice.PQ);
        assertThrows(java.security.GeneralSecurityException.class,() -> new PqOmemoDevice("alice@example.org",database));
        assertArrayEquals(identity,database.committed.get(PqOmemoDevice.IDENTITY));
    }
    @Test void authenticatedBundleRejectsKemAndEitherSignatureSubstitution() throws Exception {
        Pair p = new Pair(); StandardExtensionElement original = p.bobBundle.xml();
        for (String signature : new String[]{"pq-sig","ik-sig","kem-spks"}) {
            StandardExtensionElement field = PqOmemoBundle.one(original,signature);
            byte[] value = Base64.getDecoder().decode(field.getText()); value[value.length / 2] ^= 1;
            StandardExtensionElement bad = StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(original.toXML().toString().replace(field.getText(),PqOmemoBundle.b64(value))));
            assertThrows(Exception.class,() -> PqOmemoBundle.parse(bad));
        }
    }
    @Test void inboxIsCommittedWithRatchetAndCanBeRecoveredAfterRestart() throws Exception {
        Pair p = new Pair(); Message wire = p.send(p.alice,p.bob,p.bobBundle,"recover me");
        String digest = PqOmemoJournal.digest(wire,p.alice.jid,p.bob.jid); long timestamp = System.currentTimeMillis();
        PqOmemoMessage.decrypt(p.bob,wire,p.alice.jid,p.bob.jid,timestamp,(tx,content) ->
                PqOmemoJournal.save(tx,new PqOmemoJournal.Entry(digest,p.aliceBundle.fingerprint(),wire,content,timestamp,null)));
        p.bob = new PqOmemoDevice(p.bob.jid,p.b);
        try (PqOmemoRecords tx = p.b.begin()) {
            PqOmemoJournal.Entry pending = PqOmemoJournal.find(tx,digest);
            assertNotNull(pending); assertEquals("recover me",pending.content.getBody());
            assertEquals(p.aliceBundle.fingerprint(),pending.fingerprint); assertEquals(1,PqOmemoJournal.pending(tx).size());
        }
    }
    @Test void fullInboxDoesNotConsumeAHandshake() throws Exception {
        Pair p = new Pair(); Message wire = p.send(p.alice,p.bob,p.bobBundle,"wait for space");
        try (PqOmemoRecords tx = p.b.begin()) {
            for (int i = 0; i < PqOmemoJournal.MAX_ENTRIES; i++) tx.put("incoming/" + String.format("%064x",i),new byte[]{1}); tx.commit();
        }
        long timestamp = System.currentTimeMillis(); String digest = PqOmemoJournal.digest(wire,p.alice.jid,p.bob.jid);
        assertThrows(PqOmemoJournal.Full.class,() -> PqOmemoMessage.decrypt(p.bob,wire,p.alice.jid,p.bob.jid,timestamp,(tx,content) ->
                PqOmemoJournal.save(tx,new PqOmemoJournal.Entry(digest,p.aliceBundle.fingerprint(),wire,content,timestamp,null))));
        assertTrue(p.b.committed.keySet().stream().noneMatch(key -> key.startsWith("session/")));
        try (PqOmemoRecords tx = p.b.begin()) { for (String key : tx.keys("incoming/")) tx.put(key,null); tx.commit(); }
        assertEquals("wait for space",p.receive(p.bob,p.alice,wire).getBody());
    }
}
