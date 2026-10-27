# OMEMO device review and room trust

Room encryption still uses the existing legacy OMEMO manager and fingerprint
store shared with direct chats. OMEMO 2 group encryption is not implemented; the
room's version row now explicitly selects legacy OMEMO and disables automatic
selection. This changes outgoing messages, not another member's protocol version.

Foreground chats discover devices on opening, incoming messages, verified room
membership changes and PEP device-list events. Lookups run off the UI thread;
key/member updates coalesce for five seconds, ordinary-message checks run at
most once a minute, and all checks stop when the chat closes/backgrounds.
Discovery failure cannot approve a fingerprint. Unreviewed devices appear in a
bounded dialog showing full JIDs, device IDs and exact fingerprints, with Trust
shown, Deny trust and Later. Current-device keys are excluded. Decisions use the
existing legacy/modern APIs, survive leaving the screen, and reject a changed
account/session. Previously decrypted messages are not deleted by denial.

A separate, default-off room setting permits automatic trust for known members.
A freshly validated complete room member list takes precedence over local cached
membership and includes newly added/offline members. Device decisions are account
wide, so approvals also apply in DMs. Disabling the policy does not revoke earlier
approvals. Explicit device blocks are not overridden by automatic trust.

Key/member events invalidate cached plaintext fallbacks so encryption can be
reconsidered. They retain a valid encrypted selection: the real encrypted send
already checks membership and device trust. Explicit user trust/setting changes
still force selection to refresh. Manual encryption pins remain intact.
Room membership is already checked on every encrypted send; this is not a second
room encryption implementation or a global trust-all default.

Fingerprint discovery invalidates cache entries on published device-list changes.
The UI retains previous rows during partial refreshes and failed account lookups;
failed cached rows are labeled accordingly. Only a successful complete lookup can
prove a previously displayed device disappeared. Real device-list changes can
still change the final count.

Checks cover review batching/key changes, member/account scoping, new members,
cache invalidation, stable partial display, manual encryption pins, plus encrypted
DM/room exchange and preserved blocks against disposable local Prosody accounts.
No real contact fingerprints were trusted/blocked, and no real messages were sent.
These tests do not diagnose Bob's particular unreadable message without its
message diagnostics, nor establish support for receiving OMEMO 2 room payloads.

Send-delay regression (0.2.179): 0.2.178 discarded encrypted selections on passive
device/member events, causing the next send to probe and then encrypt a second
time. Five alternating warm runs on disposable local Prosody accounts measured
167.60 ms median with the redundant probe versus 84.13 ms with the selection
retained. Membership IQs fell from 16 to 8 per send; both pre/post-encryption
membership checks remain. These are localhost preparation times, not phone or
federated-server latency. The regression check also verifies plaintext results
are invalidated and manual pins stay manual. Existing trusted/untrusted/new-device
integration tests still pass. No trust checks or recipient validation were removed.
