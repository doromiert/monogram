// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

/** Only user-started wallet scans run here; never starts or unlocks a wallet. */
public final class WalletSyncService extends Service {
    private static final String CHANNEL="monogram_wallet_initial_scan",STOP="stop-initial-wallet-scan";
    private static final int NOTIFICATION=1036;
    private static final long MAX_RUN=6*60*60*1000L;
    private static final Set<MonogramWallet> active=java.util.Collections.newSetFromMap(new ConcurrentHashMap<MonogramWallet,Boolean>());
    private static volatile WalletSyncService instance;
    private PowerManager.WakeLock cpu;
    private long lastUpdate;
    private final Runnable timeout=()->{stopScans();stopSelf();};
    static void begin(MonogramWallet wallet){
        active.add(wallet);
        try{ContextCompat.startForegroundService(ApplicationLoader.applicationContext,new Intent(ApplicationLoader.applicationContext,WalletSyncService.class));}
        catch(RuntimeException failure){active.remove(wallet);throw failure;}
    }
    static void finish(MonogramWallet wallet){active.remove(wallet);update(true);}
    static void progress(MonogramWallet wallet){if(active.contains(wallet))update(false);}
    private static void update(boolean force){AndroidUtilities.runOnUIThread(()->{WalletSyncService service=instance;if(service!=null)service.updateNotification(force);});}
    private static void stopScans(){for(MonogramWallet wallet:new ArrayList<>(active))wallet.stop();active.clear();}
    @Override public void onCreate(){
        super.onCreate();instance=this;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"Wallet scanning",NotificationManager.IMPORTANCE_LOW);channel.setShowBadge(false);channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(channel);}
        Notification notification=notification();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(NOTIFICATION,notification);
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        if(power!=null){cpu=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Monogram:InitialWalletScan");cpu.setReferenceCounted(false);cpu.acquire(MAX_RUN);}
        AndroidUtilities.runOnUIThread(timeout,MAX_RUN);
    }
    private Notification notification(){
        ArrayList<MonogramWallet> scans=new ArrayList<>(active);
        String detail=scans.size()==1 ? scans.get(0).status : scans.isEmpty() ? "Finishing scan" : scans.size()+" wallets scanning";
        PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,LaunchActivity.class).setAction(Intent.ACTION_MAIN),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,NOTIFICATION,new Intent(this,WalletSyncService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.notification).setContentTitle("Monogram · wallet scanning")
            .setContentText(detail).setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_PROGRESS).setProgress(100,scans.size()==1 ? scans.get(0).scanPercent() : 0,scans.size()!=1 || scans.get(0).scanTarget<=0)
            .addAction(0,"Stop scan",stop).build();
    }
    private void updateNotification(boolean force){
        if(active.isEmpty()){stopSelf();return;}
        long now=SystemClock.elapsedRealtime();if(!force && now-lastUpdate<1500)return;lastUpdate=now;
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null && STOP.equals(intent.getAction())){stopScans();stopSelf();}else updateNotification(true);return START_NOT_STICKY;}
    @Override public void onTimeout(int startId,int type){stopScans();stopSelf();}
    @Override public void onDestroy(){if(instance==this)instance=null;AndroidUtilities.cancelRunOnUIThread(timeout);stopScans();if(cpu!=null && cpu.isHeld())cpu.release();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
