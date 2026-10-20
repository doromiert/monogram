# Extera grouped message menu

Monogram ports the compact message-action strip from the supplied Extera APK:
`/home/doromiert/extera/exteraGram-full-universal-20261004.apk`.

## Static evidence

The source of truth is the packaged DEX bytecode, inspected without running the APK. In
`org.telegram.ui.ChatActivity`, the call to `ExteraConfig.getGroupMessageMenu()` is followed by
`if-eqz` around the compact-row construction and a second guard requiring more than five menu
options. This establishes the shipped behavior:

- enabled, which is the APK default: use the compact strip when more than five actions are eligible;
- disabled, or five actions and fewer: retain Telegram's ordinary labeled rows.

This bytecode supersedes JADX's damaged Java reconstruction, which inverted that branch. The
packaged English resources corroborate the behavior with `Group Message Menu` and `Grouping moves
the main actions to the bottom of the message menu.` Extera's `ActionRow` class supplied the visual
measurements and animation values.

## Ported behavior

The candidate order is Reply, Delete, Copy (or the eligible photo/phone copy variant), Edit, Copy
link, and Forward. Up to four eligible actions are selected first in that order; disabled candidates
fill any remaining places. Only actions actually shown in the strip are removed from the labeled
menu, so a fifth or sixth eligible action remains reachable as a normal row.

The strip is a separate 56 dp popup panel below the main menu. It uses 40 dp circular native action
buttons, 10 dp horizontal padding, an 8 dp top inset, native submenu colors, and Extera's staggered
12 dp horizontal entrance. Monogram mirrors the layout and entrance direction in RTL and supplies
each icon's action label to accessibility services. Every enabled button calls the same existing
`processSelectedOption` handler as its labeled row; disabled buttons cannot dispatch actions.

Message details remains a labeled swipe-back row, and Copy photo keeps its existing eligibility and
handler. The setting lives under Chat behavior → Message menu and defaults on, matching the APK.

## Verification boundary

This is static source reconstruction. Nix arm64 packaging and seven focused Android cases pass at the 0.2.124 release gate (six local-color/icon persistence/validation cases and one account-local chat preference case). The menu-cell array stays dense after compaction, and native welcome-message Delete suppression is retained. Runtime appearance, motion, TalkBack traversal, live theme changes and phone acceptance remain unverified; this implementation did not open or interact with the phone.
