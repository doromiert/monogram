# Native Telegram wallet port

User approved replacing the earlier custom XMR UI wholesale with Telegram 13.0.3's actual wallet implementation (commit 0aae959806c57be8ad457980f2a5c6277ff18635). The source already includes the settings wallet currency picker; use it.

## Branch and baseline

- Superseded prototype archived locally at `backup/xmr-custom-ui-20261023` (6898433fb), including its unfinished experiments.
- Master fast-forwarded to cdc7ac55f, retaining 113 commits of existing messaging work.
- Replacement work is on `feature/xmr-native-wallet`, based on that pre-wallet commit.
- README user edits and ref.mp4 were left untouched.
- Restore only the Monero SDK/JNI, local wallet/vault/authentication and account-scoped settings as backend foundations. The old custom payment, dashboard and chat-card UI is discarded.

## Required bindings

- Preserve native attachment geometry, IME expansion, amount animation, menus and send flight.
- Preserve native chat cards/ribbons, transaction details, dashboard and its currency picker.
- Replace Gram services with the local Monero backend, system unlock and exact Monero network-fee review. No Telegram wallet RPCs, TON signing or account-wallet discovery.
- XMR has 12 atomic decimal places; conversions, display and signing must agree.
- Reuse the existing online currency picker UI; feed supported fiat codes and XMR rates from an HTTPS provider. USD/EUR/PLN and locale-correct symbol placement required. CoinGecko supported_vs_currencies and simple/price endpoints returned HTTP 200 without a key during investigation; stale/unavailable prices must not silently create a fiat-denominated transfer.
- Shared gem rendering must use an extruded mesh made from the supplied Monero SVG circle paths; retain the source animations and replace geometry/materials. Orange wallet styling.
- Preserve opt-in discovery: only the Settings row until a wallet is connected. Unknown destinations retain funded claim links; requests retain fresh automatic receiving subaddresses.

## Phone reference

Separate native preview app `org.telegram.messenger.beta`, 13.0.3-wallet-preview (71849), installed via ADB. Original wallet renderers/transitions plus local fixtures. Main app/native library built fresh from source; preview JNI identical to clean build. Verified dummy chat, incoming/outgoing cards, Money attachment, amount/fiat conversion, simulated send completion and details. Screenshots and recording in `../telegram-reference-latest/.state/wallet-phone-reference/`. User is using their phone again; no further interaction needed for current source work.

## Current state

Native UI files were copied unchanged as the starting point, then adapted as described below. Source integration is now at the private APK delivery gate, targeting 0.2.159-xmr.14 (73479). Build, focused tests and visual verification are pending; do not describe this as verified or installed. Tests were deferred until this packaging stage, per user preference. No release/source publication.

## Editable logo source

The user is editing/decimating design/monero-logo.blend. Treat it as authoritative; do not regenerate or overwrite it. Export evaluated geometry, split normals and Principled material colors/metallic/roughness using scripts/export-monero-logo.py. The SVG generator refuses to overwrite an existing .blend unless explicitly requested. Mesh format XMR2 uses position, normal, sRGB color, metallic and roughness (11 floats/vertex). Export remains pending while the user edits the file.

User's decimated/fixed model was exported without changing the .blend: 4,176 vertices, SHA-256 c619a4a70a727cc59d8b078debe85f3b9778c4dc6075d3161094f85aac455dcb. User is now adding a matte white backing disc named monero_white; exporter includes that object once the next edited file is saved. Do not overwrite the editable model.

Updated saved model exported on 2026-10-23, including the user's backing mesh named `Cylinder`. Its faces use an empty material slot, so the exporter supplies white, metallic 0, roughness 0.85 for that backing. Colored geometry/materials remain as saved. Source .blend SHA-256 4aa66dd9305007648fd1cf207aca1f4af12630ccbdc54764e35022c1f75f83fe was unchanged by export. No APK build or tests run for this asset update.

## Native Monero bindings — source checkpoint, 2026-10-23

