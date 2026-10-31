# MIX channels and spaces

Open **New conversation → Spaces and MIX channels**. MIX conversations use Monogram's normal native chat screen, attachment picker and message storage.

## Channels

Monogram implements [MIX core (XEP-0369)](https://xmpp.org/extensions/xep-0369.html), namespace `urn:xmpp:mix:core:1`, and [MIX-PAM (XEP-0405)](https://xmpp.org/extensions/xep-0405.html), namespace `urn:xmpp:mix:pam:2`.

- Discover MIX services, browse their available channels, or enter a full channel address.
- Create channels where the service advertises permission, join, change nickname, inspect participants and leave.
- Read and send messages, replies, formatting, attachments, corrections and reactions using the existing group message machinery.
- Retrieve channel history through MAM, including legacy OMEMO recovery through the existing durable incoming journal.
- Keep server-persistent membership across reconnects. Leaving applies across clients. Closing Monogram does **not** leave a MIX channel.
- Restore server-side membership and stable participant IDs from annotated rosters. Ordinary contact-roster reloads are followed by an annotated reload, without replacing Smack's contact parser or rewriting profile/key PEP nodes.

The account's own server must advertise MIX-PAM, and the channel service must advertise MIX core:1. A remote MIX service alone is insufficient. An unsupported account gets a specific error rather than a broken MUC join attempt.

MIX channels and MUC rooms have distinct persisted transport flags. MUC bookmarks cannot overwrite MIX membership. MIX sender metadata is used only for known MIX channels; sender-provided MIX elements cannot change identity in an ordinary MUC.

MIX replaces the sender's stanza ID with a server archive ID. Monogram retains that ID and an author-scoped origin alias so local sends, live echoes and history resolve to one message. Historical messages without a participant resource do not fabricate a stable participant identity from today's membership list.

### Encryption

MIX uses the existing group encryption policy and legacy OMEMO implementation, including shared DM/device trust decisions, recipient exclusions and current trust prompts. Encrypted sending fetches the full participant list, follows pubsub pagination, requires visible real addresses, and checks membership again before transmitting. A partial, malformed or hidden-address participant list cannot be used as a partial encryption recipient list.

This change does not add OMEMO 2 group encryption. As with other group conversations in this version, unsupported incoming OMEMO 2 ciphertext remains an explicit unreadable encrypted message. Plaintext sending remains controlled by the conversation's existing encryption policy; joining a space does not change it.

## Spaces and nesting

The grouping layer uses [Server-side spaces (XEP-0503)](https://xmpp.org/extensions/xep-0503.html), version 0.3.0, rather than private Monogram XML. A space is a pubsub leaf node with type `urn:xmpp:spaces:0`.

```text
Pubsub service
└── Community space
    ├── general@mix.example.org
    ├── help@mix.example.org
    └── Development space
        ├── android@mix.example.org
        └── desktop@conference.example.org
```

Use a pubsub service address and space node to open an existing space. The browser can discover space nodes, create a private or public space, follow/unfollow it, add existing channels, create a MIX channel and add it, add nested spaces, remove directory entries and grant or revoke directory access. Server permission errors are shown without changing another channel's membership. Supported directory entries are standard XEP-0402 conference bookmarks and XEP-0330 pubsub subscriptions; unsupported payloads are ignored rather than displayed as raw XML. Nesting cycles and excessive navigation depth are blocked.

A MIX service is **not necessarily a generic pubsub service**. Spaces can live on a separate pubsub service and refer to MIX or MUC channels elsewhere. This does not require modifying the MIX server to add proprietary child-chat nodes.

New spaces default to `whitelist` access and publisher-restricted writes. Public readability requires choosing **Public space**. Following a directory does not join every child conversation or grant access to it. Directory permissions and channel permissions remain independent, as specified by XEP-0503. The space's own server enforces permissions for edits and grants. Following a space is remembered on this device; the server retains the pubsub subscription.

## Verification and limits

Focused tests exercise actual Smack providers, IQ collectors, PAM join/leave, roster annotations, paginated participants, message identities, pubsub space payloads and node creation through an in-memory transport. Android SQLite tests cover upgrading existing MUC history, persisted MIX identities, account cleanup, shared contact identities and live/history deduplication. Existing group recipient and bundle-lookup tests are also run.

No live MIX deployment or phone UI has been tested for this change. MIX presence sharing, MIX-ADMIN policy editors, token invitations and OMEMO 2 group encryption are not implemented here. Existing MUC support is retained.
