# Free local name and profile colors

Chat Settings → Change Your Color and the self profile color menu reuse the
native PeerColorActivity picker, with an explicit originating account. For XMPP,
its Name tab selects one of the seven built-in name/reply swatches; Profile
selects one of those seven native swatches plus gray, or resets the profile
background. Both tabs also expose the native background-icon row. Its account-bound
picker shows only installed Monogram custom-emoji packs, supports clearing, and
opens the existing pack manager when no emoji packs are installed. Both color and
background-icon choices save together on Apply. Native profile/message previews
remain in use and the normal unsaved-change confirmation is available for free.
The information row explains that colors apply only on this device, not to
contacts' clients. No unsupported color vCard field or protocol is published.

Local palettes use native avatar/name color definitions and the existing native
PeerColors registry/renderer, with the standard name IDs preserved. XMPP does
not fetch Telegram's help peer-color catalogs. The picker omits gift/resale
sections and Premium purchase buttons and does not load Stars/gifts, request
Telegram default reply icons or send account.updateColor. The background-icon
selector reuses Telegram's native pack tabs, selection ring, clear control,
renderers, haptics and live previews while sourcing documents directly from the
originating XMPP account's installed local emoji packs. It never falls back to a
featured/default Telegram catalog. Picker callbacks and Apply reject a closed,
logged-out, replaced-account or stale-generation session. Gift/status
collectibles are not supported or silently converted into an XMPP capability.

Metadata, including the two optional local background-emoji document IDs, is
committed atomically to device-private preferences using a hash of account
slot/database namespace, account bare JID and self bare JID. Apply verifies that
the originating account is still signed in and the selected native user is that
account's self user. Failed commits keep the picker open with a visible error;
other accounts, peers and contacts cannot inherit these colors through reusable
numeric IDs. Native UserConfig and MessagesController cache are updated after
commit, with interface/profile notifications. Fresh MonogramStore.user projection
reapplies the durable choices without constructing a Controller, including after
history/profile refresh or process restart. No remote/network mutation occurs.

Four focused `MonogramUserColorsTest` fixtures pass at the 0.2.123 release gate: fresh native
projection/flags; profile reset retaining name; namespace/owner/peer/non-self
isolation; stale-owner/unsupported-selection preservation. Release acceptance:
apply both tabs with and without icons, inspect own profile plus own sender/reply
color and icon in a group, reopen/restart and switch accounts, confirm no purchase
UI or Telegram request. The two additional local-icon storage cases pass at the 0.2.124 gate, alongside the original four: atomic two-icon projection/clear with retained colors, account isolation, rejection of nonlocal or ordinary-sticker IDs, and failed missing-file updates preserving the saved state. Nix arm64 packaging also passes. Real picker/profile/reply rendering remains unverified.
These are self presentation choices; arbitrary per-contact color overrides and
peer-synchronized appearance are not implemented by this pass.
