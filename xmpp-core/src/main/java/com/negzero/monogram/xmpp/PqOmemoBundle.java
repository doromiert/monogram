// SPDX-License-Identifier: AGPL-3.0-only
// Monocles PQ profile adapter; upstream attribution in docs/pq-omemo.md.
package com.negzero.monogram.xmpp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.pqid.PqBundle;
import org.signal.libsignal.protocol.pqid.PqIdentityKeyPair;
import org.signal.libsignal.protocol.state.*;

/** An authenticated v4 hybrid bundle. Constructor/parser verify before exposing a usable bundle. */
public final class PqOmemoBundle {
    public static final String NS = PqOmemoPayload.NS;
    private static final int MAX_KEYS = 100;
    final IdentityKey identity;
    final byte[] pqIdentity, pqSignature, classicSignature, kemBinding;
    private final StandardExtensionElement xml;
    private final int signedId, lastResortId;
    private final ECPublicKey signed;
    private final byte[] signedSignature;
    private final LinkedHashMap<Integer,ECPublicKey> prekeys = new LinkedHashMap<>();
    private final LinkedHashMap<Integer,Kem> kemKeys = new LinkedHashMap<>();
    private final Kem lastResort;
    private static final class Kem {
        final KEMPublicKey key; final byte[] signature;
        Kem(byte[] serialized, byte[] signature) throws Exception {
            if (serialized.length != 1569 || serialized[0] != 10 || signature.length != 64) throw invalid();
            key = new KEMPublicKey(serialized); this.signature = signature;
        }
    }

    private PqOmemoBundle(StandardExtensionElement root) throws Exception {
        if (root == null || !"bundle".equals(root.getElementName()) || !NS.equals(root.getNamespace())
                || root.toXML().length() > 512 * 1024) throw invalid();
        xml = root;
        // 'rev' is an unsigned diagnostic hint, never the authentication decision.
        identity = new IdentityKey(ec(data(one(root,"ik"),32)));
        StandardExtensionElement spk = one(root,"spk"); signedId = id(spk);
        signed = ec(data(spk,32)); signedSignature = data(one(root,"spks"),64);
        if (!identity.getPublicKey().verifySignature(signed.serialize(),signedSignature)) throw invalid();
        StandardExtensionElement pq = one(root,"pq-ik");
        if (!"ML-DSA-87".equals(pq.getAttributeValue("type"))) throw invalid();
        pqIdentity = data(pq,2592); pqSignature = data(one(root,"pq-sig"),4627);
        classicSignature = data(one(root,"ik-sig"),64);
        for (StandardExtensionElement key : one(root,"prekeys").getElements()) {
            if (!"pk".equals(key.getElementName()) || !NS.equals(key.getNamespace())) throw invalid();
            if (id(key) == -1 || prekeys.size() >= MAX_KEYS || prekeys.put(id(key),ec(data(key,32))) != null) throw invalid();
        }
        if (prekeys.isEmpty()) throw invalid(); // Do not weaken PQXDH by omitting the EC one-time key.
        StandardExtensionElement kem = one(root,"kem-spk"); lastResortId = id(kem);
        lastResort = new Kem(data(kem,1569),data(one(root,"kem-spks"),64));
        if (!identity.getPublicKey().verifySignature(lastResort.key.serialize(),lastResort.signature)) throw invalid();
        StandardExtensionElement keys = optional(root,"kem-prekeys");
        List<PqBundle.KemOneTimeKey> manifest = new ArrayList<>();
        if (keys != null) for (StandardExtensionElement key : keys.getElements()) {
            if (!"kem-pk".equals(key.getElementName()) || !NS.equals(key.getNamespace())) throw invalid();
            int id = id(key); byte[] bytes = data(key,1569);
            Kem entry = new Kem(bytes,decode(key.getAttributeValue("sig"),64));
            if (id == lastResortId || kemKeys.size() >= MAX_KEYS || kemKeys.put(id,entry) != null
                    || !identity.getPublicKey().verifySignature(bytes,entry.signature)) throw invalid();
            manifest.add(new PqBundle.KemOneTimeKey(id,bytes));
        }
        kemBinding = PqBundle.kemBinding(lastResortId,lastResort.key.serialize(),manifest);
        if (!PqBundle.verify(identity,pqIdentity,pqSignature,classicSignature,signedId,signed,kemBinding)) throw invalid();
    }

    public static PqOmemoBundle parse(StandardExtensionElement root) throws Exception { return new PqOmemoBundle(root); }
    public StandardExtensionElement xml() { return xml; }
    public byte[] identity() { return identity.serialize(); }
    public byte[] pqIdentity() { return pqIdentity.clone(); }
    public String fingerprint() { return fingerprint(identity.serialize(),pqIdentity); }
    static String fingerprint(byte[] classic, byte[] pq) {
        SHA3Digest digest = new SHA3Digest(512);
        byte[] label = "monocles:omemo2:ik:v2".getBytes(StandardCharsets.US_ASCII);
        digest.update(label,0,label.length);
        for (byte[] key : new byte[][]{classic,pq}) {
            byte[] length = ByteBuffer.allocate(4).putInt(key.length).array();
            digest.update(length,0,length.length); digest.update(key,0,key.length);
        }
        byte[] output = new byte[64]; digest.doFinal(output,0);
        return XmppSession.hex(output);
    }

