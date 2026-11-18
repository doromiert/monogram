# Android update identity

The published beta app is `com.negzero.monogram.beta`. Debug and optimized
release builds must retain that application ID and the established signing key.
Build type, shrinking and debugging options are independent of app identity.

Android cannot install a different application ID as an update to the same app.
Removing `.beta` would require an explicit separate-app/data migration, not a
relaxed updater check. Never suggest uninstalling the existing app as a fix.
See [Android's application ID rules](https://developer.android.com/build/configure-app-module).

Before publishing a signed APK, compare it to the previous published artifact:

```sh
python3 scripts/check-apk-update.py previous.apk candidate.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0"
```

This verifies both APK signatures, matching application IDs and certificate
sets, and an increasing Android version code. Also verify the release name,
asset digest and native-library alignment before making the release latest.

0.2.227 accidentally used `com.negzero.monogram` while retaining the signing
key. The updater correctly rejected it. 0.2.228 restores the established beta
ID. The updater also distinguishes an incompatible ID, signing key or version
from a network failure, without weakening any verification.
