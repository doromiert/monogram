# Monetization and crypto removal change log

This file tracks the requested work: make client-side/XMPP-relevant features free, remove Telegram-only monetization, and remove cryptocurrency functionality. `[ ]` = todo, `[o]` = in progress, `[x]` = done. Implementation and verification are recorded separately. Tests are run only when preparing an APK release, per the user's instruction.

## Current state

- [o] Inventory existing gates, purchase routes and compiled dependencies.
- [ ] Make all relevant client-side/XMPP features free without fabricating a paid Telegram identity.
- [ ] Remove Telegram-only purchases, subscriptions, Stars, paid content, gifts, ads and their entry points/backends.
- [o] Remove cryptocurrency/wallet/TonConnect Java, JNI and packaged engine integration. Wallet backend and transaction UI removed; separate TON/Stars screens and historical schema/presentation cleanup remain.
- [o] Remove remaining Google Play Billing dependency and API users. Source client/SDK/API users removed; actual dependency/APK absence still needs release verification. Legacy unavailable purchase hooks and monetary screens still need cleanup.
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

### Billing and checkout removal (implementation, not release-verified)

- Removed `messenger/BillingController.java` entirely, including client creation/reconnect, product/purchase queries, restore/assignment, acknowledgement/consumption, subscription management and billing intents. Removed `utils/BillingUtilities.java` (developer payloads, account identifiers, receipt/purpose persistence).
- Removed `com.android.billingclient:billing:8.0.0` from Gradle. The optional map provider now declares its already-used location dependency directly at the same version, instead of relying on a transitive purchase dependency. Manifest removal directives for Billing permissions/components remain as exclusions, not SDK class implementations.
- Removed Play API imports/types, price queries/listeners and product pricing fields from Premium, gift, giveaway and Stars source users. Existing read-only amount/price presentation uses `utils/CurrencyFormatter.java`, which has no store, transaction, account or cryptocurrency-specific implementation. Generic unknown currency labels remain renderable as stored data.
- Removed the Premium subscription purchase/upgrade/restore backend and the Stars top-up/gift/giveaway and Premium gift-code/giveaway checkout implementations (including their invoice alternatives). Removed the invoice-billing selector and Billing product-update observers/events from their source callers. Remaining legacy entry methods report unavailability/failure to their callers; their promotional screens and the rest of Stars/gift transactions still require removal. These methods are not claimed as fully deleted UI features.
- Removed `PaymentFormActivity.java` in full and its deep-link, keyboard-buy, receipt and attachment/webview launch paths. Removed the web invoice delegate and all five UI implementations. A web widget invoice request receives the existing `cancelled` protocol response without a payment-form lookup, checkout UI or transaction; other widget/browser features remain on their existing paths.
- Removed Premium promotion fetch/cache refresh and purchase/promo/feature-preview analytics RPCs, including startup/user-config triggers and their callers. Removed annual/upgrade/restore/Christmas sales-hint implementations and their UI branches from the chat list. Old promo file-reference refresh completes with unavailability instead of fetching a new promotion.
- Removed the obsolete `MonogramBillingDisabledTest`: it referenced the deleted client and SDK. No tests have been run; the release gate must inspect the actual dependency graph and signed APK, rather than testing a retired stub.

Remaining: Stars/gift/auction/paid-media/message backends and their screens, old Premium upsell/marketing UI, ad reporting/settings remnants, crypto and monetary schema/presentation/configuration remnants, plus the remaining free-client-feature audit. No APK is ready or released from this branch yet.

### Home title branding follow-up

The home header's `AppName` string was already Monogram, but `DialogsActivity` replaced the whole title with an `ImageSpan` containing the upstream `telegram_logo_2` vector wordmark. Removed that overlay so the native action bar displays the real app name. The stories header had a second use of the same wordmark: replaced it with native bold text using its existing theme color, motion/visibility and status-icon placement. Its width now follows the label instead of a fixed 90dp image slot. Launcher/app icon artwork is unchanged. Source implementation only; no APK or phone visual verification yet.

### New user report tracked

A contact on 0.2.133 reports failed OMEMO 2 sends (`Invalid OMEMO2 content`) and undecryptable incoming messages. 0.2.133 already includes the prior own-bundle/prekey patch, so the screenshot alone does not identify the cause. Detailed diagnostics were requested. No identity/trust reset, plaintext retry or speculative encryption change was made.

## Verification

