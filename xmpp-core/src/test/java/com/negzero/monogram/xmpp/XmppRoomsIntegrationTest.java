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
    XmppSession session(String user,int device,BlockingQueue<XmppRoomMessage> messages) throws Exception {
        return new XmppSession(new Account("room"+user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Room-"+device),
                "monogram-test",device,new File(root,"room-trust-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) { fail("Room content must not become a direct conversation"); }
            public void roomMessage(XmppRoomMessage message) { messages.add(message); }
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
    @Test void nativeCreationInvitationsConfigurationAndAvatar() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"));
        BlockingQueue<XmppRoomMessage> aliceMessages=new LinkedBlockingQueue<>(),bobMessages=new LinkedBlockingQueue<>();
        try(XmppSession alice=session("alice",3101,aliceMessages); XmppSession bob=session("bob",3102,bobMessages)) {
            alice.connect(); bob.connect();
            XmppRooms.State created=alice.rooms.createPrivate("Alice","Native group");
            String room=created.room.toString(); assertEquals("Native group",created.title); assertEquals("owner",created.affiliation);
            RoomInfo info=MultiUserChatManager.getInstanceFor(alice.connection()).getRoomInfo(created.room);
            assertTrue(info.isMembersOnly()); assertTrue(info.isNonanonymous());
            assertThrows(Exception.class,() -> bob.rooms.join(room,"Bob",null));
            alice.rooms.invite(room,"roombob@localhost","Join this test room");
            Exception failure=null;
            for(int attempt=0;attempt<10;attempt++) {
                try { bob.rooms.join(room,"Bob",null); failure=null; break; }
                catch(Exception pending) { failure=pending; Thread.sleep(100); }
            }
            if(failure!=null) throw failure;
            XmppRooms.State changed=alice.rooms.configure(room,"Renamed group","Group description");
            assertEquals("Renamed group",changed.title); assertEquals("Group description",changed.description);
            assertThrows(Exception.class,() -> bob.rooms.configure(room,"Not the owner",null));
            assertTrue(MultiUserChatManager.getInstanceFor(alice.connection()).getRoomInfo(created.room).isMembersOnly());
            byte[] avatar=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
            alice.profiles.roomAvatar(room,avatar); assertArrayEquals(avatar,bob.profiles.loadRoom(room).avatar);
            alice.profiles.roomAvatar(room,new byte[0]); assertEquals(0,bob.profiles.loadRoom(room).avatar.length);
            assertEquals("",alice.rooms.configure(room,null,"").description);
        }
    }
    @Test void joinNativeRoomTransportEncryptOfflineMembersAndRejoinHistory() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"));
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
            XmppRoomMessage sent=alice.rooms.send(room,"room hello",false);
            XmppRoomMessage incoming=readable(bobMessages,"room hello");
            assertEquals(room+"/Alice",incoming.sender); assertFalse(incoming.outgoing); assertFalse(incoming.encrypted);
            assertEquals(sent.ids.get("origin"),incoming.ids.get("origin"));
            assertEquals("roomalice@localhost",incoming.realSender);
            assertNotNull(incoming.occupantId); assertTrue(incoming.realSenderVerified);
            ReplyReference answer=new ReplyReference(incoming.sender,incoming.ids.get("archive")).quoted("room hello");
            bob.rooms.send(room,"room reply",false,answer,null,Collections.emptySet());
            XmppRoomMessage replied=readable(aliceMessages,answer.wireBody("room reply"));
            assertEquals(incoming.ids.get("archive"),replied.reply.id); assertEquals("room reply",replied.reply.withoutFallback(replied.body));
            alice.rooms.send(room,"room edited",false,null,new MessageCorrection(sent.ids.get("stanza")),Collections.emptySet());
            assertEquals(sent.ids.get("stanza"),readable(bobMessages,"room edited").correction.id);
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
            assertEquals(OmemoSendException.Reason.TRUST_REQUIRED,assertThrows(OmemoSendException.class,() -> alice.rooms.send(room,"Auto skip must not approve unknown fingerprints",true,Collections.emptySet(),true)).reason);
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
            devicesNode.publish(new org.jivesoftware.smackx.pubsub.PayloadItem<>("current",new org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl(Collections.singleton(bob.ownDeviceId()))));
            alice.devices(bob.ownJid());
            Set<String> excluded=Collections.singleton(unavailable);
            alice.connection().addStanzaSendingListener(stanza -> repeatedLists.incrementAndGet(),stanza -> stanza instanceof org.jivesoftware.smack.packet.IQ && stanza.toXML().toString().contains("eu.siacs.conversations.axolotl.devicelist"));
            alice.rooms.send(room,"private room hello",true,excluded);
            XmppRoomMessage privateOriginal=readable(bobMessages,"private room hello"); assertTrue(privateOriginal.encrypted);
            ReplyReference secretReply=new ReplyReference(privateOriginal.sender,privateOriginal.ids.get("archive")).quoted("private room hello");
            bob.rooms.send(room,"secret answer",true,secretReply,null,excluded);
            XmppRoomMessage secretAnswer=readable(aliceMessages,secretReply.wireBody("secret answer"));
            assertTrue(secretAnswer.encrypted); assertEquals(privateOriginal.ids.get("archive"),secretAnswer.reply.id);
            alice.rooms.send(room,"private corrected",true,null,new MessageCorrection(privateOriginal.ids.get("stanza")),excluded);
            assertEquals(privateOriginal.ids.get("stanza"),readable(bobMessages,"private corrected").correction.id);
            assertEquals(0,repeatedLists.get(),"Recently reviewed device lists must not be re-fetched for every room send");
            File source=new File(root,"room-photo.png"),downloaded=new File(root,"received-room-file");
            java.nio.file.Files.write(source.toPath(),avatar);
            FileLink photo=FileTransfer.upload(alice.connection(),source,"image/png",false,new FileTransfer.Job(),null);
            FileShare described=FileShare.describe(photo,source,"room-photo.png","image/png");
            alice.rooms.sendFile(room,photo,described,"Room photo",false,answer,Collections.emptySet(),true);
            XmppRoomMessage receivedPhoto=readable(bobMessages,answer.wireBody(described.body("Room photo")));
            assertEquals(answer.id,receivedPhoto.reply.id); assertNotNull(receivedPhoto.attachment); assertNotNull(receivedPhoto.sharing); assertEquals("Room photo",receivedPhoto.caption);
            FileTransfer.download(receivedPhoto.sharing,downloaded,new FileTransfer.Job(),null,null); assertArrayEquals(avatar,java.nio.file.Files.readAllBytes(downloaded.toPath()));
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
            assertTrue(pages>3); assertTrue(bodies.contains("room hello")); assertTrue(bodies.contains("archive fill 119"));
            XmppRooms.ArchivePage latest=bob.rooms.archive(room,"",null,1);
            alice.rooms.send(room,"new archive message",false); readable(bobMessages,"new archive message");
            assertEquals("new archive message",bob.rooms.archive(room,null,latest.lastId,10).messages.get(0).body);
            alice.rooms.leave(room); assertFalse(alice.rooms.state(room).joined);
            assertThrows(IllegalStateException.class,() -> alice.rooms.send(room,"must not send",false));
        }
    }
}
