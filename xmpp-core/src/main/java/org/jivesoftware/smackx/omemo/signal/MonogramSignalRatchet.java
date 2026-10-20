// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo.signal;

import org.jivesoftware.smackx.omemo.OmemoManager;
import org.jivesoftware.smackx.omemo.MonogramSignalStore;

/** Exposes the installed Signal implementation without changing any ratchet or trust behavior. */
public final class MonogramSignalRatchet extends SignalOmemoRatchet {
    public MonogramSignalRatchet(OmemoManager manager,MonogramSignalStore store) { super(manager,store); }
}
