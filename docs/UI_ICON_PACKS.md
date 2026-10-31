# UI icon packs

Settings → Appearance → Icon packs → Import icon pack accepts a ZIP with
`metadata.json` at its root. Images can be PNG, WebP, JPEG or SVG. Missing
mappings keep Monogram's normal icon. Enable multiple packs and drag them into
priority order; the first enabled pack providing an icon wins.

```json
{
  "packId": "example.myicons",
  "packName": "My icons",
  "author": "Your name",
  "version": "1.0",
  "icons": {
    "msg_settings": "icons/settings.svg",
    "raw.camera_flip": "icons/camera-flip.svg",
    "ui.BackDrawable.back": "icons/back.svg",
    "ui.BackDrawable.close": "icons/close.svg",
    "ui.MediaActionDrawable.download": "icons/download.svg",
    "ui.Switch.checked": "icons/switch-on.svg",
    "ui.Switch.unchecked": "icons/switch-off.svg"
  }
}
```

Resource names are stable across APK builds; numeric Android IDs are not.
The in-app Add or replace icon screen lists **all** drawable resources, raw
animation resources, mipmap resources and named code-drawn controls. Search by
name rather than a restricted prefix. `drawable.msg_settings` is also accepted;
plain `msg_settings` remains compatible with existing exteraGram metadata.
`raw.*` replaces artwork in a bundled Lottie or SVG resource; it does not
replace user-sent stickers, photos or videos.

Code-drawn control keys are listed in `MonogramUiIcons.names()`. A state-specific
mapping such as `ui.BackDrawable.back` takes priority over the optional base key
`ui.BackDrawable`. Base mappings cover all states of that component. Media actions
use named states (`play`, `pause`, `download`, `cancel`, `file`, etc.); checkboxes,
radio buttons and switches expose `checked` and `unchecked`. Controls retain
native animation timing, click targets and accessibility; numbered selection
checkboxes retain their count labels (`ui.CheckBoxBase.numbered` can supply a
separate numbered background). Media progress rings keep their native progress
while their central glyph changes.

Artwork keeps its aspect ratio and resource icons keep their original intrinsic
layout size. Standard Android tinting still applies; code-drawn controls use their
configured color/alpha/filter. SVGs should be self-contained, with no external
entities. There is no fixed icon-count cutoff in the loader: every mapping stays
available while decoded raster memory is bounded by an LRU cache.

Import limits: 8,192 ZIP entries, 64 MiB extracted total, 4 MiB per file, and 1 MiB
metadata/SVG text. Paths must stay inside the pack, duplicate archive entries are
rejected, and referenced images are validated before installation. A failed
import leaves installed packs intact. Delete an installed pack before importing
another ZIP with the same `packId`, or edit/share it within the app.

The separate Use system emoji option uses the phone's emoji font in message text
and emoji picker drawables. This follows the installed system font (Noto on
systems that use Noto), not a bundled Telegram emoji sprite or automatic Telegram
emoji animation. Explicitly sent custom emoji and stickers remain message media.

These packs affect Monogram-rendered UI. Android-owned permission/status UI and
launcher rendering remain controlled by Android; the separate App icon setting
continues to manage Monogram's launcher variants.
