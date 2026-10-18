// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import java.util.List;

/** Legacy display hint only: the server may add/remove it, but no file or identity metadata is trusted. */
public final class StickerHint {
    private StickerHint() {}
    public static void addTo(Message wire) { wire.addExtension(StandardExtensionElement.builder("sticker", FileShare.STICKERS).build()); }
    static boolean from(Message wire, boolean legacy, boolean readable, FileLink protectedAttachment) {
        if (!legacy || !readable || protectedAttachment == null || !protectedAttachment.encrypted()) return false;
        List<ExtensionElement> markers = wire.getExtensions("sticker", FileShare.STICKERS);
        if (markers.size() != 1 || !(markers.get(0) instanceof StandardExtensionElement)) return false;
        StandardExtensionElement marker = (StandardExtensionElement) markers.get(0);
        return marker.getAttributes().isEmpty() && marker.getElements().isEmpty()
                && (marker.getText() == null || marker.getText().trim().isEmpty());
    }
}
