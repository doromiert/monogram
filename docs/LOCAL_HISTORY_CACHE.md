# Clear local conversation history

Storage usage has a separate **Clear local conversation history** action for the
current account. Ordinary media-cache clearing keeps its existing behavior. The
history action requires confirmation and does not run automatically.

It removes downloaded message rows, attachment references, formatting, reply/
correction projections and message-linked annotations from the authoritative
Monogram history store, and matching positive-ID rows from the native facade's
history/media cache. Contact/room identities and configuration remain. Local
call summaries and message-local tags can be lost; named tag definitions remain.
The database ID sequence is retained, so new messages cannot reuse cleared IDs.
A watermark preserves messages arriving after the clear operation starts.

Accounts, credentials, OMEMO identity/session stores and replay/decryption
journals, wallet data, contacts, room memberships and server archives are not
cleared. Reaction/retraction event state is retained with its local resolution
reset, so authenticated deletion/vote records are not silently discarded.
This action does not send XMPP retractions, leave rooms, or delete server history.
Media files can still be removed through ordinary media-cache clearing. Logical
history clearing is not a secure-erasure guarantee for physical SQLite pages.

Unsent and scheduled messages cause an explicit refusal before history deletion:
send or remove them first. They can reference local originals, so clearing those
dependencies while claiming to preserve queued replies would be misleading.
Archived-history jobs are replaced rather than allowing an old page to repopulate
the cache during completion. Available history can be requested again on opening
conversations; full restoration is not promised.

Current OMEMO identity keys alone cannot reconstruct every old message. Ratchets
discard old message keys, server MAM retention varies, local-only events are not
server history, and a new device may never have received an old encryption key.
The confirmation explains these limits. Keeping durable decryption journals helps
replay where supported, but does not turn this into a recoverable backup feature.

Synthetic SQLite tests cover preserved identities/rooms/contact aliases,
watermark boundaries and non-reused IDs. Native-cache SQL is separately exercised
against a synthetic copy of its actual table schemas, including pending-message
guards and preservation of new/negative IDs. No real phone history was cleared
for verification.
