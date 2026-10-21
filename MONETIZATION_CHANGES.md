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

### OMEMO2 signed-prekey diagnostic follow-up

Verified the signed-prekey ID range against XEP-0384 §5.3.2: 1..2147483647. The supplied trace does not establish which value was rejected. `Omemo2Bundle.parse` now reports a specific signed-prekey-ID error with missing/malformed status or a bounded decimal value, preserving the cause and all existing validation. No key material or arbitrary XML enters the new error. This is diagnostic instrumentation, not a delivery fix; no identity, trust or encryption-mode changes. Source-only: tests/build/install deferred until APK delivery, per user instruction.

### Gift upgrade, crafting and remaining sheet purchase removal

Removed `StarGiftSheet` upgrade/prepaid-upgrade entry points, next-upgrade carousel actions, payment-form queries, upgrade transactions and balance retries, one-second price refresh runnable, price schedule sheet, upgrade marketing layout and sender checkbox. Removed the unused upgrade learn-more entry point. Removed crafting requests, selection subscriptions, controls, nested crafting UI/animations, help layout and crafting dismissal guards. Deleted the now-unreferenced post-upgrade update helper. Updated the shared `TopView` constructor and its preview caller after deleting the crafting callback.

Removed resale buy controls and checkout handlers plus paid original-description removal, retaining a read-only details row. No payment-form, send-Stars-form, upgrade or craft request construction remains in this sheet. Removed imports made unused by these deletions. Source inspection covered removed method references, both shared constructor callers, class brace boundaries and `git diff --check`; no tests, build or phone interaction, per the source-work instruction.

Remaining scope: gift history/display, collectible/profile management and market browser code still exists elsewhere; shared legacy page drawing retains old page slots used by previews. This is not a complete gift-schema/assets purge or a claim that all monetization has been removed. These follow-up changes are unshipped.

### Gift marketplace and preview removal

Deleted `ResaleGiftsFragment` (market browsing/filtering, purchase and crafting selection UI), `GiftMessageBottomSheet` (purchase message/privacy composition) and the now-unreferenced `StarGiftPreviewSheet`. Removed the shared preview attribute setter and `StarsController` preview query/cache. Removed gift value-stat queries/market navigation, resale confirmation/currency/balance UI and payment state from `StarGiftSheet`. Removed all remaining buy/resale form controller shims and form-price helper after removing their callers.

In `PeerColorActivity`, removed marketplace tabs/list pagination, foreign-gift binding/selection, purchase state/actions and the empty-state marketplace link. Existing owned-gift presentation remains for a later collectible cleanup. Removed Premium purchase prompts, locked apply button and Premium-only apply/unsaved-change restrictions; ordinary local name/profile color handling remains. The remaining single owned-list tab no longer loads the gift catalogue or opens market browsing.

Source-only verification: repository Java/Kotlin reference search found no surviving references to the deleted screens/helpers; inspected edited class boundaries and `git diff --check`. No tests, build, APK or phone interaction this pass. Remaining scope includes owned collectible/profile management, gift/status/theme presentation, Stars/Premium/backend/schema/assets elsewhere. Full monetization completion remains unproven.

### Profile color editor: remove owned collectible management

Removed the remaining gift-list construction/catalogue loading, owned gift rows and grid spacing, loading/empty states, pinned gift tabs, gift selection, collectible selection state, and gift/Premium event observers from `PeerColorActivity`. The editor now always projects and saves through `MonogramUserColors` with the existing account-generation/owner validation. Removed the legacy Telegram `updateColor`/collectible-status apply path. Ordinary emoji selection is retained and rejects gift choices. Change detection compares the initially projected color/emoji values instead of collectible identity. Shared static drawing helpers/GiftCell/Tabs remain because other screens still reference them; they are not evidence of complete collectible removal.

During source inspection, found and corrected an unshipped regression in the preceding market-cleanup commit: deletion of a neighboring gift case had also removed ordinary `onCreateViewHolder` factories. Restored info, color picker, emoji icon, reset-text and button-spacer construction from the earlier source, excluding gift-only cases. Source inspection now checks each of these factories explicitly, edited class boundaries, remaining gift/backend references and diff whitespace. No runtime tests, build, APK or phone interaction; final package validation remains outstanding.

### Composer and emoji/sticker subscription gates

Removed the rich-draft send conversion/upgrade gate, rich-draft send-button subscription lock, obsolete Premium/gift notifications and caption-upgrade promotion from `ChatActivityEnterView`. Renamed the shared animated-emoji send validation to `checkAnimatedEmojiAvailable` and updated its composer/attachment callers. It retains the existing asset-import requirement for nonfree/unknown documents but no longer treats a Telegram subscription as availability or opens a purchase screen. The composer unlock callback opens Monogram pack settings.

Removed emoji-pack upgrade button construction and blocked-pack filtering from `StickersAlert`. Removed subscription-gated reaction long-press behavior, message-effect locks, pack header/install locks and default-reaction section gating from `SelectAnimatedEmojiDialog`; deleted the lock view factory/state/drawing. In `EmojiView`, removed the subscription restrictions on status selection, custom-emoji sending, sticker selection, animated categories, pack expansion and installed-pack layout; installed packs no longer split into paid/free subgroups. Removed locked outlines and paid-region filtering of favorites/recents. Emoji suggestions now follow the existing suggestion setting rather than a subscription flag overriding it.

Deleted both `MessagesController.filterPremiumStickers` implementations and migrated all remaining pack-list/search/settings callers so they no longer discard paid-marked sticker/emoji assets. Source inspection covered surviving call sites, removed API/lock references, class boundaries and whitespace. No tests, build or phone interaction. Remaining scope includes obsolete header/upgrade widget code, media/editor-specific upsells, catalogue/server-only paths, other Premium/Stars/collectible APIs, schemas and assets. These source changes do not establish complete monetization removal or runtime verification.

### Media editing, captions and remaining viewer ad UI

Made the larger configured caption limit independent of Telegram subscription flags. Replaced the shared caption-upgrade bulletin with a plain maximum-length message (`MonogramCaptionLimit`), removed the clickable purchase callback, migrated both callers, and removed early upsell warnings and their flag. Actual overflow actions in attachment/photo/caption editors now show the neutral limit rather than advertising an upgrade; media is not closed to open checkout.

Replaced the purchase-sheet/fake-fragment emoji callbacks in EditTextEmoji, PhotoViewerCaptionEnterView, PaintView and LPhotoPaintView with Monogram pack settings. Removed story-editor subscription checks on reaction widgets and links while retaining the existing hard widget limits. Removed attachment Premium badges and the Telegram checklist entry/creation path (no XMPP send adapter found); inactive checklist lifecycle references remain for later deletion. Removed PhotoViewer's sponsored menu, sponsored caption construction, ad-button factory/fields and ad-button insertion, including the remaining Premium remove-ads actions. Ordinary media menu/caption handling is retained.

Source-only inspection: edited class boundaries, deleted callback/ad/flag references, all shared caption-bulletin callers and diff whitespace. No tests, compilation, APK or phone interaction. Changes are unshipped and require final package verification. Remaining monetization scope includes obsolete header/upgrade widgets, other subscription/limit gates, Telegram gift/Stars/Business/channel APIs, schemas and resources; goal remains active.

### Remove obsolete emoji upgrade widgets and free shared client limits

Deleted EmojiView's obsolete unlock row/button class, row-position map, constructor/binding cases, header Premium button/lock and its animation state. Add/remove-pack controls retain their existing transitions. Deleted the now-unreferenced unlock delegate and all composer/editor implementations. Removed the equivalent upgrade overlay and header lock animation from SelectAnimatedEmojiDialog while retaining its ordinary add/installed button and search highlighting; simplified internal setter signatures and migrated callers.

MessagesController now returns the larger configured message/bio limits and the existing 32-reaction client limit regardless of subscription. Folder lock refresh always clears locks, and subscription changes no longer force the default folder to the front. Rich-editor availability/permission no longer depend on Telegram configuration; full-quality story editing follows the user's setting. Removed obsolete auction-update timestamp helpers after verifying they have no callers.

MediaDataController emoji suggestions no longer filter by paid/free metadata or account subscription. Removed the forcePremium argument from suggestion/fill APIs and migrated all Java declarations/forwarders/callers, including the suggestion strip and story emoji sheet; no Kotlin callers found. Existing topic-icon, matching, asset and result-count handling remains.

Source inspection covered removed references, updated setter/suggestion signatures and callers, edited class boundaries and diff whitespace. No tests, build, APK or phone interaction. Broader server-only Premium/Stars/Business/collectible screens, gates elsewhere, schemas and assets remain; this is source progress, not a full completion claim.

### Folder settings, avatar gradients and shared-wallpaper purchase option

