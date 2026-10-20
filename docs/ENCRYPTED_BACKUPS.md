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

## Modern receive-only restore source checkpoint

`MonogramBackupModernStore.prepare` builds two physically independent databases
under a newly created private work directory using the exact trusted app schema.
Both retain the same owner, device ID, identity and trust. The receive-only copy
retains the backed-up ratchets, incoming journal and prekeys. The active copy
removes **all** session/session-identity rows; `Omemo2Restore` replaces signed and
one-time prekeys with fresh randomness and increasing IDs, rotates its incoming
journal and writes a distinct migration marker. Nothing initializes an XMPP
connection, publishes bundles or sends an outbox item. Uninitialized, inconsistent,
exhausted or already migration-marked active key snapshots fail closed. Repeated
migration/archive-chain manifests still need design before enabling restoration.

The new optional `XmppOmemo2` constructor accepts `Omemo2ReceiveOnly`; ordinary
constructors and normal traffic use no archive. Recovery records expose no public
sending/publishing API. Transactions lock active trust before archive state and
permit only receive-session updates, consumed-prekey deletion and incoming-journal
updates. Current active trust is used instead of archived trust, including when
replaying saved plaintext. Archive frames carry their journal provenance and
suppress both stored and newly generated ACKs and bundle replenishment. Historical
prekey messages can consult the archive without fetching a now-expired server
bundle. Only native key/ratchet mismatch failures allow fallback; identity distrust,
duplicate-message, format and storage failures remain failures. Successful archive
decryption never copies or promotes its state into the active sending store.

This opt-in path is **not wired to account startup** and has not been built or
executed. Prepared fixtures cover write restrictions, current-trust revocation,
recovered-journal ACK suppression/provenance, fresh prekey IDs with unchanged
identity and independent native receive/outgoing sessions. The legacy opt-in source path below follows the same isolation policy; the
global capture barrier and recoverable cold commit remain missing. Backup/restore remains unavailable in settings.

Composition media drafts have a separate owner-generation/peer-key manifest under
`files/monogram-media-drafts`. Pending native media paths into that directory are
already enumerated/remapped by the native adapter, but restoring the editable
composition drafts additionally requires portable ownership generations and peer
keys. Never blindly copy a directory under a stale generation; until the manifest
adapter exists the scope summary must explicitly exclude editable compositions.
Notification-action generations remain excluded and must be invalidated at commit.

## Legacy receive-only restore source checkpoint

`MonogramBackupLegacyRestore` makes independent trusted-schema copies of a
materialized owner snapshot. Only the selected own device is retained in the
active copy; historical own devices remain in the recovery file and need an
explicit manifest/routing policy before they can be used. The active copy loses
all per-peer session rows. `MonogramLegacyRestore` preserves its identity/device,
replaces signed and one-time prekeys using fresh randomness and identifiers above
all captured rows (including consumed-key tombstones), resets the incoming epoch
and records a unique bootstrap-suppression migration marker. Key-ID exhaustion,
missing identity/materialization and repeat-marked stages fail closed. No source
bootstrap, live singleton backend or real account is changed.

The explicit `XmppSession.configureLegacyRecovery` opt-in is allowed only before
connecting. `MonogramLegacyOmemoService` keeps its active store and cached ratchets
unchanged; a dedicated archive `MonogramSignalRatchet` uses the installed Smack
4.4.8 message-key/body decoding helpers. No archive completion message, bundle
replenishment, publication or session-repair helper is invoked. Archive writes
are restricted to receive sessions/contact identities, consumed-prekey tombstones,
receive counters/dates and the incoming journal. Sender fingerprints must pass
**current** `IdentityTrust` before archive ratchet changes commit and before saved
plaintext is replayed. `MonogramLegacyRouting` permits only typed Signal missing-key/session/ratchet
mismatches, including the installed Smack wrapper chain
`MultipleCryptoFailedException -> CryptoFailedException -> NoRawSessionException`.
Every aggregate member must qualify; storage, journal, trust, duplicate, unsupported
version and untyped crypto failures never retry. Migration envelopes require
exactly one local recipient key so the installed ratchet's ignored-identity
branch cannot be hidden by another key's recoverable error. Each store is tried
at most once: archive then active for prekey packets, active then archive for
existing-session packets. Prepared real Java Signal fixtures cover new active
prekeys absent from the old archive and reciprocal old-session MAC mismatches,
with unchanged active session bytes after archive recovery.

Incoming frames retain their source journal. Acceptance uses that exact journal,
drops historical completion/replenishment obligations and cannot authorize an
identical token from another journal object. Local replay uses the saved MUC
route/identity tags, including `room-displayed`, without consulting today's
nickname mapping. Prepared fixtures cover current trust revocation, journal
provenance, obsolete protocol-obligation removal, room-tag persistence, record
write restrictions and fresh key IDs with byte-identical identity. These sources
have not been built or executed. Both protocol helpers still require startup
manifest/slot binding, archive-chain support and the cold transaction below;
settings must remain disabled until those integration gaps are closed.

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
   before reconnection. The modern and legacy opt-in source paths above implement isolated receive-only sessions;
   archive-chain migration and cold activation remain unimplemented.
   Explain migration and keep the source client disconnected.
4. Stage target vault encryption, slot/path remapping and native DB allocation.
   Record a recoverable commit journal; replace databases/files/preferences as
   one recoverable cold-start operation before any controller, Smack device,
   pack cache or native storage instance opens them. Never include the private
   `monogram-notification-actions-N` preferences in an export. Invalidate the
   target slot's `MonogramNotificationActions` generation during cold commit
   before restored native IDs/reconnection can be used; old notification action
   tokens must stay invalid even if restoration rolls back. Interrupted restore must
   either roll back or resume before messaging restarts.
5. Add native settings UI: SAF create/open document, passphrase confirmation,
   progress/cancel, owner/scope summary, explicit replace/migration confirmation
   and actionable errors. Never advertise a hardware-encrypted file copy as a
   portable backup. Restore must not automatically send the backed-up outbox
   before the user confirms reconnection.
6. At the release gate, run the prepared envelope/archive/secret fixtures, then
   test a disposable account across different Keystores/slots, process death at
   commit boundaries and unchanged identity/trust/message/outbox/pack IDs.
