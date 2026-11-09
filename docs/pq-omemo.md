# Experimental PQ OMEMO2

Implemented on `feature/pq-omemo` as an explicit experimental option for direct
chats and members-only, non-anonymous MUC rooms. Classical XEP-0384 OMEMO 2
and legacy OMEMO retain their existing keys; automatic mode does
not select this experimental protocol.

This targets Monocles' experimental `urn:monocles:omemo-pq:1` profile, not
standard XEP-0384 OMEMO 2. It must have separate identities, trust decisions,
sessions and PEP nodes. Selecting it must never silently fall back to another
protocol or plaintext. Existing OMEMO databases must not be migrated in place.

## Use

Open a direct chat or MUC → Conversation security → OMEMO version →
**PQ OMEMO2 · Monocles (experimental)**. Enabling it publishes a new public
identity in its own PEP namespace. Review the hybrid fingerprints on the PQ
devices page, including any other devices on your own account. Both identity
keys are bound into that fingerprint. Classical blind-trust approvals do not
approve PQ devices. Blocked devices are excluded; unreviewed active devices
prevent sending rather than silently reducing the recipient set.

MUCs use the same per-account device sessions and hybrid trust as DMs. The room
membership is checked before encryption and again before sending; current
device echoes are ignored. Live occupant identities are captured before crypto
work waits, while MAM recovery requires the historical server-supplied real JID.
A typed room context is journalled atomically with the ratchet so recovery cannot
resolve an old nickname through a different current occupant. Text, edits,
replies, reactions and encrypted attachment descriptions stay inside SCE.
MIX channels are not enabled for this mode. Devices on an older
Monocles protocol revision (including Round-3 Kyber or pre-v4 bundle/handshake
authentication) are incompatible. The native PQ database lives in Android's
no-backup directory and survives reconnects. Android automatic backups and the
existing experimental classical-key migration helpers do not export/restore it.

## Build

Use Java 21 for the build, Gradle 8.13, Rust `nightly-2026-03-23`, Clang/libclang,
CMake, Ninja, pkg-config, protobuf/protoc, Go, Perl and Make. Android builds need
NDK **28.2.13676358** for this fork (Monogram's other native code still uses its
existing NDK). Set `ANDROID_NDK_HOME` and, if libclang is outside the loader's
search path, `LIBCLANG_PATH`. Native bindgen discovers Android headers through
the NDK compiler rather than the host compiler.

```sh
bash scripts/build-pq-omemo.sh java
bash scripts/build-pq-omemo.sh host
bash scripts/build-pq-omemo.sh arm64-v8a
./gradlew -PmonogramAbi=arm64-v8a :TMessagesProj_App:assembleAfatDebug
```

Run the native script for every ABI included in an APK. Packaging fails when a
requested ABI's PQ library is missing. The source scripts refuse a mismatched
upstream revision or tracked source modifications. The Java fork is built from
source as a Gradle dependency; Maven workflows first run the `java` command.
Its Java runtime dependency requires Java 17 or newer on desktop test hosts.
The consuming Android app also targets Java 17 bytecode so AGP generates the
shared record-desugaring helpers used by the fork; see the
[Android record-desugaring documentation](https://android-developers.googleblog.com/2023/06/records-in-android-studio-flamingo.html).

## Verification

```sh
./gradlew :xmpp-core:pqTest :xmpp-core:pqInteropTest
# With Prosody, OpenSSL and keytool available, also checks PEP/TLS over localhost:
bash scripts/test-pq-omemo.sh
```

`pqInteropTest` compiles the unmodified pinned Monocles message codec with
JVM-only platform adapters and exercises handshakes in both directions and
subsequent ratchet messages. The tests also cover upstream/NIST known answers,
hybrid key changes, signature/commitment tampering, replay records, restarts,
out-of-order messages, and atomic inbox/ratchet rollback. Android SQLite checks
exercise account isolation, thread ownership and crash-safe transactions.
These checks do not constitute testing a complete Monocles Android installation.

Verified on 2026-11-09: 18 PQ tests and three tests against the unchanged Monocles
codec passed, including MUC messages in both directions, shared DM/MUC sessions,
wrong-room rejection with ratchet rollback, optional unknown content, encrypted
caption metadata, durable room-context replay, and MAM recovery after key approval.
The Android media-counter regression and fresh signed APK build are checked in
the release delivery. The latest fetched Monocles revision
`f4dee2517cfc3002cc6e3f8078baf0d489b307a8` has unchanged crypto sources relative
to the pinned reference below. This is codec/server interoperability evidence;
a complete Monocles Android installation was not exercised.

Earlier verification on 2026-11-03: seven upstream native bundle/authentication tests,
16 Monogram PQ tests (including localhost TLS/PEP), two cross-codec tests, and
25 selected Android storage/protocol/reaction/retraction checks passed. Maven
compilation and the signed ARM64 `0.2.190-pq.1` APK build passed. APK inspection
verified the expected certificate, 16 KB ZIP alignment, the new native library,
the desugared record helper, preserved existing native-library hashes, required
classes/licenses, and exclusion of the JVM interoperability adapters.

The broader Android selection exposed an existing failure in
`schemaEightKeepsKnownTextAndModernReplayCannotPoisonItsArchivePage`: its synthetic
old-schema fixture repeats an already-created newer table. The failure also
reproduced with the original `HEAD` store source. That unrelated fixture is
unchanged. No phone screenshots or real-account messages were used, and the new
initial APK had not yet been tested on an Android device.

Pinned upstream sources:

* [Monocles Chat](https://codeberg.org/monocles/monocles_chat), commit
  `49c0e78045532a78033ffd448ca4316859267bf2`.
  `docs/proto-xep-omemo-pqxdh.md` v0.0.13 and
  `src/main/java/eu/siacs/conversations/crypto/axolotl/XmppOmemo2Message.java`.
* [Monocles libsignal fork](https://codeberg.org/monocles/pq-omemo-2), commit
  `10861bff671740174f4e99f7e30dc492fcf6ec8b` (0.94.1).
  The ordinary Maven libsignal artifact is not a substitute for this fork.

The profile uses ML-KEM-1024, ML-DSA-87 plus a classical identity, bundle v4,
authenticated PQXDH handshakes, a continuing PQ ratchet, and the KMAC256 v3
payload derivation/commitment. These are upstream protocol choices, not a new
Monogram cryptographic construction. Experimental interoperability does not
constitute an independent security audit or an XSF standard.

## Attribution and licensing

`PqOmemoPayload.java` and its upstream known-answer test vectors are adapted
from Monocles Chat's `XmppOmemo2Message.java` and
`XmppOmemo2MessageTest.java`, respectively, at the revision above, under
AGPL-3.0-only. The libsignal fork is also AGPL-3.0-only, copyright Signal
Messenger, LLC and its contributors. Preserve the upstream notices and license
when distributing source or native artifacts; distribution must provide the
corresponding source, including build instructions and Monogram adaptations.
