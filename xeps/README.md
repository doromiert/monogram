# Monogram XEP drafts

Unsubmitted, unnumbered **ProtoXEP author drafts**, written against Monogram
**0.2.195**, commit `4736f7eddb423192e5d68435879f0b0a88371168`, on
2026-11-10. They describe the deployed formats rather than silently inventing
new wire namespaces. They are not approved standards, registrations or an
independent security audit.

The XML follows the [XSF template](https://github.com/xsf/xeps/blob/4132c531487fe9450ae90b905b8df950d0d478b2/xep-template.xml)
and [XEP-0143](https://xmpp.org/extensions/xep-0143.html). See
[SOURCE-AUDIT.md](SOURCE-AUDIT.md) for complete coverage, existing-XEP overlaps,
implementation-only behavior and deferred research.

| Draft | Type | Editorial scope |
| --- | --- | --- |
| [Readable reaction polls](xep-reaction-polls.xml) | Informational | Exact text grammar, emoji map, replacement ballots, author closure and timestamp/convergence limitations |
| [Partial spoilers](xep-partial-spoilers.xml) | Standards Track | Code-point span child, discovery, schema and protected-content rules |
| [Forward attribution](xep-forward-attribution.xml) | Informational | Readable prefix and unverified provenance; interaction with XEP-0297 and encrypted files |
| [Markdown articles](xep-markdown-articles.xml) | Informational | Bounded chat dialect over XEP-0481, formatting precedence, explicit per-line quotes and safe fallback |
| [Self-contained inline emoji](xep-self-contained-inline-emoji.xml) | Informational | Text-equivalent XHTML/BoB profile; explicitly distinct from existing XEP-0514 |
| [Portable media packs](xep-portable-media-packs.xml) | Informational | `.xmsp` ZIP/JSON snapshot, validation, atomic import and encrypted sharing |
| [Avatar suggestions](xep-avatar-suggestions.xml) | Informational | Ordinary captioned image plus explicit adoption; no remote profile-write authority |
| [Persistent profile media](xep-persistent-profile-media.xml) | Standards Track | Separate PEP node, Atom entries, presence access, persistence and retraction |
| [Monero requests/notices](xep-monero-payment-messages.xml) | Informational | Payment URI wrapper, exact amounts and sender-reported transaction metadata |
| [Monero bearer wallet links](xep-monero-bearer-wallet-links.xml) | Informational | Separate claim-wallet recovery material, encrypted transport and spending/revocation limits |
| [Legacy presentation compatibility](xep-legacy-presentation-compatibility.xml) | Informational | Narrow untrusted outer hints around body-only encrypted content; existing reply/caption compatibility |

The informational documents specify compatibility conventions over existing
standards. They are useful for independent implementers but should not all become
new Standards Track protocols. For example, existing emoji/sticker/forwarding
work may be a better home for the corresponding profile than a separate XEP.
Polls stay informational here because their deployed timestamp-based closure and
reaction tie ordering do not establish a globally convergent election protocol.

## Deferred research — do not upload as complete protocols

| Draft | Why it is separate |
| --- | --- |
| [Checklist codec](deferred/xep-checklist-codec.xml) | Not shipped; current concurrent mutation rule can diverge. Needs convergence, permissions, complete application integration and independent vectors. |
| [Monocles PQ compatibility](deferred/xep-monocles-pq-profile.xml) | Upstream cryptographic work already has a [protocol draft](https://codeberg.org/monocles/monocles_chat/src/commit/49c0e78045532a78033ffd448ca4316859267bf2/docs/proto-xep-omemo-pqxdh.md). Coordinate with its authors; this adapter record is not a new Monogram cryptosystem or a complete independent crypto specification. |

Both files are included so the inventory has no undocumented custom envelope,
while clearly separating research from submission candidates.

## Before uploading

1. Review the drafts and choose the public author/contact details you want.
   Metadata currently uses the existing handle `doromiert` and public GitHub URL;
   no legal name, email address or private account identifier was invented.
2. Review the [XSF IPR policy](https://xmpp.org/about/xsf/ipr-policy/) and the
   template legal notice. The notice is the XSF template entity; drafting it
   does not mean the XSF has accepted the proposal or that someone has accepted
   legal terms on your behalf.
3. Discuss overlap with the cited existing XEPs and upstream authors. Keep the
   two deferred research files out of an initial submission of complete protocols.
4. Follow the [current XSF submission process](https://xmpp.org/about/standards-process/).
   New proposal XML goes into the XSF repository's `inbox/` directory. Its editor
   assigns numbers and short names; retain `xxxx`, `ProtoXEP` and
   `NOT_YET_ASSIGNED` until then. A proposal uses the repository's common
   `xep.dtd`, `xep.ent` and `xep.xsl`; this folder's validation manifest/tools
   are not themselves XEP submissions.
5. Preserve private deployed identifiers until a reviewed migration exists.
   No `urn:monogram:*`, `urn:monocles:*`, URI scheme or media type has been
   registered by creating these files.

Examples use reserved `example.org` domains and deliberately invalid placeholders
for keys, payment addresses, hashes and claim secrets. **There is no live recovery
phrase or funded bearer wallet in this folder.** Shape examples marked as such
are not executable cryptographic/financial vectors.

## Validation

Requires Python 3.11+ and Java 21+:

```sh
python3 xeps/validate.py
# Or select the Java executable explicitly:
python3 xeps/validate.py --java /path/to/jdk-21/bin/java
```

The validator checks the manifest, all 13 drafts against the real XSF DTD,
embedded XML/JSON example syntax, and the pinned implementation source links.
It fetches `xep.dtd` and `xep.ent` from the immutable XSF revision recorded in
[xsf-tooling.json](xsf-tooling.json), verifies their SHA-256 hashes and caches
them under ignored `.state/xep-authoring/`. Subsequent runs use that cache.
The downloaded files retain their upstream copyright/license notices. Java's
validator resolves only those two expected local resources.

DTD validity establishes document structure, not protocol correctness, legal
approval, registration, complete interoperability or security. Source review
establishes the implementation correspondence; outstanding limitations remain
explicit in the drafts. This is a documentation change and does not change the
APK or require installation. Nothing has been sent to the XSF on your behalf.
