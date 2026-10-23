# Reaction polls

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
