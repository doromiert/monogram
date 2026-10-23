# Checklist codec draft 1

Status: bounded codec and XML transport types exist. The current article send path
uses readable Markdown; interactive/collaborative application of these envelopes
is not integrated. This is a draft for review, not a claim of shipped collaboration.

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

Publication requires fixing the actor/authorization model and conflict resolution,
writing complete operation vectors, and integrating delivery/replay/application.
Mutations must be accepted only from an authorized authenticated actor. Legacy
OMEMO outer metadata is unauthenticated and must not be used for these documents.
Plain stanzas and authenticated OMEMO2 protected content may carry them once the
application rules are implemented. Markdown remains the readable compatibility path.

Sources: `MonogramChecklists.java` defines semantic encoding/validation;
`ChecklistContent.java` defines the XML shape; `MessageMarkup.java` preserves the
opaque envelope. No Telegram TL object is deserialized from network data.
