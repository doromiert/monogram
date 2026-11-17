// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.xmpp.MonogramAttachments;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.messenger.xmpp.MonogramPacks;
import org.telegram.tgnet.TLRPC;

/** Choose the destination before explicitly downloading an ordinary XMPP file. */
public final class MonogramFileSave {
    private static final int REQUEST = 9471;
    private static MessageObject pending;
    private static int account;
    private static String owner;
    private MonogramFileSave() {}

    public static boolean shouldChoose(MessageObject message) {
        if (message == null || !MonogramAttachments.has(message.messageOwner)
                || MonogramAttachments.isMedia(message.messageOwner.media)) return false;
        TLRPC.Document document = message.getDocument();
        return document != null && !MonogramPackFiles.isPack(FileLoader.getDocumentFileName(document), document.mime_type);
    }
    public static Intent intent(TLRPC.Document document) {
        String name = FileLoader.getDocumentFileName(document).replace('/', '_').replace('\\', '_').replace('\n', '_').replace('\r', '_');
        String mime = document.mime_type;
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime)
                .putExtra(Intent.EXTRA_TITLE, name.isEmpty() ? "Attachment" : name);
    }
    public static void choose(int selectedAccount, MessageObject message) {
        Activity activity = LaunchActivity.instance;
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || pending != null) {
            reset(selectedAccount, message); return;
        }
        pending = message; account = selectedAccount; owner = MonogramPacks.owner(account);
        try { activity.startActivityForResult(intent(message.getDocument()), REQUEST); }
        catch (Exception failure) {
            pending = null;
            reset(selectedAccount, message);
            android.widget.Toast.makeText(activity, "File picker unavailable. Try downloading again.", android.widget.Toast.LENGTH_LONG).show();
        }
    }
    private static void reset(int selectedAccount, MessageObject message) {
        org.telegram.messenger.NotificationCenter.getInstance(selectedAccount).postNotificationName(
                org.telegram.messenger.NotificationCenter.fileLoadFailed, message.getFileName(), 0);
    }
    public static boolean onResult(int request, int result, Intent data) {
        if (request != REQUEST) return false;
        MessageObject message = pending; pending = null;
        if (message == null || !owner.equals(MonogramPacks.owner(account))) return true;
        if (result == Activity.RESULT_OK && data != null && data.getData() != null
                && "content".equals(data.getData().getScheme())
                && !org.telegram.messenger.AndroidUtilities.isInternalUri(data.getData())) {
            MonogramController.get(account).saveDocument(message, data.getData());
            if (LaunchActivity.instance != null) android.widget.Toast.makeText(LaunchActivity.instance, "Saving file…", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            reset(account, message);
            if (result == Activity.RESULT_OK && LaunchActivity.instance != null)
                android.widget.Toast.makeText(LaunchActivity.instance, "Choose a save location in Files, then try again.", android.widget.Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
