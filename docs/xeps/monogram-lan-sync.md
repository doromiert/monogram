# Local proposal: Monogram authenticated LAN settings synchronization

Status: experimental local proposal, version 1. No XEP number is assigned and no
XSF approval is implied. Proposed capability: `urn:monogram:lan-sync:1`.
Implementations: identical `sync-core` Rust source on GTK and Android/JNI.

## Scope and relationship to XMPP

XEP-0174 provides serverless messaging, not an interoperable account/password,
portable wallet, pack and folder synchronization format. XEP-0049/0223 private
server storage also requires a server and does not establish direct local device
trust. This proposal defines a separate LAN transport for portable client state;
it sends no XMPP messages and publishes no credentials to PEP. A future XMPP
capability advertisement could be `<feature var='urn:monogram:lan-sync:1'/>` in a
`disco#info` result, but advertisement alone MUST NOT enroll a device. Current
clients discover locally and do not publish this capability through XMPP.

## Discovery and enrollment

IPv4 UDP multicast `239.255.85.77:45950`, TTL 1, carries bounded JSON beacons:

```json
{"format":"monogram-sync-v1","id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","port":49152,"pair":null}
```

While a typed-code screen is open, `pair` contains `name`, certificate SHA-256
`pin`, and expiration in Unix milliseconds. Discovery is untrusted. Only
private/link-local/loopback destinations are accepted; unknown device beacons
never initiate a credential exchange.

A QR encodes `monogram-sync:` followed by unpadded base64url of UTF-8 JSON:
`format="monogram-lan-sync"`, `version=1`, device UUID, sanitized name, up to
eight IPv4 socket endpoints, lowercase SHA-256 DER-certificate pin, a random
32-byte standard-base64 one-time `code`, and expiration (120 seconds).
Receiver connects with TLS 1.3, verifies the exact certificate pin and standard
TLS signatures, then sends a `pair` Hello containing the one-time code, its UUID,
name, certificate pin and listening port. A successful response supplies a fresh
random 32-byte pair token. Consuming or closing the invitation invalidates it.

Typed enrollment uses RFC 6238 SHA-256, 30-second steps, eight decimal digits,
with an ephemeral random 32-byte secret retained only by the code-displaying
device. The receiving device uses the displayed digits as a SPAKE2 password
(RustCrypto `spake2` 0.4.0, Ed25519 group). Identities are UTF-8
`monogram-sync:1:<uuid>:<certificate pin>`. Initiator uses role A, displayer role B.
A `totp` Hello's `token` is JSON containing `step` and base64 PAKE `message`.
Only the current or adjacent step is accepted; at most five requests are allowed
per two-minute invitation. Server returns its PAKE message and a base64
HMAC-SHA-256 server confirmation; client verifies it and returns the client
confirmation before enrollment. HMAC input is the UTF-8 role (`server`/`client`)
followed by the canonical JSON tuple:

```
[1, clientUUID, clientName, clientPin, clientPort,
 serverUUID, serverPin, helloTokenString, base64ServerPakeMessage]
```

The shared PAKE key is the HMAC key. Role separation and this transcript bind the
untrusted discovery data, TLS certificate and actual paired identities. A LAN
attacker cannot use an advertised pin to bypass PAKE or obtain an offline
password verifier. No peer token or portable record precedes mutual confirmation.

## Framing and exchange

TCP transports TLS 1.3. Each JSON frame has a four-byte big-endian byte length,
maximum 8 MiB. A Hello contains `version`, `action`, `id`, `name`, `pin`,
`endpoint` (port), `excluded` owner JIDs, `token`, and `manifest`.
An `exchange` Hello authenticates a saved pair token and pin. The response contains
its `manifest` and requested record `want` keys. Initiator sends a count, then each
requested metadata frame followed by exactly `size` raw plaintext bytes inside
TLS. Responder sends a count and its newer records in the same form. There is no
compression and no unauthenticated plaintext fallback.

Metadata fields: `kind`, canonical owner JID, string `id`, Unix-millisecond/Lamport
`stamp`, origin UUID, lowercase SHA-256 plaintext `digest`, and byte `size`.
Key is the compact JSON tuple `[kind,owner,id]`. Version order is lexicographic
`(stamp,origin)`; local edits choose max(now,lastCounter+1). Excessively future
versions (>24 hours), invalid metadata, unexpected records and checksum failures
are rejected. Exact metadata/bytes are forwarded without rewriting origin.

