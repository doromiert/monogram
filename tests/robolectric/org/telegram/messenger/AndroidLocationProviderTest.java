package org.telegram.messenger;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLocationManager;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidLocationProviderTest {
    private Application application;
    private AndroidLocationProvider provider;
    private ShadowLocationManager locations;
    @Before public void setUp() {
        application=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION);
        locations=Shadows.shadowOf((LocationManager)application.getSystemService(Context.LOCATION_SERVICE));
        locations.setLocationEnabled(true);
        locations.setProviderEnabled(LocationManager.GPS_PROVIDER,true);
        locations.setProviderEnabled(LocationManager.NETWORK_PROVIDER,true);
        provider=new AndroidLocationProvider();provider.init(application);
    }
    private void fine() { Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private ILocationServiceProvider.ILocationRequest request(int priority) {
        ILocationServiceProvider.ILocationRequest r=provider.onCreateLocationRequest();r.setPriority(priority);r.setInterval(1000);r.setFastestInterval(0);return r;
    }
    private Location fix(String source,long elapsed,long wall,double latitude) {
        Location value=new Location(source);value.setLatitude(latitude);value.setLongitude(20);value.setAccuracy(10);value.setTime(wall);value.setElapsedRealtimeNanos(elapsed);return value;
    }
    @Test public void missingPermissionCompletesWithoutStartingLocation() {
        List<Location> last=new ArrayList<>();List<Integer> settings=new ArrayList<>();
        provider.getLastLocation(last::add);provider.checkLocationSettings(request(0),settings::add);
        provider.requestLocationUpdates(request(0),value -> fail("Location without permission"));idle();
        assertEquals(1,last.size());assertNull(last.get(0));
        assertEquals(java.util.Collections.singletonList(ILocationServiceProvider.STATUS_SETTINGS_CHANGE_UNAVAILABLE),settings);
        assertTrue(locations.getLocationUpdateListeners().isEmpty());
    }
    @Test public void lastFixUsesMonotonicAgeAndCoarseNeverReadsGps() {
        fine();Location gps=fix(LocationManager.GPS_PROVIDER,10,9000,40),network=fix(LocationManager.NETWORK_PROVIDER,20,1000,41);
        locations.setLastKnownLocation(LocationManager.GPS_PROVIDER,gps);locations.setLastKnownLocation(LocationManager.NETWORK_PROVIDER,network);
        List<Location> last=new ArrayList<>();provider.getLastLocation(last::add);idle();
        assertEquals(1,last.size());assertEquals(41,last.get(0).getLatitude(),0);assertNotSame(network,last.get(0));
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        locations.setLastKnownLocation(LocationManager.GPS_PROVIDER,fix(LocationManager.GPS_PROVIDER,30,10000,42));
        provider.getLastLocation(last::add);idle();assertEquals(41,last.get(1).getLatitude(),0);
    }
    @Test public void replacingAndRemovingOneListenerFencesQueuedCallbacksAndRetainsOtherAccount() {
        fine();List<Location> first=new ArrayList<>(),second=new ArrayList<>();
        ILocationServiceProvider.ILocationListener one=first::add,two=second::add;
        provider.requestLocationUpdates(request(0),one);idle();
        assertEquals(1,locations.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size());
        LocationListener stale=locations.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).get(0);
        provider.requestLocationUpdates(request(0),one);idle();
        LocationListener removed=locations.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).get(0);
        provider.requestLocationUpdates(request(0),two);idle();
        assertEquals(2,locations.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size());
        stale.onLocationChanged(fix(LocationManager.GPS_PROVIDER,10,1000,40));idle();assertTrue(first.isEmpty());
        provider.removeLocationUpdates(one);idle();
        assertEquals(1,locations.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size());
        removed.onLocationChanged(fix(LocationManager.GPS_PROVIDER,20,2000,41));idle();assertTrue(first.isEmpty());
        locations.simulateLocation(fix(LocationManager.GPS_PROVIDER,30,3000,42));idle();assertEquals(1,second.size());
        provider.removeLocationUpdates(two);idle();assertTrue(locations.getLocationUpdateListeners().isEmpty());
    }
    @Test public void coarseAndPassiveRequestsDoNotRegisterGps() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        ILocationServiceProvider.ILocationListener listener=value -> {};
        provider.requestLocationUpdates(request(0),listener);idle();
        assertTrue(locations.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty());
        assertEquals(1,locations.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).size());
        provider.removeLocationUpdates(listener);idle();fine();
        provider.requestLocationUpdates(request(ILocationServiceProvider.PRIORITY_NO_POWER),listener);idle();
        assertTrue(locations.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty());
        assertTrue(locations.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isEmpty());
        assertEquals(1,locations.getLocationUpdateListeners(LocationManager.PASSIVE_PROVIDER).size());
        provider.removeLocationUpdates(listener);idle();
    }
    @Test public void settingsDistinguishDisabledProvidersAndClientsFenceTheirOwnConnectionCallbacks() {
        fine();List<Integer> settings=new ArrayList<>();
        provider.checkLocationSettings(request(0),settings::add);idle();assertEquals(Integer.valueOf(0),settings.get(0));
        locations.setProviderEnabled(LocationManager.GPS_PROVIDER,false);locations.setProviderEnabled(LocationManager.NETWORK_PROVIDER,false);
        provider.checkLocationSettings(request(0),settings::add);idle();assertEquals(Integer.valueOf(1),settings.get(1));
        assertTrue(provider.checkServices());
        AtomicInteger connected=new AtomicInteger(),failed=new AtomicInteger();
        ILocationServiceProvider.IAPIConnectionCallbacks callbacks=new ILocationServiceProvider.IAPIConnectionCallbacks() {
            public void onConnected(Bundle value) { connected.incrementAndGet(); }
            public void onConnectionSuspended(int value) { fail("Unexpected suspension"); }
        };
        ILocationServiceProvider.IMapApiClient first=provider.onCreateLocationServicesAPI(application,callbacks,failed::incrementAndGet);
        ILocationServiceProvider.IMapApiClient second=provider.onCreateLocationServicesAPI(application,callbacks,failed::incrementAndGet);
        first.connect();first.disconnect();second.connect();idle();assertEquals(1,connected.get());assertEquals(0,failed.get());
        first.connect();idle();assertEquals(2,connected.get());first.disconnect();second.disconnect();
    }
}
