// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.pm.*;
import android.os.Build;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.telegram.messenger.*;

/** GitHub release transport for Telegram's existing optional updater UI. */
public final class MonogramUpdater {
    private static final MonogramUpdater INSTANCE=new MonogramUpdater();
    public static MonogramUpdater get() { return INSTANCE; }
    static final String API="https://api.github.com/repos/doromiert/monogram/releases/latest";
    private static final long MAX_APK=256L*1024*1024, INTERVAL=24L*60*60*1000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Runnable> callbacks=new ArrayList<>();
    private Release release;
    private boolean checking,downloading;
    private float progress;
    private File downloaded;
    private volatile int generation;
    private long lastCheck;
    private final Runnable dailyCheck=()->check(false,()->{
        BetaUpdate pending=update();
        if(pending!=null && !downloading() && !ApplicationLoader.mainInterfacePaused && org.telegram.ui.LaunchActivity.instance!=null)
            ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(org.telegram.ui.LaunchActivity.instance,pending,UserConfig.selectedAccount);
    });
    private void scheduleDailyCheck() {
        AndroidUtilities.cancelRunOnUIThread(dailyCheck);
        AndroidUtilities.runOnUIThread(dailyCheck,Math.max(1000,INTERVAL-(android.os.SystemClock.elapsedRealtime()-lastCheck)));
    }

