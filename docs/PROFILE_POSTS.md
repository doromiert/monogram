# Saved profile posts and editable profile fields

## Persistent profile media

Monogram stores saved profile posts on the account-owned PEP node `urn:monogram:profile-posts:0`. This is a Monogram node identifier using the [XEP-0472 Pubsub Social Feed](https://xmpp.org/extensions/xep-0472.html) base data type, `urn:xmpp:pubsub-social-feed:1`, and Atom entries with an enclosure link. It supports both photos and videos. It does not use or reconfigure another client’s microblog or gallery node.

The item ID and original publication date match the source story. Atom carries the author’s `xmpp:` bare address, title, timestamps and the HTTPS enclosure’s URL/type. The node uses presence-subscription access, persistent items, maximum server retention, retraction notifications and payload-free events. Its entries do not expire after 24 hours. Normal stories remain on `urn:xmpp:pubsub-social-feed:stories:0` and continue to expire independently.

Saving publishes the entry before persisting the local pinned flag. Removing from the profile retracts the profile item; deleting a pinned story retracts its profile item too. Caption edits update the saved entry. Archived own media is retained locally. Remote media availability still follows the upload host’s retention policy. No wallet material, OMEMO private data or unrelated PEP state is written.

The native Posts tab queries the feed to discover availability (an empty feed is valid). Refreshes merge and persist their cache in a single batch while retaining stable native IDs and independent active-story flags. The client fetches up to 1,000 profile entries at a time and preserves older cached pins if that limit is reached. Other clients can discover and consume the node as an Atom social feed; clients that do not support the Monogram node may omit it from their own profile UI.

## Public profile fields

My profile → Edit info → XMPP profile fields opens the native vCard editor. Existing own profile-field rows also open it. Standard fields include nickname, full name, about, website, birthday, organization, department, title, role, primary email/phone and postal-address components. Empty values remove the selected property value.

Every write retrieves the current vCard first, modifies only the selected allowlisted path and retains unknown namespaces, attributes, sibling fields and additional email/phone/address entries. Cached PEP avatars are preserved. These are ordinary vCard fields, not a new wire extension.

## Multi-device typing

Live received carbon copies are accepted only through the session’s own-bare-JID validated carbon callback and only when addressed to the current full resource. The inner message may target another resource of the same account, but must still target that account and pass the existing live-message checks. This follows [XEP-0280’s chat-state interaction](https://xmpp.org/extensions/xep-0280.html#rules-chatstates). Sent copies, arbitrary forwarded messages and delayed/archive states do not create peer typing.

The existing XEP-0085 controller state drives the native chat-header subtitle, replacing online/last-seen while typing and restoring it on pause, timeout or disconnect. Telegram’s separate Start secret chat entry is removed; OMEMO is configured through Conversation security on the existing XMPP chat.