Removed subscription checks and upgrade prompts from FiltersSetupActivity/FilterCreateActivity tag colors, previews, no-color labels and edit actions. Folder settings always offer creation; removed the tier-limited create action, default-folder reset timer/bulletin and locked-filter edit redirects. Tag visibility now persists directly through MessagesController.setFolderTags instead of sending Telegram toggleDialogFilterTags and issuing a follow-up reload. Deleted the obsolete color-loading bookkeeping. The remaining Telegram chatlist export cap uses the larger configured value; chatlist export itself is still a backend-only path requiring removal.

Removed the avatar constructor's gradient/custom-gradient save gate, locked save-button text, lock selector drawing/state and per-gradient subscription badges. Existing gradients and custom-color editing remain; renamed the extra palette and removed unused paid-gradient metadata. Removed ThemePreviewActivity's Telegram-only shared-wallpaper button and purchase check, simplified private apply callbacks, and fixed the legacy wallpaper representation to local-only (forBoth=false). Existing local wallpaper persistence remains.

Source inspection covered removed methods/fields/callers, shared palette references, edited class boundaries and diff whitespace. No tests, build, APK or phone interaction. Other folder/home subscription UI, rich-editor widget code, Telegram chatlist sharing and remaining Premium/Business/Stars/collectible paths remain in scope. No full-completion claim.

### Rich-editor controls, forwarding and checklist gates

Removed subscription locks from RichEditor and ChatAttachAlertRichLayout sends/previews, including the forced plain-text path, conversion/subscribe sheet, obsolete permission helper and subscription observers. Upload/content/size validation, scheduling and ordinary structured-message construction remain. Removed toolbar paid-button registries/flags/setters and updated the internal block/format button APIs/callers. Replaced heading/pullquote/footer badge wrappers with their normal icon resources and deleted RequiresPremiumDrawable; existing add/format controls, math, table, lists and icon transitions remain.

Removed the rich-article subscription restriction on hiding forwarded sender names in MessagePreviewView. Removed the paid rich-checkbox permission checks in ChatActivity/RichMessageLayout while retaining the delegate ownership/editability/translation checks. Deleted MessagesController.richEditorAllowed after migrating all remaining callers; richEditorAvailable remains the frontend availability helper.

Source inspection: repository search found no references to deleted gate/drawable/conversion APIs; reviewed updated button helper callers, edited class boundaries and diff whitespace. No tests, compilation, APK or phone interaction. These changes are unshipped. Other Premium purchase screens, Business/Stars/gift paths, limits elsewhere and unused resources/schemas remain in scope; full removal is not complete.

### Home/search speed promotions, folder dragging and reaction picker

Removed SearchViewPager's sponsored-options menu and download-speed upgrade action, visibility logic and animation. Deleted the unused SearchAdsInfoBottomSheet after checking Java/XML references. Removed DialogsActivity's speed-upgrade icon, public search action/getter consumers, animation state and premium-promo video prefetch; ordinary download actions and search action-mode operations remain. PremiumPreviewFragment.applyNewSpan is still referenced for a generic New label and needs extraction before that screen can be deleted.

Removed FilterTabsView's subscription-based default-tab drag restrictions and snap-back runnable, plus the matching home upgrade bulletin callback. Dragging remains restricted to edit mode. ReactionsDoubleTapManageActivity now presents the stock full-picker row independent of subscription; removed its old paid/basic layout split and subscription observers. Standard reactions remain available; custom document reactions on an XMPP account show the existing availability warning instead of persisting an unsupported selection. CustomEmojiReactionsWindow retains the same XMPP availability guard and loses its separate paid reaction prompt. StickersActivity's remaining legacy unlock callback opens native Monogram pack settings without a purchase route; the underlying old cell widget remains for later cleanup.

Source inspection covered removed API/field/callback references, Java/XML references for the deleted ad screen, edited class boundaries, row count/type paths and whitespace. No tests, compilation, APK or phone interaction. Changes are source-only/unshipped. Other Premium/Business/Stars/gift/ad UI and backend/schema/resource cleanup remain; the full objective is not complete.

### Sticker pack cells, emoji pack sheet and long-press preview

Removed shared StickerSetCell Premium button construction, locked/restore states, click hooks and factory filtering. Its factory now chooses Add/Remove from installation state; migrated StickersActivity after deleting the obsolete button field. FeaturedStickerSetCell2 no longer constructs an unlock button or branches on subscription/paid-pack metadata; retained the add/remove transitions and explicitly animates both scale axes.

Removed EmojiPacksAlert's footer/header upgrade buttons, purchase-launch method/cooldown, paid description row and row-offset handling, and per-header paid-document scan/flag. Headers and footer retain their install/remove flows and loading behavior. Copy/send/status capability no longer checks a Telegram subscription, while chat send permission and animated-emoji type checks remain. Removed ContentPreviewViewer's paid-sticker interception and unlock overlay lifecycle, then deleted the now-unreferenced UnlockPremiumView. Premium-marked artwork/effect loading is retained as a free rendering feature.

Source inspection covered removed Java/XML references, pack header signature/caller and description-row indexing, ordinary button state/animations, edited class boundaries and whitespace. No tests, build, APK or phone interaction. Source changes remain unshipped. Other purchase screens, server-only Business/Stars/gifts/ads, limits and resource/schema cleanup remain in scope; full completion is not established.

### Ad reports, revenue-sharing UI and chat-menu promotions

Removed all three sponsored-report entry points and the sponsored request/response branch from ReportBottomSheet. Simplified its private constructor and option/page handling to ordinary message/story/ephemeral reports; removed ad-only listener callbacks, report options, explanatory footer and Premium-required/ad-expired handling. Ordinary report construction and selection/comment pages remain.

Removed chat context-menu ad hide/report/revenue options, speed-upgrade option and its Premium-video prefetch. Removed the revenue-info callback and sponsored-label touch handler from ChatMessageCell/ChatActivity. Removed PremiumPreviewFragment's sponsored-message toggle request, ad-setting rows and revenue-info link, preserving its unrelated feature routing. Deleted RevenueSharingAdsInfoBottomSheet after removing all callers. Historical sponsored rendering/data and the local legacy-message dismissal hook remain for later cleanup; this is not a claim that the entire ad schema/rendering layer is gone.

Source-only inspection covers removed Java/XML references, ordinary ReportBottomSheet constructor/caller, option-page branches, edited class boundaries and whitespace. No tests, compilation, APK or phone interaction. Other Premium/Business/Stars/gift/backend/resource/schema cleanup remains active; full completion is unproven.

### Collectible usernames and phone numbers / Fragment

Removed collectible-username purchase-info requests and the collectible-phone detail action from both profile implementations. Ordinary username copy/share and profile navigation remain; phone rows use the normal mobile label and dial/copy actions. Removed the FragmentUsernameBottomSheet and its now-unreferenced TL_fragment schema.

Removed username validation links and purchase-oriented validation messages from username/channel editing screens, and replaced the bot username help purchase link with ordinary username help. Removed the Fragment purchase action and the extra username-sale availability request from chat username long-press handling, retaining cached-peer and ordinary username resolution. Fragment URLs no longer receive a special browser confirmation exemption.

Source-only inspection covers removed class/schema references, the retained username resolution/copy paths, edited Java class boundaries and whitespace. No tests, build, APK or phone interaction. These changes remain unshipped; other Premium/Business/Stars/gift/backend/resource cleanup is still incomplete.

### Bot balance controls and paid post-suggestion settings

Removed ChatEditActivity's Stars/TON balance section, revenue-screen click actions, loading placeholders, observer registration and balance-update handling. Removed the paid post-suggestion row, its pricing/enablement display and theme bindings. Ordinary group editing, permissions, reactions, appearance and location controls remain.

Deleted PostSuggestionsEditActivity after removing its chat-edit entry point and the remaining paid-message commission/settings paragraph in StarsIntroActivity. This removes that screen's Stars price slider and Telegram paid-message/broadcast-settings requests rather than presenting a nonfunctional paid feature on XMPP. Other StarsIntroActivity transaction/revenue UI and BotStarsActivity/controller consumers remain for subsequent cleanup.

Source-only inspection covers deleted class/method references, edited class boundaries, notification branches and whitespace. No tests, compilation, APK or phone interaction; changes are unshipped and full monetization removal remains incomplete.

### Channel revenue tab and bot revenue/withdrawal page

Removed StatisticActivity's monetization tab, revenue-layout construction, start-from-revenue state, blur/scroll capture, padding and action-bar branches. Revenue permissions no longer qualify a chat for the statistics page. Retained statistics and the existing boost page, with tab creation matching the supported boost page; boost removal remains separate outstanding work.

