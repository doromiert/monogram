package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.omemo.*;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class LegacyIncomingIntegrationTest {
    static final String ALICE="legacyjournalalice@localhost",BOB="legacyjournalbob@localhost";
    static final int A=9751,B=9752;
    static final class Inbox implements XmppSession.Listener {
        final BlockingQueue<XmppMessage> direct=new LinkedBlockingQueue<>();
        final BlockingQueue<XmppRoomMessage> rooms=new LinkedBlockingQueue<>();
        final Map<String,Runnable> directAccepted=new ConcurrentHashMap<>(),roomAccepted=new ConcurrentHashMap<>();
        public void message(EntityBareJid peer,String id,String body,boolean encrypted) {}
        public void messagePending(XmppMessage message) {}
        public void roomMessagePending(XmppRoomMessage message) {}
        public void message(XmppMessage message) { direct.add(message); }
        public void message(XmppMessage message,Runnable stored) { directAccepted.putIfAbsent(message.ids.get("stanza"),stored);direct.add(message); }
        public void roomMessage(XmppRoomMessage message) { rooms.add(message); }
        public void roomMessage(XmppRoomMessage message,Runnable stored) { roomAccepted.putIfAbsent(message.ids.toString(),stored);rooms.add(message); }
        XmppMessage next() throws Exception { XmppMessage result=direct.poll(15,TimeUnit.SECONDS);assertNotNull(result);return result; }
        XmppRoomMessage room(String body) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<end) { XmppRoomMessage result=rooms.poll(200,TimeUnit.MILLISECONDS);if(result!=null && result.readable && body.equals(result.body)) return result; }
            fail("Room plaintext did not arrive: "+body);return null;
        }
    }
    XmppSession session(boolean alice,Inbox inbox) throws Exception { return session(alice,alice ? A : B,inbox); }
    XmppSession session(boolean alice,int device,Inbox inbox) throws Exception { return session(alice,device,inbox,"LegacyJournal-"+device); }
    XmppSession session(boolean alice,int device,Inbox inbox,String resource) throws Exception {
        File root=new File(System.getenv("MONOGRAM_IT_STATE"));XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
        return new XmppSession(new Account(alice ? ALICE : BOB,"127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),resource),
                "monogram-test",device,new File(root,"legacy-journal-trust-"+device),inbox);
    }
    void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(20);assertTrue(condition.getAsBoolean());
    }
    void assertNetworkOutsideRecords(XmppSession session) throws Exception {
        java.lang.reflect.Method active=MonogramSignalStore.class.getDeclaredMethod("transactionActive");active.setAccessible(true);
        session.connection().addStanzaInterceptor(stanza -> {
            try { assertFalse((Boolean)active.invoke(MonogramLegacyOmemoService.instance().records()),"Network transmission while legacy records transaction is active"); }
            catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
        },stanza -> true);
    }
    LegacyIncomingJournal journal(XmppSession session,int device) throws Exception {
        return new LegacyIncomingJournal(MonogramLegacyOmemoService.instance().records(),new OmemoDevice(session.connection().getUser()==null ? JidCreate.bareFrom(BOB) : session.connection().getUser().asBareJid(),device));
    }
    void emptyJournal(XmppSession session,int device) throws Exception {
        LegacyIncomingJournal journal=journal(session,device);
        await(() -> { try { return journal.pending().isEmpty(); } catch(Exception failure) { throw new AssertionError(failure); } });
    }
    void trust(XmppSession session,String peer) throws Exception {
        Set<OmemoDevice> devices;
        for(int attempt=0;;attempt++) try { devices=session.devices(peer);break; }
        catch(OmemoSendException changed) { if(changed.reason!=OmemoSendException.Reason.ROOM_CHANGED || !(changed.getCause() instanceof ConcurrentModificationException) || attempt>=4) throw changed;Thread.sleep(50); }
        for(OmemoDevice device:devices) session.decide(device,session.fingerprint(device),true);
    }
    @Test void readOnlyOwnDeviceCannotBlockTrustedDirectRecipients() throws Exception {
        Inbox a=new Inbox(),b=new Inbox(),old=new Inbox();int sender=9821,recipient=9822,stale=9823;
        try(XmppSession alice=session(true,sender,a);XmppSession oldAlice=session(true,stale,old);XmppSession bob=session(false,recipient,b)) {
            alice.connect();oldAlice.connect();bob.connect();trust(alice,ALICE);trust(alice,BOB);trust(bob,ALICE);
            MonogramSignalStore records=MonogramLegacyOmemoService.instance().records();
            OmemoDevice own=new OmemoDevice(JidCreate.bareFrom(ALICE),sender),readOnly=new OmemoDevice(JidCreate.bareFrom(ALICE),stale);
            int limit=OmemoConfiguration.getMaxReadOnlyMessageCount();assertTrue(OmemoConfiguration.getIgnoreReadOnlyDevices());
            records.storeOmemoMessageCounter(own,readOnly,limit);
            String id=alice.send(BOB,"A quiet own device must not block this encrypted DM",true);
            XmppMessage received=b.next();assertEquals(id,received.ids.get("stanza"));assertTrue(received.readable);
            assertEquals("A quiet own device must not block this encrypted DM",received.body);
            assertEquals(limit,records.loadOmemoMessageCounter(own,readOnly));
            assertEquals(org.jivesoftware.smackx.omemo.trust.TrustState.trusted,alice.trustState(readOnly,alice.fingerprint(readOnly)));
            for(OmemoDevice device:alice.devices(BOB)) records.storeOmemoMessageCounter(own,device,limit);
            OmemoSendException failure=assertThrows(OmemoSendException.class,() -> alice.send(BOB,"Must not send only to ourselves",true));
            assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,failure.reason);
        }
    }
    @Test void encryptedStickerHintReachesLiveAndArchivedRecipient() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try(XmppSession alice=session(true,9791,a);XmppSession bob=session(false,9792,b)) {
            alice.connect();bob.connect();trust(alice,BOB);trust(alice,ALICE);
            FileLink link=FileLink.parse("aesgcm://example.org/sticker.webp#"+new String(new char[88]).replace('\0','a'));
            String id=alice.sendFile(BOB,link,true,null,true);
            XmppMessage received=b.next();assertEquals(id,received.ids.get("stanza"));assertTrue(received.readable);
            assertTrue(received.displayStickerHint,"Live receiver lost sticker marker");
            XmppSession.ArchivePage page=bob.archiveBefore(ALICE,null,50);
            XmppMessage archived=page.messages.stream().filter(m -> id.equals(m.ids.get("stanza"))).findFirst().orElseThrow(AssertionError::new);
            assertTrue(archived.displayStickerHint,"Archived receiver lost sticker marker");
        }
    }
    @Test void committedDirectPlaintextAndPendingMamRecoverOfflineWithOriginalIdsAndAcceptanceAfterClose() throws Exception {
        final int senderId=9781,receiverId=9782;Inbox a=new Inbox(),b=new Inbox(),recovered=new Inbox();
        try(XmppSession alice=session(true,senderId,a)) {
            alice.connect();XmppSession bob=session(false,receiverId,b);
            try {
                bob.connect();emptyJournal(bob,receiverId);assertNetworkOutsideRecords(bob);trust(alice,BOB);trust(alice,ALICE);
                String id=alice.send(BOB,"*Recover private 😀 body*",true);XmppMessage original=b.next();assertTrue(original.readable,() -> "Legacy decrypt: "+bob.legacyFailure());
                assertEquals(id,original.ids.get("stanza"));Runnable stored=b.directAccepted.get(id);assertNotNull(stored);
                XmppSession.ArchivePage interrupted=bob.archiveBefore(ALICE,null,100);
                XmppMessage cached=interrupted.messages.stream().filter(m -> id.equals(m.ids.get("stanza"))).findFirst().orElseThrow(AssertionError::new);
                assertTrue(cached.readable);assertEquals(original.ids.get("legacy-cipher"),cached.ids.get("legacy-cipher"));assertEquals(original.body,cached.body);
                assertEquals(1,journal(bob,receiverId).pending().stream().filter(pending -> pending.message!=null).count(),"Fresh receiver must not decrypt earlier fixtures' archived ciphertext");
                bob.close();
                try(XmppSession offline=session(false,receiverId,recovered)) {
                    offline.recoverIncoming();XmppMessage replay=recovered.next();
                    assertEquals(original.ids,replay.ids);assertEquals(original.timestamp,replay.timestamp);assertEquals(original.body,replay.body);
                    assertEquals(original.markup.display().body,replay.markup.display().body);assertEquals(original.senderFingerprint,replay.senderFingerprint);
                    stored.run();stored.run();interrupted.stored();interrupted.stored();offline.recoverIncoming();
                    XmppMessage extra=recovered.direct.poll(200,TimeUnit.MILLISECONDS);
                    assertNull(extra,() -> "Unexpected synthetic replay ids="+extra.ids+" body="+(extra.body!=null && (extra.body.startsWith("Held legacy ") || extra.body.equals(original.body)) ? extra.body : "<outside expected fixture strings>"));
                }
            } finally { bob.close(); }
        }
    }
    @Test void roomCanonicalBodyRecoversWithoutJoinOrCurrentOccupantAndRoomPageAcceptsAfterClose() throws Exception {
        Inbox a=new Inbox(),b=new Inbox(),recovered=new Inbox();
        try(XmppSession alice=session(true,a)) {
            alice.connect();XmppSession bob=session(false,b);
            try {
                bob.connect();emptyJournal(bob,B);String room=alice.rooms.createPrivate("Alice","Legacy journal room").room.toString();
                alice.rooms.invite(room,BOB,"Synthetic journal test");bob.rooms.join(room,"OriginalBob",null);
                trust(alice,ALICE);trust(alice,BOB);
                await(() -> { try { return alice.rooms.state(room).occupants.size()==2 && bob.rooms.state(room).occupants.size()==2; } catch(Exception error) { return false; } });
                alice.rooms.send(room,"*Recover private room 🐢*",true);XmppRoomMessage original=b.room("*Recover private room 🐢*");
                assertEquals(ALICE,original.realSender);assertTrue(original.realSenderVerified);assertNotNull(original.ids.get("legacy-cipher"));
                XmppRooms.ArchivePage page=bob.rooms.archive(room,null,null,100);
                XmppRoomMessage cached=page.messages.stream().filter(m -> original.ids.get("legacy-cipher").equals(m.ids.get("legacy-cipher"))).findFirst().orElseThrow(AssertionError::new);
                assertTrue(cached.readable);assertEquals(original.body,cached.body);assertNotNull(cached.ids.get("mam"));
                bob.rooms.leave(room);bob.close();
                try(XmppSession offline=session(false,recovered)) {
                    offline.recoverRoomIncoming(room);XmppRoomMessage replay=recovered.room(original.body);
                    assertEquals(original.ids,replay.ids);assertEquals(original.sender,replay.sender);assertEquals(original.realSender,replay.realSender);
                    assertEquals(original.realSenderVerified,replay.realSenderVerified);assertEquals(original.occupantId,replay.occupantId);assertEquals(original.outgoing,replay.outgoing);
                    assertEquals(original.timestamp,replay.timestamp);assertEquals(original.senderFingerprint,replay.senderFingerprint);
                    page.stored();page.stored();offline.recoverRoomIncoming(room);assertNull(recovered.rooms.poll(200,TimeUnit.MILLISECONDS));
                }
            } finally { bob.close(); }
        }
    }
    @Test void corruptAuthenticatedPayloadDoesNotAdvanceLegacyRatchetOrConsumePrekey() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try(XmppSession alice=session(true,a);XmppSession bob=session(false,b)) {
            alice.connect();bob.connect();emptyJournal(bob,B);trust(alice,BOB);trust(alice,ALICE);
            OmemoManager sender=OmemoManager.getInstanceFor(alice.connection(),A),receiver=OmemoManager.getInstanceFor(bob.connection(),B);
            MonogramSignalStore store=MonogramLegacyOmemoService.instance().records();OmemoDevice peer=new OmemoDevice(JidCreate.bareFrom(ALICE),A);
            byte[] before=store.loadRawSession(receiver.getOwnDevice(),peer)==null ? null : store.loadRawSession(receiver.getOwnDevice(),peer).serialize();
            Set<Integer> prekeys=new TreeSet<>(store.loadOmemoPreKeys(receiver.getOwnDevice()).keySet());
            OmemoMessage.Sent encrypted=sender.encrypt(JidCreate.bareFrom(BOB),"Authentication must roll back");
            OmemoElement original=encrypted.getElement();byte[] payload=original.getPayload().clone();payload[payload.length-1]^=1;
            Message bad=new Message(bob.connection().getUser(),Message.Type.chat);bad.setFrom(alice.connection().getUser());bad.setStanzaId("bad-authenticated-payload");
            bad.addExtension(new OmemoElement_VAxolotl((OmemoHeaderElement_VAxolotl)original.getHeader(),payload));
            LegacyIncomingJournal journal=new LegacyIncomingJournal(store,receiver.getOwnDevice());
            LegacyIncomingJournal.Context context=LegacyIncomingJournal.Context.direct(bad,bob.connection().getUser().asEntityBareJid(),false,null,null);
            assertThrows(org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException.class,() -> MonogramLegacyOmemoService.instance().receive(receiver,journal,context));
            byte[] after=store.loadRawSession(receiver.getOwnDevice(),peer)==null ? null : store.loadRawSession(receiver.getOwnDevice(),peer).serialize();
            assertArrayEquals(before,after);assertEquals(prekeys,store.loadOmemoPreKeys(receiver.getOwnDevice()).keySet());assertTrue(journal.pending().isEmpty());
            bad.removeExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);bad.addExtension(original);
            LegacyIncomingJournal.Received valid=MonogramLegacyOmemoService.instance().receive(receiver,journal,LegacyIncomingJournal.Context.direct(bad,bob.connection().getUser().asEntityBareJid(),false,null,null));
            assertEquals("Authentication must roll back",valid.message.body);assertTrue(journal.accept(valid));
        }
    }
    @Test void journalCapacityRetainsWaitingCiphertextAndAcceptanceResumesWithoutAnotherArrival() throws Exception {
        final int senderId=9761,receiverId=9762;Inbox a=new Inbox(),b=new Inbox();
        try(XmppSession alice=session(true,senderId,a);XmppSession bob=session(false,receiverId,b)) {
            alice.connect();bob.connect();emptyJournal(bob,receiverId);trust(alice,BOB);trust(alice,ALICE);
            Set<String> originals=new LinkedHashSet<>();
            for(int i=0;i<LegacyIncomingJournal.MAX_ENTRIES;i++) {
                String id=alice.send(BOB,"Held legacy "+i,true);originals.add(id);XmppMessage message=b.next();assertEquals(id,message.ids.get("stanza"));assertTrue(message.readable);
            }
            LegacyIncomingJournal held=journal(bob,receiverId);
            assertEquals(99,MonogramLegacyOmemoService.instance().records().loadOmemoPreKeys(held.own).size(),"Fresh sender must consume one prekey before completion");
            List<LegacyIncomingJournal.Received> pending=held.pending();
            assertEquals(LegacyIncomingJournal.MAX_ENTRIES,pending.size());
            assertTrue(pending.stream().allMatch(received -> received.replenish),"Every held message must capture the real prekey deficit");
            bob.close();Inbox replacementInbox=new Inbox();
            try(XmppSession replacement=session(false,receiverId,replacementInbox)) {
                replacement.connect();await(() -> replacementInbox.directAccepted.size()==LegacyIncomingJournal.MAX_ENTRIES);
                java.util.concurrent.atomic.AtomicInteger publications=new java.util.concurrent.atomic.AtomicInteger();
                replacement.connection().addStanzaInterceptor(stanza -> {
                    if(stanza.toXML().toString().contains("<publish") && stanza.toXML().toString().contains("eu.siacs.conversations.axolotl.bundles:"+receiverId)) publications.incrementAndGet();
                },stanza -> stanza instanceof org.jivesoftware.smack.packet.IQ);
                replacementInbox.direct.clear();String waiting=alice.send(BOB,"Waiting legacy ciphertext",true);
                java.lang.reflect.Field blocked=XmppSession.class.getDeclaredField("legacyBlocked"),lock=XmppSession.class.getDeclaredField("legacyInboxLock");blocked.setAccessible(true);lock.setAccessible(true);
                await(() -> { try { synchronized(lock.get(replacement)) { return blocked.getBoolean(replacement); } } catch(Exception error) { throw new AssertionError(error); } });
                // These callbacks belong to the CLOSED predecessor, not the active replacement.
                for(String id:originals) { Runnable stored=b.directAccepted.get(id);assertNotNull(stored);stored.run(); }
                XmppMessage result=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<end) { XmppMessage next=replacementInbox.direct.poll(200,TimeUnit.MILLISECONDS);if(next!=null && waiting.equals(next.ids.get("stanza"))) { result=next;break; } }
                assertNotNull(result,() -> "Waiting legacy ciphertext was not retried: "+replacement.legacyFailure());assertTrue(result.readable);replacementInbox.directAccepted.get(waiting).run();
                emptyJournal(replacement,receiverId);
                assertEquals(1,publications.get(),"A burst of accepted prekey messages must share one successful replenishment publication");
            }
        }
    }
    XmppSession modern(boolean alice,Omemo2Device device,Inbox inbox) throws Exception {
        File root=new File(System.getenv("MONOGRAM_IT_STATE"));XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
        return new XmppSession(new Account(alice ? ALICE : BOB,"127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"LegacyJournalModern-"+(alice ? A : B)),
                "monogram-test",alice ? A : B,new File(root,"legacy-journal-trust-"+(alice ? A : B)),device,inbox);
    }
    @Test @EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
    void closedPredecessorModernAcceptanceWakesReplacementAndFlushesNewAckOnlyRows() throws Exception {
        Omemo2Device aliceDevice=new Omemo2Device(ALICE,A+1000,new Omemo2TestDatabase()),bobDevice=new Omemo2Device(BOB,B+1000,new Omemo2TestDatabase());
        Inbox a=new Inbox(),b=new Inbox();
        try(XmppSession alice=modern(true,aliceDevice,a);XmppSession bob=modern(false,bobDevice,b)) {
            alice.connect();bob.connect();
            for(XmppSession owner:new XmppSession[]{alice,bob}) for(String peer:new String[]{ALICE,BOB})
                for(Map.Entry<Integer,Omemo2Bundle> entry:owner.modern().discover(peer).entrySet()) owner.modern().device.decide(peer,entry.getKey(),entry.getValue().identity(),true);
            Set<String> originals=new LinkedHashSet<>();
            for(int i=0;i<Omemo2IncomingJournal.MAX_ENTRIES;i++) {
                String id=alice.send(BOB,"Held modern predecessor "+i,XmppMessage.Encryption.OMEMO2,null);originals.add(id);
                XmppMessage message=b.next();assertEquals(id,message.ids.get("stanza"));assertTrue(message.readable);
            }
            bob.close();Inbox replacementInbox=new Inbox();
            try(XmppSession replacement=modern(false,bobDevice,replacementInbox)) {
                replacement.connect();await(() -> replacementInbox.directAccepted.size()==Omemo2IncomingJournal.MAX_ENTRIES);replacementInbox.direct.clear();
                String waiting=alice.send(BOB,"Waiting modern replacement",XmppMessage.Encryption.OMEMO2,null);
                java.lang.reflect.Field blocked=XmppSession.class.getDeclaredField("journalBlocked"),lock=XmppSession.class.getDeclaredField("modernInboxLock");blocked.setAccessible(true);lock.setAccessible(true);
                await(() -> { try { synchronized(lock.get(replacement)) { return blocked.getBoolean(replacement); } } catch(Exception error) { throw new AssertionError(error); } });
                for(String id:originals) b.directAccepted.get(id).run();
                XmppMessage result=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(System.nanoTime()<end) { XmppMessage next=replacementInbox.direct.poll(200,TimeUnit.MILLISECONDS);if(next!=null && waiting.equals(next.ids.get("stanza"))) { result=next;break; } }
                assertNotNull(result,() -> "Foreign modern capacity/ACK wake lost: "+replacement.modernFailure());assertTrue(result.readable);replacementInbox.directAccepted.get(waiting).run();
            }
        }
    }

    @Test void prekeyCompletionWaitsForStorageThenReplaysExactControlAfterCloseOnNewResource() throws Exception {
        final int senderId=9771,receiverId=9772;Inbox a=new Inbox(),b=new Inbox(),recovered=new Inbox();
        try(XmppSession alice=session(true,senderId,a)) {
            alice.connect();XmppSession bob=session(false,receiverId,b);
            try {
                bob.connect();assertNetworkOutsideRecords(bob);trust(alice,BOB);trust(alice,ALICE);
                BlockingQueue<Message> controls=new LinkedBlockingQueue<>();
                alice.connection().addSyncStanzaListener(stanza -> {
                    OmemoElement encrypted=stanza.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
                    if(encrypted!=null && encrypted.isKeyTransportElement() && encrypted.getHeader().getSid()==receiverId) controls.add((Message)stanza);
                },stanza -> stanza instanceof Message && stanza.hasExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE));
                String id=alice.send(BOB,"Stored before legacy completion",true);XmppMessage original=b.next();assertTrue(original.readable);
                assertEquals(id,original.ids.get("stanza"));Runnable stored=b.directAccepted.get(id);assertNotNull(stored);
                LegacyIncomingJournal journal=journal(bob,receiverId);LegacyIncomingJournal.Received saved=journal.pending().stream().filter(r -> r.message!=null && id.equals(r.message.ids.get("stanza"))).findFirst().orElseThrow(AssertionError::new);
                assertNotNull(saved.completion);String expectedId=saved.completion.getStanzaId();
                String expectedEncrypted=saved.completion.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE).toXML().toString();
                assertNull(controls.poll(250,TimeUnit.MILLISECONDS),"No legacy session completion before native storage");
                bob.close();stored.run();assertNull(journal.pending().get(0).message);
                try(XmppSession replacement=session(false,receiverId,recovered,"LegacyJournal-Recovered-"+receiverId)) {
                    replacement.recoverIncoming();assertNull(recovered.direct.poll(200,TimeUnit.MILLISECONDS),"ACK-only recovery must not publish usercontent");
                    MonogramSignalStore records=MonogramLegacyOmemoService.instance().records();OmemoDevice own=journal.own,peer=new OmemoDevice(JidCreate.bareFrom(ALICE),senderId);
                    byte[] ratchet=records.loadRawSession(own,peer).serialize();
                    replacement.connect();Message response=controls.poll(10,TimeUnit.SECONDS);assertNotNull(response);assertEquals(expectedId,response.getStanzaId());
                    assertNull(response.getBody());assertEquals(expectedEncrypted,response.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE).toXML().toString());
                    emptyJournal(replacement,receiverId);assertArrayEquals(ratchet,records.loadRawSession(own,peer).serialize(),"Sending stored completion cannot encrypt another ratchet packet");
                    assertNull(recovered.direct.poll(200,TimeUnit.MILLISECONDS));assertNull(controls.poll(200,TimeUnit.MILLISECONDS));
                }
            } finally { bob.close(); }
        }
    }

}
