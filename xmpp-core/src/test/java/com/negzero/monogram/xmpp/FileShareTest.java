package com.negzero.monogram.xmpp;

import java.nio.file.Files;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileShareTest {
    @Test void generatedUploadNamesKeepExtensionsAndExplicitNames() {
        assertEquals("holiday.jpg",FileTransfer.uploadName("holiday.jpg","image/jpeg",false));
        assertTrue(FileTransfer.uploadName("-2147483648_12345.jpg","image/jpeg",false).matches("[0-9]+_[a-z]{8}\\.jpg"));
        assertTrue(FileTransfer.uploadName("recording.ogg","audio/ogg",true).matches("[0-9]+_[a-z]{8}\\.ogg"));
    }
    static final String URL = "https://example.org/photo.jpg";
    private Message stanza(String body, String extra) throws Exception {
        FileShare.initialize();
        Message message = PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='bob@example.org/phone' to='alice@example.org' type='chat' id='file'>" + extra + "</message>");
        message.setBody(body); return message;
    }
    private XmppMessage read(Message message, boolean encrypted, boolean readable, String body) throws Exception {
        return XmppMessage.from(message, JidCreate.entityBareFrom("alice@example.org"), body, encrypted, readable, null, null);
    }
    private String description(String source, String metadata) {
        return "<file-sharing xmlns='urn:xmpp:sfs:0' disposition='inline'><file xmlns='urn:xmpp:file:metadata:0'><name>photo.jpg</name><media-type>image/jpeg</media-type>" + metadata
                + "</file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='" + source + "'/></sources></file-sharing>";
    }
    @Test void stickerMarkerUsesStandardSiblingsAndPersistsWithoutAcceptingForgedOuterMetadata() throws Exception {
        FileShare normal = new FileShare(description(URL, ""));
        FileShare sticker = normal.asSticker(); assertTrue(sticker.sticker); assertFalse(normal.sticker);
        assertFalse(normal.sameAttachment(sticker)); assertTrue(sticker.sameAttachment(new FileShare(sticker.xml())));
        Message message = stanza(sticker.body("😘"), ""); sticker.addTo(message, message.getBody());
        assertTrue(message.hasExtension("sticker", FileShare.STICKERS));
        assertEquals(1, message.getExtensions("file-sharing", FileShare.NAMESPACE).size());
        FileShare incoming = FileShare.from(PacketParserUtils.parseStanza(message.toXML().toString()));
        assertNotNull(incoming); assertTrue(incoming.sticker);
        assertFalse(FileShare.from(stanza(normal.body("x"), normal.xml())).sticker);
        assertNull(read(message, true, true, "Authenticated text").sharing, "Legacy body-only OMEMO cannot trust an external sticker marker");
        String nested = "<message xmlns='jabber:client'><sticker xmlns='urn:xmpp:stickers:0'/>" + normal.xml();
        assertThrows(Exception.class, () -> new FileShare(nested + normal.xml() + "</message>"));
        assertThrows(Exception.class, () -> new FileShare(nested + "<extra xmlns='urn:test'/></message>"));
        assertThrows(Exception.class, () -> new FileShare(nested.replace("<sticker xmlns", "<sticker surprise='1' xmlns") + "</message>"));
        byte[] envelope = Omemo2Payload.envelope(message, new java.security.SecureRandom());
        assertTrue(FileShare.from(Omemo2Payload.content(message, envelope)).sticker, "Modern OMEMO carries the marker inside the authenticated envelope");
    }
    @Test void captionsAndReplyFallbacksUseUnicodeCodePoints() throws Exception {
        java.io.File file = Files.createTempFile("share-", ".jpg").toFile();
        try {
            Files.write(file.toPath(), new byte[]{1,2,3});
            FileShare share = FileShare.describe(FileLink.parse(URL), file, "photo.jpg", "image/jpeg");
            ReplyReference reply = new ReplyReference("bob@example.org", "original").quoted("Quoted 🦄");
            for (String caption : new String[]{"", "Caption 😀", "First\nSecond 🐈\n", "Literal " + URL}) {
                String body = reply.wireBody(share.body(caption)); Message message = stanza(body, ""); reply.addTo(message); share.addTo(message, body);
                Message parsed = PacketParserUtils.parseStanza(message.toXML().toString());
                XmppMessage event = read(parsed, false, true, parsed.getBody());
                assertEquals(caption, event.caption); assertEquals(URL, event.attachment.wireUrl);
                assertEquals("original", event.reply.id); assertEquals(3, event.sharing.size);
                assertEquals("photo.jpg", new FileShare(event.sharing.xml()).name);
            }
            XmppMessage local = XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"), "bob@example.org", "file-id", share, "Local 😀", reply);
            assertEquals("Local 😀", local.caption); assertTrue(local.outgoing);
        } finally { file.delete(); }
    }
    @Test void externalMetadataCannotChangeEncryptedMessagesOrHideCaptionText() throws Exception {
        Message message = stanza("Caption text", description(URL, ""));
        XmppMessage privateText = read(message, true, true, "Private text");
        assertNull(privateText.attachment); assertNull(privateText.sharing); assertEquals("Private text", privateText.body);
        assertNull(read(message, true, false, null).attachment);
        String encrypted = "aesgcm://example.org/photo.jpg#" + new String(new char[88]).replace('\0', 'a');
        assertNull(read(stanza("Caption", description(encrypted, "")), false, true, "Caption").attachment);
        String marked = description(URL, "") + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:sfs:0'><body start='0' end='7'/></fallback>";
        assertEquals("Caption text", read(stanza("Caption text", marked), false, true, "Caption text").caption);
        String duplicate = description(URL, "") + description("https://example.org/other.jpg", "");
        assertNull(read(stanza("Two files", duplicate), false, true, "Two files").attachment);
        assertNotNull(read(stanza(null, description(URL, "")), false, true, null).attachment);
        assertNull(read(stanza(null, ""), false, true, null));
        String oob = "<x xmlns='jabber:x:oob'><url>"+URL+"</url></x>";
        assertEquals("An older client's caption", read(stanza("An older client's caption", oob), false, true, "An older client's caption").caption);
        ReplyReference reply = new ReplyReference("bob@example.org", "original").quoted("Original 😀");
        Message repliedFile = stanza(reply.wireBody(URL), oob); reply.addTo(repliedFile);
        assertEquals("", read(repliedFile, false, true, repliedFile.getBody()).caption, "Legacy file-only replies must not show the file URL as a caption");
        String invalidHash = description(URL, "<hash xmlns='urn:xmpp:hashes:2' algo='sha-256'>invalid</hash>");
        assertNull(read(stanza(URL, oob + invalidHash), false, true, URL).attachment, "Invalid SFS integrity metadata must not fall back to an unchecked OOB download");
    }
    @Test void malformedMetadataIsRejectedAndHashesVerifyPlaintext() throws Exception {
        for (String metadata : new String[]{"<size>-2</size>", "<size>NaN</size>", "<size>1</size><size>2</size>", "<hash xmlns='urn:xmpp:hashes:2' algo='sha-256'>broken</hash>"}) {
            assertNull(FileShare.from(stanza("Text", description(URL, metadata))), metadata);
        }
        java.io.File file = Files.createTempFile("share-hash-", ".bin").toFile();
        try {
            byte[] data = "Test contents".getBytes("UTF-8"); Files.write(file.toPath(), data);
            for (String algorithm : new String[]{"sha-256", "sha3-256", "id-blake2b256"}) {
                org.bouncycastle.crypto.Digest digest = algorithm.equals("sha-256") ? new org.bouncycastle.crypto.digests.SHA256Digest()
                        : algorithm.equals("sha3-256") ? new org.bouncycastle.crypto.digests.SHA3Digest(256) : new org.bouncycastle.crypto.digests.Blake2bDigest(256);
                digest.update(data,0,data.length); byte[] hash = new byte[32]; digest.doFinal(hash,0);
                FileShare share = new FileShare(description(URL, "<size>"+data.length+"</size><hash xmlns='urn:xmpp:hashes:2' algo='"+algorithm+"'>"+java.util.Base64.getEncoder().encodeToString(hash)+"</hash>"));
                share.verify(file, new FileTransfer.Job()); data[0] ^= 1; Files.write(file.toPath(),data);
                assertThrows(FileTransfer.Failure.class, () -> share.verify(file, new FileTransfer.Job()));
                data[0] ^= 1; Files.write(file.toPath(),data);
            }
            FileShare secret = new FileShare(description("aesgcm://example.org/photo.jpg#" + new String(new char[88]).replace('\0','a'), ""));
            XmppMessage modern = XmppMessage.sentFile(JidCreate.entityBareFrom("alice@example.org"), "bob@example.org", "file", secret, "Caption", null);
            assertEquals(XmppMessage.Encryption.OMEMO2,modern.encryption); assertEquals("Caption",modern.caption);
            assertEquals(secret.link.wireUrl,modern.sharing.link.wireUrl);
        } finally { file.delete(); }
    }
    @Test void cacheIdentityIncludesSizeAndCanonicalHashes() throws Exception {
        String sha = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String sha3 = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String first = "<hash xmlns='urn:xmpp:hashes:2' algo='sha-256'>"+sha+"</hash>";
        String second = "<hash xmlns='urn:xmpp:hashes:2' algo='sha3-256'>"+sha3+"</hash>";
        assertEquals(URL, new FileShare(description(URL, "")).cacheKey());
        FileShare expected = new FileShare(description(URL, "<size>4</size>"+first+second));
        assertEquals(expected.cacheKey(), new FileShare(description(URL, second+first+"<size> 4 </size>")).cacheKey());
        assertNotEquals(expected.cacheKey(), new FileShare(description(URL, "<size>5</size>"+first+second)).cacheKey());
        assertNotEquals(expected.cacheKey(), new FileShare(description(URL, "<size>4</size>"+first)).cacheKey());
        assertNotEquals(expected.cacheKey(), new FileShare(description("https://example.org/other.jpg", "<size>4</size>"+first+second)).cacheKey());
        assertNotEquals(expected.cacheKey(), new FileShare(description(URL, "<size>4</size>"+first.replace(sha, "AQ"+sha.substring(2))+second)).cacheKey());
        assertEquals(new FileShare(description(URL, first)).cacheKey(), new FileShare(description(URL, first.replace(sha, "\n "+sha+"\t"))).cacheKey());
    }
    @Test void attachmentEquivalenceAcceptsKnownReorderingButNotReplacement() throws Exception {
        String hash = "<hash xmlns='urn:xmpp:hashes:2' algo='sha-256'>" + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "</hash>";
        String xml = description(URL, "<size>4</size>" + hash);
        FileShare original = new FileShare(xml);
        String reordered = xml.replace("<name>photo.jpg</name><media-type>image/jpeg</media-type><size>4</size>" + hash,
                hash + "<size>4</size><media-type>image/jpeg</media-type><name>photo.jpg</name>");
        FileShare equivalent = new FileShare(reordered);
        assertNotEquals(original.xml(), equivalent.xml());
        assertTrue(original.sameAttachment(equivalent)); assertTrue(equivalent.sameAttachment(original));
        for (String changed : new String[]{reordered.replace(URL, URL + "?replacement"),
                reordered.replace("<size>4</size>", "<size>5</size>"), reordered.replace("photo.jpg", "renamed.jpg"),
                reordered.replace("image/jpeg", "image/png"), reordered.replace(hash, ""),
                reordered.replace("AAAAAAAA", "AQAAAAAA"), reordered.replace("disposition='inline'", "disposition='attachment'"),
                reordered.replace("<file-sharing ", "<file-sharing id='new' ")}) {
            assertFalse(original.sameAttachment(new FileShare(changed)), changed);
        }
        String secret = "aesgcm://example.org/photo.jpg#" + new String(new char[88]).replace('\0', 'a');
        FileShare encrypted = new FileShare(xml.replace(URL, secret));
        assertTrue(encrypted.sameAttachment(new FileShare(reordered.replace(URL, secret))));
        assertFalse(encrypted.sameAttachment(new FileShare(reordered.replace(URL, secret.substring(0, secret.length()-1) + "b"))));
        assertFalse(original.sameAttachment(null));
    }
    @Test void unknownAttachmentMetadataRequiresUnchangedSerialization() throws Exception {
        for (String extra : new String[]{"<future xmlns='urn:example:future'>value</future>",
                "<hash xmlns='urn:xmpp:hashes:2' algo='unknown'>AAAA</hash>",
                "<desc xml:lang='en'>Description</desc>", "<width unit='pixels'>10</width>",
                "<width>10</width><width>10</width>"}) {
            String xml = description(URL, extra);
            FileShare original = new FileShare(xml);
            assertTrue(original.sameAttachment(new FileShare(xml)));
            String reordered = xml.replace("<name>photo.jpg</name><media-type>image/jpeg</media-type>",
                    "<media-type>image/jpeg</media-type><name>photo.jpg</name>");
            assertFalse(original.sameAttachment(new FileShare(reordered)), extra);
            if (extra.contains("value")) assertFalse(original.sameAttachment(new FileShare(xml.replace("value", "other"))));
        }
    }
    @Test void snapshotKeepsStableBytesAndCleansFailedCopies() throws Exception {
        java.nio.file.Path directory = Files.createTempDirectory("upload-snapshot-");
        java.io.File source = directory.resolve("source.bin").toFile();
        try {
            Files.write(source.toPath(), new byte[]{1,2,3});
            java.io.File snapshot = FileTransfer.snapshot(source, directory.toFile(), new FileTransfer.Job());
            Files.write(source.toPath(), new byte[]{4,5,6});
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(snapshot.toPath())); snapshot.delete();
            FileTransfer.Job cancelled = new FileTransfer.Job(); cancelled.cancel();
            assertThrows(java.io.InterruptedIOException.class, () -> FileTransfer.snapshot(source, directory.toFile(), cancelled));
            assertThrows(java.io.InterruptedIOException.class, () -> FileShare.describe(FileLink.parse(URL), source, "photo.jpg", "image/jpeg", cancelled));
            assertEquals(1, directory.toFile().listFiles().length, "Cancelled snapshot must not leave temporary files");
            java.io.File shortened = new java.io.File(source.getPath()) { @Override public long length() { return super.length() + 1; } };
            assertThrows(FileTransfer.Failure.class, () -> FileTransfer.snapshot(shortened, directory.toFile(), new FileTransfer.Job()));
            assertEquals(1, directory.toFile().listFiles().length, "An input that shrinks during copying must not leave a partial snapshot");
            Files.write(source.toPath(), new byte[0]);
            assertThrows(FileTransfer.Failure.class, () -> FileTransfer.snapshot(source, directory.toFile(), new FileTransfer.Job()));
        } finally {
            for (java.io.File file : directory.toFile().listFiles()) file.delete(); directory.toFile().delete();
        }
    }
}
