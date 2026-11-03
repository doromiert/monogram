// SPDX-License-Identifier: AGPL-3.0-only
// JVM interoperability fixture; never included in the APK.
package eu.siacs.conversations.crypto.axolotl;
import java.util.List;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.message.*;
/** Only the platform/session adapter is substituted; SessionCipher and both wire codecs are real. */
public final class XmppAxolotlSession {
 private final SessionCipher cipher; private final SignalProtocolAddress remote; private final String fingerprint;
 public XmppAxolotlSession(SessionCipher cipher,SignalProtocolAddress remote,String fingerprint){this.cipher=cipher;this.remote=remote;this.fingerprint=fingerprint;}
 public SignalProtocolAddress getRemoteAddress(){return remote;}
 public String getFingerprint(){return fingerprint;}
 public AxolotlKey processSending(byte[] message,boolean ignoreTrust){try{CiphertextMessage encrypted=cipher.encrypt(message);return new AxolotlKey(remote.getDeviceId(),encrypted.serialize(),encrypted instanceof PreKeySignalMessage);}catch(Exception e){throw new IllegalStateException(e);}}
 public byte[] processReceiving(List<AxolotlKey> keys) throws CryptoFailedException {for(AxolotlKey key:keys)try{return key.prekey?cipher.decrypt(new PreKeySignalMessage(key.key)):cipher.decrypt(new SignalMessage(key.key));}catch(Exception e){throw new CryptoFailedException(e);}return null;}
 public static List<AxolotlKey> capCandidates(List<AxolotlKey> keys){return keys.subList(0,Math.min(8,keys.size()));}
 public static final class AxolotlKey {public final int deviceId;public final byte[] key;public final boolean prekey;public AxolotlKey(int id,byte[] key,boolean prekey){deviceId=id;this.key=key;this.prekey=prekey;}}
}
