# Group read markers and device read state

Unreleased source adds group displayed markers to the existing durable read
queue. [XEP-0333](https://xmpp.org/extensions/xep-0333.html) requires room-assigned
stanza IDs when the room advertises XEP-0359. A sender-selected stanza ID is not a
unique room reference. Monogram tags a usable reference only after room feature
discovery; duplicate/missing room-assigned IDs cannot become marker candidates.
The internal `room-displayed` alias is local metadata, never an XML extension.

Only newly explicit local reads of readable, visible incoming content can queue
a group marker. History inheritance, enabling the option and receiving a marker
do not queue one. Existing queue/anchor tables preserve chronological monotonic
state and coalesce the newest eligible read per conversation across restarts.

Native Privacy settings adds **Include group read receipts**, off by default and
effective only when **Send read receipts** is on. Existing DM-only consent does
not expand automatically. Group markers are bodyless ordinary groupchat stanzas,
outside OMEMO encryption; the settings description says so. Sending requires a
joined room with assigned-ID support and rechecks the account/privacy generation.
Changing group consent clears pending group markers without deleting pending DM
reads. No key, trust or identity reset is performed.

Incoming group markers require an identified reader: a real JID from current
room presence or authenticated room archive, or an advertised stable occupant ID.
Live stable IDs must match current presence; delayed nickname-only identities
are not substituted from today's membership. Unknown, ambiguous and foreign-room
references are ignored. Archived markers apply after the containing page is
stored. Native outgoing checkmarks represent **at least one identified reader**;
per-reader lists/counts and everyone-read semantics are not implemented.

An identified own group marker can advance local incoming read state. DMs also
consume authenticated **sent** carbons of the user's own displayed markers.
These updates use the native/local chronological read path with publication
disabled, preserving notification counts without an echo loop. Ordinary outgoing
messages do not mark a chat read. References must already resolve uniquely in
the relevant conversation; unknown anchors are not guessed or backfilled.

Unreleased source now also wires the independent private
[XEP-0490](https://xmpp.org/extensions/xep-0490.html) PEP publication/catch-up path.
Native Privacy settings adds **Sync read state between devices**, on by default
for configured accounts, independently of both public read-receipt options.
The description explains private server storage and compatible-server support.
Turning it off clears its pending queue without changing public receipt consent;
turning it back on does not manufacture publication from previously read history.
It leaves already published server state in place.

The node uses whitelist access, persistence, max-items `max` and no last-item
broadcast. Publication requires discovered publish-options support; conflicting
node configuration is repaired to those same private options before retrying.
There is no public-node or contact-message fallback. Both notifications and
catch-up target the authenticated own account. Only own-account PEP notifications
trigger refresh. Initial connection and reconnect retrieve all items; completed
archive catch-up retries unresolved references from the cached snapshot rather
than issuing a request per message. Local reads reuse that snapshot and only
publish the coalesced durable newest candidate for each conversation.

DM references retain a separate internal `read-sync` alias for an unambiguous
actual own-server stanza ID. A MAM result ID is never substituted, and discovered
own-account stanza-ID support is required before consuming/publishing DM state.
Rooms reuse discovered room-assigned IDs. Unknown chats, unknown or ambiguous
references, wrong authorities, deleted/control/decrypting messages and full-JID
room private chats are ignored. An identified own-device publication may use an
outgoing message as a history anchor; ordinary outgoing messages never queue a
publication. Seen state advances chronologically, not by insertion ID. Account,
session and privacy generations fence queued work and publication retries;
logout disables the transport and clears account-owned private state.

The source is not verified interoperation. Server-assisted publication,
per-reader presentation, room private-message read sync and synchronization to an
anchor outside the loaded history window remain unsupported. Server state is
reconsidered when the bounded archive window finishes, but this feature does not
fetch extra history solely to resolve a read anchor.

Three additional core fixtures cover parsing, distinct server/MAM references and
private publication options; four additional Android fixtures cover private queue
persistence/consent, chronological coalescing, own-device references and room
isolation. They are prepared, not run.

Three core parser/reference fixtures and two Android group persistence/privacy cases are
prepared, not run. No build, phone operation, real message send or archive query
was executed. The next authorized release gate must cover actual room feature
discovery, all three message transports, anonymous/nonanonymous rooms, nickname
reuse, archived markers, option disable/reconnect, own-device reflection, native
checkmarks and unread/notification state with Monocles.
