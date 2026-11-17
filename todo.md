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
- [ ] Build/publish the fresh0.2.227 APK and verify signing/native compatibility.
