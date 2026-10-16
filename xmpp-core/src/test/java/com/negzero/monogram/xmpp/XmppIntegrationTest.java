package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jxmpp.jid.impl.JidCreate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONOGRAM_IT", matches = "1")
class XmppIntegrationTest {
    final File state = new File(System.getenv("MONOGRAM_IT_STATE"));
    final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    final BlockingQueue<XmppMessage> receivedMessages = new LinkedBlockingQueue<>();
    final BlockingQueue<String> profileUpdates = new LinkedBlockingQueue<>();
    final BlockingQueue<Boolean> encryption = new LinkedBlockingQueue<>();
    final int port = Integer.parseInt(System.getenv("MONOGRAM_IT_PORT"));
    XmppSession session(String username, int device, XmppSession.Listener listener) throws Exception {
        return new XmppSession(new Account(username + "@localhost", "127.0.0.1", port, "Monogram-" + device),
                "monogram-test", device, new File(state, username + "-trust"), listener);
    }
    void verifyDevices(XmppSession sender, String jid) throws Exception {
        java.util.Set<OmemoDevice> devices = sender.devices(jid);
        assertFalse(devices.isEmpty(), "Peer must publish an OMEMO device");
        for (OmemoDevice device : devices) {
            OmemoFingerprint fingerprint = sender.fingerprint(device);
            assertNotNull(fingerprint);
            sender.decide(device, fingerprint, true);
        }
    }
    XmppMessage awaitBody(BlockingQueue<XmppMessage> messages, String body) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            XmppMessage message = messages.poll(1, TimeUnit.SECONDS);
            if (message != null && body.equals(message.body)) return message;
        }
        throw new AssertionError("Expected test message was not delivered");
    }
    IQ helper(XmppSession session, String child, IQ.Type type, String attributes, Message message) throws Exception {
        IQ iq = new IQ(child, "urn:monogram:schedule:0") {
            @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                if (attributes != null) xml.append(attributes);
                xml.rightAngleBracket();
                if (message != null) xml.append(message.toXML());
                return xml;
            }
        };
        iq.setType(type);
        return session.connection().createStanzaCollectorAndSend(iq).nextResultOrThrow();
    }
    @Test void tlsPlaintextOmemoPersistenceAndOptionalScheduler() throws Exception {
        XmppSession.initializeOmemo(new File(state, "keys"));
        XmppSession.Listener ignore = (sender, id, body, encrypted) -> {};
        BlockingQueue<XmppMessage> aliceMessages = new LinkedBlockingQueue<>();
        XmppSession.Listener aliceListener = new XmppSession.Listener() {
            @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {}
            @Override public void message(XmppMessage message) { aliceMessages.add(message); }
        };
        File captionSource = new File(state, "caption-source.png"); java.nio.file.Files.write(captionSource.toPath(), new byte[]{1,2,3});
        OmemoFingerprint aliceFingerprint;
        try (XmppSession alice = session("alice", 101, aliceListener);
             XmppSession bob = session("bob", 202, new XmppSession.Listener() {
                 @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {
                     received.add(body); encryption.add(encrypted);
                 }
                 @Override public void message(XmppMessage message) { receivedMessages.add(message); XmppSession.Listener.super.message(message); }
                 @Override public void profileChanged(org.jxmpp.jid.EntityBareJid jid) { profileUpdates.add(jid.toString()); }
             })) {
            Roster.getInstanceFor(alice.connection()).setSubscriptionMode(Roster.SubscriptionMode.accept_all);
            Roster.getInstanceFor(bob.connection()).setSubscriptionMode(Roster.SubscriptionMode.accept_all);
            alice.connect(); bob.connect();
            assertTrue(alice.connection().isSecureConnection());
            assertTrue(bob.connection().isSecureConnection());
            aliceFingerprint = alice.ownFingerprint();
            Roster.getInstanceFor(alice.connection()).createItemAndRequestSubscription(JidCreate.bareFrom("bob@localhost"), "Bob", null);
            Roster.getInstanceFor(bob.connection()).createItemAndRequestSubscription(JidCreate.bareFrom("alice@localhost"), "Alice", null);
            // Profiles use only the disposable localhost accounts. Seed fields from another client.
            IQ seed = new IQ("vCard", "vcard-temp") {
                @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                    xml.attribute("version", "3.0").rightAngleBracket();
                    xml.append("<FN>Alice Original</FN><N><GIVEN>Alice</GIVEN><MIDDLE>Keep</MIDDLE><FAMILY>Original</FAMILY></N>"
                        + "<EMAIL><HOME/><USERID>home@example.org</USERID></EMAIL><EMAIL><WORK/><USERID>work@example.org</USERID></EMAIL>"
                        + "<extra xmlns='urn:example:profile'><nested>Keep me</nested></extra>"); return xml;
                }
            };
            seed.setType(IQ.Type.set); alice.connection().createStanzaCollectorAndSend(seed).nextResultOrThrow();
            XmppProfile edited = alice.profiles.edit("Alice", "Test", "A synthetic profile");
            assertEquals("Alice Test", edited.nickname);
            assertEquals("alice@localhost", profileUpdates.poll(15, TimeUnit.SECONDS), "PEP profile changes must notify subscribed contacts");
            assertEquals("A synthetic profile", bob.profiles.load("alice@localhost").field("DESC"));
            assertEquals("Keep", edited.field("N", "MIDDLE")); assertEquals(2, edited.card.getElements("EMAIL").size());
            assertNotNull(edited.card.getFirstElement("extra", "urn:example:profile"));
            assertEquals("3.0", edited.card.getAttributeValue("version"));
            byte[] avatar = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=");
            alice.profiles.avatar(avatar);
            assertArrayEquals(avatar, bob.profiles.load("alice@localhost").avatar);
            assertEquals("Alice Test", bob.profiles.load("alice@localhost").nickname);
            // A forged metadata hash must never identify unrelated data as an avatar.
            org.jivesoftware.smackx.pep.PepManager.getInstanceFor(alice.connection()).publish(XmppProfiles.META,
                new org.jivesoftware.smackx.pubsub.PayloadItem<>("bad", StandardExtensionElement.builder("metadata", XmppProfiles.META)
                    .addElement(StandardExtensionElement.builder("info", XmppProfiles.META).addAttribute("id", new String(new char[40]).replace('\0', 'a'))
                        .addAttribute("bytes", Integer.toString(avatar.length)).addAttribute("type", "image/png").build()).build()));
            // Missing/malformed PEP data falls back to the vCard; no remote URLs are fetched.
            assertArrayEquals(avatar, bob.profiles.load("alice@localhost").avatar);
            alice.profiles.avatar(new byte[0]);
            assertEquals(0, bob.profiles.load("alice@localhost").avatar.length);
            assertEquals(2, alice.profiles.load("alice@localhost").card.getElements("EMAIL").size());
            assertTrue(alice.hasArchive());
            alice.send("bob@localhost", "ordinary Jabber", false);
            assertEquals("ordinary Jabber", received.poll(15, TimeUnit.SECONDS));
            assertEquals(false, encryption.poll(1, TimeUnit.SECONDS));

            assertFalse(alice.devices("bob@localhost").isEmpty());
            assertThrows(UndecidedOmemoIdentityException.class, () -> alice.send("bob@localhost", "must not leak", true));
            assertNull(received.poll(300, TimeUnit.MILLISECONDS));
            verifyDevices(alice, "bob@localhost");
            verifyDevices(bob, "alice@localhost");
            AtomicReference<String> outgoing = new AtomicReference<>();
            alice.connection().addStanzaInterceptor(stanza -> outgoing.set(stanza.toXML().toString()), stanza -> stanza instanceof Message && (((Message) stanza).getBody() != null || stanza.hasExtension("encrypted", "eu.siacs.conversations.axolotl")));
            alice.send("bob@localhost", "encrypted hello", true);
            assertEquals("encrypted hello", received.poll(15, TimeUnit.SECONDS));
            assertEquals(true, encryption.poll(1, TimeUnit.SECONDS));
            assertTrue(outgoing.get().contains("eu.siacs.conversations.axolotl"));
            assertFalse(outgoing.get().contains("encrypted hello"));

            String originalId = bob.send("alice@localhost", "Reply original 😀", false);
            ReplyReference plainReply = new ReplyReference(bob.connection().getUser().toString(), originalId).quoted("Reply original 😀");
            String replyId = alice.send("bob@localhost", "Plain reply 🦄", false, plainReply);
            XmppMessage plainReceived = awaitBody(receivedMessages, plainReply.wireBody("Plain reply 🦄"));
            assertNotNull(plainReceived.reply); assertEquals(originalId, plainReceived.reply.id);
            assertFalse(plainReceived.reply.ranges.isEmpty(), outgoing.get());
            assertEquals("Plain reply 🦄", plainReceived.reply.withoutFallback(plainReceived.body));
            assertEquals(plainReply.wireBody("Plain reply 🦄"), received.poll(15, TimeUnit.SECONDS)); encryption.poll(1, TimeUnit.SECONDS);
            ReplyReference encryptedReply = new ReplyReference(bob.connection().getUser().toString(), originalId).quoted("Sensitive quote 😀");
            String secretReplyId = alice.send("bob@localhost", "Secret reply 🐈", true, encryptedReply);
            XmppMessage encryptedReceived = awaitBody(receivedMessages, encryptedReply.wireBody("Secret reply 🐈"));
            assertTrue(encryptedReceived.encrypted); assertNotNull(encryptedReceived.reply);
            assertEquals("Secret reply 🐈", encryptedReceived.reply.withoutFallback(encryptedReceived.body));
            assertEquals(encryptedReply.wireBody("Secret reply 🐈"), received.poll(15, TimeUnit.SECONDS)); encryption.poll(1, TimeUnit.SECONDS);
            assertFalse(outgoing.get().contains("Sensitive quote")); assertFalse(outgoing.get().contains("Secret reply"));
            assertTrue(outgoing.get().contains("urn:xmpp:reply:0"));
            assertTrue(ServiceDiscoveryManager.getInstanceFor(bob.connection()).discoverInfo(alice.connection().getUser()).containsFeature(MessageCorrection.NAMESPACE));
            for (boolean secret : new boolean[]{false, true}) {
                String corrected = secret ? secretReplyId : replyId;
                for (int edit = 1; edit <= 2; edit++) {
                    String text = "Corrected reply " + secret + " #" + edit + " 😀";
                    String eventId = alice.correct("bob@localhost", text, secret, plainReply, new MessageCorrection(corrected));
                    XmppMessage event = awaitBody(receivedMessages, plainReply.wireBody(text));
                    assertEquals(secret, event.encrypted); assertEquals(corrected, event.correction.id);
                    assertEquals(eventId, event.ids.get("stanza")); assertNotEquals(corrected, eventId);
                    assertEquals(originalId, event.reply.id);
                    assertEquals(plainReply.wireBody(text), received.poll(15, TimeUnit.SECONDS)); encryption.poll(1, TimeUnit.SECONDS);
                    if (secret) assertFalse(outgoing.get().contains(text));
                }
            }
            for (boolean encryptedFile : new boolean[]{false, true}) {
                String url = encryptedFile ? "aesgcm://localhost/reply.png#" + new String(new char[88]).replace('\0', 'a') : "https://localhost/reply.png";
                ReplyReference fileReply = new ReplyReference(bob.connection().getUser().toString(), originalId).quoted("Reply original 😀");
                alice.sendFile("bob@localhost", FileLink.parse(url), encryptedFile, fileReply);
                XmppMessage fileMessage = awaitBody(receivedMessages, fileReply.wireBody(url));
                assertNotNull(fileMessage.attachment, "Reply fallback must not prevent attachment recognition after wire parsing");
                assertEquals(encryptedFile, fileMessage.encrypted); assertEquals(originalId, fileMessage.reply.id);
                assertEquals(fileReply.wireBody(url), received.poll(15, TimeUnit.SECONDS)); encryption.poll(1, TimeUnit.SECONDS);
            }
            FileShare share = FileShare.describe(FileLink.parse("https://localhost/caption.png"), captionSource, "caption.png", "image/png");
            String captionId = alice.sendFile("bob@localhost", share, "Photo caption 😀", plainReply);
            XmppMessage captioned = awaitBody(receivedMessages, plainReply.wireBody(share.body("Photo caption 😀")));
            assertEquals("Photo caption 😀", captioned.caption); assertEquals(3, captioned.sharing.size);
            assertEquals(plainReply.wireBody(share.body("Photo caption 😀")), received.poll(15, TimeUnit.SECONDS)); encryption.poll(1, TimeUnit.SECONDS);
            bob.sendFile("alice@localhost", share, "Back the other way 🦄", null);
            assertEquals("Back the other way 🦄", awaitBody(aliceMessages, share.body("Back the other way 🦄")).caption);
            java.util.List<XmppMessage> replyArchive = alice.archiveBefore(null, null, 100).messages;
            assertTrue(replyArchive.stream().anyMatch(m -> replyId.equals(m.ids.get("origin")) && m.reply != null && originalId.equals(m.reply.id)));
            assertTrue(replyArchive.stream().anyMatch(m -> secretReplyId.equals(m.ids.get("origin")) && m.reply != null && originalId.equals(m.reply.id)));
            assertEquals(4, replyArchive.stream().filter(m -> m.correction != null && (replyId.equals(m.correction.id) || secretReplyId.equals(m.correction.id))).count());
            assertTrue(replyArchive.stream().anyMatch(m -> captionId.equals(m.ids.get("stanza")) && "Photo caption 😀".equals(m.caption) && m.sharing != null));
            assertTrue(ServiceDiscoveryManager.getInstanceFor(alice.connection()).serverSupportsFeature("urn:monogram:schedule:0"));
            Message scheduled = new Message(JidCreate.entityBareFrom("bob@localhost"), Message.Type.chat);
            scheduled.setStanzaId("scheduled-test"); scheduled.setBody("scheduled hello");
            // The module must replace any forged sender with the authenticated account.
            scheduled.setFrom(JidCreate.entityBareFrom("mallory@localhost"));
            IQ queued = helper(alice, "schedule", IQ.Type.set, " at='" + (System.currentTimeMillis() / 1000 + 2) + "'", scheduled);
            assertTrue(queued.toXML().toString().contains("scheduled"));
            assertEquals("scheduled hello", received.poll(15, TimeUnit.SECONDS));
            assertEquals(false, encryption.poll(1, TimeUnit.SECONDS));
            IQ listed = helper(alice, "scheduled", IQ.Type.get, null, null);
            assertFalse(listed.toXML().toString().contains("<item"));

            IQ cancelled = helper(alice, "schedule", IQ.Type.set, " at='" + (System.currentTimeMillis() / 1000 + 60) + "'", scheduled);
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("id=['\"]([^'\"]+)['\"]").matcher(((UnparsedIQ)cancelled).getContent());
            assertTrue(id.find());
            String jobId = id.group(1);
            assertThrows(Exception.class, () -> helper(bob, "cancel", IQ.Type.set, " id='" + jobId + "'", null));
            helper(alice, "cancel", IQ.Type.set, " id='" + jobId + "'", null);
            assertFalse(helper(alice, "scheduled", IQ.Type.get, null, null).toXML().toString().contains("<item"));
        }
        try (XmppSession restarted = session("alice", 101, ignore)) {
            restarted.connect();
            assertEquals(aliceFingerprint, restarted.ownFingerprint(), "Restart must preserve device identity");
            restarted.send("bob@localhost", "offline encrypted delivery", true);
        }
        try (XmppSession bob = session("bob", 202, (sender, id, body, encrypted) -> { received.add(body); encryption.add(encrypted); })) {
            bob.connect();
            assertEquals("offline encrypted delivery", received.poll(15, TimeUnit.SECONDS));
            assertEquals(true, encryption.poll(1, TimeUnit.SECONDS));
        }
        BlockingQueue<XmppMessage> copies = new LinkedBlockingQueue<>();
        XmppSession.Listener observer = new XmppSession.Listener() {
            @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {}
            @Override public void message(XmppMessage message) { copies.add(message); }
        };
        try (XmppSession alice = session("alice", 101, ignore);
             XmppSession bob = session("bob", 202, (sender, id, body, encrypted) -> { received.add(body); encryption.add(encrypted); });
             XmppSession second = session("alice", 303, observer)) {
            alice.connect(); bob.connect(); second.connect();
            String sentId = alice.send("bob@localhost", "from another device", false);
            XmppMessage sent = copies.poll(15, TimeUnit.SECONDS);
            assertNotNull(sent); assertTrue(sent.outgoing); assertFalse(sent.encrypted);
            assertEquals("from another device", sent.body);
            assertEquals(sentId, sent.ids.get("stanza"));
            assertEquals("bob@localhost", sent.peer.toString());
            Message targeted = new Message(alice.connection().getUser(), Message.Type.chat);
            targeted.setStanzaId("carbon-incoming"); targeted.setBody("to another device");
            bob.connection().sendStanza(targeted);
            XmppMessage incoming = copies.poll(15, TimeUnit.SECONDS);
            assertNotNull(incoming); assertFalse(incoming.outgoing);
            assertEquals("to another device", incoming.body);
            assertEquals("bob@localhost", incoming.peer.toString());

            ReplyReference carbonReply = new ReplyReference(bob.connection().getUser().toString(), "carbon-incoming").quoted("to another device");
            alice.send("bob@localhost", "Reply from another device", false, carbonReply);
            XmppMessage replyCopy = copies.poll(15, TimeUnit.SECONDS);
            assertNotNull(replyCopy); assertTrue(replyCopy.outgoing); assertNotNull(replyCopy.reply);
            assertEquals("carbon-incoming", replyCopy.reply.id);
            assertEquals("Reply from another device", replyCopy.reply.withoutFallback(replyCopy.body));
            alice.correct("bob@localhost", "Corrected on another device", false, null, new MessageCorrection(sentId));
            XmppMessage correctionCopy = copies.poll(15, TimeUnit.SECONDS);
            assertNotNull(correctionCopy); assertTrue(correctionCopy.outgoing);
            assertEquals(sentId, correctionCopy.correction.id); assertEquals("Corrected on another device", correctionCopy.body);
            FileShare carbonShare = FileShare.describe(FileLink.parse("https://localhost/carbon.png"), captionSource, "carbon.png", "image/png");
            alice.sendFile("bob@localhost", carbonShare, "Captioned carbon", null);
            XmppMessage fileCopy = copies.poll(15, TimeUnit.SECONDS);
            assertNotNull(fileCopy); assertTrue(fileCopy.outgoing); assertEquals("Captioned carbon", fileCopy.caption);
            Message forged = new Message(second.connection().getUser(), Message.Type.chat);
            Message inner = new Message(JidCreate.entityBareFrom("alice@localhost"), Message.Type.chat);
            inner.setFrom(JidCreate.entityBareFrom("mallory@localhost")); inner.setBody("forged carbon");
            forged.addExtension(new org.jivesoftware.smackx.carbons.packet.CarbonExtension(
                    org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.received,
                    new org.jivesoftware.smackx.forward.packet.Forwarded<Message>(inner)));
            bob.connection().sendStanza(forged);
            assertNull(copies.poll(500, TimeUnit.MILLISECONDS), "A contact must not be able to forge a carbon");
            alice.connection().sendStanza(forged);
            assertNull(copies.poll(500, TimeUnit.MILLISECONDS), "Another client resource is not an authoritative carbon sender");

            java.util.Map<String, XmppMessage> archive = new java.util.LinkedHashMap<>();
            String before = null;
            long previous = Long.MAX_VALUE;
            for (int pageNumber = 0; pageNumber < 30; pageNumber++) {
                XmppSession.ArchivePage page = second.archiveBefore("bob@localhost", before, 2);
                assertTrue(page.messages.size() <= 2);
                for (int i = page.messages.size() - 1; i >= 0; i--) {
                    XmppMessage message = page.messages.get(i);
                    assertTrue(message.archived);
                    assertEquals("bob@localhost", message.peer.toString());
                    assertTrue(message.timestamp <= previous, "Archive timestamps must retain their order");
                    previous = message.timestamp;
                    assertNull(archive.put(message.ids.get("archive"), message), "Paging must not repeat an archive UID");
                }
                if (page.complete || page.firstId == null) break;
                assertNotEquals(before, page.firstId); before = page.firstId;
            }
            assertTrue(archive.values().stream().anyMatch(m -> "ordinary Jabber".equals(m.body) && m.outgoing));
            assertTrue(archive.values().stream().anyMatch(m -> sentId.equals(m.ids.get("stanza")) && m.outgoing));
            assertTrue(archive.values().stream().anyMatch(m -> "to another device".equals(m.body) && !m.outgoing));
            assertTrue(archive.values().stream().anyMatch(m -> m.encrypted && !m.readable && m.body == null),
                    "A new device must identify encrypted history it cannot decrypt");
            String oldest = new java.util.ArrayList<>(archive.keySet()).get(archive.size() - 1);
            java.util.Set<String> forwardIds = new java.util.HashSet<>();
            String after = oldest;
            for (int pageNumber = 0; pageNumber < 30; pageNumber++) {
                XmppSession.ArchivePage page = second.archiveAfter(after, 2);
                for (XmppMessage message : page.messages) assertTrue(forwardIds.add(message.ids.get("archive")));
                if (page.complete || page.lastId == null) break;
                assertNotEquals(after, page.lastId); after = page.lastId;
            }
            java.util.Set<String> expected = new java.util.HashSet<>(archive.keySet()); expected.remove(oldest);
            assertEquals(expected, forwardIds, "Forward catch-up must cover the same archive without repeating its anchor");
            String bobBefore = null;
            for (int pageNumber = 0; pageNumber < 30; pageNumber++) {
                XmppSession.ArchivePage page = bob.archiveBefore(null, bobBefore, 2);
                if (page.complete || page.firstId == null) break;
                assertNotEquals(bobBefore, page.firstId); bobBefore = page.firstId;
            }
            verifyDevices(alice, "alice@localhost");
            received.clear(); encryption.clear();
            alice.send("bob@localhost", "live encryption after archive replay", true);
            assertEquals("live encryption after archive replay", received.poll(15, TimeUnit.SECONDS));
            assertEquals(true, encryption.poll(1, TimeUnit.SECONDS));

            byte[] image = new byte[65537]; new java.security.SecureRandom().nextBytes(image);
            java.nio.file.Path input = new File(state, "test-image.jpg").toPath(); java.nio.file.Files.write(input, image);
            java.util.concurrent.atomic.AtomicLong progress = new java.util.concurrent.atomic.AtomicLong();
            copies.clear();
            FileLink plain = FileTransfer.upload(alice.connection(), input.toFile(), "image/jpeg", false, new FileTransfer.Job(),
                    (done, total) -> progress.set(done));
            assertEquals(image.length, progress.get());
            alice.sendFile("bob@localhost", plain, false);
            XmppMessage plainFile = awaitBody(copies, plain.wireUrl);
            assertNotNull(plainFile); assertNotNull(plainFile.attachment); assertFalse(plainFile.attachment.encrypted());
            java.nio.file.Path downloaded = new File(state, "downloaded.jpg").toPath();
            FileTransfer.download(plainFile.attachment, downloaded.toFile(), new FileTransfer.Job(), null);
            assertArrayEquals(image, java.nio.file.Files.readAllBytes(downloaded));

            copies.clear(); AtomicReference<String> encryptedWire = new AtomicReference<>();
            alice.connection().addStanzaInterceptor(stanza -> encryptedWire.set(stanza.toXML().toString()), stanza -> stanza instanceof Message);
            FileLink secured = FileTransfer.upload(alice.connection(), input.toFile(), "image/jpeg", true, new FileTransfer.Job(), null);
            assertTrue(secured.encrypted()); assertNull(secured.downloadUrl.getRef());
            alice.sendFile("bob@localhost", secured, true);
            XmppMessage encryptedFile = awaitBody(copies, secured.wireUrl);
            assertNotNull(encryptedFile); assertNotNull(encryptedFile.attachment); assertTrue(encryptedFile.encrypted);
            assertFalse(encryptedWire.get().contains(secured.wireUrl));
            assertFalse(encryptedWire.get().contains(secured.wireUrl.substring(secured.wireUrl.indexOf('#') + 1)));
            FileTransfer.download(encryptedFile.attachment, downloaded.toFile(), new FileTransfer.Job(), null);
            assertArrayEquals(image, java.nio.file.Files.readAllBytes(downloaded));
            for (FileLink media : new FileLink[]{plain, secured}) {
                assertTrue(FileTransfer.download(media, downloaded.toFile(), new FileTransfer.Job(), null, (type, size) -> image.length));
                assertArrayEquals(image, java.nio.file.Files.readAllBytes(downloaded));
                assertFalse(FileTransfer.download(media, downloaded.toFile(), new FileTransfer.Job(), null, (type, size) -> image.length - 1));
                assertFalse(FileTransfer.download(media, downloaded.toFile(), new FileTransfer.Job(), null, (type, size) -> 1024));
                assertArrayEquals(image, java.nio.file.Files.readAllBytes(downloaded), "Skipped media must preserve cached bytes");
            }
            java.nio.file.Path wireBytes = new File(state, "ciphertext").toPath();
            FileTransfer.download(FileLink.parse(secured.downloadUrl.toString()), wireBytes.toFile(), new FileTransfer.Job(), null);
            byte[] ciphertext = java.nio.file.Files.readAllBytes(wireBytes);
            assertEquals(image.length + 16, ciphertext.length);
            javax.crypto.Cipher oracle = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
            oracle.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(secured.key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, secured.iv));
            assertArrayEquals(image, oracle.doFinal(ciphertext), "Ciphertext must also decrypt with an independent JCE provider");
            String keyUrl = secured.wireUrl;
            FileLink wrongKey = FileLink.parse(keyUrl.substring(0, keyUrl.length() - 1) + (keyUrl.endsWith("0") ? "1" : "0"));
            assertEquals("AUTHENTICATION", assertThrows(FileTransfer.Failure.class,
                    () -> FileTransfer.download(wrongKey, downloaded.toFile(), new FileTransfer.Job(), null)).reason);
            assertArrayEquals(image, java.nio.file.Files.readAllBytes(downloaded), "Failed authentication must not replace the prior file");
            assertEquals(0, java.util.Arrays.stream(state.listFiles()).filter(f -> f.getName().endsWith(".part")).count());
            java.nio.file.Path large = new File(state, "too-large.bin").toPath(); java.nio.file.Files.write(large, new byte[1024*1024 + 1]);
            assertEquals("SERVER_SIZE_LIMIT", assertThrows(FileTransfer.Failure.class,
                    () -> FileTransfer.upload(alice.connection(), large.toFile(), "application/octet-stream", false, new FileTransfer.Job(), null)).reason);
            FileTransfer.Job cancelled = new FileTransfer.Job(); cancelled.cancel();
            assertThrows(java.io.InterruptedIOException.class, () -> FileTransfer.download(secured, downloaded.toFile(), cancelled, null));
            FileTransfer.Job interrupted = new FileTransfer.Job();
            assertThrows(java.io.IOException.class, () -> FileTransfer.download(secured, downloaded.toFile(), interrupted,
                    (bytes, total) -> interrupted.cancel()));
            assertTrue(interrupted.cancelled());
            assertArrayEquals(image, java.nio.file.Files.readAllBytes(downloaded), "Cancellation must preserve the verified file");
            assertEquals(0, java.util.Arrays.stream(state.listFiles()).filter(f -> f.getName().endsWith(".part")).count());
        }
    }
}