Release verification for 0.2.135 is recorded below. No phone UI actions were performed.

### Stars checkout and paid-message cleanup (0.2.135 release preparation)

- Removed Stars paid-content checkout, subscription payment, price-editing and top-up implementations and their remaining entry actions. Paid invitations report unavailability.
- Removed paid-message postponement/undo and checkout gating from ordinary sending. Confirmation callbacks report zero fees (including one zero entry per forwarding destination); bot-forum and ephemeral-message handling remain intact.
- Legacy balance/catalog/history contracts return empty local data; gift/resale checkout contracts report unavailability. Broader Stars/gift/auction and promotional UI removal remains unfinished.
- Release checks and installation are recorded below after completion.

### 0.2.135 release verification

- [x] Nix arm64 APK build: `:TMessagesProj_App:assembleAfatDebug`. Fixed the final stale TON-address click handler reference found by compilation; retired wallet links have no action.
- [x] Focused release tests: `MonogramFavoriteEmojiTest` (1) and `MonogramTranslationLanguagesTest` (2), all passed.
- [x] Runtime dependency graph excludes Play Billing, Stripe and Google Wallet.
- [x] Inspected all 59,218 defined APK DEX classes: Billing API/client/utilities, Stripe/Google Wallet, wallet/TON UI, PaymentFormActivity and VideoAds implementations absent. All three native libraries are arm64; main native library has no wallet-engine/JNI markers. Historical monetary schemas and remaining Stars/gift UI are still present.
- [x] Signed APK matches the previously delivered signer; version 0.2.135, Android version code 73109. SHA-256: `195d357843037892b5461ae7238199838470251c63d901810c58f1515c959973`.
- [x] Installed via the updated wireless ADB endpoint `192.168.1.128:46869`; package manager confirms 0.2.135 / 73109. App was not opened or exercised.
- [x] Merged into master, pushed and published https://github.com/doromiert/monogram/releases/tag/v0.2.135; release asset size/SHA-256 and anonymous download verified.
- [ ] Phone runtime/visual acceptance remains unperformed.

The overall monetization removal remains in progress; this release does not certify every Stars, gift, auction, promotional screen or frontend gate has been removed.

### Ralu OMEMO2 diagnostics received after packaging

The supplied 0.2.133 trace is `Omemo2Bundle.invalid:82 → positive:67 → parse:63 → XmppOmemo2.bundle:131 → targets:105 → encrypt:209 → XmppSession.sendModern:960 → send:884 → MonogramController.handle:3587`. The failure is the signed-prekey ID parser, before bundle cryptographic verification/encryption, not a fingerprint trust rejection. The parser currently accepts only decimal IDs 1..2147483647. The rejected ID and owning device are still unknown; the trace alone does not distinguish zero, an out-of-range value, or missing/malformed input. Next step: inspect the offending bundle's `spk@id` and supported wire/native ranges before changing validation. No encryption change was included in 0.2.135.

### Legacy profile monetization cleanup (after 0.2.135; not shipped)

- Removed legacy settings Premium/Business subscription, Stars/TON and gift row allocation, click handlers and row binding; removed eager TON balance lookup. Removed subscription-grace renewal row and its subscription-management browser action. Phone/password suggestions remain.
- Removed bot/channel revenue row creation, balance queries, formatting and launch actions from profiles; ordinary group settings, members, notifications and moderation paths remain.
- Removed user/channel gift menu construction, menu/action click implementation and both info-refresh paths that could restore those actions. Birthday rows retain their data without a gift-buy icon; username QR action remains.
- Removed profile affiliate-program row, connect/share RPC action and associated view binding. Removed paid gift-overlay widget construction on profiles; optional guarded animation references/public field remain until the wider gift UI is retired.
- Source review only. No build/tests/phone actions for this follow-up, per the instruction to test at APK delivery. Existing shared collectible-gift status UI, gift libraries and other monetization screens/controllers still require removal; this does not claim all profile Premium gates are freed.

### Free profile display and collectible removal (source only)

- Profile notes retain links/entities and biographies use full rich-text rendering for everyone. Topic icon pack information is no longer gated by a Premium-feature availability flag. Self-profile emoji-status selection no longer requires a paid-account flag. Local profile-color editing displays its ordinary icon rather than a subscription lock.
- Removed the other-user Premium sales-preview click implementation and its NFT link. Removed channel collectible NFT navigation, the collectible tooltip widget/timer/animation and its remaining NFT link. Shared status-picker callback signatures remain, but collectible gift selection is rejected and no collectible status object or wear-gift sheet is created by this profile callback. Ordinary emoji status selection remains.
- Existing collectible model/deserialization and shared picker/gift classes still need broader removal. Other profile/server permission checks are untouched; no fake paid identity is assigned. No build/tests/deployment for this source pass.

