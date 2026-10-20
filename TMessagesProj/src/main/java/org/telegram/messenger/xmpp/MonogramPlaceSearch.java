// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.os.SystemClock;
import com.negzero.monogram.xmpp.GeoLocation;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;

/** Explicit, text-only Photon searches. Never accepts, reads, biases to, or transmits a device location. */
public final class MonogramPlaceSearch {
    public interface Completion { void done(ArrayList<TLRPC.TL_messageMediaVenue> places,String error); }
    private static final String ENDPOINT="https://photon.komoot.io/api/";
    private static final int LIMIT=10,MAX_RESPONSE=512*1024;
    private static final long CACHE_TIME=7L*24*60*60*1000;
    private static final ScheduledThreadPoolExecutor NETWORK=new ScheduledThreadPoolExecutor(1,r -> { Thread t=new Thread(r,"monogram-place-search");t.setDaemon(true);return t; });
    private static final Semaphore SLOTS=new Semaphore(8);
    private static long nextRequest,lastNetworkRequest,providerRetryAt;
    static { NETWORK.setRemoveOnCancelPolicy(true); }
    private MonogramPlaceSearch() { }
    public static final class Job {
        private final AtomicBoolean cancelled=new AtomicBoolean(),released=new AtomicBoolean();
        private volatile HttpURLConnection connection;private volatile Future<?> future;
        private boolean acquired;
        public void cancel() { cancelled.set(true);HttpURLConnection active=connection;if(active!=null) active.disconnect();Future<?> task=future;if(task!=null) task.cancel(true);release(); }
        private void release() { if(acquired && released.compareAndSet(false,true)) SLOTS.release(); }
        private boolean current(BooleanSupplier owner) { return !cancelled.get() && owner.getAsBoolean(); }
    }
    public static Job search(Context context,int account,String owner,String query,BooleanSupplier current,Completion completion) {
        Job job=new Job();String text=query==null?"":query.trim();String language=Locale.getDefault().getLanguage();
        if(text.length()<2 || text.length()>200 || text.indexOf('\n')>=0 || text.indexOf('\r')>=0) {
            deliver(job,current,completion,new ArrayList<>(),"Enter a place or address (2–200 characters), then press Search.");return job;
        }
        if(!SLOTS.tryAcquire()) { deliver(job,current,completion,new ArrayList<>(),"Place search is busy. Please retry shortly.");return job; }
        job.acquired=true;final Context app=context.getApplicationContext()==null?context:context.getApplicationContext();
        long delay;
        synchronized(MonogramPlaceSearch.class) { long now=SystemClock.elapsedRealtime();long at=Math.max(now,nextRequest);nextRequest=at+1100;delay=at-now; }
        job.future=NETWORK.schedule(() -> {
            ArrayList<TLRPC.TL_messageMediaVenue> result=new ArrayList<>();String error=null;
            try {
                if(!job.current(current)) return;
                File root=new File(app.getCacheDir(),"monogram-place-search/"+account+"-"+hash(owner));
                File cache=new File(root,hash(ENDPOINT+"\n"+language+"\n"+text)+".json");byte[] bytes=null;
                if(cache.isFile() && System.currentTimeMillis()-cache.lastModified()>=0 && System.currentTimeMillis()-cache.lastModified()<CACHE_TIME) try { bytes=read(new FileInputStream(cache),MAX_RESPONSE);result=parse(new String(bytes,StandardCharsets.UTF_8)); }
                catch(Exception invalidCache) { bytes=null;cache.delete(); }
                if(bytes==null) {
                    String encoded=URLEncoder.encode(text,"UTF-8");String lang=Arrays.asList("en","de","fr").contains(language)?"&lang="+language:"";
                    pace(job,current);
                    URL url=new URL(ENDPOINT+"?q="+encoded+"&limit="+LIMIT+lang);
                    HttpURLConnection connection=(HttpURLConnection)url.openConnection();job.connection=connection;
                    try {
                        if(!job.current(current)) return;
                        connection.setRequestProperty("User-Agent","Monogram Android / github.com/doromiert/monogram");connection.setRequestProperty("Accept","application/json");
                        connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                        int status=connection.getResponseCode();
                        if(status==429 || status==503) {
                            long retry=60000;try { retry=Math.max(retry,Math.min(300000,Long.parseLong(connection.getHeaderField("Retry-After"))*1000)); }catch(Exception absent) { }
                            synchronized(MonogramPlaceSearch.class) { providerRetryAt=SystemClock.elapsedRealtime()+retry; }
                            throw new IOException("The place provider is busy or rate limited. Try again later.");
                        }
                        if(status!=200) throw new IOException("The place provider could not complete this search. Please retry.");
                        if(!job.current(current)) return;
                        bytes=read(connection.getInputStream(),MAX_RESPONSE);result=parse(new String(bytes,StandardCharsets.UTF_8));
                    } finally { connection.disconnect();job.connection=null; }
                    if(job.current(current) && (root.isDirectory() || root.mkdirs())) {
                        File temporary=File.createTempFile("query-",".tmp",root);
                        try { try(FileOutputStream out=new FileOutputStream(temporary)) { out.write(bytes); }if(job.current(current)) temporary.renameTo(cache); } finally { temporary.delete(); }
                        trim(root);
                    }
                }
            } catch(java.net.SocketTimeoutException timeout) { error="Place search timed out. Check your connection and retry."; }
            catch(IOException failure) { error=failure instanceof java.net.UnknownHostException || failure instanceof java.net.ConnectException || failure.getMessage()==null?"Place search failed. Check your connection and retry.":failure.getMessage(); }
            catch(Exception malformed) { error="The place provider returned an invalid response. Please retry."; }
            finally { job.release(); }
            deliver(job,current,completion,result,error);
        },delay,TimeUnit.MILLISECONDS);
        if(job.cancelled.get()) job.future.cancel(true);return job;
    }
    private static void pace(Job job,BooleanSupplier current) throws Exception {
        // Enforce spacing at the actual network start too, after slow earlier requests/cache hits.
        while(true) {
            if(!job.current(current)) throw new InterruptedIOException();long remaining;
            synchronized(MonogramPlaceSearch.class) {
                long now=SystemClock.elapsedRealtime();if(providerRetryAt>now) throw new IOException("The place provider is rate limited. Please try again later.");
                remaining=lastNetworkRequest==0?0:lastNetworkRequest+1100-now;
                if(remaining<=0) { lastNetworkRequest=now;return; }
            }
            Thread.sleep(Math.min(remaining,100));
        }
    }
    private static void deliver(Job job,BooleanSupplier current,Completion completion,ArrayList<TLRPC.TL_messageMediaVenue> result,String error) {
        AndroidUtilities.runOnUIThread(() -> { if(job.current(current)) completion.done(result,error); });
    }
    static ArrayList<TLRPC.TL_messageMediaVenue> parse(String json) throws Exception {
        JSONObject root=new JSONObject(json);if(!"FeatureCollection".equals(root.optString("type"))) throw new IOException("Unexpected place response.");
        JSONArray features=root.getJSONArray("features");if(features.length()>100) throw new IOException("Too many place results.");
        ArrayList<TLRPC.TL_messageMediaVenue> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int index=0;index<features.length() && result.size()<LIMIT;index++) {
            JSONObject feature=features.getJSONObject(index),geometry=feature.getJSONObject("geometry"),properties=feature.getJSONObject("properties");
            if(!"Point".equals(geometry.optString("type"))) continue;
            JSONArray coordinates=geometry.getJSONArray("coordinates");if(coordinates.length()<2 || coordinates.length()>3) continue;
            double longitude=coordinates.getDouble(0),latitude=coordinates.getDouble(1);
            String name=label(properties.optString("name",""));StringJoiner address=new StringJoiner(", ");
            for(String field:new String[]{"street","housenumber","postcode","city","state","country"}) { String value=label(properties.optString(field,""));if(!value.isEmpty()) address.add(value); }
            String place=address.toString();if(place.length()>512) place=place.substring(0,512);if(name.isEmpty()) name=place;if(name.isEmpty()) continue;
            try { new GeoLocation(latitude,longitude,0,name,place); } catch(IllegalArgumentException invalidPoint) { continue; }
            String identity=latitude+"/"+longitude+"/"+name;if(!seen.add(identity)) continue;
            TLRPC.TL_messageMediaVenue venue=new TLRPC.TL_messageMediaVenue();venue.geo=new TLRPC.TL_geoPoint();venue.geo.lat=latitude;venue.geo._long=longitude;
            venue.title=name;venue.address=place;venue.provider="openstreetmap";venue.venue_id=label(properties.optString("osm_type",""))+properties.optLong("osm_id",0);venue.venue_type="";venue.icon="pin";
            result.add(venue);
        }return result;
    }
    private static String label(String value) { String clean=value.replace('\n',' ').replace('\r',' ').trim();return clean.length()>512?clean.substring(0,512):clean; }
    private static byte[] read(InputStream stream,int limit) throws IOException {
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) { byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1) { if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();if(out.size()+count>limit) throw new IOException("Place response is too large.");out.write(buffer,0,count); }return out.toByteArray(); }
    }
    private static String hash(String value) throws Exception { StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString(); }
    private static void trim(File directory) { File[] files=directory.listFiles();if(files==null) return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=0;int count=files.length;for(File file:files) total+=file.length();for(File file:files) { if(total<=5L*1024*1024 && count<=128) break;long size=file.length();if(file.delete()) { total-=size;count--; } } }
}
