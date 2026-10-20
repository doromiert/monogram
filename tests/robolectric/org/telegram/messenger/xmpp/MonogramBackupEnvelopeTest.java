package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.Test;

public class MonogramBackupEnvelopeTest {
    private static final char[] PASSWORD = "a unique backup passphrase".toCharArray();
    private byte[] encrypt(byte[] plain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MonogramBackupEnvelope.encrypt(new ByteArrayInputStream(plain), out, PASSWORD); return out.toByteArray();
    }
    private byte[] decrypt(byte[] encrypted, char[] password) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MonogramBackupEnvelope.decrypt(new ByteArrayInputStream(encrypted), out, password); return out.toByteArray();
    }
    @Test public void portableChunkedRoundTripDoesNotNeedAndroidKeystore() throws Exception {
        byte[] plain = new byte[1024*1024+137]; new java.util.Random(74).nextBytes(plain);
        byte[] first = encrypt(plain), second = encrypt(plain);
        assertFalse(Arrays.equals(first,second));assertArrayEquals(plain,decrypt(first,PASSWORD));
        assertArrayEquals(new byte[0],decrypt(encrypt(new byte[0]),PASSWORD));
    }
    @Test public void wrongPasswordTamperingMissingEndAndTrailingDataAreRejected() throws Exception {
        byte[] encrypted = encrypt("private keys and local messages".getBytes("UTF-8"));
        assertThrows(GeneralSecurityException.class,()->decrypt(encrypted,"another secure passphrase".toCharArray()));
        byte[] changed = encrypted.clone();changed[changed.length-1]^=1;
        assertThrows(GeneralSecurityException.class,()->decrypt(changed,PASSWORD));
        byte[] saltChanged = encrypted.clone();saltChanged[17]^=1;
        assertThrows(GeneralSecurityException.class,()->decrypt(saltChanged,PASSWORD));
        assertThrows(IOException.class,()->decrypt(Arrays.copyOf(encrypted,encrypted.length-20),PASSWORD));
        assertThrows(IOException.class,()->decrypt(Arrays.copyOf(encrypted,encrypted.length+1),PASSWORD));
        byte[] hugeChunk = encrypted.clone();Arrays.fill(hugeChunk,40,44,(byte)0x7f);
        assertThrows(IOException.class,()->decrypt(hugeChunk,PASSWORD));
    }
    @Test public void reorderingIndividuallyValidChunksCannotAuthenticate() throws Exception {
        byte[] encrypted = encrypt(new byte[2*1024*1024]);
        int first=40, record=4+1024*1024+16;
        byte[] swapped=encrypted.clone();System.arraycopy(encrypted,first+record,swapped,first,record);
        System.arraycopy(encrypted,first,swapped,first+record,record);
        assertThrows(GeneralSecurityException.class,()->decrypt(swapped,PASSWORD));
    }
}
