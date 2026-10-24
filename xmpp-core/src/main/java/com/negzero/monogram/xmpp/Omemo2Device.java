// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jxmpp.jid.impl.JidCreate;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

/** Durable local key lifecycle and explicit protocol-specific trust. Network operations happen outside transactions. */
public final class Omemo2Device {
    public enum Trust { UNDECIDED, TRUSTED, REJECTED }
    static final long ROTATION = 14L*24*60*60*1000;
    public final int id;
    public final String jid;
    final Omemo2Records.Factory records;
    private final SecureRandom random = new SecureRandom();
    public Omemo2Device(String jid, int id, Omemo2Records.Factory records) throws Exception {
        if (id <= 0 || jid.indexOf('/') >= 0) throw new IllegalArgumentException("Bare account and positive device required");
        this.jid = JidCreate.entityBareFrom(jid).toString(); this.id = id; this.records = records;
    }
    public synchronized Omemo2Bundle prepare() throws Exception { return prepare(System.currentTimeMillis()); }
    synchronized Omemo2Bundle prepare(long now) throws Exception {
        if (now < 0) throw new IllegalArgumentException("Invalid key time");
        try (Omemo2Records tx = records.begin()) {
            byte[] identity = tx.load(IDENTITY,"",0), metadata = tx.load(META,"",0);
            try {
                long nextPre = 1, nextSigned = 1; int current = 0;
                if (identity == null) {
                    if (metadata != null || tx.load(IDENTITY,"",1) != null || tx.ids(PRE_KEY).length != 0 || tx.ids(SIGNED_PRE_KEY).length != 0
                            || tx.ids(PRE_PUBLIC).length != 0 || tx.ids(SIGNED_PUBLIC).length != 0) throw incomplete();
                    byte[][] created = Omemo2Native.identity();
                    try { tx.save(IDENTITY,"",0,created[0]); tx.save(IDENTITY,"",1,created[1]); identity = created[0].clone(); }
                    finally { Arrays.fill(created[0],(byte)0); }
                } else {
                    if (metadata == null || metadata.length != 24) throw incomplete();
                    ByteBuffer meta = ByteBuffer.wrap(metadata);
                    if (meta.getInt() != 1) throw incomplete();
                    nextPre = meta.getLong(); nextSigned = meta.getLong(); current = meta.getInt();
                    if (nextPre <= 0 || nextSigned <= 0 || current <= 0) throw incomplete();
                }
                byte[] signed = current == 0 ? null : tx.load(SIGNED_PUBLIC,"",current);
                if (current != 0 && (signed == null || signed.length != 104 || tx.load(SIGNED_PRE_KEY,"",current) == null)) throw incomplete();
                if (signed == null || now - ByteBuffer.wrap(signed,96,8).getLong() >= ROTATION) {
                    if (nextSigned > Integer.MAX_VALUE) throw incomplete();
                    current = (int)nextSigned++; byte[][] created = Omemo2Native.signedPreKey(identity,current,now);
                    try {
                        tx.save(SIGNED_PRE_KEY,"",current,created[0]);
                        signed = ByteBuffer.allocate(104).put(created[1]).put(created[2]).putLong(now).array();
                        tx.save(SIGNED_PUBLIC,"",current,signed);
                    } finally { Arrays.fill(created[0],(byte)0); }
                }
                for (int old : tx.ids(SIGNED_PRE_KEY)) {
                    if (old == current) continue;
                    byte[] info = tx.load(SIGNED_PUBLIC,"",old); if (info == null || info.length != 104) throw incomplete();
                    if (now - ByteBuffer.wrap(info,96,8).getLong() >= 2*ROTATION) { tx.save(SIGNED_PRE_KEY,"",old,null); tx.save(SIGNED_PUBLIC,"",old,null); }
                }
                TreeMap<Integer,byte[]> keys = new TreeMap<>();
                for (int key : tx.ids(PRE_KEY)) {
                    byte[] pub = tx.load(PRE_PUBLIC,"",key); if (pub == null || pub.length != 32) throw incomplete(); keys.put(key,pub);
                }
                for (int key : tx.ids(PRE_PUBLIC)) if (!keys.containsKey(key)) tx.save(PRE_PUBLIC,"",key,null);
                if (keys.size() > 1000) throw incomplete();
                while (keys.size() < 100) {
                    // The native library reserves this legacy sentinel; never use it as a one-time key.
                    if (nextPre == 0xffffff) nextPre++;
                    if (nextPre > Integer.MAX_VALUE) throw incomplete();
                    int key = (int)nextPre++; byte[][] created = Omemo2Native.preKey(key);
                    try { tx.save(PRE_KEY,"",key,created[0]); tx.save(PRE_PUBLIC,"",key,created[1]); keys.put(key,created[1]); }
                    finally { Arrays.fill(created[0],(byte)0); }
                }
                byte[] pubIdentity = tx.load(IDENTITY,"",1); if (pubIdentity == null || pubIdentity.length != 32) throw incomplete();
                Omemo2Bundle result = new Omemo2Bundle(pubIdentity,current,Arrays.copyOfRange(signed,0,32),Arrays.copyOfRange(signed,32,96),keys);
                tx.save(META,"",0,ByteBuffer.allocate(24).putInt(1).putLong(nextPre).putLong(nextSigned).putInt(current).array());
                tx.commit(); return result;
            } finally { if (identity != null) Arrays.fill(identity,(byte)0); }
        }
    }
    public void observe(String peer, int device, Omemo2Bundle bundle) throws Exception {
        address(peer,device);
        try (Omemo2Records tx = records.begin()) { tx.save(SEEN,peer,device,bundle.identity()); tx.commit(); }
    }
    public byte[] seen(String peer, int device) throws Exception {
        address(peer,device); try (Omemo2Records tx = records.begin()) { return tx.load(SEEN,peer,device); }
    }
    public void decide(String peer, int device, byte[] key, boolean trusted) throws Exception {
        decideAll(java.util.Collections.singletonMap(peer,java.util.Collections.singletonMap(device,key)),trusted);
    }
    /** One transaction, scoped to the exact fingerprints displayed by the caller. */
    public void decideAll(Map<String,? extends Map<Integer,byte[]>> identities, boolean trusted) throws Exception {
        Map<String,Map<Integer,byte[]>> selected = new java.util.LinkedHashMap<>(); int count=0;
        for (Map.Entry<String,? extends Map<Integer,byte[]>> peer : identities.entrySet()) {
            Map<Integer,byte[]> devices = new java.util.LinkedHashMap<>();
            for (Map.Entry<Integer,byte[]> key : peer.getValue().entrySet()) {
                address(peer.getKey(),key.getKey());
                if (++count>1000 || key.getValue()==null || key.getValue().length!=32) throw new IllegalArgumentException("Invalid identity selection");
                devices.put(key.getKey(),key.getValue().clone());
            }
            selected.put(peer.getKey(),devices);
        }
        try (Omemo2Records tx = records.begin()) {
            for (Map.Entry<String,Map<Integer,byte[]>> peer : selected.entrySet())
                for (Map.Entry<Integer,byte[]> key : peer.getValue().entrySet()) decide(tx,peer.getKey(),key.getKey(),key.getValue(),trusted);
            tx.commit();
        }
    }
    private void decide(Omemo2Records tx,String peer,int device,byte[] key,boolean trusted) throws Exception {
            byte[] refused = rejected(tx,peer,device);
            int at = index(refused,key);
            if (trusted) {
                if (!Arrays.equals(tx.load(SEEN,peer,device),key)) throw new GeneralSecurityException("OMEMO2 identity changed; refresh before approving");
                if (at >= 0) {
                    byte[] remaining = new byte[refused.length-32];
                    System.arraycopy(refused,0,remaining,0,at);
                    System.arraycopy(refused,at+32,remaining,at,refused.length-at-32);
                    tx.save(Omemo2Records.REJECTED,peer,device,remaining.length == 0 ? null : remaining);
                }
                tx.save(TRUSTED,peer,device,key);
            } else {
                if (at < 0) {
                    if (refused.length == 4096*32) throw new GeneralSecurityException("OMEMO2 refusal history is full");
                    byte[] updated = Arrays.copyOf(refused,refused.length+32);
                    System.arraycopy(key,0,updated,refused.length,32); tx.save(Omemo2Records.REJECTED,peer,device,updated);
                }
                if (Arrays.equals(tx.load(TRUSTED,peer,device),key)) tx.save(TRUSTED,peer,device,null);
            }
    }
    public Trust trust(String peer, int device, byte[] key) throws Exception {
        address(peer,device); if (key == null || key.length != 32) throw new IllegalArgumentException("Invalid identity");
        try (Omemo2Records tx = records.begin()) {
            if (index(rejected(tx,peer,device),key) >= 0) return Trust.REJECTED;
            return Arrays.equals(tx.load(TRUSTED,peer,device),key) ? Trust.TRUSTED : Trust.UNDECIDED;
        }
    }
    private static byte[] rejected(Omemo2Records tx, String peer, int device) throws Exception {
        byte[] value = tx.load(Omemo2Records.REJECTED,peer,device);
        if (value == null) return new byte[0];
        if (value.length%32 != 0 || value.length > 4096*32) throw incomplete();
        return value;
    }
    private static int index(byte[] values, byte[] key) {
        for (int offset = 0; offset < values.length; offset += 32) {
            boolean same = true;
            for (int i = 0; i < 32; i++) same &= values[offset+i] == key[i];
            if (same) return offset;
        }
        return -1;
    }
    void session(Omemo2Records tx, String peer, int device, Omemo2Bundle bundle) throws Exception {
        address(peer,device);
        if (!Arrays.equals(tx.load(TRUSTED,peer,device),bundle.identity())) throw new OmemoSendException(OmemoSendException.Reason.TRUST_REQUIRED);
        if (tx.load(SESSION,peer,device) != null) return;
        long[] keys = bundle.preKeys(); long key = keys[random.nextInt(keys.length)];
        Omemo2Native.build(tx,id,peer,device,key,bundle.preKey(key),bundle.signedId,bundle.signedKey(),bundle.signature(),bundle.identity());
    }
    void devices(String peer, Set<Integer> devices) throws Exception {
        peer = JidCreate.entityBareFrom(peer).toString(); if (devices.size() > 500) throw Omemo2Bundle.invalid();
        ByteBuffer bytes = ByteBuffer.allocate(devices.size()*4);
        for (int device : new java.util.TreeSet<>(devices)) { if (device <= 0) throw Omemo2Bundle.invalid(); bytes.putInt(device); }
        try (Omemo2Records tx = records.begin()) { tx.save(DEVICES,peer,0,bytes.array().length == 0 ? null : bytes.array()); tx.commit(); }
    }
    public Set<Integer> devices(String peer) throws Exception {
        peer = JidCreate.entityBareFrom(peer).toString();
        try (Omemo2Records tx = records.begin()) {
            byte[] bytes = tx.load(DEVICES,peer,0); Set<Integer> result = new HashSet<>();
            if (bytes != null) { if (bytes.length%4 != 0 || bytes.length > 2000) throw incomplete(); ByteBuffer ids = ByteBuffer.wrap(bytes); while (ids.hasRemaining()) { int id = ids.getInt(); if (id <= 0 || !result.add(id)) throw incomplete(); } }
            return result;
        }
    }
    static void address(String peer, int device) throws Exception {
        if (device <= 0 || peer == null || !JidCreate.entityBareFrom(peer).toString().equals(peer)) throw new IllegalArgumentException("Canonical bare peer/device required");
    }
    private static GeneralSecurityException incomplete() { return new GeneralSecurityException("Incomplete OMEMO2 state; existing identity was preserved"); }
}
