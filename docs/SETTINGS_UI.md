# Settings UI migration

The reference is the Chat Settings screen (`ThemeActivity`, basic mode) in the pinned official Telegram Android base. Custom XMPP pages use its native `RecyclerListView.setSections()` renderer (12 dp horizontal inset, 16 dp corner radius), `ActionBar.setAdaptiveBackground()` and native themed cells. Do not add a separate imitation of the glass surface.

## Surface inventory

| Entry point | Implementation | Current presentation |
| --- | --- | --- |
| Settings home | `SettingsActivity` | Existing native section renderer and navigation |
| My profile / settings name search | `UserInfoActivity` | Native profile editor; name search no longer opens the older standalone name form |
| Chat Settings | `ThemeActivity` | Native reference |
| Privacy and Security | `PrivacySettingsActivity` | Existing native sections and adaptive bar |
| Notifications | `NotificationsSettingsActivity` | Existing native sections and adaptive bar |
| Data and Storage | `DataSettingsActivity` | Existing native sections and adaptive bar |
| Chat folders | `FiltersSetupActivity` | Existing native sections and adaptive bar |
| Devices | `SessionsActivity` | Existing native sections and adaptive bar |
| Power Saving | `LiteModeSettingsActivity` | Existing native sections and adaptive bar |
| Language | `LanguageSelectActivity` | Existing native sections and adaptive bar |
| Round-video settings | `RoundVideoSettingsActivity` | Existing native sections and adaptive bar |
| XMPP connection | `MonogramConnectionActivity` | Shared native settings surface; connection, background operation and invitations cards |
| Conversation security | `MonogramSecurityActivity` | Shared native settings surface; encryption, reactions, verification, recipient policy and device cards |
| GIF search | `MonogramGifSettingsActivity` | Shared native settings surface |
| Translation | `MonogramTranslationSettingsActivity` | Shared native settings surface; provider/server/key value cells |
| Packs / individual pack | `MonogramPacksActivity` | Shared native settings surface; nonmodal account-owned import progress |
| Server invitations | `MonogramAccountInviteActivity` | Shared native settings surface |
| Contact authorization / requests | `MonogramContactActivity` | Shared native settings surface; presence values, request actions and explanatory footers |
| Room configuration / join / create | `MonogramRoomActivity` | Shared native settings surface and existing form cells |
| Room members | `MonogramRoomMembersActivity` | Shared native settings surface |
| New chat | `MonogramNewChatActivity` | Shared native settings surface |

The stock sticker, quick-reaction, proxy, storage, passcode and two-step-verification list screens also already use native sections. Forms and dialogs, settings search results, and nested navigation remain part of the overall review scope; a native-section call alone does not prove every state matches the reference.

## Invariants

Keep account selection, setting IDs, saved choices, API-key secrecy, foreground-connection behavior, fingerprint trust confirmations and per-recipient policy unchanged. Use existing native switches, values, destructive-row tags, text fields and dialogs. Theme updates must preserve accent/destructive distinctions.

Import progress persists when its screen closes, displays item and current-file byte progress when known, and allows cancellation. It does not resume across process death. Existing imported files must not need reimporting.

## Verification status

Production Java/APK compilation and six focused pack checks pass. Source review confirms the native section renderer and adaptive bar are shared by all ten custom forms. The authorization/security card pass moves explanations outside cards rather than leaving isolated headers. This is implementation and source evidence, not rendered acceptance.

Still required before the full migration is complete:

- Render all reachable settings screens, their nested forms and dialogs, and compare against Chat Settings.
- Check light and dark themes, theme changes while a screen is open, scroll transitions, RTL and large text.
- Check native selectors, switches, text fields and destructive/action rows for legibility and clipping.
- Confirm reconnect, background settings, fingerprint review, profile editing and provider settings remain usable.
- Verify the import progress, installed-pack artwork and sticker long-press on the device. Never modify the user's real packs or trust decisions just to exercise a check.

No phone navigation or setting changes were used as verification for this batch.

## Interface review record

Full review scope: settings home, all reachable stock settings and the ten custom XMPP forms. Android Views with Telegram's existing cells, theme keys, section renderer and adaptive navigation are the styling system. This record covers source inspection and compilation; it does not cover rendered acceptance.

| Category | Evidence inspected | Result |
| --- | --- | --- |
| Typography | Native HeaderCell, TextCell, TextSettingsCell, TextCheckCell, EditTextCell and explanatory footers | Native text treatment retained; large-font/RTL wrapping not rendered |
| Surfaces | ThemeActivity reference and UniversalRecyclerView section rendering | Shared native inset/radius renderer; orphaned security/authorization headers corrected |
| Animations | ActionBar adaptive background attachment | Existing native scroll treatment reused; motion not observed |
| Icons | Native back icon, existing reconnect/notification icons and theme bindings | Existing icon system retained; live theme appearance not rendered |
| Performance | Shared section renderer and account-owned import observer lifecycle | No added blur/compositing framework; actual scroll performance not measured |

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| Medium | Ten custom screens listed above | Flat legacy list presentation | Native section renderer and adaptive action bar | Matches the reference surface and navigation |
| Medium | Connection and security | Long mixed groups | Named cards, native status/value cells, explanatory footers | Keeps related controls together |
| Medium | Contact authorization and security | Explanation split header from actions | Explanations outside groups, presence shown as values | Avoids isolated header cards |
| Medium | Settings name search | Older standalone form | Native UserInfoActivity | Keeps settings navigation in the current editor |

