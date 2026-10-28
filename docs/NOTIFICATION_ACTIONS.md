# Notification reply and mark-read actions

## Source audit

`WearReplyReceiver` handles native notification replies, including wearable/car
actions. `AutoMessageHeardReceiver` handles native Mark as read. Both previously
returned while cold peer loading or XMPP read work continued without `goAsync()`.
Actions identified an account only by slot and dialog ID; both can be reused
after logout. Replies also marked messages read immediately after asking the
native sender to send, before checking its durable local row.

## Unreleased changes

New XMPP notification actions bind an opaque per-account-incarnation token into
both Intent extras and its data URI. `PendingIntent.FLAG_UPDATE_CURRENT` cannot
retarget an old incarnation or captured read anchor because they now have
different identities. Logout clears the token. Missing/old tokens, changed
accounts, inconsistent dialog/anchor extras and unsupported Telegram topic IDs
are rejected. Validation repeats before cold peer data is inserted into native
caches and before a queued reply changes read state. Tokens are app-private
bookkeeping, not credentials, and must not be restored with an account backup.
Notifications built by an older version need to be refreshed before their reply
or read actions work; rejecting an old action does not remove messages or unread
state.

Receivers retain a bounded asynchronous result through local storage work, with
an eight-second release deadline. Reconnects, fingerprint retrieval and network
delivery do not hold a broadcast result open. A deadline releases the broadcast;
it does not cancel a message the user explicitly submitted.

Replies still use `SendMessagesHelper` and the existing durable native outbox.
An optional ordinary-send callback confirms the dialog/message row and its
outgoing random ID on the native storage queue after insertion. Only a confirmed row advances the captured read
anchor. Failed pre-queue work leaves the original messages unread; queued or
failed sends remain in the existing outbox. There is no second notification
queue, plaintext fallback, automatic trust decision or dependency on being
connected. Existing passcode policy is checked again before accepting a reply.

Mark as read uses the existing chronological local-read API directly, without
first loading a Telegram peer from cache. Its callback covers native/XMPP read
state and notification counts. Successful actions retain native reaction-read
bookkeeping and wait for the storage queue before finishing. XMPP local reaction
reads no longer issue unsupported Telegram read-reaction RPCs.
The mark-read PendingIntent is immutable; only replies need mutable RemoteInput.

The previous non-XMPP receiver paths remain available for their original callers.

## Verification still required

Two account-incarnation/Intent identity fixtures are prepared, not run. They do
not prove Android broadcast lifetime, native database commitment, lock-screen
reply delivery or reconnect behavior. The next authorized phone-update gate must
cover cold DM/MUC reply, offline reply then reconnect/restart, later incoming
messages remaining unread, app passcode, mark-read while locked, storage failure,
account logout/reuse and timeout completion. No real-contact message was sent
and no phone action was performed during implementation.

## Mentions-only room mode

The existing chat notification popup (both native variants) and custom notification
screen expose Mentions only for rooms. The profile notification row shows the
selected mode. This is a per-account, per-room preference with native topic
inheritance, retained across dialog/full-chat refreshes. Ordinary room messages
use the suppressed-room override; messages marked as mentions take the existing
native sender-override path. Unread/history state is preserved. Explicit mute,
unmute or resetting custom settings removes the mode. Direct chats are unaffected.
