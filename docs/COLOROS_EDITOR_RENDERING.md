# Invisible editor text on ColorOS 17

Live diagnosis on 2026-10-21 used Monogram 0.2.128 on a OnePlus PLZ110,
Android 17 / API 37. The original process (PID 8371) stayed alive throughout.

The chat composer was visibly blank despite eight typed characters being present
in its SpannableStringBuilder and the IME selection. The editor and layout shared
the same text and paint objects, with an opaque light text color, no shader or
color filter, a valid 830-pixel layout width, and no text transformation.
Other screen text remained visible. The app was using Skia/Vulkan.

The affected editor had `wrapCanvasToFixClipping=true`, enabling Telegram's
`NoClipCanvas` proxy. Using the existing debug connection, changing only this
instance flag to false restored visible input, confirmed by the user. No restart,
text replacement, selection change, message send, or graphics-driver setting
change was used. The temporary workaround was left enabled in the original
process. The retained initial screenshot is `.state/input-render-live/screen-before.png`.

This establishes the proxy Canvas as a trigger/workaround on the observed device;
it does not establish the exact failing Android/Oplus native rendering routine.
The later automated screenshot comparison overlapped with the user switching apps
and is not evidence of a successful visual A/B test; those images were discarded.

## Permanent workaround

`EditTextEffects.allowHackingTextCanvas()` now bypasses the proxy on API 37+
OnePlus, OPPO and realme devices. This uses the native editor Canvas, following
the existing manufacturer compatibility policy used for other devices. All
subclasses (composer, captions and settings editors) inherit it. It does not
disable hardware acceleration, rebuild text, reset selection or restart the IME.
Older OS versions and other vendors retain their current policy.

The OnePlus live recovery is confirmed. OPPO/realme are the same-platform
compatibility scope, not separately tested devices. Long-term recurrence and
native clipping of tall emoji, multiline quotes, selection handles and captions
still require device acceptance. Focused host checks cover the vendor/version
boundary only; they cannot reproduce the ColorOS rendering failure.
