# Monogram port requirements and current evidence

Current source base: official `DrKLO/Telegram` 13.0.2 at `f32ffeec763f0bdbf4bec25b52d654f77bff733d`. The `monogram-current-base` branch is being migrated; current-base UI/runtime verification and completion of proprietary runtime SDK removal remain in progress. Android Java compilation, the arm64 native source build and APK packaging have passed; 0.2.0 is installed on the authorized phone. See `TODO.md`.

The Android evidence below belongs to the older Telegram-FOSS 10.14.3 prototype saved as `d46543df` on `prototype/telegram-foss`. It does not establish current-base behavior.
Public URL requested: https://monogram.neg-zero.com. Optional helper host: 192.168.1.24.
First arm64 debug APK installed successfully through wireless ADB at 192.168.1.128:39127 on 2026-10-16. Package `com.negzero.monogram.beta`, version 0.1.0, version code 49279, minSdk 23. App process and Jabber sign-in screen verified. The initial streamed install was rejected with vendor code -99; file-based installation succeeded. Reconnect before future installations because wireless ADB ports can change.

## Scope retained

This is a port of the Telegram FOSS Android client, retaining its chat interface. It is not a Telegram-to-XMPP gateway and must not depend on Telegram accounts, API credentials or servers. Local Telegram data structures may remain as UI/storage adapters. A successful protocol-library test is not evidence that the Android app works.

The requested end state includes an installable branded Monogram Android client, working Jabber login and messaging, interoperable OMEMO with persistent keys and visible trust decisions, and an optional deployable helper for Telegram-specific functionality. Basic XMPP must work without that helper. The server may run locally for tests; production deployment is not yet ready.

## Protocol decisions

- RFC 6120/6121: XMPP client connections, SASL and mandatory verified TLS. Service identity derives from the user's JID even when connecting through an explicit host.
- XEP-0198, XEP-0280, XEP-0313: stream management, carbons and archive support, negotiated by feature discovery.
- XEP-0184: delivery receipts only, not a claim that the user read a message.
- OMEMO: Smack 4.4.8's `eu.siacs.conversations.axolotl` wire format, commonly called legacy OMEMO. This does **not** implement `urn:xmpp:omemo:2`. Independent client interoperability still needs testing.
- Keys stay in the app-private filesystem; fingerprint decisions are persisted and bound to both JID/device ID and fingerprint. New and changed identities require explicit decisions. Encrypted sends must fail rather than fall back to plaintext or silently omit a recipient device.
- Prefer XMPP extensions for groups, attachments, replies, edits, reactions, bookmarks, avatars and calls; do not duplicate standard features in a proprietary helper.
- The optional Prosody scheduling module is the first helper feature. It stores opaque message stanzas (including encrypted payloads), validates ownership and limits, and has no OMEMO keys. It currently runs on the account's XMPP server; making a separate remote helper work for external Jabber accounts remains open.

## Work still required

- The first adapter now wires Jabber login, persistent JID/message IDs, roster reads, direct text messages and keychain-protected credentials into Telegram's controllers. Native MTProto initialization and request dispatch have been replaced. Audit all remaining Telegram-specific entry points and implement cancellation, reconnection state, logout races and robust offline synchronization.
- Jabber onboarding, a new-conversation address screen, a sending-mode subtitle and per-contact encryption/fingerprint controls now compile. Initial sign-in rendering and app startup are verified on the provided API 37 arm64 phone; account login and actual chat flows are not yet verified there. Replace remaining Telegram-specific settings/promotions and polish trust status and error handling.
- Native arm64 dependencies and the first debug APK built successfully with Nix. Installation and startup are verified on the provided ADB device. Next verify account login, plaintext and OMEMO chat flows on Android.
- Persist/deduplicate incoming stanza IDs and receipts; verify offline history, MAM paging, carbon direction and multi-device encryption. Library presence alone is not proof of these paths.
- Add standard group chat, upload/download (including OMEMO files), avatars, editing, replies, reactions, notifications, account management and calls as supported. Identify and implement the remaining Telegram-specific helper functions with graceful feature discovery.
- Package helper deployment for the specified host; verify service authentication, restart behavior and Android use of helper features. Current scheduling delivery removes the queued entry before routing: at most once, with a small message-loss window on a process crash.
- Finish launcher art, app links, all reachable branding, licenses/attribution and release documentation.

