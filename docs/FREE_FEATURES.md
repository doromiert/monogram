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

Validation is source-only. No tests, Gradle builds or device actions were run for
this checkpoint. At the parent's next planned release gate, compile and inspect:
self settings with no monetary rows; contact menu without wallet; long imported
emoji packs fully visible without lock/purchase rows; send local custom emoji and
Premium-tagged imported stickers; captions beyond the old free allowance; regular
multi-reactions and the unsupported-custom-reaction explanation. Verify that
actual limits, account switching and queued encrypted sends still behave normally.
