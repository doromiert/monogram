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