Removed StarsIntroActivity's bot revenue/statistics button and deleted BotStarsActivity, including its Stars/TON charts, transaction loading and withdrawal UI/requests. Its duration-format helper was moved privately into ChannelMonetizationLayout for the latter's remaining references. ChannelMonetizationLayout and BotStarsController still have other consumers; no claim that their transaction/network implementations have been fully removed.

Source-only inspection covers deleted class/tab state references, surviving pager positions/page count, edited Java boundaries and whitespace. No tests, build, APK or phone interaction. Source changes are unshipped; shared revenue components, Stars entry paths, boost/Business/Premium code and resources remain in scope.

### Delete channel revenue implementation and monetary chart dependencies

Deleted ChannelMonetizationLayout itself, including revenue/statistics requests, Stars/TON withdrawal/password flows, balance refresh timers, CPM adjustment, transaction sheets, TON address/CRC validation, revenue explanations and nested transaction/proceeds widgets. Removed its channel-update callback in BotStarsController, its UItem constructors and the UniversalAdapter revenue view types/create/bind branches.

Removed TON/Stars currency chart formatting and duplicated currency/USD legend rows from the generic chart helpers; ordinary numeric charts retain their formatting/legend paths. Removed the Cocoon attribution/promotion and explanatory sheet from TranslateButton, which were the remaining consumers of the deleted revenue class's FeatureCell. Translation controls and provider selection remain. Pruned imports made unused by these removals, including the stale revenue import in SlideIntChooseView.

Source-only inspection covers deleted class/constants/API references across Java/XML, edited class boundaries, ordinary numeric legend count/labels, adapter switch branches and whitespace. No tests, compilation, APK or phone interaction. BotStarsController still contains other Stars/revenue/affiliate consumers and StarsIntroActivity still needs cleanup; full monetization removal is incomplete and these changes remain unshipped.

### Affiliate/referral programs

Deleted AffiliateProgramFragment, ChannelAffiliateProgramsFragment and SuggestedAffiliateProgramsFragment. Removed their chat/bot editing rows, update listeners, Stars balance entry and transaction connect/share/settings links. Removed the referral-controller tail from BotStarsController: connected/suggested bot discovery, connection lookup, sorting/pagination, connection edits and admined bot/channel discovery used by those screens. Existing Telegram Business connected-bot code is a separate remaining subsystem.

Moved the generic icon/title/description cell into Components.FeatureInfoCell for its surviving gift/feature-info callers and moved the generic permille percentage formatter into CurrencyFormatter. Neither helper has referral service behavior. Migrated all consumers before deleting the affiliate screens; their decorative helpers no longer keep affiliate connection/network code alive.

Source-only inspection covers deleted screen/controller/API references, surviving helper imports/callers, edited class boundaries, remaining notification branches and whitespace. No tests, compilation, APK or phone interaction. Changes are unshipped; historical affiliate transaction descriptions/schema, other Stars operations and Business/Premium/boost code remain in scope.

### Free folder membership, pin actions and topic emoji icons

Removed the chat-home folder membership tier check and its upgrade sheet. Removed repeated subscription-based pin counters/ceilings from the preview menu and bulk pin action, including the folder-specific hardcoded cap and upgrade/limit dialog. Ordinary pin/unpin actions, ordering, folder persistence, unread actions and community operations remain. Local Monogram state owns these operations; no paid entitlement is synthesized.

Removed TopicCreateFragment's paid emoji rejection/upgrade bulletin and simplified its private selectEmoji signature/callers. Existing topic icon rendering and selection continue through the same native emoji picker; this is frontend functionality available without a subscription, not an implementation of XMPP topic synchronization.

Source-only inspection covers the retained pin action callbacks/order handling, surviving folder operations, topic selection callers and imports, edited class boundaries and whitespace. No tests, compilation, APK or phone interaction. These changes remain unshipped. Other story/Business/Premium/Stars/boost controls and legacy limit metadata remain outstanding.

### Paid-media composition and dialog balance overlay

Removed paid-media entries from attachment send-preview options and the gallery menu. Deleted the media-price input sheet in StarsIntroActivity, gallery pricing setters/readers and paid-media selection cap/refresh code. Gallery selection clears outgoing Stars metadata and retains ordinary selection counters, grouping, compression and spoiler controls; normal spoiler behavior is no longer conditioned on a sale price. Other historical paid-message/media representations outside the picker remain for later cleanup.

Deleted the unused BalanceCloud Stars/TON top-up widget and its only host integration in AlertDialog: balance setter/getters, special fullscreen wrapper, purchase click action and fullscreen window branch. Repository reference inspection found no callers of those host APIs. Ordinary dialog custom-width, spinner and standard content/window paths remain.

Source-only inspection covers deleted pricing/overlay APIs across Java/XML, surviving gallery selection/menu conditions, edited class boundaries, dialog content/window branches and whitespace. No tests, build, APK or phone interaction. These changes are unshipped; Stars top-ups elsewhere, paid transaction history/schema, Business/Premium/boost code and other monetization resources remain in scope.

### Stars shortfall prompts and paid subscription management

Deleted StarsNeededSheet, the paid subscription-management sheet and StarsSubscriptionView from StarsIntroActivity. Removed subscription rows/expand controls, subscription loading/observer hooks and related click actions from that screen. This removes its renewal/refulfillment/payment UI rather than presenting Telegram subscriptions on XMPP; separate invite-link subscription controls/controller APIs still remain for later cleanup.

Removed the suggested-post low-balance action and helper in ChatActionCell, the send-error balance fetch/purchase prompt in AlertsCreator, and the search-error balance fetch/purchase callback in PostsSearchContainer. Search keeps its empty/list refresh for that error. Existing Monogram send/encryption diagnostics continue through their earlier error branch. Ordinary generic error handling is retained; no synthetic Stars entitlement or balance is introduced.

Source-only inspection covers deleted class/method/constants across Java/XML, surviving click/error branches, observer registration, edited Java boundaries and whitespace. No tests, compilation, APK or phone interaction. Other Stars top-up/gifting screens, paid post search/transaction history and Business/Premium/boost code remain outstanding. Changes are unshipped and full removal remains incomplete.

### Delete Stars catalogue, gifting checkout and balance activity

Deleted StarsOptionsSheet, GiftStarsSheet, StarTierView and the unused direct-purchase/paid-channel-invite checkout sheets from StarsIntroActivity. Removed the Stars-gift mode and its launch/layout/title branches from the shared user selector. No external callers remained for the root Stars balance activity after those flows were deleted, so removed its activity lifecycle, balance/buy/gift UI, purchase handlers, observers and loading hooks. StarsIntroActivity is now a non-instantiable static helper holder while surviving message-rendering callers are migrated in subsequent cleanup.

Deleted its now-orphaned StarsBalanceView, StarsTransactionsLayout (including paged history/balance loading and observers) and unused sample Transaction model after checking Java/Kotlin/XML references. Retained shared particle/expand/transaction rendering helpers for existing historical-message/boost callers rather than preserving a constructible balance/purchase screen.

Source-only inspection covers deleted constructors/types and direct checkout APIs, user-selector modes/callers, edited class boundaries and whitespace. No tests, build, APK or phone interaction. Remaining Stars controller APIs/history rendering, Premium/Business/boost features and resources still require cleanup; source changes remain unshipped and the full objective is incomplete.

### Invite creation fees and orphaned bot revenue controller

Removed monthly Stars subscription controls from LinkEditActivity: pricing input/USD conversion, fee explanation, fee/approval conflicts, amount parsing and export-invite subscription pricing flags/payload. Removed the paid-invite-specific approval lock/display restoration. Ordinary link title, expiry, usage-limit and approval editing/request construction remain; this does not convert existing Telegram subscription links into XMPP invites.

Deleted BotStarsController after its revenue/transaction/affiliate UI consumers had been removed. Removed its only surviving MessagesController revenue-update hook. This removes the remaining bot Stars/TON revenue stats caches/loaders and transaction pagination/background requests in that class. Other StarsController APIs and subscription information in InviteLinkBottomSheet still remain for subsequent cleanup.

Source-only inspection covers deleted controller references across Java/XML, invite editor pricing field/request references, surviving editor branches, Java class boundaries and whitespace. No tests, build, APK or phone interaction. Changes are unshipped; other Stars/history/schema, Premium/Business/boost code and resources remain outstanding.

### Invite subscription detail and revenue removal

Removed InviteLinkBottomSheet's subscription detail sheet and per-member subscription/revenue cells. Joined/requested/creator rows now use the ordinary UserCell and open the existing profile action directly, without the extra channel-participant fetch for a subscription detail sheet. Removed revenue headers/rows/adapter types and subscription-specific price/period/earnings labels.

