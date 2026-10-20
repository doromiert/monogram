# Media picker camera transition

The reference is the user-supplied `~/extera/exteraGram-full-universal-20261004.apk`, SHA-256 `1ed01526c0cb57bc798c29cc06ab4d01d30282ce2cc1b177f0222e906c22581f`.

Static Android SDK DEX disassembly of `org.telegram.ui.Components.ChatAttachAlertPhotoLayout` in `classes3.dex` confirms the native `cameraOpenProgress` transition: `openCamera(boolean)` uses 350 ms, `closeCamera(boolean)` uses 220 ms, and both use `CubicBezierInterpolator.DEFAULT`. These are static observations, not a recording of Exteragram running. The inspected class still uses camera tile geometry.

Monogram reuses that existing transition and camera controls, adapting the geometry to its floating action button: a 56 dp square, a 28 dp initial corner radius interpolating to zero at fullscreen, and an origin converted from screen coordinates to the camera container. Both opening and closing use the same progress calculation. Camera hardware remains on demand and is released after closing.

REA tools were unavailable in this session; its read-only doctor reported registration drift. No configuration repair was attempted. APK findings above come from Android SDK `dexdump`, not REA Evidence. Working excerpts are in `.state/extera-camera-disassembly.txt` (ignored).

## Follow-up for FAB height and invisible opening animation

The FAB now uses the actual visible bottom caption/attachment controls as its
screen-coordinate boundary instead of reserving a fixed 100 dp. Its ordinary
bottom gap is 16 dp, and layout/pan changes refresh the animation origin.
The preview's collapsed Y coordinate is applied as well as X.

Expansion waits for the first initialized camera frame, then starts on the next
UI callback because CameraView sets its initialized flag after its delegate.
The old tile-preview fade does not compete with the FAB expansion. Devices without
flash modes still reach the open callback. Delayed camera disposal retains the
exact old view, so reopening cannot have its new preview removed by old cleanup.
No phone visual verification or camera activation has been performed for this fix.
