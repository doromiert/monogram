# Checklist codec draft 1

Status: bounded codec, XML transport types and a draft durable application store
exist. The current article send path uses readable Markdown; none of the checklist
store's hooks are integrated into `MonogramStore` or `MonogramController`.
Interactive/collaborative checklists are **not shipped**. This is a draft for
review, not a claim of shipped collaboration.

Private namespace: `urn:monogram:checklist:1`. XML forms:

```xml
<checklist xmlns='urn:monogram:checklist:1' data='BASE64URL_DOCUMENT'/>
<checklist-update xmlns='urn:monogram:checklist:1' target='STABLE_MESSAGE_ID' data='BASE64URL_MUTATION'/>
```

Exactly one document or update element is permitted. No child elements or text are
allowed. A document has exactly `data`; an update has exactly `data` and `target`.
Data is bounded to 65536 ASCII base64url characters (terminal padding accepted).
Targets are 1–1024 printable ASCII characters excluding quotes and XML delimiters.
They must be stable XMPP references, never native/local message IDs.

## Binary document

Data is base64url of the following sequence. Multibyte numbers are big-endian;
booleans are a byte; text is an unsigned 16-bit byte length followed by strict
UTF-8 (not Java modified UTF-8). The entire decoded payload is at most 32768 bytes.
Reject trailing bytes, unknown versions, invalid UTF-8 and truncated values.

| Field | Encoding |
| --- | --- |
| Magic | Four bytes `MCL1` |
| Format | Unsigned byte `1` |
| Owner | Text: creator's bare JID |
| Revision | Signed 64-bit integer, at least 1 |
| Others may append | Boolean |
| Others may complete | Boolean |
| Title | Text |
| Item count | Unsigned 16-bit integer, at most 512 |
| Each item | Positive unique signed-32-bit ID; title text; completed boolean |
| For each completed item only | Completed-by bare JID text; nonnegative signed-32-bit Unix seconds |

Each text field is at most 4096 encoded UTF-8 bytes. Model constructors additionally
validate JIDs, duplicate item IDs and cumulative content size. A completion's actor
cannot be trusted merely because a JID appears inside this payload.

## Binary mutation

Magic `MCU1`, format byte `1`, operation byte, reference text, base revision int64,
nonce int64, item ID int32, completed boolean, and (APPEND only) title text. Operation
values are `0` for TOGGLE and `1` for APPEND; consumers must reject other values
before interpreting them. The same text/size/trailing-byte checks apply.

Publication requires finalizing the actor/authorization model and conflict resolution,
writing complete operation vectors, and integrating delivery/replay/application.
Mutations must be accepted only from an authorized authenticated actor. Legacy
OMEMO outer metadata is unauthenticated and must not be used for these documents.
Plain stanzas and authenticated OMEMO2 protected content may carry them once the
application rules are implemented. Markdown remains the readable compatibility path.

Sources: `MonogramChecklists.java` defines semantic encoding/validation;
`ChecklistContent.java` defines the XML shape; `MessageMarkup.java` preserves the
opaque envelope. No Telegram TL object is deserialized from network data.

## Existing draft store and missing integration

`MonogramChecklistStore` is an unexposed implementation scaffold. Its receiver
API assumes the caller has already established an authenticated actor:

- `acceptDocument(localMessage, dialog, readableBody, content, authenticatedBare,
  room, realSenderVerified)` checks that the owner matches that actor and the
  readable fallback exactly matches the decoded document.
- `acceptUpdate(controlMessage, dialog, content, authenticatedBare, room,
  realSenderVerified)` persists a mutation, including when its target has not
  arrived. A positive control-message ID must belong to that dialog and remain
  readable and undeleted. A room actor requires a verified real-JID binding.
- `reconcile(dialog)` retries pending operations after IDs/documents arrive.
  Mutations use a per-dialog/actor/target/nonce uniqueness constraint. Unknown
  targets stay pending; unauthorized or stale-revision mutations are rejected.
- `snapshot`, `target` and `project` support local action validation and native
  rendering. Target resolution checks all message aliases in the dialog,
  including ordinary messages and deletion tombstones, so a colliding alias
  cannot silently select a checklist. Deleted/unreadable documents cannot be
  projected or mutated.

Before exposing this feature, integrate all of the following together:

1. Database creation/migration, deletion cleanup and backup policy for the draft
   document, update and hidden-control tables.
2. Receipt only at the authenticated plaintext or decrypted OMEMO 2 boundary;
   reject unauthenticated legacy OMEMO outer metadata.
3. Native send/edit/toggle/append requests, real recipient/room permissions,
   stable XMPP references and encrypted transport selection.
4. Document projection in live messages, archive imports, corrections and
   history/search; hide authenticated controls from unread counts, notifications,
   last-message previews and visible history.
5. Durable pending updates, outbox acknowledgments, failures, correction targeting
   and reconnect/account/logout fences.
6. Convergence, bounded pending-storage retention and independent-client vectors.

The standalone `MonogramChecklistSafetyTest` exercises replay across a database
reopen, alias ambiguity, deleted targets, actor permissions, room identity,
identifier validation, byte bounds and revision overflow. It explicitly creates
the draft tables itself. Passing it does not establish any of the missing native
integration above, nor prove convergent collaboration.

## Conflict rule is not ready for publication

The current draft applies a mutation only when `baseRevision` equals the current
revision. Two authorized users can create different mutations from revision 1;
if each client receives its own operation first, each reaches a different
revision 2 and rejects the other's operation as stale. Neither nonce deduplication
nor durable replay fixes this divergence. Completion times are also currently
assigned by the receiving device rather than carried in a canonical event.

Do not expose the draft as collaborative checklists until this is replaced with
a specified, tested convergence rule. One possible next design is a deterministic
event log: retain the immutable original document and authenticated operations,
give every event a stable actor/event identity and operation time, and rebuild
state in a universally defined order. Such a design must specify concurrent
toggle/append conflicts, item-ID collisions, owner permission changes,
corrections, missing predecessor events and group membership. An alternative is
explicit owner-authoritative sequencing with signed/OMEMO-authenticated state
acknowledgments. These are proposals, not implemented behavior or a compatibility
promise for namespace version 1.
