# Monogram-specific XMPP behavior

Source audit: 2026-11-09, `feature/pq-omemo` / 0.2.191.

This inventories application conventions that another client cannot implement
from an existing XEP alone. It distinguishes private wire formats, restricted
profiles of standards, compatibility exceptions, and local-only prototypes.
It is implementation documentation, not an XSF specification or an independent
security audit. The [XSF index](https://xmpp.org/extensions/) was checked on the
audit date; absence of a dedicated XEP is a dated finding, not a permanent claim.

## Implemented message and publication conventions

### Reaction polls

The poll definition is a canonical English message body starting with `📊 `,
followed by optional `📝 ` description, emoji-prefixed options, and an exact
single/multiple-choice voting instruction. Votes are ordinary XEP-0444 reaction
replacement sets; the meaning of those emoji as ballots is Monogram-specific.
An author correction ending in `Voting closed.` freezes Monogram's results at
that correction's time. Servers do not enforce closure and other clients can
continue sending reactions. Votes are public to the conversation; anonymous
polls and quiz semantics are not implemented.

Other clients see readable text and can vote with reactions. Legacy OMEMO votes
require consent to expose reaction metadata; OMEMO 2 protects the reaction
payload. No dedicated poll XEP was found in the reviewed index. The exact
grammar, 32-option mapping, bounds and counting rules are in [POLLS](../POLLS.md).
Source: [MonogramPolls.java](../../TMessagesProj/src/main/java/org/telegram/messenger/xmpp/MonogramPolls.java).

### Partial spoilers

Whole-message/media spoilers use XEP-0382. Hiding only a substring adds
`<spoiler xmlns='urn:monogram:spoiler:0'/>` inside an XEP-0394 `<span>`.
Offsets count Unicode code points, not Android UTF-16 units. The text stays in
the original body. Clients that do not recognize the private span can expose
that text; spoiler hiding is not encryption. The private feature is advertised
through service discovery. [Wire example and encryption rules](../SPOILERS.md).
Source: [MessageMarkup.java](../../xmpp-core/src/main/java/com/negzero/monogram/xmpp/MessageMarkup.java).

### Forward attribution fallback

Outgoing forwards use a readable, canonical prefix:

```text
↪ Forwarded from Alice · 2026-10-23T09:00:00Z

Hello
```

The receiver projects this into a native forwarding header. The original
author/date are claims by the forwarding sender, not authenticated original
provenance. Native IDs and TL serialization are not sent. URI-only legacy media
and its caption may be separate messages. Incoming standalone XEP-0297 forwards
are supported, but selecting structured XEP-0297 for outgoing forwards through
capability discovery is not implemented. [Full grammar](FORWARDING.md).
Source: [ForwardedContent.java](../../xmpp-core/src/main/java/com/negzero/monogram/xmpp/ForwardedContent.java).

### Rich articles and Markdown inference

XEP-0481's `text/markdown` content hint selects a Monogram-supported Markdown
profile, not a standardized Telegram article payload. Tables, headings, math,
checkboxes and some HTML-style inline formatting are converted locally to native
rich blocks. Supported structured Markdown can also be inferred without a hint.
Chat quotes specifically require `> ` on each line; unmarked continuation lines
and `>greentext` do not inherit quote formatting. Standalone Markdown documents
retain the ordinary CommonMark rules. The profile does not round-trip all native
layout or embedded widgets; unsupported features are rejected or fall back to
readable source.

Legacy OMEMO encrypts the body but exposes the empty content-type hint, allowing
an intermediary to affect presentation. OMEMO 2 puts the hint in authenticated
content. [Conversion and bounds](../ARTICLE_FORMATTING.md).
Sources: [MonogramArticles.java](../../TMessagesProj/src/main/java/org/telegram/messenger/xmpp/MonogramArticles.java),
[MarkdownParser.java](../../TMessagesProj/src/main/java/org/telegram/ui/Components/MarkdownParser.java).

### Inline custom emoji profile

Monogram's current profile uses XHTML-IM `<img>` replacements and same-message
XEP-0231 BoB assets. Image alt text plus surrounding text must reconstruct the
ordinary body exactly. Assets are hash-verified and bounded; arbitrary remote
HTML/images are not fetched. Other clients can show the fallback or render the
supported XHTML/BoB content. Unsupported/malformed metadata keeps the text.
Legacy OMEMO does not authenticate these outer extensions and uses text fallback.

Custom emoji **do have** an existing Experimental proposal:
[XEP-0514](https://xmpp.org/extensions/xep-0514.html), version 0.1.1, uses
`urn:xmpp:markup:emoji:0` with markup and file/media sharing. Monogram's XHTML/BoB
profile is not an implementation of that wire format. Do not advertise it as
XEP-0514 support. [Exact profile and limits](INLINE_EMOJI.md).
Source: [InlineEmoji.java](../../xmpp-core/src/main/java/com/negzero/monogram/xmpp/InlineEmoji.java).

### Sticker and custom-emoji pack files

`.xmsp` is Monogram's versioned ZIP/JSON snapshot format with a `manifest.json`
and validated, content-addressed assets. The suggested
`application/vnd.monogram.xmsp+zip` media type is unregistered. Sharing uses the
normal attachment pipeline; XEP-0454 can protect the entire file. The archive
itself is plaintext once decrypted/exported. Importing it does not publish a
PEP pack, subscribe to updates or allow remote revocation of copies.

[XEP-0449](https://xmpp.org/extensions/xep-0449.html) already defines stickers
and PubSub sticker packs. `.xmsp` is a separate file-interchange convention,
not that standard's pack-discovery format. [Container specification](../XMSP.md).

Legacy encrypted sticker messages also accept an empty
`<sticker xmlns='urn:xmpp:stickers:0'/>` alongside the protected file reference
as a presentation hint. It is public and removable/forgeable, never a trusted
source of file URLs, identity or decryption keys. The received file must still
be a valid encrypted attachment. This restricted legacy handling should not be
confused with authenticating all XEP-0449 metadata inside legacy OMEMO.
Source: [StickerHint.java](../../xmpp-core/src/main/java/com/negzero/monogram/xmpp/StickerHint.java).

### Avatar suggestions

An ordinary image message captioned `Suggested profile photo` conveys a
suggestion. Adopting it is an explicit recipient action which then publishes
their own avatar normally. There is no dedicated Monogram suggestion stanza,
automatic avatar replacement, or stronger author guarantee than that message's
normal transport. Other clients see an image and caption.
[Local contacts](../LOCAL_CONTACTS.md); sender:
[MonogramController.java](../../TMessagesProj/src/main/java/org/telegram/messenger/xmpp/MonogramController.java).

### Persistent saved profile posts

`urn:monogram:profile-posts:0` is an account-owned PEP node carrying XEP-0472
Atom social-feed entries with media enclosures. The node selection, persistent
profile semantics and linkage to saved stories are Monogram-specific. Entries
do not expire after the normal story window; upload-host retention still applies.
Access uses presence subscriptions, not an assurance of end-to-end encryption.
Other clients may consume the feed but need to know this private node identifier.
[Profile posts](../PROFILE_POSTS.md); source:
[XmppStories.java](../../xmpp-core/src/main/java/com/negzero/monogram/xmpp/XmppStories.java).

Normal expiring stories use [XEP-0501](https://xmpp.org/extensions/xep-0501.html),
not this private profile node. Stories should not be listed as wholly proprietary.

### Monero requests, notices and bearer links

These are ordinary message-body conventions; the native card is only a local
projection. Requests use `XMR payment request` followed by a validated `monero:`
URI. The Monero URI is external to XMPP; Monogram's body wrapper/card behavior is
not a dedicated XEP. Accepted broadcasts can produce `XMR payment broadcast`
plus a `monogram-xmr:transfer?v=1&payment=...&txid=...` notice. A notice is a
sender report, not blockchain proof or permission to repeat the transaction.

Funded unknown-recipient payments use `monogram-xmr:claim?v=1&network=...&height=...`
with the independent claim-wallet mnemonic encoded in the URI fragment. That
fragment is a bearer secret. It must never be included in examples, logs or
plaintext fallback, and must never be confused with the main wallet's seed.
Sender and recipient can deposit the still-available funds into a chosen wallet;
confirmation, fees and blockchain unlock rules still apply. External claiming
uses the documented recovery path; recognizing a link does not authorize spending.

[XEP-0518](https://xmpp.org/extensions/xep-0518.html) defines service payment
challenges/invoices. Monogram's request/transfer/claim wrappers do not implement
that invoice protocol. [Exact formats and security boundary](MONERO.md).
Sources: [MoneroClaim.java](../../xmpp-core/src/main/java/com/negzero/monogram/xmpp/MoneroClaim.java),
[MoneroPayment.java](../../xmpp-core/src/main/java/com/negzero/monogram/xmpp/MoneroPayment.java).

### Experimental Monocles PQ encryption

`urn:monocles:omemo-pq:1` is an upstream Monocles experimental profile with its
own device/bundle PEP nodes, hybrid identities, trust approvals and ratchets.
It is not standard XEP-0384 OMEMO 2, and is not a Monogram-designed cryptosystem.
Monogram exposes the pinned profile as PQ OMEMO2 for direct chats and members-only,
non-anonymous MUCs. It is not selected automatically and never silently falls back.
MUC delivery uses the same device sessions and hybrid trust as direct chats;
room destinations and captured real sender identities are authenticated. MIX is
not enabled for this mode.
Older protocol revisions are not automatically compatible. Other clients need
the matching experimental protocol, not just ordinary OMEMO 2 support.
See [pins, interoperability evidence and limitations](../pq-omemo.md).

## Compatibility exceptions and client policy

* **MUC replies:** new replies prefer the room stanza ID. The receiver also
  resolves older clients' references to a retained MAM UID when the same room,
  claimed occupant and a unique target match. Ambiguous aliases or wrong authors
  do not invent a reply. This compatibility lookup is not a new reply namespace
  or a reason to send new replies with local/native IDs.
* **Legacy OMEMO presentation:** its body-only protection means public hints
  cannot gain the authority of decrypted message content. The hint exceptions
  above are deliberately restricted. They do not authenticate actions or keys.
* **Automatic encryption and recipient skipping:** per-chat daily selection,
  room-device omission and blind-trust toggles are Monogram policies over
  existing encryption transports. They are not new standards or ATM guarantees.
  [Daily selection](../DAILY_ENCRYPTION.md), [device review](../OMEMO_DEVICE_REVIEW.md).
* **TLS exceptions:** explicit local certificate approval is a trust decision,
  not a new server protocol or global disabling of verification.
  [Certificate policy](../SELF_SIGNED_CERTIFICATES.md).
* **Resource allocation:** each account installation now keeps a generated
  `Monogram-<UUID>` resource to prevent clients replacing each other's stream.
  This uses ordinary RFC 6120 resource binding, not a proprietary extension.

## Codecs and prototypes that are not shipped protocols

* **Collaborative checklists:** `urn:monogram:checklist:1` contains versioned
  document/update codecs (`MCL1` document magic) and a draft store. The live
  controller/store send and mutation hooks are not integrated. Current article
  checkboxes are readable Markdown, not synchronized checklist objects.
  [Codec draft](CHECKLISTS.md).
* **Portable account backups:** the `MGBACKUP` encrypted envelope, logical
  snapshot/restore adapters and owner-bound recovery manifest are local file
  formats, not XMPP stanzas. A coherent exported backup/settings/cold restore
  flow is not shipped. Their existence must not imply all current history or
  experimental PQ state can be restored. [Status](../ENCRYPTED_BACKUPS.md).

## Local features with no private wire protocol

Saved Messages tags, contact aliases/notes/photo overrides, message pins, drafts,
scheduled-send queues, themes/wallpapers, name/profile colors, custom UI icon
packs, precision preferences, crash reports, local history clearing and wallet management are local state. They must
not be advertised as cross-device XMPP synchronization. Scheduled delivery uses
an ordinary message when the local scheduler runs. Saved Messages itself is an
ordinary self-chat; its local tag metadata is not sent as reactions.
[Saved tags](SAVED_TAGS.md), [drafts](../DRAFTS.md), [schedules](../LOCAL_SCHEDULES.md),
[chat appearance](../CHAT_APPEARANCE.md), [profile colors](../PROFILE_COLORS.md).
See also [local history clearing](../LOCAL_HISTORY_CACHE.md) and
[crash reports](../CRASH_REPORTS.md); neither introduces a network protocol.

Calls/MUJI, MIX, server-side spaces, mentions, `/me`, replies, reactions,
retractions, content hints, private read synchronization and expiring stories
already have related XEPs; native Telegram-looking UI does not make them private
protocols. In particular [XEP-0503 spaces](https://xmpp.org/extensions/xep-0503.html)
must not be presented as an invented nested-group protocol. This audit does not
claim complete conformance to every feature of any of these XEPs.

## Maintenance and interoperability

The namespace/body-string inventory covered `xmpp-core`, Android's XMPP adapter,
and the referenced feature specifications. A feature is not considered shipped
merely because a codec, unit test or old design document exists. New formats must
update this inventory and their exact grammar, discovery rules, bounds, author
checks, encryption placement, unsupported-version handling and readable fallback.
Do not silently rename an existing private identifier or export native TL IDs.
Private identifiers and schemes above are not XSF/IANA registrations. Unknown
clients must remain able to display the ordinary body or ignore optional metadata.
