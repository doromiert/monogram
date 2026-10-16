# Monogram TODO

Legend: `[ ]` todo · `[o]` work in progress · `[x]` verified done.

Work only in `~/Projects/monogram`, using Git branches. Do not create separate source checkouts in other project folders.
Reuse the existing upstream feature implementations and redesigned UI components (`MainTabsActivity`, `UniversalFragment`, native settings/presets); do not add parallel custom settings for features Telegram already supplies.

The goal is a Monogram Android client based on current official Telegram source, using ordinary XMPP/Jabber and interoperable OMEMO, with an optional server helper for features that need it. Nix is the development/build environment. Planned public home: https://monogram.neg-zero.com. Helper host: 192.168.1.24.

## Official Telegram base

- [x] Reapply the Monogram changes onto `DrKLO/Telegram` 13.0.2, commit `f32ffeec763f0bdbf4bec25b52d654f77bff733d` (2026-10-13). The Git rebase is complete; build/runtime validation is tracked below.
- [x] Update the pinned Nix environment for Java 21, SDK/build tools 35 and 36, NDK 27.2, CMake 3.22.1 and Rust 1.96.1.
- [x] Build all current-base arm64 native libraries from source; remove bundled native archives/generated headers from Git tracking.
- [o] Restore the FOSS replacements and remove proprietary runtime SDKs from the new base. Firebase, phone authentication, captcha/integrity and Google voice verification are removed. Remaining: Maps/location, Cast, Wear, Vision/ML Kit, Wallet and Play Billing.
- [o] Verify XMPP onboarding and request dispatch; prevent Telegram networking in startup, background work and unsupported actions.
- [x] Compile the current-base Android Java sources with the XMPP adapter and Jabber account routes (138 tasks; BUILD SUCCESSFUL).
- [x] Build the rebased arm64 APK 0.2.0 (71759) with Nix (301 tasks, BUILD SUCCESSFUL).
- [o] Verify the current Telegram UI, Monogram branding and security button on Android. The current-base chat renders and exposes the separate Conversation security control; complete branding and deliberate lock/confirmation touch checks remain pending.
- [o] Use the redesigned main tabs after Jabber sign-in, and migrate Monogram's sign-in/new-chat/security/connection pages to the upstream universal lists and editable cells. Source wiring is updated; verify the rebuilt UI on Android.
- [x] Install 0.2.0 through wireless ADB with the same signing key; verify the saved account/credential, all 53 existing local message rows, and the OMEMO device ID and identity key remain intact.

## Current priority: XMPP user profiles

- [o] Fix the profile-opening crash and restore cached avatars immediately after process restart: retain native gallery metadata and resolve persisted XMPP photo markers to private files. Installed 0.2.8 first, then 0.2.9 with gallery-cache serialization fixed too. Serialized-user/gallery regressions and isolated Android checks pass. All 1,508 pre-update messages, 9 existing avatar files, saved login and OMEMO identity survive; current message count 1,513. Profile touch verification remains.
- [o] Replace phone-number identity displays with the account's full Jabber address. Installed 0.2.9: native settings subtitle and account-profile identity row/search show/copy the address without phone formatting or a change-number flow. Visual verification remains.
- [o] Keep the account display name separate from its address: use the published nickname/name, falling back to the username part for an unnamed account. Installed 0.2.9: publish the cached native account model before reconnect/profile fetch; Android assertions verify fallback and published-name precedence. Visual verification remains.
- [o] Move Reconnect from the chat header to the Monogram home screen, and move per-chat encryption settings into the chat overflow menu. Installed 0.2.9 uses native home header visibility and the existing chat menu/security page; touch verification remains.

