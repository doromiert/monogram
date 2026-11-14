# Chat defaults and the 0.2.205 upgrade

Read receipts (direct and supported group receipts), typing indicators, automatic
link embeds including encrypted chats, and direct-contact blind trust start on.
The first upgrade of each account enables these even if the old version saved
its previous defaults as false. A versioned marker prevents later updates from
overwriting changes made after this upgrade. Explicit blocked-device decisions
are not changed. Room blind trust remains a separate opt-in.

Preferences migration applies immediately in memory and writes asynchronously.
Device credentials, OMEMO identities/ratchets and wallet keys are unchanged.

Custom emoji names are independent of the associated fallback emoji. Open
Settings → Stickers and custom emoji → your custom emoji pack → an emoji →
Edit emoji name. Names populate native pack keywords and animated-emoji search
suggestions. Artwork and fallback can still be edited separately.

Direct Legacy OMEMO inline artwork is now sent only to peers advertising the
[local Legacy inline proposal](PROPOSED_XEP_LEGACY_INLINE.md). The artwork is
inside authenticated ciphertext; other clients keep the normal Unicode fallback.
Previously received fallback-only messages cannot recover omitted artwork.
