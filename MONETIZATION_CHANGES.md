# Monetization and crypto removal change log

This file tracks the requested work: make client-side/XMPP-relevant features free, remove Telegram-only monetization, and remove cryptocurrency functionality. `[ ]` = todo, `[o]` = in progress, `[x]` = done. Implementation and verification are recorded separately. Tests are run only when preparing an APK release, per the user's instruction.

## Current state

- [o] Inventory existing gates, purchase routes and compiled dependencies.
- [ ] Make all relevant client-side/XMPP features free without fabricating a paid Telegram identity.
- [ ] Remove Telegram-only purchases, subscriptions, Stars, paid content, gifts, ads and their entry points/backends.
- [o] Remove cryptocurrency/wallet/TonConnect Java, JNI and packaged engine integration. Wallet backend and transaction UI removed; separate TON/Stars screens and historical schema/presentation cleanup remain.
- [ ] Remove remaining Google Play Billing dependency and API users.
- [ ] Verify the complete signed APK against these requirements before marking the goal complete.

## Baseline inspected on 2026-10-21

Source baseline: `master` at `9a2ff7618` (0.2.134 delivery). The stopped encryption-choice follow-up remains on its separate branch and is excluded from this work. The user's README edits are preserved.

Previously removed work is documented in `docs/FREE_FEATURES.md` and `docs/MONETIZATION_REMOVAL.md`: Google Wallet SDK, Stripe/native card tokenization, several purchase/navigation routes and some frontend Premium gates. These documents explicitly do not certify complete removal.

Current inspection confirms remaining Google Play Billing 8.0.0 and ten Java files importing its API; CMake still links `wallet_engine`, its generated C++ wrappers and wallet JNI objects into the native client library. Wallet/TonConnect Java classes and references from messaging, navigation, notifications and attachment UI remain. Relevant free frontend gates and server-only promotion/purchase paths still need a complete audit.

## Changes in this work branch

### Wallet backend removal (implementation in progress)

- `TMessagesProj/jni/CMakeLists.txt`: removed the Rust archive, generated C++ wrapper targets, JNI object target and all wallet links from the native app library.
- Removed the wallet-engine submodule and its `.gitmodules` entry, native `jni/wallet` bridge/adapter/journal sources and `prebuild/build_wallet_engine.sh`.
- Removed wallet Java engine, key/storage/authentication, currency/DNS, streaming/polling, TonConnect and transaction UI classes. The standalone `WalletDiamondView` remains temporarily because the separate TON/Stars purchase screen still imports it; that screen is part of the next removal pass.
- Removed `ChatAttachAlertGramLayout`, the Money attachment tab and transaction flight/swipe/receive rendering in `ChatAttachAlert`, `ChatActivity` and `ChatActionCell`.
- Removed wallet lifecycle/update hooks from `MessagesController`/`LaunchActivity`, balance checks from logout, money actions from profiles/dialogs, wallet passcode mode and settings rows, wallet notifications, transfer creation/completion helpers and currency formatting hooks. Existing locally stored wallet records are not deleted.
- Removed wallet/TonConnect deep-link implementations from `LinkManager`; old cryptocurrency scheme/username routes are consumed without executing them. Removed OAuth wallet consent/session construction. Requests requiring a wallet grant are rejected rather than silently granting partial consent.
- Removed `TL_wallet` and `TL_toncenter` schemas, wallet update types/dispatch and OAuth wallet-session serialization. Historical transfer message action records remain temporarily to allow their stored records to deserialize; retry explicitly marks these unsupported operations failed rather than resending them as normal messages.

Remaining before a release: finish removing TON/Stars/purchase screens, old transfer presentation/schema references and monetary configuration/resources; remove Billing and audit/free relevant frontend gates. This branch is an implementation worktree, not an APK ready for delivery.

### New user report tracked

A contact on 0.2.133 reports failed OMEMO 2 sends (`Invalid OMEMO2 content`) and undecryptable incoming messages. 0.2.133 already includes the prior own-bundle/prekey patch, so the screenshot alone does not identify the cause. Detailed diagnostics were requested. No identity/trust reset, plaintext retry or speculative encryption change was made.

## Verification

No tests, phone UI actions or APK deployment have been performed for this work yet. Implementation-phase source inspection is permitted; release checks will be recorded here when preparing an APK.
