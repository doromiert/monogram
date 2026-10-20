# Local chat organization

Pin/unpin, pin order and archive/unarchive use the existing native chat-list
controls and SQLite cache. Monogram also records the state per XMPP account and
local dialog ID so rebuilding the XMPP dialog list cannot reset it. The facade
returns pinned chats and respects the requested main/archive folder. These
operations are local to this installation, not server-synchronized XMPP actions.

Logout clears the departing account's organization records before local peer IDs
can be reused. Separate account slots and separate account addresses cannot
share these settings. Room bookmarks and membership are unaffected by archiving.

Phone acceptance for pin order and archive state across reconnect and restart
remains open; both focused local-state fixtures passed at the 0.2.119 release
gate.

## Unreleased native custom-folder work

Custom folders use the existing native `dialog_filter` / peer / pinned-peer
SQLite tables. Loading native folders is authoritative for XMPP accounts;
Monogram does not fetch an empty Telegram server list that could replace them.
Create/edit/delete persist through the existing native storage methods, including
explicit chat lists, exclusions, pinned order, names, colors and animation flags.
Native callbacks acknowledge local order/tag changes without publishing anything
to a service. Suggested Telegram server templates are empty.

Folder colors, tags and moving All Chats are available without subscription
gates. Local folders and their explicit chat selections do not inherit Telegram's
server folder-count limits. Native tags render under chat names when enabled.
The existing controls and account databases are reused; no second folder store
or account-Premium spoof is introduced. Telegram shared-folder invitation
controls are hidden with a local-only explanation, and old entry callbacks are
guarded.

Shared message pins are still unsupported. Choosing Pin or calling a native
pin/unpin action now explains that limitation instead of silently sending a
Telegram request or showing a successful pin confirmation. This is not an
implementation of shared XMPP pins or cross-device folder synchronization.

The custom-folder changes and two prepared facade regression cases have not been
built, tested or installed yet. Runtime acceptance must cover create/edit/delete,
selected/excluded chats, default-tab reorder, colors/tags, offline restart,
account switching and absence of subscription prompts at the next phone-update
gate. Do not use the 0.2.119 pin/archive checks as evidence for these additions.