- [o] Prepare profiles before replies: load and cache display names, avatars and standard Jabber profile details; reuse the upstream profile, edit-account and avatar-picker UI. Installed in 0.2.6 (71819); profile UI interaction still needs checking.
- [o] Read and publish vCards without deleting fields supplied by other clients; support nickname and avatar PEP updates, with vCard avatar fallback. Local Prosody round trips, incoming PEP notifications and field-preservation checks pass; independent-client verification remains.
- [o] Show the Jabber address separately from the display name, retain local roster names, and keep cached profiles available offline. Native profile/edit-account rows and schema 5 are wired. Isolated Android tests verify profile/avatar persistence, roster aliases and separate account caches; visual UI checks remain.
- [o] Verify profile edits, avatar replacement/removal, incoming updates, malformed/oversized images, account isolation and database migration with a local server and an independent client. Twelve core tests and isolated Android database/image/transport checks pass, including schema 1/2/3/4 migrations to 5, malformed images, avatar removal and profile metadata serialization. Independent-client and touch checks remain.

- [x] Build and install 0.2.6 with Nix and the same signing key; preserve all 1,502 pre-update message rows, the saved login and OMEMO identity. The live database is still schema 4 until the app starts: Android rejected background service startup; no foreground navigation was performed.
- [x] Verify first-launch profile/reply migration: live database is schema 6 with 1,508 messages; profile names and 7 private avatar files are loaded. Native avatar reference/cache reuse and profile touch verification are tracked above.
- [ ] Extend editing beyond name/bio/avatar to the remaining received vCard fields; evaluate vCard4 (XEP-0292/0294) support and external/avatar-pointer formats. Current display covers nickname/full name, website, birthday, organization/department, role/title, email, phone and address fields.

## Messaging and existing conversations

- [x] Discover and import the account's available direct-message MAM archive. Installed 0.2.2 completed the first real-account backfill: 1,455 local messages, preserving all 57 previous messages and the credential/OMEMO identity.
- [o] Implement paged MAM history with original timestamps, ID aliases/deduplication and chronological cache handling. Backfill/catch-up cursors and Android database paging pass tests; verify scrolling, history navigation and cache refresh visually, including imports while a chat is open.
- [x] Represent encrypted archive messages this device cannot decrypt with an explicit placeholder, retaining already known plaintext. The real import contains 1,361 unreadable encrypted messages; no claim of recovering their plaintext.
- [o] Handle incoming/outgoing carbons, multiple devices, offline messages and reconnects in the Android storage/UI adapter. Carbon direction/delays and forward archive catch-up are implemented and tested locally; automatic reconnect synchronization, unread state and independent-client Android checks remain.
- [ ] Refresh already displayed history when an archive import corrects a timestamp or replaces an unreadable placeholder; cover jump-to-message/date navigation.
- [ ] Implement delivery receipts and accurate pending/sent/delivered/failed states, with useful errors and retry behavior.
- [o] Implement XEP-0085 typing indicators: installed 0.2.10 with a direct-chat adapter and native Privacy toggle. Twenty-one core tests and isolated Android checks pass, including state transitions, no repeated standalone notifications, per-resource aggregation, implicit negotiation, privacy-off behavior and stale/forwarded state rejection. Touch/independent-client verification and MUC integration remain.
- [o] Keep cached conversations visible during reconnect/refresh instead of replacing them with a throbber. Installed 0.2.10 reads local history/dialog/profile data independently of network login/send work, restores the saved self ID before network initialization, completes failed history loads, and clears dialog loading flags on errors without deleting cached data. All 1,534 pre-update messages, 9 avatars, login and OMEMO identity survive; live count 1,539. Visual offline/reconnect checks remain.
- [o] Fix message editing in both directions using XEP-0308. Version 0.2.11 wires native text editing, plain/OMEMO corrections, repeated edits of the original client stanza ID, cached edited markers, reply refresh and MAM/carbon reconciliation. Direct-chat author/direction scope and ambiguous IDs are checked; plaintext cannot replace an encrypted original or later encrypted edit. All 23 core tests and three isolated Android database suites pass locally (Robolectric/API 36). Published as a prerelease and its APK link delivered through the Codex bot because ADB is unreachable. Phone touch and independent-client verification remain; MUC and media/caption editing remain pending.
- [ ] Implement roster management, subscription requests, presence and contact display names.
- [o] Fix replies in both directions using XEP-0461: send a reference to the original XMPP message, resolve incoming references to local messages, handle quoted fallback text and missing originals, and verify Android reply previews with an independent client. Installed in 0.2.7: plain/OMEMO text and attachment references, native previews, exact author/ID matching, missing-original fallbacks, late/recovered-original refresh, and navigation around the original. Seventeen core tests and isolated Android checks pass; touch and independent-client verification remain.
- [x] Verify reply wire metadata for plain/encrypted text and attachments, MAM and outgoing carbons on local Prosody; enforce Unicode code-point fallback bounds, author/resource scope and ambiguity handling. Replace Smack’s outdated fallback parser so current ranges survive parsing.
- [o] Rescan existing archive reply metadata after schema 6 migration while preserving local messages, known plaintext, profiles and attachment paths. Isolated Android tests verify schema 1/2/3/4/5 migrations, metadata enrichment from an unreadable encrypted replay, cyclic-reference limits and account cleanup. Verify the real-account rescan after first launch.
- [ ] Complete room/media editing, reactions and other applicable messaging actions using standard XMPP extensions.
- [ ] Complete account management, request cancellation, logout isolation and background notification behavior.
- [o] Fix silent/random disconnections: lifecycle callbacks, idle pings, bounded Android retries and a native Reconnect page are installed. Local cut/unresponsive-stream tests verify reauthentication, restored features and encrypted delivery with the same identity. Verify the Android button and prolonged background behavior.

