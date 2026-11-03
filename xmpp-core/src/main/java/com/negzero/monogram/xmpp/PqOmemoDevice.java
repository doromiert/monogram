// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;
import org.jxmpp.jid.impl.JidCreate;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.*;
import org.signal.libsignal.protocol.pqid.PqIdentityKeyPair;
import org.signal.libsignal.protocol.state.*;

/** A PQ identity and its transaction factory. Classical OMEMO state never enters this class. */
public final class PqOmemoDevice {
    public final String jid;
    public final int id;
    final PqOmemoRecords.Factory records;
    private final SecureRandom random = new SecureRandom();
    private static final long ROTATE = 7L * 24 * 60 * 60 * 1000;
    private static final long RETAIN = 90L * 24 * 60 * 60 * 1000;
    static final String IDENTITY = "local/identity", PQ = "local/pq-identity";

    public PqOmemoDevice(String jid, PqOmemoRecords.Factory records) throws Exception {
        this.jid = bare(jid); this.records = records;
        try (PqOmemoRecords tx = records.begin()) {
            byte[] identity = tx.get(IDENTITY), pq = tx.get(PQ), device = tx.get("local/device"), registration = tx.get("local/registration");
            if (identity == null && pq == null && device == null && registration == null && tx.keys("").isEmpty()) {
                IdentityKeyPair classic = IdentityKeyPair.generate();
                PqIdentityKeyPair postQuantum = PqIdentityKeyPair.generate();
                tx.put(IDENTITY,classic.serialize()); tx.put(PQ,postQuantum.serialize());
                device = integer(random.nextInt(Integer.MAX_VALUE) + 1);
                tx.put("local/device",device); tx.put("local/registration",integer(random.nextInt(16380) + 1));
                tx.put("local/next-id",integer(1));
            } else if (identity == null || pq == null || device == null || registration == null) {
                throw new GeneralSecurityException("Incomplete PQ identity; existing state preserved");
            }
            id = number(device);
            if (id <= 0) throw new GeneralSecurityException("Invalid PQ device ID");
            // Parse both halves now. Never regenerate a malformed identity on a connection failure.
            store(tx); pq(tx).getPublicKey();
            tx.commit();
        }
    }

    PqOmemoSignalStore store(PqOmemoRecords tx) throws Exception {
        return new PqOmemoSignalStore(tx,new IdentityKeyPair(required(tx,IDENTITY)),number(required(tx,"local/registration")));
    }
    PqIdentityKeyPair pq(PqOmemoRecords tx) throws Exception {
        return PqIdentityKeyPair.fromSerialized(required(tx,PQ));
    }
    SignalProtocolAddress address() { return new SignalProtocolAddress(jid,id); }

    public PqOmemoBundle prepare() throws Exception {
        try (PqOmemoRecords tx = records.begin()) {
            PqOmemoSignalStore store = store(tx);
            SignedPreKeyRecord signed = null; KyberPreKeyRecord last = null;
            byte[] active = tx.get("local/active-signed");
            if (active != null) signed = store.loadSignedPreKey(number(active));
            long now = System.currentTimeMillis();
            if (signed == null || now - signed.getTimestamp() >= ROTATE || signed.getTimestamp() > now + 600000) {
                ECKeyPair pair = ECKeyPair.generate(); int keyId = next(tx);
                signed = new SignedPreKeyRecord(keyId,now,pair,store.getIdentityKeyPair().getPrivateKey().calculateSignature(pair.getPublicKey().serialize()));
                store.storeSignedPreKey(keyId,signed); tx.put("local/active-signed",integer(keyId));
            }
            active = tx.get("local/active-kem");
            if (active != null) last = store.loadKyberPreKey(number(active));
            if (last == null || now - last.getTimestamp() >= ROTATE || last.getTimestamp() > now + 600000) {
                last = kem(store,next(tx),now); store.storeKyberPreKey(last.getId(),last);
                tx.put("last-resort/" + Integer.toUnsignedString(last.getId()),new byte[]{1});
                tx.put("local/active-kem",integer(last.getId()));
            }
            for (SignedPreKeyRecord old : store.loadSignedPreKeys())
                if (old.getId() != signed.getId() && now - old.getTimestamp() > RETAIN) store.removeSignedPreKey(old.getId());
            for (KyberPreKeyRecord old : store.loadKyberPreKeys()) if (old.getId() != last.getId() && now - old.getTimestamp() > RETAIN) {
                String keyId = Integer.toUnsignedString(old.getId());
                tx.put("kem/" + keyId,null); tx.put("last-resort/" + keyId,null);
                for (String replay : tx.keys("replay/" + keyId + "/")) tx.put(replay,null);
            }
            List<PreKeyRecord> ec = new ArrayList<>();
            for (String key : tx.keys("prekey/")) ec.add(store.loadPreKey(Integer.parseUnsignedInt(key.substring(7))));
            while (ec.size() < 32) {
                PreKeyRecord key = new PreKeyRecord(next(tx),ECKeyPair.generate());
                store.storePreKey(key.getId(),key); ec.add(key);
            }
            List<KyberPreKeyRecord> oneTime = new ArrayList<>();
            for (KyberPreKeyRecord key : store.loadKyberPreKeys())
                if (tx.get("last-resort/" + Integer.toUnsignedString(key.getId())) == null) oneTime.add(key);
            while (oneTime.size() < 32) {
                KyberPreKeyRecord key = kem(store,next(tx),now); store.storeKyberPreKey(key.getId(),key); oneTime.add(key);
            }
            PqOmemoBundle bundle = PqOmemoBundle.create(store.getIdentityKeyPair(),pq(tx),signed,ec,last,oneTime);
            tx.commit(); return bundle;
        }
    }
    private KyberPreKeyRecord kem(PqOmemoSignalStore store,int id,long now) {
        KEMKeyPair pair = KEMKeyPair.generate(KEMKeyType.MLKEM1024);
        return new KyberPreKeyRecord(id,now,pair,store.getIdentityKeyPair().getPrivateKey().calculateSignature(pair.getPublicKey().serialize()));
    }
    private int next(PqOmemoRecords tx) throws Exception {
        int next = number(required(tx,"local/next-id"));
        if (next <= 0 || next == Integer.MAX_VALUE) throw new GeneralSecurityException("PQ prekey identifiers exhausted");
        tx.put("local/next-id",integer(next + 1)); return next;
    }

