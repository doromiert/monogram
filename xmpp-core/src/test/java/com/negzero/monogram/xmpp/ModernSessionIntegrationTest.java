package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
class ModernSessionIntegrationTest {
    static final class Inbox implements XmppSession.Listener {
        final BlockingQueue<XmppMessage> messages=new LinkedBlockingQueue<>();
        final BlockingQueue<XmppMessage> pending=new LinkedBlockingQueue<>();
        final BlockingQueue<Runnable> accepted=new LinkedBlockingQueue<>();
        @Override public void message(org.jxmpp.jid.EntityBareJid jid,String id,String body,boolean encrypted) {}
        @Override public void message(XmppMessage message) { messages.add(message); }
        @Override public void messagePending(XmppMessage message) { pending.add(message); }
        @Override public void message(XmppMessage message,Runnable stored) { messages.add(message); accepted.add(stored); }
        XmppMessage next() throws Exception { XmppMessage message=messages.poll(15,TimeUnit.SECONDS); assertNotNull(message); return message; }
        void store() throws Exception { Runnable stored=accepted.poll(5,TimeUnit.SECONDS); assertNotNull(stored); stored.run(); }
    }
    private XmppSession session(String user,int id,boolean modern,Inbox inbox) throws Exception {
        File state=new File(System.getenv("MONOGRAM_IT_STATE")); XmppSession.initializeOmemo(new File(state,"keys"));
        Account account=new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Session-"+id);
        return new XmppSession(account,"monogram-test",id,new File(state,user+".trust"),
                modern ? new Omemo2Device(user+"@localhost",id+1000,new Omemo2TestDatabase()) : null,inbox);
    }
    private void trust(XmppSession own,String peer) throws Exception {
        for (Map.Entry<Integer,Omemo2Bundle> device:own.modern().discover(peer).entrySet()) own.modern().device.decide(peer,device.getKey(),device.getValue().identity(),true);
    }
    private MessageMarkup formatted(String body) {
        return new MessageMarkup(body,java.util.Collections.singletonList(new MessageMarkup.Range(0,body.codePointCount(0,body.length()),java.util.Collections.singleton(MessageMarkup.Style.STRONG),null))).styled();
    }
    @Test void nativeSessionDeliversCaptionsAndWaitsForStorageBeforeAcknowledging() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try (XmppSession alice=session("sessionalice",411,true,a); XmppSession bob=session("sessionbob",422,true,b)) {
            alice.connect(); bob.connect(); assertTrue(alice.modernAvailable()); assertTrue(bob.modernAvailable());
            trust(alice,"sessionbob@localhost"); trust(bob,"sessionalice@localhost");
            trust(alice,"sessionalice@localhost");trust(bob,"sessionbob@localhost");
            BlockingQueue<Message> controls=new LinkedBlockingQueue<>();
            alice.connection().addAsyncStanzaListener(stanza -> controls.add((Message)stanza),stanza -> stanza instanceof Message
                    && stanza.hasExtension("encrypted",XmppOmemo2.NS)
                    && ((StandardExtensionElement)stanza.getExtension("encrypted",XmppOmemo2.NS)).getFirstElement("payload",XmppOmemo2.NS)==null);
            MessageMarkup plainMarkup=formatted("Plain styled 😀");
            alice.send("sessionbob@localhost",plainMarkup.body,XmppMessage.Encryption.PLAIN,null,plainMarkup);
            XmppMessage plainStyled=b.next();assertEquals("Plain styled 😀",plainStyled.markup.display().body);assertFalse(plainStyled.markup.ranges.isEmpty());b.store();
            java.util.concurrent.BlockingQueue<Message> protectedWires=new LinkedBlockingQueue<>();
            alice.connection().addStanzaInterceptor(stanza -> protectedWires.add((Message)stanza),stanza -> stanza instanceof Message
                    && stanza.hasExtension("encrypted",XmppOmemo2.NS) && ((StandardExtensionElement)stanza.getExtension("encrypted",XmppOmemo2.NS)).getFirstElement("payload",XmppOmemo2.NS)!=null);
            ReplyReference reply=new ReplyReference("sessionbob@localhost","original");
            MessageMarkup textMarkup=formatted("Modern native session 😀");
            String id=alice.send("sessionbob@localhost",textMarkup.body,XmppMessage.Encryption.OMEMO2,reply,textMarkup);
            XmppMessage first=b.next(); assertTrue(first.readable,() -> "Modern decrypt failed: "+bob.modernFailure()); assertEquals(XmppMessage.Encryption.OMEMO2,first.encryption);
            XmppMessage placeholder=b.pending.poll(5,TimeUnit.SECONDS);assertNotNull(placeholder);assertFalse(placeholder.readable);assertEquals(id,placeholder.ids.get("stanza"));
            assertEquals("Modern native session 😀",first.markup.display().body);assertFalse(first.markup.ranges.isEmpty());
            Message protectedWire=protectedWires.poll(5,TimeUnit.SECONDS);assertNotNull(protectedWire);
            assertFalse(protectedWire.hasExtension("markup",MessageMarkup.NAMESPACE));assertFalse(protectedWire.hasExtension("unstyled",MessageMarkup.STYLING));
            assertEquals("original",first.reply.id); assertEquals(id,first.ids.get("stanza"));
            assertNull(controls.poll(200,TimeUnit.MILLISECONDS),"No ACK before the Android adapter commits the message");
            b.store(); assertNotNull(controls.poll(10,TimeUnit.SECONDS));
            String url="aesgcm://example.org/friends.png#"+new String(new char[88]).replace('\0','a');
            FileShare sharing=new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>Friends.png</name><media-type>image/png</media-type></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+url+"'/></sources></file-sharing>");
            MessageMarkup captionMarkup=formatted("One private caption 🦄");
            String media=alice.sendFile("sessionbob@localhost",sharing,captionMarkup.body,reply,captionMarkup);
            XmppMessage photo=b.next(); assertTrue(photo.readable); assertEquals("One private caption 🦄",photo.captionMarkup.display().body);assertFalse(photo.captionMarkup.ranges.isEmpty());
            assertEquals(url,photo.attachment.wireUrl); assertEquals(XmppMessage.Encryption.OMEMO2,photo.encryption); b.store();
            assertEquals("One private caption 🦄",XmppMessage.sentFile(alice.connection().getUser(),"sessionbob@localhost",media,sharing,"One private caption 🦄",reply).caption);
            MessageMarkup editMarkup=formatted("Modern edited text");
            alice.correct("sessionbob@localhost",editMarkup.body,XmppMessage.Encryption.OMEMO2,reply,new MessageCorrection(id),editMarkup);
            XmppMessage edit=b.next(); assertEquals(id,edit.correction.id); assertEquals("Modern edited text",edit.markup.display().body); b.store();
            MessageMarkup captionEdit=formatted("Edited private caption");
            alice.correctFile("sessionbob@localhost",sharing,captionEdit.body,XmppMessage.Encryption.OMEMO2,reply,new MessageCorrection(media),captionEdit);
            XmppMessage correctedPhoto=b.next();assertEquals(media,correctedPhoto.correction.id);assertEquals("Edited private caption",correctedPhoto.captionMarkup.display().body);b.store();
            XmppSession.ArchivePage page=bob.archiveBefore("sessionalice@localhost",null,100);
            XmppMessage archived=page.messages.stream().filter(m -> id.equals(m.ids.get("stanza"))).findFirst().orElseThrow(AssertionError::new);
            assertEquals(XmppMessage.Encryption.OMEMO2,archived.encryption); assertFalse(archived.readable); page.stored(); page.stored();
            bob.sendReactions("sessionalice@localhost",new MessageReactions(id,java.util.Arrays.asList("👍","🐢")),XmppMessage.Encryption.PLAIN);
            XmppMessage plainReaction=a.next(); assertNull(plainReaction.body); assertFalse(plainReaction.encrypted); assertEquals(id,plainReaction.reactions.id);
            for(java.util.List<String> emojis:java.util.Arrays.asList(java.util.Arrays.asList("❤️","🔥"),java.util.Collections.<String>emptyList())) {
                bob.sendReactions("sessionalice@localhost",new MessageReactions(id,emojis),XmppMessage.Encryption.OMEMO2);
                XmppMessage reaction=a.next(); assertNull(reaction.body); assertTrue(reaction.encrypted); assertEquals(id,reaction.reactions.id); assertEquals(new java.util.LinkedHashSet<>(emojis),reaction.reactions.emojis); a.store();
            }
            assertThrows(UnsupportedOperationException.class,() -> bob.sendReactions("sessionalice@localhost",new MessageReactions(id,java.util.Collections.singleton("👍")),XmppMessage.Encryption.LEGACY_OMEMO));
            alice.modern().close();
            OmemoSendException failure=assertThrows(OmemoSendException.class,() -> alice.send("sessionbob@localhost","Never downgrade",XmppMessage.Encryption.OMEMO2,null));
            assertEquals(OmemoSendException.Reason.MODERN_UNAVAILABLE,failure.reason); assertNull(b.messages.poll(200,TimeUnit.MILLISECONDS));
        }
    }
    @Test void unsupportedModernPayloadIsStoredAsEncryptedInsteadOfItsPublicFallback() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try (XmppSession alice=session("fallbackalice",433,false,a); XmppSession bob=session("fallbackbob",444,false,b)) {
            alice.connect(); bob.connect();
            Message packet=new Message(JidCreate.entityBareFrom("fallbackbob@localhost"),Message.Type.chat); packet.setStanzaId("unsupported-modern");
            packet.setBody("This message is OMEMO encrypted."); packet.addExtension(StandardExtensionElement.builder("encrypted",XmppOmemo2.NS).addElement("payload","invalid-test-content").build());
            new MessageCorrection("forged-edit").addTo(packet); alice.connection().sendStanza(packet);
            XmppMessage received=b.next(); assertFalse(received.readable); assertTrue(received.encrypted);
            assertEquals(XmppMessage.Encryption.OMEMO2,received.encryption); assertNull(received.body); assertNull(received.correction); b.store();
            XmppSession.ArchivePage page=bob.archiveBefore("fallbackalice@localhost",null,100);
            assertTrue(page.messages.stream().anyMatch(m -> m.encryption==XmppMessage.Encryption.OMEMO2 && !m.readable && m.body==null));
        }
    }
    @Test void ciphertextDuringHeldInitialSetupWaitsForReadinessAndStorageAcceptance() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try(XmppSession alice=session("sessionalice",7711,true,a)) {
            alice.connect();Omemo2Device bobDevice;
            // Publish the same durable Bob identity first, as a returning installation would have done.
            try(Omemo2IntegrationTest.Client seed=new Omemo2IntegrationTest.Client("sessionbob",7722)) {
                seed.trust("sessionalice@localhost");trust(alice,"sessionbob@localhost");trust(alice,"sessionalice@localhost");bobDevice=seed.device;
            }
            File state=new File(System.getenv("MONOGRAM_IT_STATE"));
            try(XmppSession bob=new XmppSession(new Account("sessionbob@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"HeldSetup"),
                    "monogram-test",7722,new File(state,"held-setup-bob.trust"),bobDevice,b)) {
                java.util.concurrent.CountDownLatch setupEntered=new java.util.concurrent.CountDownLatch(1),releaseSetup=new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean held=new java.util.concurrent.atomic.AtomicBoolean();
                bob.connection().addStanzaInterceptor(stanza -> {
                    String xml=stanza.toXML().toString();
                    if(xml.contains("<publish") && xml.contains(XmppOmemo2.BUNDLES_NODE) && held.compareAndSet(false,true)) {
                        setupEntered.countDown();
                        try { if(!releaseSetup.await(10,TimeUnit.SECONDS)) throw new AssertionError("Held setup was not released"); }
                        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
                    }
                },stanza -> stanza instanceof org.jivesoftware.smack.packet.IQ);
                java.util.concurrent.ExecutorService connector=java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                    java.util.concurrent.Future<?> connected=connector.submit(() -> { try { bob.connect(); } catch(Exception error) { throw new RuntimeException(error); } });
                    assertTrue(setupEntered.await(10,TimeUnit.SECONDS));assertFalse(bob.modernAvailable());
                    java.util.concurrent.Future<XmppSession.ArchivePage> archive=connector.submit(() -> bob.archiveBefore("sessionalice@localhost",null,10));
                    BlockingQueue<Message> controls=new LinkedBlockingQueue<>();
                    alice.connection().addAsyncStanzaListener(stanza -> controls.add((Message)stanza),stanza -> stanza instanceof Message
                            && stanza.hasExtension("encrypted",XmppOmemo2.NS)
                            && ((StandardExtensionElement)stanza.getExtension("encrypted",XmppOmemo2.NS)).getFirstElement("payload",XmppOmemo2.NS)==null);
                    Message clear=new Message(JidCreate.entityFullFrom("sessionbob@localhost/HeldSetup"),Message.Type.chat);
                    clear.setFrom(alice.connection().getUser());clear.setStanzaId("during-held-setup");clear.setBody("Arrived before readiness 😀");
                    alice.connection().sendStanza(alice.modern().encrypt(clear));
                    XmppMessage pending=b.pending.poll(5,TimeUnit.SECONDS);assertNotNull(pending);assertEquals(clear.getStanzaId(),pending.ids.get("stanza"));assertFalse(pending.readable);
                    assertNull(b.messages.poll(300,TimeUnit.MILLISECONDS),"Setup in progress must not publish a terminal unreadable result");
                    assertFalse(archive.isDone(),"MAM decoding must wait for encryption setup outside the crypto executor");
                    assertNull(controls.poll(100,TimeUnit.MILLISECONDS));
                    releaseSetup.countDown();connected.get(10,TimeUnit.SECONDS);
                    XmppMessage delivered=b.next();assertTrue(delivered.readable,() -> "Modern setup drain failed: "+bob.modernFailure());
                    assertEquals(clear.getStanzaId(),delivered.ids.get("stanza"));assertEquals(clear.getBody(),delivered.body);
                    XmppSession.ArchivePage cachedPage=archive.get(15,TimeUnit.SECONDS);
                    XmppMessage cached=cachedPage.messages.stream().filter(message -> clear.getStanzaId().equals(message.ids.get("stanza"))).findFirst().orElseThrow(AssertionError::new);
                    assertTrue(cached.readable);assertTrue(cached.archived);assertEquals(delivered.ids.get("omemo2-cipher"),cached.ids.get("omemo2-cipher"));
                    assertNull(controls.poll(200,TimeUnit.MILLISECONDS),"Queued decrypt must still wait for durable acceptance");
                    b.store();cachedPage.stored();assertNotNull(controls.poll(10,TimeUnit.SECONDS));
                } finally { releaseSetup.countDown();connector.shutdownNow();assertTrue(connector.awaitTermination(10,TimeUnit.SECONDS)); }
            }
        }
    }
    @Test void committedIncomingContentRecoversOfflineAndAcceptanceAfterCloseReplaysOnlyStableAckOnNewResource() throws Exception {
        Inbox a=new Inbox(),b=new Inbox(),recovery=new Inbox();
        try(XmppSession alice=session("sessionalice",8811,true,a);XmppSession bob=session("sessionbob",8812,true,b)) {
            alice.connect();bob.connect();trust(alice,"sessionbob@localhost");trust(bob,"sessionalice@localhost");trust(alice,"sessionalice@localhost");
            BlockingQueue<Message> controls=new LinkedBlockingQueue<>();
            alice.connection().addAsyncStanzaListener(stanza -> controls.add((Message)stanza),stanza -> stanza instanceof Message
                    && stanza.hasExtension("encrypted",XmppOmemo2.NS)
                    && ((StandardExtensionElement)stanza.getExtension("encrypted",XmppOmemo2.NS)).getFirstElement("payload",XmppOmemo2.NS)==null);
            java.util.concurrent.atomic.AtomicReference<Message> wire=new java.util.concurrent.atomic.AtomicReference<>();
            alice.connection().addStanzaInterceptor(stanza -> wire.set((Message)stanza),stanza -> stanza instanceof Message
                    && stanza.hasExtension("encrypted",XmppOmemo2.NS) && ((StandardExtensionElement)stanza.getExtension("encrypted",XmppOmemo2.NS)).getFirstElement("payload",XmppOmemo2.NS)!=null);
            MessageMarkup markup=formatted("Journal survives 😀");
            String id=alice.send("sessionbob@localhost",markup.body,XmppMessage.Encryption.OMEMO2,null,markup);
            XmppMessage delivered=b.next();assertTrue(delivered.readable);assertEquals(id,delivered.ids.get("stanza"));
            Runnable acceptedAfterClose=b.accepted.poll(5,TimeUnit.SECONDS);assertNotNull(acceptedAfterClose);
            // Smack omits the client's outbound from; a real MAM stanza has the server-supplied route.
            Message mamWire=new Message(wire.get());mamWire.setFrom(JidCreate.from(delivered.sender));mamWire.setTo(JidCreate.entityBareFrom("sessionbob@localhost"));
            XmppOmemo2.Received mamRetry=bob.modern().receive(mamWire,new java.util.Date(delivered.timestamp),"journal-mam");
            assertEquals(delivered.body,mamRetry.message.body);assertEquals("journal-mam",mamRetry.message.ids.get("archive"));
            assertEquals(delivered.ids.get("omemo2-cipher"),mamRetry.message.ids.get("omemo2-cipher"));
            Omemo2Device durableDevice=bob.modern().device;bob.close();
            File state=new File(System.getenv("MONOGRAM_IT_STATE"));
            try(XmppSession reopened=new XmppSession(new Account("sessionbob@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Journal-Recovered"),
                    "monogram-test",8812,new File(state,"journal-recovery.trust"),durableDevice,recovery)) {
                reopened.recoverIncoming();
                XmppMessage restored=recovery.next();assertEquals(delivered.ids,restored.ids);assertEquals(delivered.timestamp,restored.timestamp);
                assertEquals(delivered.body,restored.body);assertEquals("Journal survives 😀",restored.markup.display().body);
                assertFalse(reopened.connection().isConnected(),"Recovery must not require network authentication");
                assertNull(controls.poll(200,TimeUnit.MILLISECONDS));
                acceptedAfterClose.run();
                XmppOmemo2.Received ackOnly=reopened.modern().pendingIncoming().get(0);assertNull(ackOnly.message);assertNull(ackOnly.content);
                String ackId=ackOnly.acknowledgement.getStanzaId();assertNotNull(ackId);
                reopened.recoverIncoming();assertNull(recovery.messages.poll(200,TimeUnit.MILLISECONDS),"ACK-only replay must not create another user message");
                reopened.connect();
                Message ack=controls.poll(10,TimeUnit.SECONDS);assertNotNull(ack);assertEquals(ackId,ack.getStanzaId());
                assertEquals("sessionbob@localhost",ack.getFrom().asBareJid().toString());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!reopened.modern().pendingIncoming().isEmpty() && System.nanoTime()<deadline) Thread.sleep(20);
                assertTrue(reopened.modern().pendingIncoming().isEmpty());
            }
        }
    }
    @Test void fullIncomingJournalRetainsLiveCiphertextUntilStorageAcceptanceReleasesCapacity() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try(XmppSession alice=session("sessionalice",9911,true,a);XmppSession bob=session("sessionbob",9912,true,b)) {
            alice.connect();bob.connect();trust(alice,"sessionbob@localhost");trust(bob,"sessionalice@localhost");trust(alice,"sessionalice@localhost");
            java.util.Set<String> sent=new java.util.HashSet<>();
            for(int i=0;i<=Omemo2IncomingJournal.MAX_ENTRIES;i++)
                sent.add(alice.send("sessionbob@localhost","Backpressure "+i,XmppMessage.Encryption.OMEMO2,null));
            java.util.Set<String> delivered=new java.util.HashSet<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(delivered.size()<Omemo2IncomingJournal.MAX_ENTRIES && System.nanoTime()<deadline) {
                XmppMessage message=b.messages.poll(1,TimeUnit.SECONDS);if(message==null) continue;
                assertTrue(message.readable,() -> "Capacity must not turn ciphertext into terminal unreadable: "+bob.modernFailure());
                assertTrue(sent.contains(message.ids.get("stanza")));delivered.add(message.ids.get("stanza"));
            }
            assertEquals(Omemo2IncomingJournal.MAX_ENTRIES,delivered.size());
            java.util.Set<String> queued=new java.util.HashSet<>(sent);queued.removeAll(delivered);assertEquals(1,queued.size());String waiting=queued.iterator().next();
            assertEquals(Omemo2IncomingJournal.MAX_ENTRIES,bob.modern().pendingIncoming().size());
            java.lang.reflect.Field blockedField=XmppSession.class.getDeclaredField("journalBlocked"),lockField=XmppSession.class.getDeclaredField("modernInboxLock");
            blockedField.setAccessible(true);lockField.setAccessible(true);Object lock=lockField.get(bob);boolean blocked=false;
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline) {
                synchronized(lock) { blocked=blockedField.getBoolean(bob); }
                if(blocked) break;Thread.sleep(20);
            }
            assertTrue(blocked,"The extra ciphertext must actually encounter FullException before capacity is released");
            java.lang.reflect.Method beginSetup=XmppSession.class.getDeclaredMethod("beginModernSetup"),finishSetup=XmppSession.class.getDeclaredMethod("finishModernSetup",long.class);
            beginSetup.setAccessible(true);finishSetup.setAccessible(true);long generation=(Long)beginSetup.invoke(bob);
            for(int i=0;i<Omemo2IncomingJournal.MAX_ENTRIES;i++) b.store();
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!bob.modern().pendingIncoming().isEmpty() && System.nanoTime()<deadline) Thread.sleep(20);
            assertTrue(bob.modern().pendingIncoming().isEmpty(),"All original content and ACKs must leave the journal while setup is gated");
            synchronized(lock) { assertTrue(blockedField.getBoolean(bob)); }
            finishSetup.invoke(bob,generation); // No later storage callback is available to wake the queued ciphertext.
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!delivered.contains(waiting) && System.nanoTime()<deadline) {
                XmppMessage message=b.messages.poll(1,TimeUnit.SECONDS);if(message==null) continue;
                assertTrue(message.readable,() -> "Queued ciphertext must survive journal capacity: "+bob.modernFailure());
                assertTrue(sent.contains(message.ids.get("stanza")));delivered.add(message.ids.get("stanza"));
            }
            assertEquals(sent,delivered);
        }
    }
}