Removed expired-subscriber row/list state and its pagination pass/request flag. Retained ordinary joined/requested-user pagination and combined has-more handling, creator/member role rendering, invite expiry/revocation information and usage/approval controls. Invite expiry itself is distinct from a paid subscription and remains supported.

Source-only inspection covers removed subscription/revenue identifiers and APIs across Java/XML, ordinary UserCell constructor/role methods, retained list indexes/pagination/profile click callbacks, edited Java boundaries and whitespace. No tests, build, APK or phone interaction. Changes remain unshipped; StarsController, transaction history/schema, other Premium/Business/boost screens and resources still require cleanup.

### Premium gift-code redemption and delivery sheets

Deleted GiftInfoBottomSheet, PremiumPreviewGiftLinkBottomSheet and PremiumPreviewGiftSentBottomSheet, plus the unused PremiumNotAvailableBottomSheet official-Telegram-install subscription prompt. Removed gift-code intent handling, chat action delegate/open methods and loading callback, boost-list gift-code detail construction and giveaway prize-redemption button. Ordinary chat actions, boost-list remaining branches and generic intent handling remain while their respective Telegram-only subsystems are removed separately.

This removes these subscription gift-code entry points and sheets; it does not claim the remaining PremiumPreviewBottomSheet, gift/history rendering or boost implementation is gone. The paid SMS implementation was already absent on source inspection; legacy SMS settings-link handling remains to be cleaned with the wider settings routing.

Source-only inspection covers deleted screen/method/delegate references across Java/XML, surviving action/intent/boost branches, edited Java boundaries and whitespace. No tests, build, APK or phone interaction. Changes are unshipped and full monetization removal remains incomplete.

### Remove paid public-post search

Deleted PostsSearchContainer, including Telegram post-search/flood-price checks, balance loading, pay-to-search controls, request cancellation/loading UI and paid-search empty-state rendering. Removed its SearchViewPager field, construction/listeners, tab metadata/title/view routing, search/color/padding hooks and the home keyboard-height callback. Removed the now-unused PremiumPreviewFragment New-label helper import from SearchViewPager.

Changed showDownloads to locate the DOWNLOADS_TYPE item rather than using a fixed position, preserving navigation after the Posts tab is removed and when optional tabs differ. Ordinary dialog/media filter search paths and download list construction remain. Separate public hashtag/story-search and recommendation implementations still remain for later classification/removal; this is not a claim that all Telegram global search is gone.

Source-only inspection covers deleted class/fields/tab API references across Java/XML, remaining pager type/filter mapping, Downloads callers and edited Java boundaries/whitespace. No tests, build, APK or phone interaction. These changes remain unshipped; Premium/Business/boost/Stars controller, historical data and resources remain in scope.

### Remove orphaned Stars payment APIs and ad toggle request

Deleted the unused Stars top-up and gift-option caches/accessors, purchase eligibility/top-up/gift purchase stubs, transaction arrays/pagination APIs, subscription/insufficient-subscription state and loaders from StarsController after their UI callers were removed. Removed the lone remaining transaction-invalidation call from the legacy balance-update handler. Giveaway options/purchase and remaining gift/profile/balance consumers still exist and remain in scope.

Deleted the unused Stars-unavailable dialog helper and its stale import. Deleted MessagesController.disableAds/isSponsoredDisabled and the now-unreferenced TL_account.toggleSponsoredMessages request type, removing that remaining Telegram sponsored-message settings request rather than leaving an inactive API behind.

Source-only inspection covers deleted public APIs/types, removed state identifiers, surviving balance-update branch and Java class boundaries/whitespace. No tests, build, APK or phone interaction. Changes remain unshipped; Stars giveaways/gift/profile operations, Premium/Business/boost code and resources still require cleanup.

### Giveaway purchase/setup removal

Deleted BoostViaGiftsBottomSheet and BoostPagerBottomSheet. Removed giveaway header buttons, setup/list click paths, prepaid-giveaway rows, purchase encouragement rows and the gifting promotion in the boost-limit footer. Removed the deleted pager's configuration-change hook and Stars giveaway option preload. Ordinary remaining boost/feature/copy controls still exist pending full boost cleanup; no giveaway button is left opening an unavailable purchase sheet.

Deleted the now-unused StarsController giveaway option cache/accessors and buyGiveaway stub. Deleted BoostRepository.payGiftCode/payGiveAway stubs and launchPreparedGiveaway, including its Telegram prepaid-giveaway launch request. Other boost repository methods and historical giveaway rendering remain for later cleanup.

Source inspection caught and corrected an orphaned else branch when removing the giveaway purchase row. Reviewed surviving boost-header/list/footer branches, deleted API references across Java/XML, Java class boundaries and whitespace. No tests, build, APK or phone interaction. Changes remain unshipped; wider boost/Premium/Business/Stars and resource/schema cleanup remains incomplete.

### Appearance boost-level gates

Removed ChannelColorActivity/GroupColorActivity's required-level calculations and lock labels, boost-count load/watch/refresh paths, Apply waiting for boost status, pack-selection level rejection and boost/limit prompts. Unsaved-change/back handling now depends on edits rather than subscription/boost level. Retained normal preview/color/emoji/wallpaper controls and generic save error reporting. Removed the group preview boost-count/promotion row while retaining its collapsing title/header behavior.

Important unfinished work: these legacy group/channel save paths still construct Telegram color/status/wallpaper requests. Removing the paywall does not establish working XMPP/local persistence for them. Existing local self-profile colors use a separate owner-scoped implementation; group presentation needs its own local storage/projection or a suitable XMPP mapping before full feature completion can be claimed. No artificial boost level or entitlement was written.

Source-only inspection covers deleted boost fields/methods/observers across both classes and callers, retained header animation targets, pack/preview selection paths, Java boundaries and whitespace. No tests, build, APK or phone interaction. Changes remain unshipped and the overall goal remains incomplete.

### Implement local group appearance persistence

Added MonogramGroupAppearance for device-only room name/profile colors, local emoji backgrounds and ordinary local emoji status (with expiry). Keys include database name, owning account JID and bare room JID. Save validates palette bounds, room identity, account generation, local custom-emoji files and status type; rejects collectible/remote emoji IDs. Projection reads preferences without constructing a controller or opening the pack cache, so room-store reloads can restore appearance safely. Updated production roomChat callers to supply the self identity for owner-scoped projection, retaining the neutral overload for callers without an owner.

Replaced ChannelColorActivity's Telegram color/status/wallpaper save requests with local persistence. Native palette initialization and local-emoji picker now use the current account explicitly. Removed the remote restricted-status catalogue load. Wallpapers/themes use the existing MonogramChatAppearance/ChatThemeController ownership checks and asynchronous file-copy path, restore the selected local theme/wallpaper when reopening, and report save failures without claiming success. Colors commit before wallpaper work; if wallpaper copying fails, colors remain saved and the editor stays open for retry. No state is published to other room members, and no boost entitlement is synthesized.

Source-only inspection covers account/peer/generation checks, controller-free store projection, roomChat signature/callers, palette/status validation, native theme/file wallpaper APIs, removed Telegram save-request references, Java boundaries and whitespace. No tests, build, APK or phone interaction. Marked the group-appearance task WIP: restart/account-isolation/wallpaper failure behavior needs verification when preparing the next APK. Broader monetization removal remains incomplete.

### Remove standalone and embedded boost statistics

Deleted BoostsActivity and ChannelBoostLayout, including their boost/gift participant loading, boost-status/can-boost queries, state/listeners, remaining boost actions and historical giveaway list UI. Removed chat and topic overflow boost entries/actions. StatisticActivity now has a single ordinary statistics page: removed its boost tab/layout, boost-only/start-from-boost state, boost-created observer/navigation, blur capture and padding/action-bar branches. Simplified its factory and migrated the profile/chat-edit callers; it no longer redirects a missing statistics permission into boost statistics.

Removed BoostDialogs' boost-success link into the deleted statistics activity while retaining its remaining success bulletin text for consumers still being cleaned. Ordinary statistics loading and its remaining callers are preserved; broader Telegram analytics and remaining boost restriction/controller/schema code are separate outstanding work.

Source-only inspection covers deleted classes/navigation/fields across Java/XML, remaining factory callers, ordinary statistics adapter page count and action-bar/notification branches, Java boundaries and whitespace. No tests, build, APK or phone interaction. These changes remain unshipped and full monetization removal remains incomplete.

### Remove chat permission boost upsells and transfer-speed promotions

Removed chat/topic boost-status and can-boost queries, cached eligibility fields and boosted-event observers. Removed chat service-message/boost-counter actions into boost dialogs, composer/attachment/emoji early-return detours that opened boost prompts, and the corresponding delegate/helper APIs. Preserved the underlying send/media permission checks and their normal error bulletins. The restricted-message overlay now displays the ordinary restriction text instead of a boost-to-send action.

