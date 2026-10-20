# Free native features and removed purchases

Monogram's XMPP accounts have no Telegram Premium subscription. `UserConfig.isPremium()`
continues to report the actual native account flag; free capabilities use the
explicit `MonogramFreeFeatures` policy instead. It does not set Premium markers
on users or enable Telegram server operations.

For an active XMPP account this source checkpoint:

- Uses the existing larger native message/caption/bio length allowance, with no
  change to the XMPP parser/stanza/file-size bounds. Bio remains the existing
  native Premium-sized allowance, rather than an arbitrary unbounded vCard field.
- Allows up to the existing 32 per-user Unicode reactions in a chat, independent
  of a subscription. XEP-0444 remains Unicode-only; this does not invent a custom
  animated-reaction protocol.
- Treats locally imported/created XMPP sticker and emoji IDs (and the account-owned
  received inline-emoji cache) as free in keyboard
  pack processing, pack expansion/install presentation, long-press preview,
  sticker cells and native animated-emoji send validation. Residual animated-emoji
  unlock actions open the local pack importer, and nonlocal/unavailable custom
  emoji ask for import instead of offering a Premium purchase. Imported Telegram
  Premium metadata cannot add a payment requirement to a local pack.
- Removes caption purchase prompts for local XMPP compositions. Genuine maximum
  length validation still runs.
- Omits Premium, Stars, TON/wallet, Business and gifting purchase rows from the
  native self profile/settings page, including the Stars balance lookup that
  previously ran while constructing those rows. Wallet transfer is omitted from
  contact menus. Purchase policy getters suppress Stars/Premium purchase offers.
- Skips billing startup while the build's existing `IS_BILLING_UNAVAILABLE` flag
  is set, including reconnect calls into the billing controller.

Unsupported custom emoji reactions now explain the XMPP limitation rather than
showing a Premium purchase bulletin. Ordinary Unicode reactions are unchanged.
Native account limits were already free in this fork. Existing local recent/favorite
sticker handling, account isolation, pack import/sync, encrypted pack sharing,
private metadata and trust settings are unchanged.

This is not a completed removal of every stock Telegram business/payment class.
The billing, Wallet and Stripe Gradle libraries remain because compiled native
payment classes directly reference those types. Removing those entire classes,
all deep-link routing and dependencies needs a separate deletion pass; removing
only dependency declarations would break compilation. Server-only effects,
boosts, status gifts, Telegram Business/Quick Replies and the separate unported
rich-message editor are not unlocked by pretending the account is Premium.
Additional native purchase/feature entrypoints may remain outside this bounded
keyboard/profile pass and must be audited before declaring full monetization removal.

Compiled and packaged in 0.2.120. No device actions or visual payment-gate acceptance were performed. Remaining native acceptance:
self settings with no monetary rows; contact menu without wallet; long imported
emoji packs fully visible without lock/purchase rows; send local custom emoji and
Premium-tagged imported stickers; captions beyond the old free allowance; regular
multi-reactions and the unsupported-custom-reaction explanation. Verify that
actual limits, account switching and queued encrypted sends still behave normally.

## Purchase links, sidebar and notifications

A second source checkpoint blocks known monetary routes before LaunchActivity's
GiftInfo/UserSelector handlers or LinkManager can open a payment form or request
Telegram payment data. The classifier covers tg opaque/hierarchical links,
Telegram public-link aliases (including invoice `$slug` paths and username.t.me),
Stars/Premium/Business purchase settings, gifts/auctions/boosts and TON/TonConnect
wallet routes. Percent-encoded commands and Android-accepted malformed query
values cannot turn a known purchase route into an unclassified link. Unrelated
external sites are not classified just because a path contains “invoice”.

Rejection uses native toast/bulletin handling, closes link progress and clears
pending Premium navigation callbacks. The internal typed invoice/gift/boost
request path has an additional guard, including boost notifications. This does
not send a help/deep-link-info request to Telegram to explain the rejection.
Chat, sticker/custom-emoji import, XMPP invitations, translation/settings and
location routes continue through their existing handlers.

The native chat-list top menu no longer exposes the wallet or Telegram-only bot
sidebar entries for XMPP accounts. Premium restore/annual/Christmas, Stars low
balance, auction and download-speed purchase hints are suppressed before promo
lookups. Birthday gift purchase icons are omitted while ordinary profile birthday
text remains. The debug invoice-payment toggle is hidden using the existing
nullable debug-row pattern. Wallet-only TonConnect notifications are suppressed
and any notification left by an earlier version is cancelled during notification
controller initialization; ordinary XMPP message notifications are unchanged.

Four pure synthetic URI regression fixtures are prepared in
`MonogramPurchaseLinksTest`; they have not been run. They check the monetary
aliases, preserved ordinary links, settings route boundaries and encoded/malformed
query handling. No URI is opened and no account/server/network action occurs.
The stock compiled payment implementations and their Gradle dependencies still
remain; these guards remove the reviewed reachable routes, rather than claiming
those unused classes have already been deleted.
