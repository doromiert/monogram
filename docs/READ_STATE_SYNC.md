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

This is not complete offline device synchronization. [XEP-0490](https://xmpp.org/extensions/xep-0490.html)
defines private PEP read state and catch-up independently of public read receipts.
That publication/catch-up path remains unfinished; it must use whitelist access
and validated account-origin notifications before being enabled. No XEP-0490
support is advertised by these changes.

Three core parser/reference fixtures and two Android group persistence/privacy cases are
prepared, not run. No build, phone operation, real message send or archive query
was executed. The next authorized release gate must cover actual room feature
discovery, all three message transports, anonymous/nonanonymous rooms, nickname
reuse, archived markers, option disable/reconnect, own-device reflection, native
checkmarks and unread/notification state with Monocles.
