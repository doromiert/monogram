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

## External provider and camera results

Ordinary external document/gallery results now return to the native picker for
confirmation and captions instead of immediately sending the selected files.
External camera results keep the native photo/video preview. Editing, scheduled
and rich-composer result paths continue using their existing behavior.

Each ordinary launch records the account JID, conversation, logout generation
and camera output path. The fragment saves its request tokens across recreation.
A result for a different currently displayed chat/account is preserved only for
its original still-valid owner; it never sends or opens in the new context.
Results from a logged-out/replaced owner are rejected with a recovery notice.

SAF documents request retained read grants. Returned content URIs are recorded
before background copying into private draft assets; provider display names are
kept. Temporary grants have the same private-copy fallback. Image orientation
and video dimensions/duration are resolved on the worker, and the ready native
picker opens only when the owning chat resumes. URI access/copy failures retain
recoverable metadata and show a notice to restore access or select the inputs
again. Unsupported schemes and the app’s own private FileProvider are rejected.
An external result never replaces another media kind or a batch awaiting native
handoff. The existing composition remains intact, and the UI asks the user to
finish/discard it first. Result metadata and retained grants stay owner-bound;
reopening an empty attachment picker retries the deferred result. Sources with
only a temporary grant may need to be selected again if that access has expired.
Captions are merged only with the same compatible unsent kind.
No automatic-send queue is added: pressing Send uses the ordinary native outbox
and existing all-item handoff. A copy interrupted before its private snapshot
finishes still requires the original URI/file to be accessible, as above.

## Unapplied native crop and painting

Ordinary photo/video selections now carry an independent `editor` checkpoint and
stable `draftId`. Applied crop, image/paint paths and edit entities stay unchanged
until native Done. Crop checkpoints use CropState's native codec. Painting saves
a detached stroke-only PNG plus native editable entity metadata; text and stickers
are recreated as native entity views, rather than flattened twice into the canvas.
The draft remains local to the same account JID, conversation and logout epoch.
Camera/cache inputs and entity assets use the existing private snapshot path.

Reopening a pending item resumes its native crop/paint editor once the image/video
is ready. Send first opens unfinished edits for Done or explicit Cancel, including
an item previewed without selecting it. Such an item reopens as an unchecked
preview; restoring it does not add it to the send selection. Explicit Cancel drops only the unapplied
checkpoint; Done applies through the original native editor and then saves the
normal selection. If native Done returns without a renderable result, the editor
remains open, applied crop state is restored and checkpoint capture is rearmed;
paint entity cleanup waits for successful application. The viewer’s direct Send
path uses the same application-result guard before any outbox handoff; it cannot
send the original merely because applying an unfinished edit returned early. A different attachment kind or a batch awaiting native outbox
handoff is never replaced by opening an editor.

Snapshots are debounced for 750 ms and requested again on pause/navigation.
Stroke readback is asynchronous on the native GL queue and PNG compression runs
on a worker. Each editor/selection retains at most its current compression and
one latest waiting snapshot; superseded waiting bitmaps are recycled. Superseded
helper-created PNGs become targeted cleanup candidates after metadata replacement.
Deletion requires a newer durable checkpoint plus a successful native reference
scan, fresh disk/latest manifests (including atomic backups), live editor/job
references and an unchanged mutation fence. Unknown/corrupt metadata or a failed
scan retains files for later retry; active compression/current PNGs are excluded.
This reclaims old paint checkpoints even while an ordinary picker remains open,
without collecting unrelated user media or whole draft directories. Owner epoch, stable selection ID, editor token and revision fence
late captures after a newer edit, restored entry, apply, discard or logout. Only
unapplied checkpoint fields are changed in the latest manifest. A last edit can
still be lost if Android kills the process before pending GL/file work finishes;
the previously committed checkpoint remains recoverable. A lost/corrupt pending
asset is reported as a missing draft item instead of silently sending it unedited.

Telegram's UndoStore contains runtime Runnable closures. They cannot be restored
across process death. The current editable canvas/entities survive with a fresh
native undo history; earlier individual steps are not undoable after restart.
Unapplied filter adjustments, sticker-maker editing, avatars, stories and scheduled
compositions are outside this ordinary attachment-editor checkpoint.

## Remaining coverage and acceptance

Scheduled compositions and unapplied filter adjustments are not covered. The native location tab dispatches its current place directly
through didSelectLocation from its action/venue callbacks; it has no selected
attachment batch to restore. Contacts’ native selectedContacts/order and caption path are now recovered.
External Android provider/camera results are covered for ordinary chats. No cross-device draft support is
claimed. Keep the broader media-draft TODO in progress until those paths and
phone acceptance are finished.

Twenty-eight source fixtures cover selection order and caption entities, video edits,
private camera snapshots, changed/missing sources, owner isolation, all-item
outbox acknowledgement, reserved native params, logout retention, document/music
restoration, contact/provider metadata, history-forward source metadata, partial
caption/contact acceptance, scoped one-use handoffs and conservative asset
collection including a stale-scan/cross-owner-selection race, external launch
ownership, SAF multiple files/provider names, missing grants, camera output
recovery, stale/private-provider rejection, incompatible-kind preservation and
pending-batch preservation/deferred recovery, unapplied crop versus applied metadata, editable painting baselines/entities, stale capture fencing, editor-only cancellation and recycling superseded waiting paint snapshots, and retired-PNG retention on native reference failures/references/corrupt manifests followed by safe reclamation. They are
passing at the 0.2.120 release gate (17 media/editor, eight external-selection and three selection-codec checks). Native phone acceptance remains open. Targeted rerun command:

```sh
nix develop --command ./gradlew --offline --no-daemon -PmonogramAbi=arm64-v8a :TMessagesProj:testDebugUnitTest --tests org.telegram.messenger.xmpp.MonogramMediaDraftsTest --tests org.telegram.messenger.xmpp.MonogramSelectionDraftsTest --tests org.telegram.messenger.xmpp.MonogramExternalSelectionsTest
```

Phone acceptance should exercise keep/discard, back navigation, process restart,
account switching, file/music/contact/history/external selections, camera preview, applied/unapplied photo/video crop and painting (including text/sticker entity editing after restart, explicit cancel, native Done with unavailable GL/bitmap, Send from grid and old GL callback racing restoration/native Done), missing files and conversion
failure/partial batches before claiming complete attachment draft support.
Text draft acceptance still includes formatting-only edits and reply quotes;
the two earlier text comparison cases passed at the 0.2.119 gate.
