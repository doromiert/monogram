// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.BareJid;

/** Public PEP discovery must not hold OmemoManager's encryption/ratchet monitor across network waits. */
final class XmppLegacyDiscovery {
    private final XMPPConnection connection;
    private final int deviceId;
    XmppLegacyDiscovery(XMPPConnection connection,int deviceId) { this.connection=connection; this.deviceId=deviceId; }
    Set<OmemoDevice> devices(BareJid address) throws Exception {
        LeafNode node=PubSubManager.getInstanceFor(connection,address).getLeafNode("eu.siacs.conversations.axolotl.devicelist");
        List<PayloadItem<OmemoDeviceListElement>> items=node.getItems();
        Set<OmemoDevice> devices=new LinkedHashSet<>();
        if(!items.isEmpty()) for(int id:items.get(items.size()-1).getPayload().getDeviceIds()) devices.add(new OmemoDevice(address,id));
        return devices;
    }
    OmemoFingerprint ownFingerprint() throws Exception {
        return SignalOmemoService.getInstance().getOmemoStoreBackend().getFingerprint(new OmemoDevice(connection.getUser().asBareJid(),deviceId));
    }
    OmemoFingerprint fingerprint(OmemoDevice device) throws Exception {
        LeafNode node=PubSubManager.getInstanceFor(connection,device.getJid()).getLeafNode(device.getBundleNodeName());
        List<PayloadItem<OmemoBundleElement>> items=node.getItems();
        if(items.isEmpty() || items.get(items.size()-1).getPayload()==null) throw new java.io.IOException("No published key bundle");
        return fingerprint(items.get(items.size()-1).getPayload());
    }
    static OmemoFingerprint fingerprint(OmemoBundleElement bundle) throws Exception {
        SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();
        org.whispersystems.libsignal.IdentityKey identity=keys.BUNDLE.identityKey(bundle);
        keys.BUNDLE.signedPreKeyPublic(bundle);
        if(!org.whispersystems.libsignal.ecc.Curve.verifySignature(identity.getPublicKey(),bundle.getSignedPreKey(),bundle.getSignedPreKeySignature()))
            throw new org.whispersystems.libsignal.InvalidKeyException("Invalid bundle signature");
        return keys.getFingerprintOfIdentityKey(identity);
    }
}
