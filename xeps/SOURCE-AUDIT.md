# Source coverage and proposal boundaries

Audited source: Monogram 0.2.195,
`4736f7eddb423192e5d68435879f0b0a88371168` (2026-11-10).
Start with the earlier [non-standard behavior inventory](../docs/protocols/NON_STANDARD_XMPP.md)
and [protocol registry](../docs/protocols/README.md). This pass cross-checked
private namespaces/body prefixes in `xmpp-core` and the Android XMPP adapter,
plus the feature sources linked by each draft. It does not claim every path
conforms perfectly to every base XEP.

| Behavior / source | Coverage | Boundary |
| --- | --- | --- |
| `MonogramPolls` legacy fallback and reaction tally | [Poll draft](xep-reaction-polls.xml), revised proposal 0.0.2 | Deployed body codec is historical evidence; structured XML with optional fallback and OMEMO 2/SCE is proposed and not yet implemented. Public ballots; timestamp closure is not consensus. |
| `MessageMarkup.SPOILER_SPAN` | [Partial spoilers](xep-partial-spoilers.xml) | Existing XEP-0382 handles whole messages; private child adds ranges. |
| `ForwardedContent` | [Forward attribution](xep-forward-attribution.xml) | Incoming XEP-0297 is standard; outgoing readable prefix is a compatibility convention. |
| `MonogramArticles`, `MarkdownParser` | [Markdown articles](xep-markdown-articles.xml) | XEP-0481 hint plus deployed dialect and inference; static checkboxes are not collaboration. |
| `InlineEmoji`, `MonogramEmojis` | [Inline emoji](xep-self-contained-inline-emoji.xml) | XHTML/BoB profile; XEP-0514 exists and uses a different representation. |
| `MonogramPacks` `.xmsp` | [Portable packs](xep-portable-media-packs.xml) | File snapshot, not XEP-0449 PubSub publication or private automatic synchronization. |
| `Suggested profile photo` caption | [Avatar suggestions](xep-avatar-suggestions.xml) | Ordinary attachment/caption; recipient explicitly publishes their own avatar. |
| `XmppStories.PROFILE_NODE` | [Persistent media](xep-persistent-profile-media.xml) | Private node selection/persistence over XEP-0472. Expiring stories themselves use XEP-0501. |
| `MoneroPayment`, `MonogramXmrMessage` | [Payment requests/notices](xep-monero-payment-messages.xml) | External payment URI plus custom readable wrappers; not XEP-0518 invoice compliance. |
| `MoneroClaim` and external restore section | [Bearer wallet links](xep-monero-bearer-wallet-links.xml) | Sensitive independent wallet recovery material; no identity-bound escrow or revocation. |
| `StickerHint`, public spoiler/article hints in legacy OMEMO | [Legacy compatibility](xep-legacy-presentation-compatibility.xml) | Deliberately narrow unauthenticated presentation, never authoritative identity/action metadata. |
| MAM-UID reply aliases, URI-only legacy files, caption companions | [Legacy compatibility](xep-legacy-presentation-compatibility.xml) and [forward attribution](xep-forward-attribution.xml) | Local same-conversation/author resolution over existing IDs/replies; no new reply or caption namespace. |
| `ChecklistContent`, `MonogramChecklists`, unexposed checklist store | [Deferred checklist record](deferred/xep-checklist-codec.xml) | Codec and prototype only. No shipped collaborative application; revision conflicts are not convergent. |
| `urn:monocles:omemo-pq:1`, hybrid PEP/ratchets and MUC adapter | [Deferred upstream profile](deferred/xep-monocles-pq-profile.xml) | Upstream Monocles protocol, separate from standard OMEMO2. Existing upstream proto-draft needs coordination rather than competing authorship. |

## Existing standards — no duplicate XEP invented

| Function | Existing base / implementation notes |
| --- | --- |
| Direct/group messages, rooms, replies, stable IDs | RFC6120/6121; XEP-0045, XEP-0359, XEP-0461 |
| Reactions, corrections and retractions | XEP-0444, XEP-0308, XEP-0424/0425; these supply the actions, not the poll grammar |
| Mentions and `/me` | XEP-0372 and XEP-0245 |
| Typing across resources, carbons, archives | XEP-0085, XEP-0280, XEP-0313; acceptance rules do not create a private typing namespace |
| Standard story posts and Atom payloads | XEP-0501 and XEP-0472; private persistent node covered separately above |
| MIX/spaces, Jingle/MUJI calls | Existing MIX family, XEP-0503 and Jingle/MUJI proposals; native Telegram UI is not a new wire format |
| Contact requests / access control / roster / blocking | Standard presence subscriptions, roster and blocking; local UI/ACL choices do not require a new namespace |
| Profile fields and ordinary avatars | Existing vCard and XEP-0084; preserve unknown fields and sibling entries |
| Cross-device private read state | Existing private read-synchronization behavior described in [READ_STATE_SYNC](../docs/READ_STATE_SYNC.md) |
| Self chat and resource allocation | Ordinary self-addressed messages and RFC6120 resource binding; generated per-install resource IDs are local policy |

See the linked source inventory for exact existing-XEP mappings; this table is a
scope boundary, not a blanket conformance certification. In particular XEP-0514,
XEP-0449 and XEP-0518 overlap with our compatibility formats and must be considered
before proposing competing new protocols.

## Device-local behavior — no wire protocol to standardize

The following remain implementation/UI/storage behavior: Saved Messages tags;
contact aliases, notes and photo overrides; local message pins; drafts; scheduled
send queues; themes, wallpapers and colors; icon-pack ZIPs; emoji rendering;
precision controls; notification modes; cache/history clearing; crash reports;
certificate approval; reconnect notices; wallet management; fiat price lookup;
local fee-cap approvals; and media-window performance limits. A scheduled send
later emits an ordinary message. Neither a local native ID nor an SQLite schema
is a cross-client protocol. Icon packs are application-specific resources, unlike
the portable chat-artwork snapshot in `.xmsp`.

Encryption-mode selection, blind-trust settings, device blocking and daily mode
policy operate over existing transports. They do not create a new cryptographic
format or justify automatically trusting another installation. The PQ upstream
wire profile itself is covered separately.

`MGBACKUP` encrypted backup envelopes and restore scaffolding are local file
formats with incomplete integration, not shipped XMPP synchronization. They must
not be represented as an interoperable backup XEP or as a guarantee that history
can be recreated from current ratchet keys. A future backup/sync protocol would
need its own threat model, identifiers, retention and convergence specification.

## Review and validation record

All 13 XML documents validate against the pinned XSF DTD/entity files. Embedded
XML examples are checked for syntax, JSON examples for JSON validity, manifests
for complete draft coverage, and implementation source links for local existence.
Illustrative placeholders are intentionally not treated as valid signatures,
wallet addresses, hash-bound assets or complete cryptographic test vectors.

The two deferred documents explicitly list missing integration/specification
work. No new namespace, capability or behavior was added to the app by writing
these proposals. No documents were uploaded or messages sent to anyone.
