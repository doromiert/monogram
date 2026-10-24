package com.negzero.monogram.xmpp;

import java.util.HashMap;
import java.util.Map;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONOGRAM_OMEMO2", matches = "1")
class Omemo2NativeTest {
    static final String ALICE = "alice@example.org", BOB = "bob@example.org";
    static final class Records implements Omemo2Records {
        Map<String, byte[]> records = new HashMap<>(), pending;
        boolean failSave, failLoad;
        final int localDevice;
        Records(int device) throws Exception {
            localDevice = device;
            byte[][] identity = Omemo2Native.identity(), pre = Omemo2Native.preKey(11);
            byte[][] signed = Omemo2Native.signedPreKey(identity[0], 22, System.currentTimeMillis());
            put(Omemo2Native.IDENTITY, "", 0, identity[0]); put(Omemo2Native.IDENTITY, "", 1, identity[1]);
            put(Omemo2Native.PRE_KEY, "", 11, pre[0]); put(Omemo2Native.PRE_KEY, "", 12, pre[1]);
            put(Omemo2Native.SIGNED_PRE_KEY, "", 22, signed[0]); put(Omemo2Native.SIGNED_PRE_KEY, "", 23, signed[1]); put(Omemo2Native.SIGNED_PRE_KEY, "", 24, signed[2]);
        }
        byte[] get(int kind, String peer, int id) { return records.get(kind+"\n"+peer+"\n"+id); }
        void put(int kind, String peer, int id, byte[] value) { records.put(kind+"\n"+peer+"\n"+id, value); }
        @Override public byte[] load(int kind, String peer, int id) throws Exception {
            if (failLoad) throw new java.io.IOException("Test load failure");
            byte[] value = pending.get(kind+"\n"+peer+"\n"+id); return value == null ? null : value.clone();
        }
        @Override public void save(int kind, String peer, int id, byte[] value) throws Exception {
            if (failSave) throw new java.io.IOException("Test persistence failure");
            String key = kind+"\n"+peer+"\n"+id;
            if (value == null) pending.remove(key); else pending.put(key, value.clone());
        }
        @Override public int[] ids(int kind) {
            String prefix = kind+"\n\n";
            return pending.keySet().stream().filter(k -> k.startsWith(prefix)).mapToInt(k -> Integer.parseInt(k.substring(prefix.length()))).sorted().toArray();
        }
        @Override public void commit() { records = pending; pending = null; }
        @Override public void close() { pending = null; }
        interface Operation<T> { T run() throws Exception; }
        <T> T transaction(Operation<T> body) throws Exception {
            pending = new HashMap<>(records);
            try { T result = body.run(); commit(); return result; }
            finally { close(); }
        }
        void trust(String peer, int device, Records other) { put(Omemo2Native.TRUSTED, peer, device, other.get(Omemo2Native.IDENTITY,"",1).clone()); }
        void build(String peer, int device, Records other) throws Exception {
            transaction(() -> { Omemo2Native.build(this, localDevice, peer, device, 11, other.get(Omemo2Native.PRE_KEY,"",12),
                    22, other.get(Omemo2Native.SIGNED_PRE_KEY,"",23), other.get(Omemo2Native.SIGNED_PRE_KEY,"",24), other.get(Omemo2Native.IDENTITY,"",1)); return null; });
        }
        byte[][] encrypt(String peer, int device, String text) throws Exception {
            return transaction(() -> Omemo2Native.encrypt(this, localDevice, peer, device, text.getBytes("UTF-8")));
        }
        String decrypt(String peer, int device, byte[][] message) throws Exception {
            return transaction(() -> new String(Omemo2Native.decrypt(this, localDevice, peer, device, message[1], message[0][0] == 1), "UTF-8"));
        }
    }
    @Test void publicBundleUsesEd25519SignatureFormatVerifiedIndependently() throws Exception {
        Records bob = new Records(202); byte[] identity = bob.get(Omemo2Native.IDENTITY,"",1), publicKey = bob.get(Omemo2Native.SIGNED_PRE_KEY,"",23);
        assertEquals(32, identity.length); assertEquals(32, publicKey.length);
        Ed25519Signer verifier = new Ed25519Signer(); verifier.init(false, new Ed25519PublicKeyParameters(identity,0));
        verifier.update(publicKey,0,publicKey.length);
        assertTrue(verifier.verifySignature(bob.get(Omemo2Native.SIGNED_PRE_KEY,"",24)));
    }
    @Test void bidirectionalRatchetsPreserveSerializedStateAndHandleOutOfOrderMessages() throws Exception {
        Records alice = new Records(101), bob = new Records(202); alice.trust(BOB,202,bob); bob.trust(ALICE,101,alice); alice.build(BOB,202,bob);
        byte[][] first = alice.encrypt(BOB,202,"Caption 😀"); assertEquals(1,first[0][0]);
        assertEquals("Caption 😀",bob.decrypt(ALICE,101,first));
        assertNull(bob.get(Omemo2Native.PRE_KEY,"",11), "Receiving a prekey message must consume the one-time key");
        assertEquals("Reply 🦄",alice.decrypt(BOB,202,bob.encrypt(ALICE,101,"Reply 🦄")));
        byte[][] second = alice.encrypt(BOB,202,"Second"), third = alice.encrypt(BOB,202,"Third");
        assertEquals(0, second[0][0]); assertEquals("Third",bob.decrypt(ALICE,101,third)); assertEquals("Second",bob.decrypt(ALICE,101,second));
        Omemo2Native.Failure replay = assertThrows(Omemo2Native.Failure.class, () -> bob.decrypt(ALICE,101,second));
        assertEquals(-1001,replay.code);
    }
    @Test void missingChangedOrSignFlippedIdentityNeverInheritsTrust() throws Exception {
        Records alice = new Records(101), bob = new Records(202);
        assertEquals(-1010,assertThrows(Omemo2Native.Failure.class, () -> alice.build(BOB,202,bob)).code);
        alice.trust(BOB,202,bob); bob.trust(ALICE,101,alice); alice.build(BOB,202,bob);
        byte[] changed = alice.get(Omemo2Native.TRUSTED,BOB,202).clone(); changed[31] ^= (byte)0x80;
        alice.put(Omemo2Native.TRUSTED,BOB,202,changed);
        assertEquals(-1010,assertThrows(Omemo2Native.Failure.class, () -> alice.encrypt(BOB,202,"Do not send")).code);
        assertEquals(-1010,assertThrows(Omemo2Native.Failure.class, () -> alice.build(BOB,202,bob)).code);
        alice.trust(BOB,202,bob);
        byte[][] first = alice.encrypt(BOB,202,"Protected"); byte[] alicePin = bob.get(Omemo2Native.TRUSTED,ALICE,101).clone(); alicePin[31] ^= (byte)0x80;
        bob.put(Omemo2Native.TRUSTED,ALICE,101,alicePin);
        assertEquals(-1010,assertThrows(Omemo2Native.Failure.class, () -> bob.decrypt(ALICE,101,first)).code);
        assertNotNull(bob.get(Omemo2Native.PRE_KEY,"",11));
    }
    @Test void corruptCiphertextAndFailedPersistenceDoNotConsumePrekeysOrAdvanceCommittedSessions() throws Exception {
        Records alice = new Records(101), bob = new Records(202); alice.trust(BOB,202,bob); bob.trust(ALICE,101,alice); alice.build(BOB,202,bob);
        byte[][] first = alice.encrypt(BOB,202,"Keep this message");
        byte[][] corrupt = {first[0],first[1].clone()}; corrupt[1][corrupt[1].length-1] ^= 1;
        assertThrows(Omemo2Native.Failure.class, () -> bob.decrypt(ALICE,101,corrupt)); assertNotNull(bob.get(Omemo2Native.PRE_KEY,"",11));
        bob.failSave = true; assertThrows(java.io.IOException.class, () -> bob.decrypt(ALICE,101,first)); assertNotNull(bob.get(Omemo2Native.PRE_KEY,"",11));
        bob.failSave = false; assertEquals("Keep this message",bob.decrypt(ALICE,101,first));
        byte[] before = alice.get(Omemo2Native.SESSION,BOB,202).clone(); alice.failSave = true;
        assertThrows(java.io.IOException.class, () -> alice.encrypt(BOB,202,"Failed send"));
        assertArrayEquals(before,alice.get(Omemo2Native.SESSION,BOB,202));
        alice.failSave = false; alice.failLoad = true;
        assertThrows(java.io.IOException.class, () -> alice.encrypt(BOB,202,"Failed read"));
        assertArrayEquals(before,alice.get(Omemo2Native.SESSION,BOB,202));
    }
    @Test void parsedUnsignedBundleIdsEstablishADecryptableSession()throws Exception {
        for(long keyId:new long[]{0,2147483648L,4294967295L}) {
            Records alice=new Records(101),bob=new Records(202);
            // Simulate an external client that indexes its existing keys by uint32 IDs.
            bob.put(Omemo2Native.PRE_KEY,"",(int)keyId,bob.get(Omemo2Native.PRE_KEY,"",11));
            bob.put(Omemo2Native.SIGNED_PRE_KEY,"",(int)keyId,bob.get(Omemo2Native.SIGNED_PRE_KEY,"",22));
            java.util.Map<Long,byte[]> keys=java.util.Collections.singletonMap(keyId,bob.get(Omemo2Native.PRE_KEY,"",12));
            Omemo2Bundle bundle=new Omemo2Bundle(bob.get(Omemo2Native.IDENTITY,"",1),keyId,bob.get(Omemo2Native.SIGNED_PRE_KEY,"",23),bob.get(Omemo2Native.SIGNED_PRE_KEY,"",24),keys);
            Omemo2Bundle parsed=Omemo2Bundle.parse(org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE.parse(org.jivesoftware.smack.util.PacketParserUtils.getParserFor(bundle.xml().toXML().toString())));
            alice.trust(BOB,202,bob);bob.trust(ALICE,101,alice);
            Omemo2Device sender=new Omemo2Device(ALICE,101,()->alice);
            alice.transaction(()->{sender.session(alice,BOB,202,parsed);return null;});
            byte[][] encrypted=alice.encrypt(BOB,202,"Unsigned prekeys "+keyId);
            assertEquals("Unsigned prekeys "+keyId,bob.decrypt(ALICE,101,encrypted));
        }
    }
    @Test void modernBoundaryRejectsMalformedInputsAndAcceptsFullUnsignedPrekeyIds() throws Exception {
        Records alice = new Records(101), bob = new Records(202); alice.trust(BOB,202,bob); bob.trust(ALICE,101,alice);
        alice.transaction(() -> {
            Omemo2Native.build(alice,101,BOB,202,0,bob.get(Omemo2Native.PRE_KEY,"",12),
                    0,bob.get(Omemo2Native.SIGNED_PRE_KEY,"",23),bob.get(Omemo2Native.SIGNED_PRE_KEY,"",24),bob.get(Omemo2Native.IDENTITY,"",1));
            Omemo2Native.build(alice,101,BOB,202,0xffffffffL,bob.get(Omemo2Native.PRE_KEY,"",12),
                    0xfffffffeL,bob.get(Omemo2Native.SIGNED_PRE_KEY,"",23),bob.get(Omemo2Native.SIGNED_PRE_KEY,"",24),bob.get(Omemo2Native.IDENTITY,"",1));
            return null;
        });
        alice.build(BOB,202,bob);
        java.util.Random random = new java.util.Random(41038);
        for (int length = 0; length < 64; length++) {
            byte[] malformed = new byte[length]; random.nextBytes(malformed);
            assertThrows(Omemo2Native.Failure.class, () -> bob.transaction(() -> Omemo2Native.decrypt(bob,202,ALICE,101,malformed,true)));
        }
        byte[] signature = bob.get(Omemo2Native.SIGNED_PRE_KEY,"",24).clone(); signature[63] |= (byte)0x80;
        assertThrows(Omemo2Native.Failure.class, () -> alice.transaction(() -> {
            Omemo2Native.build(alice,101,BOB,202,11,bob.get(Omemo2Native.PRE_KEY,"",12),22,bob.get(Omemo2Native.SIGNED_PRE_KEY,"",23),signature,bob.get(Omemo2Native.IDENTITY,"",1)); return null;
        }));
    }
}
