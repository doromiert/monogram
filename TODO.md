# Monogram TODO

Legend: `[ ]` todo · `[o]` work in progress · `[x]` verified done.

Work only in `~/Projects/monogram`, using Git branches. Do not create separate source checkouts in other project folders.

The goal is a Monogram Android client based on current official Telegram source, using ordinary XMPP/Jabber and interoperable OMEMO, with an optional server helper for features that need it. Nix is the development/build environment. Planned public home: https://monogram.neg-zero.com. Helper host: 192.168.1.24.

## Current priority: official Telegram base

- [x] Reapply the Monogram changes onto `DrKLO/Telegram` 13.0.2, commit `f32ffeec763f0bdbf4bec25b52d654f77bff733d` (2026-10-13). The Git rebase is complete; build/runtime validation is tracked below.
- [x] Update the pinned Nix environment for Java 21, SDK/build tools 35 and 36, NDK 27.2, CMake 3.22.1 and Rust 1.96.1.
- [x] Build all current-base arm64 native libraries from source; remove bundled native archives/generated headers from Git tracking.
- [o] Restore the FOSS replacements and remove proprietary runtime SDKs from the new base.
- [o] Verify XMPP onboarding and request dispatch; prevent Telegram networking in startup, background work and unsupported actions.
- [x] Compile the current-base Android Java sources with the XMPP adapter and Jabber account routes (138 tasks; BUILD SUCCESSFUL).
- [o] Build the rebased APK and verify the current Telegram UI, Monogram branding and the security button on Android.
- [ ] Update the installed package while preserving its signing key, accounts, local messages and OMEMO identities.

## Messaging and existing conversations

- [ ] Restore previous conversations using the roster and server archive discovery.
- [ ] Implement paged MAM history with correct timestamps, stable message IDs, deduplication and cache handling.
- [ ] Represent old encrypted messages that this new device cannot decrypt accurately; do not pretend their plaintext is recoverable.
- [ ] Handle incoming/outgoing carbons, multiple devices, offline messages and reconnects in the Android storage/UI adapter.
- [ ] Implement delivery receipts and accurate pending/sent/delivered/failed states, with useful errors and retry behavior.
- [ ] Implement roster management, subscription requests, presence and contact display names.
- [ ] Port replies, editing, reactions and other applicable messaging actions using standard XMPP extensions.
- [ ] Implement standard group chats and bookmarks, with OMEMO where supported.
- [ ] Complete account management, request cancellation, logout isolation and background notification behavior.

## OMEMO

- [ ] Verify actual Android encrypted exchange with an independent client such as Monocles, including this user's reported send failure. No successful send to the reported friend has been verified.
- [ ] Verify persistent identities across a real process restart and app update.
- [ ] Verify new/changed/distrusted identities and multiple recipient/own devices; never silently downgrade an encrypted send to plaintext.
- [ ] Improve encryption/trust indicators and device verification on the rebased UI.

## Images and other attachments

- [ ] Replace the Telegram upload pipeline with XMPP HTTP File Upload (XEP-0363).
- [ ] Implement interoperable encrypted file upload/download for OMEMO conversations.
- [ ] Connect photo/image selection, sending, receiving, previews and opening to that pipeline.
- [ ] Cover documents, video and voice messages using the same transport where applicable.
- [ ] Surface server limits, missing upload support, progress, cancellation and retry clearly.

## Optional helper and feature compatibility

- [ ] Inventory remaining Telegram-specific features: map each to standard XMPP, an optional helper capability, or an explicitly unavailable UI action.
- [ ] Support the helper on the separate host without making ordinary messaging depend on it or requiring control of another Jabber provider's server.
- [ ] Wire capability discovery and the helper features into Android.
- [ ] Harden scheduled-message persistence, restart/retry semantics, ownership and limits; test encrypted scheduled delivery.
- [ ] Package and verify deployment for 192.168.1.24 using Nix.
- [ ] Address remaining applicable features such as calls through standard XMPP support, and remove misleading Telegram-only service controls.

## Release verification

- [ ] Finish branding, launcher variants, app links, attribution and licensing on the current base.
- [ ] Verify plain Jabber operation without the helper and graceful handling of unsupported extensions.
- [ ] Run unit tests, real-server integration tests and independent-client interoperability checks covering the actual supported paths.
- [ ] Verify Android login, history, text/OMEMO messages, groups, attachments, reconnects and notifications.
- [ ] Produce a reviewable APK and current build/deployment instructions for https://monogram.neg-zero.com.
- [ ] Audit the full original goal against current runtime/build evidence before marking it complete.

## Repository

- [x] Publish the current-base Monogram source and TODO to https://github.com/doromiert/monogram (`master` and `monogram-current-base`).
- [x] Remove the extra comparison checkouts; retain upstream commits as branches in this repository.
- [x] Publish the preserved prototype branch as `prototype/telegram-foss`.

## Completed prototype milestones

These describe the earlier Telegram-FOSS 10.14.3 prototype; they do not prove the current-base port works.

- [x] Create the Monogram checkout and separate Android package ID.
- [x] Pin a Nix development environment and build the prototype's arm64 native dependencies/APK.
- [x] Add a shared XMPP transport with verified TLS, persistent OMEMO key storage and fingerprint decisions.
- [x] Pass local Prosody tests for plain messages, OMEMO round trips, offline delivery and the scheduling helper.
- [x] Add Jabber sign-in, direct-message/storage adapters and initial encryption controls to the prototype UI.
- [x] Install and launch the initial APK through wireless ADB at `192.168.1.128:39127`.
- [x] Build and install 0.1.1 with the lock touch-target fix and actionable OMEMO send errors; tapping the lock reaches Conversation security on the phone.
- [x] Add the narrow OMEMO AES-GCM provider fix; pass its regression test and the existing integration tests (4 tests, no failures/skips).
- [x] Download and inspect the current official Android source linked by https://telegram.org/apps#source-code.
- [x] Save the prototype as local checkpoint commit `d46543df` before starting the rebase.
