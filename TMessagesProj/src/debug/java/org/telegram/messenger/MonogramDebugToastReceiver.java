// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

/** ADB development prompts; neither this class nor its receiver ships in release builds. */
public final class MonogramDebugToastReceiver extends BroadcastReceiver {
    static final String CHANNEL = "monogram_debug_prompts";
    static final int NOTIFICATION = 0x4d474442;

    static String message(Intent intent) {
        String text = intent == null ? null : intent.getStringExtra("message");
        if (text == null) return "";
        text = text.trim();
        return text.codePointCount(0, text.length()) > 512
                ? text.substring(0, text.offsetByCodePoints(0, 512)) : text;
    }

    @Override public void onReceive(Context context, Intent intent) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String text = message(intent);
        if (text.isEmpty()) { setResultData("Missing message"); return; }
        try {
            BaseFragment fragment = ApplicationLoader.mainInterfacePaused ? null : LaunchActivity.getLastFragmentIncludeMainTabs();
            if (fragment != null && fragment.getParentActivity() != null && fragment.getFragmentView() != null) {
                Bulletin bulletin = BulletinFactory.of(fragment)
                        .createSimpleBulletin(R.raw.info, "Monogram testing", text).setDuration(10000);
                Bulletin.TwoLineLottieLayout layout = (Bulletin.TwoLineLottieLayout) bulletin.getLayout();
                layout.subtitleTextView.setSingleLine(false);
                layout.subtitleTextView.setMaxLines(6);
                layout.setOnClickListener(view -> bulletin.hide());
                bulletin.show();
                setResultData("Shown in Monogram");
            } else if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Testing prompts", NotificationManager.IMPORTANCE_HIGH));
                }
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.notification).setContentTitle("Monogram testing")
                        .setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setAutoCancel(true);
                if (launch != null) notification.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                manager.notify(NOTIFICATION, notification.build());
                setResultData("Posted testing notification");
            } else {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                setResultData("Shown as system toast; notifications disabled");
            }
            setResultCode(Activity.RESULT_OK);
        } catch (RuntimeException error) {
            FileLog.e("Monogram debug prompt: " + error.getClass().getSimpleName());
            setResultData("Could not show prompt: " + error.getClass().getSimpleName());
        }
    }
}