- Added `GramWallet` compatibility facade over the retained Monero backend. Balances/history come from SDK snapshots, not Telegram wallet services. Spend controls use unlocked funds. Unknown/unscanned balances are not presented as a known zero.
- Adapted native amount entry, fiat conversion, card balance and fee displays to twelve atomic decimal places. Removed source coin rounding to two decimals and silent overflow saturation. Native signed-long models reject amounts beyond their representable range; the underlying URI/SDK protocol retains its uint64 range.
- Fee preparation is coalesced while typing, cancellable and tied to the exact destination/amount. Send requires that reviewed quote. Comments travel in the encrypted chat body and do not trigger irrelevant TON fee simulations. Uncertain broadcasts remain errors requiring inspection rather than automatic resubmission.
- Native wallet dashboard is accessible through the opt-in Monero Settings row with a Monero icon. Native currency picker uses the online fiat feed. Inline system unlock/sync, node selection, native recovery cells for 25-word Monero/16-word Polyseed and optional passphrase/restore height are wired. The SDK validates the complete recovery phrase; no TON dictionary/signing/backup services are used.
- Attachment entry uses the original native amount sheet and its IME/pan/flight integration. It appears only for connected wallets in normal DM chats, matching the reference's recipient scope. Added Send/Request using the reference tab component; request mode generates and persists a fresh receiving subaddress automatically and queues an ordinary encrypted XMPP payment URI.
- Ported wallet-only reference blocks into `ChatActionCell`, preserving Monogram's existing gift removal and other chat behavior. Native payment cards, ribbons, receipt details and the send-flight landing are used. Payment requests open the native amount screen; funded links open the native claim review flow. Incoming transfer notices say reported until SDK history verifies them.
- Unknown-recipient sends use a separately encrypted dedicated claim wallet, system authentication and the native fee-review sheet before funding. Funding hashes/state persist before broadcast. The link is materialized only after an accepted broadcast and queued through the encryption-protected XMPP outbox. It never contains the main wallet seed.
- Claim/reclaim flows unlock the user's wallet, generate a fresh receiving subaddress, restore/open and scan the independent claim wallet, then show the actual sweep amount and fee in a second native confirmation sheet. SDK sweep/commit signs only with the independent claim wallet. Saved payment links are accessible from the wallet menu for re-sharing/reclaiming instead of re-funding after a delivery failure.
- Replaced native gems with the user-edited extruded Monero mesh and added white-backed Monero vector/Lottie fallbacks. Applied orange wallet palettes while retaining source geometry/motion. Monero address displays preserve case; receiving QR codes use `monero:` rather than TON links.
- Removed live onramp, TON DNS, connected-apps, Telegram cloud-password/backup and TON explorer paths from the ported UI. Existing source collectible rendering has an empty unsupported Monero backend and does not initiate NFT operations. Restored verified SDK packaging, JNI shrinker rules and the scan foreground-service declaration. No helper APK is part of this application.
- Still required at delivery: compile compatibility fixes against Monogram's stripped/newer UI APIs, focused monetary/URI checks, APK/native-library/signer inspection, actual GL/IME/animation and unlock/send/request/claim state verification. No phone interaction has occurred during source work.

Protocol notes: `docs/protocols/MONERO.md`. Source edits and packaging outcomes are tracked here and in the root change logs.

## Private xmr.14 delivery gate

- Final Gradle build succeeded (57 seconds after compatibility fixes); all eight focused app checks passed: twelve-decimal/small-value preservation, exact fiat rounding, stale/future rate rejection, UI overflow/subatomic rejection, locale symbol placement and request/broadcast/claim envelope parsing. The seven core `MoneroPaymentTest` checks passed with Maven. Tests ran only at APK preparation.
- APK: `.state/apks/monogram-0.2.159-xmr.14-arm64.apk`, version 73479, 84,226,528 bytes, SHA-256 `03d2cba198827600028f356ebc62d72fa6cfc5fe9d218aca127bbc20b583feab`.
- Original signer retained: certificate SHA-256 `17a0f6d82126497354f71d00f5f8ccb03f976d7843d8a59d11fe18c8720e9c9a`. Package audit verified native wallet classes, exclusion of old custom wallet/TON/payment/billing UI families, all six libraries arm64, exact pinned SDK hashes, unchanged main messaging/OMEMO binaries and the current 4,752-vertex white-backed model/assets.
- Final gate also corrected remaining TON QR input assumptions, restored the existing contact-permission helper, matched native history notification identity and marked local SDK snapshots as non-paginated so a locked empty wallet cannot recurse through the reference loader. Local fiat/fee and UI snapshot changes were rebuilt and the focused app checks rerun after changes.
- Installed successfully over ADB at 192.168.1.128:45475. Package manager confirms `com.negzero.monogram.beta`, 0.2.159-xmr.14 (73479). No helper APK, release upload or source push.
- User approved a brief UI check stopping at payment review. Phone auto-locked before screenshots of the app could be captured; unlock requested. Actual GL/IME/animation and live unlocked-wallet flow verification remain pending. No payment was submitted.

