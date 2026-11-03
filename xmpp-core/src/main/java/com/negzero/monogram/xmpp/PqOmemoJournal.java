// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;

/** Pending authenticated content survives a crash between ratchet commit and message-store commit. */
final class PqOmemoJournal {
    static final int MAX_ENTRIES = 128, MAX_BYTES = 16 * 1024 * 1024, MAX_ENTRY = 2 * 1024 * 1024;
    static final class Full extends IOException { Full() { super("PQ incoming journal is full; persist pending messages before retrying"); } }
    static final class Entry {
        final String digest, fingerprint, archive;
        final Message outer, content;
        final long timestamp;
        Entry(String digest,String fingerprint,Message outer,Message content,long timestamp,String archive) {
            this.digest = digest; this.fingerprint = fingerprint; this.outer = outer; this.content = content; this.timestamp = timestamp; this.archive = archive;
        }
    }
    static String digest(Message outer,String sender,String destination) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String field : new String[]{PqOmemoBundle.NS,sender,destination,outer.getExtension("encrypted",PqOmemoBundle.NS).toXML().toString()}) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            hash.update(PqOmemoDevice.integer(bytes.length)); hash.update(bytes);
        }
        return XmppSession.hex(hash.digest());
    }
    static Entry find(PqOmemoRecords tx,String digest) throws Exception {
        byte[] encoded = tx.get("incoming/" + digest);
        try { return encoded == null ? null : decode(digest,encoded); }
        finally { if (encoded != null) Arrays.fill(encoded,(byte) 0); }
    }
    static void save(PqOmemoRecords tx,Entry entry) throws Exception {
        Set<String> keys = tx.keys("incoming/"); long total = 0;
        if (keys.size() >= MAX_ENTRIES) throw new Full();
        for (String key : keys) total += tx.get(key).length;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1); string(out,entry.fingerprint); string(out,entry.outer.toXML().toString());
        string(out,entry.content == null ? null : entry.content.toXML().toString()); out.writeLong(entry.timestamp); string(out,entry.archive); out.flush();
        byte[] encoded = bytes.toByteArray();
        try {
            if (encoded.length > MAX_ENTRY || total + encoded.length > MAX_BYTES) throw new Full();
            tx.put("incoming/" + entry.digest,encoded);
        } finally { Arrays.fill(encoded,(byte) 0); }
    }
    static List<Entry> pending(PqOmemoRecords tx) throws Exception {
        Set<String> keys = tx.keys("incoming/"); if (keys.size() > MAX_ENTRIES) throw new Full();
        List<Entry> result = new ArrayList<>(); long total = 0;
        for (String key : keys) {
            byte[] encoded = tx.get(key); total += encoded.length; if (total > MAX_BYTES) throw new Full();
            try { result.add(decode(key.substring(9),encoded)); } finally { Arrays.fill(encoded,(byte) 0); }
        }
        return result;
    }
    private static Entry decode(String digest,byte[] encoded) throws Exception {
        if (!digest.matches("[a-f0-9]{64}") || encoded.length > MAX_ENTRY) throw new IOException("Invalid PQ journal record");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != 1) throw new IOException("Unknown PQ journal format");
        String fingerprint = string(in), outer = string(in), content = string(in);
        long timestamp = in.readLong(); String archive = string(in);
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{128}") || outer == null || outer.contains("<!") || content != null && content.contains("<!") || in.read() != -1)
            throw new IOException("Invalid PQ journal content");
        return new Entry(digest,fingerprint,PacketParserUtils.parseStanza(outer),content == null ? null : PacketParserUtils.parseStanza(content),timestamp,archive);
    }
    private static void string(DataOutputStream out,String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_ENTRY) throw new IOException("PQ journal field too large");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size == -1) return null;
        if (size < 0 || size > MAX_ENTRY || size > in.available()) throw new IOException("Invalid PQ journal field");
        byte[] bytes = new byte[size]; in.readFully(bytes);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch(java.nio.charset.CharacterCodingException bad) { throw new IOException("Malformed PQ journal text",bad); }
        finally { Arrays.fill(bytes,(byte) 0); }
    }
}
