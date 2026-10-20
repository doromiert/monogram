package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.xdata.form.FillableForm;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppRoomsIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    final BlockingQueue<String> rejectedInvitations=new LinkedBlockingQueue<>();
    final BlockingQueue<String> metadataChanges=new LinkedBlockingQueue<>();
    XmppSession session(String user,int device,BlockingQueue<XmppRoomMessage> messages) throws Exception {
        return new XmppSession(new Account("room"+user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Room-"+device),
                "monogram-test",device,new File(root,"room-trust-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) { fail("Room content must not become a direct conversation"); }
            public void roomMessage(XmppRoomMessage message) { messages.add(message); }
            public void roomInviteFailed(String room,String target,String condition) { rejectedInvitations.add(target+":"+condition); }
            public void roomMetadataChanged(String room) { metadataChanges.add(user+":"+room); }
        });
    }
    XmppRoomMessage readable(BlockingQueue<XmppRoomMessage> messages,String body) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {
            XmppRoomMessage message=messages.poll(200,TimeUnit.MILLISECONDS);
            if(message!=null && message.readable && body.equals(message.body)) return message;
        }
        fail("Room message did not arrive: "+body); return null;
    }
    void awaitMetadata(String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) if(expected.equals(metadataChanges.poll(100,TimeUnit.MILLISECONDS))) return;
        fail("Room configuration hint did not arrive");
    }
    @Test void nativeCreationInvitationsConfigurationAndAvatar() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),com.negzero.monogram.xmpp.LegacyOmemoTestDatabase.shared());
        BlockingQueue<XmppRoomMessage> aliceMessages=new LinkedBlockingQueue<>(),bobMessages=new LinkedBlockingQueue<>();
        try(XmppSession alice=session("alice",3101,aliceMessages); XmppSession bob=session("bob",3102,bobMessages)) {
            alice.connect(); bob.connect();
            XmppRooms.State created=alice.rooms.createPrivate("Alice","Native group");
            String room=created.room.toString(); assertEquals("Native group",created.title); assertEquals("owner",created.affiliation);
            RoomInfo info=MultiUserChatManager.getInstanceFor(alice.connection()).getRoomInfo(created.room);
            assertTrue(info.isMembersOnly()); assertTrue(info.isNonanonymous());
            assertThrows(Exception.class,() -> bob.rooms.join(room,"Bob",null));
            alice.rooms.invite(room,"roombob@localhost","Join this test room");
            alice.rooms.invite(room,"alice@localhost","Offline member");
            assertEquals("member",alice.rooms.roster(room).get("alice@localhost"));
            assertEquals(1,alice.rooms.state(room).occupants.size());
            Exception failure=null;
            for(int attempt=0;attempt<10;attempt++) {
                try { bob.rooms.join(room,"Bob",null); failure=null; break; }
                catch(Exception pending) { failure=pending; Thread.sleep(100); }
            }
            if(failure!=null) throw failure;
            FillableForm invitationPolicy=MultiUserChatManager.getInstanceFor(alice.connection()).getMultiUserChat(created.room).getConfigurationForm().getFillableForm();
            invitationPolicy.setAnswer("{http://prosody.im/protocol/muc}roomconfig_allowmemberinvites",false);
            MultiUserChatManager.getInstanceFor(alice.connection()).getMultiUserChat(created.room).sendConfigurationForm(invitationPolicy);
            bob.rooms.invite(room,"bob@localhost","Should be rejected by room policy");
            String rejected=rejectedInvitations.poll(10,TimeUnit.SECONDS);assertNotNull(rejected);assertTrue(rejected.startsWith("bob@localhost:"));
            metadataChanges.clear();
            XmppRooms.State changed=alice.rooms.configure(room,"Renamed group","Group description");
            awaitMetadata("bob:"+room);
            RoomInfo remote=bob.rooms.information(room);assertEquals("Renamed group",remote.getName());assertEquals("Group description",remote.getDescription());
            assertEquals("Renamed group",changed.title); assertEquals("Group description",changed.description);
            assertThrows(Exception.class,() -> bob.rooms.configure(room,"Not the owner",null));
            assertTrue(MultiUserChatManager.getInstanceFor(alice.connection()).getRoomInfo(created.room).isMembersOnly());
            byte[] avatar=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
            metadataChanges.clear();alice.profiles.roomAvatar(room,avatar);awaitMetadata("bob:"+room);
            com.negzero.monogram.xmpp.XmppProfile cachedAvatar=bob.profiles.loadRoom(room,null,bob.rooms.information(room));
            assertArrayEquals(avatar,cachedAvatar.avatar);
            assertFalse(XmppProfiles.roomAvatarHashes(bob.rooms.information(room).getForm()).isEmpty());
            assertSame(cachedAvatar,bob.profiles.loadRoom(room,cachedAvatar,bob.rooms.information(room)));
            metadataChanges.clear();alice.profiles.roomAvatar(room,new byte[0]);awaitMetadata("bob:"+room);
            assertEquals(0,bob.profiles.loadRoom(room,cachedAvatar,bob.rooms.information(room)).avatar.length);
            assertEquals("",alice.rooms.configure(room,null,"").description);
        }
    }
    private MessageMarkup formatted(String body) {
        return new MessageMarkup(body,Collections.singletonList(new MessageMarkup.Range(0,body.codePointCount(0,body.length()),Collections.singleton(MessageMarkup.Style.STRONG),null))).styled();
    }
    @SuppressWarnings("unchecked") private static <T> T privateField(Object object,String name) throws Exception {
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(object);
    }
    private void eventually(Callable<Boolean> condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) { if(condition.call()) return;Thread.sleep(20); }
        assertTrue(condition.call(),"Asynchronous room recovery did not finish");
    }
    private void publishOnlyCurrentDevice(XmppSession session) throws Exception {
        org.jivesoftware.smackx.pubsub.PubSubManager.getInstanceFor(session.connection(),session.connection().getUser().asBareJid())
                .getOrCreateLeafNode("eu.siacs.conversations.axolotl.devicelist")
                .publish(new org.jivesoftware.smackx.pubsub.PayloadItem<>("current",new org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl(Collections.singleton(session.ownDeviceId()))));
        session.devices(session.ownJid());
    }
    private XmppSession.Listener recoveryListener(BlockingQueue<XmppRoomMessage> messages) {
        return new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid sender,String id,String body,boolean encrypted) { fail("Room recovery must not create a direct conversation"); }
            public void roomMessage(XmppRoomMessage message) { if(messages!=null) messages.add(message); }
        };
    }
    private final class RecoveryRoom implements AutoCloseable {
        final BlockingQueue<XmppRoomMessage> messages=new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String,String>> snapshots=new LinkedBlockingQueue<>();
        final XmppSession alice,bob;final String room;
        RecoveryRoom(int id,boolean trustBob,Map<String,String> restored) throws Exception {
            XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
            alice=new XmppSession(new Account("callsalice@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Room-recovery-"+id),"monogram-test",id,new File(root,"recovery-trust-"+id),recoveryListener(null));
            bob=new XmppSession(new Account("callsbob@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Room-recovery-"+(id+1)),"monogram-test",id+1,new File(root,"recovery-trust-"+(id+1)),recoveryListener(messages));
            alice.rooms.setUnavailableCache(restored,snapshots::add);alice.connect();bob.connect();
            publishOnlyCurrentDevice(alice);publishOnlyCurrentDevice(bob);
            alice.devices(bob.ownJid());
            if(trustBob) for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:alice.devices(bob.ownJid())) alice.decide(device,alice.fingerprint(device),true);
            room=alice.rooms.createPrivate("Alice","Unavailable recipient fixture").room.toString();
            alice.rooms.invite(room,bob.ownJid(),null);bob.rooms.join(room,"Bob",null);
        }
        public void close() { alice.close();bob.close(); }
    }
    @Test void newAndDistrustedDevicesCannotBlockTrustedRoomRecipients() throws Exception {
        try(RecoveryRoom fixture=new RecoveryRoom(3971,true,Collections.emptyMap());
                XmppSession extra=new XmppSession(new Account(fixture.bob.ownJid(),"127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Unverified-room-device"),
                        "monogram-test",3973,new File(root,"unverified-room-trust"),recoveryListener(null))) {
            extra.connect();fixture.alice.devices(fixture.bob.ownJid());
            org.jivesoftware.smackx.omemo.internal.OmemoDevice unknown=new org.jivesoftware.smackx.omemo.internal.OmemoDevice(JidCreate.bareFrom(fixture.bob.ownJid()),3973);
            org.jivesoftware.smackx.omemo.trust.OmemoFingerprint fingerprint=fixture.alice.fingerprint(unknown);
            java.util.concurrent.atomic.AtomicReference<org.jivesoftware.smackx.omemo.element.OmemoElement> wire=new java.util.concurrent.atomic.AtomicReference<>();
            fixture.alice.connection().addStanzaInterceptor(stanza -> wire.set(stanza.getExtension("encrypted","eu.siacs.conversations.axolotl")),
                    stanza -> stanza instanceof Message && ((Message)stanza).getType()==Message.Type.groupchat);
            XmppRoomMessage sent=fixture.alice.rooms.send(fixture.room,"Skip unknown device, deliver encrypted",true,Collections.emptySet(),true);
            assertTrue(sent.skippedRecipients.keySet().stream().anyMatch(key -> key.endsWith("device 3973")));
            assertTrue(readable(fixture.messages,"Skip unknown device, deliver encrypted").encrypted);
            assertEquals(org.jivesoftware.smackx.omemo.trust.TrustState.undecided,fixture.alice.trustState(unknown,fingerprint));
            assertNotNull(wire.get());assertTrue(wire.get().getHeader().getKeys().stream().noneMatch(key -> key.getId()==3973));
            assertThrows(org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException.class,
                    () -> fixture.alice.rooms.send(fixture.room,"Strict still requires approval",true,Collections.emptySet(),false));
            fixture.alice.decide(unknown,fingerprint,false);
            fixture.alice.rooms.send(fixture.room,"Distrusted device cannot block delivery",true,Collections.emptySet(),true);
            assertTrue(readable(fixture.messages,"Distrusted device cannot block delivery").encrypted);
            assertEquals(org.jivesoftware.smackx.omemo.trust.TrustState.untrusted,fixture.alice.trustState(unknown,fingerprint));
            assertTrue(wire.get().getHeader().getKeys().stream().noneMatch(key -> key.getId()==3973));
            for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:fixture.alice.devices(fixture.bob.ownJid()))
                fixture.alice.decide(device,fixture.alice.fingerprint(device),false);
            wire.set(null);
            assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,assertThrows(OmemoSendException.class,
                    () -> fixture.alice.rooms.send(fixture.room,"No trusted recipient, no ciphertext",true,Collections.emptySet(),true)).reason);
            assertNull(wire.get());
        }
    }
    @Test void readOnlyDevicesCannotBlockAutomaticRoomSendsOrPermitOwnOnlyCiphertext() throws Exception {
        try(RecoveryRoom fixture=new RecoveryRoom(3961,true,Collections.emptyMap());
                XmppSession quiet=new XmppSession(new Account(fixture.alice.ownJid(),"127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Quiet-room-device"),
                        "monogram-test",3963,new File(root,"quiet-room-trust"),recoveryListener(null))) {
            quiet.connect();
            org.jivesoftware.smackx.omemo.MonogramSignalStore records=org.jivesoftware.smackx.omemo.MonogramLegacyOmemoService.instance().records();
            org.jivesoftware.smackx.omemo.internal.OmemoDevice own=new org.jivesoftware.smackx.omemo.internal.OmemoDevice(JidCreate.bareFrom(fixture.alice.ownJid()),3961);
            org.jivesoftware.smackx.omemo.internal.OmemoDevice stale=new org.jivesoftware.smackx.omemo.internal.OmemoDevice(JidCreate.bareFrom(fixture.alice.ownJid()),3963);
            for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:fixture.alice.devices(fixture.alice.ownJid()))
                if(!device.equals(own)) fixture.alice.decide(device,fixture.alice.fingerprint(device),true);
            int limit=org.jivesoftware.smackx.omemo.OmemoConfiguration.getMaxReadOnlyMessageCount();
            records.storeOmemoMessageCounter(own,stale,limit);
            XmppRoomMessage sent=fixture.alice.rooms.send(fixture.room,"Quiet own device must not block room",true,Collections.emptySet(),true);
            assertTrue(sent.skippedRecipients.keySet().stream().anyMatch(key -> key.endsWith("device 3963")));
            assertTrue(readable(fixture.messages,"Quiet own device must not block room").encrypted);
            assertEquals(limit,records.loadOmemoMessageCounter(own,stale));
            assertEquals(org.jivesoftware.smackx.omemo.trust.TrustState.trusted,fixture.alice.trustState(stale,fixture.alice.fingerprint(stale)));
            assertEquals(OmemoSendException.Reason.DEVICE_FAILURE,assertThrows(OmemoSendException.class,
                    () -> fixture.alice.rooms.send(fixture.room,"Strict must refuse inactive devices",true,Collections.emptySet(),false)).reason);
            for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:fixture.alice.devices(fixture.bob.ownJid())) records.storeOmemoMessageCounter(own,device,limit);
            java.util.concurrent.atomic.AtomicInteger transmitted=new java.util.concurrent.atomic.AtomicInteger();
            fixture.alice.connection().addStanzaInterceptor(stanza -> transmitted.incrementAndGet(),stanza -> stanza instanceof Message && ((Message)stanza).getType()==Message.Type.groupchat);
            assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,assertThrows(OmemoSendException.class,
                    () -> fixture.alice.rooms.send(fixture.room,"Must not send only to ourselves",true,Collections.emptySet(),true)).reason);
            assertEquals(0,transmitted.get());
        }
    }
    @Test void rememberedRecipientProbeIsPostSendCoalescedAndCannotBlockLaterMessages() throws Exception {
        CountDownLatch probing=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService sends=Executors.newSingleThreadExecutor();
        try(RecoveryRoom fixture=new RecoveryRoom(3901,true,Collections.emptyMap());
            XmppSession restored=new XmppSession(new Account("alice@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Room-recovered-recipient"),"monogram-test",3903,new File(root,"recovery-trust-3903"),recoveryListener(null))) {
            restored.connect();publishOnlyCurrentDevice(restored);
            String target=restored.ownJid();MultiUserChat chat=MultiUserChatManager.getInstanceFor(fixture.alice.connection()).getMultiUserChat(JidCreate.entityBareFrom(fixture.room));chat.grantMembership(JidCreate.bareFrom(target));
            XmppRooms.DeviceLookup original=privateField(fixture.alice.rooms,"deviceLookup");
            java.util.concurrent.atomic.AtomicInteger lookups=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean sent=new java.util.concurrent.atomic.AtomicBoolean(),earlyProbe=new java.util.concurrent.atomic.AtomicBoolean();
            org.jivesoftware.smack.StanzaListener sentWire=stanza -> sent.set(true);
            fixture.alice.connection().addStanzaInterceptor(sentWire,stanza -> stanza instanceof Message && Message.Type.groupchat==((Message)stanza).getType() && fixture.room.equals(String.valueOf(stanza.getTo())));
            XmppRooms.DeviceLookup controlled=contact -> {
                if(!target.equals(contact.toString())) return original.devices(contact);
                int invocation=lookups.incrementAndGet();
                if(invocation==1) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES);
                earlyProbe.set(!sent.get());probing.countDown();
                if(!release.await(30,TimeUnit.SECONDS)) throw new java.io.IOException("Fixture probe remained blocked");
                return original.devices(contact);
            };
            java.lang.reflect.Field lookup=XmppRooms.class.getDeclaredField("deviceLookup");lookup.setAccessible(true);lookup.set(fixture.alice.rooms,controlled);
            try {
                XmppRoomMessage first=sends.submit(() -> fixture.alice.rooms.send(fixture.room,"first remembered skip",true,Collections.emptySet(),true)).get(10,TimeUnit.SECONDS);
                assertTrue(first.skippedRecipients.containsKey(target));assertTrue(readable(fixture.messages,"first remembered skip").encrypted);
                assertTrue(probing.await(10,TimeUnit.SECONDS));assertFalse(earlyProbe.get(),"Recovery must begin after the room ciphertext is sent");
                for(String body:Arrays.asList("blocked probe second send","blocked probe third send")) {
                    XmppRoomMessage next=sends.submit(() -> fixture.alice.rooms.send(fixture.room,body,true,Collections.emptySet(),true)).get(10,TimeUnit.SECONDS);
                    assertTrue(next.skippedRecipients.containsKey(target));assertTrue(readable(fixture.messages,body).encrypted);
                }
                assertEquals(2,lookups.get(),"Remembered failure must avoid foreground lookup and coalesce the blocked background job");
                release.countDown();
                eventually(() -> fixture.snapshots.stream().anyMatch(snapshot -> !snapshot.containsKey(target)));
                XmppRoomMessage unverified=fixture.alice.rooms.send(fixture.room,"recovery must not approve identity",true,Collections.emptySet(),true);
                assertTrue(unverified.skippedRecipients.keySet().stream().anyMatch(key -> key.startsWith(target+" · device ")));
                assertTrue(readable(fixture.messages,"recovery must not approve identity").encrypted);
                for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:fixture.alice.devices(target)) fixture.alice.decide(device,fixture.alice.fingerprint(device),true);
                assertFalse(fixture.alice.rooms.send(fixture.room,"recovered recipient eligible",true,Collections.emptySet(),true).skippedRecipients.containsKey(target));
                assertTrue(readable(fixture.messages,"recovered recipient eligible").encrypted);
            } finally { release.countDown();fixture.alice.connection().removeStanzaInterceptor(sentWire); }
        } finally { release.countDown();sends.shutdownNow(); }
    }
    @Test void allSkippedDeviceRecoveryFetchesOnceAndLeavesRatchetAndTrustForForegroundSend() throws Exception {
        String key="callsbob@localhost · device 3912";
        CountDownLatch fetching=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService sends=Executors.newSingleThreadExecutor();
        try(RecoveryRoom fixture=new RecoveryRoom(3911,false,Collections.singletonMap(key,"Previously unavailable bundle"))) {
            org.jivesoftware.smackx.omemo.OmemoManager manager=privateField(fixture.alice,"omemo");
            org.jivesoftware.smackx.omemo.internal.OmemoDevice recipient=new org.jivesoftware.smackx.omemo.internal.OmemoDevice(JidCreate.bareFrom(fixture.bob.ownJid()),fixture.bob.ownDeviceId());
            java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
            org.jivesoftware.smack.StanzaListener blockBundle=stanza -> {
                requests.incrementAndGet();fetching.countDown();
                try { if(!release.await(30,TimeUnit.SECONDS)) throw new AssertionError("Fixture bundle probe remained blocked"); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            fixture.alice.connection().addStanzaInterceptor(blockBundle,stanza -> stanza instanceof org.jivesoftware.smack.packet.IQ && stanza.toXML().toString().contains("eu.siacs.conversations.axolotl.bundles:3912"));
            try {
                for(int attempt=0;attempt<2;attempt++) {
                    OmemoSendException failure=sends.submit(() -> assertThrows(OmemoSendException.class,() -> fixture.alice.rooms.send(fixture.room,"all recipients remembered unavailable",true,Collections.emptySet(),true))).get(10,TimeUnit.SECONDS);
                    assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,failure.reason);
                    assertTrue(fetching.await(10,TimeUnit.SECONDS));
                }
                assertEquals(1,requests.get(),"All-skipped attempts must schedule only one blocked public bundle probe");
                assertTrue(org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.needsSession(manager,recipient));
                release.countDown();
                eventually(() -> { synchronized(privateField(fixture.alice.rooms,"unavailableLock")) { Map<?,?> recovered=privateField(fixture.alice.rooms,"recoveredBundles");return recovered.containsKey(key); } });
                assertTrue(org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.needsSession(manager,recipient),"Recovery may fetch public keys but must not advance Signal state");
                int fetched=requests.get();
                assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,assertThrows(OmemoSendException.class,() -> fixture.alice.rooms.send(fixture.room,"cached bundle still requires trust",true,Collections.emptySet(),true)).reason);
                assertEquals(fetched,requests.get(),"Foreground preparation must consume the recovered bundle without another fetch");
                fixture.alice.decide(recipient,fixture.alice.fingerprint(recipient),true);
                assertTrue(fixture.alice.rooms.send(fixture.room,"cached device recovered",true,Collections.emptySet(),true).skippedRecipients.isEmpty());
                assertTrue(readable(fixture.messages,"cached device recovered").encrypted);
                fixture.alice.rooms.retryUnavailable();
                assertTrue(fixture.snapshots.stream().anyMatch(Map::isEmpty));
            } finally { release.countDown();fixture.alice.connection().removeStanzaInterceptor(blockBundle); }
        } finally { release.countDown();sends.shutdownNow(); }
    }
    @Test void joinNativeRoomTransportEncryptOfflineMembersAndRejoinHistory() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),com.negzero.monogram.xmpp.LegacyOmemoTestDatabase.shared());
        BlockingQueue<XmppRoomMessage> aliceMessages=new LinkedBlockingQueue<>(),bobMessages=new LinkedBlockingQueue<>();
        String room="friends@conference.localhost";
        try(XmppSession alice=session("alice",701,aliceMessages); XmppSession bob=session("bob",702,bobMessages)) {
            alice.connect(); bob.connect();
            assertTrue(alice.rooms.services().contains(JidCreate.domainBareFrom("conference.localhost")));
            MultiUserChat peer=MultiUserChatManager.getInstanceFor(bob.connection()).getMultiUserChat(JidCreate.entityBareFrom(room));
            peer.create(Resourcepart.from("Bob"));
            FillableForm form=peer.getConfigurationForm().getFillableForm();
            form.setAnswer("muc#roomconfig_persistentroom",true);
            form.setAnswer("muc#roomconfig_membersonly",true);
            form.setAnswer("muc#roomconfig_whois","anyone");
            form.setAnswer("muc#roomconfig_enablearchiving",true); peer.sendConfigurationForm(form);
            peer.grantMembership(JidCreate.bareFrom("roomalice@localhost"));
            peer.leave();
            bob.rooms.join(room,"Bob",null); alice.rooms.join(room,"Alice",null);
            assertEquals(2,alice.rooms.state(room).occupants.size());
            assertThrows(org.jivesoftware.smack.XMPPException.XMPPErrorException.class,() -> alice.rooms.join("missing@conference.localhost","Alice",null));
            assertThrows(UnsupportedOperationException.class,() -> alice.send(room+"/Bob","must not strip occupant",false));
            assertThrows(UnsupportedOperationException.class,() -> alice.send(room,"must not become a direct chat",false));
            MessageMarkup plainMarkup=formatted("room hello");
            XmppRoomMessage sent=alice.rooms.send(room,plainMarkup.body,false,null,null,Collections.emptySet(),false,plainMarkup);
            XmppRoomMessage incoming=readable(bobMessages,plainMarkup.body);
            assertEquals("room hello",incoming.markup.display().body);assertFalse(incoming.markup.ranges.isEmpty());
            assertEquals(room+"/Alice",incoming.sender); assertFalse(incoming.outgoing); assertFalse(incoming.encrypted);
            assertEquals(sent.ids.get("origin"),incoming.ids.get("origin"));
            assertEquals("roomalice@localhost",incoming.realSender);
            assertNotNull(incoming.occupantId); assertTrue(incoming.realSenderVerified);
            ReplyReference answer=new ReplyReference(incoming.sender,incoming.ids.get("archive")).quoted("room hello");
            MessageMarkup replyMarkup=formatted("room reply");
            bob.rooms.send(room,replyMarkup.body,false,answer,null,Collections.emptySet(),false,replyMarkup);
            XmppRoomMessage replied=readable(aliceMessages,answer.wireBody(replyMarkup.body));
            assertEquals(incoming.ids.get("archive"),replied.reply.id); assertEquals(replyMarkup.body,replied.reply.withoutFallback(replied.body));
            MessageMarkup plainEdit=formatted("room edited");
            alice.rooms.send(room,plainEdit.body,false,null,new MessageCorrection(sent.ids.get("stanza")),Collections.emptySet(),false,plainEdit);
            XmppRoomMessage plainEdited=readable(bobMessages,plainEdit.body);assertEquals(sent.ids.get("stanza"),plainEdited.correction.id);assertEquals("room edited",plainEdited.markup.display().body);
            bob.rooms.sendReactions(room,new MessageReactions(incoming.ids.get("archive"),Arrays.asList("👍","🐢")));
            XmppRoomMessage roomReaction=null; long reactionDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<reactionDeadline) { XmppRoomMessage next=aliceMessages.poll(100,TimeUnit.MILLISECONDS); if(next!=null && next.reactions!=null) { roomReaction=next; break; } }
            assertNotNull(roomReaction); assertNull(roomReaction.body); assertEquals(incoming.ids.get("archive"),roomReaction.reactions.id); assertEquals("roombob@localhost",roomReaction.realSender); assertNotNull(roomReaction.occupantId);
            assertEquals("participant",alice.rooms.state(room).role);
            assertTrue(alice.rooms.archive(room,"",null,10).messages.stream().anyMatch(m -> sent.ids.get("origin").equals(m.ids.get("origin")) && m.outgoing));
            byte[] avatar=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
            bob.profiles.avatar(avatar);
            assertArrayEquals(avatar,alice.profiles.load(room+"/Bob").avatar);
            org.jivesoftware.smack.packet.IQ roomCard=new org.jivesoftware.smack.packet.IQ("vCard",XmppProfile.VCARD) {
                @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
                    xml.rightAngleBracket(); xml.append("<PHOTO><TYPE>image/png</TYPE><BINVAL>"+java.util.Base64.getEncoder().encodeToString(avatar)+"</BINVAL></PHOTO>"); return xml;
                }
            };
            roomCard.setTo(JidCreate.entityBareFrom(room)); roomCard.setType(org.jivesoftware.smack.packet.IQ.Type.set);
            bob.connection().createStanzaCollectorAndSend(roomCard).nextResultOrThrow();
            assertArrayEquals(avatar,alice.profiles.loadRoom(room).avatar);
            assertThrows(org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException.class,() -> alice.rooms.send(room,"requires trust",true));
            assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,assertThrows(OmemoSendException.class,() -> alice.rooms.send(room,"Auto skip must not approve unknown fingerprints",true,Collections.emptySet(),true)).reason);
            for(String jid:new String[]{"roomalice@localhost","roombob@localhost"}) {
                for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:alice.devices(jid)) {
                    if(device.getJid().toString().equals(alice.ownJid()) && device.getDeviceId()==alice.ownDeviceId()) continue;
                    alice.decide(device,alice.fingerprint(device),true);
                }
                for(org.jivesoftware.smackx.omemo.internal.OmemoDevice device:bob.devices(jid)) {
                    if(device.getJid().toString().equals(bob.ownJid()) && device.getDeviceId()==bob.ownDeviceId()) continue;
                    bob.decide(device,bob.fingerprint(device),true);
                }
            }
            java.util.concurrent.atomic.AtomicInteger repeatedLists=new java.util.concurrent.atomic.AtomicInteger();
            String unavailable="notregistered@localhost";
            peer.grantMembership(JidCreate.bareFrom(unavailable));
            assertEquals(OmemoSendException.Reason.NO_DEVICES,assertThrows(OmemoSendException.class,() -> alice.rooms.send(room,"must not silently omit a member",true)).reason);
            XmppRoomMessage automatic=alice.rooms.send(room,"automatic room recipients",true,Collections.emptySet(),true);
            assertTrue(automatic.skippedRecipients.containsKey(unavailable)); assertTrue(readable(bobMessages,"automatic room recipients").encrypted);
            org.jivesoftware.smackx.pubsub.LeafNode devicesNode=org.jivesoftware.smackx.pubsub.PubSubManager.getInstanceFor(bob.connection(),JidCreate.entityBareFrom(bob.ownJid())).getLeafNode("eu.siacs.conversations.axolotl.devicelist");
            devicesNode.publish(new org.jivesoftware.smackx.pubsub.PayloadItem<>("current",new org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl(new HashSet<>(Arrays.asList(bob.ownDeviceId(),803)))));
            assertTrue(alice.devices(bob.ownJid()).stream().anyMatch(device -> device.getDeviceId()==803));
            XmppRoomMessage partial=alice.rooms.send(room,"one unreachable device",true,Collections.emptySet(),true);
            assertTrue(partial.skippedRecipients.keySet().stream().anyMatch(key -> key.endsWith("device 803")));
            assertTrue(readable(bobMessages,"one unreachable device").encrypted);
            java.util.concurrent.atomic.AtomicInteger strictBundles=new java.util.concurrent.atomic.AtomicInteger(),strictRoomMessages=new java.util.concurrent.atomic.AtomicInteger();
            org.jivesoftware.smack.StanzaListener strictProbe=stanza -> {
                if(stanza instanceof org.jivesoftware.smack.packet.IQ && stanza.toXML().toString().contains("eu.siacs.conversations.axolotl.bundles:803")) strictBundles.incrementAndGet();
                if(stanza instanceof org.jivesoftware.smack.packet.Message && ((org.jivesoftware.smack.packet.Message)stanza).getType()==org.jivesoftware.smack.packet.Message.Type.groupchat
                        && stanza.getTo()!=null && stanza.getTo().toString().equals(room)) strictRoomMessages.incrementAndGet();
            };
            alice.connection().addStanzaInterceptor(strictProbe,stanza -> true);
            try {
                OmemoSendException strictFailure=assertThrows(OmemoSendException.class,
                        () -> alice.rooms.send(room,"Strict must not reuse automatic device skips",true,Collections.singleton(unavailable)));
                assertEquals(OmemoSendException.Reason.DEVICE_FAILURE,strictFailure.reason);assertTrue(strictFailure.detail.contains("device 803"));
                assertTrue(strictBundles.get()>0,"Strict mode must retry the cold device despite automatic backoff");
                assertEquals(0,strictRoomMessages.get(),"A missing required device must prevent every room ciphertext send");
            } finally { alice.connection().removeStanzaInterceptor(strictProbe); }
            devicesNode.publish(new org.jivesoftware.smackx.pubsub.PayloadItem<>("current",new org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl(Collections.singleton(bob.ownDeviceId()))));
            alice.devices(bob.ownJid());
            Set<String> excluded=Collections.singleton(unavailable);
            alice.connection().addStanzaSendingListener(stanza -> repeatedLists.incrementAndGet(),stanza -> stanza instanceof org.jivesoftware.smack.packet.IQ && stanza.toXML().toString().contains("eu.siacs.conversations.axolotl.devicelist"));
            java.util.concurrent.atomic.AtomicReference<org.jivesoftware.smack.packet.Message> encryptedFormattingWire=new java.util.concurrent.atomic.AtomicReference<>();
            alice.connection().addStanzaInterceptor(stanza -> encryptedFormattingWire.set((org.jivesoftware.smack.packet.Message)stanza),stanza -> stanza instanceof org.jivesoftware.smack.packet.Message
                    && stanza.hasExtension("encrypted","eu.siacs.conversations.axolotl"));
            MessageMarkup privateMarkup=formatted("private room hello");
            alice.rooms.send(room,privateMarkup.body,true,null,null,excluded,false,privateMarkup);
            XmppRoomMessage privateOriginal=readable(bobMessages,privateMarkup.body); assertTrue(privateOriginal.encrypted);
            assertEquals("private room hello",privateOriginal.markup.display().body);
            assertNotNull(encryptedFormattingWire.get());assertFalse(encryptedFormattingWire.get().hasExtension("markup",MessageMarkup.NAMESPACE));assertFalse(encryptedFormattingWire.get().hasExtension("unstyled",MessageMarkup.STYLING));
            ReplyReference secretReply=new ReplyReference(privateOriginal.sender,privateOriginal.ids.get("archive")).quoted("private room hello");
            MessageMarkup secretAnswerMarkup=formatted("secret answer");
            bob.rooms.send(room,secretAnswerMarkup.body,true,secretReply,null,excluded,false,secretAnswerMarkup);
            XmppRoomMessage secretAnswer=readable(aliceMessages,secretReply.wireBody(secretAnswerMarkup.body));
            assertTrue(secretAnswer.encrypted); assertEquals(privateOriginal.ids.get("archive"),secretAnswer.reply.id);
            MessageMarkup secretEdit=formatted("private corrected");
            alice.rooms.send(room,secretEdit.body,true,null,new MessageCorrection(privateOriginal.ids.get("stanza")),excluded,false,secretEdit);
            XmppRoomMessage privateEdited=readable(bobMessages,secretEdit.body);assertEquals(privateOriginal.ids.get("stanza"),privateEdited.correction.id);assertEquals("private corrected",privateEdited.markup.display().body);
            assertEquals(0,repeatedLists.get(),"Recently reviewed device lists must not be re-fetched for every room send");
            File source=new File(root,"room-photo.png"),downloaded=new File(root,"received-room-file");
            java.nio.file.Files.write(source.toPath(),avatar);
            FileLink photo=FileTransfer.upload(alice.connection(),source,"image/png",false,new FileTransfer.Job(),null);
            FileShare described=FileShare.describe(photo,source,"room-photo.png","image/png");
            MessageMarkup photoCaption=formatted("Room photo");
            alice.rooms.sendFile(room,photo,described,photoCaption.body,false,answer,Collections.emptySet(),true,photoCaption);
            XmppRoomMessage receivedPhoto=readable(bobMessages,answer.wireBody(described.body(photoCaption.body)));
            assertEquals(answer.id,receivedPhoto.reply.id); assertNotNull(receivedPhoto.attachment); assertNotNull(receivedPhoto.sharing); assertEquals("Room photo",receivedPhoto.captionMarkup.display().body);
            FileTransfer.download(receivedPhoto.sharing,downloaded,new FileTransfer.Job(),null,null); assertArrayEquals(avatar,java.nio.file.Files.readAllBytes(downloaded.toPath()));
            MessageMarkup photoEdit=formatted("Edited room caption");
            alice.rooms.correctFile(room,described,photoEdit.body,answer,new MessageCorrection(receivedPhoto.ids.get("stanza")),Collections.emptySet(),true,photoEdit);
            XmppRoomMessage editedPhoto=readable(bobMessages,answer.wireBody(described.body(photoEdit.body)));assertEquals("Edited room caption",editedPhoto.captionMarkup.display().body);
            FileLink secretPhoto=FileTransfer.upload(alice.connection(),source,"image/png",true,new FileTransfer.Job(),null);
            alice.rooms.sendFile(room,secretPhoto,null,"",true,secretReply,excluded,true);
            XmppRoomMessage receivedSecret=readable(bobMessages,secretReply.wireBody(secretPhoto.wireUrl));
            assertEquals(secretReply.id,receivedSecret.reply.id); assertTrue(receivedSecret.encrypted); assertNotNull(receivedSecret.attachment);
            FileTransfer.download(receivedSecret.attachment,downloaded,new FileTransfer.Job(),null); assertArrayEquals(avatar,java.nio.file.Files.readAllBytes(downloaded.toPath()));
            byte[] audio=new byte[]{'O','g','g','S',0,2,3,4}; java.nio.file.Files.write(source.toPath(),audio);
            FileLink voice=FileTransfer.upload(alice.connection(),source,"audio/ogg",true,new FileTransfer.Job(),null);
            alice.rooms.sendFile(room,voice,null,"",true,excluded);
            XmppRoomMessage receivedVoice=readable(bobMessages,voice.wireUrl);
            FileTransfer.download(receivedVoice.attachment,downloaded,new FileTransfer.Job(),null); assertArrayEquals(audio,java.nio.file.Files.readAllBytes(downloaded.toPath()));
            peer.revokeMembership(JidCreate.bareFrom(unavailable));
            bob.rooms.leave(room);
            alice.rooms.send(room,"encrypted while Bob is absent",true);
            bob.rooms.join(room,"Bob",null);
            assertTrue(bob.rooms.archive(room,"",null,50).messages.stream().anyMatch(m -> m.readable && m.encrypted && "encrypted while Bob is absent".equals(m.body)),"Offline members recover their encrypted messages through the separately scheduled archive");
            java.util.List<String> presence=new java.util.concurrent.CopyOnWriteArrayList<>();
            alice.connection().addAsyncStanzaListener(stanza -> presence.add(stanza.toXML().toString()),stanza -> stanza instanceof org.jivesoftware.smack.packet.Presence);
            try { alice.rooms.nickname(room,"Alice2"); } catch(Exception failure) { throw new AssertionError(presence.toString(),failure); }
            alice.rooms.send(room,"after nickname",false);
            assertEquals(room+"/Alice2",readable(bobMessages,"after nickname").sender);
            assertTrue(bob.rooms.hasArchive(room));
            for(int i=0;i<120;i++) alice.rooms.send(room,"archive fill "+i,false);
            readable(bobMessages,"archive fill 119");
            Set<String> archived=new HashSet<>(); Set<String> bodies=new HashSet<>(); String before=""; int pages=0;
            while(true) {
                XmppRooms.ArchivePage page=bob.rooms.archive(room,before,null,30); pages++;
                for(XmppRoomMessage message:page.messages) { assertTrue(message.archived); assertTrue(archived.add(message.ids.get("mam"))); if(message.readable) bodies.add(message.body); }
                if(page.complete) break;
                assertNotNull(page.firstId); assertNotEquals(before,page.firstId); before=page.firstId;
                assertTrue(pages<10,"Archive pagination must finish");
            }
            assertTrue(pages>3); assertTrue(bodies.contains(plainMarkup.body)); assertTrue(bodies.contains("archive fill 119"));
            XmppRooms.ArchivePage latest=bob.rooms.archive(room,"",null,1);
            alice.rooms.send(room,"new archive message",false); readable(bobMessages,"new archive message");
            assertEquals("new archive message",bob.rooms.archive(room,null,latest.lastId,10).messages.get(0).body);
            alice.rooms.leave(room); assertFalse(alice.rooms.state(room).joined);
            assertThrows(IllegalStateException.class,() -> alice.rooms.send(room,"must not send",false));
        }
    }
}