## Multi-user chats (MUCs)

- [ ] Implement XEP-0045 room discovery, joining/leaving, nicknames and room conversation adapters.
- [ ] Support room creation/configuration, invitations, occupants, affiliation/role changes and moderation.
- [ ] Implement persistent room bookmarks and automatic rejoining through standard XMPP extensions.
- [ ] Import room history/MAM with stable IDs, timestamps and deduplication; keep room state separate from direct messages.
- [ ] Classify legacy/unmarked MUC private messages before importing them; occupant addresses must not share the direct-message bare-JID model. The direct adapter rejects messages with the modern MUC marker. Room corrections require full occupant/session continuity, including leave/rejoin and nickname reuse; do not rely solely on the optional marker.
- [ ] Implement interoperable MUC OMEMO for supported non-anonymous, members-only rooms, with explicit participant/device trust and membership changes.
- [ ] Explain when a room cannot support OMEMO; never silently send plaintext for an encrypted room.
- [ ] Test rooms and encrypted group messages against an independent XMPP client, including reconnect and membership changes.

## OMEMO

- [x] Implement and install Trust all fingerprints in 0.2.1 (71769), scoped to the displayed contact/own-device fingerprints, with confirmation and atomic persistence. Six core tests pass, including changed/unseen-key isolation and failed-save rollback.
- [ ] Verify the new trust-all button, confirmation and cancellation visually; no real trust decision was changed by the agent.

- [ ] Verify actual Android encrypted exchange with an independent client such as Monocles, including this user's reported send failure. No successful send to the reported friend has been verified.
- [ ] Verify persistent identities across a real process restart and app update.
- [ ] Verify new/changed/distrusted identities and multiple recipient/own devices; never silently downgrade an encrypted send to plaintext.
- [ ] Improve encryption/trust indicators and device verification on the rebased UI.

## Images and other attachments

