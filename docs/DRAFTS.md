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

## Native attachment selections

Ordinary chat photo/video, document-file, music, ordered contact and document-history forwarding selections now have a local recoverable composition.
The original attachment grid and photo/video editor remain responsible for
selection, editing and sending. The manifest records native selection order,
per-item and shared captions/entities, caption placement, applied crop/filter/
paint state, video trim/mute settings, spoilers, timers and cover metadata.
Reopening the picker restores the saved native tab and merges recoverable entries into its selection. Document selection order and native music document/track metadata are retained. Contacts preserve the selected native User/phonebook fields and order; phonebook lookup IDs are given local recovery identities because Android reallocates them between loads. Native contact preparation still resolves the selected provider lookup when sending; if that lookup is unavailable, the stored name/phone numbers remain available. Recovered contacts and history messages are visible as native checked rows. Forwarding keeps the source message, conversation, entities and native retry params and reuses the existing XMPP forwarding path. Tab switches retain same-owner selections in memory; the durable composition follows the last selected tab.
The close confirmation offers **Keep draft**, **Discard** and **Cancel**.

Ownership uses the account slot, account JID, conversation address and a logout
ownership generation. A delayed restore cannot populate another conversation or
account. Logout invalidates the old generation. Sticker/import, avatar,
message-media replacement and thread pickers do not restore these drafts.

Metadata is written through AtomicFile synchronously at capture, before sending
or the picker pause/dismiss lifecycle returns. Caption typing is captured after
750 ms; an abrupt process kill inside that interval can still lose that edit.
Camera/cache images, applied edit assets and content-URI inputs are copied into
private app files in the background; temporary native camera cleanup preserves
inputs while copying. URI grants are retained where the provider allows it.
A kill before a URI/private-asset copy finishes still requires the original input
to remain accessible. Gallery sources are checked for changed size/time before
reuse. Missing inputs or applied edit assets produce a recovery notice; their
manifest entries remain available rather than silently sending different media.

Sending attaches private batch/item tokens to native media params. A selection
is cleared only after **every item** is confirmed in the native durable outbox.
Conversion failures before queuing retain the composition, and partial handoffs
restore only unaccepted items. Contact/history-forward captions sent as separate
text messages have separate batch tokens, so an accepted caption is not restored
and sent again. Reference tokens can only be claimed during the picker’s native
delegate call; they are closed in finally and cannot tag unrelated later sends. There is no second sending queue. Assets that
might be used by queued/failed native sends are retained across draft clearing
and logout; completion/deletion notifications trigger a scan on the native storage queue.
Private owner directories are removed only with no active/recoverable manifest
and no pending, failed or scheduled native reference. Collection defers while
any picker is open or any recoverable manifest remains in that account scope,
conservatively protecting references into another draft directory, including
binary editor data. A lifecycle lock and mutation fence prevent a stale native
scan from racing a new selection/handoff. Closing the picker or completing batch
acceptance requests a fresh native scan. The scan checks owner
tokens, attachment paths and path-bearing native params; query/decode failure
retains files. Opening the picker also schedules collection after a restart.
Protected original temporary paths can still remain until cache cleanup/process
restart.
No account credentials or OMEMO keys are copied into this store.

## Remaining coverage and acceptance

Scheduled compositions and unapplied crop/paint editor canvases are not covered. The native location tab dispatches its current place directly
through didSelectLocation from its action/venue callbacks; it has no selected
attachment batch to restore. Contacts’ native selectedContacts/order and caption path are now recovered.
External Android
file-provider launch/result selection is also outside the in-picker file list. No cross-device draft support is
claimed. Keep the broader media-draft TODO in progress until those paths and
phone acceptance are finished.

Fifteen source fixtures cover selection order and caption entities, video edits,
private camera snapshots, changed/missing sources, owner isolation, all-item
outbox acknowledgement, reserved native params, logout retention, document/music
restoration, contact/provider metadata, history-forward source metadata, partial
caption/contact acceptance, scoped one-use handoffs and conservative asset
collection including a stale-scan/cross-owner-selection race. They are
prepared, **not run**. Run at the next planned phone-update gate:

```sh
nix develop --command ./gradlew --offline --no-daemon -PmonogramAbi=arm64-v8a :TMessagesProj:testDebugUnitTest --tests org.telegram.messenger.xmpp.MonogramMediaDraftsTest --tests org.telegram.messenger.xmpp.MonogramSelectionDraftsTest
```

Phone acceptance should exercise keep/discard, back navigation, process restart,
account switching, file/music/contact/history selections, applied photo/video edits, missing files and conversion
failure/partial batches before claiming complete attachment draft support.
Text draft acceptance still includes formatting-only edits and reply quotes;
the two earlier text comparison cases passed at the 0.2.119 gate.
