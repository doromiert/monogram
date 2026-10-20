// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import java.security.GeneralSecurityException;
import com.negzero.monogram.xmpp.IdentityTrust;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
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
    /** Can only be created after the exact incoming Signal identity passes current trust. */
    static final class TrustedPeerMismatch extends Exception {
        private TrustedPeerMismatch() { super("Trusted incoming identity is incompatible with this historical peer lineage"); }
    }
    /**
     * Candidate selection only. Never overwrite an old peer identity/session to make a new
     * fingerprint fit. The caller holds archive records then the current trust monitor.
     */
    static void checkCandidate(MonogramSignalStore archive,OmemoDevice own,OmemoDevice sender,OmemoKeyElement key,IdentityTrust trust) throws Exception {
        if(!Thread.holdsLock(trust)) throw new IllegalStateException("Pin current trust while selecting historical receive state");
        if(!key.isPreKey()) return; // Existing-session packets authenticate their identity after MAC verification.
        PreKeySignalMessage packet=new PreKeySignalMessage(key.getData());
        org.jivesoftware.smackx.omemo.trust.OmemoFingerprint incoming=new SignalOmemoKeyUtil().getFingerprintOfIdentityKey(packet.getIdentityKey());
        if(!trust.isTrusted(sender,incoming)) throw new GeneralSecurityException("Incoming recovery identity is not currently trusted");
        IdentityKey previous=archive.loadOmemoIdentityKey(own,sender);
        if(previous!=null && !previous.equals(packet.getIdentityKey()) && !matchesExistingState(archive.loadRawSession(own,sender),packet))
            throw new TrustedPeerMismatch();
    }
    private static boolean matchesExistingState(SessionRecord record,PreKeySignalMessage packet) {
        if(record==null) return false;
        List<SessionState> states=new ArrayList<>();states.add(record.getSessionState());states.addAll(record.getPreviousSessionStates());
        for(SessionState state:states) if(packet.getIdentityKey().equals(state.getRemoteIdentityKey())
                && new SessionRecord(state).hasSessionState(packet.getMessageVersion(),packet.getBaseKey().serialize())) return true;
        return false;
    }
    static final class Authenticated {
        final byte[] material;
        final org.jivesoftware.smackx.omemo.trust.OmemoFingerprint fingerprint;
        Authenticated(byte[] material,org.jivesoftware.smackx.omemo.trust.OmemoFingerprint fingerprint) { this.material=material;this.fingerprint=fingerprint; }
    }
    /** Fixture/strict pinned-identity path; production additionally supplies current account trust. */
    static byte[] decrypt(MonogramSignalStore archive,OmemoDevice own,OmemoDevice sender,OmemoKeyElement key) throws Exception {
        return decryptAuthenticated(archive,own,sender,key,null).material;
    }
    /** Caller holds the archive's outer records transaction; every write rolls back on payload/journal failure. */
    static Authenticated decryptAuthenticated(MonogramSignalStore archive,OmemoDevice own,OmemoDevice sender,OmemoKeyElement key,IdentityTrust trust) throws Exception {
        if(trust!=null && !Thread.holdsLock(trust)) throw new IllegalStateException("Pin current trust while decrypting historical state");
        IdentityKeyPair identity=archive.loadOmemoIdentityKeyPair(own);
        if(identity==null) throw new GeneralSecurityException("Historical local identity is unavailable");
        // The installed Signal store/cipher performs its complete normal receive algorithm in
        // detached memory. Only successful receive mutations are copied to the archive transaction.
        SignalProtocolAddress address=new SignalProtocolAddress(sender.getJid().toString(),sender.getDeviceId());
        SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();
        InMemorySignalProtocolStore detached=new InMemorySignalProtocolStore(identity,own.getDeviceId()) {
            @Override public boolean isTrustedIdentity(SignalProtocolAddress remote,IdentityKey identity,IdentityKeyStore.Direction direction) {
                if(trust==null) return super.isTrustedIdentity(remote,identity,direction);
                try { return address.equals(remote) && trust.isTrusted(sender,keys.getFingerprintOfIdentityKey(identity)); }
                catch(Exception invalid) { throw new IllegalStateException("Could not validate historical Signal identity",invalid); }
            }
        };
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
        if(packet!=null) {
            if(peer!=null && !peer.equals(packet.getIdentityKey()) && (trust==null || !matchesExistingState(previous,packet)))
                throw new GeneralSecurityException("Historical peer identity changed");
            clear=cipher.decrypt(packet);
        } else clear=cipher.decrypt(new SignalMessage(key.getData()));
        try {
            SessionRecord received=detached.loadSession(address);
            IdentityKey authenticated=received.getSessionState().getRemoteIdentityKey();
            if(authenticated==null || packet!=null && !authenticated.equals(packet.getIdentityKey()))
                throw new GeneralSecurityException("Historical decrypted session identity does not match its packet");
            org.jivesoftware.smackx.omemo.trust.OmemoFingerprint fingerprint=keys.getFingerprintOfIdentityKey(authenticated);
            if(trust!=null && !trust.isTrusted(sender,fingerprint)) throw new GeneralSecurityException("Historical session identity is no longer trusted");
            archive.storeRawSession(own,sender,received);
            // A previous receive state may be promoted inside this archive. Keep the current
            // peer pin intact and label plaintext using the ACTUAL MAC-authenticated state.
            if(peer==null) archive.storeOmemoIdentityKey(own,sender,authenticated);
            if(pre!=null && !detached.containsPreKey(preId)) archive.removeOmemoPreKey(own,preId);
            return new Authenticated(clear,fingerprint);
        } catch(Exception failure) { Arrays.fill(clear,(byte)0);throw failure; }
    }
}
