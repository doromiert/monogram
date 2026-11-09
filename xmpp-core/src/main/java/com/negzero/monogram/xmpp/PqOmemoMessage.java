// SPDX-License-Identifier: AGPL-3.0-only
// Monocles PQ profile adapter; upstream attribution in docs/pq-omemo.md.
package com.negzero.monogram.xmpp;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.message.*;
import org.signal.libsignal.protocol.pqid.*;
import static com.negzero.monogram.xmpp.PqOmemoBundle.*;

/** Hybrid-authenticated per-device key wrapping and committed payloads. All ratchet writes are atomic. */
final class PqOmemoMessage {
    private static final int MAX_TARGETS = 500, MAX_WRAPPED_BYTES = 65536, MAX_WIRE = 4 * 1024 * 1024;
    static final class Target {
        final String jid; final int device; final PqOmemoBundle bundle;
        Target(String jid,int device,PqOmemoBundle bundle) throws Exception {
            PqOmemoDevice.remote(jid,device); this.jid = jid; this.device = device; this.bundle = bundle;
        }
    }
    private static final class Wrapped {
        final String jid; final int id; final boolean kex; final byte[] data;
        Wrapped(String jid,int id,boolean kex,byte[] data) { this.jid = jid; this.id = id; this.kex = kex; this.data = data; }
    }
    private PqOmemoMessage() {}
    interface SaveContent { void save(PqOmemoRecords tx,Message content) throws Exception; }

    static StandardExtensionElement encrypt(PqOmemoDevice device,Message plaintext,List<Target> targets,SecureRandom random) throws Exception {
        return encrypt(device,plaintext,targets,random,false);
    }
    static StandardExtensionElement encrypt(PqOmemoDevice device,Message plaintext,List<Target> targets,SecureRandom random,boolean transport) throws Exception {
        if (!device.jid.equals(plaintext.getFrom().asBareJid().toString()) || targets.isEmpty() || targets.size() > MAX_TARGETS) throw invalid();
        byte[] key = new byte[32]; random.nextBytes(key); byte[] envelope = null;
        try (PqOmemoRecords tx = device.records.begin()) {
            PqOmemoSignalStore store = device.store(tx);
            List<Wrapped> wraps = new ArrayList<>(); Set<String> addresses = new HashSet<>();
            List<PqAuth.Entry> kex = new ArrayList<>();
            for (Target target : targets) {
                SignalProtocolAddress remote = PqOmemoDevice.remote(target.jid,target.device);
                if (!addresses.add(PqOmemoSignalStore.peer(remote)) || target.jid.equals(device.jid) && target.device == device.id) throw invalid();
                if (target.bundle != null) store.requireTrusted(remote,target.bundle.identity,target.bundle.pqIdentity);
                else {
                    byte[] pin = tx.get(PqOmemoSignalStore.trust(remote));
                    if (pin == null || pin.length != 2625 || !store.containsSession(remote)) throw new UntrustedIdentityException(target.jid);
                    store.requireTrusted(remote,new IdentityKey(Arrays.copyOf(pin,33)),Arrays.copyOfRange(pin,33,pin.length));
                }
                if (!store.containsSession(remote)) new SessionBuilder(store,remote,device.address()).process(target.bundle.sessionBundle(target.device,random));
                CiphertextMessage encrypted = new SessionCipher(store,device.address(),remote).encrypt(key);
                boolean prekey = encrypted instanceof PreKeySignalMessage;
                byte[] bytes = encrypted.serialize();
                if (bytes.length > MAX_WRAPPED_BYTES) throw invalid();
                wraps.add(new Wrapped(target.jid,target.device,prekey,bytes));
                if (prekey) kex.add(new PqAuth.Entry(target.jid,target.device,bytes));
            }
            StandardExtensionElement.Builder header = StandardExtensionElement.builder("header",NS).addAttribute("sid",Integer.toString(device.id));
            if (!kex.isEmpty()) {
                PqIdentityKeyPair pq = device.pq(tx); byte[] pqPublic = pq.getPublicKey().serialize();
                byte[] transcript = PqAuth.transcript(device.jid,device.id,store.getIdentityKeyPair().getPublicKey(),pqPublic,kex);
                header.addElement(StandardExtensionElement.builder("pq-auth",NS)
                        .addElement(StandardExtensionElement.builder("pq-ik",NS).addAttribute("type","ML-DSA-87").setText(b64(pqPublic)).build())
                        .addElement("pq-sig",b64(pq.sign(transcript)))
                        .addElement("ik-sig",b64(store.getIdentityKeyPair().getPrivateKey().calculateSignature(transcript))).build());
            }
            Map<String,StandardExtensionElement.Builder> byJid = new LinkedHashMap<>();
            for (Wrapped wrap : wraps) {
                StandardExtensionElement.Builder keys = byJid.get(wrap.jid);
                if (keys == null) { keys = StandardExtensionElement.builder("keys",NS).addAttribute("jid",wrap.jid); byJid.put(wrap.jid,keys); }
                StandardExtensionElement.Builder wrapped = StandardExtensionElement.builder("key",NS).addAttribute("rid",Integer.toString(wrap.id)).setText(b64(wrap.data));
                if (wrap.kex) wrapped.addAttribute("kex","true"); keys.addElement(wrapped.build());
            }
            for (StandardExtensionElement.Builder keys : byJid.values()) header.addElement(keys.build());
            StandardExtensionElement.Builder resultBuilder = StandardExtensionElement.builder("encrypted",NS).addElement(header.build());
            if (!transport) {
                envelope = envelope(plaintext,random);
                byte[] binding = PqOmemoPayload.binding(device.jid,plaintext.getTo().asBareJid().toString(),device.id);
                resultBuilder.addElement("payload",b64(PqOmemoPayload.seal(envelope,key,binding)))
                        .addElement("commit",b64(PqOmemoPayload.commitment(key,binding)));
            }
            StandardExtensionElement result = resultBuilder.build();
            if (result.toXML().length() > MAX_WIRE) throw invalid();
            tx.commit(); return result;
        } finally { Arrays.fill(key,(byte) 0); if (envelope != null) Arrays.fill(envelope,(byte) 0); }
    }

