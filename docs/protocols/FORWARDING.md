# User-forward attribution: compatibility profile 1

Status: implemented for the next release after 0.2.155. This document distinguishes
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
or a header exceeding 512 UTF-16 units. Labels are trimmed, at most 128 Unicode
codepoints; timestamps represent nonnegative signed-32-bit Unix seconds.

The native UI removes only this prefix, shifts native UTF-16 entity offsets by
its UTF-16 length and displays a non-clickable `from_name` forwarding header.
XMPP markup continues using Unicode codepoint offsets on the complete wire body.
The outer sender, encryption fingerprints, receipts, replies and IDs retain their
usual meaning. A hand-typed matching prefix is indistinguishable from a forwarded
claim; the display must never imply verified authorship.

The original date and label survive history/reload, retries and re-forwarding.
Choosing Telegram's Hide Sender / send-as-copy action intentionally omits them.
Only a display label is exported by this compatibility form; no original JID,
receiver, local message ID, fingerprint or source attachment key is added.
A local nickname can be included in that label, so attribution is display text,
not a reliable global identity.

## Media, polls and encryption

Forwarded files are downloaded/verified locally and uploaded afresh for their new
destination. Original key-bearing URLs are never reused. For cleartext/OMEMO2 file
sharing the header prefixes the caption, before XEP-0447's URL fallback. For legacy
OMEMO it prefixes the protected `aesgcm` body; attachment recognition peels the
forward prefix before parsing that URL. Existing legacy separate-caption behavior
is retained. Sticker attributes and spoilers follow the normal media path.

Poll definitions and static location bodies are wrapped without changing their
inner canonical format. Native projection removes the forward prefix before
interpreting them. A forwarded poll starts a new vote; old ballots and identities
are not copied.

For legacy OMEMO the entire compatibility header lives inside the encrypted body.
For OMEMO2 it lives in authenticated protected content. No cleartext attribution
extension is attached to encrypted outer stanzas. Other clients see the readable
header; Monogram recognizes the same protected body after decrypting it.

The convention is not a cryptographic signature, an XSF extension namespace or a
way to forge the authenticated stanza sender. Its use of visible text deliberately
keeps unsupported clients useful without duplicating an inner original alongside a
plain-text fallback in a standalone XEP-0297 stanza.
