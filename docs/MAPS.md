# Map providers

Chat Settings → Chat behavior → Maps offers OpenStreetMap (default) and Google Maps.
The choice is device-wide and persists across restarts. Existing map screens retain
their provider so changing the preference cannot mix incompatible camera updates,
markers or bounds. Newly opened screens use the new choice.

Google is only constructed after checking both a configured Monogram API key and
Google Play services availability. Missing, disabled or outdated Play services,
missing configuration, or synchronous initialization failure falls back to OSM.
The app does not launch a Play services repair/install dialog. The preference stays
saved; restart after installing/updating Play services to check availability again.
This is a runtime choice, not a claim that this APK has no proprietary libraries.

Location continues to use Android LocationManager for either renderer. Explicit
place search continues to use Photon; selecting Google does not enable Google
Places or change location permissions. Google may contact its services when its
renderer is selected. OSM retains its existing memory and disk tile caches.

## Google configuration

Use a Google Cloud project with Maps SDK for Android enabled and an API key
restricted to Monogram's actual application ID and signing certificate SHA-1.
Upstream Telegram credentials have been removed from our manifest overlays;
they belong to a different project and may be restricted to Telegram.

Configure one of these before building (highest priority first):

- Gradle property `monogramGoogleMapsApiKey` in an untracked Gradle properties file.
- Environment variable `MONOGRAM_GOOGLE_MAPS_API_KEY`.
- `monogram.googleMapsApiKey` in the untracked root `local.properties`.

The generated string resource supplies `com.google.android.geo.API_KEY` in the
merged manifest. The default is empty, so builds without configuration use OSM
even when Google is selected and explain why in settings. Android API keys are
included in APKs; restrict the key in Google Cloud rather than treating it as a
secret that the APK can hide. No Google project or billing account is provisioned
by this implementation.

Availability checks cannot establish whether Google will authorize a configured
key. A nonempty key with incorrect API/package/certificate restrictions can still
produce a blank Google map; switch to OSM while correcting the configuration.

Setup reference: https://developers.google.com/maps/documentation/android-sdk/config
Availability reference: https://developers.google.com/android/reference/com/google/android/gms/common/GoogleApiAvailability
