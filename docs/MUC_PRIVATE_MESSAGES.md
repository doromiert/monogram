# Room-private message routing

Monogram does not currently expose room-occupant private conversations. An occupant
address such as `friends@conference.example.org/Alice` must not be reduced to a
direct conversation with `friends@conference.example.org`: unrelated occupants
would then share a conversation and a bare sender identity for corrections.

The direct adapter rejects messages carrying the MUC user marker. The session also
excludes known rooms from live, carbon and direct-archive imports, including old
messages without that marker. Native account startup restores persisted room
identities before recovering incoming encryption journals or connecting.

The recovery callbacks now recheck that restored room registry for both legacy
OMEMO and OMEMO 2. A direct journal entry captured before a room was recognized
cannot bypass those live-route checks after restart, including correction events
from an old or reused nickname. Unsupported entries remain in their encryption
journal without storage acceptance or outgoing acknowledgements; this change does
not delete authenticated retained content or reinterpret it as a room message.
Ordinary direct-contact journal delivery is unchanged.

`XmppRecoveredRoomRouteTest` uses an actual disconnected session and both delivery
callbacks. It checks retained corrections from old/reused occupant nicknames after
restoring room metadata, plus ordinary direct-contact delivery. It does not connect
to an account or send messages.

Remaining scope: a full-JID message from an entirely unknown room, lacking a MUC
marker, still looks like a direct resource message. Correct support needs reliable
room discovery and a separate occupant/session conversation model; it cannot be
solved by rejecting all full-JID messages or guessing from domain names. This
change does not claim full room-private-message support or independent-client
interoperability.
