# Reaction polls

## Structured proposal — implementation in progress

The [reaction-poll ProtoXEP](../xeps/xep-reaction-polls.xml), revision 0.0.2,
defines explicit `<poll xmlns="urn:xmpp:polls:0" type="single">` content with one
question and ordered `<option emoji="…">label</option>` elements. Its body is an
optional localizable XEP-0428 fallback and never supplies authoritative options.
The namespace remains an unsubmitted proposal, not an assigned XSF feature.

The shared mobile codec and Rust codec now enforce the same bounded structured
fields, emoji mappings and fallback-less payloads. OMEMO 2/SCE protects that XML
and any optional fallback. Mobile's native receive/vote path persists definitions
in schema 36 independently of bodies, handles direct and room cards and custom
mapping, filters plaintext ballots on protected polls, and accepts only matching
author closures with a frozen first accepted timestamp. Poll and reply fallback
ranges/formatting are rebased independently; malformed poll metadata stays readable
without legacy guessing. The Java encoder's duplicate body/subject emission was
also fixed for interoperable protected content. Both readers collapse identical
historical text duplicates while rejecting conflicting bodies, subjects, threads
or attributes. Encrypted Java-generated historical vectors verify the Rust reader.

Native mobile creation now derives XML directly from the native question, description,
options and mode. It never recovers authoritative fields from a fallback body.
The composer uses a readable fallback; the shared send API also supports bodyless
polls. Author closure sends the unchanged complete definition. Forwarding creates
an open poll with a fresh send identity and no copied ballots, preserving custom
emoji mappings and attribution. The proposed namespace is advertised.

Native outbox params retain encoded XML before serialization. Base64 avoids the
native attach-path parameter delimiters (`||` and `|=|`); arbitrary poll text must
not become new local parameters. Pending sends snapshot that metadata. The local
scheduled journal retains the definition across claims, retries and repeated
occurrences, and projects a native poll card. Existing account/encryption fences
remain in force. These changes preserve metadata; they do not establish exactly
once delivery after an uncertain network send.

Direct sends support plaintext, OMEMO 2 and PQ OMEMO; room sends support plaintext
and PQ OMEMO MUCs. Legacy body-only encryption and classic OMEMO 2 room sending
fail explicitly, with no plaintext or outer-metadata downgrade. Historical legacy
body polls remain readable/votable and close using their historical format.

Desktop now recognizes the historical canonical body cards, votes and closure,
and forwards them as fresh structured polls. The old UTF-16 limits apply only to
that compatibility reader; explicit XML blocks legacy guessing.

A native mobile fallback toggle, physical cross-client UI acceptance, desktop
historical forward attribution and desktop room transport remain open. See the
[desktop implementation status](../../desktop/docs/STRUCTURED_POLLS.md).

## Verification

Shared-core tests exercise bodyless/translated fallbacks, explicit choices,
Unicode/serialized bounds, malformed definitions, independent reply offsets,
model copies, outgoing fallback/reply ranges and actual OMEMO 2 payload roundtrips.
The test-only `GeneratePollVectors` exports Java wire/semantic/crypto vectors for
Rust. Live disposable-server checks exercise plaintext/OMEMO 2 direct and plaintext room
bodyless/fallback delivery, public votes and complete closure. Separate pinned
PQ backend tests verify bodyless direct/MUC sends and closure with no exposed
poll/fallback XML. Native Robolectric checks cover structured cards, custom mapping, late votes, withdrawal,
closure/reopen, deletion, native outbox delimiter roundtrips, scheduled retries/repeats,
forwarding and schema upgrades; historical poll behavior remains covered. Local APKs use unchanged native libraries from the 0.2.195 baseline
for these application-code changes and a separate local debug signing identity. No real
account, phone install or user key store is used for these checks.

## Deployed legacy body convention

