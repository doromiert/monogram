// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.List;
import org.jivesoftware.smackx.omemo.MonogramLegacyReceiveOnly;
import org.jivesoftware.smackx.omemo.MonogramSignalStore;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;

/** Explicit validated cold-start factories. They never authenticate, publish, activate a slot or send. */
public interface XmppRecovery {
    List<Omemo2ReceiveOnly> modern(Omemo2Device active) throws Exception;
    List<MonogramLegacyReceiveOnly> legacy(MonogramSignalStore active,OmemoDevice own,IdentityTrust trust) throws Exception;
}
