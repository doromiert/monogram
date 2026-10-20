# Encrypted account backups — implementation status and format

**Not a shipped backup feature yet.** The portable encryption/container, logical Android SQLite snapshot/restore and
Keystore secret-conversion primitives are implemented. Native durable-store capture/staging and owner-specific legacy bootstrap
materialization adapters are also implemented. A coherent cross-store snapshot,
media/secret collection and cold restore transaction are still required before
exposing a settings entry. No real account has been exported or
restored, and the prepared tests have not been run during this feature phase.

## What the repository actually stores

| Data | Authoritative store | Portability requirements |
| --- | --- | --- |
| Local history, message IDs, attachments, MAM cursors, room metadata, reads, reactions, profiles and formatting | `MonogramStore`, `databases/monogram-N.db` (schema 28) | Preserve local IDs and relational state. Snapshot through SQLite, including WAL; copying the main file alone is incomplete. Rewrite local media paths. |
| Queued/failed/scheduled outgoing messages, draft metadata and native facade state | `MessagesStorage` native SQLite and native account preferences | `MonogramOutbox` only merges durable native messages into views. Preserve serialized message params, local negative IDs, encryption mode, send state, reply/media data and required upload sources. Snapshot on its storage queue. Do not replace it with a second queue. |
| OMEMO 2 identity, device allocation, prekeys, ratchets, fingerprints/trust and incoming journal | `no_backup/monogram-omemo2-N.db`, owner/device keyed | Preserve complete transactions and `own_devices`; validate every owner is the requested account. Never allocate a new device as a substitute for restoring its private keys. |
| Legacy OMEMO current transactional state | application-wide `no_backup/monogram-legacy-omemo.db`, owner/device keyed | Export only the requested owner's rows, preserving journals, tombstones, counters and bootstrap-suppression markers. Never include another slot's records. |
| Legacy OMEMO bootstrap state | `files/monogram-omemo`, read-only Signal files | Some keys/sessions are still loaded lazily from this tree; the overlay alone is insufficient. Materialize/collect the exact owner's typed bootstrap state, including all local devices, before snapshotting. Do not copy the entire multi-account tree. |
| Legacy trust decisions | `files/monogram-trust-N` properties | Fingerprint-bound decisions must stay unchanged and be scoped/remapped to the target slot. |
| Credentials and room secrets | `monogram-N` preferences and `room_bookmarks.payload` | `AccountVault` uses nonexportable Android Keystore alias `monogram-xmpp-N`. Decrypt inside private export staging and re-encrypt with the target slot's vault during restore. Raw Keystore ciphertext will not work on another phone. Nested saved room invitations also contain encrypted passwords. |
| Packs/custom emoji and linked-pack update state | `files/monogram-packs/<slot>-<JID hash>/packs.json` and artwork | Preserve pack/item IDs, hidden artwork history, linked/detached/auto-update metadata, private policy and local order. Remap only the account-slot directory prefix. Resolve native favorites/recent references. |
| Inline emoji assets | `files/monogram-inline-emoji/<history DB basename>` | Preserve referenced content-addressed assets and rewrite the DB basename for a remapped slot. |
| GIPHY/Telegram/translation secrets | account pack/GIPHY/main preferences | Rewrap the audited encrypted token fields; preserve account-scoped favorites/recents where supported. |

`MonogramBackupSecrets` explicitly converts the currently audited vault fields:
login credential, room-password preferences, nested room-invitation passwords,
bookmark XML payload, Telegram bot token, GIPHY key and translation API key.
An unreadable secret aborts conversion; it is never copied as supposedly portable
hardware ciphertext. The caller must use these transforms only inside the
passphrase-protected export or private restore staging, never overwrite live
preferences with unsealed values.

## Portable container primitives

`MonogramBackupArchive.write` accepts an **already quiescent, owner-filtered,
portable snapshot**, checks required sections, builds a manifest and wraps a ZIP
with `MonogramBackupEnvelope`. It does not capture live stores. `stage` fully
authenticates the envelope before processing ZIP entries, rejects traversal,
duplicates, missing/undeclared files, mismatched sizes/digests and a different
expected bare JID, then returns an AutoCloseable private staging directory.
It never applies database files or preferences to a running account.

