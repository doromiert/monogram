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