### Paid chat reactions removal and free reaction picker (source only)

- Removed `StarsController.PendingPaidReactions` and its paid-reaction send/commit/cancel/undo APIs: balance reservation, pending counts, bulletin timers, retries and `messages.sendPaidReaction` request construction. Deleted `StarReactionsOverlay` and all chat attachment/lifecycle/touch launch paths.
- Chat selection no longer opens the paid-reaction sheet or overlay; paid reaction metadata is not exposed as an ordinary selectable emoji. Removed chat prefetch of Stars balance/paid privacy. Received reaction updates use received data without merging a local paid pending count.
- Removed paid-reaction privacy lookup, update handling/cache and sheet privacy RPC. The sheet's chat-reaction send branch is deleted. Its remaining live-stream donation UI and shared particles/slider still exist and require removal/extraction; this is not complete live monetization removal. Generated historical schemas/model helpers remain.
- Delivery-report deduplication now owns a private `MessagesController.DeliveryMessageId` value class, preserving its dialog/message equality without depending on the deleted Stars key.
- Removed saved-message tag purchase prompts and the reaction picker's subscription filters/lock construction/upsell button. Local emoji/effect/tag candidates, expansion and recent/default choices no longer require a paid identity. Existing document metadata, chat reaction permissions and Lite Mode controls remain. Nullable shared lock-view fields remain for existing animation callers.
- Candidate filtering excludes paid-star metadata and deduplicates through an iterator. This also avoids the previous remove-after-decrement index error when duplicates were encountered. Ordinary Unicode/custom emoji reactions use their existing send route.
- Source inspection confirms no remaining paid-reaction send/privacy RPC constructors, pending-state/overlay references or Stars delivery-key callers. No build/tests/phone actions were performed; compilation and normal DM/MUC reaction/tag/animation behavior must be checked at the next APK delivery.

### Live donation and highlighting removal (source only)

- Deleted `Stories/PaidReactionButton` and its effects view, plus live-story donation creation/click/long-click, layout and callbacks. Removed composer highlighting menus, suggestion action and hint. Live comments report no Stars price and no paid amount is passed into their send call.
- Removed live donation state, pending/undo bulletin timers, donor accumulation/ranking and the five-second `getGroupCallStars` poll, including attach/detach/setup scheduling. Removed the balance-too-low purchase/retry handler. The live comment send API no longer accepts a payment amount or sets `allow_paid_stars`; ordinary send/error/update processing remains. Service-side restrictions still apply and are not bypassed.
- Deleted `StarsReactionsSheet` in full, including its remaining balance cloud, donation purchase/reaction UI, animated payment path and sender selector. Removed `HighlightMessageSheet.open` and its tier-value UI. Read-only historical tier parsing/render metadata remains temporarily for existing consumers.
- Extracted the original generic particle renderer into `Components/ReactionParticles` and migrated its emoji/reaction/media consumers, preserving local/Lite Mode effects without retaining the payment sheet. The former shared Stars slider has one remaining auction caller; extracted it as `Gifts/AuctionBidSlider` so the payment sheet can be deleted. The auction slider and auction transaction/UI remain a pending removal task, not a free feature.
- Source review only: no remaining references to the deleted sheet/button, live donation API/poll/undo callbacks or paid highlighting launcher. No tests/build/phone actions; next APK delivery must verify compilation, normal emoji/reaction animation, story composer layout and ordinary live-comment behavior.

### Gift auction engine and UI removal (source only)

- Deleted `GiftAuctionController`: auction state subscriptions/polling/listeners, bid submission/payment form requests, active/acquired auction queries and upgrade-attribute fetching. Removed its account/base-fragment accessors and update dispatch.
- Deleted auction bid/join/wearing/acquired/active screens, the auction-only slider extracted in the preceding pass, and `ActiveGiftAuctionsHintCell`. Removed home promotion allocation/update/theme hooks.
- Auction/preview deep-link routes are consumed without catalogue requests, bidding screens or external navigation. Removed auction sender subscriptions/countdown/bid launch/info controls from `SendGiftSheet`, and filtered auction entries out of the remaining gift catalogue's tabs/items. Remaining generic gift sending/purchase screens are a separate unfinished removal task.
- Removed the remaining auction-controller attribute fetches from the legacy gift sheet. Historical rarity text can still be shown locally without a network lookup; wider gift crafting/collectible UI is still pending removal. Historical generated auction models/configuration/resources remain, so this is not a complete schema/resource purge.
- Source inspection confirms no references to the deleted auction classes/accessors or auction RPC constructors outside generated schemas. No build/tests/phone actions; verify compilation, home/profile navigation and deep-link handling at the next APK delivery.

