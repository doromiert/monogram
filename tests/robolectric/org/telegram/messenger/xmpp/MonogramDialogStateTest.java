package org.telegram.messenger.xmpp;

import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramDialogStateTest.Labels.class)
public class MonogramDialogStateTest {
    @Implements(LocaleController.class)
    public static class Labels {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static String getString(int resource) { return "Archived chats"; }
    }
    @Before public void setup() { ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication(); }
    @Test public void localOrganizationSurvivesFreshProjectionWithoutCrossAccountReuse() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("dialog-state-test", 0);
        prefs.edit().clear().commit();
        TLRPC.TL_dialog chosen = new TLRPC.TL_dialog();
        chosen.id = -42; chosen.folder_id = 1; chosen.pinned = true; chosen.pinnedNum = 7;
        MonogramDialogState.save(prefs, "alice@example.org", chosen);
        TLRPC.TL_dialog restored = new TLRPC.TL_dialog(); restored.id = -42;
        MonogramDialogState.apply(prefs, "alice@example.org", restored);
        assertEquals(1, restored.folder_id); assertTrue(restored.pinned); assertEquals(7, restored.pinnedNum);
        TLRPC.TL_dialog other = new TLRPC.TL_dialog(); other.id = -42;
        MonogramDialogState.apply(prefs, "bob@example.org", other);
        assertEquals(0, other.folder_id); assertFalse(other.pinned);
        restored.folder_id = 0; restored.pinned = false;
        MonogramDialogState.save(prefs, "alice@example.org", restored);
        TLRPC.TL_dialog unpinned = new TLRPC.TL_dialog(); unpinned.id = -42;
        MonogramDialogState.apply(prefs, "alice@example.org", unpinned);
        assertEquals(0, unpinned.folder_id); assertEquals(0, unpinned.pinnedNum); assertFalse(unpinned.pinned);
    }

    @Test public void mainAndArchiveRequestsExcludeEachOthersChatsAndPinnedRequestsUseLocalOrder() {
        TLRPC.TL_messages_dialogs all = new TLRPC.TL_messages_dialogs();
        for (int i = 0; i < 3; i++) {
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog(); dialog.id = i + 1;
            dialog.pinned = i != 2; dialog.pinnedNum = 3 - i; all.dialogs.add(dialog);
        }
        TLRPC.TL_messages_peerDialogs pinned = MonogramDialogState.pinned(0, "alice", all, 0);
        assertEquals(2, pinned.dialogs.size()); assertEquals(1, pinned.dialogs.get(0).id);
        TLRPC.TL_messages_getDialogs request = new TLRPC.TL_messages_getDialogs(); request.exclude_pinned = true;
        assertEquals(1, MonogramDialogState.dialogs(0, "alice", all, request).dialogs.size());
        all.dialogs.get(0).folder_id = 1; request.folder_id = 1;
        assertEquals(1, MonogramDialogState.dialogs(0, "alice", all, request).dialogs.size());
        request.folder_id = 0;
        assertTrue(MonogramDialogState.dialogs(0, "alice", all, request).dialogs.isEmpty());
    }
}
