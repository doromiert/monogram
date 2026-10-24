// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.jivesoftware.smack.packet.StandardExtensionElement;

/** Public OMEMO2 bundle. No private records or legacy identities enter the PEP payload. */
public final class Omemo2Bundle {
    public static final String NS = "urn:xmpp:omemo:2";
    public final long signedId;
    private final byte[] identity, signedKey, signature;
    private final TreeMap<Long,byte[]> preKeys = new TreeMap<>();
    Omemo2Bundle(byte[] identity, long signedId, byte[] signedKey, byte[] signature, Map<? extends Number,byte[]> keys) throws Exception {
        if (identity.length != 32 || signedId < 0 || signedId > 0xffffffffL || signedKey.length != 32 || signature.length != 64 || keys.isEmpty() || keys.size() > 1000) throw invalid();
        if (!org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyFull(identity,0)) throw invalid();
        point(signedKey);
        Ed25519Signer verifier = new Ed25519Signer(); verifier.init(false,new Ed25519PublicKeyParameters(identity,0));
        verifier.update(signedKey,0,signedKey.length);
        if (!verifier.verifySignature(signature)) throw invalid();
        this.identity = identity.clone(); this.signedId = signedId; this.signedKey = signedKey.clone(); this.signature = signature.clone();
        for (Map.Entry<? extends Number,byte[]> key : keys.entrySet()) {
            long id=key.getKey().longValue();
            if (id<0 || id>0xffffffffL || key.getValue().length!=32) throw invalid();
            point(key.getValue());
            if(preKeys.put(id,key.getValue().clone())!=null)throw invalid();
        }
    }
    private static void point(byte[] key) throws GeneralSecurityException {
        byte[] scalar = new byte[32], agreement = new byte[32]; scalar[0] = 1;
        if (!org.bouncycastle.math.ec.rfc7748.X25519.calculateAgreement(scalar,0,key,0,agreement,0)) throw invalid();
    }
    public byte[] identity() { return identity.clone(); }
    byte[] signedKey() { return signedKey.clone(); }
    byte[] signature() { return signature.clone(); }
    long[] preKeys() { return preKeys.keySet().stream().mapToLong(Long::longValue).toArray(); }
    byte[] preKey(long id) { return preKeys.get(id).clone(); }
    public StandardExtensionElement xml() {
        StandardExtensionElement.Builder keys = StandardExtensionElement.builder("prekeys",NS);
        for (Map.Entry<Long,byte[]> key : preKeys.entrySet()) keys.addElement(StandardExtensionElement.builder("pk",NS)
                .addAttribute("id",Long.toString(key.getKey())).setText(Base64.getEncoder().encodeToString(key.getValue())).build());
        return StandardExtensionElement.builder("bundle",NS)
                .addElement(StandardExtensionElement.builder("spk",NS).addAttribute("id",Long.toString(signedId)).setText(Base64.getEncoder().encodeToString(signedKey)).build())
                .addElement("spks",Base64.getEncoder().encodeToString(signature)).addElement("ik",Base64.getEncoder().encodeToString(identity)).addElement(keys.build()).build();
    }
    public static Omemo2Bundle parse(StandardExtensionElement root) throws Exception {
        if (!"bundle".equals(root.getElementName()) || !NS.equals(root.getNamespace()) || root.toXML().length() > 256*1024) throw invalid();
        StandardExtensionElement signed = single(root,"spk",NS), keys = single(root,"prekeys",NS);
        TreeMap<Long,byte[]> preKeys = new TreeMap<>();
        int count=0;
        for (StandardExtensionElement key : keys.getElements()) {
            if (!"pk".equals(key.getElementName()) || !NS.equals(key.getNamespace())) continue;
            if(++count>1000) throw invalid();
            final long id;
            try { id=unsignedKeyId(key.getAttributeValue("id"),"prekey"); }
            catch(GeneralSecurityException unusableId) { continue; }
            // A key with an unusable ID cannot participate in X3DH. Keep valid keys from the same bundle.
            if(preKeys.put(id,base64(key.getText(),32))!=null) throw invalid();
        }
        return new Omemo2Bundle(base64(single(root,"ik",NS).getText(),32),unsignedKeyId(signed.getAttributeValue("id"),"signed-prekey"),
                base64(signed.getText(),32),base64(single(root,"spks",NS).getText(),64),preKeys);
    }
    static final class InvalidKeyId extends GeneralSecurityException {
        private InvalidKeyId(String kind,String value) {
            super("Invalid OMEMO2 "+kind+" ID: "+(value==null ? "missing" : value.matches("[0-9]{1,10}") ? value : "malformed")+" (expected 0..4294967295)");
        }
    }
    private static long unsignedKeyId(String value,String kind)throws GeneralSecurityException {
        if(value==null || !value.matches("[0-9]{1,10}"))throw new InvalidKeyId(kind,value);
        long id=Long.parseLong(value);
        if(id>0xffffffffL)throw new InvalidKeyId(kind,value);
        return id;
    }
    static int positive(String value) throws GeneralSecurityException {
        try { if (value == null || !value.matches("[0-9]{1,10}")) throw invalid(); int id = Integer.parseInt(value); if (id <= 0) throw invalid(); return id; }
        catch (NumberFormatException error) { throw invalid(); }
    }
    static StandardExtensionElement single(StandardExtensionElement root, String name, String ns) throws GeneralSecurityException {
        java.util.List<StandardExtensionElement> elements = root.getElements(name,ns);
        if (elements == null || elements.size() != 1) throw invalid();
        return elements.get(0);
    }
    static byte[] base64(String text, int exact) throws GeneralSecurityException {
        try {
            if (text == null || text.length() > Math.max(128,exact*2)) throw invalid();
            byte[] value = Base64.getDecoder().decode(text.replaceAll("[\\t\\n\\r ]",""));
            if (value.length != exact) throw invalid(); return value;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    static GeneralSecurityException invalid() { return new GeneralSecurityException("Invalid OMEMO2 content"); }
}
