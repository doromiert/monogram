// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import java.util.HashSet;
import java.util.Set;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;

/** Narrow bridge to Smack 4.4.8's existing encryption implementation for an explicit device set. */
public final class MonogramDeviceEncryption {
    private MonogramDeviceEncryption() {}
    public static OmemoFingerprint prepare(OmemoManager manager,OmemoDevice device) throws Exception {
        if(manager.getOmemoService().getOmemoStoreBackend().loadRawSession(manager.getOwnDevice(),device)==null)
            manager.rebuildSessionWith(device);
        return manager.getFingerprint(device);
    }
    public static OmemoMessage.Sent encrypt(OmemoManager manager,Set<OmemoDevice> devices,String body) throws Exception {
        return manager.getOmemoService().createOmemoMessage(new OmemoManager.LoggedInOmemoManager(manager),new HashSet<>(devices),body);
    }
}