    public static final class Fingerprint {
        public final int device;
        public final String fingerprint;
        public final boolean trusted, blocked, active;
        Fingerprint(int device, byte[] identity, boolean trusted, boolean blocked) {
            this.device = device; this.fingerprint = PqOmemoBundle.fingerprint(Arrays.copyOf(identity,33),Arrays.copyOfRange(identity,33,identity.length));
            this.trusted = trusted; this.blocked = blocked; this.active = true;
        }
        private Fingerprint(Fingerprint key,boolean active) {
            device = key.device; fingerprint = key.fingerprint; trusted = key.trusted; blocked = key.blocked; this.active = active;
        }
        Fingerprint withActive(boolean active) { return new Fingerprint(this,active); }
    }
    public void observe(String peer,int device,PqOmemoBundle bundle) throws Exception {
        observe(peer,device,bundle.identity,bundle.pqIdentity);
    }
    void observe(String peer,int device,IdentityKey classic,byte[] pq) throws Exception {
        SignalProtocolAddress address = remote(peer,device);
        try (PqOmemoRecords tx = records.begin()) {
            tx.put(PqOmemoSignalStore.observed(address),PqOmemoSignalStore.hybrid(classic,pq));
            tx.commit();
        }
    }
    public List<Fingerprint> fingerprints(String peer) throws Exception {
        String normalized = bare(peer);
        String prefix = "observed/" + Base64.getUrlEncoder().withoutPadding().encodeToString(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/";
        List<Fingerprint> result = new ArrayList<>();
        try (PqOmemoRecords tx = records.begin()) {
            for (String key : tx.keys(prefix)) {
                int id = Integer.parseUnsignedInt(key.substring(prefix.length())); SignalProtocolAddress address = remote(normalized,id);
                byte[] identity = tx.get(key), trusted = tx.get(PqOmemoSignalStore.trust(address));
                if (identity.length != 2625) throw new GeneralSecurityException("Corrupt PQ fingerprint record");
                result.add(new Fingerprint(id,identity,trusted != null && MessageDigest.isEqual(identity,trusted),tx.get("blocked/" + PqOmemoSignalStore.peer(address)) != null));
            }
        }
        result.sort(Comparator.comparingInt(f -> f.device)); return result;
    }
    /** The fingerprint displayed at approval time must still match; never trust a key replaced mid-dialog. */
    public void trust(String peer,int id,String fingerprint,boolean trusted) throws Exception {
        SignalProtocolAddress address = remote(peer,id);
        try (PqOmemoRecords tx = records.begin()) {
            byte[] identity = tx.get(PqOmemoSignalStore.observed(address));
            if (identity == null || identity.length != 2625 || !PqOmemoBundle.fingerprint(Arrays.copyOf(identity,33),Arrays.copyOfRange(identity,33,identity.length)).equals(fingerprint))
                throw new GeneralSecurityException("PQ fingerprint changed; refresh before approving");
            byte[] previous = tx.get(PqOmemoSignalStore.trust(address));
            tx.put(PqOmemoSignalStore.trust(address),trusted ? identity : null);
            tx.put("blocked/" + PqOmemoSignalStore.peer(address),trusted ? null : new byte[]{1});
            if (!trusted || !Arrays.equals(previous,identity)) {
                tx.put("session/" + PqOmemoSignalStore.peer(address),null);
                tx.put("identity/" + PqOmemoSignalStore.peer(address),null);
            }
            tx.commit();
        }
    }
    static SignalProtocolAddress remote(String jid,int device) throws Exception {
        if (device <= 0) throw new GeneralSecurityException("Invalid PQ device");
        return new SignalProtocolAddress(bare(jid),device);
    }
    static String bare(String jid) throws Exception {
        if (jid == null || jid.indexOf('/') >= 0 || jid.indexOf('\0') >= 0) throw new GeneralSecurityException("Bare account JID required");
        String normalized = JidCreate.entityBareFrom(jid).toString();
        if (!normalized.equals(jid)) throw new GeneralSecurityException("Noncanonical PQ JID"); return normalized;
    }
    static byte[] integer(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
    static int number(byte[] value) throws GeneralSecurityException {
        if (value == null || value.length != 4) throw new GeneralSecurityException("Corrupt PQ integer");
        return ByteBuffer.wrap(value).getInt();
    }
    static byte[] required(PqOmemoRecords tx,String key) throws GeneralSecurityException {
        byte[] value = tx.get(key); if (value == null) throw new GeneralSecurityException("Missing PQ identity state"); return value;
    }
}
