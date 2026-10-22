// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.graphics.Point;
import android.graphics.PointF;
import org.telegram.tgnet.TLRPC;

/** Use the media's dimensions rather than the shape of a generated preview. */
public final class MonogramMediaPresentation {
    private MonogramMediaPresentation() {}
    public static Point dimensions(TLRPC.Document document) {
        if (document == null) return null;
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo && attribute.w > 0 && attribute.h > 0)
                return new Point(attribute.w, attribute.h);
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeImageSize && attribute.w > 0 && attribute.h > 0)
                return new Point(attribute.w, attribute.h);
        }
        return null;
    }
    public static PointF fit(int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return null;
        double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
        return new PointF(Math.max(1, (float) (width * scale)), Math.max(1, (float) (height * scale)));
    }
}