## Current-base build evidence

All arm64 native libraries rebuilt successfully from the pinned upstream submodules under Nix on 2026-10-16: `.state/current-native-build.log`, `All prebuilt libraries were built successfully`. Bundled archives/generated headers are removed from Git tracking and ignored; `scripts/build-native.sh` generates them. The Nix toolchain includes SDK/build tools 35 for Media3 and 36 for the app, Java 21, NDK 27.2, CMake 3.22.1 and Rust 1.96.1 with Android targets.

Firebase startup, Telegram phone login, SMS retrieval, captcha/Play Integrity and Google voice verification are removed from the active build. Other Google runtime dependencies remain to be replaced; this is not yet a finished FOSS APK. The current-base Java compile passed after updating the two moved TL model types in the adapter: `.state/current-java-build.log`, 138 tasks, BUILD SUCCESSFUL. This includes the Jabber account-entry adapter and removal of the Firebase/auth/integrity/captcha dependencies. Full APK linking/packaging subsequently passed: `.state/current-apk-build.log`, 301 tasks, BUILD SUCCESSFUL in 5m 9s. APK 0.2.0 (71759), package `com.negzero.monogram.beta`, contains arm64 libraries and matches the prototype signing certificate. It was installed with `adb install --no-streaming -r`; `.state/current-adb-install.log` records Success.

Before/after update checks verified the saved JID/credential, all 53 existing local message rows, and the OMEMO device ID/identity key unchanged (`.state/android-update-verification.json`). The new process starts. The phone keyguard currently prevents visible UI/security-button verification; that check is still pending. No message was sent or trust decision changed during these checks.

## Bulk fingerprint trust (0.2.1)

`Trust all fingerprints` applies only to the fingerprints displayed in Conversation security, including the contact and other own devices. The confirmation explicitly includes existing distrust decisions. The trust file is replaced atomically; a failed write changes neither the saved file nor in-memory decisions. Keys remain bound to JID, device ID and fingerprint, so new devices and changed keys stay undecided. A session change cancels the pending decision.

The six shared-core tests passed against local TLS-enabled Prosody (`.state/trust-all-core-tests.log`; fixture `.state/integration.6YGee5/server.log`), including bulk persistence/restart, changed/unseen-key isolation and failed-save rollback. The Android APK built successfully with Nix (301 tasks; `.state/trust-all-android-build.log`), and 0.2.1 (71769) was installed successfully with the same signing certificate. The original 53 message rows, saved credential and OMEMO identity/device ID remained intact after this update too. Visible button/confirmation checks are pending an unlocked phone; no real trust decision was changed during testing.

## Reproducible checks

`nix develop .#test --command ./scripts/test-xmpp.sh` creates an isolated local Prosody server with a temporary trusted TLS certificate, runs actual client connections, then stops the server. Server logs and temporary test data remain under ignored `.state/`. Unit and integration results are in `xmpp-core/target/surefire-reports/`.

`nix develop --command ./gradlew :TMessagesProj_App:assembleAfatDebug` is the Android build target, once native submodules have been built. Consult the current `.state/android-build.log` and installed-package evidence for Android build/runtime status. Do not report an APK as ready on the strength of the core tests.

Latest shared-core fixture: `.state/integration.YseQ9h/server.log`, 4 tests passed, 0 skipped/failed (including the OMEMO AES-GCM provider regression). Prototype 0.1.1 Android build log: `.state/android-build-0.1.1.log` (55 tasks, BUILD SUCCESSFUL). Version 0.1.1 (49289) was installed as an account-preserving update; the lock now opens Conversation security. Actual peer OMEMO send and earlier chat recovery remain unverified. Initial screen evidence: `.state/first-launch.png` (private device screenshot; do not publish). The optional NixOS helper module is exported as `nixosModules.monogramHelper`; deployment remains pending.
