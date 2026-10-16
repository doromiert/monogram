// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;

/** XEP-0384 payload encryption and the direct-message XEP-0420 profile. */
final class Omemo2Payload {
    static final String SCE = "urn:xmpp:sce:1", CLIENT = "jabber:client";
    static final int MAX = 256*1024;
    static final class Sealed implements AutoCloseable {
        final byte[] ciphertext, material;
        Sealed(byte[] ciphertext, byte[] material) { this.ciphertext = ciphertext; this.material = material; }
        @Override public void close() { Arrays.fill(material,(byte)0); }
    }
    private Omemo2Payload() {}
    static byte[] derive(byte[] key) {
        byte[] expanded = new byte[80]; HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(key,new byte[32],"OMEMO Payload".getBytes(StandardCharsets.US_ASCII)));
        hkdf.generateBytes(expanded,0,expanded.length); return expanded;
    }
    static byte[] mac(byte[] expanded, byte[] ciphertext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(expanded,32,32,"HmacSHA256"));
        return Arrays.copyOf(mac.doFinal(ciphertext),16);
    }
    static Sealed seal(byte[] plaintext, SecureRandom random) throws Exception {
        if (plaintext.length > MAX) throw Omemo2Bundle.invalid();
        byte[] key = new byte[32]; random.nextBytes(key); byte[] expanded = derive(key);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(expanded,0,32,"AES"),new IvParameterSpec(expanded,64,16));
            byte[] ciphertext = cipher.doFinal(plaintext), material = new byte[48];
            System.arraycopy(key,0,material,0,32); System.arraycopy(mac(expanded,ciphertext),0,material,32,16);
            return new Sealed(ciphertext,material);
        } finally { Arrays.fill(key,(byte)0); Arrays.fill(expanded,(byte)0); }
    }
    static byte[] open(byte[] ciphertext, byte[] material) throws Exception {
        if (material.length != 48 || ciphertext.length == 0 || ciphertext.length > MAX+16 || ciphertext.length%16 != 0) throw Omemo2Bundle.invalid();
        byte[] key = Arrays.copyOf(material,32), expanded = derive(key);
        try {
            if (!MessageDigest.isEqual(mac(expanded,ciphertext),Arrays.copyOfRange(material,32,48))) throw Omemo2Bundle.invalid();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(expanded,0,32,"AES"),new IvParameterSpec(expanded,64,16));
            return cipher.doFinal(ciphertext);
        } finally { Arrays.fill(key,(byte)0); Arrays.fill(expanded,(byte)0); }
    }
    static byte[] envelope(Message message, SecureRandom random) throws Exception {
        direct(message);
        StandardExtensionElement.Builder content = StandardExtensionElement.builder("content",SCE);
        for (Message.Body body : message.getBodies()) {
            StandardExtensionElement.Builder text = StandardExtensionElement.builder("body",CLIENT).setText(body.getMessage());
            if (body.getLanguage() != null) text.addAttribute("xml:lang",body.getLanguage()); content.addElement(text.build());
        }
        for (Message.Subject subject : message.getSubjects()) {
            StandardExtensionElement.Builder text = StandardExtensionElement.builder("subject",CLIENT).setText(subject.getSubject());
            if (subject.getLanguage() != null) text.addAttribute("xml:lang",subject.getLanguage()); content.addElement(text.build());
        }
        if (message.getThread() != null) content.addElement(StandardExtensionElement.builder("thread",CLIENT).setText(message.getThread()).build());
        for (ExtensionElement extension : message.getExtensions()) {
            if (!allowed(extension.getElementName(),extension.getNamespace())) throw new GeneralSecurityException("Unsupported protected content");
            content.addElement(StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(extension.toXML().toString())));
        }
        StandardExtensionElement inside = content.build();
        String from = message.getFrom().asBareJid().toString(), to = message.getTo().asBareJid().toString();
        int baseline = wrap(inside,from,to,"").toXML().toString().getBytes(StandardCharsets.UTF_8).length;
        int count = Math.max(0,512-baseline)+random.nextInt(201); StringBuilder pad = new StringBuilder(count);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i=0;i<count;i++) pad.append(alphabet.charAt(random.nextInt(alphabet.length())));
        byte[] result = wrap(inside,from,to,pad.toString()).toXML().toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX) { Arrays.fill(result,(byte)0); throw Omemo2Bundle.invalid(); } return result;
    }
    private static StandardExtensionElement wrap(StandardExtensionElement content, String from, String to, String padding) {
        return StandardExtensionElement.builder("envelope",SCE).addElement(content)
                .addElement(StandardExtensionElement.builder("rpad",SCE).setText(padding).build())
                .addElement(StandardExtensionElement.builder("from",SCE).addAttribute("jid",from).build())
                .addElement(StandardExtensionElement.builder("to",SCE).addAttribute("jid",to).build()).build();
    }
    static Message content(Message outer, byte[] plaintext) throws Exception {
        java.util.Date delayed = org.jivesoftware.smackx.delay.DelayInformationManager.getDelayTimestamp(outer);
        return content(outer,plaintext,delayed == null ? System.currentTimeMillis() : delayed.getTime());
    }
    static Message content(Message outer, byte[] plaintext, long sentTime) throws Exception {
        direct(outer); if (plaintext.length > MAX) throw Omemo2Bundle.invalid();
        String xml = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString();
        if (xml.contains("<!")) throw Omemo2Bundle.invalid();
        StandardExtensionElement envelope = StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml));
        if (!"envelope".equals(envelope.getElementName()) || !SCE.equals(envelope.getNamespace())) throw Omemo2Bundle.invalid();
        StandardExtensionElement content = Omemo2Bundle.single(envelope,"content",SCE);
        Omemo2Bundle.single(envelope,"rpad",SCE);
        for (StandardExtensionElement affix : envelope.getElements()) {
            if (!SCE.equals(affix.getNamespace())) throw Omemo2Bundle.invalid();
            String name = affix.getElementName();
            if (name.equals("from") || name.equals("to")) {
                Omemo2Bundle.single(envelope,name,SCE);
                String value = affix.getAttributeValue("jid");
                if (value == null || !JidCreate.entityBareFrom(value).toString().equals(value)
                        || !value.equals((name.equals("from") ? outer.getFrom() : outer.getTo()).asBareJid().toString())) throw Omemo2Bundle.invalid();
            } else if (name.equals("time")) {
                Omemo2Bundle.single(envelope,name,SCE);
                String stamp = affix.getAttributeValue("stamp"); if (stamp == null || stamp.length() > 100) throw Omemo2Bundle.invalid();
                long time = org.jxmpp.util.XmppDateTime.parseXEP0082Date(stamp).getTime();
                if (sentTime < 0 || sentTime > Long.MAX_VALUE-600000 || time < sentTime-600000 || time > sentTime+600000) throw Omemo2Bundle.invalid();
            } else if (!name.equals("content") && !name.equals("rpad")) throw Omemo2Bundle.invalid();
        }
        StringBuilder stanza = new StringBuilder("<message xmlns='jabber:client'>");
        for (StandardExtensionElement child : content.getElements()) {
            if (!allowed(child.getElementName(),child.getNamespace())) throw Omemo2Bundle.invalid();
            stanza.append(child.toXML(CLIENT));
        }
        stanza.append("</message>");
        Message result = PacketParserUtils.parseStanza(stanza.toString());
        result.setFrom(outer.getFrom()); result.setTo(outer.getTo()); result.setStanzaId(outer.getStanzaId()); result.setType(outer.getType());
        return result;
    }
    private static boolean allowed(String name, String namespace) {
        return (name.equals("body") || name.equals("subject") || name.equals("thread")) && CLIENT.equals(namespace)
                || name.equals("file-sharing") && FileShare.NAMESPACE.equals(namespace)
                || name.equals("x") && FileShare.OOB.equals(namespace)
                || name.equals("reply") && ReplyReference.NAMESPACE.equals(namespace)
                || name.equals("fallback") && ReplyReference.FALLBACK.equals(namespace)
                || name.equals("replace") && MessageCorrection.NAMESPACE.equals(namespace)
                || name.equals("reactions") && MessageReactions.NAMESPACE.equals(namespace);
    }
    static void direct(Message message) throws GeneralSecurityException {
        if ((message.getType() != Message.Type.chat && message.getType() != Message.Type.normal) || message.getFrom() == null || message.getTo() == null
                || message.getFrom().asEntityBareJidIfPossible() == null || message.getTo().asEntityBareJidIfPossible() == null
                || message.hasExtension("x","http://jabber.org/protocol/muc#user")) throw Omemo2Bundle.invalid();
    }
}
