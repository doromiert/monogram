// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.*;

/** Fork callbacks share the caller's database transaction. No native callbacks commit independently. */
final class PqOmemoSignalStore implements SignalProtocolStore {
    final PqOmemoRecords tx;
    private final IdentityKeyPair identity;
    private final int registration;

    PqOmemoSignalStore(PqOmemoRecords tx, IdentityKeyPair identity, int registration) {
        this.tx = tx; this.identity = identity; this.registration = registration;
    }
    static String peer(SignalProtocolAddress address) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(address.getName().getBytes(StandardCharsets.UTF_8))
                + "/" + Integer.toUnsignedString(address.getDeviceId());
    }
    private String session(SignalProtocolAddress address) { return "session/" + peer(address); }
    static String trust(SignalProtocolAddress address) { return "trust/" + peer(address); }
    static String observed(SignalProtocolAddress address) { return "observed/" + peer(address); }
    static byte[] hybrid(IdentityKey key, byte[] pq) {
        byte[] classic = key.serialize();
        if (classic.length != 33 || pq.length != 2592) throw new IllegalArgumentException("Invalid hybrid identity");
        byte[] result = Arrays.copyOf(classic,33 + pq.length);
        System.arraycopy(pq,0,result,33,pq.length); return result;
    }

    /** Exact hybrid pin required before libsignal can accept either a session or a new identity. */
    void requireTrusted(SignalProtocolAddress address, IdentityKey classic, byte[] pq) throws UntrustedIdentityException {
        byte[] expected = hybrid(classic,pq), saved = tx.get(trust(address));
        if (saved == null || !MessageDigest.isEqual(saved,expected)) throw new UntrustedIdentityException(address.getName(),classic);
    }
    @Override public IdentityKeyPair getIdentityKeyPair() { return identity; }
    @Override public int getLocalRegistrationId() { return registration; }
    @Override public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey key, IdentityKeyStore.Direction direction) {
        byte[] trusted = tx.get(trust(address));
        return trusted != null && trusted.length == 2625 && MessageDigest.isEqual(Arrays.copyOf(trusted,33),key.serialize());
    }
    @Override public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey key) {
        if (!isTrustedIdentity(address,key,IdentityKeyStore.Direction.RECEIVING)) throw new IllegalStateException("Untrusted PQ identity callback");
        byte[] previous = tx.get("identity/" + peer(address)), current = key.serialize();
        tx.put("identity/" + peer(address),current);
        return previous != null && !Arrays.equals(previous,current) ? IdentityChange.REPLACED_EXISTING : IdentityChange.NEW_OR_UNCHANGED;
    }
    @Override public IdentityKey getIdentity(SignalProtocolAddress address) {
        byte[] bytes = tx.get("identity/" + peer(address));
        try { return bytes == null ? null : new IdentityKey(bytes); }
        catch (InvalidKeyException error) { throw corrupt(error); }
    }
    @Override public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {
        byte[] bytes = tx.get("prekey/" + Integer.toUnsignedString(id));
        if (bytes == null) throw new InvalidKeyIdException("PQ prekey unavailable");
        try { return new PreKeyRecord(bytes); } catch (InvalidMessageException error) { throw corrupt(error); }
    }
    @Override public void storePreKey(int id, PreKeyRecord record) { tx.put("prekey/" + Integer.toUnsignedString(id),record.serialize()); }
    @Override public boolean containsPreKey(int id) { return tx.get("prekey/" + Integer.toUnsignedString(id)) != null; }
    @Override public void removePreKey(int id) { tx.put("prekey/" + Integer.toUnsignedString(id),null); }
    @Override public SessionRecord loadSession(SignalProtocolAddress address) {
        byte[] bytes = tx.get(session(address));
        try { return bytes == null ? new SessionRecord() : new SessionRecord(bytes); }
        catch (InvalidMessageException error) { throw corrupt(error); }
    }
    @Override public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
        List<SessionRecord> result = new ArrayList<>();
        for (SignalProtocolAddress address : addresses) {
            if (!containsSession(address)) throw new NoSessionException("PQ session unavailable");
            result.add(loadSession(address));
        }
        return result;
    }
    @Override public List<Integer> getSubDeviceSessions(String name) {
        String prefix = "session/" + Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "/";
        List<Integer> result = new ArrayList<>();
        for (String key : tx.keys(prefix)) result.add(Integer.parseUnsignedInt(key.substring(prefix.length())));
        return result;
    }
    @Override public void storeSession(SignalProtocolAddress address, SessionRecord record) { tx.put(session(address),record.serialize()); }
    @Override public boolean containsSession(SignalProtocolAddress address) { return tx.get(session(address)) != null; }
    @Override public void deleteSession(SignalProtocolAddress address) { tx.put(session(address),null); }
    @Override public void deleteAllSessions(String name) {
        for (int id : getSubDeviceSessions(name)) deleteSession(new SignalProtocolAddress(name,id));
    }
    @Override public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {
        byte[] bytes = tx.get("signed/" + Integer.toUnsignedString(id));
        if (bytes == null) throw new InvalidKeyIdException("PQ signed prekey unavailable");
        try { return new SignedPreKeyRecord(bytes); } catch (InvalidMessageException error) { throw corrupt(error); }
    }
    @Override public List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> result = new ArrayList<>();
        for (String key : tx.keys("signed/")) try { result.add(loadSignedPreKey(Integer.parseUnsignedInt(key.substring(7)))); }
        catch (InvalidKeyIdException error) { throw corrupt(error); }
        return result;
    }
    @Override public void storeSignedPreKey(int id, SignedPreKeyRecord record) { tx.put("signed/" + Integer.toUnsignedString(id),record.serialize()); }
    @Override public boolean containsSignedPreKey(int id) { return tx.get("signed/" + Integer.toUnsignedString(id)) != null; }
    @Override public void removeSignedPreKey(int id) { tx.put("signed/" + Integer.toUnsignedString(id),null); }
    @Override public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {
        byte[] bytes = tx.get("kem/" + Integer.toUnsignedString(id));
        if (bytes == null) throw new InvalidKeyIdException("PQ KEM prekey unavailable");
        try { return new KyberPreKeyRecord(bytes); } catch (InvalidMessageException error) { throw corrupt(error); }
    }
    @Override public List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> result = new ArrayList<>();
        for (String key : tx.keys("kem/")) try { result.add(loadKyberPreKey(Integer.parseUnsignedInt(key.substring(4)))); }
        catch (InvalidKeyIdException error) { throw corrupt(error); }
        return result;
    }
    @Override public void storeKyberPreKey(int id, KyberPreKeyRecord record) { tx.put("kem/" + Integer.toUnsignedString(id),record.serialize()); }
    @Override public boolean containsKyberPreKey(int id) { return tx.get("kem/" + Integer.toUnsignedString(id)) != null; }
    @Override public void markKyberPreKeyUsed(int id, int signedId, ECPublicKey baseKey) throws ReusedBaseKeyException {
        if (tx.get("last-resort/" + Integer.toUnsignedString(id)) == null) {
            tx.put("kem/" + Integer.toUnsignedString(id),null);
            return;
        }
        String replay = "replay/" + Integer.toUnsignedString(id) + "/" + Integer.toUnsignedString(signedId)
                + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(baseKey.serialize());
        if (tx.get(replay) != null) throw new ReusedBaseKeyException();
        tx.put(replay,new byte[]{1});
    }
    // PQ OMEMO uses per-device ratchets, not Signal sender-key groups.
    @Override public void storeSenderKey(SignalProtocolAddress sender, UUID distribution, SenderKeyRecord record) { throw new UnsupportedOperationException("PQ sender keys are not supported"); }
    @Override public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distribution) { throw new UnsupportedOperationException("PQ sender keys are not supported"); }
    private static IllegalStateException corrupt(Exception error) { return new IllegalStateException("Corrupt PQ state; existing keys preserved",error); }
}
