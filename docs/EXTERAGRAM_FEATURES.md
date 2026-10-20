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

## Remaining scope

The full APK also contains configurable swipe actions, chat/header/list appearance,
message-menu grouping, media/camera options, notification controls and other
features. These still need individual implementation and runtime parity checks.
Telegram-specific services require a working XMPP equivalent rather than dormant
Telegram API calls. Premium frontend features and monetization removal are tracked
separately in TODO.md; this inventory does not certify those complete.
