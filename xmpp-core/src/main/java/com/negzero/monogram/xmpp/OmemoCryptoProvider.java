// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.security.Provider;
import java.security.Security;

/** Smack's legacy OMEMO cipher uses IvParameterSpec; some platform GCM providers reject it. */
final class OmemoCryptoProvider extends Provider {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "MonogramOMEMO";
    private OmemoCryptoProvider() {
        super(NAME, 1.0, "Monogram OMEMO AES-GCM");
        put("Cipher.AES/GCM/NoPadding", "org.bouncycastle.jcajce.provider.symmetric.AES$GCM");
        // Android Keystore keys must continue to use the platform's hardware-backed provider.
        put("Cipher.AES/GCM/NoPadding SupportedKeyClasses", "javax.crypto.spec.SecretKeySpec");
    }
    static synchronized void install() {
        if (Security.getProvider(NAME) == null) Security.insertProviderAt(new OmemoCryptoProvider(), 1);
    }
}
