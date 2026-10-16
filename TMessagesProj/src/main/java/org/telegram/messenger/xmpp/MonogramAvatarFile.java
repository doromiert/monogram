// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

/** Local avatar selected by the upstream picker; it must never enter an MTProto upload. */
public final class MonogramAvatarFile extends org.telegram.tgnet.TLRPC.TL_inputFile {
    public final java.io.File source;
    public MonogramAvatarFile(java.io.File source) { this.source = source; }
    @Override public void serializeToStream(org.telegram.tgnet.OutputSerializedData stream) {
        throw new UnsupportedOperationException("XMPP avatar is a local file");
    }
}
