# Battery investigation, 2026-10-25

Android diagnostics showed Monogram consuming about 119% CPU in the background
(one fully busy core plus work on other threads), with `pool-5-thread-1` dominating.
No WalletSyncService or ClaimDepositService was active. Battery stats recorded
about 5 CPU hours against about 1h16m in the top-app state over the available
collection window. OEM battery percentages and Android's estimated mAh values
use different attribution models, so these readings do not establish an exact
comparison with Instagram or a precise percentage of battery saved.

A 12-second simpleperf sample from the phone attributed 52.7% of sampled CPU to
the account worker. The largest identifiable application path was
`MonogramController.Listener.roomChanged → MonogramStore.roomState/roomUser`,
including repeated SQLite roster-name and participant writes. Recreating the
process did not remove this workload during room initialization.

## Fix and repeatable measurement

Previously each room snapshot marked all participants inactive, resolved every
occupant's peer ID, rewrote each anonymous roster name, marked everybody active
again, and republished the full room to native UI storage. Even an unchanged
room snapshot generated work proportional to the entire room and unnecessary
native updates. Busy room presence can continue while the app is in background.

The store now reads the persisted participant identities/active flags once and
writes only membership or identity differences. Room metadata and own permissions
are written only when changed. The controller republishes only changed states or
actual local join/leave transitions. No persistent snapshot cache is added: the
source for each comparison is the database, avoiding stale invalidation rules.

Same local Robolectric/native-SQLite workload, same machine, three trials after
an initial roster save: 200 anonymous occupants, 20 unchanged snapshots per trial.

| Metric | Before | After |
|---|---|---|
| Changed database rows per trial | 12,040 / 12,040 / 12,040 | 0 / 0 / 0 |
| Elapsed milliseconds | 457 / 118 / 100 | 30 / 24 / 21 |
| Median milliseconds | 118 | 24 |

The deterministic regression budget is zero writes for unchanged snapshots.
Membership tests cover departure, rejoin, anonymous-to-known identity mapping,
permissions, and leaving/rejoining a room. Historical identities are retained.
This reduction is a measured local workload improvement, not a claim of a
matching percentage reduction in whole-device battery consumption. Normal usage
across a comparable charge interval is needed for that.

## Diagnostic attempts

- Java debugger attachment disconnected when the old process exited; no usable
  thread stacks were obtained from that attempt.
- `am profile` produced an empty file on this Android build.
- Direct run-as simpleperf could not set required profiling properties. The
  supported shell `simpleperf record --app ...` mode produced the useful sample.
- A temporary `security.perf_harden` change in the failed direct attempt was
  restored in a finally block; after shell-mode recording its profiling permission
  was restricted again. No battery statistics were reset, account data
  cleared, messages sent, or phone screenshots captured.

Raw diagnostic files remain in ignored `.state/`; reports above contain no chat
contents, wallet secrets or contact addresses. No speculative wallet, notification
or connection policy changes were bundled with the measured roster fix.

## Follow-up: incremental presence publication

The first follow-up phone trace still showed `publishRoom → roomUsers` consuming
23.9% of sampled CPU. Total short-sample CPU did not fall (129 vs 197 samples at
100 Hz); live traffic/startup were not controlled, so this is not evidence of a
whole-app battery improvement. It identified the next specific workload instead:
for every real member change, `roomUsers` rebuilt all users retained from departed
occupants and historical messages, not just current/changed members.

Presence persistence now returns the exact affected user IDs, including the old
and new identities when a nickname is remapped. Routine room publication projects
only those users. Explicit full-roster loads remain unchanged, as do historical
message authors and their profiles. Metadata-only changes update the chat without
rebuilding unrelated user objects.

Second isolated workload: 200 historical occupants plus one current member,
20 native presence projections per trial, three trials on the same machine.

| Metric | Before incremental publication | After |
|---|---|---|
| User objects projected per trial | 4,040 / 4,040 / 4,040 | 20 / 20 / 20 |
| Elapsed milliseconds | 447 / 197 / 184 | 5 / 3 / 3 |
| Median milliseconds | 197 | 3 |

The regression budget is one projected user per single-user change, independent
of how many historical users the room retains. Tests also assert that the full
roster remains available, departed users are included in the update delta, and
both old/new IDs are included for an identity replacement. This second fix is
measured independently from the no-op database-write reduction above.
