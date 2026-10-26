# Calendar history windows

The unreleased date-navigation adapter keeps an independent MAM window for each
open calendar conversation. The initial request fetches up to 50 results on or
after the chosen date and 50 immediately before its first archive UID. DMs use
the user's filtered archive; rooms use the room archive. Neither path advances
the normal newest-to-oldest synchronization cursors or marks a conversation read.

Scrolling backward or forward requests adjacent 50-result pages with the
window's first or last MAM UID. A request performs at most three page queries;
only the latest three fetched pages remain in the window's in-memory ID set.
Evicted messages remain in the existing database and can be fetched again in
the opposite direction. This bound applies to window bookkeeping, not to the
native chat view's independent row cache.

Local history queries select only IDs represented by the fetched pages. They
cannot jump over an unfetched gap into an unrelated recent-message cache. Room
`mam` and direct `archive` identifiers resolve through their existing ID maps.
Earlier/later pages use the existing directional archive ordering, including
timestamp ties, without changing durable sync cursors. Duplicate imports keep
their existing local identity and read state.

Window end flags are directional MAM results, rather than the complete flag of
the unrelated recent-history sync. Empty/control-only pages with an advancing
cursor can continue. A missing cursor on an incomplete response or a repeated
cursor leaves the window retryable. Native short-page and empty-page handling
does not mark an active calendar direction exhausted until its archive end is
known. Account/session changes, another date jump and returning to the latest
messages invalidate previous window work.

Offline/unsupported initial queries retain at most 100 closest cached rows in
an explicitly cache-only window with no server cursors. Scrolling still loads
known local neighbors in batches of up to 50, retains at most three cached pages
and stops when that local direction is exhausted. It never borrows another date's
server cursor. Reselecting the date/message retries archive loading; returning to latest history
leaves this window. An interrupted online continuation keeps already fetched
rows and retries on a later scroll. Searching remains cache-only: this change does not add server full-text
search or claim that unfetched history has been searched.

No tests, build, phone actions or network archive requests were run while making
this change. Two cursor/window fixtures and one additional store-isolation case
are prepared for the next authorized update gate. Real DM/MUC scrolling,
direction changes after eviction, date-result navigation, reconnects, server
cursor failures and long runs of control-only pages still need acceptance.


## Explicit search expansion

Unreleased ordinary DM/MUC search now says **Search downloaded messages**. Its
native calendar/history button offers **Jump to date** and **Load up to 50 older
archive entries**. The latter advances that chat’s ordinary recent-history cursor
by exactly one requested page, after its initial window is idle, then reruns the
current local query and sender/reaction filters without jumping away from the
current result. It does not run a server text query or scan all encrypted history.
Control-only archive pages can add fewer than 50 visible messages. Date windows
remain independent: this action continues backwards from the recent-history
cursor, not from a selected date.

The existing metadata/fingerprint-first and account/session ownership guards
remain in use. Concurrent expansion, pending initial sync, offline state and
exhausted/unsupported archives produce specific messages. Connection replacement,
logout, encryption rescans and terminal failures release the completion callback
once. Expired cursors report the reset and refresh a three-page recent window.
Loaded history retains chronological unread inheritance without explicitly marking
the conversation read. Three additional controller fixtures are prepared, not run:
exactly one extra page with preserved unread state, end/offline rejection without
fetching, and reconnect cancellation without stale callback reuse. Existing
controller fixture cleanup now closes the private read-sync executor too.

Global home search also labels **Chats and downloaded messages**, preserving the
chat-only hint in recipient selection and the topic-specific hint in topic views.
Older history is expanded deliberately within a chosen chat; global search does
not silently download every conversation. Native search result/date-window
navigation and phone layout acceptance remain open. No tests, build, server operation or phone action ran for
this checkpoint.


## Message-result and reply jumps

Unreleased source gives native around-message jumps the same independent paging
window as date navigation. A jump outside the current window invalidates its old
rows/cursors; a target already within a live fetched window can reuse that window.
Outbox items from the present are not injected into an old result window.

New direct-message MAM imports retain an explicit internal `mam` alias, separate
from `archive` (which may also originate from a server stanza ID). Room imports
already retain their MAM alias. Only an unambiguous MAM result ID from the selected
conversation can seed exact before/after queries. The resulting three-page window
contains up to 50 prior archive entries, the cached target, and up to 50 subsequent
entries, with independent directional end flags. Later scrolls retain the existing
three-page bookkeeping limit and 50-entry query limit. Sync cursors/read state are
unchanged by navigation.

For older cached rows without a proven MAM alias, date seeding is accepted only
if the fetched window contains the exact selected local identity. If it does not,
or a cursor expired/query failed, the cached selected message and its bounded
neighbors remain visible in a cache-only window instead of attaching that row to
an unrelated archive cursor. Further scrolling uses only known local rows in
bounded pages; these local ends do not claim that the server archive is exhausted. A new explicit jump can reseed online. Account/session/window
checks reject superseded work.

Three additional fixtures are prepared, not run: centered cursor ownership versus
cache fallback, offline result jumps replacing a previous date window without
recent outbox rows, and proven MAM cursor metadata versus stanza-only/ambiguous
IDs. The core server/MAM separation fixture also records the new alias. Online
DM/MUC result/reply navigation, repeated timestamp boundaries, expired IDs,
reconnect timing and native row-cache layout still need the authorized release
acceptance gate.


## Repairs found by navigation imports

Date and around-message pages refresh the native message cache, loaded message
objects and dialog previews when they repair a timestamp or unreadable encrypted
placeholder. The publication uses the importing account's JID and incarnation,
without requiring an ordinary archive-sync object to still exist. Queued UI work
is rejected after account replacement. Plaintext already recovered from an earlier
copy remains intact if an encrypted replay cannot be decrypted a second time.
Unchanged pages do not republish dialogs or create new-message notifications.

Three focused controller regressions cover direct timestamp repair, recovered
plaintext plus duplicate replay, and a room repair with account replacement before
UI delivery. These are prepared for the next build/test gate. This change does not
claim to rebuild native chat day groups after a changed date; that renderer path
requires separate verification. Ordinary synchronization cursors and read state
remain independent of navigation imports.