## Record schemas and native application

* `account/login`: `{jid,host,port,direct_tls,password}`.
* `token/<provider>`: `{value}`, providers `giphy`, `telegram-packs`, `translation`,
  `monero-daemon` only.
* `pack/<UUID>`: stored XMSP v1 archive plus `manifest.sync` fields
  `{id,source,installed,private_pack,connected,auto_sync}`. Item `id`s are stable
  UUIDs. Existing XMSP integrity/media/path bounds apply. Manual import/export is
  independent from sync identities and source policies.
* `folders/folders`: `{rules:{name:rule},order:[names including All],all_hidden,
  rooms?:[JIDs]}`. Rule fields: types (`contacts`, `non_contacts`, `groups`,
  `channels`, `bots`), boolean exclusions (`exclude_muted`, `exclude_read`,
  `exclude_archived`), include/exclude JID arrays, optional stable icon name and
  pinned JID array. At most 64 uniquely named folders, 1,024 members/list and
  512 KiB JSON. Platform-local numeric dialog IDs never cross devices.
* `fingerprint/<stable identifier>`: `{address,mode,device,fingerprint,trust}`.
  Modes `legacy`, `classic`, `pq`; fingerprints are 64 hex characters for the
  first two and 128 for PQ. These are public verification references. Private
  identity/session/key-store files MUST NOT be imported as active identities.
* `monero-settings/settings`: `{enabled,network,daemon,username,require_encryption}`.
* `wallet/<primary address>`: `{name,network,address,keys,password,height,node,
  max_index?}`; keys are base64 of an encrypted Monero `.keys` file, at most
  4 MiB. Validate primary Base58 address checksum/network and locally open the
  recovery file to verify its address before installing it in a wallet engine.
  Destination wraps the native wallet password under its own authenticated vault.
  New restorations rescan from height zero rather than trusting a remote cache
  checkpoint. Do not copy active payment approvals, claims or live caches.

The encrypted portable vault can relay a type before its native platform supports
operating it (currently desktop Monero recovery/spending). Native application
errors remain visible and records remain staged for retry where appropriate.

## Storage, cancellation and errors

A platform-protected 256-bit key encrypts index and chunks with AES-256-GCM,
96-bit random nonces and authenticated object key/digest/size/chunk index.
64 KiB chunks stream without holding the index mutex across network I/O.
Private staging, fsync and atomic replacement protect installed state. An
exclusive store lock prevents two processes writing/cleaning the same vault.
Stopped or unpaired sockets are shut down; generation checks fence native imports.

Limits: 32 direct peers, four incoming connections, 4,096 records, 128 MiB per
record, 8 GiB indexed content, 15-second I/O timeout, bounded JSON framing.
Schema/authorization/checksum/clock/size/expired-code errors fail closed and surface
as user-visible status. Account exclusion is device-local and purges its cached
records. Signing out is not a remote wipe; other mesh links have their own trust.
Device removal does not revoke access granted by another paired path.

## Verification and references

Shared tests cover TLS graph forwarding, convergence, wrong pins, forgotten peers,
at-rest tamper/wrong-master rejection, typed-code PAKE confirmation/single use,
and bounded folder schemas. RFC 6238 SHA-256 vector: secret ASCII
`12345678901234567890123456789012`, Unix time 59 seconds → `46119246`.
Android tests exercise real pack storage/update IDs and XMSP round trips; native
GTK checks cover reply navigation, About and responsiveness. No real wallet funds
or personal credential stores are used by these tests.

Sources: [RFC 8446](https://www.rfc-editor.org/rfc/rfc8446),
[RFC 6238](https://www.rfc-editor.org/rfc/rfc6238),
[RustCrypto SPAKE2](https://docs.rs/spake2/0.4.0/spake2/),
[XEP-0174](https://xmpp.org/extensions/xep-0174.html),
[Monero recovery files](https://www.getmonero.org/resources/user-guides/restore_from_keys.html).
