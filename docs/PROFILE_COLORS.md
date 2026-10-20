# Free local name and profile colors

Chat Settings → Change Your Color and the self profile color menu reuse the
native PeerColorActivity picker, with an explicit originating account. For XMPP,
its Name tab selects one of the seven built-in name/reply swatches; Profile
selects one of those seven native swatches plus gray, or resets the profile
background. Both choices save together on Apply. Native profile/message previews
remain in use and the normal unsaved-change confirmation is available for free.
The information row explains that colors apply only on this device, not to
contacts' clients. No unsupported color vCard field or protocol is published.

Local palettes use native avatar/name color definitions and the existing native
PeerColors registry/renderer, with the standard name IDs preserved. XMPP does
not fetch Telegram's help peer-color catalogs. The picker omits gift/resale
sections and Premium purchase buttons and does not load Stars/gifts, request
Telegram default reply icons or send account.updateColor. Its purchase/apply
entrypoints also guard direct or stale callers. Frontend background emoji remains
unavailable here because this native picker depends on a Telegram default-icon
catalog; a local pack picker integration is separate work. Gift/status
collectibles are not supported or silently converted into an XMPP capability.

Metadata is committed to device-private preferences using a hash of account
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
apply both tabs, inspect own profile plus own sender/reply color in a group,
reopen/restart and switch accounts, confirm no purchase UI or Telegram request.
These are self presentation choices; arbitrary per-contact color overrides and
peer-synchronized appearance are not implemented by this pass.
