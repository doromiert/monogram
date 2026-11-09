# Performance investigation ledger

## 0.2.193: media-count ANRs and profile contention (2026-11-09)

Primary regression: the media-count handler introduced in 0.2.192 reused full
message search once per requested filter. Opening a contact requests multiple
counts; each pass reconstructed messages, embedded replies, reactions and media,
including file-header checks, while holding the store monitor. Phone ANR dumps
showed the UI blocked in `chatOpened → chatState → MonogramStore.jid` or avatar
rendering while the cache worker ran `mediaCounters → search → getMessage`.
The input dispatcher timed out after five seconds.

The replacement makes one metadata pass in batches of at most 100 rows. It
releases the store monitor before XML/type classification, never reconstructs
full messages or replies, and never opens media files to count them. Cached
media types survive caption edits; deleted/control messages remain excluded.
A regression guard forbids full-message projection and pauses classification
while a separate chat lookup completes in 0 ms. A 251-row, three-filter fixture
completed in 65–86 ms on the test host. These are controlled host numbers, not a
claimed phone-wide speedup ratio.

A separate measured contention issue was also fixed. Symptom: pauses throughout scrolling and typing on Android API 37, rather than
steadily low rendering FPS. An ART sample located UI waits in
`MonogramStore.avatar` while background profile work held the same store monitor.
The profile writer encoded PNGs, wrote image files and serialized vCard XML
before releasing that monitor. The initial trace observed 2.12 seconds in avatar
lookups over approximately seven seconds, with a 507 ms maximum call.

| Experiment | Before → after | Decision |
| --- | --- | --- |
| Prepare profile images/files/XML outside the store monitor; retain a separate serialized writer lock and atomic DB commit | Controlled avatar read hit its 500 ms timeout while preparation was paused → 0–1 ms while preparation remained paused | Kept; regression also checks that a later queued profile update wins |
| Use the existing streaming XML flattener in `XmppProfile.xml()` | 168,114-character cold profile, median of five runs: 4.76 ms → 0.29 ms | Kept; old/new XML byte equality checked; existing profile field and XML namespace tests pass |
| Initial repeated serialization benchmark of one already-serialized card | Native cached `toString()` appeared faster than fresh flattening | Discarded as evidence for cold profile processing; replaced with fresh equivalent cards per iteration |

The image preparation test holds the expensive step behind a latch and reads
avatars on another thread, reproducing the actual shared-monitor dependency.
It fails on the original writer and passes with the fix. Profile writers remain
serialized, and profile/remote-avatar/cache metadata still commit together.

The initial phone trace requested 10 µs sampling; a supplementary post-fix trace
used 10 ms sampling. Different sampling overhead and user activity mean these
traces are **not** a quantitative before/after phone speedup comparison. The
controlled contention test supplies that comparison. The later short phone
sample did not reproduce the long avatar wait, but user testing then produced
the media-count ANR above. The profile fix alone was insufficient. Final phone
acceptance of the media-count correction remains a separate user check. Raw phone traces remain private ignored artifacts.
No screenshots or account test messages were used.

Verification: `MonogramMediaCounterTest` (three cases),
`MonogramProfileContentionTest`, `XmppProfileTest`, `XmppXmlTest`,
a fresh signed ARM64 APK, and the existing native/class/license/alignment audit.
The tests guard the lock dependency and preserved profile fields; no permanent
telemetry or user-data collection was added. Further reported pauses require
new samples rather than speculative caches or disabled features.
