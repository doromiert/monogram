# Local scheduled messages

Native scheduled text messages are stored on the account's device, scoped to its
XMPP address. Monogram can list, edit, cancel, send immediately and repeat these
messages through the regular XMPP outbox. Delivery needs this device to be running
and connected; there is no XMPP server scheduling queue. Media, rich articles and
send-when-online are not implemented by this journal.

Each occurrence keeps its ordinary send random ID across retries. A live outbox
claim prevents a concurrent second dispatch or a misleading successful cancel.
Repeating messages skip missed intervals rather than sending a backlog all at once.
Transport acceptance followed by process death before durable completion can still
lead to duplicate delivery; a persisted random ID alone does not guarantee remote
deduplication.

A manually selected encryption mode is pinned when the schedule is created. If
the chat would now use another mode, including after switching the chat to
automatic encryption, the occurrence remains queued for retry instead of sending.
Restore the intended chat mode, or cancel and recreate the schedule using the
desired policy. Automatic schedules
evaluate the current daily encryption policy at delivery; that explicit automatic
policy may select plaintext. A pinned mode is checked at queue admission and again
at dispatch, and the actual text send uses that captured mode rather than rereading
a potentially changed chat preference.

The journal stores two times: `due` is the next attempt deadline; `occurrence` is
the planned occurrence used to calculate the next repeat. Retry backoff changes
only the attempt deadline, so a failed send cannot shift the repeating schedule.
For example, a message repeating each minute from 12:00, delivered after retries
at 12:01:30, next sends at 12:02. Send now or an explicit schedule edit intentionally
establishes a new cadence. Older journals without `occurrence` use their existing
`due` value; any drift accumulated by an older build cannot be reconstructed.

`MonogramScheduledMessagesTest` covers claim exclusivity, repeat retry cadence,
old-journal migration, intentional Send now cadence changes and account scoping.
These local journal cases do not establish background Android delivery or
independent-client interoperability.

`MonogramScheduledEncryptionTest` runs the pending-send admission/dispatch queues
with an intercepted transport boundary. It covers manual pins against automatic
plaintext/legacy selection, matching-mode delivery, a policy change between queue
admission and dispatch, and automatic schedules. It sends no network stanzas and
does not prove interoperability or successful cryptographic exchange.
