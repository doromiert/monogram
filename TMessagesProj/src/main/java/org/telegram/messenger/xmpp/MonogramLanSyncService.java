// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.app.*;import android.content.*;import android.os.*;import org.telegram.messenger.R;
/** Visible, user-controlled LAN activity. It never keeps wallet access alive through screen lock. */
public final class MonogramLanSyncService extends Service {
 private static final int ID=2937;
 @Override public void onCreate(){super.onCreate();NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel("monogram-device-sync","Device sync",NotificationManager.IMPORTANCE_LOW));
  Intent open=new Intent(this,org.telegram.ui.LaunchActivity.class).setAction("monogram-device-sync").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent pending=PendingIntent.getActivity(this,ID,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification.Builder n=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"monogram-device-sync"):new Notification.Builder(this);n.setSmallIcon(R.drawable.notification).setContentTitle("Monogram device sync").setContentText("Encrypted sync on your local network · pauses when locked").setContentIntent(pending).setOngoing(true);
  if(Build.VERSION.SDK_INT>=29)startForeground(ID,n.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(ID,n.build());
 }
 @Override public int onStartCommand(Intent i,int flags,int id){if(!MonogramLanSync.enabled()){stopSelf();return START_NOT_STICKY;}return START_NOT_STICKY;}
 @Override public void onTimeout(int startId,int type){MonogramLanSync.stopNow();stopSelf();}
 @Override public void onDestroy(){MonogramLanSync.stopNow();super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}
