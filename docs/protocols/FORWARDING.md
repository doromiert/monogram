# User-forward attribution: compatibility profile 1

Status: implemented in 0.2.156, with the media-compatibility correction in 0.2.157. This document distinguishes
standard forwarding from Monogram's outgoing compatibility convention.

## Existing standard

[XEP-0297](https://xmpp.org/extensions/xep-0297.html) defines `urn:xmpp:forward:0`:

```xml
<message xmlns='jabber:client' from='bob@example.org/phone' to='carol@example.org' type='chat'>
  <body/>
  <forwarded xmlns='urn:xmpp:forward:0'>
    <delay xmlns='urn:xmpp:delay' stamp='2026-10-23T09:00:00Z'/>
    <message xmlns='jabber:client' from='alice@example.org/laptop' type='chat'>
      <body>Hello</body>
    </message>
  </forwarded>
</message>
```

The outer sender is Bob. Alice's identity/content is Bob's claim, not an
authenticated message from Alice. XEP-0297 requires distinct rendering and forbids
processing the inner stanza as a new ordinary stanza. A forwarded retraction,
reaction, receipt, chat state, identity hint or stanza ID must never acquire the
original sender's authority.

Monogram advertises the standard feature and renders one standalone forwarded
message received in cleartext or authenticated OMEMO2 content. It preserves an
outer annotation as visible text. Inner IDs and actions are not processed. Nested
forwards and non-message stanzas are not interpreted. A missing original timestamp
uses zero/unknown. Legacy OMEMO outer extensions cannot supply private attribution.

## Outgoing compatibility body

XEP-0297 recommends discovering support before sending standalone structured
forwards, and permits plain-text forwarding for other clients. Monogram currently
uses that body compatibility path for **all outgoing forwards**. Outbound structured
XEP-0297 selection/discovery is not implemented. This also works in mixed-client
MUCs and legacy OMEMO, which encrypts only the body.

The exact canonical form is:

```text
↪ Forwarded from Alice · 2026-10-23T09:00:00Z

Hello
```

The prefix is U+21AA, U+0020, `Forwarded from `, the sender label, U+0020 U+00B7
U+0020, a strict UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` timestamp, and two LF characters.
The remainder is the payload and may be empty. Fixed English words are wire
syntax; they must not be localized. A parser must round-trip the complete prefix
exactly and reject invalid dates, control/bidi override characters in the label,
or a header exceeding 4096 UTF-16 units. Legacy display labels are trimmed and at
most 128 Unicode codepoints. From 0.2.157, known entity JIDs are preserved in full
without truncation (up to 3071 UTF-16 units, also subject to JID validation).
Timestamps represent nonnegative signed-32-bit Unix seconds.

The native UI removes only this prefix, shifts native UTF-16 entity offsets by
its UTF-16 length and displays a `from_name` forwarding header. Known XMPP addresses open a full-address/copy
dialog instead of Telegram's inaccurate hidden-account notice.
XMPP markup continues using Unicode codepoint offsets on the complete wire body.
The outer sender, encryption fingerprints, receipts, replies and IDs retain their
usual meaning. A hand-typed matching prefix is indistinguishable from a forwarded
claim; the display must never imply verified authorship.

The original date and label survive history/reload, retries and re-forwarding.
Choosing Telegram's Hide Sender / send-as-copy action intentionally omits them.
New forwards use the original full XMPP entity address whenever it is known,
resolved from the stored sender identity rather than a roster name or local alias.
Known MUC occupant addresses remain room/nickname addresses; a private real JID is
never guessed from a nickname. Re-forwarding retains an existing original address.
Older name-only forwards cannot acquire a missing original address automatically;
they retain their label without claiming that the account was hidden. No receiver,
local message ID, fingerprint or original attachment key is added by attribution.

## Media, polls and encryption

Forwarded files are downloaded/verified locally and uploaded afresh for their new
destination. Original key-bearing URLs are never reused. For cleartext/OMEMO2 file
sharing the header prefixes the caption, before XEP-0447's URL fallback. For legacy
OMEMO the file body MUST remain exactly the `aesgcm` URI (or URI plus a thumbnail
as permitted by XEP-0454). Version 0.2.156 incorrectly prefixed attribution to this
body; strict Monocles/Conversations-compatible receivers consequently displayed
text instead of an attachment. Version 0.2.157 restores the original media syntax.

Legacy attribution goes in the existing separate encrypted caption message. Its
body is the canonical forward header followed by the user's caption, or the
localized ordinary text "Forwarded attachment" when there is no caption. It
contains no file URL/key. The recipient receives a normal file followed by a
readable attribution/caption; supporting Monogram clients render the caption's
native forward header. This does not merge private attribution into the received
file itself. No unauthenticated outer XML is used to claim a private original sender.

The sending device stores a `local_forwarded_messages(message,sender,date)` record
for its sent file's header, preserving the full JID across reload without changing
the transmitted/archived file body. This is local presentation data, not a new wire
format. Database version 32 adds the table and account reset clears it. Other
devices learn the readable companion caption, not this local record. A received
0.2.156 prefixed file remains readable in Monogram for backward compatibility;
fixing its old copy in another client requires a fresh forward.

### Sending-device presentation correction after 0.2.157

The sending device now records an explicit local outbox link from the file to its
generated legacy caption. Once the caption is saved successfully, Monogram renders
one attachment bubble with one forwarding header and the user's real caption.
The synthetic "Forwarded attachment" placeholder is omitted from that bubble.
Queued or failed caption sends remain visible and retryable until successful.

Database version 33 adds `local_media_captions(message,companion,placeholder)`.
Both original wire bodies and message IDs remain intact in the canonical store;
the local link changes presentation, not encryption or server history. Timeline,
calendar, pagination and search omit the linked companion, and search matches real
caption text on the attachment. Account reset clears the links. A local compound
attachment deletion keeps its companion hidden rather than resurfacing a dummy
caption bubble.

Migration recovers an old synthetic placeholder only when it is the immediately
following message in the same chat, is outgoing legacy OMEMO within 30 seconds,
and exactly matches the file's known local forwarding label/date and the English
or current localized placeholder. Other captions and ambiguous neighbors remain
separate. This is deliberately limited recovery, not a general adjacency matcher.

These links are local to the sending device. Other clients/devices still receive
the compatible file plus readable caption; no remote merge or authenticated
association is implied. A received-caption merge would require its own explicit
interoperable association instead of guessing from neighboring messages. This
correction adds no wire extension or unauthenticated attribution metadata.

Sticker attributes and spoilers follow the normal media path.

Poll definitions and static location bodies are wrapped without changing their
inner canonical format. Native projection removes the forward prefix before
interpreting them. A forwarded poll starts a new vote; old ballots and identities
are not copied.

For legacy OMEMO the compatibility header lives inside the encrypted text body
or separate encrypted media-caption body, never inside the file URI message.
For OMEMO2 it lives in authenticated protected content. No cleartext attribution
extension is attached to encrypted outer stanzas. Other clients see the readable
header; Monogram recognizes the same protected body after decrypting it.

The convention is not a cryptographic signature, an XSF extension namespace or a
way to forge the authenticated stanza sender. Its use of visible text deliberately
keeps unsupported clients useful without duplicating an inner original alongside a
plain-text fallback in a standalone XEP-0297 stanza.
