// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** App-wide Android setup reminder. An interrupted dialog is still pending. */
final class MonogramBatteryPrompt {
    static final String ACKNOWLEDGED = "unrestricted_battery_prompt_acknowledged";
    private AlertDialog dialog;

    static boolean isUnrestricted(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static void openSettings(Activity activity) {
        Uri app = Uri.parse("package:" + activity.getPackageName());
        String[] actions = {Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS};
        for (String action : actions) {
            try {
                activity.startActivity(new Intent(action,
                        Settings.ACTION_SETTINGS.equals(action) ? null : app));
                return;
            } catch (ActivityNotFoundException | SecurityException unsupported) { }
        }
    }

    void showIfNeeded(Activity activity, SharedPreferences preferences, boolean eligible) {
        if (!eligible || activity.isFinishing() || activity.isDestroyed() || dialog != null
                || preferences.getBoolean(ACKNOWLEDGED, false) || isUnrestricted(activity)) return;
        Runnable acknowledge = () -> preferences.edit().putBoolean(ACKNOWLEDGED, true).apply();
        AlertDialog prompt = new AlertDialog.Builder(activity)
                .setTitle("Allow unrestricted battery usage")
                .setMessage("Allow unrestricted battery usage so Monogram can keep its connection and receive messages while your phone is idle.\n\nChoose Allow in Android's request. Some phones also require allowing background activity in the app's battery settings. You can change this later in Settings → XMPP connection.")
                .setPositiveButton("Enable", (ignored, which) -> {
                    acknowledge.run();
                    openSettings(activity);
                })
                .setNegativeButton("Not now", (ignored, which) -> acknowledge.run())
                .setOnCancelListener(ignored -> acknowledge.run())
                .create();
        prompt.setOnDismissListener(ignored -> { if (dialog == prompt) dialog = null; });
        dialog = prompt;
        try { prompt.show(); }
        catch (android.view.WindowManager.BadTokenException unavailable) { dialog = null; }
    }

    void pause() {
        AlertDialog previous = dialog;
        dialog = null;
        // Programmatic dismissal must not acknowledge a prompt the user may not have seen.
        if (previous != null) previous.dismiss();
    }
}