- [x] Build the 0.2.7 (71829) arm64 APK with Nix; seventeen core tests and Android storage/profile/reply/transport checks pass. Installed through wireless ADB with the same signing key; all 1,502 existing messages, login and OMEMO identity survive. Live schema 6 migration and metadata rescan await the user opening the app; no foreground navigation was performed.
- [x] Install 0.2.5 (71809) with the native settings integration and redesigned Monogram pages. Ten core tests and Android database/metadata/transport checks pass; all 1,487 previous message rows, saved credential and OMEMO identity survive.
- [o] Use the existing Data and Storage auto-download settings and DownloadController presets for XMPP photos, videos and files/music. Remove the duplicate custom page/preferences; pass HTTP size/type metadata to the existing policy, expose the existing size slider for photos too, and verify network/category limits, unknown sizes, cancellation and manual fallback.
- [x] Build and install 0.2.3 (71789) through wireless ADB with the same signing key; schema 3 → 4 preserves all 1,457 previous message rows, the saved credential and OMEMO identity.
- [x] Verify shared HTTPS plain/encrypted upload/download on local Prosody and Android's networking stack, including server limits, wrong-key preservation, cancellation during transfer and partial-file cleanup. Eight core tests and isolated Android transport/database checks pass.
- [o] Replace the Telegram upload pipeline with XMPP HTTP File Upload (XEP-0363). Android photo/document sending is wired; complete picker/send/download/viewer verification remains pending.
- [o] Implement encrypted file upload/download using the documented XEP-0454 aesgcm format. Local round trips, independent-provider decryption and failed-authentication preservation pass; independent-client image exchange remains pending.
- [o] Connect photo/image selection, sending, receiving, previews and opening to that pipeline. Schema 4 stores media references and verified local paths; Android tests cover schema 1/2/3 migrations, real bitmap preview metadata and database reopen. Full picker/download/viewer interaction remains pending.
- [ ] Cover documents, video and voice messages using the same transport where applicable.
- [ ] Send and receive a photo with its caption in one message through the native caption UI, including OMEMO. Replace the current separate follow-up text send; use compatible file-sharing/body metadata and preserve captions in history, replies and cache.
- [ ] Support [XEP-0449 Stickers](https://xmpp.org/extensions/xep-0449.html) (checked version 0.2.0, Experimental): reuse the native sticker picker/rendering and pack previews, implement standalone/pack stickers through XEP-0447 file sharing and XEP-0446 metadata, and cache/verify files and pack hashes. Add PEP/PubSub pack import/sharing with textual fallback and restricted-pack handling; verify interoperability and encrypted-message behavior before claiming support.
- [ ] Support private encrypted sticker packs for personal photos of the user and friends. Encrypt every sticker/preview and the pack manifest; upload only ciphertext, keep decrypted caches private, and distribute decryption keys only to intended recipients/own devices through OMEMO. Never publish private-pack keys, plaintext previews or sensitive metadata in public PEP/PubSub entries; verify an upload/XMPP server administrator cannot recover the pictures. Define compatible private-pack sharing/import and account-device synchronization before advertising this alongside public XEP-0449 packs.
- [ ] Replace the provisional 100 MiB transport ceiling with limits consistent with the existing file UI and server capabilities; verify large streaming transfers and cancellation without buffering the entire file.
- [o] Surface server limits, missing upload support, progress, cancellation and retry. Transport limits/cancellation and error messages are implemented; verify the complete UI paths, including cancellation before worker dispatch.

## Optional helper and feature compatibility

- [ ] Inventory remaining Telegram-specific features: map each to standard XMPP, an optional helper capability, or an explicitly unavailable UI action.
- [ ] Support the helper on the separate host without making ordinary messaging depend on it or requiring control of another Jabber provider's server.
- [ ] Wire capability discovery and the helper features into Android.
- [ ] Harden scheduled-message persistence, restart/retry semantics, ownership and limits; test encrypted scheduled delivery.
- [ ] Package and verify deployment for 192.168.1.24 using Nix.
- [ ] Address remaining applicable features such as calls through standard XMPP support, and remove misleading Telegram-only service controls.

## exteraGram features

Reference APK: `~/extera/exteraGram-full-universal-20261004.apk`. The user reports that exteraGram's GitHub source is outdated; use the supplied APK as the behavior reference and reverse engineer it where needed.

- [ ] Inventory exteraGram features from the supplied APK and prioritize which to bring to Monogram.
- [ ] Compare the available source with the APK; inspect/decompile APK code and resources where the source does not cover the shipped behavior.
- [ ] Map selected features to local client changes, standard XMPP extensions or optional helper capabilities, accounting for OMEMO and MUCs.
- [ ] Implement and verify the selected features on the current official Telegram base, recording attribution and applicable license requirements.

## Watch version

- [ ] Plan a Monogram watch app; confirm the target watch platform and whether it should work independently or through a paired phone.
- [ ] Design watch-sized conversations, notifications, replies and input, with account setup and OMEMO trust/key handling appropriate to the chosen architecture.
- [ ] Implement and verify the watch app, including background connectivity, reconnection, battery use and interoperability with phone Monogram and other XMPP clients.

## Stories (backburner)

- [ ] Add Monocles-compatible stories after core messaging/profiles/MUCs are reliable, reusing the existing Telegram stories UI where practical. Stories are outside core XMPP: evaluate [XEP-0501 Pubsub Stories](https://xmpp.org/extensions/xep-0501.html) (Experimental), built on XEP-0472 social feeds and PEP/PubSub. Verify Monocles' actual wire format and interoperability, then implement viewing/publishing, audience/access controls, expiry/retraction and media caching. Do not require the optional Monogram helper for standard stories.

## Premium features and monetization

- [ ] Inventory Telegram Premium/Pro features; make every frontend-only feature and every feature that translates to XMPP available free in Monogram, without premium gates or upsells.
- [ ] Remove monetization features that cannot be ported to XMPP, including the Telegram wallet, purchase/subscription flows and their UI entry points. Audit Stars, paid media/messages, gifts, ads and related Telegram-only service requests.
- [ ] Verify that supported features work without a paid Telegram account and that removed monetization controls cannot be reached through settings, navigation, notifications or links.

## Release verification

- [x] Build and publish [0.2.11](https://github.com/doromiert/monogram/releases/tag/v0.2.11) (71869, arm64, existing signing key), including corresponding source and checksum; verify GitHub's uploaded APK digest matches the local artifact. Wireless ADB reports “No route to host”; the download link was successfully sent through the verified Codex bot's private destination. The agent has not installed this version on the phone; real-device migration/touch checks remain pending.
- [x] Record delivery fallback: try wireless ADB at `192.168.1.128:39127` first. If connection/install fails, send the updated APK through the existing Codex Telegram bot; for APKs above Telegram's upload limit, the user approved sending a GitHub APK download link instead. The running `codex-telegram-bot.service` and its configured owner's private destination were verified read-only; no test message was sent. Preserve the existing signing key and app data.
- [ ] Finish branding, launcher variants, app links, attribution and licensing on the current base.
- [ ] Verify plain Jabber operation without the helper and graceful handling of unsupported extensions.
- [ ] Run unit tests, real-server integration tests and independent-client interoperability checks covering the actual supported paths.
- [ ] Verify Android login, history, text/OMEMO messages, groups, attachments, reconnects and notifications.
- [ ] Produce a reviewable APK and current build/deployment instructions for https://monogram.neg-zero.com.
- [ ] Audit the full original goal against current runtime/build evidence before marking it complete.

## Repository

- [x] Publish the current-base Monogram source and TODO to https://github.com/doromiert/monogram (`master` and `monogram-current-base`).
- [x] Remove the extra comparison checkouts; retain upstream commits as branches in this repository.
- [x] Publish the preserved prototype branch as `prototype/telegram-foss`.

## Completed prototype milestones

These describe the earlier Telegram-FOSS 10.14.3 prototype; they do not prove the current-base port works.

- [x] Create the Monogram checkout and separate Android package ID.
- [x] Pin a Nix development environment and build the prototype's arm64 native dependencies/APK.
- [x] Add a shared XMPP transport with verified TLS, persistent OMEMO key storage and fingerprint decisions.
- [x] Pass local Prosody tests for plain messages, OMEMO round trips, offline delivery and the scheduling helper.
- [x] Add Jabber sign-in, direct-message/storage adapters and initial encryption controls to the prototype UI.
- [x] Install and launch the initial APK through wireless ADB at `192.168.1.128:39127`.
- [x] Build and install 0.1.1 with the lock touch-target fix and actionable OMEMO send errors; tapping the lock reaches Conversation security on the phone.
- [x] Add the narrow OMEMO AES-GCM provider fix; pass its regression test and the existing integration tests (4 tests, no failures/skips).
- [x] Download and inspect the current official Android source linked by https://telegram.org/apps#source-code.
- [x] Save the prototype as local checkpoint commit `d46543df` before starting the rebase.