Removed Premium transfer-speed upgrade bulletins and premium-flood-wait-driven visible-row scanning from ChatActivity and SearchDownloadsContainer, including notification subscriptions. Deleted the unused shared-media version of the same promotion. Transfer progress, real failure handling and ordinary download/media lists remain; no network speed or paid entitlement is fabricated.

Source-only inspection covers removed helpers/delegate/callers and fields across Java/XML, retained restriction error paths, remaining event branches, edited Java boundaries and whitespace. No tests, compilation, APK or phone interaction. Other boost restriction/controller/schema code and Premium/Business/Stars/resources remain in scope; changes are unshipped and the full goal remains incomplete.

### Free local launcher icon variants

Removed Premium eligibility checks and upgrade launch from AppIconsSelectorCell. All existing launcher variants appear in settings regardless of subscription metadata; titles no longer get lock badges and the cell no longer subscribes to Premium-status changes. Removed the paid boolean/constructor tier from LauncherIconController's icon model. Renamed the formerly Premium-titled variant to Gradient in the native settings label. Retained the existing Android activity-alias identifier to preserve launcher component identity; icon artwork and currently selected component are not changed by this source edit.

Adjusted the remaining Premium showcase's icon preview to select existing variants without paid metadata so it no longer depends on the removed field. The showcase itself is still pending removal. Source inspection confirms all six aliases exist in the manifest and no consumers remain for the deleted icon tier field. Reviewed icon selection/component handling, Java class boundaries and whitespace. No tests, build, APK or phone interaction; runtime launcher switching still needs APK verification. Other monetization cleanup remains active.

### Replace status Premium showcase with emoji-only preview

Replaced PremiumPreviewBottomSheet with Components.EmojiStatusPreviewSheet and migrated its two remaining chat callers. Kept the native status icon display and entrance animation, title transition and visual particles. Removed the Premium feature catalogue/gradient rows, Subscribe button, purchase launch, gift tier APIs/text, NFT collection links, gift confetti, promotional sticker prefetch, remote sticker-pack title loading and their listeners. Removed the default Premium logo renderer; a standard emoji icon is the fallback.

Chat status inspection no longer opens NFT slug URLs or a subscription-badge showcase. Ordinary emoji document inspection still supplies the original animated image and entrance coordinates; the status hint link is labelled Emoji status. Native localized preview labels describe the selected user's emoji status. Other Premium feature sheets and the main PremiumPreviewFragment remain separate outstanding work.

Source-only inspection covers old sheet/subclass/API references across Java/XML, migrated callers, remaining animation fields/icon lifecycle, single-row adapter and native label resources, edited class boundaries and whitespace. No tests, build, APK or phone interaction. Visual/animation verification remains pending at APK delivery, and the overall monetization goal remains incomplete.

### Subscription-independent animation/power settings

Replaced the animated-emoji Premium/non-Premium flag split in LiteMode with one existing Monogram preference bit per keyboard/reaction/chat setting. Runtime animation eligibility no longer reads subscription state. Retained the previous non-subscription bits and corresponding Monogram low/medium/high defaults, so this does not silently enable additional animation for current XMPP users. Obsolete tier-only slots are discarded when loading/saving preferences or applying preset masks; normal user toggles remain available.

Removed subscription-based subtraction from LiteModeSettingsActivity's enabled/total counters; each animated-emoji option now counts once. Existing device/API/battery restrictions and user-selected power-saving behavior remain. Source inspection covers old flag references across Java/XML, preference normalization, preset/toggle paths, counter/device checks, Java boundaries and whitespace. No tests, build, APK or phone interaction. Changes remain unshipped; broader Premium/Business/boost/Stars cleanup and release verification are still incomplete.

### Remove Telegram bot creation service

Deleted CreateBotAlert, including Premium upgrade prompts, Telegram managed-bot username checks, creation requests and creation-success navigation. Removed LinkManager's manager-resolution/creation flow and consume legacy newbot URLs without creating a Telegram account/bot. Deleted the now-unreferenced TL_bots.checkUsername/createBot request types.

Legacy keyboard and webview request-peer handlers now explicitly reject the Telegram create-bot subtype: native keyboard shows the standard unavailable bulletin; webview reports requested_chat_failed with the request ID. Other peer-selection branches remain. This removes the paid Telegram creation service without claiming to implement XMPP bot provisioning or leaving a waiting webview after its creation sheet is removed. Bot-controlled emoji-status setup is a separate remaining Telegram integration.

Source-only inspection covers deleted creation classes/API references across Java/XML, retained peer-selection branches, request-failure reply and URL dispatch, Java boundaries and whitespace. No tests, build, APK or phone interaction. Changes remain unshipped; broader Premium/Business/boost/Stars/resource cleanup remains incomplete.

### Remove Telegram bot-managed emoji status service

Deleted SetupEmojiStatusSheet, including its subscription prompts, remote emoji-status update/request flow, bot-grant UI and remote status catalogue helpers. Removed the bot status permission row/toggle/request tracking from ProfileActivity and obsolete debug reset hooks from profile/settings. Deleted the now-unreferenced TL_bots.toggleUserEmojiStatusPermission request type.

Webview status-setting calls now receive emoji_status_failed/UNSUPPORTED, and permission requests receive a denied status without prompting or changing any account status. Removed the grant/set delegate methods, host success/undo bulletins and public permission-notification helper used only by the deleted flows. Ordinary local profile/group emoji status controls and unrelated webview permissions remain.

Source-only inspection covers deleted service/delegate/request references across Java/XML, remaining profile permission row/header conditions and indexing, webview failure replies, Java boundaries and whitespace. No tests, build, APK or phone interaction. These changes remain unshipped; other Premium/Business/boost/Stars and schema/resource work remains outstanding.

### Privacy payment entry points and last-seen subscription promotion

Removed Privacy Settings rows, click handlers, adapter bindings/enabled checks and cached state for Telegram gift acceptance and Premium/Stars contact requirements. Old gift/message privacy settings links are consumed without opening those editors, including their price/fee/gift-subsection routes. Ordinary privacy navigation and the native Monogram typing/read-receipt controls are preserved. Removed the last-seen Premium promotion and explanatory sales row from PrivacyControlActivity, including navigation, bindings and row-diff bookkeeping; the ordinary last-seen/read-time controls remain.

This removes entry points and promotion code, not the complete legacy privacy payment implementation: PrivacyControlActivity still contains gift/paid-message editor branches and ContactsController/schema still retain those rules. Voice-message Premium gating and subscription-based privacy exception rules also require separate cleanup. No new XMPP privacy semantics are claimed. Source inspection checked edited class boundaries, remaining references to deleted row fields, settings-link routing, duplicate annotations and `git diff --check`. No tests, package build or phone interaction; these source changes remain unshipped.

### Delete paid-message privacy editor and paid-user exceptions

Removed the complete paid-message mode from PrivacyControlActivity: contact-Premium rejection prompt, charge radio choice, fee slider and locked purchase button/view holders, fee-exemption selector labels, charge/income explanations, pricing state, global paid-message save requests, fee-exemption save/load requests, and the cross-promotion suggesting stricter invite/call settings after enabling payment requirements. Removed the now-unused message-mode constant and payment choice slot; the remaining three privacy choices retain their ordinary exception-list comparison, animated selection and adapter bookkeeping.

Removed subscription-holder exception selection/state/labels and outgoing allow-Premium rules from this editor. Legacy allow-Premium rules are explicitly skipped during parsing so they cannot fall through into the ordinary rule classification. Concrete user/group exceptions are retained. Gift rules and voice-message subscription checks still remain; ordinary read/gift global-settings requests still copy historical global fields rather than changing them. Controller/schema cleanup remains separate. No claim of new XMPP privacy implementation.

Source review covered removed row/API references, the remaining currentType assignments/three-slot arrays, normal exception comparison, switch cases, class boundary and whitespace checks. Tests, build and phone checks remain deferred until an APK delivery. Source-only, unshipped.

### Remove gift privacy editor and retired privacy-rule requests

Deleted the gift acceptance editor from PrivacyControlActivity: gift-icon and gift-type rows/click handlers, subscription rejection prompts, gift global-settings saves, saved/current values, legacy gift key selection, enabled-state gates, adapter bindings and diff bookkeeping. Removed the gift-only miniapp/channel exception state/selection/rule emission and summary strings. PrivacyUsersActivity no longer offers Premium-user or gift-miniapp categories through its secondary exception-add path. Ordinary concrete-user/group exception selection and read-time controls remain.