    /** Context must be resolved by the session: sender's real bare JID and DM/room destination. */
    static Message decrypt(PqOmemoDevice device,Message outer,String sender,String destination,long timestamp) throws Exception {
        return decrypt(device,outer,sender,destination,timestamp,(tx,content) -> {});
    }
    static Message decrypt(PqOmemoDevice device,Message outer,String sender,String destination,long timestamp,SaveContent save) throws Exception {
        PqOmemoDevice.bare(sender); PqOmemoDevice.bare(destination);
        StandardExtensionElement encrypted = outer.getExtension("encrypted",NS);
        if (encrypted == null || outer.getExtensions("encrypted",NS).size() != 1 || encrypted.toXML().length() > MAX_WIRE) throw invalid();
        StandardExtensionElement header = one(encrypted,"header");
        int sid = positive(header.getAttributeValue("sid"));
        List<PqAuth.Entry> kexEntries = new ArrayList<>(); Wrapped own = null;
        Set<String> addresses = new HashSet<>(); int count = 0;
        for (StandardExtensionElement group : header.getElements()) {
            if (!"keys".equals(group.getElementName()) || !NS.equals(group.getNamespace())) continue;
            String jid = PqOmemoDevice.bare(group.getAttributeValue("jid")); int inGroup = 0;
            for (StandardExtensionElement wrapped : group.getElements()) {
                if (!NS.equals(wrapped.getNamespace()) || !"key".equals(wrapped.getElementName()) || ++count > MAX_TARGETS || ++inGroup > 128) throw invalid();
                int id = positive(wrapped.getAttributeValue("rid"));
                if (!addresses.add(jid + "\n" + id)) throw invalid();
                String flag = wrapped.getAttributeValue("kex");
                if (flag != null && !flag.equals("true") && !flag.equals("false")) throw invalid();
                boolean prekey = "true".equals(flag); byte[] data = variable(wrapped.getText(),MAX_WRAPPED_BYTES);
                if (prekey) kexEntries.add(new PqAuth.Entry(jid,id,data));
                if (jid.equals(device.jid) && id == device.id) own = new Wrapped(jid,id,prekey,data);
            }
        }
        if (own == null) throw new GeneralSecurityException("PQ message is not encrypted for this device");
        SignalProtocolAddress remote = PqOmemoDevice.remote(sender,sid);
        PreKeySignalMessage prekey = null; byte[] pqPublic = null;
        if (own.kex) {
            prekey = new PreKeySignalMessage(own.data);
            StandardExtensionElement auth = one(header,"pq-auth"), pq = one(auth,"pq-ik");
            if (!"ML-DSA-87".equals(pq.getAttributeValue("type"))) throw invalid();
            pqPublic = data(pq,2592);
            if (!PqAuth.verify(sender,sid,prekey.getIdentityKey(),pqPublic,data(one(auth,"pq-sig"),4627),data(one(auth,"ik-sig"),64),kexEntries)) throw invalid();
            // Public authenticated observation survives an untrusted-key rejection, not a session mutation.
            device.observe(sender,sid,prekey.getIdentityKey(),pqPublic);
        }
        byte[] plaintext = null, key = null;
        try (PqOmemoRecords tx = device.records.begin()) {
            PqOmemoSignalStore store = device.store(tx);
            byte[] trusted = tx.get(PqOmemoSignalStore.trust(remote));
            if (trusted == null || trusted.length != 2625 || tx.get("blocked/" + PqOmemoSignalStore.peer(remote)) != null)
                throw new UntrustedIdentityException(sender);
            if (prekey != null) store.requireTrusted(remote,prekey.getIdentityKey(),pqPublic);
            SessionCipher cipher = new SessionCipher(store,device.address(),remote);
            key = prekey != null ? cipher.decrypt(prekey) : cipher.decrypt(new SignalMessage(own.data));
            if (key.length != 32) throw invalid();
            if (optional(encrypted,"payload") == null) {
                if (optional(encrypted,"commit") != null) throw invalid();
                save.save(tx,null); tx.commit(); return null;
            }
            plaintext = PqOmemoPayload.open(variable(one(encrypted,"payload").getText(),PqOmemoPayload.MAX + 16),key,
                    PqOmemoPayload.binding(sender,destination,sid),data(one(encrypted,"commit"),64));
            Message context = new Message(); context.setType(outer.getType()==Message.Type.groupchat ? Message.Type.groupchat : Message.Type.chat);
            context.setFrom(JidCreate.entityBareFrom(sender)); context.setTo(JidCreate.entityBareFrom(destination));
            context.setStanzaId(outer.getStanzaId());
            // Unlike the classic compatibility parser, this profile requires all SCE affixes.
            String xml = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(plaintext)).toString();
            if (xml.contains("<!")) throw invalid();
            StandardExtensionElement sce = StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml));
            for (String name : new String[]{"from","to","time"}) Omemo2Bundle.single(sce,name,Omemo2Payload.SCE);
            Message content = Omemo2Payload.content(context,plaintext,timestamp,true);
            content.setFrom(outer.getFrom()); content.setTo(outer.getTo()); content.setType(outer.getType());
            save.save(tx,content);
            tx.commit(); return content;
        } finally { if (key != null) Arrays.fill(key,(byte) 0); if (plaintext != null) Arrays.fill(plaintext,(byte) 0); }
    }

    private static byte[] envelope(Message message,SecureRandom random) throws Exception {
        byte[] classic = Omemo2Payload.envelope(message,random);
        try {
            StandardExtensionElement parsed = StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(new String(classic,StandardCharsets.UTF_8)));
            StandardExtensionElement.Builder result = StandardExtensionElement.builder("envelope",Omemo2Payload.SCE);
            for (StandardExtensionElement child : parsed.getElements()) if (!"rpad".equals(child.getElementName())) result.addElement(child);
            result.addElement(StandardExtensionElement.builder("time",Omemo2Payload.SCE).addAttribute("stamp",java.time.Instant.now().toString()).build());
            // First measure the serializer's exact empty-rpad overhead; then use upstream Padme buckets.
            StandardExtensionElement base = result.build();
            StandardExtensionElement.Builder measured = StandardExtensionElement.builder("envelope",Omemo2Payload.SCE);
            for (StandardExtensionElement child : base.getElements()) measured.addElement(child);
            int unpadded = measured.addElement(StandardExtensionElement.builder("rpad",Omemo2Payload.SCE).setText("A").build())
                    .build().toXML().toString().getBytes(StandardCharsets.UTF_8).length - 1;
            int target = paddedLength(unpadded);
            char[] pad = new char[target - unpadded]; Arrays.fill(pad,'A');
            for (int i = 0; i < pad.length; i++) pad[i] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".charAt(random.nextInt(64));
            byte[] output = result.addElement(StandardExtensionElement.builder("rpad",Omemo2Payload.SCE).setText(new String(pad)).build()).build().toXML().toString().getBytes(StandardCharsets.UTF_8);
            if (output.length > PqOmemoPayload.MAX) throw invalid(); return output;
        } finally { Arrays.fill(classic,(byte) 0); }
    }
    static int paddedLength(int unpadded) {
        long x = (long) unpadded + 1; int e = 63 - Long.numberOfLeadingZeros(x), s = 32 - Integer.numberOfLeadingZeros(e);
        long mask = (1L << Math.max(0,e - s)) - 1, padme = (x + mask) & ~mask;
        return (int) ((padme + 255) / 256 * 256);
    }
    private static int positive(String value) throws Exception {
        if (value == null || !value.matches("[0-9]{1,10}")) throw invalid();
        try { int id = Integer.parseInt(value); if (id <= 0) throw invalid(); return id; }
        catch (NumberFormatException error) { throw invalid(); }
    }
    private static byte[] variable(String value,int max) throws Exception {
        if (value == null || value.length() > (max + 2) / 3 * 4 + 128) throw invalid();
        try { byte[] data = Base64.getDecoder().decode(value.replaceAll("[\\t\\n\\r ]", "")); if (data.length == 0 || data.length > max) throw invalid(); return data; }
        catch (IllegalArgumentException error) { throw invalid(); }
    }
}