### Messaging gift controls and sales-link cleanup (source only)

- Removed the composer gift button, its purchase-options loading/checkout launcher, birthday purchase tooltip/timer and gift-related scheduled-button displacement. Removed their external chat draw/update hooks. Birthday contact/profile data is not removed.
- Removed channel gift action, its fully-visible promotional hint/timer and old-gift-message “send another gift” menu action. The channel button layout now has matching four-item IDs, icon array and ordering, retaining search/direct/info controls.
- Removed legacy home Stars low-balance/keep-subscription and Premium grace banners, and birthday-to-gift-purchase prompts. Other account/security hints and contact birthday data remain on their existing paths.
- Consumed retired Stars/Premium/Business/send-gift settings links without sales screens/catalogue requests, and `tg:stars`/`tg:send_gift` routes without recipient resolution or gift launch. Other generic gift resale/collectible links and their screens are still pending removal.
- Remaining `GiftSheet` and `SendGiftSheet` classes and launches within other monetary screens are not claimed removed; shared gift artwork is still used by historical-message/other UI. Further passes must separate generic local styling from those purchase screens before deleting them.
- Source-only review; no tests/build/device actions. Next APK checks must cover ordinary composer buttons, scheduled-send position, channel search/direct controls, context menus, home hints and retired links.

### Paid-message fee UI and revenue cleanup (source only)

- `MessagesController.getSendPaidMessagesStars` now provides zero for legacy callers; no paid-account identity/permission is fabricated. Removed paid-fee exception update queuing/dispatch and the Stars handler, fee revenue lookup and refund/charge exception RPCs.
- Removed chat fee menu controls/header text, monetary top-panel/checkbox balance dialogs, balance prefetch and paid-price send-error popup. Normal verification/report/top-panel paths remain. Removed Premium-required contact overlay/greeting purchase actions and their premium-contact requery hooks; ordinary greetings/intro rendering stays on its existing path.
- Composer price API is zero; removed price-dependent send/edit-button changes and “type/suggest for Stars” hints. Legacy update method still updates ordinary live-comment length feedback using the configured maximum without a paid tier. Removed fee suppression of scheduled-send hints and scheduling availability. Existing chat/admin/send permissions still apply.
- Generic checkbox alert now has a single ordinary API and no monetary balance header variant. Existing callers retain the original checkbox behavior.
- Remaining monetary suggested-post offers/balance sheet, shared send-button price rendering and generic gift/sales controllers still need removal. This pass does not claim all monetary message schemas/send parameters are gone.
- Source-only inspection; no build/tests/phone actions. At next APK delivery verify compilation, composer send/edit/schedule, ordinary greetings, top-panel reports and checkbox alerts.

### Suggested-post Stars/TON sales removal (source only)

- Deleted `Stars/MessageSuggestionOfferSheet`, including currency tabs, balance cloud/top-up link, commission/price entry and sale scheduling UI. Removed its composer/reply-panel/context-menu/attachment launch paths and send-with-offer action. Sale-specific date/decline dialogs were deleted; ordinary schedule controls remain.
- Removed `MessagesController` add/approve/reject suggested-post transaction backend, in-flight state and `messages.toggleSuggestedPostApproval` request construction. Removed sale accept/decline/edit keyboard generation, handlers and progress rendering. Ordinary bot and open-thread buttons remain.
- Removed balance checks that redirected photo, attachment, text/media editing into the sale sheet. Text editing uses the existing edit-message path instead of resubmitting a sale offer. Removed obsolete balance helper from StarsController.
- Chat send accessors no longer return suggested-sale parameters, media composing uses that accessor, and sale metadata from old drafts is not reopened as an editable offer. Existing draft message/reply content is preserved.
- Historical suggestion model/serialization and some read-only presentation remain for the broader schema/presentation cleanup. Date formatting for existing history moved to the existing model helper; no sales screen is retained just to format a timestamp. Remaining gifts/Stars controllers and shared monetary rendering are not claimed complete.
- Source-only review confirms no references to the deleted sheet, offer dialogs, approval request backend or UI launch APIs. No build/tests/phone actions; next APK delivery must verify compilation, normal edit/media send, draft restoration, bot buttons, forwarding and scheduling.

