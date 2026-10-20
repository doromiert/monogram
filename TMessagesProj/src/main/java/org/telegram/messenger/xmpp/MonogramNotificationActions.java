// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

/** Binds actionable notifications to an account incarnation, not just a reusable slot number. */
public final class MonogramNotificationActions {
    private static final String OWNER = "monogram_notification_owner";
    private MonogramNotificationActions() {}
    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-notification-actions-" + account, Context.MODE_PRIVATE);
    }
    public static void bind(Intent intent, int account) {
        bind(intent, account, preferences(account), MonogramController.get(account).accountJid());
    }
    static synchronized void bind(Intent intent, int account, SharedPreferences prefs, String jid) {
        if (jid.isEmpty()) return;
        String token = prefs.getString("token", "");
        if (!jid.equals(prefs.getString("jid", "")) || token.isEmpty()) {
            token = UUID.randomUUID().toString();
            if (!prefs.edit().putString("jid", jid).putString("token", token).commit()) token = "";
        }
        intent.putExtra("currentAccount", account);
        intent.putExtra(OWNER, token);
        // Extras alone are not PendingIntent identity: UPDATE_CURRENT could otherwise retarget an old action.
        intent.setData(new Uri.Builder().scheme("monogram").authority("notification-action")
                .appendPath(Integer.toString(account)).appendPath(token)
                .appendPath(intent.getComponent() == null ? "action" : intent.getComponent().getClassName())
                .appendPath(Long.toString(intent.getLongExtra("dialog_id", 0)))
                .appendPath(Integer.toString(intent.getIntExtra("max_id", 0))).build());
    }
    public static void invalidate(int account) { preferences(account).edit().clear().commit(); }
    public static boolean isXmpp(Intent intent, int account) {
        return intent.hasExtra(OWNER) || !MonogramController.get(account).accountJid().isEmpty();
    }
    public static boolean valid(Intent intent, int account) {
        return valid(intent, preferences(account), MonogramController.get(account).accountJid());
    }
    static boolean valid(Intent intent, SharedPreferences prefs, String jid) {
        String token = intent.getStringExtra(OWNER);
        Uri data = intent.getData();
        if (data == null || !"monogram".equals(data.getScheme()) || !"notification-action".equals(data.getAuthority())) return false;
        java.util.List<String> path = data.getPathSegments();
        if (path.size() != 5 || !path.get(0).equals(Integer.toString(intent.getIntExtra("currentAccount", -1)))
                || !path.get(1).equals(token)
                || !path.get(2).equals(intent.getComponent() == null ? "action" : intent.getComponent().getClassName())
                || !path.get(3).equals(Long.toString(intent.getLongExtra("dialog_id", 0)))
                || !path.get(4).equals(Integer.toString(intent.getIntExtra("max_id", 0)))) return false;
        return !jid.isEmpty() && jid.equals(prefs.getString("jid", "")) && token != null && !token.isEmpty()
                && token.equals(prefs.getString("token", "")) && intent.getLongExtra("dialog_id", 0) != 0
                && intent.getIntExtra("max_id", 0) > 0 && intent.getLongExtra("topic_id", 0) == 0;
    }
    public static Runnable keepAlive(BroadcastReceiver receiver) {
        BroadcastReceiver.PendingResult pending = receiver.goAsync();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable completion = new Runnable() {
            @Override public void run() {
                if (!finished.compareAndSet(false, true)) return;
                AndroidUtilities.cancelRunOnUIThread(this);
                if (pending != null) pending.finish();
            }
        };
        AndroidUtilities.runOnUIThread(completion, 8000);
        return completion;
    }
}
