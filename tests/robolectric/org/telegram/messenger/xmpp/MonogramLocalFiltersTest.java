package org.telegram.messenger.xmpp;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import static org.junit.Assert.*;

public class MonogramLocalFiltersTest {
    @Test public void nativeLocalMutationCallbacksReceiveSuccessWithoutAnyRemoteFolderList() {
        TLRPC.TL_messages_updateDialogFilter create = new TLRPC.TL_messages_updateDialogFilter();
        create.id = 2; create.flags = 1; create.filter = new TLRPC.TL_dialogFilter();
        assertTrue(MonogramLocalFilters.handle(create) instanceof TLRPC.TL_boolTrue);
        TLRPC.TL_messages_updateDialogFilter delete = new TLRPC.TL_messages_updateDialogFilter();
        delete.id = 2;
        assertTrue(MonogramLocalFilters.handle(delete) instanceof TLRPC.TL_boolTrue);
        assertTrue(MonogramLocalFilters.handle(new TLRPC.TL_messages_updateDialogFiltersOrder()) instanceof TLRPC.TL_boolTrue);
        assertTrue(MonogramLocalFilters.handle(new TLRPC.TL_messages_toggleDialogFilterTags()) instanceof TLRPC.TL_boolTrue);
        assertFalse(MonogramLocalFilters.supports(new TLRPC.TL_messages_getDialogFilters()));
        assertNull(MonogramLocalFilters.handle(new TLRPC.TL_messages_getDialogFilters()));
        assertNull(MonogramLocalFilters.handle(new TLRPC.TL_messages_updatePinnedMessage()));
    }

    @Test public void noRemoteSuggestionsOrFolderInvitesAreInvented() {
        Vector<?> suggested = (Vector<?>) MonogramLocalFilters.handle(new TLRPC.TL_messages_getSuggestedDialogFilters());
        assertTrue(suggested.objects.isEmpty());
        assertFalse(MonogramLocalFilters.supports(new org.telegram.tgnet.tl.TL_chatlists.TL_chatlists_getExportedInvites()));
    }
}