Considered and rejected: adding a custom blur/glass layer would duplicate the existing renderer; globally modifying UniversalFragment would change unrelated Telegram flows; replacing stock settings already using native sections would add churn without improving fidelity.

Verification commands: Nix `:TMessagesProj_App:assembleAfatDebug`; focused `MonogramPacksTest` (six cases, zero failures/errors); source route and callback comparison; APK version/ABI/certificate checks. Rendered interactions and 10%-speed motion inspection were not run. Verdict: rendered acceptance pending; do not mark the full settings migration complete from these checks.

## 0.2.94 rendered follow-up

The user reviewed the generated settings screenshots and confirmed this is the requested visual direction. Offscreen Android native graphics rendering now covers all ten custom forms in 14 states (including DM/room security, fingerprint sections, empty requests and join/existing-room variants), plus the nested theme-link editor. Each is rendered at 360 dp phone width in light, dark, RTL and a 1.3 system-font configuration, before/after scrolling: 120 PNGs under `.state/settings-renders/`. The four rendering cases pass. Backend/account services, preference storage and unrelated native wallpaper operations are isolated with synthetic data; the actual views, section renderer, cells, theme palettes and action bars are rendered.

The RTL harness initially omitted Android’s attached-window pre-draw, making horizontally scrolling TextViews appear blank. Attaching the root and dispatching pre-draw restores the labels; no production RTL workaround was added for this harness artifact.

Rendered findings fixed: long connection-notice/recipient-policy/token labels; header-only empty pack/request/participant cards; room identity label disappearing once a nickname was filled. The source route inventory examines 54 literal nested settings routes. Three remaining native surfaces were updated: bot biometric settings, topic-notification exceptions and the theme-link form (native SectionsScrollView, keyboard-scrollable and without legacy divider shadows).

Remaining acceptance boundaries: all stock/nested settings and dialogs have not been rendered individually, live theme-change transitions and real device navigation/scroll performance are not verified. Reconnect/trust/provider actions were not exercised on the real account. These limits remain part of the full goal; the custom-form render matrix alone does not prove all reachable settings states.

## 0.2.95 follow-up

Changing the theme on already-created custom screens exposed a shared binding error: ThemeDescription maps reflected field names by class index, and the TextSettingsCell entry had no corresponding second field name. The binding now updates both switch and value-row labels. Round-video settings also now declares native bindings for its background, navigation, section surfaces, labels, values, switches and footers. Its recorder choices and persisted values are unchanged.

The pack-import failure card no longer displays Preparing after termination. The safe diagnostic is visible below the card and available through View import error with a Copy action in a native AlertDialog. The action remains in the import card instead of joining the next section. Detailed diagnostics were not in 0.2.94.

Five offscreen rendering checks pass for 17 screen states: the preceding 15 plus round-video settings and a failed-import example. Four modes cover light/dark/RTL/1.3 system font, before and after scrolling. The fifth switches the same instances from light to dark and back, applies native theme descriptions and ActionBar.updateColors, checks actual attached value-label/background colors and renders each result. The harness supplies the native chat bubble drawables needed by the theme-link editor; it scrolls the round-video RecyclerView rather than its enclosing frame. These are synthetic-account Android renders, not actual device interaction or a measurement of theme-animation timing.

The original custom/stock route review still applies. Full individual stock-screen/dialog rendering, real-device interactions and scroll performance remain outstanding; the settings goal is still in progress.

Delivery: 0.2.95 (72709), source `0be82b0ba`, built with Nix and installed through wireless ADB. Eighteen focused cases pass (five settings render cases, eight packs, four profile/alias/cache, one native storage); the earlier three portable-archive checks also pass. Installation preserves all 896 prior message identities, 608 read IDs, nine anchors, ten native notification entries, 132 pack files and existing account/legacy/modern identities. Schema 26 is active and the foreground connection service is present. The exact reported remote import has not been reproduced; ask for the new screen's copyable diagnostic rather than claim it fixed the cause.

## Nested settings follow-up (unreleased)

The native Do Not Translate page used TextCheckbox2Cell but its theme descriptions still targeted the retired LanguageCell. Bindings now cover the actual checkbox row labels, secondary names, headers and card backgrounds. Its existing native sections, selection and search behavior are preserved. Five offscreen cases cover this page and the new-proxy form in light/dark/RTL/large-font and same-instance theme changes. The rendered checkbox labels and card surfaces were inspected.

A second shared issue affected animated settings values: ThemeDescription handled TextView and SimpleTextView but ignored AnimatedTextView. Both direct text-color bindings and reflected fields now support the native animated label. Existing bindings in Chat Settings, Data and Storage, Notifications and Monogram settings therefore update their values during theme changes. Two focused regression cases cover successive colors and reflected bindings with tag isolation; both pass. The 17-state same-instance theme render also passes and checks value RGB plus the drawable's separate compositing alpha.

