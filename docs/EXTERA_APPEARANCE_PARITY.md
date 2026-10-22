# exteraGram Appearance port

Reference: user-supplied `exteraGram-full-universal-20261004.apk`, SHA-256 `1ed01526c0cb57bc798c29cc06ab4d01d30282ce2cc1b177f0222e906c22581f`.

The reference screen has 37 IDs, including preview cells. Static JADX output is retained in ignored `.state/ExteraAppearance.java`, `.state/ExteraNavigation.java`, `.state/ExteraIconStorage.java`, and `.state/ExteraPillRegistry.java`. REA MCP remains unavailable with previously diagnosed registration drift; these are static observations, not a runtime comparison.

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
