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
