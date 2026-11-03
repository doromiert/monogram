// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Monocles Chat XmppOmemo2Message (see docs/pq-omemo.md).
package com.negzero.monogram.xmpp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.macs.KMAC;
import org.bouncycastle.crypto.params.KeyParameter;

/** Monocles PQ profile payload v3. Never use with standard OMEMO 2 keys or stanzas. */
final class PqOmemoPayload {
    static final String NS = "urn:monocles:omemo-pq:1";
    static final int MAX = 256 * 1024;
    private static final byte[] PAYLOAD = utf8("monocles:omemo2:payload:v3");
    private static final byte[] COMMIT = utf8("monocles:omemo2:key-commitment:v3");

    private PqOmemoPayload() {}

    static byte[] binding(String from, String to, int senderDevice) {
        if (from == null || to == null || senderDevice <= 0 || from.indexOf('\0') >= 0 || to.indexOf('\0') >= 0)
            throw new IllegalArgumentException("PQ payload requires bare sender/recipient and device");
        byte[] sender = utf8(from), recipient = utf8(to);
        return ByteBuffer.allocate(6 + 1 + sender.length + 1 + recipient.length + 1 + 4)
                .put(utf8("OMEMO2")).put((byte) 0).put(sender).put((byte) 0)
                .put(recipient).put((byte) 0).putInt(senderDevice).array();
    }

    static byte[] kmac(byte[] key, byte[] data, byte[] customization, int length) {
        KMAC mac = new KMAC(256, customization);
        mac.init(new KeyParameter(key));
        mac.update(data, 0, data.length);
        byte[] result = new byte[length];
        mac.doFinal(result, 0, length);
        // KMAC keeps its own key copy; Java cannot reliably erase that copy.
        return result;
    }

    static byte[] derive(byte[] key, byte[] binding) { return kmac(key, binding, PAYLOAD, 44); }
    static byte[] commitment(byte[] key, byte[] binding) { return kmac(key, binding, COMMIT, 64); }

    static byte[] seal(byte[] plaintext, byte[] key, byte[] binding) throws GeneralSecurityException {
        if (key.length != 32 || plaintext.length > MAX) throw invalid();
        return crypt(Cipher.ENCRYPT_MODE, plaintext, key, binding);
    }

    static byte[] open(byte[] ciphertext, byte[] key, byte[] binding, byte[] commitment)
            throws GeneralSecurityException {
        if (key.length != 32 || ciphertext.length < 16 || ciphertext.length > MAX + 16
                || commitment == null || commitment.length != 64) throw invalid();
        byte[] expected = commitment(key, binding);
        try {
            if (!MessageDigest.isEqual(expected, commitment)) throw invalid();
        } finally { Arrays.fill(expected, (byte) 0); }
        return crypt(Cipher.DECRYPT_MODE, ciphertext, key, binding);
    }

    private static byte[] crypt(int mode, byte[] input, byte[] key, byte[] binding)
            throws GeneralSecurityException {
        byte[] derived = derive(key, binding);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(derived, 0, 32, "AES"),
                    new GCMParameterSpec(128, derived, 32, 12));
            cipher.updateAAD(binding);
            return cipher.doFinal(input);
        } finally { Arrays.fill(derived, (byte) 0); }
    }

    private static GeneralSecurityException invalid() { return new GeneralSecurityException("Invalid PQ OMEMO payload"); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
