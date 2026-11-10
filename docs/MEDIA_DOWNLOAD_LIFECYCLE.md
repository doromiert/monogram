# Media download lifecycle

Attachment downloads belong to the account controller rather than the open chat.
Closing and reopening a conversation keeps its active transfer and native progress
registry. Incoming response headers may upgrade a generic file to a photo or video;
both the original native filename and the replacement filename then refer to the
same transfer until it finishes.

Cancelling a download releases its filename so a retry can start immediately. The
cancelled worker can finish later, after the retry has registered that same name.
Cleanup must remove the old job by identity and clear progress only when that
identity still owns the filename. Otherwise the old worker erases the retry's
progress even though its bytes are still downloading. Upload cleanup already
enforced this rule; download cleanup now does too.

`MonogramUploadProgressTest.lateDownloadCompletionPreservesRetryAndMetadataAliasProgress`
covers an old cancelled worker finishing after a retry, preserving the retry's
loading state, byte progress and throttling timestamp, while still removing the
old worker's remaining alias. It also checks that the retry's eventual completion
cleans up both original and metadata-upgraded names. This is an isolated registry
test; it sends no messages or network requests.

Transfers interrupted by a process exit or explicit cancellation still restart
from the beginning. HTTP range resume is not implemented, and an encrypted file
is committed to the cache only after complete authenticated decryption and any
advertised file hashes have been verified.

A manual picture-button tap explicitly enters the XMPP FileLoader route before
updating ImageReceiver. This prevents an unchanged failed image key from
suppressing the retry. A tap during an automatic job promotes that same job to
manual rather than cancelling its existing bytes. If an automatic size policy
already declined it, the promoted request retries without that automatic limit;
the hard byte bound and authenticated decryption remain mandatory. Transfer
errors remain available in Message diagnostics without private URL text.


## Desktop transfer progress

Desktop now uses the same SFS/OOB metadata and AES-GCM URL profile for direct
plaintext/OMEMO 2 reception and explicit HTTPS downloads. Its native file cards
provide Download/Cancel/Retry/Save As, preserve jobs when leaving a chat, and cancel
on account changes/removal. Authentication, size and advertised supported hashes
are checked before cache commit; file keys never enter the HTTP request fragment
or error output. Cache/export verification and account-separated paths preserve
the same integrity boundary.

The test-only `GenerateFileVectors` exports 32 actual received-wire/model cases and
four actual `FileTransfer` cipher cases (12/16-byte nonce, empty/binary data and
SHA-256/SHA3-256/BLAKE2b-256). They are consumed by desktop's Rust and private
HTTPS/GTK/XMPP checks. Desktop now also implements XEP-0363 direct uploads using
private immutable inputs, provider size checks, four streaming workers, fresh
AES-GCM keys, opaque encrypted slot metadata and explicit Cancel/Retry. Pending
attachments persist across restart without automatic retransmission, and account,
session, encryption and fingerprint checks fence final sends. Native media remains
generic file cards: thumbnail/type upgrades, native media viewer/playback, auto-download settings,
caption edits/forwarding and complete cache management remain open. See
[desktop transfer details](../../desktop/docs/FILE_TRANSFERS.md). No physical
mobile/desktop UI exchange is established by these fixture comparisons.
