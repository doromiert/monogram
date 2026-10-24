package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
class Omemo2PepAccessIntegrationTest {
    @Test void reconnectRepairsRestrictedNodesWithoutRosterApprovalOrReplacingOtherDeviceMetadata()throws Exception {
        try(Omemo2IntegrationTest.Client alice=new Omemo2IntegrationTest.Client("pepaclalice",101);
            Omemo2IntegrationTest.Client bob=new Omemo2IntegrationTest.Client("pepaclbob",202)) {
            PubSubManager own=PubSubManager.getInstanceFor(alice.connection,JidCreate.entityBareFrom(alice.device.jid));
            LeafNode devices=own.getLeafNode(XmppOmemo2.DEVICES_NODE);
            StandardExtensionElement signed=StandardExtensionElement.builder("device",XmppOmemo2.NS).addAttribute("id","303")
                .addAttribute("label","Other client").addAttribute("labelsig","keep-this-signature").build();
            devices.publish(new PayloadItem<ExtensionElement>("current",StandardExtensionElement.builder("devices",XmppOmemo2.NS).addElement(signed).build()));
            byte[] original=alice.device.prepare().identity();
            for(String node:new String[]{XmppOmemo2.DEVICES_NODE,XmppOmemo2.BUNDLES_NODE}) {
                Node published=own.getNode(node);
                org.jivesoftware.smackx.pubsub.form.FillableConfigureForm restricted=published.getNodeConfiguration().getFillableForm();
                restricted.setAccessModel(AccessModel.whitelist);published.sendConfigurationForm(restricted);
                assertEquals(AccessModel.whitelist,published.getNodeConfiguration().getAccessModel());
            }
            assertThrows(Exception.class,()->bob.service.discover(alice.device.jid));
            alice.service.initialize();
            for(String node:new String[]{XmppOmemo2.DEVICES_NODE,XmppOmemo2.BUNDLES_NODE})assertEquals(AccessModel.open,own.getNode(node).getNodeConfiguration().getAccessModel());
            // Read the real node from an unrelated account with no presence subscription.
            LeafNode remote=PubSubManager.getInstanceFor(bob.connection,JidCreate.entityBareFrom(alice.device.jid)).getLeafNode(XmppOmemo2.DEVICES_NODE);
            java.util.List<PayloadItem<ExtensionElement>> values=remote.getItems(1);
            StandardExtensionElement payload=org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE.parse(org.jivesoftware.smack.util.PacketParserUtils.getParserFor(values.get(0).getPayload().toXML().toString()));
            assertTrue(Omemo2DeviceList.ids(payload).contains(101));
            StandardExtensionElement preserved=payload.getElements("device").stream().filter(d->"303".equals(d.getAttributeValue("id"))).findFirst().get();
            assertEquals("Other client",preserved.getAttributeValue("label"));assertEquals("keep-this-signature",preserved.getAttributeValue("labelsig"));
            LeafNode remoteBundles=PubSubManager.getInstanceFor(bob.connection,JidCreate.entityBareFrom(alice.device.jid)).getLeafNode(XmppOmemo2.BUNDLES_NODE);
            java.util.List<PayloadItem<ExtensionElement>> publishedBundle=remoteBundles.getItems(java.util.Collections.singletonList("101"));
            assertArrayEquals(original,Omemo2Bundle.parse(org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE.parse(org.jivesoftware.smack.util.PacketParserUtils.getParserFor(publishedBundle.get(0).getPayload().toXML().toString()))).identity());
        }
    }
    @Test void explicitlyBlockedDeviceGetsNoNewKeysAndBlockingAllDevicesFailsClosed()throws Exception {
        try(Omemo2IntegrationTest.Client alice=new Omemo2IntegrationTest.Client("blockdevicealice",401);
            Omemo2IntegrationTest.Client bob=new Omemo2IntegrationTest.Client("blockdevicebob",402);
            Omemo2IntegrationTest.Client other=new Omemo2IntegrationTest.Client("blockdevicebob",403)) {
            alice.trust(bob.device.jid);
            alice.device.decide(bob.device.jid,402,bob.device.prepare().identity(),false);
            org.jivesoftware.smack.packet.Message encrypted=alice.service.encrypt(alice.text(bob,"Only the remaining trusted device"));
            StandardExtensionElement root=encrypted.getExtension("encrypted",XmppOmemo2.NS);
            StandardExtensionElement keys=root.getFirstElement("header").getElements("keys").stream().filter(k->bob.device.jid.equals(k.getAttributeValue("jid"))).findFirst().get();
            assertFalse(keys.getElements("key").stream().anyMatch(k->"402".equals(k.getAttributeValue("rid"))));
            assertTrue(keys.getElements("key").stream().anyMatch(k->"403".equals(k.getAttributeValue("rid"))));
            alice.device.decide(bob.device.jid,403,other.device.prepare().identity(),false);
            OmemoSendException blocked=assertThrows(OmemoSendException.class,()->alice.service.encrypt(alice.text(bob,"Must not send")));
            assertEquals(OmemoSendException.Reason.NO_TRUSTED_DEVICES,blocked.reason);
        }
    }
}
