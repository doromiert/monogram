/*
 * Telegram for Android, licensed under GNU GPL v. 2 or later (see LICENSE).
 * Copyright Nikolai Kudashov, 2013-2018.
 */
package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {
    private static final String CHANNEL = "monogram_connection";
    private static final int NOTIFICATION = 1028;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    public static boolean enabled(Context context) {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (context.getSharedPreferences("monogram-" + account, MODE_PRIVATE).contains("jid")
                    && MessagesController.getNotificationsSettings(account).getBoolean("pushService", true)
                    && MessagesController.getNotificationsSettings(account).getBoolean("pushConnection", true)) return true;
        }
        return false;
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    LocaleController.getString(R.string.NotificationsServiceConnection), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, LaunchActivity.class).setAction(Intent.ACTION_MAIN),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.notification).setContentTitle(LocaleController.getString(R.string.AppName))
                .setContentText(LocaleController.getString(R.string.MonogramBackgroundConnection))
                .setContentIntent(open).setOngoing(true).setSilent(true).setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification);
        ApplicationLoader.postInitApplication();
        if (Build.VERSION.SDK_INT >= 24) {
            connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { restoreConnections(); }
            };
            connectivity.registerDefaultNetworkCallback(networkCallback);
        }
    }

    private static void restoreConnections() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) MonogramController.get(account).restore();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        restoreConnections();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (networkCallback != null) connectivity.unregisterNetworkCallback(networkCallback);
        stopForeground(true);
        super.onDestroy();
    }
}
