// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import java.security.GeneralSecurityException;
import java.util.*;
import org.jivesoftware.smackx.omemo.element.OmemoKeyElement;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.protocol.*;
import org.whispersystems.libsignal.state.*;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;

/** Fixed historical own-device receive cipher. Never creates an OmemoManager or outgoing ciphertext. */
final class MonogramLegacyArchiveCipher {
    private MonogramLegacyArchiveCipher() { }
    /** Caller holds the archive's outer records transaction; every write rolls back on payload/journal failure. */
    static byte[] decrypt(MonogramSignalStore archive,OmemoDevice own,OmemoDevice sender,OmemoKeyElement key) throws Exception {
        IdentityKeyPair identity=archive.loadOmemoIdentityKeyPair(own);
        if(identity==null) throw new GeneralSecurityException("Historical local identity is unavailable");
        // The installed Signal store/cipher performs its complete normal receive algorithm in
        // detached memory. Only successful receive mutations are copied to the archive transaction.
        InMemorySignalProtocolStore detached=new InMemorySignalProtocolStore(identity,own.getDeviceId());
        SignalProtocolAddress address=new SignalProtocolAddress(sender.getJid().toString(),sender.getDeviceId());
        IdentityKey peer=archive.loadOmemoIdentityKey(own,sender);if(peer!=null) detached.saveIdentity(address,peer);
        SessionRecord previous=archive.loadRawSession(own,sender);if(previous!=null) detached.storeSession(address,previous);
        PreKeySignalMessage packet=key.isPreKey()?new PreKeySignalMessage(key.getData()):null;
        Integer preId=packet!=null && packet.getPreKeyId().isPresent()?packet.getPreKeyId().get():null;
        PreKeyRecord pre=null;
        if(packet!=null) {
            if(preId==null) throw new GeneralSecurityException("Historical prekey message has no one-time key ID");
            pre=archive.loadOmemoPreKey(own,preId);if(pre!=null) detached.storePreKey(preId,pre);
            SignedPreKeyRecord signed=archive.loadOmemoSignedPreKey(own,packet.getSignedPreKeyId());
            if(signed!=null) detached.storeSignedPreKey(packet.getSignedPreKeyId(),signed);
        }
        SessionCipher cipher=new SessionCipher(detached,address);byte[] clear;
        IdentityKey receivedIdentity=peer;
        if(packet!=null) {
            receivedIdentity=packet.getIdentityKey();
            if(peer!=null && !peer.equals(receivedIdentity)) throw new GeneralSecurityException("Historical peer identity changed");
            clear=cipher.decrypt(packet);
        } else clear=cipher.decrypt(new SignalMessage(key.getData()));
        try {
            archive.storeRawSession(own,sender,detached.loadSession(address));
            if(receivedIdentity!=null) archive.storeOmemoIdentityKey(own,sender,receivedIdentity);
            if(pre!=null && !detached.containsPreKey(preId)) archive.removeOmemoPreKey(own,preId);
            return clear;
        } catch(Exception failure) { Arrays.fill(clear,(byte)0);throw failure; }
    }
}