## Private xmr.15 — unlock and Cake-style scanning (2026-10-23)

- Diagnosed the apparent unlock hang on the phone: authentication had succeeded; dashboard automatically launched a full legacy-cache repair from genesis, hid progress and replaced its action label with an indefinite spinner. Foreground notification showed block 60,940.
- Unlock now performs only local authentication/open. Scan is a separate visible action. Dashboard displays blocks remaining and percentage, with Stop scan; amount/attachment surfaces retain readable progress and a stop action. Initial-scan notification has determinate progress once the target height is known. Receiving remains available while unlocked and idle without requiring completion of the chain scan.
- Inspected Cake's pinned source (see MONERO_SYNC_DIAGNOSIS.md). Added approximately 30-second checkpoints, incremental repair resume and persistent verified HTTPS connections. Native refresh is stopped at a block boundary and returns before wallet serialization; no concurrent wallet2 store is introduced. Successful saved repair checkpoints advance the migration policy, while full-scan completion and spending readiness remain separate. JNI progress crossings are throttled to four per second; UI updates stay below two per second. Explicit restore heights remain supported, and unknown legacy wallet birthdays still require scanning from zero.
- Settings now uses a monochrome white wallet glyph on the existing orange background instead of placing the colored Monero logo on another colored background.
- User reported a crash during diagnosis. Android recorded an ANR at 22:19:32 in xmr.14, PID 2116; native trace shows the debugger event helper blocked in `threadControl_suspendAll`. This points to the earlier debugger attachment, which was stopped. No fresh Java exception or native wallet crash was recorded for that incident. App process had already exited before installation; unsaved in-memory progress cannot be recovered. Removed the remaining debugger forwarding. No debugger attached to xmr.15.
- Packaging gate: native adapter rebuilt successfully; app build and eight amount/URI checks passed. Private TLS fixture passed binary/content-length and chunked framing, loopback authorization, untrusted-certificate rejection, hostname rejection, and four RPCs over one TLS connection without leaking loopback credentials. Real RPCs are never automatically replayed. The Android SDK, main messaging/OMEMO binaries and logo asset audit passed; packaged adapter matches Gradle's stripped output from the rebuilt JNI binary.
- Installed `0.2.159-xmr.15` (73489) over ADB. APK SHA-256 `cffb63b9e0e370f12b56284d779e638c1aa84a435d733d3a663307e965051470`. Native strip-output SHA-256 `9eeec545ad6ca48d6ff8b8ef394f0720c7ddb00ba98502431d275517e67d3a54`. No helper APK, release upload or source push. Live unlocked scan/checkpoint confirmation is pending; no throughput multiplier is claimed.

## Private xmr.16 and live scan verification

- User's apparent endless node connection was traced to a native API distinction: `connectToDaemon()` performs an HTTP check but does not set `WalletImpl::m_is_connected`; daemon-height getters return zero until `connected()` is called. The progress formatter therefore retained “Connecting to node…” even as native scanning proceeded. Added explicit connection/version validation and rejected unusable daemon heights. A disposable public-seed fixture on the phone now gets Cake's real height (3,790,600) in 2.14 seconds. No user seed or real wallet was accessed by the fixture.
- Removed the remaining gem artwork from the native card material, receive QR center and recipient icons. Card now renders the actual user-edited extruded Monero mesh into its own material texture; removed the gem-specific procedural facets. QR/recipient icons use the supplied Monero vector. Scan subtitle uses stable text updates and a normal font.
- xmr.16 build, eight app checks, TLS fixture and native connection fixture passed; artifact/native SDK/model/adapter/signature checks passed. Installed privately over ADB: 0.2.159-xmr.16 (73499), APK SHA-256 `48d324b1090c16757d0ba0e7947afcdde35aa01bdcc418c8c9216dc65dda7107`. No release/source upload.
- Verified on phone and confirmed by user: scanning advances. Screenshot shows 76,362 blocks remaining (5%), then 67,378 (16%). Repair metadata advanced durably to scanPolicy 2 while scanned/initialScanComplete remained false, and native cache was saved (~9.55 MB), confirming a partial checkpoint. Full initial scan completion and monetary sending remain untested.
- User noticed upside-down card icon and a solid circle in the collapsed header. Captured both states. Corrected GLES FBO vertical sampling and disabled monochrome recoloring of the multicolor balance icon. These two cosmetic source fixes are queued for the next APK; xmr.16 remains installed while its scan proceeds. No tests/build run for these source-only cosmetic changes.