ContactsController no longer requests, stores or exposes gift acceptance or paid-message fee-exemption rules, and MessagesController no longer forwards incoming gift privacy updates into that removed state. Existing numeric IDs for remaining rule types stay stable; unsupported retired slots are skipped before loading state is set. Removed the obsolete paid-message mode constant. Privacy summaries no longer advertise Premium/bot categories; historical rules for those removed categories are skipped instead of being interpreted as a normal rule.

Historical global privacy wire fields remain copied by the existing read-time save request; TL schema compatibility fields have not been removed. Voice-message subscription gates and broader Telegram-only privacy behavior remain unfinished. Source inspection covered deleted identifiers/callers across Java, retained rule indices/load switch, class boundaries, row conditions and diff whitespace. No build, tests or phone actions; unshipped source work.

### Free translation/effect selection and delete subscription-gift checkout

TranslateAlert3's Translate Entire Chat action now uses the existing TranslateController/provider settings without checking Telegram Premium or opening a subscription sheet. Configured-provider and translation-setting checks remain in TranslateController. MessageSendPreview now retains the selected message/camera effect, updates the native selector and preview and send-button state for every effect; removed the Premium-only rollback, selection suppression and subscription bulletin. Effect catalogue transport/XMPP effect interchange are not implemented by this gate removal.

Deleted GiftPremiumBottomSheet and its dedicated PremiumGiftTierCell/PremiumGiftHeaderCell. Its only remaining external dependency was the GiftTier data model used by otherwise unused subscription-gift factories in GiftVisuals; removed those factory/binding/equality/accessibility/discount branches, tier state and setter. Other remaining historical gift visual code remains for later cleanup. Legacy tg:premium_offer links no longer open PremiumPreviewFragment; the main storefront and other remaining routes still require removal.

Source inspection checked deleted class/API references across Java/resources, effect selection/clear branches and retained preview state, translation provider gating, class boundaries and whitespace. No tests, build or phone actions; source-only, unshipped. README remains untouched.

### Free Saved Messages tags with local persistence/search

Removed Saved Messages tag Premium entry/selection/hint/rename gates and the subscription overlay (including its touch/render suppression) from native ChatActivity/SearchTagsList. Tag actions are shown for Saved Messages only. Tag support no longer depends on Telegram saved-subdialog support. Existing tag rename UI, picker, animations, filtering and tag labels remain native.

Added MonogramSavedTags, backed by account-store tables for bookmark/tag associations and optional names. Schema 29 creates those tables on fresh databases and upgrades; account-store clearing clears both. Applying/removing tags validates the target is a visible own Saved Messages message, deduplicates standard/custom emoji identifiers and replaces its association set in a transaction. Names are persisted locally. History/reaction updates project these associations as reactions_as_tags. Counts exclude deleted messages. Named/custom emoji tags use their native reaction identifiers; local document availability still controls their rendering.

Handled tag listing/renaming/default suggestions locally in MonogramController and routed bookmark tagging through the cache worker without session(), encryption negotiation or outgoing reaction stanzas. Default suggestions combine existing tags, favorite emoji and ordinary built-ins. Ordinary contact/group reaction transport is unchanged. Search honors saved_reaction filters via bound SQL EXISTS clauses restricted to own Saved Messages; empty tag filters preserve normal search. Tags/names are device-local, with no cross-device synchronization or Telegram saved-subdialog grouping added.

Source inspection checked native TL types, account/self addressing, local request dispatch, SQL parameter ordering, clear/creation/upgrade paths, supported tag identifier paths, remaining tag Premium references, new/edited class boundaries and diff whitespace. No tests, Nix build or device interaction per standing instruction. Migration/restart/account isolation, rename/delete counts and picker/search interaction need validation at the next APK delivery; this is unshipped source work, not runtime verification.

### Remove Telegram Business chatbot delegation

Deleted ChatbotsActivity, ChatbotSheet, BusinessChatbotController and BusinessBotButton. Removed their Premium/User Info navigation/preload and catalogue row, session-list loading/rows/clicks/identity bindings, special terminate-chatbot prompt, chat top-panel construction/visibility and obsolete peer-setting persistence. Ordinary session termination remains on its existing confirmation path. Normal XMPP bot conversations are unaffected; these controls managed Telegram account delegation, not bot JIDs.

Removed new Business-bot connection forwarding and approval/disconnection requests from UnconfirmedAuthController. Cached legacy bot-delegation records are parsed only to skip them, while ordinary authorization records retain their existing path; removed the now-unnecessary bot-user fetch. Deleted the unused connected-bot response/model plus confirm/get/update/pause/disable request classes in TL_account after checking all callers. Raw historical PeerSettings/update/authorization fields and remaining storefront feature metadata are separate cleanup; other Business services remain.

Source review checked deleted identifiers and request-class references across Java/resources, session row types/indices/ordinary termination continuation, chat panel conditions, ordinary authorization callbacks, class boundaries and diff whitespace. No tests, packaging or phone interaction; unshipped source work.

### Remove Telegram Business chat-link service and editor mode

Deleted BusinessLinksActivity, BusinessLinksController and BusinessLinksEmptyView, their Premium catalogue entry/navigation/preload and shared UItem/UniversalAdapter row type/factory/bindings. Removed ChatActivity's Business link edit mode, link-specific menu/discard/title/empty view/observer branches, preset resolved-link message injection and composer setup/delegate. Normal composer delegate, channel-rights check and Saved Messages preloading remain; removed the link-only attachment/input restrictions and header/reaction-overlay exceptions. Other quick-reply/welcome/suggestion modes retain their own paths.

Deleted Business-link editing/preset-message/save state and methods from ChatActivityEnterView. Removed ChatAvatarContainer's link-mode media-preload/avatar/subtitle exceptions. Deleted nine unused Business chat-link request/response/input classes in TL_account. LaunchActivity consumes legacy Business link slugs without performing Telegram resolution; its broad legacy link parameter plumbing, inert notification IDs and old storage table are not removed in this pass. Ordinary XMPP links are unchanged. Other Business services and the main Premium storefront remain unfinished.

Source inspection checked removed modes/classes/methods/request identifiers across Java/resources, shared adapter switch cases, ordinary composer delegate/rights/attachment paths, header conditions, class boundaries and diff whitespace. No tests, build or device interaction; unshipped source work.

### Round-video branding correction

The round-video watermark was a baked curved TELEGRAM PNG, referenced by both InstantCameraVideoEncoderOverlayHelper and RoundVideoOverlayRenderer. Replaced both uses with MonogramBranding.roundVideoWatermark, a shared native Canvas alpha-mask renderer drawing curved MONOGRAM text at the existing corner footprint/output sizes. Kept the plane animation unchanged as requested for logo branding. Removed the now-unused Telegram wordmark PNG. This affects newly encoded videos; text already baked into older files cannot change.

Source review checked both encoder call sites, alpha-mask ownership/recycling, old-resource references, class boundaries and diff whitespace. No tests, build or phone actions; visual/recording verification remains for APK delivery.

### Remove recent-GIF eviction and favorite-media upgrade bulletins

Removed the native recent-GIF count eviction and its backing database deletion, and made GIF persistence/load processing bypass the legacy maxRecentGifsCount truncation. Regular sticker recents/favorites were already unlimited; removed their unused favorite-cap lookup from the add path and persistence logic. Masks and greeting/catalogue bounds retain their existing independent behavior. MonogramGiphy's metadata-size/HTTP-response safety bounds remain; they are not subscription tiers.

Deleted favorite-sticker/GIF replacement-limit bulletin types and both Premium upgrade descriptions/actions, including the longer bulletin-duration branch. Removed addRecentGif's replacement-bulletin argument and migrated all native sender/receiver/viewer/composer callers; ordinary added/removed favorite notifications remain. Existing configuration fields/limit showcase code elsewhere remain for later removal.

Source review checked the old cap/eviction conditions, unlimited persistence guards, every addRecentGif caller and removed bulletin identifiers, edited class boundaries and whitespace. No tests, package build or phone interaction; unshipped source work. Restart/large-library behavior still needs APK-delivery verification.

### Remove share/recipient Premium blockers and native send fees

Removed ShareAlert and GroupCreateActivity's paid-contact selection rejection and Premium upgrade bulletins. Share search no longer opts into paid-contact decoration. ShareAlert no longer calculates per-recipient Stars fees, wraps forwarding/text/story sending in payment confirmation, sets paid send parameters or prices its send button. Preserved selected-recipient counts, comments, text entities, topic selection, forwarding options, sound choice, actual slow-mode/permission/send-result checks, delegate callbacks and dismissal.

