// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

/** Native callbacks persist local folders in MessagesStorage; there is no Telegram server state. */
public final class MonogramLocalFilters {
    private MonogramLocalFilters() {}

    public static boolean supports(TLObject request) {
        return request instanceof TLRPC.TL_messages_updateDialogFilter
                || request instanceof TLRPC.TL_messages_updateDialogFiltersOrder
                || request instanceof TLRPC.TL_messages_toggleDialogFilterTags
                || request instanceof TLRPC.TL_messages_getSuggestedDialogFilters;
    }

    public static TLObject handle(TLObject request) {
        if (request instanceof TLRPC.TL_messages_getSuggestedDialogFilters)
            return new Vector<TLRPC.TL_dialogFilterSuggested>(TLRPC.TL_dialogFilterSuggested::TLdeserialize);
        return supports(request) ? new TLRPC.TL_boolTrue() : null;
    }
}
