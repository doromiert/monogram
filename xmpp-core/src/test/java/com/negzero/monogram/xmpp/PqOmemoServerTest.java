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
    private static void publishDevices(Client client,int... ids) throws Exception {
        publishDevices(client.connection,client.device.jid,ids);
    }
    static void publishDevices(org.jivesoftware.smack.XMPPConnection connection,String owner,int... ids) throws Exception {
        StandardExtensionElement.Builder list=StandardExtensionElement.builder("devices",XmppPqOmemo.NS);
        for(int id:ids)list.addElement(StandardExtensionElement.builder("device",XmppPqOmemo.NS).addAttribute("id",Integer.toString(id)).build());
        PubSubManager.getInstanceFor(connection,JidCreate.entityBareFrom(owner))
                .getLeafNode(PqOmemoPep.DEVICES).publish(new PayloadItem<ExtensionElement>("current",list.build()));
    }
    private static void removeBundle(Client client) throws Exception {
        PubSubManager.getInstanceFor(client.connection,JidCreate.entityBareFrom(client.device.jid))
                .getLeafNode(PqOmemoPep.BUNDLES).deleteItem(Integer.toString(client.device.id));
    }
    @Test void missingBundleDoesNotAbortOtherDevicesOrRequirePrekeysForEstablishedRatchet() throws Exception {
        try(Client alice=new Client("pqmissingalice");Client bob=new Client("pqmissingbob")) {
            int orphan=alice.device.id==1234567 || bob.device.id==1234567 ? 1234568 : 1234567;
            publishDevices(alice,orphan,alice.device.id);publishDevices(bob,orphan,bob.device.id);
            alice.trust(bob.device.jid);bob.trust(alice.device.jid);
            assertEquals(1,alice.service.discover(bob.device.jid).size());
            Message clear=new Message(bob.connection.getUser(),Message.Type.chat);clear.setFrom(alice.connection.getUser());
            clear.setBody("orphan device must not block a usable recipient");clear.setStanzaId(UUID.randomUUID().toString());
            Message wire=alice.service.encrypt(clear);
            assertFalse(wire.toXML().toString().contains(clear.getBody()));
            assertEquals(clear.getBody(),bob.service.receive(wire,alice.device.jid,bob.device.jid,System.currentTimeMillis(),null).content.getBody());
            // Lost server prekeys do not destroy a pinned established session.
            removeBundle(bob);assertTrue(alice.service.discover(bob.device.jid).get(0).active);
            clear.setStanzaId(UUID.randomUUID().toString());clear.setBody("continue the trusted ratchet");
            assertEquals(clear.getBody(),bob.service.receive(alice.service.encrypt(clear),alice.device.jid,bob.device.jid,System.currentTimeMillis(),null).content.getBody());
            // An observed key change cannot use the old pin as a missing-bundle fallback.
            try(PqOmemoRecords tx=alice.records.begin()) {
                tx.put(PqOmemoSignalStore.observed(PqOmemoDevice.remote(bob.device.jid,bob.device.id)),new byte[2625]);tx.commit();
            }
            assertFalse(alice.service.discover(bob.device.jid).get(0).active);
            Exception absent=assertThrows(java.security.GeneralSecurityException.class,() -> alice.service.encrypt(clear));
            assertTrue(XmppRequestException.describe(absent).contains("republish their keys"));
        }
    }
    @Test void orphanDevicesDoNotApproveValidUnknownKeysOrHideMalformedBundles() throws Exception {
        try(Client alice=new Client("pqtrustalice");Client bob=new Client("pqtrustbob")) {
            int orphan=bob.device.id==7654321 ? 7654322 : 7654321;
            publishDevices(bob,orphan,bob.device.id);
            alice.service.discover(bob.device.jid);
            Message clear=new Message(bob.connection.getUser(),Message.Type.chat);clear.setFrom(alice.connection.getUser());clear.setBody("no automatic trust");clear.setStanzaId(UUID.randomUUID().toString());
            assertThrows(org.signal.libsignal.protocol.UntrustedIdentityException.class,() -> alice.service.encrypt(clear));
            alice.trust(bob.device.jid);
            // Confirmed absence is special; invalid authenticated bundle data still fails closed.
            PubSubManager.getInstanceFor(bob.connection,JidCreate.entityBareFrom(bob.device.jid)).getLeafNode(PqOmemoPep.BUNDLES)
                    .publish(new PayloadItem<ExtensionElement>(Integer.toString(bob.device.id),StandardExtensionElement.builder("bundle",XmppPqOmemo.NS).build()));
            assertThrows(java.security.GeneralSecurityException.class,() -> alice.service.discover(bob.device.jid));
            assertThrows(java.security.GeneralSecurityException.class,() -> alice.service.encrypt(clear));
            bob.service.replenish();alice.service.discover(bob.device.jid);
            Node node=PubSubManager.getInstanceFor(bob.connection,JidCreate.entityBareFrom(bob.device.jid)).getNode(PqOmemoPep.BUNDLES);
            org.jivesoftware.smackx.pubsub.form.FillableConfigureForm config=node.getNodeConfiguration().getFillableForm();
            config.setAccessModel(AccessModel.whitelist);node.sendConfigurationForm(config);
            assertThrows(XmppRequestException.class,() -> alice.service.discover(bob.device.jid));
            assertThrows(XmppRequestException.class,() -> alice.service.encrypt(clear));
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
