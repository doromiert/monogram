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

Offline/unsupported initial queries retain the closest cached date window.
An interrupted continuation keeps already fetched rows and retries on a later
scroll. Searching remains cache-only: this change does not add server full-text
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

Global search still needs an equally explicit local-cache scope and deliberate
history expansion. Native search result/date-window navigation and phone layout
acceptance remain open. No tests, build, server operation or phone action ran for
this checkpoint.
