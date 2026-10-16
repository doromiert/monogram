// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.io.File;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Local request consumed by the XMPP adapter; never serialized onto Telegram's network. */
public final class MonogramFileRequest extends TLObject {
    public final TLRPC.Message message;
    public final File file;
    public final String mime, caption;
    public final int localId;
    public final boolean supported;
    public final com.negzero.monogram.xmpp.FileTransfer.Job job = new com.negzero.monogram.xmpp.FileTransfer.Job();
    public MonogramFileRequest(TLRPC.Message message, File file, String mime, String caption) {
        this(message, file, mime, caption, true);
    }
    public MonogramFileRequest(TLRPC.Message message, File file, String mime, String caption, boolean supported) {
        this.supported = supported;
        this.message = message; this.file = file; this.mime = mime; this.caption = caption; localId = message.id;
    }
}
