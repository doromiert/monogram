// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_PQ_IT",matches="1")
class PqOmemoServerTest {
    static final class Client implements AutoCloseable {
        final XMPPTCPConnection connection;
        final PqOmemoNativeTest.Database records = new PqOmemoNativeTest.Database();
        final PqOmemoDevice device;
        XmppPqOmemo service;
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        Client(String user) throws Exception {
            connection = new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder().setXmppDomain("localhost")
                    .setHost("127.0.0.1").setPort(Integer.parseInt(System.getenv("MONOGRAM_PQ_IT_PORT")))
                    .setUsernameAndPassword(user,"monogram-pq-test").setResource("PQ-" + UUID.randomUUID())
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.required).build());
            connection.addAsyncStanzaListener(stanza -> messages.add((Message)stanza),stanza -> stanza instanceof Message && stanza.hasExtension("encrypted",XmppPqOmemo.NS));
            connection.connect().login();
            device = new PqOmemoDevice(user + "@localhost",records); service = new XmppPqOmemo(connection,device,peer -> {});
            service.initialize(); assertTrue(connection.isSecureConnection());
        }
        void trust(String peer) throws Exception { for(PqOmemoDevice.Fingerprint key : service.discover(peer)) device.trust(peer,key.device,key.fingerprint,true); }
        @Override public void close() { service.close(); connection.disconnect(); }
    }
    @Test void publicPepDiscoveryAndPrivateMessagesWithDurableAcceptance() throws Exception {
        try(Client alice = new Client("pqalice"); Client bob = new Client("pqbob"); Client another = new Client("pqalice")) {
            assertEquals(1,alice.service.discover(alice.device.jid).size());
            alice.trust(bob.device.jid); alice.trust(alice.device.jid); bob.trust(alice.device.jid);
            Message clear = new Message(bob.connection.getUser(),Message.Type.chat); clear.setFrom(alice.connection.getUser());
            clear.setBody("private PQ caption 😀"); clear.setStanzaId(UUID.randomUUID().toString()); new MessageCorrection("original").addTo(clear);
            Message wire = alice.service.encrypt(clear);
            assertFalse(wire.toXML().toString().contains("private PQ caption")); assertFalse(wire.toXML().toString().contains("message-correct")); assertNull(wire.getBody());
            alice.connection.sendStanza(wire); Message packet = bob.messages.poll(10,TimeUnit.SECONDS); assertNotNull(packet);
            XmppPqOmemo.Received received = bob.service.receive(packet,alice.device.jid,bob.device.jid,System.currentTimeMillis(),null);
            assertEquals(clear.getBody(),received.content.getBody()); assertEquals("original",MessageCorrection.from(received.content).id);
            assertEquals(1,bob.service.pending().size());
            // A retransmission before native message-store acceptance uses the journal, not the ratchet twice.
            assertEquals(received.digest,bob.service.receive(packet,alice.device.jid,bob.device.jid,System.currentTimeMillis(),null).digest);
            bob.service.accepted(received); assertTrue(bob.service.pending().isEmpty());
        }
    }
    @Test void repairsPublicKeyAccessAndPreservesOtherDevicesAndClassicalNodes() throws Exception {
        try(Client alice = new Client("pqaclalice"); Client bob = new Client("pqaclbob")) {
            alice.service.close();
            PubSubManager own = PubSubManager.getInstanceFor(alice.connection,JidCreate.entityBareFrom(alice.device.jid));
            LeafNode devices = own.getLeafNode(PqOmemoPep.DEVICES);
            StandardExtensionElement metadata = StandardExtensionElement.builder("device",XmppPqOmemo.NS).addAttribute("id","303").addAttribute("label","Other client").addAttribute("labelsig","preserved-signature").build();
            devices.publish(new PayloadItem<ExtensionElement>("current",StandardExtensionElement.builder("devices",XmppPqOmemo.NS).addElement(metadata).build()));
            for(String node : new String[]{PqOmemoPep.DEVICES,PqOmemoPep.BUNDLES}) {
                Node value = own.getNode(node); org.jivesoftware.smackx.pubsub.form.FillableConfigureForm config = value.getNodeConfiguration().getFillableForm();
                config.setAccessModel(AccessModel.whitelist); value.sendConfigurationForm(config);
            }
            byte[] original = alice.device.prepare().identity();
            alice.service = new XmppPqOmemo(alice.connection,alice.device,peer -> {}); alice.service.initialize();
            PqOmemoPep remote = new PqOmemoPep(bob.connection,bob.device);
            StandardExtensionElement list = remote.item(alice.device.jid,PqOmemoPep.DEVICES,null);
            assertTrue(Omemo2DeviceList.ids(list,XmppPqOmemo.NS).containsAll(Arrays.asList(alice.device.id,303)));
            StandardExtensionElement preserved = list.getElements().stream().filter(value -> "303".equals(value.getAttributeValue("id"))).findFirst().get();
            assertEquals("Other client",preserved.getAttributeValue("label")); assertEquals("preserved-signature",preserved.getAttributeValue("labelsig"));
            PqOmemoBundle published = PqOmemoBundle.parse(remote.item(alice.device.jid,PqOmemoPep.BUNDLES,Integer.toString(alice.device.id)));
            assertArrayEquals(original,published.identity());
            for(String node : new String[]{PqOmemoPep.DEVICES,PqOmemoPep.BUNDLES}) assertEquals(AccessModel.open,own.getNode(node).getNodeConfiguration().getAccessModel());
            assertThrows(Exception.class,() -> own.getNode(XmppOmemo2.DEVICES_NODE));
        }
    }
}
