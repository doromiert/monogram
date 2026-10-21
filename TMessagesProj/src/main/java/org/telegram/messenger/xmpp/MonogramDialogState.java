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

    public static TLRPC.GlobalPrivacySettings archiveSettings(int account, String owner) {
        SharedPreferences prefs = preferences(account);
        String key = owner + "\narchive-policy:";
        TLRPC.GlobalPrivacySettings settings = new TLRPC.TL_globalPrivacySettings();
        settings.keep_archived_unmuted = prefs.getBoolean(key + "unmuted", false);
        settings.keep_archived_folders = prefs.getBoolean(key + "folders", false);
        settings.archive_and_mute_new_noncontact_peers = prefs.getBoolean(key + "unknown", false);
        return settings;
    }

    public static boolean saveArchiveSettings(int account, String owner, TLRPC.GlobalPrivacySettings settings) {
        if (owner.isEmpty() || !owner.equals(MonogramController.get(account).accountJid())) return false;
        String key = owner + "\narchive-policy:";
        return preferences(account).edit()
                .putBoolean(key + "unmuted", settings.keep_archived_unmuted)
                .putBoolean(key + "folders", settings.keep_archived_folders)
                .putBoolean(key + "unknown", settings.archive_and_mute_new_noncontact_peers).commit();
    }

    /** Runs on the UI thread before publishing a fresh live message and its notification. */
    static void incoming(int account, String owner, long peer, int messageId, boolean newUnknownChat) {
        try {
            applyIncoming(account, owner, peer, messageId, newUnknownChat);
        } catch (Exception error) {
            // Archive bookkeeping must never prevent delivery of the incoming message.
            org.telegram.messenger.FileLog.e(error);
        }
    }

    private static void applyIncoming(int account, String owner, long peer, int messageId, boolean newUnknownChat) {
        if (owner.isEmpty() || !owner.equals(MonogramController.get(account).accountJid()) || messageId <= 0) return;
        SharedPreferences prefs = preferences(account);
        String key = owner + "\n" + peer;
        if (prefs.getInt(key + ":archive-seen", 0) >= messageId) return;
        TLRPC.GlobalPrivacySettings rules = archiveSettings(account, owner);
        org.telegram.messenger.MessagesController controller = org.telegram.messenger.MessagesController.getInstance(account);
        TLRPC.Dialog dialog = controller.dialogs_dict.get(peer);
        // An explicit folder choice, including a manual unarchive, takes precedence for an existing chat.
        if (newUnknownChat && rules.archive_and_mute_new_noncontact_peers && !prefs.contains(key + ":folder")) {
            if (!prefs.edit().putInt(key + ":folder", 1).putInt(key + ":pin", 0).putInt(key + ":archive-seen", messageId).commit()) return;
            org.telegram.messenger.NotificationsController.getInstance(account).muteDialog(peer, 0, true);
            if (dialog != null) controller.addDialogToFolder(peer, 1, 0, 0);
            return;
        }
        if (dialog == null || dialog.folder_id != 1 || controller.isDialogMuted(peer, 0)) return;
        prefs.edit().putInt(key + ":archive-seen", messageId).apply();
        boolean inFolder = false;
        TLRPC.TL_dialog unarchived = new TLRPC.TL_dialog();
        unarchived.id = dialog.id;
        unarchived.unread_count = dialog.unread_count;
        unarchived.unread_mark = dialog.unread_mark;
        unarchived.unread_mentions_count = dialog.unread_mentions_count;
        for (org.telegram.messenger.MessagesController.DialogFilter filter : controller.getDialogFilters()) {
            if (filter.id != 0 && filter.includesDialog(org.telegram.messenger.AccountInstance.getInstance(account), peer, unarchived)) {
                inFolder = true;
                break;
            }
        }
        if (inFolder ? rules.keep_archived_folders : rules.keep_archived_unmuted) return;
        controller.addDialogToFolder(peer, 0, 0, 0);
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
