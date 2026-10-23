// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.math.BigInteger;
import java.util.*;
import org.telegram.messenger.*;
import org.telegram.ui.LaunchActivity;

/** User-authorized in-memory claim sessions only. Never restores or unlocks keys. */
public final class ClaimDepositService extends Service {
    private static final String CHANNEL="monogram_claim_deposits",STOP="stop-claim-deposits";
    private static final int NOTIFICATION=1037;
    private static final long MAX_RUN=6*60*60*1000L;
    private static final Map<String,Job> jobs=new HashMap<>(); // Main thread only.
    private static ClaimDepositService instance;
    private PowerManager.WakeLock cpu;
    public static boolean active(int account,String address){return jobs.containsKey(ClaimStatusStore.key(account,address));}
    public static void start(MonogramWallet claim,String destination,BigInteger maxFee,MonogramWallet.Result<Void> done){
        start(claim,destination,maxFee,false,done);
    }
    public static void watch(MonogramWallet claim,MonogramWallet.Result<Void> done){start(claim,null,null,true,done);}
    private static void start(MonogramWallet claim,String destination,BigInteger maxFee,boolean watch,MonogramWallet.Result<Void> done){
        String key=ClaimStatusStore.key(claim.accountIndex(),claim.primary());
        Job existing=jobs.get(key);
        if(existing!=null){
            if(watch){done.done(null,null);return;}
            if(existing.watch){
                existing.watch=false;existing.destination=destination;existing.limit=maxFee;existing.pendingApproval=done;
                AndroidUtilities.cancelRunOnUIThread(existing);AndroidUtilities.runOnUIThread(existing);return;
            }
            done.done(null,new IllegalStateException("A deposit for this link is already queued. Stop its notification before changing it."));return;
        }
        try{
            if(!watch && (maxFee==null || maxFee.signum()<=0))throw new IllegalArgumentException("Approve a positive maximum network fee.");
            claim.beginBackgroundClaim();Job job=new Job(key,claim,destination,maxFee,watch);jobs.put(key,job);
            MonogramWallet.Result<Void> saved=(unused,error)->{
                if(error!=null){job.finish("paused","Deposit could not be queued");done.done(null,error);return;}
                try{
                    ContextCompat.startForegroundService(ApplicationLoader.applicationContext,new Intent(ApplicationLoader.applicationContext,ClaimDepositService.class));
                    ClaimStatusStore.pending(key,!watch);job.state(watch ? "unknown" : "queued");AndroidUtilities.runOnUIThread(job);done.done(null,null);
                }catch(Exception failure){job.finish("paused","Background checking could not start");done.done(null,failure);}
            };
            if(watch)saved.done(null,null);else claim.saveDepositApproval(destination,maxFee,saved);
        }catch(Exception error){Job job=jobs.get(key);if(job!=null && job.claim==claim)job.finish("paused","Deposit could not be queued");done.done(null,error);}
    }
    private static final class Job implements Runnable {
        final String key;String destination;final MonogramWallet claim;BigInteger limit;boolean watch;
        MonogramWallet.Result<Void> pendingApproval;
        final long started=SystemClock.elapsedRealtime();boolean closed,committing,broadcasted,readyNotified;
        Job(String key,MonogramWallet claim,String destination,BigInteger limit,boolean watch){this.key=key;this.claim=claim;this.destination=destination;this.limit=limit;this.watch=watch;}
        void state(String state){ClaimStatusStore.putKey(claim.accountIndex(),key,state);if(instance!=null)instance.update();}
        void later(){if(!closed){AndroidUtilities.cancelRunOnUIThread(this);AndroidUtilities.runOnUIThread(this,30000);}}
        @Override public void run(){
            AndroidUtilities.cancelRunOnUIThread(this);
            if(closed)return;
            if(SystemClock.elapsedRealtime()-started>=MAX_RUN || !claim.backgroundClaimActive()){finish("paused","Reopen the link to resume checking");return;}
            if(claim.busy){later();return;}
            if(pendingApproval!=null){
                MonogramWallet.Result<Void> approved=pendingApproval;pendingApproval=null;
                try{claim.saveDepositApproval(destination,limit,(unused,error)->{
                    if(closed){approved.done(null,new IllegalStateException("Background deposit stopped."));return;}
                    if(error!=null){finish("paused","Deposit needs another review");approved.done(null,error);return;}
                    ClaimStatusStore.pending(key,true);state("queued");approved.done(null,null);AndroidUtilities.runOnUIThread(this);
                });}catch(Exception error){finish("paused","Deposit needs another review");approved.done(null,error);}
                return;
            }
            try{claim.syncBackgroundClaim((unused,error)->{
                if(closed)return;
                if(error!=null){finish("paused","Reopen the link to retry its status check");return;}
                try{
                    String state=ClaimStatusStore.state(claim.accountIndex(),claim.primary());
                    if("claimed".equals(state)){finish("claimed","Payment link has been claimed");return;}
                    if(watch && "ready".equals(state) && !readyNotified){readyNotified=true;completion(key,"Payment link is ready to deposit");}
                    if(pendingApproval!=null){AndroidUtilities.runOnUIThread(this);return;}
                    if(watch || broadcasted || !"ready".equals(state)){later();return;}
                    claim.prepareBackgroundClaimSweep(destination,(quote,failure)->{
                        if(closed){if(quote!=null)claim.cancel(quote);return;}
                        if(failure!=null){finish("paused","Deposit needs another review");return;}
                        try{
                            ClaimDepositPolicy.checkApproval(destination,quote.payment.address,new BigInteger(claim.balance[1]),quote.payment.atomicAmount,quote.fee,limit);
                        }catch(Exception rejected){claim.cancel(quote);finish(quote.fee.compareTo(limit)>0 ? "fee-limit" : "paused",rejected.getMessage());return;}
                        committing=true;state("depositing");
                        try{claim.commitBackgroundClaim(quote,(result,commitError)->{
                            committing=false;if(closed)return;
                            if(commitError!=null || result==null || result.length<1 || !"broadcast".equals(result[0])){finish("uncertain","Check the deposit before trying again");return;}
                            broadcasted=true;state("depositing");completion(key,"Deposit sent; waiting for confirmation");later();
                        });}catch(Exception rejected){committing=false;claim.cancel(quote);finish("uncertain","Check the deposit before trying again");}
                    });
                }catch(Exception invalid){finish("paused","Reopen the link to check its status");}
            });}catch(Exception error){finish("paused","Reopen the link to resume checking");}
        }
        void finish(String state,String text){
            if(closed)return;closed=true;AndroidUtilities.cancelRunOnUIThread(this);jobs.remove(key);
            ClaimStatusStore.pending(key,false);
            if(pendingApproval!=null){MonogramWallet.Result<Void> waiting=pendingApproval;pendingApproval=null;waiting.done(null,new IllegalStateException("Background deposit stopped."));}
            state(committing ? "uncertain" : watch && "paused".equals(state) ? ClaimStatusStore.state(claim.accountIndex(),claim.primary()) : state);
            claim.endBackgroundClaim();completion(key,committing ? "Check deposit status; an in-flight transfer cannot be recalled" : text);
        }
    }
    private static PendingIntent open(Context context){return PendingIntent.getActivity(context,NOTIFICATION,new Intent(context,LaunchActivity.class).setAction(Intent.ACTION_MAIN),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private static void completion(String key,String text){
        Context context=ApplicationLoader.applicationContext;
        NotificationManager manager=(NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null && (Build.VERSION.SDK_INT<26 || manager.getNotificationChannel(CHANNEL)!=null))manager.notify(200000+(key.hashCode()&0xffff),new NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.notification).setContentTitle("Monogram · payment link").setContentText(text).setContentIntent(open(context)).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build());
    }
    private Notification notification(){
        PendingIntent stop=PendingIntent.getService(this,NOTIFICATION,new Intent(this,ClaimDepositService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.notification).setContentTitle("Monogram · payment links")
            .setContentText(jobs.size()+" link(s) being checked in the background").setContentIntent(open(this)).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(0,"Stop checking",stop).build();
    }
    private void update(){if(jobs.isEmpty())stopSelf();else ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());}
    @Override public void onCreate(){
        super.onCreate();instance=this;NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"Payment link deposits",NotificationManager.IMPORTANCE_LOW);channel.setShowBadge(false);channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(channel);}
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(NOTIFICATION,notification());
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);if(power!=null){cpu=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Monogram:ClaimDeposits");cpu.setReferenceCounted(false);cpu.acquire(MAX_RUN);}
    }
    private static void stopJobs(){for(Job job:new ArrayList<>(jobs.values()))job.finish("paused","Background checking stopped; reopen the link to resume");}
    @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null && STOP.equals(intent.getAction())){stopJobs();stopSelf();}else update();return START_NOT_STICKY;}
    @Override public void onTimeout(int id,int type){stopJobs();stopSelf();}
    @Override public void onDestroy(){if(instance==this)instance=null;stopJobs();if(cpu!=null && cpu.isHeld())cpu.release();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
