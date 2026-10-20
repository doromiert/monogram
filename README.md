# Monogram

An XMPP/Jabber port of the current official Telegram Android client, branded Monogram. GPL-2.0-or-later upstream; new Monogram code is GPL-3.0-or-later (compatible with the OMEMO Signal backend).

**Experimental Android client.** Monogram uses official Telegram 13.0.2 (`f32ffeec763f0bdbf4bec25b52d654f77bff733d`); signed arm64 builds are available in [Releases](https://github.com/doromiert/monogram/releases). The Telegram-FOSS prototype is retained on a separate branch. Runtime bugs, independent-client interoperability and removal of remaining proprietary SDKs are still in progress. See [TODO.md](TODO.md) for implementation and verification status.

telegram x monocles = holy chatting
![shitpost.png](shitpost.png)

## Development with Nix

```sh
git submodule update --init --recursive --depth 1
nix develop
mvn -f xmpp-core/pom.xml test
./scripts/test-xmpp.sh
./scripts/build-native.sh arm64-v8a
./gradlew :TMessagesProj_App:assembleAfatDebug -PmonogramAbi=arm64-v8a
```

The pinned flake selects Java 21, Maven, Android SDK 36 (plus SDK 35 for the bundled Media3 modules), NDK 27.2.12479018, CMake 3.22.1, Rust 1.96.1 with Android targets, and Prosody. Gradle/Maven caches and integration server data stay in this checkout. The native wrapper runs the current upstream source build scripts; this migration is still being verified. The lighter `nix develop .#test` shell runs shared-core tests without the Android toolchain. See [upstream source and instructions](docs/UPSTREAM.md).

Work stays in this repository using Git branches. `prototype/telegram-foss` preserves the old prototype; `monogram-current-base` carries the official-source migration.

The shared `xmpp-core` is a Java library used by the Android project and exercised against a local TLS-enabled Prosody. No Telegram API credentials are needed. See [port status and protocol decisions](docs/PORT.md).
