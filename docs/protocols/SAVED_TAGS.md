# Saved Messages tag model 1

Status: device-local implementation, not a network protocol. Messages use the
account's ordinary XMPP self-chat; labels and membership remain in that account's
local database. No reactions containing private tag names/colors are sent to
contacts, rooms or the server. Cross-device label synchronization is not implemented.

[XEP-0444](https://xmpp.org/extensions/xep-0444.html) reactions recommend a single emoji and allow clients to ignore other
strings. Arbitrary label JSON or text in a standard `<reaction>` therefore is not
an interoperable tagging protocol. No tag-specific XEP was identified in the
review for this implementation.

## Data model

A tag has an emoji identity, optional display name and text color. Existing native
Telegram reaction/tag controls are projections of this model, not its transport.

- Standard emoji key: `e:` followed by the exact Unicode emoji string.
- Custom emoji key: `d:` followed by the positive local custom-emoji document ID.
  That document ID is local to the account/client and must not become a wire ID.
- Name: trimmed text, at most 128 UTF-16 units. Empty removes the displayed name.
- Color: an opaque Android ARGB integer; zero means follow the current theme.
  It affects label text in both the message tag and tag filter strip.
- Membership: unique `(local message ID, tag key)` pairs. Only visible messages in
  the owning account's self-chat may receive these labels. Counts exclude deletion.

SQLite storage uses `saved_message_tags(message,tag)` and
`saved_tag_names(tag,title,color)`. Database version 31 adds `color DEFAULT 0` to
older stores without dropping labels. Account reset clears both tables. This
schema is implementation documentation, not an interoperable sync payload.

Changing the emoji moves every existing membership to the new emoji in one
transaction while preserving the chosen name/color. A destination emoji that
already has a tag is rejected rather than silently merging differently named tags.
The existing per-emoji identity means two tags cannot use the same emoji. Renaming
through the older native API preserves the color. Removing a tag from one message
does not remove its definition or affect other messages.

Tag mutations refresh native message reactions, the tag list/counts and default
tag picker. Changing the emoji updates an active tag search to the new identity.
Reaction-send failure rollback reads the self-chat tag snapshot rather than the
ordinary conversation reaction journal. Editing works independently of Telegram's
unsupported Saved Subdialogs feature flag.

## Future synchronization boundary

A future private synchronization format should use stable opaque tag IDs, Unicode
emoji or content-addressed custom-emoji assets, RGB text colors, versioned names,
and stable self-chat message references. It must define conflicts/deletions,
account authorization, archive replay and encryption before being advertised as
syncing. Do not publish this SQLite schema or local document IDs as that format.
A private/encrypted self-message or carefully access-controlled PubSub node could
carry such a format; neither is implemented by this local-label change.
