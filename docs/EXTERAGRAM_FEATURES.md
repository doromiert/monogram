# exteraGram ports

Reference: the supplied `exteraGram-full-universal-20261004.apk`, SHA-256
`1ed01526c0cb57bc798c29cc06ab4d01d30282ce2cc1b177f0222e906c22581f`.
Use shipped APK behavior as the target; the public repository alone is insufficient.
These are partial ports, not a claim of complete exteraGram parity.

## Implemented source

- Configurable incoming/own double-tap actions: native action availability and
  illustration; see DOUBLE_TAP_ACTIONS.md.
- Monet Light/Dark/Black themes: see MONET_THEMES.md.
- Camera expansion: native 350 ms open, 220 ms close and DEFAULT interpolation;
  see CAMERA_FAB_TRANSITION.md for the FAB adaptation and outstanding device check.
- Chat Settings → Chat behavior: hide keyboard/emoji panel on manual chat drag;
  add comma and space after mention selection, excluding bots; optional Save and
  Repeat menu actions using the existing forwarding/outbox path. Secret/forwarding
  restrictions and group send permissions remain enforced. Values persist per account.
  Keyboard/comma default on, Save/Repeat menu default off, matching APK ExteraConfig.

The last four behaviors are based on static JADX excerpts in the ignored
`.state/ExteraConfig.java` and `.state/ExteraChatActivity.java`, including the
SCROLL_STATE_DRAGGING handling and mention span boundaries. This is ordinary
decompilation evidence, not an observed runtime or REA Evidence record. REA MCP
remains unavailable; its previously saved doctor reports registration drift.
The settings/suffix fixture passes at the 0.2.120 gate; native interaction remains unverified.

## Message menu, playback and free self colors

0.2.123 source adds Copy photo (default on) and lazy Message details (default
off) in Chat Settings → Chat behavior, using the native swipe-back metadata
submenu and existing copy/open handlers. Details substitute stored XMPP
identifiers/encryption for Telegram-only fields; cached media inspection never
fetches attachments. See EXTERA_MESSAGE_MENU.md for exact limits.

Chat Settings → Media playback exposes pause-on-minimize for viewer videos,
voice messages and round videos, plus volume-button unmute. Supplied APK defaults
and native lifecycle entrypoints are retained; see EXTERA_MEDIA_PLAYBACK.md.

The native self color picker now offers free device-local name/reply and profile
swatches with durable account/JID ownership, without purchase or color-update
RPCs. This is not peer-synchronized appearance or a complete background-emoji
port; see PROFILE_COLORS.md.

## Remaining scope

The full APK also contains chat/header/list appearance,
message-menu grouping, media/camera options, notification controls and other
features. These still need individual implementation and runtime parity checks.
Telegram-specific services require a working XMPP equivalent rather than dormant
Telegram API calls. Premium frontend features and monetization removal are tracked
separately in TODO.md; this inventory does not certify those complete.

## Configurable swipe actions

The next source checkpoint ports the supplied APK's multi-action left swipe.
Chat Settings → Message swipe actions supports an ordered enabled list, drag
reordering and accessible move controls, an empty list to disable the gesture,
looping and reverse vertical selection. The default is Reply alone, with looping
and reversal off. The interactive illustration uses the same selector as chats
and never executes an action.

After the native left-swipe threshold, vertical intent arms the action selector:
44 dp for the first choice and 36 dp for later choices. The stack chooses the
roomier vertical direction and uses the APK's position/visibility/fill/ring
spring parameters, service backgrounds, icon fit, quick-reaction icon and haptics.
Both settings and the gesture use per-account action order and flags.

Reply, quick reaction, Copy, Forward, Edit, Save, Repeat, Delete and Translate use
native message availability/handlers. Save/Repeat retain existing transport and
forwarding restrictions; deletion retains confirmation. Quick reactions remain
Unicode because XEP-0444 does not carry Telegram custom-emoji document IDs.
Grouped media uses the visible album's right edge for selector placement.

Static references: ignored `.state/ExteraSwipeAction.java`,
`.state/ExteraSwipeActionsHelper.java`, `.state/ExteraSwipeActionsHelper-debug.java`
and `.state/ExteraChatActivity.java`. The rendering method failed full JADX
reconstruction; its native arc/fill/ring drawing is expressed using the existing
Telegram renderer and the recoverable selector parameters. This is not a runtime
comparison or a claim of pixel-exact APK parity. Three focused preference/threshold/loop
fixtures pass at the 0.2.122 release gate. Native visual/accessibility/gesture checks remain.
