# Camera and room messaging regressions (0.2.130)

## Camera FAB

Expansion no longer waits for CameraView.isInited(): hardware startup runs alongside
Telegram's existing expansion. Capture/record remains guarded until initialization.
Duplicate taps do not start duplicate transitions. Open/close animators are owned,
cancelled on release and bound to the preview instance that created them. Release
resets the request/expanded/accessibility state even when no camera view remains.

Old previews now detach immediately instead of after 300 ms. CameraView releases
each session only once across explicit destroy and TextureView surface destruction.
The prior double-close could otherwise enqueue another release against shared
CameraInfo after the next camera opened. Disposed views reject late UI startup
callbacks. No eager background camera warm-up is added.

Two hardware-free checks execute the real explicit/surface teardown paths in both
orders, including two camera sessions and an independent reopened camera. They do
not constitute device camera stress testing. The attempted phone reproduction was
interrupted by app backgrounding; no photos or recordings were made.

Chat Settings → Chat behavior → Use system camera exposes the existing persistent
SharedConfig.inappCamera preference, inverted for the setting label. The FAB uses
the existing external-camera Intent and result handling when enabled. The default
remains Monogram's camera. This setting applies to all accounts.

Message-context actions now read Diagnostics, Favorite, Save and Bookmark. Global
Saved Messages navigation and unrelated gallery settings retain their names.

## Native room replies

XEP-0461 requires room-assigned stanza IDs for group replies. A missing ID during
sync is not proof that the room lacks this feature. Room discovery now records a
tri-state result: supported, explicitly unsupported, or unknown. Quote-only sending
requires an explicitly unsupported room plus no available canonical target ID.
Otherwise a missing target ID surfaces as reply unavailable for retry, rather than
silently changing a structured reply into body text. A known canonical ID remains
usable even if the actor cache is absent. Direct-message behavior is unchanged.

## Room retractions

Live opt-in XML captured a Monocles plaintext retraction with the original room
stanza ID and XEP-0421 occupant ID. Monogram previously rejected plaintext controls
for encrypted originals before applying them. Room controls now honor the existing
strict original-author/occupant and unique-reference checks without requiring that
a plaintext retraction share the original message's encryption protocol.

Outgoing room retractions use plaintext control metadata, matching the observed
Monocles stanza. They do not expose the original message text or wait for OMEMO
devices. Room echo confirmation and author/occupant validation remain required.
Encrypted DM retractions and encrypted control fingerprint checks are unchanged.
This is author-bound deletion of one's own content, not moderation of other users.

The focused tests retain wrong-author, wrong-occupant, ambiguous-ID and encrypted
DM/key-mismatch rejection, and add plaintext room retraction of an encrypted
original by its author. Existing pending room retractions can reconcile on refresh.

References: https://xmpp.org/extensions/xep-0461.html#businessrules-id and
https://xmpp.org/extensions/xep-0424.html#businessrules
