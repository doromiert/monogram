# Client-side link previews

Settings → XMPP connection → Link previews controls direct fetching. It is off for a new account. A separate, default-off permission is required for encrypted conversations. Enabling either permission displays a consent dialog explaining that the destination and thumbnail host see the user's IP address and requested URL. Disabling fetching does not hide an already cached preview.

Monogram fetches only the first HTTPS URL in a saved message after decryption, on live message delivery or native chat-history loading. It never sends message text, fingerprints, keys, or preview metadata to Telegram, an XMPP server, or a preview service. The URL itself (including its query) is necessarily sent to its destination; fragments are removed. Metadata and thumbnails stay local and are not added to outgoing XMPP messages.

The reader uses platform certificate trust and hostname verification, resolves all destination addresses, rejects local/private/reserved addresses, and pins the selected public address into its TLS socket. Redirects and image destinations go through the same checks. Requests use port443, no cookies, authorization, referer, JavaScript, or embedded players. Cleartext HTTP and nonstandard ports are deliberately not previewed. Four redirects,512KiB HTML,2MiB source images,40-megapixel source dimensions, a15-second per-fetch deadline and socket timeouts limit work. Images are decoded down to at most640pixels and re-encoded locally; remote media URLs never reach the native image loader. Unsupported/failed images leave a text preview; failed pages leave the original link usable.

Native `TL_messageMediaWebPage` rendering displays the result. Explicit received photos/documents/stickers are preserved. Schema28 adds only `link_previews`; existing accounts, messages, encryption keys, trust, and archive cursors are preserved. Metadata lives in the account's private message database (up to256 URL entries). Thumbnails use the private Android cache (at most128 files or16MiB). Positive results cache for7days, failures for1hour to avoid network retry storms. Clearing previews or logging out removes the preview cache; a chat must be reopened to refresh rows already displayed. OS cache eviction can leave a text-only preview until metadata refresh.

Seven focused cases passed at the 0.2.119 release gate (run only when preparing a phone update):

```sh
nix develop --command ./gradlew --offline --no-daemon -PmonogramAbi=arm64-v8a :TMessagesProj:testDebugUnitTest --tests org.telegram.messenger.xmpp.MonogramLinkPreviewsTest
```

The fixture covers destination policy, redirect URL validation, HTTP size/framing/cancellation, metadata parsing, default/encrypted/account privacy settings, negative caching, reopen/edit/deletion/native-media preservation and the27→28 migration. It does not contact any user's links. Before claiming phone verification: confirm no requests before consent; approve a synthetic public HTTPS fixture; inspect native title/thumb/click handling, reopen/cache behavior, disabling/clearing and encrypted permission. A controlled TLS redirect fixture should additionally exercise certificate mismatch, public-to-private redirects and thumbnail redirects end-to-end; the static policy fixture alone does not prove the full socket exchange.