    PreKeyBundle sessionBundle(int device, SecureRandom random) throws Exception {
        List<Integer> ecIds = new ArrayList<>(prekeys.keySet()); int ecId = ecIds.get(random.nextInt(ecIds.size()));
        List<Integer> kemIds = new ArrayList<>(kemKeys.keySet());
        int kemId = kemIds.isEmpty() ? lastResortId : kemIds.get(random.nextInt(kemIds.size()));
        Kem kem = kemIds.isEmpty() ? lastResort : kemKeys.get(kemId);
        return new PreKeyBundle(0,device,ecId,prekeys.get(ecId),signedId,signed,signedSignature,identity,
                kemId,kem.key,kem.signature).withPqIdentity(pqIdentity,pqSignature,classicSignature,kemBinding);
    }

    static PqOmemoBundle create(IdentityKeyPair identity, PqIdentityKeyPair pq, SignedPreKeyRecord signed,
            List<PreKeyRecord> prekeys, KyberPreKeyRecord last, List<KyberPreKeyRecord> kemKeys) throws Exception {
        StandardExtensionElement.Builder root = StandardExtensionElement.builder("bundle",NS).addAttribute("rev","4");
        root.addElement(key("spk",signed.getId(),signed.getKeyPair().getPublicKey().getPublicKeyBytes()))
                .addElement("spks",b64(signed.getSignature())).addElement("ik",b64(identity.getPublicKey().getPublicKey().getPublicKeyBytes()));
        StandardExtensionElement.Builder ec = StandardExtensionElement.builder("prekeys",NS);
        for (PreKeyRecord key : prekeys) ec.addElement(key("pk",key.getId(),key.getKeyPair().getPublicKey().getPublicKeyBytes()));
        root.addElement(ec.build()).addElement(key("kem-spk",last.getId(),last.getKeyPair().getPublicKey().serialize()))
                .addElement("kem-spks",b64(last.getSignature()));
        StandardExtensionElement.Builder kem = StandardExtensionElement.builder("kem-prekeys",NS);
        List<PqBundle.KemOneTimeKey> manifest = new ArrayList<>();
        for (KyberPreKeyRecord key : kemKeys) {
            byte[] pub = key.getKeyPair().getPublicKey().serialize();
            kem.addElement(StandardExtensionElement.builder("kem-pk",NS).addAttribute("id",Integer.toUnsignedString(key.getId()))
                    .addAttribute("sig",b64(key.getSignature())).setText(b64(pub)).build());
            manifest.add(new PqBundle.KemOneTimeKey(key.getId(),pub));
        }
        byte[] pqPublic = pq.getPublicKey().serialize();
        byte[] transcript = PqBundle.transcript(identity.getPublicKey(),pqPublic,signed.getId(),signed.getKeyPair().getPublicKey(),
                PqBundle.kemBinding(last.getId(),last.getKeyPair().getPublicKey().serialize(),manifest));
        root.addElement(kem.build()).addElement(StandardExtensionElement.builder("pq-ik",NS).addAttribute("type","ML-DSA-87").setText(b64(pqPublic)).build())
                .addElement("pq-sig",b64(pq.sign(transcript))).addElement("ik-sig",b64(identity.getPrivateKey().calculateSignature(transcript)));
        return parse(root.build());
    }

    static StandardExtensionElement one(StandardExtensionElement root,String name) throws GeneralSecurityException {
        StandardExtensionElement value = optional(root,name); if (value == null) throw invalid(); return value;
    }
    static StandardExtensionElement optional(StandardExtensionElement root,String name) throws GeneralSecurityException {
        StandardExtensionElement value = null;
        for (StandardExtensionElement child : root.getElements()) if (name.equals(child.getElementName()) && NS.equals(child.getNamespace())) {
            if (value != null) throw invalid(); value = child;
        }
        return value;
    }
    static int id(StandardExtensionElement key) throws GeneralSecurityException {
        String value = key.getAttributeValue("id");
        try {
            if (value == null || !value.matches("[0-9]{1,10}")) throw invalid();
            return Integer.parseUnsignedInt(value);
        } catch (NumberFormatException error) { throw invalid(); }
    }
    static byte[] data(StandardExtensionElement node,int length) throws GeneralSecurityException { return decode(node.getText(),length); }
    static byte[] decode(String value,int length) throws GeneralSecurityException {
        if (value == null || value.length() > (length + 2) / 3 * 4 + 32) throw invalid();
        try { byte[] bytes = Base64.getDecoder().decode(value.replaceAll("[\\t\\r\\n ]", "")); if (bytes.length != length) throw invalid(); return bytes; }
        catch (IllegalArgumentException error) { throw invalid(); }
    }
    private static ECPublicKey ec(byte[] raw) throws Exception {
        byte[] scalar = new byte[32], agreement = new byte[32]; scalar[0] = 1;
        if (!org.bouncycastle.math.ec.rfc7748.X25519.calculateAgreement(scalar,0,raw,0,agreement,0)) throw invalid();
        return ECPublicKey.fromPublicKeyBytes(raw);
    }
    static String b64(byte[] data) { return Base64.getEncoder().encodeToString(data); }
    private static StandardExtensionElement key(String name,int id,byte[] pub) {
        return StandardExtensionElement.builder(name,NS).addAttribute("id",Integer.toUnsignedString(id)).setText(b64(pub)).build();
    }
    static GeneralSecurityException invalid() { return new GeneralSecurityException("Invalid experimental PQ OMEMO bundle"); }
}
