// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

/** Authenticated incoming content and its ratchet changes share the exact same records transaction. */
public final class Omemo2IncomingJournal {
    public static final int MAX_ENTRIES=128,MAX_BYTES=16*1024*1024,MAX_ENTRY_BYTES=2*1024*1024;
    private static final int HEADER=0x4d494831,ENTRY=0x4d494a31;
    private final Omemo2Device device;
    private final byte[] epoch;
    public static final class FullException extends IOException {
        FullException(String message) { super(message); }
    }
    public static final class Token {
        private final byte[] epoch,digest;
        private final int slot;
        private Token(byte[] epoch,int slot,byte[] digest) { this.epoch=epoch.clone();this.slot=slot;this.digest=digest.clone(); }
    }
    static final class Envelope {
        final int sender;final String peer;final byte[] ciphertext,digest;
        Envelope(int sender,String peer,byte[] ciphertext,byte[] digest) {
            this.sender=sender;this.peer=peer;this.ciphertext=ciphertext;this.digest=digest;
        }
    }
    public Omemo2IncomingJournal(Omemo2Device device) throws Exception {
        this.device=device;
        try(Omemo2Records tx=device.records.begin()) {
            byte[] header=tx.load(INCOMING,"",0);
            if(header==null) { rotate(tx);header=tx.load(INCOMING,"",0);tx.commit(); }
            epoch=header(header);
        }
    }
    /** Explicit logout/reset only. Normal close/reconnect must preserve pending plaintext. Caller commits. */
    public static void rotate(Omemo2Records tx) throws Exception {
        for(int id:tx.ids(INCOMING)) tx.save(INCOMING,"",id,null);
        byte[] epoch=new byte[16];new SecureRandom().nextBytes(epoch);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(HEADER);out.write(epoch);tx.save(INCOMING,"",0,bytes.toByteArray());
    }
    private static byte[] header(byte[] value) throws IOException {
        if(value==null || value.length!=20) throw new IOException("Invalid incoming journal header");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(value));
        if(in.readInt()!=HEADER) throw new IOException("Unknown incoming journal version");
        byte[] epoch=new byte[16];in.readFully(epoch);return epoch;
    }
    private boolean current(Omemo2Records tx) throws Exception {
        byte[] value=tx.load(INCOMING,"",0);return value!=null && MessageDigest.isEqual(epoch,header(value));
    }
    void check(Omemo2Records tx) throws Exception {
        if(!current(tx)) throw new GeneralSecurityException("OMEMO2 incoming journal was reset");
    }
    private byte[] identity(Omemo2Records tx) throws Exception {
        byte[] identity=tx.load(IDENTITY,"",1);
        if(identity==null || identity.length!=32) throw new GeneralSecurityException("Incoming journal identity is unavailable");
        return identity;
    }
    private static final class Saved {
        Token token;byte[] identity;String owner,fingerprint,outerXml,contentXml,ackXml;
        int device,size;boolean accepted,replenish,archived;long timestamp;String markable;
        Map<String,String> ids=new LinkedHashMap<>();
    }
    private Saved read(Omemo2Records tx,int slot) throws Exception {
        byte[] bytes=tx.load(INCOMING,"",slot);if(bytes==null) return null;
        if(slot<1 || slot>MAX_ENTRIES || bytes.length>MAX_ENTRY_BYTES) throw new IOException("Invalid incoming journal entry");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=ENTRY) throw new IOException("Unknown incoming journal entry version");
        byte[] scope=new byte[16],digest=new byte[32];in.readFully(scope);in.readFully(digest);
        Saved saved=new Saved();saved.size=bytes.length;saved.token=new Token(scope,slot,digest);saved.identity=new byte[32];in.readFully(saved.identity);
        saved.owner=string(in,4096);saved.device=in.readInt();saved.fingerprint=string(in,64);
        saved.accepted=in.readBoolean();saved.replenish=in.readBoolean();saved.ackXml=string(in,65536);
        if(!saved.accepted) {
            saved.outerXml=string(in,1024*1024);saved.contentXml=string(in,MAX_ENTRY_BYTES);
            saved.timestamp=in.readLong();saved.archived=in.readBoolean();saved.markable=string(in,16384);
            int count=in.readUnsignedByte();if(count<1 || count>8) throw new IOException("Invalid incoming journal aliases");
            for(int i=0;i<count;i++) {
                String kind=string(in,32),value=string(in,16384);
                if(kind==null || value==null || value.isEmpty() || saved.ids.put(kind,value)!=null) throw new IOException("Invalid incoming journal alias");
            }
        }
        if(in.read()!=-1 || !MessageDigest.isEqual(epoch,scope) || !device.jid.equals(saved.owner) || device.id!=saved.device
                || !MessageDigest.isEqual(identity(tx),saved.identity) || saved.fingerprint==null || !saved.fingerprint.matches("[0-9a-f]{64}"))
            throw new GeneralSecurityException("Incoming journal identity does not match its device");
        return saved;
    }
    private byte[] write(Saved saved) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(ENTRY);out.write(saved.token.epoch);out.write(saved.token.digest);out.write(saved.identity);
        string(out,saved.owner);out.writeInt(saved.device);string(out,saved.fingerprint);
        out.writeBoolean(saved.accepted);out.writeBoolean(saved.replenish);string(out,saved.ackXml);
        if(!saved.accepted) {
            string(out,saved.outerXml);string(out,saved.contentXml);out.writeLong(saved.timestamp);out.writeBoolean(saved.archived);string(out,saved.markable);
            if(saved.ids.size()>8) throw new IOException("Too many incoming aliases");out.writeByte(saved.ids.size());
            for(Map.Entry<String,String> id:saved.ids.entrySet()) {
                if(id.getKey()==null || id.getValue()==null || id.getKey().getBytes(StandardCharsets.UTF_8).length>32 || id.getValue().getBytes(StandardCharsets.UTF_8).length>16384)
                    throw new IOException("Incoming alias exceeds capacity");
                string(out,id.getKey());string(out,id.getValue());
            }
        }
        out.flush();byte[] result=bytes.toByteArray();
        if(result.length>MAX_ENTRY_BYTES) throw new IOException("Incoming journal entry exceeds capacity");return result;
    }
    private static void string(DataOutputStream out,String value) throws IOException {
        if(value==null) { out.writeInt(-1);return; }
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static String string(DataInputStream in,int maximum) throws IOException {
        int length=in.readInt();if(length==-1) return null;
        if(length<0 || length>maximum || length>in.available()) throw new IOException("Invalid incoming journal field");
        byte[] bytes=new byte[length];in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static Message message(String xml) throws Exception {
        if(xml==null) return null;
        if(xml.contains("<!")) throw new IOException("Invalid incoming journal XML");
        ReplyReference.initialize();FileShare.initialize();MessageCorrection.initialize();MessageReactions.initialize();MessageRetraction.initialize();MessageMarkup.initialize();
        return PacketParserUtils.parseStanza(xml);
    }
    private XmppOmemo2.Received received(Saved saved,Date time,String archiveId) throws Exception {
        Message acknowledgement=message(saved.ackXml),content=null;XmppMessage model=null;
        if(!saved.accepted) {
            Message outer=message(saved.outerXml);content=message(saved.contentXml);
            model=XmppMessage.modern(outer,content,JidCreate.entityBareFrom(device.jid),null,null);
            if(model==null) throw new IOException("Invalid authenticated journal content");
            model=model.canonical(saved.ids,saved.timestamp,saved.archived,saved.markable).withSenderFingerprint(saved.fingerprint).archived(archiveId,time);
        }
        return new XmppOmemo2.Received(content,acknowledgement,saved.replenish,saved.fingerprint,model,saved.token);
    }
    XmppOmemo2.Received find(Omemo2Records tx,byte[] digest,Date time,String archiveId) throws Exception {
        check(tx);int count=0;long bytes=0;
        for(int slot:tx.ids(INCOMING)) if(slot!=0) {
            Saved saved=read(tx,slot);
            if(saved!=null) bytes+=saved.size;
            if(++count>MAX_ENTRIES || bytes>MAX_BYTES) throw new IOException("Incoming journal exceeds capacity");
            if(saved!=null && MessageDigest.isEqual(digest,saved.token.digest)) return received(saved,time,archiveId);
        }
        return null;
    }
    XmppOmemo2.Received append(Omemo2Records tx,byte[] digest,Message outer,Message content,Message acknowledgement,
                             XmppMessage model,String fingerprint,boolean replenish) throws Exception {
        check(tx);boolean[] occupied=new boolean[MAX_ENTRIES+1];long total=0;int count=0;
        for(int slot:tx.ids(INCOMING)) if(slot!=0) {
            if(slot<1 || slot>MAX_ENTRIES) throw new IOException("Invalid incoming journal slot");
            occupied[slot]=true;count++;byte[] bytes=tx.load(INCOMING,"",slot);if(bytes!=null) total+=bytes.length;
        }
        if(count>=MAX_ENTRIES) throw new FullException("Incoming journal is full; store pending messages before retrying");
        int slot=1;while(occupied[slot]) slot++;
        Saved saved=new Saved();saved.token=new Token(epoch,slot,digest);saved.identity=identity(tx);saved.owner=device.jid;saved.device=device.id;
        if(fingerprint==null || !fingerprint.matches("[0-9a-f]{64}")) throw new GeneralSecurityException("Incoming sender identity is unavailable");
        saved.fingerprint=fingerprint;saved.replenish=replenish;saved.accepted=content==null;
        if(acknowledgement!=null) {
            if(acknowledgement.getStanzaId()==null) acknowledgement.setStanzaId(UUID.randomUUID().toString());
            saved.ackXml=acknowledgement.toXML().toString();if(saved.ackXml.getBytes(StandardCharsets.UTF_8).length>65536) throw new IOException("Incoming acknowledgement exceeds capacity");
        }
        if(content!=null) {
            if(model==null) throw new GeneralSecurityException("Invalid authenticated incoming message");
            saved.outerXml=outer.toXML().toString();saved.contentXml=content.toXML().toString();
            if(saved.outerXml.getBytes(StandardCharsets.UTF_8).length>1024*1024) throw new IOException("Incoming stanza exceeds journal capacity");
            saved.ids.putAll(model.ids);saved.timestamp=model.timestamp;saved.archived=model.archived;saved.markable=model.markableId;
        }
        byte[] bytes=write(saved);if(total+bytes.length>MAX_BYTES) throw new FullException("Incoming journal byte capacity is exhausted");
        tx.save(INCOMING,"",slot,bytes);
        return new XmppOmemo2.Received(content,acknowledgement,replenish,fingerprint,model,saved.token);
    }
    public List<XmppOmemo2.Received> pending() throws Exception {
        List<XmppOmemo2.Received> pending=new ArrayList<>();
        try(Omemo2Records tx=device.records.begin()) {
            if(!current(tx)) return pending;
            long bytes=0;int count=0;
            for(int slot:tx.ids(INCOMING)) if(slot!=0) {
                byte[] value=tx.load(INCOMING,"",slot);if(value!=null) bytes+=value.length;
                if(++count>MAX_ENTRIES || bytes>MAX_BYTES) throw new IOException("Incoming journal exceeds capacity");
                pending.add(received(read(tx,slot),null,null));
            }
        }
        return Collections.unmodifiableList(pending);
    }
    public boolean accept(Token token) throws Exception {
        if(token==null) return true;
        try(Omemo2Records tx=device.records.begin()) {
            if(!current(tx) || !MessageDigest.isEqual(epoch,token.epoch)) return false;
            Saved saved=read(tx,token.slot);
            if(saved==null || !MessageDigest.isEqual(saved.token.digest,token.digest)) return false;
            if(saved.accepted) return true;
            saved.accepted=true;saved.outerXml=null;saved.contentXml=null;saved.ids.clear();saved.markable=null;
            tx.save(INCOMING,"",token.slot,saved.ackXml==null ? null : write(saved));tx.commit();return true;
        }
    }
    public void acknowledge(Token token) throws Exception {
        if(token==null) return;
        try(Omemo2Records tx=device.records.begin()) {
            if(!current(tx) || !MessageDigest.isEqual(epoch,token.epoch)) return;
            Saved saved=read(tx,token.slot);
            if(saved==null || !saved.accepted || !MessageDigest.isEqual(saved.token.digest,token.digest)) return;
            tx.save(INCOMING,"",token.slot,null);tx.commit();
        }
    }
    /** A private stable alias: transport/MAM IDs and full resource rewriting cannot substitute ciphertext. */
    static Envelope envelope(Message outer) throws Exception {
        Omemo2Payload.direct(outer);
        List<ExtensionElement> encrypted=outer.getExtensions("encrypted",XmppOmemo2.NS);
        if(encrypted.size()!=1 || !(encrypted.get(0) instanceof StandardExtensionElement)) throw Omemo2Bundle.invalid();
        StandardExtensionElement root=(StandardExtensionElement)encrypted.get(0);
        if(root.toXML().length()>1024*1024) throw Omemo2Bundle.invalid();
        StandardExtensionElement header=Omemo2Bundle.single(root,"header",XmppOmemo2.NS);
        int sender=Omemo2Bundle.positive(header.getAttributeValue("sid"));
        List<StandardExtensionElement> groups=new ArrayList<>(header.getElements());Set<String> seen=new HashSet<>();int count=0;
        for(StandardExtensionElement group:groups) {
            String jid=group.getAttributeValue("jid");
            if(!"keys".equals(group.getElementName()) || !XmppOmemo2.NS.equals(group.getNamespace()) || jid==null
                    || !JidCreate.entityBareFrom(jid).toString().equals(jid) || jid.indexOf('/')>=0 || !seen.add(jid)) throw Omemo2Bundle.invalid();
            Set<Integer> ids=new HashSet<>();
            for(StandardExtensionElement key:group.getElements()) {
                if(!"key".equals(key.getElementName()) || !XmppOmemo2.NS.equals(key.getNamespace()) || ++count>1000
                        || !ids.add(Omemo2Bundle.positive(key.getAttributeValue("rid")))) throw Omemo2Bundle.invalid();
            }
        }
        List<StandardExtensionElement> payloads=root.getElements("payload",XmppOmemo2.NS);
        if(payloads!=null && payloads.size()>1) throw Omemo2Bundle.invalid();
        byte[] ciphertext=payloads==null || payloads.isEmpty() ? null : decode(payloads.get(0).getText(),Omemo2Payload.MAX+16);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        string(out,"Monogram OMEMO2 incoming v1");string(out,outer.getFrom().asBareJid().toString());string(out,outer.getTo().asBareJid().toString());string(out,outer.getType().name());out.writeInt(sender);
        groups.sort(Comparator.comparing(group -> group.getAttributeValue("jid")));out.writeInt(groups.size());
        for(StandardExtensionElement group:groups) {
            string(out,group.getAttributeValue("jid"));List<StandardExtensionElement> keys=new ArrayList<>(group.getElements());
            keys.sort(Comparator.comparingInt(key -> Integer.parseInt(key.getAttributeValue("rid"))));out.writeInt(keys.size());
            for(StandardExtensionElement key:keys) {
                out.writeInt(Omemo2Bundle.positive(key.getAttributeValue("rid")));String flag=key.getAttributeValue("kex");
                string(out,"true".equals(flag) || "1".equals(flag) ? "1" : flag==null || "false".equals(flag) || "0".equals(flag) ? "0" : flag);
                string(out,key.getText()==null ? null : key.getText().replaceAll("[\\t\\n\\r ]",""));
            }
        }
        out.writeInt(ciphertext==null ? -1 : ciphertext.length);if(ciphertext!=null) out.write(ciphertext);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        // Selection is account/device specific and is checked separately by receive, never by the alias.
        return new Envelope(sender,outer.getFrom().asBareJid().toString(),ciphertext,digest);
    }
    static byte[] decode(String text,int maximum) throws Exception {
        if(text==null || text.length()>maximum*2) throw Omemo2Bundle.invalid();
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(text.replaceAll("[\\t\\n\\r ]","")); }
        catch(IllegalArgumentException invalid) { throw Omemo2Bundle.invalid(); }
        if(bytes.length==0 || bytes.length>maximum) throw Omemo2Bundle.invalid();return bytes;
    }
}
