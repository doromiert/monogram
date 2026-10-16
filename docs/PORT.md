# Monogram port requirements and current evidence

Source: Telegram-FOSS-Team/Telegram-FOSS at the commit recorded by `git rev-parse HEAD`.
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

## Reproducible checks

`nix develop .#test --command ./scripts/test-xmpp.sh` creates an isolated local Prosody server with a temporary trusted TLS certificate, runs actual client connections, then stops the server. Server logs and temporary test data remain under ignored `.state/`. Unit and integration results are in `xmpp-core/target/surefire-reports/`.

`nix develop --command ./gradlew :TMessagesProj_App:assembleAfatDebug` is the Android build target, once native submodules have been built. Consult the current `.state/android-build.log` and installed-package evidence for Android build/runtime status. Do not report an APK as ready on the strength of the core tests.

Latest shared-core fixture: `.state/integration.hFFYVE/server.log`, 3 tests passed, 0 skipped/failed. Android build log: `.state/android-build.log` (55 tasks, BUILD SUCCESSFUL). Initial screen evidence: `.state/first-launch.png` (private device screenshot; do not publish). The optional NixOS helper module is exported as `nixosModules.monogramHelper`; deployment remains pending.
