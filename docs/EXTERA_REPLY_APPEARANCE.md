# Extera reply appearance

Monogram ports the three reply-decoration controls from the supplied Extera APK:
`/home/doromiert/extera/exteraGram-full-universal-20261004.apk`.

## Static evidence

The packaged `org.telegram.ui.Components.ReplyMessageLine` DEX bytecode was inspected without
running the APK. `replyColors`, `replyEmoji`, and `replyBackground` all default to enabled.

- Disabling reply colors flattens resolved peer and collectible palettes to Telegram's native
  incoming reply line and reply-name colors, removing the animated multi-color line.
- Disabling reply emoji clears both the background emoji document and the collectible sticker
  decoration before the native drawable is updated.
- Disabling reply background uses a transparent decoration background while retaining the native
  line and readable reply-name/text colors. Code blocks and backgroundless sticker contexts keep
  their native special handling.

The shared renderer also serves quote, link, and contact call sites. The APK applies each check only
in its recovered branch: ordinary contacts retain native color and background handling, while
collectible contacts use all three controls; emoji suppression applies across supported call sites.
The settings do not alter profile colors, stored emoji choices, message content, reply targets, or
click behavior.

## Monogram integration

The controls use native check rows under Chat behavior → Reply appearance and are stored per
account. Rendering reads `messageObject.currentAccount`, so multi-account messages never inherit
the currently selected account's decoration preferences. With all three controls disabled, the
reply line and native text colors remain visible while the tint and emoji pattern are absent.

## Verification boundary

This is a static source reconstruction. Runtime theme transitions, RTL rendering, animation,
accessibility, and phone acceptance remain for the release gate. This implementation did not build
the app, run tests, execute the supplied APK, or interact with a phone.

0.2.126 packaging passes. The two focused billing cases validate the adjacent payment retirement, not reply rendering; native theme/RTL/motion/accessibility acceptance remains unverified at the user’s request.
