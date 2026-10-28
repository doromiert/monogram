# MUJI group calls

Open a room's existing menu → Group call → Join voice call or Join video call.
Opening the screen does not start capture. Permission and an explicit join are
required. The ongoing notification keeps the call available in the background
and provides Leave. Microphone, camera and speaker controls use native cells.

The implementation follows [XEP-0272 version 0.2.0](https://xmpp.org/extensions/xep-0272.html)
and was checked against the [Monocles MUJI coordinator](https://codeberg.org/monocles/monocles_chat/src/commit/e7d071a782da1c4149b1a42c5dbb473316dbffe7/src/main/java/eu/siacs/conversations/xmpp/jingle/MujiConferenceManager.java).
Monocles' ready/preparing presence and nickname ordering are interoperability
references. This is a Smack integration of Monogram's existing Jingle engine;
Monocles' AGPL application code and dependencies are not bundled.

Preparation waits for echoed room presence. Ready participants establish a mesh
of separate Jingle legs, addressed to full real JIDs and tagged with the room.
Only current occupants with ready advertisements can authorize a leg. Anonymous
or public rooms, unknown resources, outsiders and blocked peers are rejected.
Changing an occupant identity, leaving, blocking, losing the connection or ending
the call closes affected legs. A participant leaving does not end the other pairs.
A shared WebRTC factory/audio device and capture tracks serve the mesh. Native
peer-to-peer privacy preferences apply per participant; TURN is required where
direct candidates are disallowed. Calls over a configured SOCKS5/Tor proxy are
currently refused, and enabling a proxy ends an existing call. Capture and wake locks belong to the foreground
service, with teardown fenced before another call can start.

Current limits: eight participants, Opus audio and optional VP8 video, one active
call across accounts. Dynamic content addition, SFUs, external call-invite cards,
and OMEMO authentication of DTLS fingerprints are not implemented. The UI states
that transport is encrypted and identities are unverified. Joining with video
enables the negotiated camera track; turning it off pauses that track.

Tests cover three-participant audio/video offer/answer, scoped trickle ICE,
outsider rejection, participant departure and blocking over disposable local
Prosody connections. SDP is synthetic in these tests. Android checks cover capture
permissions, queued-close fencing, SDP content naming and existing direct-call
lifecycle behavior. Live multi-device audio/video and Monocles runtime
interoperability have not been verified; no phone access or screenshots were used.
