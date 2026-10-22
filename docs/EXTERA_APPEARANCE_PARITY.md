# exteraGram Appearance port

Reference: user-supplied `exteraGram-full-universal-20261004.apk`, SHA-256 `1ed01526c0cb57bc798c29cc06ab4d01d30282ce2cc1b177f0222e906c22581f`.

The reference screen has 37 IDs, including preview cells. Static JADX output is retained in ignored `.state/ExteraAppearance.java`, `.state/ExteraNavigation.java`, `.state/ExteraIconStorage.java`, and `.state/ExteraPillRegistry.java`. REA MCP remains unavailable with previously diagnosed registration drift; these started as static observations; the physical-device reference captures below now supplement them.

## Implemented controls (0.2.146)

- Dedicated Appearance screen linked from Chat Settings.
- Avatar radius, consistent forum/chat shapes, snow, centered title, hidden stories/compose/search, sender mini avatars, home title.
- Folder title/icon modes, all/unmuted/off counters, optional hidden All Chats.
- App Navigation: tablet auto/on/off, bottom bar show/hide/floating, transition style, predictive back intensity, drawer/immersive animation, reorder/hide menu destinations and separators, reset.
- Launcher icon selector, icon packs with local editing/import/export, pill configuration (weather, storage, XMPP connection, daily USD reference rate).
- Floating button shape, system fonts/emoji, Material loading/sliders/switches/header/navigation/FAB.
- Gooey avatar transitions, custom chat themes, section radius/separated headers/dividers, glass outline/menu/forced blur.

Emoji status remains removed as explicitly requested earlier. Cryptocurrency pills remain excluded under the existing removal request. Telegram-only destinations need XMPP-supported equivalents. Existing Monogram defaults are retained unless the user changes a control.

Do not mark full parity complete until every control has a rendering/interaction implementation and the release gate is recorded. Tests run only during APK preparation. Phone installation is through the GitHub updater, not ADB, unless the user changes that instruction.

## Integration and limits

Settings → Chat Settings → Appearance is the entry point; App Navigation is a real nested screen. Existing Chat behavior shortcuts retain their account-scoped values. Other appearance settings are device-wide, like the reference client. Tablet mode requires a restart, as in exteraGram.

Icon packs use the reference `metadata.json` fields `packId`, `packName`, `author`, `version`, and `icons` (native drawable name → relative PNG/WebP/JPEG/SVG path). Imports are ZIP archives; exports use the same format. Imports are staged, paths contained, duplicate IDs rejected, and the icon map loaded off the UI thread. Enabled packs are applied in configured order, first override winning. No matching override means the native icon. Launcher choices reuse Monogram's registered icon aliases rather than adding exteraGram branding.