All work directories must be beneath Android's app-private `no_backup` directory;
never use public cache, external storage or ordinary Android-backed-up files for
plaintext staging. Cleanup of abandoned staging after process death is required
at integration. Private staging is plaintext like the live database and must be
deleted on error, cancellation and completion. Final user-selected files contain
only the authenticated encrypted envelope. The passphrase is never persisted;
callers erase their mutable password arrays when done.

Envelope v1 uses big-endian binary fields:

1. Eight ASCII bytes `MGBACKUP`, 32-bit version `1`, 32-bit PBKDF2 iteration
   count `600000`, a random 16-byte salt, and random 8-byte nonce prefix.
2. PBKDF2-HMAC-SHA256 derives a 256-bit AES key from the user passphrase (12–1024
   characters). Each record contains a 32-bit plaintext length followed by
   AES-256-GCM ciphertext and its 16-byte authentication tag. Record plaintext is
   at most 1 MiB. The 12-byte GCM nonce combines the random prefix with the
   incrementing 32-bit record index. The entire header, index and plaintext
   length are authenticated as additional data.
3. A zero-length authenticated record is mandatory at the end, followed by EOF.
   Missing records, record reordering, incorrect passwords, tampered fields,
   truncation at a valid record boundary and trailing bytes are rejected.

`BackupKeyDerivation` in `xmpp-core` uses the existing Bouncy Castle 1.80
lightweight PBKDF2-HMAC-SHA256 implementation consistently on all API levels.
It does not register/change global security providers or add dependencies.
Password characters are encoded as UTF-8 without Unicode normalization; malformed
UTF-16 is rejected rather than silently replaced. Temporary password bytes and
the intermediate returned key buffer are erased; callers own and erase their
password array and final derived key. Internal library/JVM copies cannot be
guaranteed erased.