### Gift catalogue and purchase-sheet deletion (source only)

- Deleted `Gifts/GiftSheet` as a catalogue/purchase screen and deleted `Gifts/SendGiftSheet` in full: catalogue/tier loading, purchase tabs/header, recipient pricing/message editor, purchase button and checkout callbacks no longer live in these screens.
- Removed the remaining catalogue launch actions from Stars transaction tables, gift detail tables and recipient selectors. Table rows retain their ordinary profile navigation and have no secondary send-gift label/action. Removed the profile gift-container purchase CTA; its owned-collection edit action remains temporarily until the larger collectible feature is removed. Native call/contact selection remains.
- Moved the existing static gift presentation helpers into `Gifts/GiftVisuals` and migrated their consumers. This separates the removed screens from card/ribbon rendering used by old gift history and profile-color previews. Gift-specific cell/price/lock presentation and sticker artwork-loading helpers still exist here for other remaining collectible screens; they are not described as a completed monetization purge. The utility has no catalogue or purchase action implementation.
- Remaining resale/collectible screens, Stars controller/catalogue/data operations and old recipient-selector monetary modes still require deletion. No changes were made to Monogram custom sticker/emoji pack importing or ordinary media sending.
- Source review: no remaining `GiftSheet`/`SendGiftSheet` class references or constructor launches. No build/tests/phone actions. At next APK delivery verify compilation, historical rows/profile navigation, native call selection and preserved card/ribbon presentation.

### Collectible purchase offers and resale price operations (source only)

- Deleted `Stars/GiftOfferSheet`: offer price/currency entry, payment submission and purchase-offer acceptance. Removed its gift-menu launch and the accept/decline keyboard creation/callbacks in historical action cells, including `sendStarGiftOffer`/`resolveStarGiftOffer` request constructors. Ordinary sharing-offer/thread/bot actions are not removed.
- Unique-collectible/NFT deep links no longer query `getUniqueStarGift` or construct a native market/details sheet. The route ends its loading state without initiating a purchase flow.
- Removed gift listing/unlisting/repricing implementations, their `updateStarGiftPrice` requests, resale action handlers and Stars/TON resale price sheets. Removed constructor callback dependencies from the remaining gift top view/preview; old price metadata is inert and no longer clickable to edit. The existing non-sale share action replaces the old sell/unlist action; gift sharing/export/transfer and broader collectible screen removal remain pending.
- Historical gift offer/price metadata and remaining `ResaleGiftsFragment`, collectible presentation/crafting and controller data operations remain. This is not described as complete NFT/crypto removal.
- Source review confirms no references to the deleted offer screen, offer-resolution request constructors, repricing handlers or price-sheet APIs. No tests/build/phone actions; verify compilation, ordinary action-cell controls and retired-link handling at next APK delivery.

### Collectible transfer, blockchain export and conversion removal (source only)

- Removed collectible transfer recipient selection/confirmation, paid transfer/payment-form submission/retries, gift withdrawal URL requests and the complete TON export password/SRP flow. Removed transfer eligibility/timeouts and menu/preview/collection actions. Ordinary XMPP media/file transfer code was not changed.
- Gift top-view actions now have two matching slots/callbacks instead of retaining a dead third transfer control. Updated its remaining preview caller and wear/share indices. Those remaining collectible wear/share features are still part of the unfinished wider cleanup.
- Removed gift-to-Stars conversion request, confirmation/progress/retry/navigation and interactive sell links. Existing gift titles/attributes remain without conversion sales descriptions or links.
- Removed explicit blockchain explorer banners, wallet-address copy rows and the external Fragment marketplace control from the remaining gift sheet. Historical raw owner/address fields may still deserialize; they no longer construct these controls.
- Remaining resale browsing/buy UI, crafting/upgrade, collectible wear/share and controller/schema/data code still require removal. Source review confirms no references to deleted transfer/export/conversion APIs or their RPC constructors in the edited sheet.
- No build/tests/phone actions. At next APK delivery verify compilation, preview action layout and historical gift/profile rendering, plus ordinary media/file transfers as regression checks.
