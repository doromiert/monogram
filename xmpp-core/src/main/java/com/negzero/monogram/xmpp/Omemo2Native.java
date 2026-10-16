// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

/** Internal OMEMO2-only ratchet. Store callbacks must belong to one atomic transaction. */
final class Omemo2Native {
    static { System.loadLibrary("monogram_omemo2"); }
    static final int IDENTITY = 0, PRE_KEY = 1, SIGNED_PRE_KEY = 2, SESSION = 3, SESSION_IDENTITY = 4, TRUSTED = 5;
    static final class Failure extends java.security.GeneralSecurityException {
        final int code;
        Failure(int code) { super("OMEMO2 ratchet operation failed (" + code + ")"); this.code = code; }
    }
    private Omemo2Native() {}
    /** Serialized private record and raw Ed25519 public identity. */
    static native byte[][] identity() throws Failure;
    /** Serialized private record and raw X25519 public prekey. */
    static native byte[][] preKey(int id) throws Failure;
    /** Serialized private record, raw X25519 public key and EdDSA-compatible signature. */
    static native byte[][] signedPreKey(byte[] identity, int id, long time) throws Failure;
    static native void build(Omemo2Records store, int localDevice, String peer, int device, long preKeyId, byte[] preKey,
            long signedId, byte[] signedKey, byte[] signature, byte[] identity) throws Exception;
    /** One-byte prekey flag and the modern protobuf ciphertext. Commit the store before sending. */
    static native byte[][] encrypt(Omemo2Records store, int localDevice, String peer, int device, byte[] plaintext) throws Exception;
    static native byte[] decrypt(Omemo2Records store, int localDevice, String peer, int device, byte[] ciphertext, boolean preKey) throws Exception;
}