This closes the platform JCA availability gap on API 23–25: Android's [official
algorithm support table](https://developer.android.com/reference/javax/crypto/SecretKeyFactory)
only guarantees `PBKDF2withHmacSHA256` from API 26. The envelope's algorithm,
iterations, key length and valid UTF-8 password semantics remain unchanged.
Prepared fixtures compare [RFC 7914 section 11 vectors](https://www.rfc-editor.org/rfc/rfc7914.html#section-11)
and JCA-derived bytes, including non-ASCII, supplementary/NUL characters,
composed/decomposed forms and production iteration count. The implementation
uses [BC's UTF-8 password conversion](https://github.com/bcgit/bc-java/blob/r1rv80/core/src/main/java/org/bouncycastle/crypto/PBEParametersGenerator.java)
and [explicit SHA-256 PBKDF2 generator](https://github.com/bcgit/bc-java/blob/r1rv80/core/src/main/java/org/bouncycastle/crypto/generators/PKCS5S2ParametersGenerator.java).
No new fixtures or Android API-level crypto path have been run/verified yet.

This bounds per-record decryption memory instead of relying on Android GCM
providers buffering a multi-gigabyte backup. Both envelope and expanded ZIP have
an 8 GiB safety bound. The manifest is limited to 8 MiB. These are input/storage
safety limits, not sticker-count limits.

The ZIP contains `manifest.json` with format `monogram-account-backup`, version
`1`, canonical bare `owner`, millisecond `created` timestamp, and a `files` list
of `{path,size,sha256}`. Required logical sections are `data/history.db`,
`data/outbox.db`, `data/outbox-meta.json`, `data/omemo2.db`, `data/legacy.db`, `trust/legacy.properties`,
`preferences/xmpp.json`, `preferences/native.json`, `preferences/packs.json`,
`preferences/giphy.json` and `paths.json`. Empty real sections still need valid
empty snapshots. Additional account-owned artwork/media/bootstrap files use
validated relative paths. Presence of a required filename does **not** establish
that its database schema, ownership or vault portability is correct: the
snapshot/restore adapter must validate those before commit.

## Logical Android SQLite adapter

`MonogramBackupDatabase` snapshots current-schema history, modern OMEMO and the
shared legacy overlay into standalone private SQLite files. It copies rows with
typed values, filters both key stores by the canonical account owner, preserves
`sqlite_sequence` high-water marks and converts bookmark payload secrets via
`MonogramBackupSecrets`. Binary records are fetched in 512 KiB chunks so Signal
sessions/incoming journals larger than Android's CursorWindow are not lost.
Individual cells have a 16 MiB bound.

The restore adapter accepts only an empty temporary database initialized using
the app's trusted schema. It requires exact schema/version agreement and a
SQLite integrity check, rejects foreign account key rows, reseals bookmark
payloads, and rolls back the temporary destination on failure. It never executes
schema supplied by a restored archive. It has no live callers and does not
replace files, merge key states or initialize native `MessagesStorage`.
Schemas from older/newer app versions must be migrated separately in staging;
blind cross-version restoration is rejected.

## Native durable store adapter

`MonogramBackupNativeStore.capture` uses existing `MessagesStorage` public storage
queue/database APIs. It executes `VACUUM INTO` on that queue, using the bundled
SQLite 3.39.3 implementation, to capture a full standalone account-native database
including committed WAL content. It does not copy a running main database file,
recreate serialized outgoing messages in another queue, or modify the live
outbox. Account JID/native self ID are checked before/after snapshot. Existing
output files are rejected and preserved. No `MessagesStorage` hooks/edits were
needed.

`data/outbox-meta.json` binds canonical owner, native self ID, native DB version,
full schema fingerprint and queued/failed/scheduled counts. `prepareRestore`
requires metadata agreement, integrity checks and an exact schema/version match
to an app-created empty native template **before** copying/modifying private
restore staging. It never executes schema supplied by the archive and rejects
portable DB sidecars. Divergent migrated schemas currently fail closed rather
than being guessed compatible.

Attachment paths and native local params are read/written using existing TL
message serialization. Current `TL_message.serializeToStream` already appends
`writeAttachPath`, so the adapter does not append it twice. It remaps attachment,
`originalPath`, video edit original source and poll attachment paths while
preserving negative local IDs, dialog IDs, send state, `monogram-encryption`,
opaque custom params, random IDs and scheduled rows. Every local/content-URI
reference in a queued/failed/scheduled message requires a portable mapping or
restore staging fails and is deleted. Callers must copy/validate the mapped
source assets and resolve content URIs into app-private files; enumeration alone
is not proof of a recoverable media outbox. Unmapped historical attachment paths
are optional and remain unchanged; historically uncached media may need a new
download. Nothing invokes `getUnsentMessages` or starts sends during staging.

## Legacy bootstrap materialization adapter

`MonogramBackupLegacyStore` takes an already owner-filtered overlay snapshot
inside a dedicated private backup work directory. It rejects the live legacy
DB path and foreign-owner rows. Core `MonogramLegacyBackup` copies only that
owner's Smack 4.4.8 URL-encoded bootstrap tree into separate private staging;
original files and the live overlay factory are never written. Linked/escaped
paths, unknown file shapes and corrupt typed key/session records fail closed.
The temporary copy has a 512 MiB total safety bound and each record obeys the
existing legacy record-size limit.

A typed merged store materializes every own device's identities, prekeys,
signed prekeys, sessions, cached active/inactive device lists, counters and dates
into the isolated overlay, honoring existing tombstones and purge markers.
Incoming-journal bytes remain untouched. It sets bootstrap suppression **only in
the snapshot** after a device's values are committed, so restore does not consult
unrelated destination-phone bootstrap files. The wrapper discards the entire
isolated snapshot if any device fails. Fingerprint trust still needs the
separately bound account trust file. Fixtures prepare two-owner/two-device
coverage, prekey zero/tombstones, byte-identical source preservation, restoration
against empty bootstrap and corrupt-record rejection.

## Receive-only recovery, repeated migrations and startup binding

`MonogramBackupModernStore` and `MonogramBackupLegacyRestore` create independent
active and receive-only copies using the exact trusted app schema. The active
copies retain the selected identity/device and trust, remove **all** old session
lineage, regenerate signed and one-time prekeys with fresh randomness and IDs
above captured keys (including legacy consumed-key tombstones), rotate their
incoming journals and receive new migration markers. A migration-marked source
can be migrated again; an old outbound chain is never reactivated. Exhausted IDs,
missing identities and inconsistent key metadata still fail closed.

`RecoveryManifest` binds canonical account, generation, active device IDs, public
identity pins and migration markers to ordered UUID-named archive files. An archive
ID identifies a particular mutable receive state, so multiple archives for the same
device ID are retained. Each archive also pins its protocol, own-device ID and
identity; explicitly pinned historical identities may differ from today's local
identity, including after reuse of an old device ID. No historical identity is
allocated a replacement ID or published again.

`MonogramRecoveryStage` is an offline staging adapter. It appends the just-retired
active snapshot and every materialized historical legacy own device, and logically
copies every earlier archive under its **original archive ID**, preserving receive
ratchets, prekeys, journals and pending plaintext. It verifies that a previous
manifest's active identity/epoch matches the active snapshot captured alongside it.
The legacy materializer preserves existing migration markers instead of replacing
them with a one-byte bootstrap marker. Each legacy own device currently receives
an independent full owner-filtered DB copy; this deliberately favors isolation over
storage deduplication. The staged manifest is fsynced but no live file is replaced.
Callers must quiesce all active and archived receive writers before capture; this
adapter does not implement that global barrier.

The controller now calls `MonogramRecoveryStore.load` **before allocating any
missing device ID or constructing a session**. An absent recovery directory is the
unchanged ordinary-account path. A partial directory, changed owner/device/identity
or epoch, unsupported SQLite schema, redirected/missing/unlisted archive, foreign
owner rows or unconfirmed migration blocks startup. Archive sqlite_master is
compared with in-memory trusted app schema before archive table queries. Public
identity pins are rechecked against the actual records. The private installed path
is `no_backup/monogram-recovery-<slot>/`, containing `manifest.bin`,
`archives/<uuid>.db` and an empty bootstrap directory. The loader never creates or
installs that directory on an existing account.

`XmppRecovery` factories are bound through an optional `XmppSession` constructor.
They never authenticate, initialize a registered device, publish, send an outbox
or activate a slot. The manifest's `reconnectApproved` flag must represent explicit
migration/reconnect confirmation in the future cold transaction; false blocks
connection. Merely restoring files must never silently grant that confirmation.
An installed manifest cannot change in-process: open archive stores remain alive
until process exit because session close does not yet await every crypto callback.
Manifest changes require a cold restart. Logout/account-slot reuse is also an
unfinished cold-transaction responsibility: current logout does not retire the
installed recovery root or the process-wide OPEN-slot cache. Before a different
account can reuse a restored slot, a future transaction must stop old callbacks,
await archive writers, retire/close those handles and atomically detach the old
account's manifest/cache binding. It must preserve old keys and receive archives
until explicit retention/deletion handling, invalidate slot ownership generations
and recover correctly after interruption. Silently deleting live archives or
allowing a new account to inherit them is not an acceptable shortcut. Until that
path exists the loader intentionally blocks a different owner, so restore UI must
remain unavailable; a permanently blocked slot after logout is not final behavior.
The loader currently rejects WAL/SHM/hot
journal sidecars; safe recovery after a crash during an archive receive transaction
is a remaining cold-start concern before enabling this feature.

Modern OMEMO selects the encrypted recipient key using each archive's historical
own-device ID, not only today's ID. Active and archive journals retain explicit
source provenance. Recovery records consult current active trust and permit only
receive-session updates, consumed-prekey deletion and incoming-journal updates.
Saved and fresh archive frames suppress ACK encryption and bundle replenishment.
Only native key/ratchet mismatch codes try another archive; trust, duplicate,
format, journal and storage failures remain failures. Normal constructors retain
no recovery state.

Legacy recovery does not create additional OmemoManagers or replace the singleton
backend. `MonogramLegacyArchiveCipher` runs the installed Java Signal receive
algorithm in detached records bound to the **historical** own identity/device,
loading only the target session and referenced prekey/signed-prekey. Successful
receive changes are copied to the archive's outer journal transaction; payload or
journal failures roll everything back. Existing Smack payload decoding remains in
use. Current `IdentityTrust` must approve the sender before archive changes commit
and before stored plaintext is replayed. No archive completion, receipt,
replenishment, bundle publication or repair helper runs.

`MonogramLegacyRouting` recognizes only typed Signal missing-key/session/ratchet
mismatches, including installed Smack's `MultipleCryptoFailedException ->
CryptoFailedException -> NoRawSessionException` wrapping. Every aggregate member
must qualify; trust, IO, duplicate, unsupported-version and untyped crypto failures
never retry. There must be one encrypted key per targeted local own-device ID,
preventing the installed ratchet's ignored-identity branch from being hidden by
another key's error. Historical recipient IDs are routed without registering them.
For a prekey packet, candidate selection parses the actual Signal identity and
checks current trust while holding the trust monitor. An explicitly trusted new
peer identity may skip an incompatible archive through a private
`TrustedPeerMismatch` result. An undecided/revoked identity or a corrupt store
remains terminal; no generic trust exception is reclassified. Old peer pins and
session bytes are not overwritten to make a candidate fit. If the archive already
contains a previous receive state matching the packet's version, base key and
remote identity, trusted prekey resends may continue using that state.

Installed Java Signal authenticates ordinary packets against the receive state
that actually decrypts the MAC, which can be a promoted previous state. Archive
trust therefore uses that exact `SessionState.remoteIdentityKey`; returned/journal
fingerprints use the authenticated state rather than the archive's current global
peer pin. A previous trusted state can advance inside the receive-only archive
while preserving its newer peer pin. Revoking that old fingerprint blocks both
prekey and ordinary packets even if the newer fingerprint remains trusted. The
prepared rotation fixture covers incompatible-old-archive -> compatible-active
selection, unchanged old records, corrupt/revoked candidates and old prekey/normal
packets from previous states with their correct fingerprint.

Legacy journal source provenance determines acceptance and protocol cleanup; saved
MUC route/identity tags, including `room-displayed`, survive replay without resolving
today's nickname mapping.

The incompatible archive candidate case is addressed above when another compatible
active or archived state exists. Ordinary active peer-identity replacement remains
a distinct unfinished transition, described below.

Prepared fixtures cover manifest round-trip and repeated archive generations,
partial/foreign/unconfirmed startup, pinned historical own devices, fresh native
and Java Signal keys, old/new session routing, trust revocation, journal provenance,
ACK/completion suppression, MUC tag persistence and record write restrictions.
None of this checkpoint has been built or executed. Global capture quiescence,
recoverable cold commit, hot-journal recovery and settings UI remain incomplete;
backup/restore remains unavailable in settings.

Composition media drafts use a separate owner-generation/peer-key manifest under
`files/monogram-media-drafts`. Native pending media paths into that directory are
already enumerated/remapped, but restoring editable composition drafts additionally
requires portable ownership generations and peer keys. Do not blindly copy a
stale generation. Notification-action generations remain excluded and must be
invalidated at cold commit.

## Ordinary active legacy peer identity rotation (source checkpoint)

Explicit legacy fingerprint approval now handles a peer reusing a device ID with a
new identity. `XmppSession.decideAll` captures the existing peer and local identity,
fetches the replacement public bundle outside crypto/storage/trust locks, then
`MonogramLegacyRotation` verifies both the exact approved fingerprint and signed
prekey. A stale owner identity, peer identity, session lifecycle or trust decision
revision aborts approval. Revocation never creates a session. Trust-all uses the
same verified batch path; unchanged fingerprints and entirely new devices retain
the ordinary session preparation path.

The apply transaction saves frozen local/peer identities and the complete original
SessionRecord in `retired_records`, scoped by account JID, own device, peer JID,
peer device, fingerprint and a unique retirement UUID. It also saves an independent
mutable receive session containing only states for that fingerprint. A→B→A
rotations keep separate generations rather than overwriting an advanced historical
ratchet. The new outgoing session is built from the approved signed bundle in an
empty detached Signal store: no old SessionRecord/previous state enters it. Own
identity/device, unrelated peer state and existing trust decisions stay intact.

`MonogramLegacyRetired` supplies peer-scoped receive views, suppressing every
bootstrap/active-key fallback. Only session and incoming-journal writes are allowed;
frozen identities and the original snapshot are immutable. Retired candidate views
are restricted to the authenticated route's author/device, then the existing
archive cipher checks current exact-fingerprint trust and the MAC-selected peer
identity. Historical decryption never promotes state into the active sender store,
creates a crypto ACK, replenishes a key or publishes a bundle. Undelivered active-journal bodies are atomically moved into their retired fingerprint
branch, preserving captured DM/MUC routes while clearing old crypto obligations;
accepted rows with only obsolete protocol work are removed. Pending plaintext
uses each retired journal's own provenance/epoch; revoked fingerprints are rejected
before replay. Logout rotates retired plaintext journals while retaining keys.
Typed trust, corrupt-storage and duplicate failures remain terminal, rather than
being interpreted as candidate mismatches.

Legacy SQLite version 2 adds this owner-scoped table through trusted v1→v2 DDL.
Portable import accepts an exact trusted v1 schema as the sole compatibility case;
it never executes archive-provided schema. Owner filtering/rejection covers both
tables. Cold staging puts prior retired rows exclusively in its receive-only copy,
whose startup configuration expands their peer-scoped journals, so mutable ratchets
are not duplicated in the fresh active stage. Repeated migration manifests continue
to preserve earlier archives and historical own devices.

The trust file and SQLite commit cannot form a cross-file atomic transaction.
Trust persistence failure rolls SQL back. If the explicit trust decision persists
but SQL commit fails, all old identities/sessions/lineage rows roll back; approval
reports failure and repeating it still performs the required rotation. No success
is reported on that path. Account close is serialized with the apply manager lock.
There is no implicit account activation, reconnection, key publication or network
send during approval. The only network operation is public bundle lookup.

The 0.2.120 release gate passes seven headless active-rotation crypto/rollback/lineage checks, two prior peer-rotation checks and nine Android legacy-store schema/owner/logout checks. This does not verify the complete backup/export/restore pipeline.
Global backup quiescence/cold-install transaction and its settings UI remain
unavailable pending the integration work below.

## Required integration before enabling settings

1. Define a durable quiescence barrier: stop/reject new sends, close XMPP and
   finish Smack crypto callbacks, transfers and native storage/pack writes.
   Capture each database consistently after those writers stop. Merely running
   a task on the controller worker does not stop Smack or native storage queues.
2. Build logical account-filtered exports and audited path/secret transforms.
   Check expected owner/device records, all required schemas and source files.
   Detect unsupported schema versions and missing media needed by queued sends;
   explain optional missing historic media separately.
3. Restore only to the same JID in an empty target slot, or use an explicit
   replace flow after disconnecting it. Validate the entire staged snapshot
   first. Never merge opaque ratchet snapshots into newer live state. A restored
   identity/device must not run concurrently with the old phone: that would
   fork ratchets. An older backup must also never reactivate an old outbound
   ratchet/send-chain state: it could reuse message keys/counters even after
   disconnecting the source phone. Preserve identity/trust but design fresh
   outbound session establishment or a receive-only restored-session strategy
   before reconnection. The source paths above implement isolated receive-only sessions and archive-chain
   staging/startup binding; capture quiescence, crash recovery, handle/slot retirement
   and cold activation remain unimplemented.
   Explain migration and keep the source client disconnected.
4. Stage target vault encryption, slot/path remapping and native DB allocation.
   Record a recoverable commit journal; replace databases/files/preferences as
   one recoverable cold-start operation before any controller, Smack device,
   pack cache or native storage instance opens them. Never include the private
   `monogram-notification-actions-N` preferences in an export. Invalidate the
   target slot's `MonogramNotificationActions` generation during cold commit
   before restored native IDs/reconnection can be used; old notification action
   tokens must stay invalid even if restoration rolls back. Retire mutable recovery
   handles only after a real writer barrier, and implement logout/slot reuse ownership
   cleanup without deleting old keys as a side effect. Interrupted restore must
   either roll back or resume before messaging restarts.
5. Add native settings UI: SAF create/open document, passphrase confirmation,
   progress/cancel, owner/scope summary, explicit replace/migration confirmation
   and actionable errors. Never advertise a hardware-encrypted file copy as a
   portable backup. Restore must not automatically send the backed-up outbox
   before the user confirms reconnection.
6. At the release gate, run the prepared envelope/archive/secret fixtures, then
   test a disposable account across different Keystores/slots, process death at
   commit boundaries and unchanged identity/trust/message/outbox/pack IDs.
