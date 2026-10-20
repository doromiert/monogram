# Device-local chat appearance

XMPP conversations use Telegram's native chat-theme sheet, gallery/camera wallpaper
picker and wallpaper preview. The six bundled chat palettes and a custom solid,
gradient or image background can be applied on this device without Premium,
boosts, a Telegram account, or a wallpaper/theme update sent to either service.
The other participant's appearance remains their choice. The native apply button
says “Apply on this device”; no “for both” option is offered for XMPP.

Saved overrides use account slot + account bare JID + peer bare JID, hashed for
local keys and image directories. Reusing a numeric peer ID or signing into a
different account cannot select another conversation's saved appearance. Logout
increments a runtime generation before credentials/peer mappings are cleared;
old asynchronous writes and callbacks cannot cross even a same-JID relogin.
Preferences themselves remain available when returning to the same account.
Old numeric-ID-only theme/wallpaper preferences are deliberately not migrated:
reselect once rather than risk assigning an earlier account's background.

Native no-file colors/gradients retain their wallpaper settings and motion
rendering. Image, blur and pattern previews are saved as private JPEG snapshots
bounded to a 2048-pixel longest edge, preserving the current crop/blur/pattern
appearance; patterned backgrounds become static raster snapshots. Dark-mode
dimming remains a native wallpaper setting. Files are copied/encoded off the UI
thread and fsynced before the preference commit. Failed saves keep the previous
override and leave the preview open with an error. Replaced/reset owned images
are deleted only after successful metadata commit; uncertain paths are retained.
No original gallery/provider file is removed. In-memory owner and decoded
wallpaper caches avoid repeated peer SQLite lookup/decoding on drawing paths.

Selecting a palette atomically resets its custom wallpaper override. Native
preview delegates update the open chat even when UserFull/ChatFull is not cached;
null full-info notifications are never emitted. Reopening or restarting resolves
the owner-scoped override through ChatThemeController. Group appearance uses the
same local sheet rather than Telegram's server-only channel color/boost editor.
This scope does not implement remote shared wallpapers, purchased/gift themes,
profile/name colors, or peer-wide appearance synchronization.

Four focused synthetic fixtures in `MonogramChatAppearanceTest` cover peer/account
isolation and reopen, native settings and atomic theme reset, owned-file
replacement/reset/failure retention, and same-JID logout fencing of a pending
write. Prepared only; not run before the combined release gate. Device acceptance
should apply a palette and custom photo in a DM and MUC, reset them, reopen and
restart, and switch accounts. Confirm loading/failed images cannot replace the
current wallpaper and no Telegram request/boost/purchase UI appears.
