// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smackx.omemo.element.OmemoBundleElement;
import org.jivesoftware.smackx.omemo.exceptions.CannotEstablishOmemoSessionException;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jivesoftware.smackx.pubsub.LeafNode;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubException;
import org.jivesoftware.smackx.pubsub.PubSubManager;

/** Narrow bridge to Smack 4.4.8's existing encryption implementation for an explicit device set. */
public final class MonogramDeviceEncryption {
    private MonogramDeviceEncryption() {}
    public static OmemoFingerprint prepare(OmemoManager manager,OmemoDevice device) throws Exception {
        OmemoBundleElement bundle=needsSession(manager,device) ? fetchBundle(manager.getConnection(),device) : null;
        return prepare(manager,device,bundle);
    }
    public static boolean needsSession(OmemoManager manager,OmemoDevice device) throws Exception {
        synchronized(manager) {
            return !device.equals(manager.getOwnDevice())
                    && manager.getOmemoService().getOmemoStoreBackend().loadRawSession(manager.getOwnDevice(),device)==null;
        }
    }
    /** PEP lookup only: this may run concurrently without touching manager, identity or ratchet state. */
    public static OmemoBundleElement fetchBundle(XMPPConnection connection,OmemoDevice device) throws Exception {
        try {
            LeafNode node=PubSubManager.getInstanceFor(connection,device.getJid()).getLeafNode(device.getBundleNodeName());
            if(node==null) return null;
            List<PayloadItem<OmemoBundleElement>> items=node.getItems();
            return items.isEmpty() ? null : items.get(items.size()-1).getPayload();
        } catch(XMPPException.XMPPErrorException | PubSubException.NotALeafNodeException | PubSubException.NotAPubSubNodeException error) {
            throw new CannotEstablishOmemoSessionException(device,error);
        }
    }
    /** Local apply only; a session learned while the bundle was in flight must retain its ratchet. */
    public static OmemoFingerprint prepare(OmemoManager manager,OmemoDevice device,OmemoBundleElement bundle) throws Exception {
        synchronized(manager) {
            if(needsSession(manager,device)) {
                if(bundle==null) throw new CannotEstablishOmemoSessionException(device,new java.io.IOException("No published key bundle"));
                apply(manager.getOmemoService(),manager,device,bundle);
            }
            // OmemoManager.getFingerprint may fetch and rebuild a session if the identity is missing.
            return device.equals(manager.getOwnDevice()) ? manager.getOmemoService().getOmemoStoreBackend().getFingerprint(manager.getOwnDevice())
                    : manager.getOmemoService().getOmemoStoreBackend().getFingerprint(manager.getOwnDevice(),device);
        }
    }
    private static <B> void apply(OmemoService<?,?,?,?,?,?,?,B,?> service,OmemoManager manager,OmemoDevice device,OmemoBundleElement bundle) throws Exception {
        HashMap<Integer,B> bundles=service.getOmemoStoreBackend().keyUtil().BUNDLE.bundles(bundle,device);
        B selected=new ArrayList<>(bundles.values()).get(new Random().nextInt(bundles.size()));
        service.processBundle(manager,selected,device);
    }
    public static OmemoMessage.Sent encrypt(OmemoManager manager,Set<OmemoDevice> devices,String body) throws Exception {
        return manager.getOmemoService().createOmemoMessage(new OmemoManager.LoggedInOmemoManager(manager),new HashSet<>(devices),body);
    }
}
