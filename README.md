# Monogram

Telegram FOSS for Android, being ported to XMPP. GPL-2.0-or-later upstream; new Monogram code is GPL-3.0-or-later (compatible with the OMEMO Signal backend).

**Experimental development build.** Telegram's interface now has Jabber sign-in, a direct-message adapter and per-contact OMEMO controls. The shared transport passes a local TLS/OMEMO integration test. Android runtime verification and broader interoperability are still in progress; this is not a release.

Intended public home: https://monogram.neg-zero.com. The optional helper will be deployable on 192.168.1.24. Neither address is required for ordinary Jabber messaging.

## Development with Nix

```sh
nix develop
mvn -f xmpp-core/pom.xml test
./scripts/test-xmpp.sh
./scripts/build-native.sh arm64
./gradlew :TMessagesProj_App:assembleAfatDebug -PmonogramAbi=arm64-v8a
```

The pinned flake supplies Java 11, Maven, Android SDK 33, NDK 21, CMake and Prosody. Gradle/Maven caches and integration server data stay in this checkout. Android native libraries also require the upstream git submodules and FFmpeg build; see [upstream build instructions](docs/UPSTREAM.md).

The shared `xmpp-core` is a Java library used by the Android project and exercised against a local TLS-enabled Prosody. No Telegram API credentials are needed. See [port status and protocol decisions](docs/PORT.md).
