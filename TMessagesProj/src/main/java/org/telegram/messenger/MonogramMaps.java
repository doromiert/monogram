// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

/** Device-wide map choice. Selecting Google never makes Play services mandatory. */
public final class MonogramMaps {
    public static final int OPEN_STREET_MAP = 0, GOOGLE = 1;
    private MonogramMaps() {}

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("monogram-maps", Context.MODE_PRIVATE);
    }
    public static int selected(Context context) {
        return settings(context).getInt("provider", OPEN_STREET_MAP) == GOOGLE ? GOOGLE : OPEN_STREET_MAP;
    }
    public static boolean select(Context context, int provider) {
        if (provider != OPEN_STREET_MAP && provider != GOOGLE) throw new IllegalArgumentException("Unknown map provider");
        if (!settings(context).edit().putInt("provider", provider).commit()) return false;
        ApplicationLoader.resetMapsProvider();
        return true;
    }
    public static boolean hasGoogleKey(Context context) {
        return !context.getString(R.string.monogram_google_maps_api_key).trim().isEmpty();
    }
    public static boolean hasGoogleServices(Context context) {
        try {
            return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
    public static int statusText(Context context) {
        if (!hasGoogleKey(context)) return R.string.MonogramMapsNoKey;
        if (!hasGoogleServices(context)) return R.string.MonogramMapsNoServices;
        return R.string.MonogramMapsGoogleInfo;
    }
    public static IMapsProvider create(Context context) {
        return create(selected(context) == GOOGLE, hasGoogleKey(context), () -> hasGoogleServices(context), () -> {
            IMapsProvider provider = new GoogleMapsProvider();
            provider.initializeMaps(context);
            return provider;
        });
    }
    static IMapsProvider create(boolean google, boolean configured, java.util.function.BooleanSupplier available,
                               java.util.function.Supplier<IMapsProvider> initialize) {
        // Do not initialize or instantiate the Google renderer on the OSM path.
        try {
            if (google && configured && available.getAsBoolean()) return initialize.get();
        } catch (RuntimeException | LinkageError unavailable) {
            // No repair dialogs, Play Store launches, or location-provider changes.
        }
        return new OpenStreetMapProvider();
    }
}
