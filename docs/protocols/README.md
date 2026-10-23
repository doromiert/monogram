# Monogram protocol and compatibility registry

This is the publication entry point for Monogram-specific formats. These are
implementation documents and draft proposals, **not XSF-approved XEPs**. Reusing
an XEP transport does not make our application convention part of that XEP.
Published formats must retain their versioned grammar and include interoperability
and security rules. Existing wire identifiers must not be silently renamed.

| Feature | Base standards | Monogram-specific part | Specification / implementation status |
| --- | --- | --- | --- |
| Reaction polls | XEP-0444 reactions, XEP-0359 IDs, XEP-0308 correction | Canonical readable poll definition, emoji-to-option map, public vote/closure rules | [Polls](../POLLS.md), implemented |
| Forward attribution | XEP-0297 stanza forwarding | Full-address readable fallback, native unverified attribution, URI-only legacy media plus separate caption | [Forwarding](FORWARDING.md), incoming standard + outgoing fallback implemented |
| Partial spoilers | XEP-0394 markup, XEP-0382 whole-message spoilers | `urn:monogram:spoiler:0` span child | [Spoilers](../SPOILERS.md), implemented |
| Checklists | XEP-0394 markup and XEP-0481 content hints | `urn:monogram:checklist:1` document/update codecs | [Checklist draft](CHECKLISTS.md), codecs exist; collaborative send/application is not integrated |
| Saved Messages tags | XMPP self-chat for messages | Account-local emoji/name/text-color definitions and message membership | [Saved tags](SAVED_TAGS.md), local only; no tag wire protocol or cross-device sync |
| Inline custom emoji | XHTML-IM and XEP-0231 BoB | Strict self-contained, hash-verified fallback-image profile | [Inline emoji](INLINE_EMOJI.md), implemented |
| Avatar suggestions | Ordinary file-sharing messages | Suggested-profile-photo caption and explicit recipient adoption | [Local contacts](../LOCAL_CONTACTS.md), implemented compatibility convention |
| Portable stickers / custom emoji | ZIP + JSON; XEP-0454 when encrypting an exported file | `.xmsp` manifest, asset validation and limits; suggested unregistered media type | [XMSP](../XMSP.md) |
| Article formatting | XEP-0394, XEP-0481 | Mapping rich editor features to bounded Markdown fallbacks | [Article formatting](../ARTICLE_FORMATTING.md) |

Stories use the existing XEP-0501 representation rather than a Monogram namespace;
see [stories](../STORIES.md). Mentions use XEP-0372; see [mentions](../MUC_MENTIONS.md).
Local contact overrides, local pins, drafts, themes and read state are application
behavior, not automatically new wire extensions. Their implementation notes stay
in the main docs directory.

## Publication checklist

Each proposed format should identify its owner, version, exact grammar, byte and
Unicode offset rules, examples, discovery requirements, failure behavior,
privacy/encryption boundary, identity/authorization rules and compatibility path.
Separate shipped behavior from codec-only drafts and future ideas. Include
executable vectors or linked regression cases before proposing external adoption.
Use stable room-assigned IDs for MUC references; never export local Android/TL IDs.

`urn:monogram:*` identifiers and `application/vnd.monogram.xmsp+zip` are private,
unregistered identifiers. They are not reserved XMPP/XSF or registered IANA names.
An eventual XEP submission needs its own assigned identity and migration story;
existing Monogram implementations must continue recognizing their older versions.
