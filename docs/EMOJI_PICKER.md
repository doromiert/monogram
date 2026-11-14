# Emoji and sticker picker gestures

Mobile and desktop share an enlarged hold preview for standard Unicode emoji,
custom emoji and stickers. Moving onto another item after the preview appears
browses that item in the same preview. Mobile Unicode previews preserve the
displayed skin tone and accept presentation-selector variants such as ☠️.

In Favorites, briefly hold and start moving before the preview appears to
rearrange items. The actual neighboring cells move with the drag; releasing
persists their order. Once the preview appears, the gesture stays in preview
browsing and cannot turn into rearrangement.

On mobile, an extended stationary hold opens the existing emoji options menu.
Mouse/stylus context clicks also open it. Existing accessibility long-click
actions remain available, including the original skin-tone picker action.
Previewing or browsing does not insert or send an emoji.

Favorite/recent membership and display order sync through shared pack/item UUIDs
and Unicode identities; see [LAN sync schemas](xeps/monogram-lan-sync.md).

Android tests cover hold/move ownership, Unicode/skin-tone selection and existing
preview binding/browsing without document IDs. Physical gesture verification is
pending whenever the user's phone is in another application.