## Optional send/request notes — private xmr.17 preparation

- Added a visible, editable “Add a note” entry beneath amount/fee controls in both the attachment Send/Request sheet and full native send screen. The entry remains available before entering an amount and returns after clearing a note. Existing notes preview in the composer and payment cards; the native review UI retains them.
- Reused existing request `tx_description`, direct-transfer URI descriptions and funded-link note transport. Removed the misleading TON public-comment checkbox from these XMR note editors. Notes follow chat encryption and never go on-chain or alter destination/amount/fee.
- Payment descriptions now safely preserve line feeds and Unicode through percent encoding, while rejecting other hidden control characters. Added focused round-trip coverage for request/transfer card notes and multiline URI notes.
- Included the pending card-texture vertical flip and disabled monochrome tinting of the multicolor header balance logo. User confirmed the initial scan completed; persisted metadata confirms scanned=true and initialScanComplete=true before preparing this update.
- Preparing private xmr.17 (73509). Build/tests/install verification pending; no release/source publication.

Additional xmr.17 requests: reuse a successful sync for five minutes after local unlock, scoped to the same account/network/node, so the attachment composer can open immediately from the saved full-scan cache. Lock still clears the native connection; the first real fee preparation reconnects and catches up the native engine automatically before preparing a transaction. Node changes, interrupted scans and incomplete repair state cannot qualify as a recent successful sync.

Captured the user's note-layout shrink on xmr.16. When a note exists, the attachment sheet now uses the expanded height previously reserved for the amount keyboard; adding/removing a note requests parent remeasurement. This makes room for the amount and note instead of fitting them into the compact sheet's remaining space.

Captured the sent request card's Monero address overflowing both sides and remaining blue gradient/text. The native chat payment card now uses an orange gradient and warm accents, bounds its address text to the card, and shows a grouped prefix/suffix summary for long Monero addresses. Full case-sensitive addresses remain unchanged in URIs, details and copy actions. Requests no longer display a debit/credit sign; their amount describes a request rather than a completed transfer. Included in the xmr.17 delivery gate.

## Private xmr.18 preparation — wallet management and precision

- Added a native Wallets list with create, restore, rename, select and remove controls. The existing wallet retains its original default path and Keystore alias. Additional wallets have UUID slots, separate SDK cache directories and distinct authenticated vault aliases within the same account/network scope. Switching cancels the prior session and refreshes the dashboard's bindings. Selection/names persist locally.
- Removal requires explicit confirmation and system authentication, closes the SDK session before deleting its local cache and unlock key, and preserves unrelated wallets. Wallets with saved payment-link wallets cannot be removed until those links are resolved, because the main seed cannot recover an independent bearer wallet.
- Fresh-wallet creation uses the pinned SDK, verifies its HTTPS node connection, saves a restore height near creation and displays its SDK-generated recovery phrase locally behind system authentication and secure-window protection. The phrase is cleared when leaving the screen. Claim-link code retains its distinct, claim-only seed export path.
- Added configurable balance display precision (0–12 decimal places), default four. Main-card “Show precise amount” hides the XMR label, Monero balance icon, QR insert and address engraving, shows the full exact balance, and changes to Back. Settings affect display only; all payment arithmetic and signed amounts retain twelve decimals. Nonzero balances below the chosen precision display a less-than threshold instead of a false zero.
- xmr.17's actual request-card native preview was shown in the PC emulator with dummy data/payments disabled and approved by the user. Emulator helper was confined to that device; no helper installed on the phone. xmr.18 source work is now at its private delivery gate; build/tests/real-wallet-management verification pending. Do not claim funds were sent or wallet removal was exercised on the user's wallet.

## Private xmr.18 delivery and xmr.19 animation preparation