    static final class Release {
        final String version,url,digest,notes;final long size;
        Release(String version,String url,String digest,String notes,long size) { this.version=version;this.url=url;this.digest=digest;this.notes=notes;this.size=size; }
    }
    static int version(String text) {
        if(text==null || !text.matches("v?[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) throw new IllegalArgumentException("Unsupported release version");
        String[] parts=text.replaceFirst("^v","").split("\\.");
        return Integer.parseInt(parts[0])*1000000+Integer.parseInt(parts[1])*1000+Integer.parseInt(parts[2]);
    }
    static Release parse(JSONObject json,String abi) throws Exception {
        if(json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
        String tag=json.getString("tag_name");version(tag);String name=tag.replaceFirst("^v","");
        JSONArray assets=json.getJSONArray("assets");
        String target="monogram-"+name+"-"+(abi.equals("arm64-v8a")?"arm64":abi)+".apk";
        for(int i=0;i<assets.length();i++) {
            JSONObject item=assets.getJSONObject(i);
            if(!target.equals(item.optString("name")) || !"uploaded".equals(item.optString("state"))) continue;
            String url=item.getString("browser_download_url"),digest=item.optString("digest");long size=item.getLong("size");
            String expected="https://github.com/doromiert/monogram/releases/download/"+tag+"/"+target;
            if(!url.equals(expected) || !digest.matches("sha256:[a-fA-F0-9]{64}") || size<=0 || size>MAX_APK) throw new IOException("Invalid release asset");
            return new Release(name,url,digest.substring(7).toLowerCase(Locale.ROOT),json.optString("body"),size);
        }
        return null;
    }
    private static PackageInfo installed(int flags) throws Exception {
        return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(),flags);
    }
    public synchronized BetaUpdate update() { return release==null ? null : new BetaUpdate(release.version,version(release.version),release.notes); }
    public synchronized boolean downloading() { return downloading; }
    public synchronized float progress() { return progress; }
    public synchronized File file() { return downloaded!=null && downloaded.isFile() ? downloaded : null; }
    private static void event(int id) { AndroidUtilities.runOnUIThread(()->NotificationCenter.getGlobalInstance().postNotificationName(id)); }
    private static void notice(String value) { AndroidUtilities.runOnUIThread(()->Toast.makeText(ApplicationLoader.applicationContext,value,Toast.LENGTH_LONG).show()); }
    public synchronized void check(boolean force,Runnable done) {
        if(done!=null) callbacks.add(done);
        if(checking) return;
        if(!force && lastCheck>0 && android.os.SystemClock.elapsedRealtime()-lastCheck<INTERVAL) { complete();scheduleDailyCheck();return; }
        checking=true;
        worker.execute(()->{
            String error=null;Release next=null;boolean success=false;
            try {
                byte[] metadata=read(API,1024*1024);
                next=parse(new JSONObject(new String(metadata,java.nio.charset.StandardCharsets.UTF_8)),Build.SUPPORTED_ABIS[0]);
                if(next==null) throw new IOException("No compatible APK in latest release");
                if(version(next.version)<=version(installed(0).versionName)) next=null;
                success=true;
            } catch(Exception failed) { error="Could not check Monogram updates. Try again later.";FileLog.e(failed); }
            final Release found=next;final String failure=error;final boolean ok=success;
            AndroidUtilities.runOnUIThread(()->{
                synchronized(this) {
                    checking=false;
                    lastCheck=android.os.SystemClock.elapsedRealtime();
                    scheduleDailyCheck();
                    if(ok) {
                        if(release==null || found==null || !release.version.equals(found.version) || !release.digest.equals(found.digest)) {
                            if(downloading) cancel();
                            if(downloaded!=null) downloaded.delete();downloaded=null;
                        }
                        release=found;
                    }
                    complete();
                }
                event(NotificationCenter.appUpdateAvailable);
                if(force && failure!=null) notice(failure);
                else if(force && found==null) notice("Monogram is up to date.");
            });
        });
    }
    private void complete() {
        ArrayList<Runnable> done=new ArrayList<>(callbacks);callbacks.clear();
        AndroidUtilities.runOnUIThread(()->{ for(Runnable callback:done) callback.run(); });
    }
    private static HttpURLConnection connection(String address) throws Exception {
        URL url=new URL(address);
        for(int redirect=0;redirect<6;redirect++) {
            String host=url.getHost();
            if(!"https".equals(url.getProtocol()) || url.getUserInfo()!=null || !(host.equals("api.github.com") || host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"))) throw new IOException("Unexpected update destination");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setRequestProperty("User-Agent","Monogram-Updater");
            connection.setRequestProperty("Accept",host.equals("api.github.com")?"application/vnd.github+json":"application/octet-stream");
            int status=connection.getResponseCode();
            if(status>=300 && status<400) { String location=connection.getHeaderField("Location");connection.disconnect();if(location==null) throw new IOException("Missing redirect");url=new URL(url,location);continue; }
            if(status!=200) { connection.disconnect();throw new IOException("Update HTTP "+status); }return connection;
        }
        throw new IOException("Too many update redirects");
    }
    private static byte[] read(String url,int max) throws Exception {
        HttpURLConnection c=connection(url);
        try(InputStream input=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1) { if(out.size()+count>max) throw new IOException("Update metadata too large");out.write(buffer,0,count); }return out.toByteArray();
        } finally { c.disconnect(); }
    }
    @SuppressWarnings("deprecation") private static void verify(File file,Release release) throws Exception {
        PackageManager manager=ApplicationLoader.applicationContext.getPackageManager();
        PackageInfo apk=manager.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES),current=installed(PackageManager.GET_SIGNATURES);
        if(apk==null || !current.packageName.equals(apk.packageName) || !release.version.equals(apk.versionName)
                || apk.versionCode<=current.versionCode || apk.signatures==null || current.signatures==null
                || !new HashSet<>(Arrays.asList(apk.signatures)).equals(new HashSet<>(Arrays.asList(current.signatures)))) throw new SecurityException("APK package, version or signer mismatch");
    }
    public synchronized void download() {
        if(downloading || release==null || file()!=null) return;
        final Release target=release;final int job=++generation;downloading=true;progress=0;event(NotificationCenter.appUpdateLoading);
        worker.execute(()->{
            File part=new File(ApplicationLoader.applicationContext.getCacheDir(),"monogram-update-"+job+".part");
            File apk=new File(ApplicationLoader.applicationContext.getCacheDir(),"monogram-update-"+target.version+".apk");
            HttpURLConnection c=null;boolean accepted=false;
            try {
                c=connection(target.url);MessageDigest hash=MessageDigest.getInstance("SHA-256");long total=0,last=0;
                try(InputStream input=c.getInputStream();OutputStream output=new FileOutputStream(part)) {
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1) {
                        if(job!=generation) throw new InterruptedIOException("Update cancelled");total+=count;
                        if(total>target.size) throw new IOException("Update size mismatch");output.write(buffer,0,count);hash.update(buffer,0,count);
                        if(System.currentTimeMillis()-last>150) { synchronized(this) { if(job==generation) progress=(float)total/target.size; }event(NotificationCenter.appUpdateLoading);last=System.currentTimeMillis(); }
                    }
                }
                StringBuilder digest=new StringBuilder();for(byte value:hash.digest()) digest.append(String.format(Locale.ROOT,"%02x",value&255));
                if(total!=target.size || !target.digest.equals(digest.toString())) throw new SecurityException("Update digest mismatch");
                verify(part,target);
                synchronized(this) {
                    if(job!=generation) throw new InterruptedIOException("Update cancelled");
                    if(!part.renameTo(apk)) throw new IOException("Could not finalize update");downloaded=apk;progress=1;accepted=true;
                }
            } catch(Exception failed) { if(job==generation) { FileLog.e(failed);notice("Update download failed. Please try again."); } }
            finally {
                if(c!=null) c.disconnect();part.delete();
                synchronized(this) { if(job==generation) downloading=false; }
                event(NotificationCenter.appUpdateAvailable);
            }
            if(accepted) AndroidUtilities.runOnUIThread(()->{
                if(job!=generation || org.telegram.ui.LaunchActivity.instance==null || ApplicationLoader.mainInterfacePaused) return;
                ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(org.telegram.ui.LaunchActivity.instance,update(),UserConfig.selectedAccount);
            });
        });
    }
    public synchronized void cancel() { generation++;downloading=false;progress=0;event(NotificationCenter.appUpdateAvailable); }
}