Removed SendMessagesHelper's contact-fee lookups and payment-confirmation resend branches from forwarding and the main send path, and fee lookup for game requests. Removed fee stamping of newly sent messages and outgoing allow_paid_stars flag/value assignments throughout its text/media/album/forward request builders. Native API fee parameters still exist as inert compatibility plumbing pending broader signature cleanup; historical paid-media branches remain separate work. Shared recipient-cell badges/requirement queries and controller caching still remain and require the next removal pass. Actual XMPP block lists and group rights were not changed.

Source inspection checked all changed send branches, absence of payment/query/write identifiers in these paths, the unwrapped share callback/delegate sequence, remaining local identifiers in the main send method, class boundaries and whitespace. No tests, build or phone actions; unshipped source work.

### Remove payment badges from shared recipient cells

Removed Premium/Stars requirement state, lookups, update listeners, lock/price badge rendering and public restriction APIs from ShareDialogCell, HintDialogCell, ProfileSearchCell and GroupCreateUserCell. Removed the Share cell's now-unused observer interface and paid-price getter. Preserved ordinary avatars, names, status, online-dot animation, checkboxes, dividers and actual secret-chat name decoration. Online indicators no longer shrink/disappear behind paid-contact badge progress.

Migrated search/share/group/restricted-recipient callers: removed paid-selection rejection for ProfileSearchCell/HintDialogCell/GroupCreateUserCell, paid restriction overrides/status labels in the restricted-user picker, old Premium badge opt-ins and CategoryAdapterRecycler's unused paid-badge parameter. Native selection callbacks and normal user status remain. Main DialogCell badges, other paid-contact consumers and the controller requirement cache/request are still pending; actual XMPP/Stories block lists and group permission checks are unchanged.

Source review checked all removed cell state/API names across consumers, both CategoryAdapterRecycler constructors, ordinary online/checkbox rendering branches, class boundaries and diff whitespace. No tests, build or phone interaction; source-only, unshipped.

### Remove main-list paid-contact locks and restricted-invite subscription paths

Removed DialogCell paid-contact locks, paid-channel subscription star badges and their price/status state; removed associated chat-list click/long-press suppression, paid-contact toast/callback and recipient-price calculation. Normal chat navigation, unread/online/TTL/call/story indicators and real permissions retain their existing paths. Profile secret-chat eligibility no longer depends on Telegram paid-contact state; this does not add XMPP secret-chat support.

Deleted MessagesController’s contact-requirement cache, batching runnable, RPC and invalidation methods and TL_account.getRequirementsToContact after migrating all Java callers. Call invitation failures retain actual restricted-user collection and the invite-link sheet without a second request to classify users by subscription. Restricted invitation sheets no longer accept paid-user arrays, hide users by payment status or display subscription purchase offers. Invite-link sends no longer calculate prices or request payment confirmation. Missing-invitee users and the XMPP-specific failure alert remain handled.

Story replies no longer query paid-contact state or show Premium/boost Unlock actions. Actual group plain-message permission and disabled-live-comment checks remain, with ordinary reply-restricted notices. Other story subscription/boost paths remain separate cleanup.

Removed native voice-message privacy radio locks, subscription error/purchase bulletin and Premium-only exception-row suppression. This makes existing native choices available without payment; it does not implement missing XMPP voice-message privacy enforcement or claim that those legacy rules work end to end.

Source inspection covered remaining references to removed APIs, all restricted-sheet callers, selection/link sending, retained restriction conditions, Java class boundaries and diff whitespace. No tests, Nix build or phone actions; unshipped source work. Runtime/compilation checks are deferred to the next APK. README remains untouched.

### Remove privacy/contact upsell views and free bookmark-tag confirmations

Deleted the read-time/last-seen subscription sheet and its action that changed Telegram privacy settings. MessagePrivateSeenView keeps read/edit/forward date presentation; either party’s privacy restriction now displays the existing unknown-read-time label without a purchase prompt or privacy override. Removed its subscription pill, lock state and dismissal callback, migrating all seven native menu construction sites. Removed the profile’s hidden-status Show purchase action and its animation state; the shared ShowDrawable used by ordinary chat/event-log controls remains.

Removed ChatGreetingsView’s unused paid-contact lock/card/button/particle system and subscription sheet, plus its only remaining reset call. Normal empty-chat text, greeting sticker layout and sticker interaction remain. SenderSelectPopup no longer adds Premium lock badges or intercepts selections to show a subscription bulletin; removed its dedicated SelectSendAsPremiumHintBulletinLayout and overlay-window bookkeeping. Native peer selection still calls the existing sender callback; this does not implement unsupported Telegram channel identities in XMPP.

Removed the voice-message privacy settings-search subscription gate and its now-unused promotional bulletin. Saved Messages forwarding/tag confirmation bulletins now expose the existing tag selector without a Telegram subscription. Existing local bookmark-tag persistence/routing remains unchanged. Deleted the unused multiple-reaction promo method, its SharedConfig saved flag/setter/reset calls, and an unused chat-cell delegate callback that opened Premium features. Actual reaction transport, animations and XMPP consent checks remain.

Source inspection checked removed symbols across Java, remaining constructor signatures/call sites, shared drawable consumers, ordinary empty-state initialization, sender callback, privacy-error handling, class boundaries and diff whitespace. No tests, Nix build or phone actions. This is unshipped source cleanup; compilation and native interaction checks remain for the next APK. Main Premium storefront, story/Business/AI/transcription and gift/Stars cleanup remain unfinished. README remains untouched.

### Remove Telegram story stealth service and free native story media controls

Deleted StealthModeAlert and its activation RPC class. Removed anonymous-view/stealth menu actions and locked artwork construction from the home story menu and story viewer. Removed StoriesController stealth state, saved-state parsing/writing, getters/setters, its update dispatch, notification event, MessagesController timing configuration and SharedConfig send-confirmation settings/reset calls. Raw story response/update data classes and storefront feature metadata remain for subsequent schema/catalogue cleanup; no stealth activation request or runtime UI/controller state remains.

Replaced the viewer’s stealth countdown/update routine with ordinary reply-input state updates. Real group reply restrictions and disabled live comments still disable input; ordinary reply/comment hints remain. Removed the per-second countdown callback and stealth-specific sending confirmation. Actual ordinary send callback and account-frozen checks remain.

Native story playback-quality selection and saving shared media now use the existing unrestricted action paths, without Premium lock artwork, subscription cards or feature sheets. Existing unsupported/share/live conditions still control saving; the quality preference and quality-update notification remain. Story captions use the existing larger allowance for every account. Removed the subscription-dependent local expiring-story count check and Premium-account-required purchase detour; existing service responses/error handling still apply. This does not implement XMPP story publishing, alter server permissions or claim the inherited Telegram story backend works with Jabber.

Removed Premium-user and Telegram-service-account promotion from story ordering. Live, uploading and unread precedence remain, followed by latest date. Source inspection covered all deleted class/request/config/state/callback references, menu chain continuity, ordinary reply state/send continuation, retained save/quality conditions, sorting branches, Java class boundaries and whitespace. No tests, Nix build or device actions; source-only, unshipped. Broader story recorder/viewer-list/boosts and the remaining main Premium storefront still require cleanup. README remains untouched.

### Free native story recorder controls and shared media caption limits

Removed story-duration popup subscription locks and their hint callback, allowing existing duration choices through the normal selection callback. Removed the story editor link-widget lock artwork/masking, unused Premium bulletin and purchase-sheet implementation. Long-video multiple-story selection no longer checks account subscription. Existing duration, editing, collage and repost conditions remain.

CaptionStory and CaptionPhotoViewer now expose their existing larger caption limits directly; removed the shared caption container’s default/Premium getter split. Crossing the actual maximum still updates the character counter and gives error feedback. The story recorder now uses the existing plain Monogram caption-limit bulletin instead of a purchase prompt. Native story posting/formatting capability policy treats legacy “premium” configuration as enabled for everyone; explicit “disabled” policy still applies. Removed the story formatting purchase interception and duration hint registration. This does not supply an XMPP story backend or override a service error.

Replaced StoryRecorder’s generic subscription limit sheet and custom Premium-opening fragment with an ordinary limit notice, including the server-provided retry-until time when present. Dismissing it releases the preview pause; the existing close-recorder condition remains. Deleted openPremium/premiumText/period-purchase helper methods and their imports. Added two neutral server-limit strings. Other viewer-list, story-home prompts, boost and catalogue cleanup remain pending.

Source inspection covered caption getter overrides/callers, period callbacks, widget drawing, remaining recorder purchase helper references, explicit disabled policy, formatting/send paths, limit notice pause/dismiss continuation, Java class boundaries and whitespace. No tests, Nix build or phone actions. Source-only and unshipped; compilation, caption boundaries, media/story editor controls and service-error presentation need verification at the next APK. README remains untouched.

