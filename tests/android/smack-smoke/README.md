# Packaged Smack startup regression

The standalone instrumentation process loads the installed Monogram beta APK,
sets an empty thread context class loader, reproduces raw Smack discovery failure,
then runs the real shared Android bootstrap concurrently from two workers. It
initializes Smack configuration/providers and parses a fixture message. It does
not start Monogram activities, access accounts or open XMPP sockets.

Run `scripts/check-smack-apk-startup.sh` with `ANDROID_HOME`, `ANDROID_SERIAL` and
`MONOGRAM_ANDROID_KEYSTORE` pointing to the SDK, test device and local debug
keystore. Use `-e baseline true` to check an older APK without the new bootstrap.
The baseline must report `raw_discovery_reproduced=true`; fixed APKs must also
report `PASS packaged APK sterile-loader concurrent Smack startup and XML parse`.

For a minified candidate without installing it as the user's app, copy its APK
into this fixture's private files directory, make it read-only, and pass
`-e apk /data/user/0/com.negzero.monogram.smacksmoke/files/candidate.apk`.
The same parser/configuration check then exercises the candidate's DEX/resources.
The optional `configClass` argument supports diagnosing obfuscated configuration
classes before keep rules are repaired; that mode checks configuration startup
without the reflection-only explicit parser assertion.

The signing-identity verifier requires API28 or newer and reports the installed
version and SHA-256 certificate. Add `-e revision true` to dispatch the actual
installed `putJsonAt`/`appliedVersion` JNI methods with inactive handles. This
verifies Android symbol/descriptor binding without creating a store or sockets;
full revision semantics are covered by the production host JNI fixture.
