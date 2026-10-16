package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.mam.MamManager;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
class Omemo2IntegrationTest {
    static final class Client implements AutoCloseable {
        final XMPPTCPConnection connection;
        final Omemo2Device device;
        final XmppOmemo2 service;
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>(), carbons = new LinkedBlockingQueue<>();
        Client(String user,int id) throws Exception {
            connection = new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder().setXmppDomain("localhost")
                    .setHost("127.0.0.1").setPort(Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")))
                    .setUsernameAndPassword(user,"monogram-test").setResource("Modern-"+id).setSecurityMode(ConnectionConfiguration.SecurityMode.required).build());
            connection.addAsyncStanzaListener(stanza -> messages.add((Message)stanza),stanza -> stanza instanceof Message && stanza.hasExtension("encrypted",XmppOmemo2.NS));
            connection.connect().login();
            device = new Omemo2Device(user+"@localhost",id,new Omemo2TestDatabase()); service = new XmppOmemo2(connection,device,jid -> {});
            service.initialize(); assertTrue(connection.isSecureConnection());
            CarbonManager.getInstanceFor(connection).addCarbonCopyReceivedListener((direction,carbon,carrier) -> {
                if (carbon.hasExtension("encrypted",XmppOmemo2.NS)) carbons.add(carbon);
            });
            CarbonManager.getInstanceFor(connection).enableCarbons();
        }
        void trust(String jid) throws Exception {
            for (Map.Entry<Integer,Omemo2Bundle> peer : service.discover(jid).entrySet()) device.decide(jid,peer.getKey(),peer.getValue().identity(),true);
        }
        Message text(Client peer,String body) {
            Message m = new Message(peer.connection.getUser(),Message.Type.chat); m.setFrom(connection.getUser()); m.setBody(body); return m;
        }
        Message packet() throws Exception {
            Message packet = messages.poll(15,TimeUnit.SECONDS); assertNotNull(packet,"Expected an encrypted packet on the disposable connection"); return packet;
        }
        void acknowledge(XmppOmemo2.Received received) throws Exception { if (received.acknowledgement != null) connection.sendStanza(received.acknowledgement); }
        @Override public void close() { service.close(); connection.disconnect(); }
    }
    @Test void publicBundlesPrivateCaptionsCarbonsArchiveAndSessionAcknowledgements() throws Exception {
        try (Client alice = new Client("modernalice",101); Client bob = new Client("modernbob",202); Client other = new Client("modernalice",303)) {
            assertEquals(1,alice.service.discover(alice.device.jid).size(),"Bundle node must retain the other own device");
            alice.trust(bob.device.jid); alice.trust(alice.device.jid); bob.trust(alice.device.jid); other.trust(alice.device.jid);
            Message privateMessage = alice.text(bob,"A photo of us 😀");
            File source = new File(System.getenv("MONOGRAM_IT_STATE"),"private-caption-source.png");
            byte[] picture = new byte[8193]; new java.security.SecureRandom().nextBytes(picture); java.nio.file.Files.write(source.toPath(),picture);
            FileLink link = FileTransfer.upload(alice.connection,source,"payload.bin","application/octet-stream",true,new FileTransfer.Job(),null);
            FileShare sharing = FileShare.describe(link,source,"Me and friends.png","image/png");
            ReplyReference reply = new ReplyReference(bob.device.jid,"original").quoted("Quoted 🦄");
            privateMessage.setBody(reply.wireBody(sharing.body("A photo of us 😀"))); reply.addTo(privateMessage); sharing.addTo(privateMessage,privateMessage.getBody());
            new MessageCorrection("original").addTo(privateMessage);
            Message wire = alice.service.encrypt(privateMessage); String xml = wire.toXML().toString();
            assertFalse(xml.contains("A photo of us")); assertFalse(xml.contains("Me and friends")); assertFalse(xml.contains("aesgcm")); assertNull(wire.getBody());
            assertFalse(xml.contains(link.wireUrl.substring(link.wireUrl.indexOf('#')+1)),"The XMPP server must never see the file key");
            alice.connection.sendStanza(wire);
            Message received = bob.packet(); XmppOmemo2.Received decoded = bob.service.receive(received); assertNotNull(decoded.content);
            XmppMessage model = XmppMessage.modern(received,decoded.content,bob.connection.getUser().asEntityBareJid(),null,null);
            assertEquals(XmppMessage.Encryption.OMEMO2,model.encryption); assertEquals("A photo of us 😀",model.caption); assertEquals(link.wireUrl,model.attachment.wireUrl);
            FileShare file = FileShare.from(decoded.content); assertNotNull(file); assertEquals("Me and friends.png",file.name);
            assertEquals("A photo of us 😀",file.caption(decoded.content,decoded.content.getBody(),ReplyReference.from(decoded.content,decoded.content.getBody())));
            assertEquals("original",MessageCorrection.from(decoded.content).id);
            File download = new File(System.getenv("MONOGRAM_IT_STATE"),"private-caption-downloaded.png");
            FileTransfer.download(file,download,new FileTransfer.Job(),null,null); assertArrayEquals(picture,java.nio.file.Files.readAllBytes(download.toPath()));
            bob.acknowledge(decoded);
            XmppOmemo2.Received acknowledgement = alice.service.receive(alice.packet()); assertNull(acknowledgement.content); assertNull(acknowledgement.acknowledgement);
            Message carbon = other.carbons.poll(15,TimeUnit.SECONDS); assertNotNull(carbon);
            XmppOmemo2.Received ownCopy = other.service.receive(carbon); assertEquals(decoded.content.getBody(),ownCopy.content.getBody()); other.acknowledge(ownCopy);
            XmppOmemo2.Received ownAck = alice.service.receive(alice.packet()); assertNull(ownAck.content);
            Message replyWire = bob.service.encrypt(bob.text(alice,"Got the photo 🦄")); bob.connection.sendStanza(replyWire);
            assertEquals("Got the photo 🦄",alice.service.receive(alice.packet()).content.getBody());
            MamManager.MamQuery archive = MamManager.getInstanceFor(bob.connection).queryArchive(MamManager.MamQueryArgs.builder().setResultPageSizeTo(100).build());
            Message archived = archive.getMamResultExtensions().stream().map(result -> result.getForwarded().getForwardedStanza())
                    .filter(m -> wire.getStanzaId().equals(m.getStanzaId())).findFirst().orElseThrow(() -> new AssertionError("Encrypted caption missing from MAM"));
            assertNull(archived.getBody()); assertFalse(archived.toXML().toString().contains("Me and friends"));
            assertThrows(Omemo2Native.Failure.class,() -> bob.service.receive(archived),"Already-consumed archive ciphertext must not reset a ratchet");
            Message after = alice.service.encrypt(alice.text(bob,"Live after MAM replay")); alice.connection.sendStanza(after);
            assertEquals("Live after MAM replay",bob.service.receive(bob.packet()).content.getBody());
        }
    }
    @Test void malformedRoutingAndPayloadCannotConsumeAnOtherwiseValidMessage() throws Exception {
        try (Client alice = new Client("modernalice",404); Client bob = new Client("modernbob",505)) {
            alice.trust(bob.device.jid); alice.trust(alice.device.jid); bob.trust(alice.device.jid);
            Message clear = alice.text(bob,"Retain this after tampering"); Message wire = alice.service.encrypt(clear);
            Message wrong = PacketParserUtils.parseStanza(wire.toXML().toString()); wrong.setTo(JidCreate.entityBareFrom("unrelated@localhost"));
            assertThrows(java.security.GeneralSecurityException.class,() -> bob.service.receive(wrong));
            String changed = wire.toXML().toString().replaceFirst("<payload>","<payload>AAAA");
            Message corrupt = PacketParserUtils.parseStanza(changed);
            assertThrows(java.security.GeneralSecurityException.class,() -> bob.service.receive(corrupt));
            assertEquals("Retain this after tampering",bob.service.receive(wire).content.getBody());
        }
    }
    @Test void nonRosterDeviceUpdatesInvalidateCachesWithoutApprovingNewKeys() throws Exception {
        try (Client alice = new Client("devicealice",111); Client bob = new Client("devicebob",222)) {
            String peer = bob.device.jid; alice.trust(peer); bob.trust(alice.device.jid);
            alice.service.encrypt(alice.text(bob,"Warm caches"));
            try (Client added = new Client("devicebob",333)) {
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while (!alice.device.devices(peer).contains(333) && System.nanoTime()<deadline) Thread.sleep(50);
                assertTrue(alice.device.devices(peer).contains(333),"PEP changes must reach a non-roster contact");
                byte[] key = added.device.prepare().identity();
                assertEquals(Omemo2Device.Trust.UNDECIDED,alice.device.trust(peer,333,key));
                assertThrows(java.security.GeneralSecurityException.class,() -> alice.service.encrypt(alice.text(bob,"Needs new approval")));
                alice.device.decide(peer,333,key,true);
                Message wire = alice.service.encrypt(alice.text(bob,"Approved new device")); alice.connection.sendStanza(wire);
                assertEquals("Approved new device",bob.service.receive(bob.packet()).content.getBody());
            }
        }
    }
}
