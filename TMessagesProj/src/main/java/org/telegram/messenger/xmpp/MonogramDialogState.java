// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

/** Local chat-list organization; no private bookkeeping is published to the XMPP server. */
public final class MonogramDialogState {
    private MonogramDialogState() {}

    public static boolean local(int account) {
        return !MonogramController.get(account).accountJid().isEmpty();
    }

    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-dialog-state-" + account, Context.MODE_PRIVATE);
    }

    public static void save(int account, TLRPC.Dialog dialog) {
        String owner = MonogramController.get(account).accountJid();
        if (!owner.isEmpty()) save(preferences(account), owner, dialog);
    }

    public static void clear(int account, String owner) {
        if (owner.isEmpty()) return;
        SharedPreferences prefs = preferences(account);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(owner + "\n")) editor.remove(key);
        editor.commit();
    }

    static void save(SharedPreferences preferences, String owner, TLRPC.Dialog dialog) {
        if (owner.isEmpty() || dialog == null || dialog instanceof TLRPC.TL_dialogFolder) return;
        String key = owner + "\n" + dialog.id;
        preferences.edit().putInt(key + ":folder", dialog.folder_id)
                .putInt(key + ":pin", dialog.pinned ? Math.max(1, dialog.pinnedNum) : 0).commit();
    }

    public static void apply(int account, String owner, TLRPC.Dialog dialog) {
        apply(preferences(account), owner, dialog);
    }

    static void apply(SharedPreferences preferences, String owner, TLRPC.Dialog dialog) {
        if (owner.isEmpty() || dialog instanceof TLRPC.TL_dialogFolder) return;
        String key = owner + "\n" + dialog.id;
        if (preferences.contains(key + ":folder")) dialog.folder_id = preferences.getInt(key + ":folder", 0);
        if (preferences.contains(key + ":pin")) {
            dialog.pinnedNum = Math.max(0, preferences.getInt(key + ":pin", 0));
            dialog.pinned = dialog.pinnedNum > 0;
        }
    }

    public static TLRPC.TL_messages_dialogs dialogs(int account, String owner, TLRPC.TL_messages_dialogs all,
            TLRPC.TL_messages_getDialogs request) {
        TLRPC.TL_dialogFolder archive = archive(all);
        all.dialogs.removeIf(dialog -> dialog.folder_id != request.folder_id || request.exclude_pinned && dialog.pinned);
        if (archive != null && request.folder_id == 0 && !request.exclude_pinned) all.dialogs.add(0, archive);
        return all;
    }

    public static TLRPC.TL_messages_peerDialogs pinned(int account, String owner, TLRPC.TL_messages_dialogs all, int folder) {
        TLRPC.TL_messages_peerDialogs result = new TLRPC.TL_messages_peerDialogs();
        for (TLRPC.Dialog dialog : all.dialogs) if (dialog.folder_id == folder && dialog.pinned) result.dialogs.add(dialog);
        result.dialogs.sort((a, b) -> Integer.compare(b.pinnedNum, a.pinnedNum));
        TLRPC.TL_dialogFolder archive = folder == 0 ? archive(all) : null;
        if (archive != null) result.dialogs.add(0, archive);
        result.messages.addAll(all.messages); result.users.addAll(all.users); result.chats.addAll(all.chats);
        result.state = new TLRPC.TL_updates_state();
        return result;
    }

    private static TLRPC.TL_dialogFolder archive(TLRPC.TL_messages_dialogs all) {
        boolean archived = false;
        for (TLRPC.Dialog dialog : all.dialogs) if (dialog.folder_id == 1) { archived = true; break; }
        if (!archived) return null;
        TLRPC.TL_dialogFolder folder = new TLRPC.TL_dialogFolder();
        folder.id = DialogObject.makeFolderDialogId(1); folder.peer = new TLRPC.TL_peerUser();
        folder.folder = new TLRPC.TL_folder(); folder.folder.id = 1;
        folder.folder.title = LocaleController.getString(R.string.ArchivedChats);
        folder.pinned = true; folder.pinnedNum = Integer.MAX_VALUE;
        return folder;
    }
}