xmr.18 (73519) built, passed ten focused app checks (six exact/display amount checks and four request/transfer/claim-note checks), passed the disposable native wallet lifecycle fixture, and passed APK/signature/ABI/pinned-SDK/model/adapter verification. Native fixture verified independent generated wallets, creation restore height, matching phrase restoration, reopen identity and rejection of main-wallet seed export through the claim API; no user wallet was created, removed or funded by the fixture. User's saved wallet opened after the update and the phone screenshot confirmed the four-decimal balance display. User approved the precise-mode result and requested animation. Installed xmr.18 SHA-256: `72ea961cd8c34fb66ccdae7dded2cbc031eb88d82aaaa7734353786a068b3a4a`.

xmr.19 adds the requested rightward slide of the QR insert and wallet-address engraving, clipped to the actual card face and reversed on Back. The movement uses the native GLES card material and its 2D fallback while the card background stays steady. The Show precise amount/Back label and its pill background use Telegram's AnimatedTextView. Incoming/history wallet icons and pending highlights now use warm colors. The five actual wallet shader variants compiled and linked in an offscreen GLES3 context on the phone; this check opened no Activity and used no wallet keys. Packaging/install verification pending.

xmr.19 also bounds the main card's decorative long-address engraving with a grouped prefix/suffix summary, matching the compact chat-card approach; full addresses remain in detail/copy/URI data. The native AnimatedTextView control animates both the Show precise amount/Back label and its sizeable pill background. QR/address move off the right edge over 260 ms and return from the right on Back, in both GLES and 2D card implementations.

## Final private delivery — xmr.19 (73529)

Build, signature and APK class/native-SDK/model/adapter/shader-asset audits passed. Installed over ADB; package manager confirms 0.2.159-xmr.19 (73529). APK SHA-256 `d86bedb39cfc2ef4636e54ce1f99356b764b1c1d76811081c34665c473563b5b`, size 81,541,712 bytes. Five updated native shader variants passed offscreen compilation/linking on the phone. Monetary/note checks and disposable SDK wallet lifecycle checks from xmr.18 remain applicable; those code paths were unchanged in xmr.19.

Only `com.negzero.monogram.beta` is installed under our package prefix on the phone; no helper APK was installed there. Disposable wallet and shader-check files were cleaned from /data/local/tmp. The user's saved wallet remained at its legacy path and its four-decimal balance was visible after unlock in xmr.18; the user approved the precision layout before requesting these animations. Wallet create/remove flows were not exercised against the user's real funds, and the final slide animation still awaits user feedback. No release upload, source push, payment broadcast or destructive test on the user's wallet. README.md, ref.mp4 and the user's Blender source/backup are preserved.

- Wallet attachment amount area: replace fit-to-height scaling with a fixed-size scrolling viewport above the footer; keyboard no longer shrinks tabs, amount and notes. Screenshot captured in `.state/wallet-sheet-current.png`. Pending APK validation.

### Recovery and unlock follow-up (validated by xmr.20 delivery checks)
- New main wallets use the pinned SDK's English 16-word Polyseed generator and `createWalletFromPolyseed(newWallet=true)`. Dedicated claim wallet seeds retain their existing protocol. Existing wallets are not converted.
- Recovery export prefers the wallet's actual Polyseed, with legacy mnemonic fallback. Recovery words use two numbered columns (eight rows for Polyseed); longer legacy phrases use additional rows. Screen security and clearing on pause remain in place.
- Locked cards hide Show precise amount and leave precise mode. Successful unlock starts the existing sync/rescan path in Settings and attachment amount screens, retaining visible progress and Stop. No second tap required.
- Delivery checks now include fresh disposable Polyseed restore/reopen, 19 focused Java checks and the signed ARM64 APK audit; see xmr.20 notes below. User-wallet UI interactions remain pending.

### xmr.20 release candidate
- Claim messages and copied claim details now include Cake-compatible universal restore URIs and readable Legacy mnemonic/network/height fallback. The card parser verifies this section against the bearer URI. Existing main-wallet Polyseed recovery and independent claim Legacy mnemonics stay distinct.
- Prepare cancellation/claim now recognizes locked balances before requesting a sweep quote, shows a confirmation-wait UI, checks every 30 seconds, and requires final amount/fee review after unlocking. This cannot revoke an already shared bearer link or bypass Monero confirmation locks. No automatic broadcast.
- Claim-wallet unlock reuses current Keystore authorization after the main wallet's system unlock; expired device authorization still prompts.
- Performance: move engraving/QR scissor bounds with their UV offset; draw animated balance/button as native overlays instead of uploading a full-card text bitmap each frame; temporarily use display refresh up to 120 Hz during precision transitions, then return to 60 Hz. RenderNode overlay path draws children directly rather than reusing the full-face cache. Device frame-pacing measurement remains pending.
- Attachment amount controls now scroll at normal size in their available viewport instead of being uniformly scaled to fit the keyboard.
- Delivery checks: ARM64 native adapter and APK build successful; 11 focused app tests and 8 core Monero payment tests pass (precision arithmetic, note/URI handling, portable claim integrity). Disposable device fixture confirms fresh 16-word Polyseed creation, distinct wallets, restore/reopen identity and main-seed rejection by claim export. Artifact audit verifies pinned native SDKs, unchanged messaging/OMEMO libraries, required UI/assets and excluded old wallet/payment families.
- No real payment was broadcast or user's recovery phrase inspected by these checks. UI checks involving the unlocked user wallet still need user participation.

