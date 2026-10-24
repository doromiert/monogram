// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import org.telegram.tgnet.TLRPC;

/** XEP-0245 presentation only. The original /me body remains the transport/storage value. */
public final class MonogramActions {
    private MonogramActions() {}

    public static boolean isAction(TLRPC.Message message) {
        return message != null && message.params != null
                && (message.params.containsKey("monogram-actual-encryption") || message.params.containsKey("monogram-encryption"))
                && !(message instanceof TLRPC.TL_messageService)
                && message.message != null && message.message.startsWith("/me ")
                && (message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty)
                && message.fwd_from == null;
    }

    public static CharSequence presentation(TLRPC.Message message, String actor) {
        if (!isAction(message)) throw new IllegalArgumentException("Not an XMPP action");
        String name = actor == null || actor.isEmpty() ? Long.toString(message.from_id == null ? 0 : message.from_id.user_id)
                : actor.replace('\n', ' ').replace('\r', ' ');
        // The star differentiates user-authored actions from real presence/system events.
        String label = "* " + name + " " + message.message.substring(4);
        TLRPC.TL_messageActionCustomAction action = new TLRPC.TL_messageActionCustomAction();
        action.message = label;
        message.action = action;
        SpannableString text = new SpannableString(label);
        text.setSpan(new StyleSpan(Typeface.ITALIC), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
}
