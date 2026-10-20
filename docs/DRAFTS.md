# Drafts

Text drafts use Telegram's existing `MediaDataController` draft store, including
formatting entities, the reply message and quoted selection. Storage is local to
the Android app and separated by account slot (`drafts`, `drafts1`, etc.). XMPP
does not synchronize these drafts to another device. Monogram does not send
Telegram draft RPCs for an XMPP account.

The chat saves after 750 ms without text/formatting changes, on reply-panel
changes, and through the existing navigation/pause path. Reopening a draft uses
the native editor and reply restoration. Formatting-only edits and changes to a
quote's offset/entities must be compared as part of the draft, even when the
plain text is identical. The delayed callback is cancelled when the chat is
destroyed and when a navigation save runs. Message editing is excluded from the
delayed save so an edited sent message cannot replace the compose draft.

The most recent changes can still be lost if Android kills the process before
the delayed save and without a pause callback. Persisted drafts use the existing
synchronous preference commit. No account credentials or OMEMO keys are copied
into the draft store.

## Remaining acceptance and media work

The native attachment picker keeps its selected photos and captions in memory;
it does not provide a durable attachment-composition draft across process death.
That needs a separate recoverable selection/caption model, including URI access
permissions, missing-file handling and account/conversation ownership. Do not
represent the text-draft repairs as completed caption-draft support.

Phone acceptance still needs text, formatting-only edits and reply quotes across
back navigation, restart and account switches. No cross-device draft support is
claimed. Two regression cases covering entity and quote comparison passed at the
0.2.119 release gate.
