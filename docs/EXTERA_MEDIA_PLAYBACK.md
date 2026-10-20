# Media playback controls

Static source evidence: supplied `~/extera/exteraGram-full-universal-20261004.apk`,
exported with installed Nix JADX into `.state/ExteraConfig.java`,
`.state/ExteraBooleanPref.java`, `.state/ExteraMediaController.java`,
`.state/ExteraPhotoViewer.java`, and `.state/ExteraMediaLaunchActivity.java`.
No APK execution was used. JADX reported failures in unrelated methods/native
archive members; the relevant methods are readable Java, not fallback stubs.

`ExteraConfig` initializes pause-on-minimize for video to true, voice/round to
false, and unmute-with-volume-buttons to false. `BooleanPref` confirms the first
constructor argument is the preference default. Monogram preserves those values
in per-account main settings, under `monogram-extera-media-*`.

The native Chat Settings page exposes four independent switches. MediaController
observes the existing global minimize notification (`stopAllHeavyOperations`,
flag 4096) and its existing player-start notification. The voice/round switches
pause the currently playing message using that message's owning account. They
also prevent newly started voice/round playback while the app is minimized,
matching the APK. Music and foreground service policy are unchanged. An explicit
minimize pause clears the unrelated temporary audio-resume flag so another media
viewer cannot undo the user's choice.

PhotoViewer pauses a currently playing local video and suppresses automatic PiP
on leaving when enabled, then resumes only the exact same player on returning.
Releasing/replacing the player clears that resume token. A video the user had
already paused is not resumed. Explicit PiP, embedded web videos, and the media
viewer's existing volume controls are not reimplemented here. The supplied APK's
audio-focus helper is absent from this Telegram base; native VideoPlayer audio
focus behavior is retained.

The volume switch gates LaunchActivity's existing `maybePlayVisibleVideo()`
dispatch for the visible chat's account, including the tablet right pane.
The first successful gesture is consumed, exactly as in the APK; calls, story
viewers, open sheets, repeats, and fullscreen media retain native handling.

## Phone acceptance (not executed)

- Check default and changed values survive restart independently across accounts.
- Home/minimize a playing viewer video: enabled pauses and resumes the same item;
  disabled retains native automatic PiP. Paused videos must stay paused.
- Close/change the viewer while paused, switch account, or rotate: an unrelated
  player must never resume from a stale token.
- Test each voice/round switch independently, including a new playback started
  from background controls and a paused foreground voice opened in a viewer.
  Music must continue normally.
- Verify volume keys with switch enabled/disabled on phones/tablet panes, in
  sheets, in calls, in fullscreen media, and with no visible playable video.

The same release batch's image-copy helper has two prepared Robolectric cases in
`tests/robolectric/org/telegram/messenger/xmpp/MonogramPhotoClipboardTest.java`:
a decoded PNG is copied byte-for-byte into an independent private-cache snapshot
despite an unrelated source extension, and a non-image bearing a PNG extension is
rejected without an export file. These cases have not been executed. Clipboard
URI permissions, recipient interoperability, and native playback lifecycle still
need the release gate/device checks above; the fixture does not claim those are
covered.

0.2.123 Nix arm64 packaging and seven focused Android cases pass (four user-color, two native-graphics clipboard and one account-local chat-menu preference case). These checks do not exercise real playback, audio focus, PiP or phone lifecycle behavior.

## Configurable fullscreen gestures

Monogram ports the configurable fullscreen-video gestures from the supplied Extera APK:
`/home/doromiert/extera/exteraGram-full-universal-20261004.apk`.

## Static evidence

The packaged DEX bytecode was inspected without running the APK. Extera stores the double-tap seek
choice as indices `0..3`, with index `1` as the default. Its helper maps those values to 5, 10, 15,
and 30 seconds. The selected interval is used both for the actual seek and the native
`VideoForwardDrawable` feedback.

The shipped `PhotoViewer.enableSwipeToPiP()` also establishes the complete admission guard. The
gesture requires the setting, a visible native PiP action, an available and uploaded video texture,
a non-repeating video player, inline-window permission, and no active texture or inline transition.
On a completed upward drag it calls Telegram's existing `switchToPip(true)` path. Completion uses
either one sixth of the viewport or Extera's shorter 0.4 cm fling with its portrait/landscape
velocity threshold. Other completed drags retain the ordinary close behavior.

## Ported behavior

Media playback settings expose four native radio rows for the double-tap interval and a native
swipe-to-PiP switch. Both settings are per account. The 10-second interval remains the default and
swipe-to-PiP remains off by default, matching the APK.

Fullscreen videos apply the selected interval to the left and right double-tap regions, the seekbar,
and the native rewind/forward animation. Positions are clamped to the playable `0..duration` range.
The APK's admission helper compares a millisecond duration against raw `10` or `15` constants; that
unit mismatch was not copied. Monogram applies the intended 10-second minimum video length for the
5-second option and 15-second minimum for the other options. Forward seeking still requires more
than the selected interval to remain.

## Verification boundary

This is a static source reconstruction. Runtime motion, web-video behavior, accessibility traversal,
PiP permission flows, and device acceptance remain for the release gate. This implementation did
not build the app, run tests, execute the supplied APK, or interact with a phone.

0.2.125 packaging gate passes with the new native gesture controls. The two focused billing host cases do not exercise video gestures, PiP, codecs or real phone playback; those acceptance checks remain unverified at the user’s request.
