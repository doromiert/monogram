# Optional native Monero SDK

`lock.json` pins Monfluo 0.9.7's ARM64 release APK, extracted library hashes,
PGP signer and exact app/Monero source commits. `SHA256SUMS.asc` is the publisher's
clearsigned manifest; `acx.asc` is the pinned publisher key. The BSD notice for
the exact public C++ API is included in `wallet2_api.h`; the application GPL
license is included alongside it. Native build dependencies retain upstream
licenses and notices in the corresponding source/build recipes linked below.

Run `python3 scripts/prepare-monero-wallet.py` with GPG, Android SDK CMake 3.22.1
and NDK 27.2.12479018 available. The script verifies the signature and all pinned
hashes, extracts only the two runtime libraries, then compiles Monogram's small
JNI adapter using the exact header. Binary outputs stay under `.state/`; they
are not vendored or published by the preparation script. Gradle verifies SDK
hashes again before packaging. The private candidate supports ARM64 only.

Source and upstream build instructions:
- https://codeberg.org/Monfluo/monfluo-android/src/tag/0.9.7
- https://codeberg.org/Monfluo/monero/src/commit/9c2e01c6599d11b6283cb4789b943eaa6c854e97

**Transport:** the upstream public wallet API ignores its SSL flag and uses an
automatic SSL mode that accepts invalid certificates. Monogram therefore never
passes a remote node to it. It connects only to a randomly authenticated loopback
HTTP endpoint; `WalletTlsBridge` verifies Android trust chains and HTTPS hostnames
for every remote socket and strips the local credentials before forwarding.
This is a persistent transport boundary, not a preflight certificate check.

The native dependency's original JNI_OnLoad is not invoked: only Monogram's
adapter is loaded from Java. Seed handling, scanning and signing call the public
Monero C++ API. The old Monfluo Java/JNI UI model is not included. No native
wallet is created, loaded or synced for users who have not enabled Monero.
