// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;

import android.Manifest;
import android.content.pm.PackageManager;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramMujiService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.GroupCallActivity;
import org.telegram.ui.LaunchActivity;

/** Permission/join entry point; the call itself uses Telegram's GroupCallActivity. */
public final class MonogramMujiUi {
    private MonogramMujiUi() {}

    public static void open(BaseFragment fragment, long room, String title) {
        if (!(fragment.getParentActivity() instanceof LaunchActivity)) return;
        int account = fragment.getCurrentAccount();
        MonogramMujiService active = MonogramMujiService.current;
        if (active != null && active.groupCall != null) {
            if (active.account == account && active.peer == room) show(fragment);
            else error(fragment, "Another call is active");
            return;
        }
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle(title)
            .setMessage("Join this room's group call. Calls use encrypted media connections; participant identities are not cryptographically verified.")
            .setPositiveButton("Join voice call", (dialog, which) -> join(fragment, room, title, false))
            .setNeutralButton("Join video call", (dialog, which) -> join(fragment, room, title, true))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }

    private static void join(BaseFragment fragment, long room, String title, boolean video) {
        if (fragment.getParentActivity() == null) return;
        String[] permissions = video ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
            : new String[]{Manifest.permission.RECORD_AUDIO};
        for (String permission : permissions) if (!PermissionRequest.hasPermission(permission)) {
            PermissionRequest.requestPermissions(permissions, grants -> {
                if (grants.length != permissions.length) return;
                for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) return;
                join(fragment, room, title, video);
            });
            return;
        }
        int account = fragment.getCurrentAccount();
        NotificationCenter center = NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        Runnable[] timeout = new Runnable[1];
        observer[0] = (id, a, args) -> {
            MonogramMujiService call = MonogramMujiService.current;
            if (call == null || call.groupCall == null || call.account != account || call.peer != room) return;
            center.removeObserver(observer[0], NotificationCenter.monogramGroupCallChanged);
            AndroidUtilities.cancelRunOnUIThread(timeout[0]);
            if (call.error != null) error(fragment, call.error);
            else show(fragment);
        };
        timeout[0] = () -> {
            center.removeObserver(observer[0], NotificationCenter.monogramGroupCallChanged);
            error(fragment, "Could not start the group call");
        };
        center.addObserver(observer[0], NotificationCenter.monogramGroupCallChanged);
        AndroidUtilities.runOnUIThread(timeout[0], 10000);
        try { MonogramMujiService.start(fragment.getParentActivity(), account, room, title, video); }
        catch (Exception failed) {
            center.removeObserver(observer[0], NotificationCenter.monogramGroupCallChanged);
            AndroidUtilities.cancelRunOnUIThread(timeout[0]);
            error(fragment, failed.getMessage());
        }
    }

    private static void show(BaseFragment fragment) {
        if (fragment.getParentActivity() instanceof LaunchActivity)
            GroupCallActivity.create((LaunchActivity) fragment.getParentActivity(),
                AccountInstance.getInstance(fragment.getCurrentAccount()), null, null, false, null);
    }

    private static void error(BaseFragment fragment, String message) {
        if (fragment.getParentActivity() != null) fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity())
            .setTitle("Group call").setMessage(message).setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }
}
