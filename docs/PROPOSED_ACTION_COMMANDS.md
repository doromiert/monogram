# Proposed action-command presentation profile

This is a local Monogram proposal, not an assigned or approved XEP. It extends
body-text presentation alongside [XEP-0245](https://xmpp.org/extensions/xep-0245.html),
which specifies only `/me `. No new XML namespace or discovery feature is used:
these are ordinary message bodies, not a new stanza payload or server operation.

Match the exact case-sensitive prefix at the beginning of an eligible plain text
message. Preserve the original body in transport, history, encryption, copy and
editing. Presentation uses the same centered italic action cell as `/me`:

| Stored/sent body | Display label (sender Alex) |
| --- | --- |
| `/me waves` | `* Alex waves` |
| `/you wave` | `* you wave` |
| `/nar The room falls silent.` | `The room falls silent.` |

`you` is literal lowercase text on every device and in both directions. Narrator
text has no actor or asterisk prefix. Preserve Unicode and subsequent newlines.
A command requires its trailing ASCII space: `/you`, `/YOU wave`, `/you\twave`,
` /nar text`, `/narrator text` and embedded commands remain ordinary messages.
Use existing `/me` eligibility rules; do not reinterpret media captions,
forwarded messages, undecrypted placeholders or real service events.

Examples use the existing `jabber:client` stanza namespace:

```xml
<message xmlns='jabber:client' to='peer@example.invalid' type='chat'>
  <body>/you wave</body>
</message>
<message xmlns='jabber:client' to='room@example.invalid' type='groupchat'>
  <body>/nar The room falls silent.</body>
</message>
```

All selected encryption modes wrap the unchanged command body just as ordinary
text. Receiving, archived, queued and corrected bodies use the same presentation
rules. Clients without this proposal show the literal body, providing a readable
fallback; no capability probing, negotiation, downgrade or special error reply
is needed. Validation, length limits, message identifiers, replay/deduplication
and sending failures remain those of ordinary text messages.

These are user-authored chat messages, never authoritative narrator/system
notifications. Keep actual sender/provenance in the underlying message and
existing message-details actions. Do not generate presence, membership events,
XML, executable content or trusted markup from narrator text. Escaping and
rendering follow the existing plain-text action path. No extra metadata leaks
outside encryption. There are no protocol-version fields; changes to these
literal prefixes or meanings require a revised profile on both clients.

Regression vectors cover the display labels above, both directions, multiline
Unicode, exact-prefix rejection and original-body preservation. Desktop uses
production action helpers; mobile verifies the MessageObject service-cell type
and italic span across recorded encryption modes. These checks exercise
presentation, not new live network protocol behavior.
