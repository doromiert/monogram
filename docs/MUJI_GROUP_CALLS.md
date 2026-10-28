# MUJI group calls

Open a room's existing menu → Group call → Join voice call or Join video call.
Opening the join prompt does not start capture. Permission and an explicit join
are required. Calls open Telegram's existing `GroupCallActivity` sheet, with its
microphone, camera, flip-camera, audio-route controls, participant rows, video grid
and fullscreen tiles. The native floating call control and ongoing notification
return to the same sheet. The separate settings-style call screen is removed.

`MonogramMujiService` implements the existing VoIP service hooks for that UI, but
skips Telegram engine initialization and cleanup. `MonogramMujiCall` projects the
MUJI roster into its native presentation model without creating an RPC handle.
One person with multiple resources has one row; local mute/volume controls apply
to that person's resources. Scheduling, recording, screen sharing, Telegram call
messages, invite links and server moderation controls stay hidden. Video calls
negotiate their camera at join time; voice calls cannot add video mid-call.

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
lifecycle behavior. Native-UI adapter checks cover service discovery without a
private-call registration or Telegram engine, resource grouping and departure,
mic/camera/volume routing, communication-device release and audio-mode restore,
and synchronous renderer detach before disposal. Live multi-device audio/video and Monocles runtime
interoperability have not been verified; no phone access or screenshots were used.
