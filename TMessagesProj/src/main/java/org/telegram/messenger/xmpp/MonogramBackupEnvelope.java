// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.io.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable passphrase protection. No AndroidKeyStore dependency; plaintext never goes to public storage. */
final class MonogramBackupEnvelope {
    private static final byte[] MAGIC = {'M','G','B','A','C','K','U','P'};
    private static final int VERSION = 1, ITERATIONS = 600_000, CHUNK = 1024 * 1024;
    static final long MAX_PLAINTEXT = 8L * 1024 * 1024 * 1024;
    private MonogramBackupEnvelope() { }

    /** Streams from an app-private snapshot. Caller owns both streams and erases its passphrase. */
    static void encrypt(InputStream plain, OutputStream destination, char[] password) throws IOException, GeneralSecurityException {
        requirePassword(password);
        byte[] salt = new byte[16], prefix = new byte[8]; SecureRandom random = new SecureRandom();
        random.nextBytes(salt); random.nextBytes(prefix);
        byte[] header = header(salt, prefix), key = derive(password, salt);
        byte[] buffer = new byte[CHUNK]; DataOutputStream out = new DataOutputStream(destination);
        try {
            out.write(header); int index = 0; long total = 0;
            while (true) {
                int count = fill(plain, buffer); total += count;
                if (total > MAX_PLAINTEXT) throw new IOException("Backup exceeds the 8 GiB safety limit.");
                Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, prefix, header, index++, count);
                out.writeInt(count); out.write(cipher.doFinal(buffer, 0, count));
                if (count == 0) break; // Authenticated end marker prevents truncation at a valid chunk boundary.
            }
            out.flush();
        } finally { Arrays.fill(key, (byte)0); Arrays.fill(buffer, (byte)0); }
    }

    /** Destination MUST be private staging. Discard it on any error; apply nothing until this returns. */
    static void decrypt(InputStream encrypted, OutputStream staging, char[] password) throws IOException, GeneralSecurityException {
        requirePassword(password); DataInputStream in = new DataInputStream(encrypted);
        byte[] magic = new byte[MAGIC.length]; in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC) || in.readInt() != VERSION) throw new IOException("Unsupported Monogram backup format.");
        if (in.readInt() != ITERATIONS) throw new IOException("Unsupported backup key derivation parameters.");
        byte[] salt = new byte[16], prefix = new byte[8]; in.readFully(salt); in.readFully(prefix);
        byte[] header = header(salt, prefix), key = derive(password, salt);
        try {
            int index = 0; long total = 0;
            while (true) {
                int count = in.readInt();
                if (count < 0 || count > CHUNK || (total += count) > MAX_PLAINTEXT || index > MAX_PLAINTEXT / CHUNK)
                    throw new IOException("Invalid backup chunk size.");
                byte[] ciphertext = new byte[count + 16]; in.readFully(ciphertext);
                byte[] plaintext = cipher(Cipher.DECRYPT_MODE, key, prefix, header, index++, count).doFinal(ciphertext);
                try {
                    if (count == 0) {
                        if (in.read() != -1) throw new IOException("Unexpected data after the backup end marker.");
                        staging.flush(); return;
                    }
                    staging.write(plaintext);
                } finally { Arrays.fill(plaintext, (byte)0); }
            }
        } finally { Arrays.fill(key, (byte)0); }
    }
    private static void requirePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 1024)
            throw new IllegalArgumentException("Use a backup passphrase of at least 12 characters (maximum 1024).");
    }
    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        return com.negzero.monogram.xmpp.BackupKeyDerivation.derive256(password, salt, ITERATIONS);
    }
    private static byte[] header(byte[] salt, byte[] prefix) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC); out.writeInt(VERSION); out.writeInt(ITERATIONS); out.write(salt); out.write(prefix); return bytes.toByteArray();
    }
    private static Cipher cipher(int mode, byte[] key, byte[] prefix, byte[] header, int index, int size) throws GeneralSecurityException {
        byte[] nonce = Arrays.copyOf(prefix, 12);
        for (int i = 0; i < 4; i++) nonce[8+i] = (byte)(index >>> (24-i*8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        cipher.updateAAD(new byte[]{(byte)(index>>>24),(byte)(index>>>16),(byte)(index>>>8),(byte)index,
                (byte)(size>>>24),(byte)(size>>>16),(byte)(size>>>8),(byte)size});
        return cipher;
    }
    private static int fill(InputStream in, byte[] buffer) throws IOException {
        int length = 0;
        while (length < buffer.length) {
            int count = in.read(buffer, length, buffer.length-length);
            if (count < 0) break;
            if (count == 0) { int single = in.read(); if (single < 0) break; buffer[length++] = (byte)single; }
            else length += count;
        }
        return length;
    }
}
