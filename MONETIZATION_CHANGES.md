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

Baseline inspection confirmed Google Play Billing 8.0.0 and ten Java files importing its API; CMake linked `wallet_engine`, its generated C++ wrappers and wallet JNI objects into the native client library. Wallet/TonConnect Java classes and references from messaging, navigation, notifications and attachment UI remain. Relevant free frontend gates and server-only promotion/purchase paths still need a complete audit.

## Changes in this work branch

### Wallet backend removal (implementation in progress)

- `TMessagesProj/jni/CMakeLists.txt`: removed the Rust archive, generated C++ wrapper targets, JNI object target and all wallet links from the native app library.
- Removed the wallet-engine submodule and its `.gitmodules` entry, native `jni/wallet` bridge/adapter/journal sources and `prebuild/build_wallet_engine.sh`.
- Removed wallet Java engine, key/storage/authentication, currency/DNS, streaming/polling, TonConnect and transaction UI classes. The remaining standalone `WalletDiamondView` and its separate TON purchase screen were subsequently removed in the next pass below.
- Removed `ChatAttachAlertGramLayout`, the Money attachment tab and transaction flight/swipe/receive rendering in `ChatAttachAlert`, `ChatActivity` and `ChatActionCell`.
- Removed wallet lifecycle/update hooks from `MessagesController`/`LaunchActivity`, balance checks from logout, money actions from profiles/dialogs, wallet passcode mode and settings rows, wallet notifications, transfer creation/completion helpers and currency formatting hooks. Existing locally stored wallet records are not deleted.
- Removed wallet/TonConnect deep-link implementations from `LinkManager`; old cryptocurrency scheme/username routes are consumed without executing them. Removed OAuth wallet consent/session construction. Requests requiring a wallet grant are rejected rather than silently granting partial consent.
- Removed `TL_wallet` and `TL_toncenter` schemas, wallet update types/dispatch and OAuth wallet-session serialization. Historical transfer message action records remain temporarily to allow their stored records to deserialize; retry explicitly marks these unsupported operations failed rather than resending them as normal messages.

Remaining before a release: finish removing TON/Stars/purchase screens, old transfer presentation/schema references and monetary configuration/resources; remove Billing and audit/free relevant frontend gates. This branch is an implementation worktree, not an APK ready for delivery.

### TON screens and free frontend pass (implementation, not release-verified)

- Removed `TON/TONIntroActivity`, including its crypto balance/transactions, top-up/withdrawal controls, TON purchase sheet and observers, plus the final `Wallet/WalletDiamondView` class.
- Removed TON screen launch actions from profile/settings/deep links and TON top-up sheet actions from gift/offer screens. `StarsController.canUseTon()` now rejects TON; the broader Stars controller, monetary models and purchase flows still await removal, so this is not described as complete crypto removal.
- Removed `StakedDiceSheet` and chat stake prompting. Ordinary dice still use their existing information/send behavior. Removed the send-error handler that refreshed betting game hashes or offered a TON top-up/retry.
- `ChatActivity`: translation top-bar visibility and click handling now use the configured translation controller directly, without a Telegram Premium upsell. Outgoing local sticker effects use the existing Lite Mode/device-performance gates without a subscription gate.
- `EmojiView` and `MediaDataController`: free XMPP animated emoji participate consistently in pack sizing, expansion, visible-item binding and keyword search; imported Premium sticker recents are available locally. Existing emoji identity/import/send validation is preserved.

### Advertising backend removal (implementation, not release-verified)

- Removed `messenger/video/VideoAds` in full: ad loading/cache, scheduling/timers, playback popups, view/click reporting and its Premium upsell. `PhotoViewer` no longer constructs or updates it, pauses/resumes playback for an ad popup, or manages its lifecycle. Ordinary playback remains on its original path.
- Removed `MessagesController.getSponsoredMessages`, its cache/info model and the chat method that requested/inserted sponsored messages. Removed its load event subscription/callback. This eliminates channel/bot ad fetching and message injection rather than only hiding the result.
- Removed the bot top-bar ad view and all its action/upsell/report callbacks from `ChatActivity`; deleted `BotAdView` and the now-unused sponsored-cache removal hooks.
- Removed chat ad click/impression RPCs and their call sites; any historical ad dismissal is local and has no subscription gate.
- Removed sponsored-peer search queries and impression reporting from `DialogsSearchAdapter`, including query/request-ID state. Ordinary local/global search stays on its existing path.
- Historical sponsored-message/search-peer display/report handling, ad-related settings/screens and generated schemas remain for a later removal pass. These backend changes do not certify complete ad removal.

### New user report tracked

A contact on 0.2.133 reports failed OMEMO 2 sends (`Invalid OMEMO2 content`) and undecryptable incoming messages. 0.2.133 already includes the prior own-bundle/prekey patch, so the screenshot alone does not identify the cause. Detailed diagnostics were requested. No identity/trust reset, plaintext retry or speculative encryption change was made.

## Verification

No tests, phone UI actions or APK deployment have been performed for this work yet. Implementation-phase source inspection is permitted; release checks will be recorded here when preparing an APK.
