package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

class BackupKeyDerivationTest {
    private static byte[] hex(String value) {
        byte[] result=new byte[value.length()/2];
        for(int i=0;i<result.length;i++) result[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return result;
    }
    @Test void rfc7914Section11VectorsMatchAt256Bits() {
        // RFC 7914 gives 64-byte outputs; the first 32 are the identical PBKDF2 256-bit prefix.
        assertArrayEquals(hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"),
                BackupKeyDerivation.derive256("passwd".toCharArray(),"salt".getBytes(StandardCharsets.UTF_8),1));
        assertArrayEquals(hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"),
                BackupKeyDerivation.derive256("Password".toCharArray(),"NaCl".getBytes(StandardCharsets.UTF_8),80000));
    }
    @Test void jcaCompatibilityIncludesUnicodeNullSupplementaryAndProductionIterationCount() throws Exception {
        byte[] salt=hex("000102030405060708090a0b0c0d0e0f");
        for(String value:new String[]{"plain ASCII passphrase","pąsswörd🔐\u0000漢字","combining e\u0301","composed é"}) {
            char[] password=value.toCharArray(),before=password.clone();byte[] saltBefore=salt.clone();
            PBEKeySpec spec=new PBEKeySpec(password,salt,4096,256);
            try { assertArrayEquals(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),BackupKeyDerivation.derive256(password,salt,4096)); }
            finally { spec.clearPassword(); }
            assertArrayEquals(before,password);assertArrayEquals(saltBefore,salt);
        }
        char[] password="portable 🔐 backup passphrase".toCharArray();PBEKeySpec production=new PBEKeySpec(password,salt,600000,256);
        try { assertArrayEquals(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(production).getEncoded(),BackupKeyDerivation.derive256(password,salt,600000)); }
        finally { production.clearPassword();Arrays.fill(password,'\0'); }
        assertFalse(Arrays.equals(BackupKeyDerivation.derive256("é".toCharArray(),salt,1),BackupKeyDerivation.derive256("e\u0301".toCharArray(),salt,1)),"Passwords must not be normalized silently");
    }
    @Test void doesNotInstallProvidersAndRejectsAmbiguousUnicodeOrUnboundedWork() {
        Provider[] before=Security.getProviders();byte[] salt=new byte[16];
        BackupKeyDerivation.derive256("a backup passphrase".toCharArray(),salt,1);assertArrayEquals(before,Security.getProviders());
        for(char[] invalid:new char[][]{{'x','\ud800'},{'\udc00'},{'\ud800','x'}})
            assertThrows(IllegalArgumentException.class,()->BackupKeyDerivation.derive256(invalid,salt,1));
        assertThrows(IllegalArgumentException.class,()->BackupKeyDerivation.derive256(new char[1],salt,0));
        assertThrows(IllegalArgumentException.class,()->BackupKeyDerivation.derive256(new char[1],salt,10000001));
    }
}
