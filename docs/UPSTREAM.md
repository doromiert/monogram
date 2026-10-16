# Source provenance

Current base: [DrKLO/Telegram](https://github.com/DrKLO/Telegram), commit `f32ffeec763f0bdbf4bec25b52d654f77bff733d`, Telegram 13.0.2 (7175), dated 2026-10-13. This is the official Android source linked by [Telegram applications](https://telegram.org/apps#source-code).

The earlier prototype used [Telegram-FOSS-Team/Telegram-FOSS](https://github.com/Telegram-FOSS-Team/Telegram-FOSS) at `10da5406ed92d30c6add3b25d40b2b3b6995d873` (10.14.3). The prototype checkpoint is `d46543df` on `prototype/telegram-foss`. Monogram uses no Telegram API account; upstream Telegram build instructions below describe the unmodified app. Use the root README for the Monogram Nix build.

Upstream code is GPL-2.0-or-later; new Monogram code is GPL-3.0-or-later. Keep upstream copyright notices and publish corresponding source with distributed binaries.

The `modern-omemo` development branch adds [Dino/libomemo-c](https://github.com/dino/libomemo-c), pinned to `1f0b37fee3ebefc46c32f882623481899a1ba568` by `flake.lock` (NAR hash `sha256-HoZykdGVDsj4L5yN3SHGF5tjMq5exJyC15zTLBlpX/c=`). It is a GPL-3.0 fork of libsignal-protocol-c with the newer OMEMO ratchet, XEdDSA/Ed25519 and protobuf format. The source remains immutable in the Nix store; Monogram's CMake wrapper regenerates protocol sources into build outputs rather than modifying upstream files. `protobuf-c` 1.5.2 source/compiler come from the existing pinned nixpkgs input. Native JNI glue is GPL-3.0-or-later; host tests use Nix OpenSSL, while Android reuses the BoringSSL source/build already supplied by the official Telegram base. The new module is not a complete XMPP OMEMO2 implementation yet.

---

## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