Monogram reuses the native poll composer, poll cards, multiple-choice controls,
vote/retract animations, voter lists and author-only Stop Poll action. Polls
work in rooms and DMs. Votes and their latest replacement sets use
[XEP-0444](https://xmpp.org/extensions/xep-0444.html), whose introduction explicitly
includes voting as a use case. No dedicated poll XEP was found in the
[current XSF list](https://xmpp.org/extensions/); this is a documented convention
on top of ordinary messages/reactions, not a claimed new XEP.

## Readable fallback

The message body is the complete poll definition. Other clients can read it and
vote using their existing reaction picker. For example:

```text
📊 Where shall we eat?

1️⃣ Pizza
2️⃣ Noodles

Public votes. React with an option's emoji to vote. Choose one.
```

Multiple-choice polls use this exact final line instead:

```text
Public votes. React with option emojis to vote. Choose any.
```

Poll descriptions are optional. Starting with 0.2.154, a separate block between
the question and options preserves them:

```text
📊 Where shall we eat?

📝 Pick a place for tonight.

1️⃣ Pizza
2️⃣ Noodles

Public votes. React with an option's emoji to vote. Choose one.
```

The native card shows the description as its caption; the fallback includes it
once. Definitions without descriptions keep their original wire representation.
0.2.153 displays description-bearing polls as readable text; 0.2.154 recognizes
their native cards. Descriptions survive retries, native send acknowledgements,
reload, closure and forwarding. Descriptions are bounded to 4096 UTF-16 units;
complete authored fallback bodies are bounded to 40000 units.

The fixed English fallback is a wire convention; it must not be localized or
silently changed. Native controls can use the app's usual translations. The
question/description/options may contain line breaks; every continuation line is indented
by exactly two additional spaces. Blank lines inside a field are therefore
indented too. Monogram recognizes only canonical, bounded definitions that
round-trip exactly, rather than guessing that ordinary numbered text is a poll.
Formatted/spoilered messages are not reinterpreted as interactive polls.

The option emoji map, in order, is:

```text
1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔴 🟠 🟡 🟢 🔵 🟣 🟤 ⚫ ⚪ ⭐ 🌙 ☀️ 🌈 ⚡ 🔥 💧 🌸 🍀 🍎 🍋 🍇 🥝
```

There are at most 32 options, matching the existing reaction replacement-set
bound. Questions are bounded to 4096 UTF-16 units, options to 1024 each, with
2–32 nonempty options. No remote Telegram objects, file IDs or serialized TL
objects are accepted. The plain body remains the source of truth in storage;
the native card is a local projection, not an alternate remote text payload.

## Votes and results

A vote is a normal XEP-0444 replacement set referencing the original message ID
in a DM or the stable room stanza ID in a MUC. Choosing a different option
replaces the sender's prior option reactions; retracting removes them. Unrelated
reactions are retained. Duplicate emoji variants with/without U+FE0F are treated
as the same option. A single-choice ballot containing multiple option reactions
is invalid and contributes no vote; other clients should remove the previous
option before picking another. Multiple choice counts people once in the total,
while counting each of their selected options.

Monogram hides the option-mapped reaction emoji chips on native poll cards,
showing votes through the poll controls/results instead. Ordinary reactions stay
visible. This is a rendering filter: the complete replacement sets remain in
storage and on the wire for counting, voter lists and other clients.

Counts, selected options and voter lists derive from the durable reaction event
journal. This handles out-of-order arrival, archive replay, votes arriving before
the poll and stable room identities across nickname changes. The existing
reaction trust/identity rules still apply: an anonymous room without stable
occupant identities cannot safely count attributable votes. Native local poll
IDs identify the card only and are never sent as XMPP targets.

Closing uses an author-authenticated message correction with the same question,
options and mode, adding this final line after the voting instruction:

```text
Voting closed.
```

Monogram freezes its result projection at the correction's timestamp (second
precision in the native message model). The server does not enforce poll rules:
other clients may still send reactions after closure, but Monogram excludes
those later events from the closed result. The existing correction ownership
and encryption checks remain in force. A forwarded poll is a fresh, open poll
in the destination conversation; it does not copy voters or claim a shared tally.

## Privacy and supported controls

Votes are public. Anonymous votes, quizzes, dynamic options, disabling revotes,
restricted audiences/countries, media options, hidden results and timed closure
are not exposed as supported composer controls. Unsupported media requests fail
explicitly rather than claiming those properties. Send attachments separately.

Poll text uses the normal text outbox and encryption mode, including queued,
failed, scheduled and forwarded sends. OMEMO 2 reactions are protected using the
existing encrypted reaction transport. Legacy OMEMO cannot protect XEP-0444
reaction metadata; voting uses the existing per-chat unencrypted-reaction
consent dialog. It never silently permits public votes in an encrypted chat.
No server plugin or new polling loop is added. Native result refreshes read local
state; live and archive reaction events update it through the existing sync path.

This convention is indexed with other publication candidates in the [Monogram protocol registry](protocols/README.md).