### xmr.22 presentation and lifecycle follow-up
- Wallet cards accept horizontal swipes between existing local wallets; selection uses the existing registry/vault isolation, replaces the bound screen, and labels multi-wallet cards by wallet name. Busy scans must be stopped before changing wallets.
- Send sheet shows exact total balance rather than mislabeling spendable balance as total. A separate available amount explains confirmation locks; insufficient-spendable errors identify waiting change instead of suggesting the wallet has no funds. This does not bypass the SDK spendability rules.
- Native unlock completion is retained across pause/resume and delivered once the host resumes, so authentication no longer silently drops completion/sync startup.
- Hidden error rows no longer reserve a large gap above Add a note. Scroll viewport clips overflow above the fixed footer.
- History rows use configured compact precision. Transaction details offer animated Show precise amount / Back, hide the decorative model/unit in exact display, and fit long amounts to available width. Payment review continues showing exact values by default.
- User screenshot `.state/xmr-transactions-current.png` confirmed the old detail amount clipped the XMR unit. Device UI verification of the new paths is recorded separately below.

### xmr.25 pager, parallel scans and full sheet body
- Replaced the temporary swipe/recreate shortcut with AndroidX ViewPager. Adjacent wallet cards remain visible at the edges, follow the drag and settle continuously before wallet selection changes. Neighboring GLES cards run at 10 FPS while idle; the selected card runs normally. The final page always offers Add or create another wallet. Its background is translucent white, with no outline or side clasp.
- Pager owns touch until release even when a 3D child receives ACTION_CANCEL. Its card area requests Android edge-gesture exclusion; vertical gestures hand back to the outer scroller.
- Main-wallet scans retain their own worker/session, foreground-service lease and checkpoints across wallet selection. Only scan execution bypasses the active-wallet check; account/network/opt-in checks remain, and signing/other wallet work still require active selection. Switching during signing or another non-scan operation remains blocked. Scan completion releases an inactive wallet normally.
- The entire attachment body (recipient, amount, currency, note) now lives in a nested scroll view with top/bottom spacing. Header and footer stay outside it. Nested sheet expansion remains enabled, including with a keyboard/note. Removed the small amount viewport and empty hint/error space.
- Delivery fixture NativeParallelScanSmoke successfully synchronized two independent, freshly created unfunded wallets concurrently via separate verified TLS bridges. It never opened any user wallet. Temporary device fixtures were removed.
- Device screenshot confirms the real pager shows both neighboring edges and locked cards omit the precision button. An attempted swipe capture was interrupted by the phone locking; no successful continuous-motion capture is claimed. The final edge-gesture fix still needs user confirmation.

Delivery: xmr.25 (73589) was installed via ADB and published as prerelease `v0.2.159-xmr.25` from commit `69868a1095e3bd2f338c251ac06da7a26fa1954b`. GitHub asset size and available SHA-256 digest match the local signed APK (`b9575634c26611f6965030d1970a8c68d58694f291f24bba6c9120d7c648136d`). The XMR branch was pushed; main was not merged or changed. Final gesture/sheet runtime feedback remains pending as stated above.

