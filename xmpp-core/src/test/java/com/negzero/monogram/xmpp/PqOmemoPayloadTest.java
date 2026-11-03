// SPDX-License-Identifier: AGPL-3.0-only
// Upstream vectors: Monocles XmppOmemo2MessageTest; provenance in docs/pq-omemo.md.
package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PqOmemoPayloadTest {
    private static byte[] range(int first, int last) {
        byte[] result = new byte[last - first + 1];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (first + i);
        return result;
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static byte[] key() { byte[] key = new byte[32]; Arrays.fill(key, (byte) 7); return key; }
    private static byte[] binding() { return PqOmemoPayload.binding("alice@example.com", "bob@example.com", 42); }

    @Test void nistKmac256Vectors() {
        byte[] tag = "My Tagged Application".getBytes(StandardCharsets.UTF_8);
        assertEquals("20c570c31346f703c9ac36c61c03cb64c3970d0cfc787e9b79599d273a68d2f7f69d4cc3de9d104a351689f27cf6f5951f0103f33f4f24871024d9c27773a8dd",
                hex(PqOmemoPayload.kmac(range(0x40, 0x5f), range(0, 3), tag, 64)));
        assertEquals("75358cf39e41494e949707927cee0af20a3ff553904c86b08f21cc414bcfd691589d27cf5e15369cbbff8b9a4c2eb17800855d0235ff635da82533ec6b759b69",
                hex(PqOmemoPayload.kmac(range(0x40, 0x5f), range(0, 0xc7), new byte[0], 64)));
        assertEquals("b58618f71f92e1d56c1b8c55ddd7cd188b97b4ca4d99831eb2699a837da2e4d970fbacfde50033aea585f1a2708510c32d07880801bd182898fe476876fc8965",
                hex(PqOmemoPayload.kmac(range(0x40, 0x5f), range(0, 0xc7), tag, 64)));
    }

    @Test void monoclesKnownAnswers() {
        assertEquals("e04e685382db88563a43d2a5d55218bf917b5b57989b377636d88cf7f479bfc531b5a1a87a4eeef2909d8510a27f0b83e9f183361686ad5a3b00194794bde224",
                hex(PqOmemoPayload.commitment(key(), binding())));
        assertEquals("91d133b399016d8ed75e9e585ecdcd7ffb1c95b9b364e188784ccbab610d97e7058b5c01dd3d1eac0e564269",
                hex(PqOmemoPayload.derive(key(), binding())));
    }
    @Test void hybridFingerprintMatchesMonoclesKnownAnswer() {
        byte[] classic = new byte[33], pq = new byte[2592];
        Arrays.fill(classic,(byte) 0x11); classic[0] = 5; Arrays.fill(pq,(byte) 0x22);
        assertEquals("6b6ea370b7cbc0078f992487b235ab384a7f272b232e508a2d27da9b42f1def7f2def0daffbdfd91a33065c1e383473a1eacce6e5709833d286c5e399e19c77a",
                PqOmemoBundle.fingerprint(classic,pq));
    }

    @Test void rejectsWrongContextKeyCommitmentAndCiphertext() throws Exception {
        byte[] plaintext = "<envelope xmlns='urn:xmpp:sce:1'>hello</envelope>".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = PqOmemoPayload.seal(plaintext, key(), binding());
        byte[] commit = PqOmemoPayload.commitment(key(), binding());
        assertArrayEquals(plaintext, PqOmemoPayload.open(encrypted, key(), binding(), commit));
        for (byte[] context : new byte[][] {
                PqOmemoPayload.binding("mallory@example.com", "bob@example.com", 42),
                PqOmemoPayload.binding("alice@example.com", "mallory@example.com", 42),
                PqOmemoPayload.binding("alice@example.com", "bob@example.com", 43)}) {
            assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.open(encrypted, key(), context, commit));
        }
        assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.open(encrypted, new byte[32], binding(), commit));
        assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.open(encrypted, key(), binding(), null));
        byte[] changed = commit.clone(); changed[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.open(encrypted, key(), binding(), changed));
        encrypted[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.open(encrypted, key(), binding(), commit));
    }

    @Test void rejectsInvalidLengths() {
        assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.seal(new byte[PqOmemoPayload.MAX + 1], key(), binding()));
        assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.seal(new byte[0], new byte[16], binding()));
        assertThrows(GeneralSecurityException.class, () -> PqOmemoPayload.open(new byte[15], key(), binding(), new byte[64]));
    }
}
