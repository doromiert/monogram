# Mobile transfer follow-up

- [x] Retain upload phase, attempt count and sanitized failure stacks.
- [x] Add debug ADB testing prompts in Monogram and background notifications.
- [x] Rebuild motion photos without requiring a custom cover; preserve the original still and explicit cover choices.
- [x] Retry a fully flushed upload with a fresh slot after a one-second missing acknowledgement; keep the final acknowledgement and object-confirmation waits bounded.
- [x] Verify fast acknowledgement recovery on AndroidAPI37: first upload-response SocketException at21:24:50.671, successful fresh-slot attempt2 logged21:24:51.435 with396ms response phase; no subsequent upload/send failure in the captured interval.
- [ ] Recheck the upload service after its HTTP acknowledgement issue is repaired. Plain HTTPS HEAD requests to its root also stalled on phone and PC, independently of uploads or OMEMO. TCP/TLS connected promptly; HTTP response headers alternated between timeouts and fast HTTP 200 responses. The client now has a fast bounded retry and final object-confirmation fallback.

Do not treat the server acknowledgement issue as a fixed client bug. The phone
trace showed the upload body completing before the response timeout. The separate
motion-photo preparation failure happened before any upload body was sent.

# Profile-photo publication

- [x] Keep public PNG avatars within32KiB, preserving transparency and aspect ratio; local-only overrides keep their existing allowance.
- [x] Reject vCard writes above60KiB of UTF-8 before sending, including group photos; return acknowledged photo saves without an unnecessary refetch.
- [x] Verify native Android image conversion and profile migration regressions (7 Android tests plus7 protocol tests).
- [x] Build fresh0.2.226, verify same signer/all8 native libraries/16KiB alignment.
- [x] Publish0.2.226 (sourcec1e8ff308, APK SHA256 b296dd0b6855e98de2ea6d3bc5967e315aacdb90a957d6979badc841771c64ff).
- [ ] Confirm the original remote StreamErrorException condition; the supplied archive retains only profile timeouts/disconnected-request classes.

- [x] Keep ordinary attachment downloads manual and choose a destination before download; preserve media automatic limits and native pack opening. Three API36 Robolectric checks pass for policy, picker/cancel and exact private-cache export.
- [x] Build/publish the fresh0.2.227/74449 APK froma6bccbc34: hostedSHA256 verified, established signer matches226, all8native libraries unchanged and ELF/ZIP16KiB checks pass. Three finalAPI36 Robolectric tests pass; no physical phone/provider interaction. https://github.com/doromiert/monogram/releases/tag/v0.2.227

- [x] Restore0.2.226 afatDebug beta delivery after accidental optimized release switch; retain app ID/signing key and debug features.0.2.230 passes62.6s emulator+phone, typed disco/roster/PubSub/MAM and libsignal JNI contract checks; phone chatting confirmed working. No identities/sessions were reset.

## Mixed-text link previews (2026-11-27)
- [x] Draw native webpage previews after rich article text and allow card taps.
- [x] Include rich layout height in transition-aware text measurement so cards sit below the body.
- [x] Pass26 article/preview tests and8 native emulator rendering/tap/non-overlap cases; previouspublishedAPK fails the missing-preview regression.
- [x] Build established-key afatDebug APK with betaID, verify update compatibility, and pass62.86seconds startup/crash monitoring (62PID samples, zero crashes).

APK delivery: `.private/State/monogram-mixed-preview/monogram-0.2.240-mixed-links-arm64.apk`, SHA256 `71d48f419e349d0a8ce5f9553becf3d7ee1eb5cacb8c33438a2d5db8a1fc2ace`. Two unchanged CMake JNI outputs reused after prior-tested hash verification; all eight required native libraries packaged. No physical phone was accessed.