### xmr.26 light theme, direct removal and attachment geometry
- Add wallet card uses theme foreground/surface tint and label/accent colors instead of permanent white text on translucent white. Keeps its unoutlined appearance in dark and light themes.
- Wallet management removes the chosen wallet directly after its named confirmation and system authentication. A dedicated management lease/auth scope checks account/network identity without selecting it. Removal runs in that identity scope; signing, recovery export and normal wallet operations keep their active-selection checks. Removing an inactive wallet preserves the selected wallet and its observer/cache instance. Existing scan-busy and unresolved-claim protections remain.
- Locked attachment state removes the hidden composer/footer from layout, and measures a real centered unlock area. The footer is reserved only for a ready composer. Scroll viewport stays under the fixed header; collapsed space is scrollable top padding, so expansion lifts the entire body rather than leaving a small fixed scrollbox. Bottom inset updates independently when fee/keyboard/access state changes.
- User screenshots 10091/10092 motivated these fixes. No user wallet is deleted as a test. Runtime verification is tracked at delivery.

The attachment expansion follow-up (xmr.27) reserves scroll range for the collapsed top padding even when the locked body is short. Its scroll listener updates the parent sheet offset and grows the centered unlock region as the sheet expands. This prevents a short locked body from leaving the sheet unable to expand.

Delivery: xmr.27 (73609) installed successfully via ADB and published as GitHub prerelease `v0.2.159-xmr.27`. Signed APK audit and uploaded asset size/digest verified; SHA-256 4129c7a56816847e432c808df851ef972e45f011a4577b1a6e05b74d17bd5ccd. No user wallet was removed. Device visual confirmation remains pending.

### xmr.28 measured attachment viewport
- Device captures `.state/attachment-padding-{1,2}.png`, `.state/attachment-padding-later.png` and `.state/attachment-boundaries{,-next}.png` show the settled expanded state clipping the recipient and reserving a large centered body.
- Header offset now counts only the action bar and content gap; the attachment parent already supplies the status-bar inset. Body minimum/expansion range use the actual scroll viewport height after layout, rather than the parent's height before external insets. Unlocked amount controls use natural height/top alignment; only the locked unlock area is vertically centered.
- Delivery: signed ARM64 APK build/artifact audit passed, installed xmr.28 (73619) via ADB. Settled device capture `.state/xmr28-expanded-settled.png` shows the recipient fully below the header and an unclipped centered sync/unlock action. The initial “lgtm” was withdrawn after footer inspection; the bottom redesign follows in xmr.29. No payment was sent.

### xmr.29 compact footer wallet cards
- Replaces the old standalone Balance TextView with a transparent horizontal native ViewPager containing only existing wallets, each shown as a short orange card with one exact balance line. No Add wallet page or outer balance background. Unknown locked balances remain unknown rather than showing zero; wallet names are available in native tooltips/accessibility descriptions.
- Selecting another card cancels stale fee/review work, switches through the existing wallet registry and rebinds access/observers. Existing independent scans continue; signing/broadcast work cannot be switched away mid-operation. The strip stays visible while locked so another wallet can be chosen.
- Strip/fee/send rows have explicit footer heights and no legacy 60 dp balance translation. Bottom reservation follows the visible attachment-tabs inset as it fades out on expansion; no permanent tray-sized empty gap.
- User rejected the previous footer after `.state/attachment-bottom-before.png`. No xmr.28 release was published. Final xmr.29 verification follows below.

### xmr.31 actual compact 3D material and lock cleanup
- User rejected xmr.29’s flat gradient footer; no release of xmr.29 or xmr.30 was published. Compact footer now embeds WalletCardView and the same WalletCard3D material shader, LUT, lighting and native balance projection as the settings cards.
- Shortens the original face/edge mesh in model space while preserving the corner arcs and width/depth, with compact camera calibration and matching overlay projection. Body shader coordinates retain the native foil pattern instead of scaling an imitation gradient. Compact mode omits full-size QR/engraving/icon passes and leaves normal card geometry unchanged. Neighbor cards render at 10 FPS, current card at 60 FPS.
- Locking clears native logo bounds and disables outgoing animated logo-span draws, preventing the old icon from remaining behind the dash. Unlock restores icon eligibility.
- Three focused geometry tests pass: normal geometry identity, compact bounds/circular arcs, and monotonic mapping without mesh folds. Native shader source is unchanged. Final runtime capture pending.

- Compact attachment wallet cards are display-only touch targets: page dispatch declines child touches so the enclosing ViewPager receives swipes directly, without 3D press/drag handlers or tap selection. Source fix; device validation pending next APK.

- Returning from wallet management now rebuilds changed wallet pages in the existing screen instead of presenting a replacement fragment during the back transition. Captured overlapping whole-screen columns in `.state/wallet-manager-renderer-after-delete.png`; runtime verification pending.
