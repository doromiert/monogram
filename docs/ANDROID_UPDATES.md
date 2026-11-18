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
Run the exact signed APK in an emulator for at least 60 seconds, monitoring its
process and crash buffer. A crash blocks publication and restarts the full check
after a fix. Exercise the packaged Smack extension startup test too: unit tests
do not expose fields removed by R8.

0.2.227 accidentally used `com.negzero.monogram` while retaining the signing
key. The updater correctly rejected it. 0.2.228 restores the established beta
ID. The updater also distinguishes an incompatible ID, signing key or version
from a network failure, without weakening any verification.

0.2.229 preserves Smack extension static fields used by QName reflection during
PEP/PubSub initialization. The optimized 0.2.228 APK removed these fields and
crashed while restoring XMPP accounts. The packaged startup regression now checks
that exact initialization path, and its shell runner fails on an unsuccessful
instrumentation result even when Android's shell command exits zero.

0.2.230 corrects a second shrinker rule: Smack4.4.8 IQ providers inherit from
`IqProvider` and `AbstractProvider`, not the sibling `Provider` base. Keeping only
`Provider` subclasses removed discovery IQ parsers, so valid server responses
became `UnparsedIQ` and post-login setup disconnected. Keep XML-registered
`AbstractProvider` subclasses and their constructors. The packaged regression
now checks typed discovery, roster, PubSub and MAM IQ parsing as well as startup.

Comparing the actual artifacts identified the delivery regression:0.2.226 was
an unshrunk `afatDebug` beta APK;0.2.227 silently changed to optimized `release`.
There was no XMPP backend source change between them. The optimizer removed IQ
providers and renamed libsignal JNI handle-owner/adapter signatures.0.2.230
restores `afatDebug` delivery, including the user's debug features, and disables
shrinking for the beta `release` configuration as a backstop. Keep rules preserve
the IQ-provider and JNI contracts for future optimization work. The final
packaged test checks the JNI `loadSession(long)` return signature too.
