# Monocles wire-code interoperability harness

The `pqInteropTest` task compiles the **unmodified** `XmppOmemo2Message` source
from the pinned Monocles revision, fetched by `scripts/prepare-pq-interop.sh`.
It exchanges messages with Monogram's codec in both directions using the built
native libsignal fork. Tests include initial hybrid-authenticated handshakes and
subsequent ratchet messages. Upstream code retains its upstream license.

The classes here supply JVM-only platform/XML adapters and a narrow session
adapter to the real native `SessionCipher`. They do not replace either wire
codec or cryptographic implementation. They are test fixtures, never APK code.
This tests wire compatibility, not a full Monocles Android installation, server
configuration, device sensors or a production security audit.
