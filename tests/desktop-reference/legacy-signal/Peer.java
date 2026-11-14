// Cross-check against the exact upstream legacy libraries used by Monogram mobile.
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jivesoftware.smackx.omemo.internal.OmemoAesCipher;
import org.jivesoftware.smackx.omemo.util.OmemoMessageBuilder;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;

public class Peer {
    static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    static byte[] decode(String bytes) { return Base64.getDecoder().decode(bytes); }

    public static void main(String[] args) throws Exception {
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        var identity = KeyHelper.generateIdentityKeyPair();
        var pre = KeyHelper.generatePreKeys(1, 1).get(0);
        var signed = KeyHelper.generateSignedPreKey(identity, 1);
        var store = new InMemorySignalProtocolStore(identity, 202);
        store.storePreKey(pre.getId(), pre);
        store.storeSignedPreKey(signed.getId(), signed);
        System.out.println(String.join(" ", b64(identity.getPublicKey().serialize()),
            b64(pre.getKeyPair().getPublicKey().serialize()),
            b64(signed.getKeyPair().getPublicKey().serialize()), b64(signed.getSignature())));
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String[] incoming = input.readLine().split(" ");
            var cipher = new SessionCipher(store, new SignalProtocolAddress("alice@example.invalid", 101));
            byte[] material = cipher.decrypt(new PreKeySignalMessage(decode(incoming[0])));
            byte[] content = decode(incoming[2]);
            byte[] sealed = Arrays.copyOf(content, content.length + 16);
            System.arraycopy(material, 16, sealed, content.length, 16);
            byte[] plain = OmemoAesCipher.decryptAesGcmNoPadding(sealed, Arrays.copyOf(material, 16), decode(incoming[1]));
            if (!"From Rust 🌿".equals(new String(plain, StandardCharsets.UTF_8))) {
                throw new AssertionError("Java failed to decrypt the Rust packet");
            }
            byte[] iv = OmemoMessageBuilder.generateIv();
            byte[] key = OmemoMessageBuilder.generateKey("AES", 128);
            byte[] outgoing = OmemoAesCipher.encryptAesGcmNoPadding("From Smack 🌙", key, iv);
            byte[] wrapped = Arrays.copyOf(key, 32);
            System.arraycopy(outgoing, outgoing.length - 16, wrapped, 16, 16);
            CiphertextMessage encrypted = cipher.encrypt(wrapped);
            System.out.println(String.join(" ", Boolean.toString(encrypted.getType() == CiphertextMessage.PREKEY_TYPE),
                b64(encrypted.serialize()), b64(iv), b64(Arrays.copyOf(outgoing, outgoing.length - 16))));
        }
    }
}
