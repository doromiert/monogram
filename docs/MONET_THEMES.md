# Local chat themes and Monet

Monogram's native theme pickers use six bundled color presets, each with Classic,
Day, Night and Tinted settings. Chat appearance is a local preference, preserved
when XMPP profile/room data refreshes; it does not require a Telegram account or
change another participant's appearance.

On Android 12 and newer, Browse Themes also offers Monet Light, Monet Dark and
Monet Black. These resolve Android's current system accent/neutral palette at
load time. A changed system palette refreshes the active Monet theme when
Monogram returns to the foreground, preserving the chosen wallpaper override.
Older Android versions retain the standard themes and local color presets.

The three `monet_*.attheme` templates were copied unchanged from the user-supplied
`exteraGram-full-universal-20261004.apk` (SHA-256
`1ed01526c0cb57bc798c29cc06ab4d01d30282ce2cc1b177f0222e906c22581f`).
Credit belongs to Exteragram and its theme contributors, including the Nekogram
and @tgmonet work acknowledged by Exteragram. Public older reference:
https://github.com/exteraSquad/exteraGram/blob/main/TMessagesProj/src/main/java/com/exteragram/messenger/utils/MonetUtils.java

The supplied APK is newer than that public source. Its `MonetUtils.getColor`
implementation was checked using Android SDK DEX disassembly: `a=percentage`
sets alpha, and `l=percentage` blends black into the selected palette color
(retaining that percentage of the original color). Monogram implements the
modifiers actually used in these templates. It does not import Exteragram's
networking or alter its APK.

Current validation covers all bundled palette tokens, local theme serialization
and the Night accent regression. Real device appearance and wallpaper-change
behavior still need verification.