Seven native dialog types were instantiated with synthetic account data, without confirming actions. Dialog capture is not yet accepted: some child labels/buttons are absent from the offscreen output despite creation. An attempted frame-idle capture entered Telegram's repeating Choreographer callback; a JVM stack confirmed the fixture loop and the run was stopped. No production timing/layout workaround was made. Full dialog content and motion acceptance remain open, alongside stock-page coverage and actual phone interaction. These changes are included in the 0.2.96 source batch.


## 0.2.96 follow-up

Power Saving now declares native theme descriptions for its existing sections, controls, labels and footer. Its nested switch and threshold cells implement Theme.Colorable, preserving current switch/slider animation state while updating colors. Five offscreen power-screen render cases pass, including same-instance theme changes. Full stock-screen/dialog/device acceptance remains open.

The accompanying sticker repair preserves legacy-OMEMO sticker presentation with a bare public XEP-0449 display hint. The hint is deliberately separate from authenticated attachment and pack metadata; file content remains encrypted. Only successfully decrypted encrypted attachments can use it, after cached media validation. Schema 27 adds a URL-bound presentation flag without rewriting history. Both peers must upgrade for new legacy sticker sends; older sends without a marker remain images. Eleven core checks and three Android sticker/storage cases pass, alongside the previously passing profile/cache, packs and settings-color checks. A native-graphics fixture was needed to validate opaque TGS media correctly; invalid gzip never retains inferred sticker MIME. Real-server sticker round trips remain unverified.

Delivery: 0.2.96 (72719), source `546db8ca7`, published with the original signing identity. APK SHA-256 `238f3728a5d5c582bd1d7318ad4e6d5631083bdd1e42fb1761711e671ccf6f55`, 69,532,679 bytes; remote digest and tag match. ADB was unreachable (No route to host), so the authorized Codex bot delivered the APK download link. No phone installation, preservation or runtime verification is claimed for this release.


## Language page follow-up (unreleased)

The top-level Language page still described retired LanguageCell rows although its actual adapter creates TextRadioCell, HeaderCell, TextCheckCell and TextSettingsCell. Theme bindings now target the actual primary/secondary labels, radio states, section backgrounds, translation switches, animated values and explanatory text/links. Selection, search, translation/provider state and account routing are unchanged.

Five native offscreen cases cover light/dark/RTL/1.3 font configuration and same-instance light → dark → light transitions, before/after scrolling. Primary/secondary language-label colors are asserted through the actual screen descriptions. Inspected output confirms native rounded sections, legible translation controls and radio rows in light/dark and mirrored RTL. The first fixture attempts lacked initialized synthetic account shadows; a later fixture-only LocaleController constructor reset RTL/configuration. Both were corrected without production changes. Native cells use their existing dp text sizing, so a 1.3 configuration does not imply every label grows. Evidence: `.state/settings-language-light-followup.log`, `.state/settings-language-final-followup.log`, `.state/settings-language-config-followup.log`, and `language-*` PNGs in `.state/settings-renders/`.

This source change is not in the published 0.2.96 APK. Full stock-page/dialog and real-device acceptance remain open; this follow-up does not complete the settings goal.


## Network Usage native follow-up (0.2.97)

Network Usage retained a fixed action-mode header and had no live theme bindings for its current cells. Its tabs now live in the native ActionBar extra-height region with a transparent surface, sharing the existing adaptive header animation. Completed pager selections bind the active list; ActionBar detaches the previous adaptive RecyclerView listener before rebinding, so retained offscreen pages cannot control the visible header. The existing native transition is reused.

Theme descriptions cover the page, tabs, selectors, card surfaces, headers, body/value labels, arrow tints, footer links, rounding paint and tagged end spacer. Reset stays red. CacheChart implements native Theme.Colorable and refreshes its label/loading/category palette, retaining sector matrices, counters and animation state; attachment refresh handles charts returning from cache. No statistics/reset behavior changed. The Language page correction above is included in this version.

Verification: five populated native screen cases pass for light/dark/RTL/1.3 font configuration and same-instance theme transitions, plus one real pager-state case covering All/Mobile/Wi-Fi/Roaming and native category expansion. A focused behavioral case verifies old list scrolls cannot drive a rebound header. Inspected light and same-instance dark PNGs show the integrated gray header, rounded cards and updated chart/row labels. Evidence: `.state/settings-usage-final-check.log`, `.state/settings-usage-tabs-check.log`, and `usage-*` PNGs in `.state/settings-renders/`. Captures settle the native header animator to inspect its final state; actual device frame timing is not measured.

Dialog diagnostic follow-up: actual native provider rows and Cancel have nonzero geometry, visible state and full alpha. Explicit software-window rendering plus invalidation still omits some child content in offscreen captures. This remains an unaccepted capture, not evidence to change production dialog layout. Logs/trees: `.state/settings-dialog-layout-diagnostic.log`, `.state/settings-dialog-software-capture.log`, `dialogs-light-*-tree.txt`. No dialog actions were confirmed. Full remaining stock-page/dialog/device acceptance stays open.