### Remove story viewer-list subscription cutoff and remaining home purchase hints

Removed SelfStoryViewsPage’s subscription-dependent local expiry/reactions-only state, its suppression of cached recent viewers, paging and search/filter controls, and dedicated subscription hint row/purchase sheet. The model now seeds available recent viewers and uses normal page loading independently of account subscription. Existing service responses, incomplete-result notices, loading/end-of-page rules, search/contact filters and channel reactions remain authoritative; this cannot recover data a server no longer retains and does not implement XMPP story history.

Deleted DialogStoriesCell’s Premium hint overlay, scrolling/layout/translation bookkeeping and purchase route, along with all parent DialogsActivity callers. Removed the separate home-recorder subscription hint and unused upgrade flag. Explicitly disabled story posting still blocks opening the recorder. Story header status rendering retains emoji statuses and drops the fallback Premium-account star badge.

Replaced all four remaining StoryLimit-to-Premium-limit-sheet routes (home recorder, story strip, external story sharing and viewer notification) with the existing neutral server-limit dialog. Centralized its plain message/retry-until formatting in StoryLimit.getMessage, reused by StoryRecorder, and removed getLimitReachedType plus the viewer’s dedicated subscription-sheet fragment/activity helper. Server count/time checks and ordinary continue/return behavior remain.

Source inspection checked removed viewer-state/hint/class/method identifiers across Java, cached-view initialization, paging termination, list/empty/divider construction, all five notice entry points and theme providers, retained disabled-posting handling, shared status rendering, class boundaries and whitespace. No tests, Nix build or phone interaction; unshipped source work. Compilation and native viewer/search/home/limit-dialog behavior remain for the next APK. Remaining story boost controls, main storefront and wider monetization audit are unfinished. README remains untouched.

### Remove paid DM sharing controls and gift-to-Premium error route

Removed profile Enable/Disable Sharing menu IDs, entries, visibility updates, subscription sheet, confirmation card and DM request callback. Deleted the dedicated sharing-status/pending bulletin and unused confirmation-sheet implementation. Historical no-forward request messages retain their existing display, but no longer construct accept/decline keyboards or dispatch sharing-request RPC callbacks; deleted those keyboard IDs/builders and the action-cell-only callback.

Removed MessagesController’s generic dialog/request-message sharing method, result classification and callback constants. The existing unpaid group setting uses a narrower toggleGroupNoForwards(chatId, enabled) method; migrated its two ChatEditTypeActivity callers and retained update processing/interface notification. Generated optional request fields and historical no-forward model/display/enforcement are not purged in this pass; the group setting’s XMPP backend compatibility is not certified by this source change.

Removed the PREMIUM_GIFT_SELF_REQUIRED clickable route into the Premium storefront and the special Premium error-dialog title. Generic error text/OK handling and AUTH_KEY_DROP logout confirmation remain. Main storefront and other entry points remain unfinished.

Capability inspection also found that native schedule_date sends and checklist mutations have not been bridged to XMPP in the current controller; merely removing subscription UI gates would not make them usable. Existing helper/scheduling history is not runtime proof of native recurring scheduling. These remain explicit capability work, not completed/free-working features in this pass.

Source inspection checked all deleted menu/callback/keyboard/result identifiers across Java, both remaining group-setting callers, group request update/error continuation, historical sharing display, gift error-route removal, ordinary account error/logout handling, class boundaries and diff whitespace. No tests, Nix build or device actions. Source-only, unshipped; compile and verify profile/menu/service-message/group-setting behavior at next APK. README remains untouched.

### Delete doubled-limits sales page and orphan gift sheets

Deleted DoubledLimitsBottomSheet and DoubleLimitsPageView, including their subscription button, account-tier/price comparison adapter and gradient measurement. Removed the doubled-limits feature from the remaining Premium catalogue, its feature constant and server-name conversion cases, feature-sheet page construction/title/full-height branches. The remaining sheet’s fallback title uses the selected feature title. This removes sales presentation, not actual application limits; the wider limit/capability audit remains unfinished.

Deleted CraftGiftSheet and SellGiftEnterPriceSheet after confirming no remaining source callers. They were orphaned remnants of retired gift crafting/pricing flows; this does not claim the remaining StarGiftSheet, collectible/history presentation or main Premium storefront has been removed.

Source inspection checked removed class/constant references across Java/XML, catalogue construction and page/title branches, feature-name mapping, class boundaries and diff whitespace. No tests, Nix build or phone actions. Unshipped source cleanup; next APK must verify compilation and surviving native controls. Remaining storefront/tier metadata, service-specific feature routes, gift/Stars/backend/schema/assets and final capability/release verification are still pending. README remains untouched.

### Remove subscription tier selector, pricing model and unavailable purchase callbacks

Deleted PremiumTierCell and PremiumPreviewFragment.SubscriptionTier, subscription option collection/current-selection/annual-selection state, tier adapter/touch routing/rounded selection, discounts/yearly pricing and tier-gradient measurement. Removed unused force-subscriber/annual-selection setters. Deleted the main catalogue screen’s purchase bar, purchase listeners, blur/drawing/inset handling and bottom-height reservation. Removed all buyPremium/getPremiumButtonText overloads, which previously led only to the purchases-unavailable message. Removed subscription-status/promo observers and the catalogue’s eager promo-video preload.

Removed tier parameters/state from PremiumFeatureBottomSheet constructors and migrated native callers. Its existing bottom control now consistently says OK and dismisses; removed purchase/About Premium navigation, unlock labels/icons, force-about API and subscription-driven label listeners. Other sheet illustrations/descriptions and subscription-gated callers remain unfinished cleanup; this does not make unsupported transcription, AI, quick replies or checklist transport work. Retained generic close-button artwork and applicable feature visuals are separate from subscription purchase behavior.

The main PremiumPreviewFragment feature catalogue and its remaining navigation/shared metadata still exist. This is removal of the subscription purchase/tier subsystem, not a claim that the storefront or all upsells have been purged. Wider capability/limit/schema/resource audit and final release verification remain pending.

Source inspection checked deleted tier/class/purchase APIs across Java, migrated constructor signatures, tier touch/drawing/inset references, close-button overlay/click behavior, notification registration removal, remaining catalogue layout, class boundaries and diff whitespace. No tests, Nix build or phone actions. Source-only and unshipped; compilation and remaining native layout/navigation need validation at the next APK. README remains untouched.

### Remove Stars/TON balance state and paid-send error plumbing

Deleted StarsController balance/available/loading/minus state, zero-balance stubs, amount/currency accessor, invalidation/update methods and canUseTon. Removed the separate TON/currency factories and dual singleton array; remaining historical gift data uses one synchronized controller instance per account. Removed Stars-balance update dispatch, dice-result TON invalidation and the chat-open TON probe. Deleted balance/subscription notification events and their home/chat/profile/settings listeners after checking no remaining producers/consumers.

Removed four ALLOW_PAYMENT_REQUIRED price-specific send-error branches, amount parsing/custom price persistence and price-change profile refresh/toast. Deleted getAllowedPaidStars, unused request-peer helper and showPriceChangedToast. Ordinary error processing, failed-message persistence, messageSendError notifications, sent-message cleanup and retry paths remain. Historical TL amount/message custom fields and remaining gift/catalogue service code still need separate cleanup.

Source inspection checked all deleted balance/factory/event/fee identifiers across Java, remaining singleton callers, send-error fallthrough and notifications, class boundaries and diff whitespace. No tests, Nix build or phone actions. Source-only and unshipped; compile and verify ordinary send/retry/settings behavior at the next APK. README remains untouched.

### Remove collectible collection creation and naming editor

Removed gift collection creation from profile/context menus, the extra New Collection tab and its name editor; removed the collection rename menu action. Shared media no longer offers collection creation. Tabs now appear for existing collections without using creation eligibility to keep an empty tab strip visible. Removed canAdd, its server-configured creation-count check, temporary create-tab text and pre-tab creation callback. Existing collection display and unrelated tab selection remain.

Deleted StarsController.GiftsCollections.createCollection, its optimistic temporary collection/list, creating state and network/error callback, plus the rename request method. Deleted the now-unused TL_stars.createStarGiftCollection request class. Retained generic updateStarGiftCollection wire type because existing membership operations still use it. The existing selector/bulk-add, membership/removal/reordering and collectible/history screens remain pending removal; this is not a claim that all gift collection management has been removed.

Source inspection checked removed creation/editor/request/canAdd identifiers across Java, profile/tab/shared-media entry points, menu-chain continuation, remaining controller references, class boundaries and diff whitespace. No tests, Nix build or phone actions. Source-only, unshipped; next APK must verify compilation and surviving ordinary profile/shared-media behavior. README remains untouched.
