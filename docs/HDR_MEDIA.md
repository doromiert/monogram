# HDR media

Both clients retain original media bytes and their embedded color metadata through
normal authenticated file transfers. No unauthenticated HDR hint or new XMPP
extension is added. Encryption, hash verification, private caches and the existing
attachment limits stay in force.

Desktop decodes high precision and CICP through sandboxed Glycin2.1.5, retaining
color states during resizing and private PNG thumbnail caching. Cache version3
regenerates previously flattened thumbnails. Floating highlights are converted
to a bounded16-bit PQ representation when CPU resizing is needed; resizing stays
on workers. Cached preview reads allow32MiB for1536px high-precision thumbnails,
with the existing128MiB/256-file cache bounds.

Ultra HDR JPEG gain maps are reconstructed by libultrahdr1.4 inside the existing
network-free, resource-limited media-author sandbox. The input is a copied verified
file descriptor; dimensions are checked before decode, output is bounded to4096px,
EXIF orientation is applied, and derived files are removed with the private scratch
directory. Original files are never rewritten. Ordinary JPEGs and unsupported or
invalid gain maps retain Glycin's compatible base-image path. The helper's128MiB
encoded-input and64MP dimension limits are independent of upload size limits.

The upstream GTK GStreamer sink retains10-bit HDR10/PQ and HLG color states in
inline/viewer playback. Its paintable is initialized on the GTK thread before
Clapper prerolls; it is not wrapped in an8-bit GL sink. Posters use16-bit color-managed
textures. GTK4.22's process-local `GDK_DEBUG=color-mgmt` opt-in is set before display
initialization, without changing compositor or monitor preferences. SDR outputs
use the platform's color conversion.

Android uses native gain maps on Android14+, and native PQ/HLG/extended color
spaces where available. Native metadata selects RGBA_F16 for HDR stills; gain-map
JPEGs keep their base bitmap plus enhancement data. Visible HDR receivers share
window ownership and restore its prior color mode when the last one leaves.
There is no polling service, permanent brightness override or new wake lock.
The existing SurfaceView/ExoPlayer viewer supplies HDR video output.

Unedited HDR stills bypass the SDR photo-upload rendition; the original source
and correct JPEG/PNG/HEIC/AVIF MIME are retained. Current native HDR video metadata
bypasses conversion only when dimensions, time range, audio and effects are
unchanged. Edits continue through the existing editors; this change does not add
HDR authoring or guarantee preservation through filters/re-encoding. Playback also
depends on device codec, display and compositor capabilities; Dolby Vision dynamic
metadata and proprietary Apple gain-map variants are not independently validated.

Verification uses generated CICP16-bit PNGs, floating highlights, an actual JPEG
gain map and EXIF portrait, warm caches, and10-bit PQ/HLG HEVC playback/posters.
Android tests cover native gain-map decoding and unchanged bytes on API35, window
ownership/restoration and video-edit guards under Robolectric. Host Skia does not
parse the fixture's gain map, so the decoder check runs on actual Android rather
than being substituted by a mock. Hardware brightness is not measured by SDR
emulator/X11 screenshots.

References: [Android Ultra HDR](https://developer.android.com/media/grow/ultra-hdr/display),
[Android HDR video](https://developer.android.com/media/grow/hdr-playback),
[Glycin](https://gnome.pages.gitlab.gnome.org/glycin/),
[libultrahdr](https://github.com/google/libultrahdr),
[GTK video sink](https://gstreamer.freedesktop.org/documentation/gtk4/index.html).
