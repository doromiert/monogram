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
