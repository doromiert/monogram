# Saved Messages

Saved Messages uses the account's XMPP self-chat. Open it from the existing Saved Messages navigation, use Bookmark in a message's context menu, or forward a message to Saved Messages. Text and media follow the existing native compose/forward/upload pipeline. Completed messages use the account's durable message database; queued and failed messages use the native durable outbox.

Telegram represents this conversation with `TL_inputPeerSelf`, which serializes without a numeric ID. Monogram's adapter previously read its default zero ID, rejecting sends and selecting an incorrect history/search scope. `MonogramSelfChat` assigns the current account's local peer ID while retaining the native self-peer type. Resolution happens when building native peers and at request/queue boundaries, including text, media/location, history, search, edits, reactions and translation. Ordinary users, rooms and global search keep their IDs and scope.

The self-chat is included in the chat list when it has messages, in the initial per-conversation archive sync, and in older-history/search pagination. It uses the existing bounded history loading and message deduplication paths. No new storage schema, key migration or secondary send queue is added.

Synchronization uses the selected XMPP encryption mode and the server's ordinary message/archive support. OMEMO excludes the sending device from recipient encryption, so encrypted sending requires another eligible, trusted device of this account. This change does not auto-trust devices or silently downgrade to plaintext. A standalone offline-only notebook or a new encrypted personal-storage protocol is not implemented. A new device cannot decrypt older OMEMO messages that were never addressed to it.

Validation for 0.2.132: focused self-peer account scoping, queued text/media/edit resolution, signed-out validation, saved text/media reopen/search/delete isolation, existing forwarding, and native-outbox merge checks. Phone UI acceptance, real self-chat encrypted delivery and inter-device archive recovery remain pending. The app was not opened and no real messages were sent during delivery.

Local tag editing, colors, membership and synchronization limits are specified in [Saved Messages tags](protocols/SAVED_TAGS.md).
