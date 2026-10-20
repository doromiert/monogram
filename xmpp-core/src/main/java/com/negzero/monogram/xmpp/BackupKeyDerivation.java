// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.Arrays;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

/** Portable backup PBKDF2, including Android 23–25 where the platform JCA algorithm is absent. */
public final class BackupKeyDerivation {
    private BackupKeyDerivation() { }

    /** PBKDF2-HMAC-SHA256, UTF-8 without normalization, 256-bit output. Caller erases the returned key. */
    public static byte[] derive256(char[] password, byte[] salt, int iterations) {
        if (password == null || password.length > 1024 || salt == null || salt.length < 1 || salt.length > 1024
                || iterations < 1 || iterations > 10_000_000) throw new IllegalArgumentException("Invalid backup derivation parameters.");
        // Reject malformed UTF-16 instead of allowing providers to substitute different password bytes.
        for (int i=0;i<password.length;i++) {
            char value=password[i];
            if(Character.isHighSurrogate(value)) {
                if(++i==password.length || !Character.isLowSurrogate(password[i])) throw new IllegalArgumentException("Invalid backup passphrase Unicode.");
            } else if(Character.isLowSurrogate(value)) throw new IllegalArgumentException("Invalid backup passphrase Unicode.");
        }
        byte[] encoded=PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password),saltCopy=salt.clone();
        try {
            PKCS5S2ParametersGenerator generator=new PKCS5S2ParametersGenerator(new SHA256Digest());
            generator.init(encoded,saltCopy,iterations);
            byte[] derived=((KeyParameter)generator.generateDerivedParameters(256)).getKey();
            try { return derived.clone(); } finally { Arrays.fill(derived,(byte)0); }
        } finally { Arrays.fill(encoded,(byte)0);Arrays.fill(saltCopy,(byte)0); }
    }
}