Pills sit beside the idle home search field, support drag order, hide/show, swipe selection and optional wraparound. Weather uses an explicitly selected city with [Open-Meteo geocoding](https://open-meteo.com/en/docs/geocoding-api) and [current weather](https://open-meteo.com/en/docs); no automatic device location lookup. The connection pill opens XMPP connection settings. The USD pill uses [ECB daily reference data](https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml) with a selectable quote currency. Neither pill routes through Telegram. Weather reuses 30-minute data, exchange data is daily, cache scans are reused for ten minutes; detached views stop timers.

The Material widgets and drawer are implemented in Monogram's current native renderer, not a wholesale replacement with APK decompilation. Full pixel/motion parity with the supplied APK is unverified. Native preview, native launcher aliases and the XMPP-adapted destinations intentionally preserve Monogram identity and existing behavior. Emoji status remains permanently hidden and crypto pills remain removed.

## Release verification

Nix arm64 packaging and 15 focused release checks pass: existing theme/preference cases plus navigation ordering, archive path containment/duplicates, and native resource fallback. APK version/signing verification and publication are recorded in TODO.md and MONETIZATION_CHANGES.md. Phone visual acceptance, RTL/large-font layout, motion, imported-pack appearance and battery profiling remain pending. No ADB installation.

A minimal pack ZIP contains `metadata.json` and the referenced files at its root:

```json
{"packId":"example.outline","packName":"My outline icons","author":"Me","version":"1.0","icons":{"msg_edit":"edit.svg","msg_saved":"bookmark.png"}}
```

Resource names stay stable across builds; numeric Android resource IDs are not exported. Relative subdirectories are supported, but references outside the pack are rejected. SVG imports have no external entity support. Imports do not overwrite an installed pack with the same ID.


### 0.2.147 drawer and flat-navigation correction

The initial drawer overlay did not reproduce the supplied reference. Replace it with an activity-root drawer whose gesture interception runs above the home pager. Adapt the supplied APK’s DrawerContainer/Header/AccountPicker geometry: 300dp maximum width, 72dp account avatar, 15/12sp name/JID header, day/night action, expandable account card, 48dp menu rows and rounded outside corners. Preserve native XMPP account switching/login, profile, archived chats, bookmarks, settings and configured menu visibility/order. Use MenuDrawable instead of the overflow entry while drawer mode is enabled. Dragging updates both panel and immersive content translation directly; release/cancel settle through a cancellable animator. Restore scroll/velocity cleanup on detach.

Flat navigation now fills available width and paints through the system navigation inset; floating mode retains its original padding and glass shape. The colored profile hero uses white status text, transitioning to themed compact-bar text on collapse.

Release checks: Nix arm64 packaging, existing 15 focused cases and three Robolectric real-view gesture cases pass. APK signer/version/exclusion audit verified and previous entry inventory preserved. No phone installation; visual comparison, closed-drawer opening and physical-device motion acceptance remain pending. This is an adapted native implementation, without a pixel-exact parity claim.


## Reference correction audit (in progress, after 0.2.149)

Reference package `com.exteragram.messenger`, version 12.10.6/code 71129. The supplied “universal” APK contains ARM libraries only. Static evidence is from its DEX classes, not upstream guesses. Runtime evidence is tracked separately; installing the APK does not count as visually verifying it.

| Surface | Initial mismatch | Reference-backed correction | Evidence |
| --- | --- | --- | --- |
| Separated headers | Forced gray fill; wrong padding; header remained inside some white groups | Transparent header, 24dp inset/3dp bottom, exclude all four header types from grouped surfaces and divider boundaries | HeaderCell, UniversalAdapter, UniversalRecyclerView |
| Segments | 6dp gaps; every row fully round; native cell clipping still square | 2dp gap before adjacent rows, 4dp inner corners, outer section radius; matching row/selector/context clipping | RecyclerListView and ListSectionsDecoration |
| Slider | Thickened hand-drawn native track/rectangular thumb everywhere | Actual Material 1.14 Slider: 8dp track, 24×3dp thumb, no halo/stop marker, native value/delegate synchronization; preserve native buffering/timestamps/two-sided controls | MaterialSliderUiHelper, SeekBarView |
| Switch | Arbitrary track/knob, always drew a check; no reference spring/ripple/disabled treatment | 0.8125 scaled reference geometry, tone-derived off colors, morphing thumb, supplied icons, outline/ripple/press state, 510 stiffness/0.65 damping | SwitchUiHelper and Switch |
| Loading | Hand-drawn oscillating star substituted globally | Real Material LoadingIndicator/CircularProgressIndicator, explicit reference style IDs and drawable lifecycle; native spinner when the caller requests native style | RadialProgressView and ChatActivity pinned progress |
| FAB | Material flag forced square shape but left 48dp size | Independent round/square choice; Material 56dp/elevation6dp vs native48dp/elevation0.5dp; source sub-button padding/radii/margins | FabUiHelper, UIUtil, FragmentFloatingButton |
| Chat header | Solid action bar; lost glass buttons | Keep glass back/menu buttons, suppress center pill and shadow, 46dp avatar, source spacing/top fade | ChatHeaderUiHelper |
| Navigation | Only changed pill corners | Full-width64dp Material layout; 56×32dp icon indicator at y6, label at42, reference selection/color springs, no swipe selection | MainTabsUiHelper, GlassTabView |
| Glass outline | “Solid” copied one edge color; no side borders | Source full-stroke path/color/cache; hidden outlines and appropriate shadow suppression | BlurredBackgroundDrawable/color provider |
| Appearance screen | Combined home mock and flat six-row Material list | Separate avatar slider specimen, native action-bar preview, native folder preview, shape chooser; expandable Material group | AppearancePreferencesActivity, AltSeekbar, preview components |

Runtime light/dark comparison, RTL, large fonts and slowed motion remain unverified until reference execution is available. Existing account, icon-pack and XMPP-specific options stay adapted to Monogram; emoji status and crypto remain excluded as requested. No full parity claim yet.


### Physical-device reference capture — 2026-10-22

The user authorized five minutes of phone access. Connected to their supplied ADB endpoint at 19:07 UTC, disconnected at 19:11 UTC, before that limit. The installed reference is exteraGram 12.10.6 / 71129, matching the supplied APK. No APK was installed and no messages were sent. The temporary reference preference changes (divider Lines, separate headers off/on) were restored to Segments with separate headers on; `restored.xml` and `restored.png` record the final state. Material children were expanded then collapsed without changing their values. No account data was edited.

Ignored evidence directory: `.state/reference-phone-20261022/`. Captures include avatar corners/online dot, action-bar preview, real folder preview, independent FAB shape previews, all six expanded Material children, line dividers with separate and integrated headers, segmented rows, glass controls, and App Navigation with visible/hidden destinations. `appearance-top.png`, `appearance-1.png` through `appearance-3.png`, `material-expanded.png`, `sections-lines-separated.png`, `sections-lines-integrated.png`, `restored.png`, `navigation.png`, `navigation-1.png` and `navigation-2.png` are the useful reference captures. An earlier file named `sections-restored-segments.png` actually still shows Lines; only `restored.png`/XML establishes the restored value. Initial drawer screenshots caught motion and are not geometry evidence. The later Monogram home capture changed orientation; it is not a same-orientation candidate comparison.

Confirmed corrections from observation: Material group header shows an enabled/total count, remains checked when any child is enabled, row tap expands and only the trailing switch toggles all; children are indented round checkboxes. Avatar specimen includes its shape-dependent online dot and time placeholder. Folder preview uses actual configured folders followed by samples. Navigation destination rows have icons and drag handles rather than switches. The line/segment header captures confirm transparent separate headers and integrated surface headers.

Candidate assembly 3 succeeds with Material 1.14 components. Further corrections from the phone captures are being compiled separately. Automated tests remain deferred to the release gate. This establishes reference observation, not full candidate parity: direct candidate comparison, light theme, RTL/large fonts, all toggle transitions and slowed animation are still pending.


### Live candidate preview

The user requested a live emulator window. Monogram candidate assemblies 4 and 5 run in the isolated Android 15 emulator (`emulator-5580`), viewed through scrcpy titled “Monogram — live appearance preview.” The local APK copy is signed with a throwaway harness key solely to allow development instrumentation; frontend code/resources are unchanged by that signing step. No real account is loaded and network radios are disabled. The harness opens the actual MonogramAppearanceActivity without activating an account. Its resumed-activity callback avoids waiting forever for the intro's continuously animated idle state. Evidence: `.state/monogram-ui-harness/appearance-opened.png`, `appearance-material-night.png`, and `run-4.log`/`run-5.log`.

Native light mode and Material dark segmented settings render. Live feedback identified that forcing a pool/adapter reset on every setting change destroyed switch animation. Replaced that reset with animated item updates and explicit visible-widget transitions, retaining view identity. The user confirmed both native/Material animations then work, but style changes initially left cached child display lists stale. Added recursive attached-view refresh with per-switch style-state reset only when the style actually changes; ordinary toggle animations continue. System-font view recreation is deferred until the initiating switch animation finishes. Candidate 6 includes the style redraw follow-up and public-server signup choices; it is not yet a released APK.

Candidate comparison remains incomplete: glass settings header still needs the reference BasePreferencesActivity composition, and same-density/light-dark/RTL/motion checks remain to be finished. The live preview is interactive; do not operate the physical phone outside a new user-authorized window.


### 0.2.150 release checkpoint

The user confirmed native/Material toggle animation and the subsequent live style refresh in candidate 6. Implemented the reference BasePreferencesActivity header composition: independent glass back/menu buttons, 12dp header padding/20sp title, scroll capture/noise suppression, gray top fade, edge-to-edge insets and retained list state. Real Material 1.14 components are retained rather than approximated. Final packaged APK was loaded only into the isolated emulator through the throwaway-signed development copy. Light Appearance renders at the top and after scrolling; dark Material App Navigation renders the real slider and both switch states. Captures: `appearance-150-top.png`, `appearance-150-scrolled.png`, `navigation-150-dark.png`; instrumentation logs report successful screen opening.

Emulator radios were re-enabled for the user's sign-in attempt; the earlier offline-preview statement describes the initial setup only. Disroot rejects the attempted credentials with SASL not-authorized; both service endpoints have verified Let’s Encrypt chains from the PC. No physical-device account was logged out or new account created.

This packages existing corrections; it does not establish full exteraGram parity. Outstanding items include the special isolated pill/animation-offset behavior, some fixed bottom-navigation/FAB caller reserves, exact reset/choice-dialog placement, and comprehensive RTL/large-font/motion/physical-device acceptance.
