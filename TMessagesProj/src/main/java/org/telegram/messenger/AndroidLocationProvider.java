// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.util.Consumer;
import java.util.*;

/** Android's own GPS/network providers, shared without sharing account listener lifetimes. */
@SuppressLint("MissingPermission")
public final class AndroidLocationProvider implements ILocationServiceProvider {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final IdentityHashMap<ILocationListener,Registration> registrations=new IdentityHashMap<>();
    private volatile Context context;
    private volatile LocationManager manager;

    @Override public synchronized void init(Context context) {
        if(this.context!=null) return;
        Context application=Objects.requireNonNull(context).getApplicationContext();
        this.context=application==null ? context : application;
        manager=(LocationManager)this.context.getSystemService(Context.LOCATION_SERVICE);
    }
    private boolean granted(String permission) {
        return context!=null && context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean fine() { return granted(Manifest.permission.ACCESS_FINE_LOCATION); }
    private boolean permitted(String provider) {
        return fine() || !LocationManager.GPS_PROVIDER.equals(provider) && granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }
    private boolean exists(String provider) {
        try { return manager!=null && manager.getAllProviders().contains(provider); }
        catch(RuntimeException unavailable) { return false; }
    }
    private boolean enabled(String provider) {
        try { return exists(provider) && manager.isProviderEnabled(provider); }
        catch(RuntimeException unavailable) { return false; }
    }
    @Override public boolean checkServices() { return exists(LocationManager.GPS_PROVIDER) || exists(LocationManager.NETWORK_PROVIDER); }
    @Override public ILocationRequest onCreateLocationRequest() { return new Request(); }
    private List<String> providers(Request request) {
        ArrayList<String> providers=new ArrayList<>();
        if(request.priority==PRIORITY_NO_POWER) {
            if(permitted(LocationManager.PASSIVE_PROVIDER) && enabled(LocationManager.PASSIVE_PROVIDER)) providers.add(LocationManager.PASSIVE_PROVIDER);
            return providers;
        }
        boolean network=permitted(LocationManager.NETWORK_PROVIDER) && enabled(LocationManager.NETWORK_PROVIDER);
        if(permitted(LocationManager.GPS_PROVIDER) && enabled(LocationManager.GPS_PROVIDER)
                && (request.priority==PRIORITY_HIGH_ACCURACY || !network)) providers.add(LocationManager.GPS_PROVIDER);
        if(network) providers.add(LocationManager.NETWORK_PROVIDER);
        return providers;
    }
    @Override public void checkLocationSettings(ILocationRequest request,Consumer<Integer> callback) {
        Objects.requireNonNull(callback);Request selected=snapshot(request);
        main.post(() -> callback.accept(settings(selected)));
    }
    private int settings(Request selected) {
        int status;
        boolean permission=fine() || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if(!permission || !checkServices()) status=STATUS_SETTINGS_CHANGE_UNAVAILABLE;
        else if(providers(selected).isEmpty() || !enabled(LocationManager.GPS_PROVIDER) && !enabled(LocationManager.NETWORK_PROVIDER)) {
            boolean possible=selected.priority==PRIORITY_NO_POWER ? permitted(LocationManager.PASSIVE_PROVIDER) && exists(LocationManager.PASSIVE_PROVIDER)
                    : permitted(LocationManager.GPS_PROVIDER) && exists(LocationManager.GPS_PROVIDER)
                    || permitted(LocationManager.NETWORK_PROVIDER) && exists(LocationManager.NETWORK_PROVIDER);
            status=possible ? STATUS_RESOLUTION_REQUIRED : STATUS_SETTINGS_CHANGE_UNAVAILABLE;
        } else status=STATUS_SUCCESS;
        return status;
    }
    @Override public void getLastLocation(Consumer<Location> callback) {
        Objects.requireNonNull(callback);Location latest=null;boolean precise=fine();
        for(String provider:Arrays.asList(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER)) {
            if(!exists(provider) || !permitted(provider)) continue;
            try {
                Location candidate=manager.getLastKnownLocation(provider);
                if(usable(candidate) && permitted(candidate.getProvider()) && (latest==null || newer(candidate,latest))) latest=new Location(candidate);
            } catch(RuntimeException unavailable) { /* A missing permission/provider must still complete the callback. */ }
        }
        final Location result=latest;
        main.post(() -> callback.accept(result!=null && (!precise || fine()) && permitted(result.getProvider()) ? new Location(result) : null));
    }
    private static boolean usable(Location location) {
        if(location==null || location.getTime()<=0 && location.getElapsedRealtimeNanos()<=0) return false;
        double latitude=location.getLatitude(),longitude=location.getLongitude();
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude) && Math.abs(latitude)<=90
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude) && Math.abs(longitude)<=180;
    }
    private static boolean newer(Location candidate,Location previous) {
        long first=candidate.getElapsedRealtimeNanos(),second=previous.getElapsedRealtimeNanos();
        int order=Long.compare(first>0 && second>0 ? first : candidate.getTime(),first>0 && second>0 ? second : previous.getTime());
        return order>0 || order==0 && candidate.hasAccuracy() && (!previous.hasAccuracy() || candidate.getAccuracy()<previous.getAccuracy());
    }
    private static Request snapshot(ILocationRequest request) {
        if(!(request instanceof Request)) throw new IllegalArgumentException("Unknown Android location request");
        return ((Request)request).copy();
    }
    @Override public synchronized void requestLocationUpdates(ILocationRequest request,ILocationListener listener) {
        Objects.requireNonNull(listener);Request selected=snapshot(request);removeLocationUpdates(listener);
        if(manager==null) return;
        List<String> providers=providers(selected);if(providers.isEmpty()) return;
        Registration registration=new Registration(listener,selected,fine());registrations.put(listener,registration);boolean registered=false;
        for(String provider:providers) try {
            if(Build.VERSION.SDK_INT>=31) {
                int quality=selected.priority==PRIORITY_HIGH_ACCURACY ? android.location.LocationRequest.QUALITY_HIGH_ACCURACY
                        : selected.priority==PRIORITY_BALANCED_POWER_ACCURACY ? android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                        : android.location.LocationRequest.QUALITY_LOW_POWER;
                android.location.LocationRequest nativeRequest=new android.location.LocationRequest.Builder(selected.priority==PRIORITY_NO_POWER
                        ? android.location.LocationRequest.PASSIVE_INTERVAL : selected.interval)
                        .setMinUpdateIntervalMillis(selected.fastest).setQuality(quality).build();
                manager.requestLocationUpdates(provider,nativeRequest,main::post,registration);
            } else manager.requestLocationUpdates(provider,selected.interval,0,registration,Looper.getMainLooper());
            registered=true;
        } catch(RuntimeException unavailable) { /* Try another permitted provider without prompting for permission. */ }
        if(!registered) removeLocationUpdates(listener);
    }
    @Override public synchronized void removeLocationUpdates(ILocationListener listener) {
        Registration registration=registrations.remove(listener);
        if(registration!=null && manager!=null) try { manager.removeUpdates(registration); } catch(RuntimeException unavailable) {}
    }
    private final class Registration implements LocationListener {
        final ILocationListener listener;final Request request;final boolean precise;
        long lastDelivered=-1;
        Registration(ILocationListener listener,Request request,boolean precise) { this.listener=listener;this.request=request;this.precise=precise; }
        @Override public void onLocationChanged(Location location) {
            if(!usable(location)) return;Location copy=new Location(location);
            main.post(() -> {
                synchronized(AndroidLocationProvider.this) {
                    if(registrations.get(listener)!=this || precise && !fine() || !permitted(copy.getProvider())) return;
                    long now=SystemClock.elapsedRealtime();long fastest=Math.min(request.interval,request.fastest);
                    if(lastDelivered>=0 && now-lastDelivered<fastest) return;
                    lastDelivered=now;listener.onLocationChanged(new Location(copy));
                }
            });
        }
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
        @Override public void onStatusChanged(String provider,int status,Bundle extras) {}
    }
    @Override public IMapApiClient onCreateLocationServicesAPI(Context context,IAPIConnectionCallbacks callbacks,IAPIOnConnectionFailedListener failed) {
        Objects.requireNonNull(callbacks);Objects.requireNonNull(failed);
        return new IMapApiClient() {
            long generation;boolean connecting;
            @Override public synchronized void connect() {
                if(connecting) return;connecting=true;long expected=++generation;
                main.post(() -> {
                    synchronized(this) {
                        if(!connecting || generation!=expected) return;
                        if(checkServices()) callbacks.onConnected(new Bundle());
                        else { connecting=false;failed.onConnectionFailed(); }
                    }
                });
            }
            @Override public synchronized void disconnect() { connecting=false;generation++; }
        };
    }
    private static final class Request implements ILocationRequest {
        int priority=PRIORITY_BALANCED_POWER_ACCURACY;
        long interval=3600000,fastest=600000;boolean explicitFastest;
        synchronized Request copy() { Request result=new Request();result.priority=priority;result.interval=interval;result.fastest=fastest;result.explicitFastest=explicitFastest;return result; }
        @Override public synchronized void setPriority(int priority) {
            if(priority<PRIORITY_HIGH_ACCURACY || priority>PRIORITY_NO_POWER) throw new IllegalArgumentException("Invalid location priority");this.priority=priority;
        }
        @Override public synchronized void setInterval(long interval) {
            if(interval<0) throw new IllegalArgumentException("Invalid location interval");this.interval=interval;if(!explicitFastest) fastest=interval/6;
        }
        @Override public synchronized void setFastestInterval(long interval) {
            if(interval<0) throw new IllegalArgumentException("Invalid fastest location interval");fastest=interval;explicitFastest=true;
        }
    }
}
