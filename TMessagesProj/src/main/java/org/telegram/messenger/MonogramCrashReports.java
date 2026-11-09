// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded diagnostic reports, never raw logcat/XML, exception messages or thread names. */
public final class MonogramCrashReports {
    static final int MAX_BYTES=65536;
    private static final ArrayDeque<String> events=new ArrayDeque<>();
    private MonogramCrashReports() {}
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        if(context!=null && !(previous instanceof Handler))
            Thread.setDefaultUncaughtExceptionHandler(new Handler(context.getApplicationContext(),previous));
    }
    public static void connectionState(int account,int state) {event("XMPP slot="+account+" state="+state);}
    public static void connectionFailure(int account,Exception error) {
        String kind=error==null ? "closed" : error.getClass().getName();
        if(error instanceof org.jivesoftware.smack.XMPPException.StreamErrorException
                && ((org.jivesoftware.smack.XMPPException.StreamErrorException)error).getStreamError()!=null)
            kind+=" condition="+((org.jivesoftware.smack.XMPPException.StreamErrorException)error).getStreamError().getCondition();
        event("XMPP slot="+account+" "+kind);
    }
    private static synchronized void event(String value) {
        if(events.size()==32)events.removeFirst();events.addLast(SystemClock.elapsedRealtime()+" "+value);
    }
    static synchronized String report(Throwable error) {
        StringBuilder text=new StringBuilder("Monogram crash report\nVersion: ").append(BuildConfig.BUILD_VERSION_STRING)
            .append("\nAndroid API: ").append(Build.VERSION.SDK_INT).append("\nABI: ").append(Build.SUPPORTED_ABIS.length==0 ? "unknown" : Build.SUPPORTED_ABIS[0])
            .append("\nException messages, message XML, URLs and account addresses are omitted.\n\n");
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int depth=0;error!=null && depth<16 && seen.add(error) && text.length()<28000;depth++,error=error.getCause()) {
            text.append(depth==0 ? "" : "Caused by: ").append(error.getClass().getName()).append('\n');
            StackTraceElement[] frames=error.getStackTrace();
            for(int i=0;i<Math.min(frames.length,128) && text.length()<28000;i++)text.append("  at ").append(frames[i]).append('\n');
            if(frames.length>128)text.append("  [remaining frames omitted]\n");
        }
        text.append("\nRecent connection diagnostics (elapsed ms):\n");
        for(String event:events)text.append(event).append('\n');
        // Bound UTF-8 too, including unusual Unicode in compiled stack-frame names.
        String report=text.toString();
        while(report.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)report=report.substring(0,report.length()/2);
        return report;
    }
    private static File file(Context context,String name) {return new File(context.getNoBackupFilesDir(),name);}
    public static String last(Context context) {
        try {
            AtomicFile stored=new AtomicFile(file(context,"monogram-last-crash.txt"));
            if(!stored.getBaseFile().exists() || stored.getBaseFile().length()>MAX_BYTES)return null;
            return new String(stored.readFully(),StandardCharsets.UTF_8);
        } catch(Exception unavailable) {return null;}
    }
    static void copy(Context context,String report) {
        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip=ClipData.newPlainText("Monogram crash report",report);
        if(Build.VERSION.SDK_INT>=33) {PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);}
        clipboard.setPrimaryClip(clip);
    }
    public static void showPending(Activity activity) {
        File pending=file(activity,"monogram-crash-pending");
        if(!pending.exists() || activity.isFinishing() || activity.isDestroyed())return;
        String report=last(activity);if(report==null)return;
        new org.telegram.ui.ActionBar.AlertDialog.Builder(activity).setTitle("Monogram stopped unexpectedly")
            .setMessage("A crash report was saved. Monogram tried to copy it when the crash occurred; Android may block clipboard access in the background. You can copy it here or find it later in Developer tools. The report excludes message contents and credentials.")
            .setPositiveButton("Copy crash report",(dialog,which)->copy(activity,report)).setNegativeButton("Close",null).show();
        pending.delete();
    }
    static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;
        private final AtomicBoolean handling=new AtomicBoolean();
        Handler(Context context,Thread.UncaughtExceptionHandler previous) {this.context=context;this.previous=previous;}
        @Override public void uncaughtException(Thread thread,Throwable error) {
            try {
                if(handling.compareAndSet(false,true)) {
                    String report=report(error);
                    AtomicFile stored=new AtomicFile(file(context,"monogram-last-crash.txt"));
                    FileOutputStream output=null;boolean saved=false;
                    try {output=stored.startWrite();output.write(report.getBytes(StandardCharsets.UTF_8));stored.finishWrite(output);saved=true;}
                    catch(Exception failed) {if(output!=null)stored.failWrite(output);}
                    if(saved)try {file(context,"monogram-crash-pending").createNewFile();}catch(Exception ignored) {}
                    try {copy(context,report);}catch(Exception clipboardUnavailable) {}
                }
            } catch(Throwable reportingFailed) {
                // Reporting must never replace Android's original fatal-exception handling.
            } finally {
                if(previous!=null)previous.uncaughtException(thread,error);
                else {android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
            }
        }
    }
}
