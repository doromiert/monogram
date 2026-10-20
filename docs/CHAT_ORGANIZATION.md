# Local chat organization

Pin/unpin, pin order and archive/unarchive use the existing native chat-list
controls and SQLite cache. Monogram also records the state per XMPP account and
local dialog ID so rebuilding the XMPP dialog list cannot reset it. The facade
returns pinned chats and respects the requested main/archive folder. These
operations are local to this installation, not server-synchronized XMPP actions.

Logout clears the departing account's organization records before local peer IDs
can be reused. Separate account slots and separate account addresses cannot
share these settings. Room bookmarks and membership are unaffected by archiving.

Existing native custom chat filters and pinned messages still need their own
facade audit. This change does not claim shared room pins or cross-device chat
folders. Phone acceptance for pin order and archive state across reconnect and
restart remains open; both focused local-state fixtures passed at the 0.2.119 release
gate.
