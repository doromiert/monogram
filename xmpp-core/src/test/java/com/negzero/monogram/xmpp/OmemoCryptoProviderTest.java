package com.negzero.monogram.xmpp;

import java.security.Provider;
import java.security.Security;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OmemoCryptoProviderTest {
    @Test void legacyGcmWorksEvenWhenThePlatformAlreadyHasABouncyCastleProvider() throws Exception {
        Provider platform = new Provider("BC", 1.0, "Platform provider without the required OMEMO cipher") {};
        boolean installed = Security.addProvider(platform) != -1;
        try {
            OmemoCryptoProvider.install();
            SecretKeySpec key = new SecretKeySpec(new byte[16], "AES");
            IvParameterSpec iv = new IvParameterSpec(new byte[12]);
            Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
            assertEquals("MonogramOMEMO", encrypt.getProvider().getName());
            byte[] plaintext = "OMEMO provider test".getBytes("UTF-8");
            byte[] ciphertext = encrypt.doFinal(plaintext);
            Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, key, iv);
            assertArrayEquals(plaintext, decrypt.doFinal(ciphertext));
            ciphertext[0] ^= 1;
            decrypt.init(Cipher.DECRYPT_MODE, key, iv);
            assertThrows(javax.crypto.AEADBadTagException.class, () -> decrypt.doFinal(ciphertext));
        } finally { if (installed) Security.removeProvider(platform.getName()); }
    }
}
