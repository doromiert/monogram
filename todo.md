# Mobile transfer follow-up

- [x] Retain upload phase, attempt count and sanitized failure stacks.
- [x] Add debug ADB testing prompts in Monogram and background notifications.
- [x] Rebuild motion photos without requiring a custom cover; preserve the original still and explicit cover choices.
- [ ] Recheck the upload service after its HTTP acknowledgement issue is repaired. Plain HTTPS HEAD requests to its root also stalled on phone and PC, independently of uploads or OMEMO. TCP/TLS connected promptly; HTTP response headers alternated between timeouts and fast HTTP 200 responses. Existing bounded retries and object-confirmation checks remain in place.

Do not treat the server acknowledgement issue as a fixed client bug. The phone
trace showed the upload body completing before the response timeout. The separate
motion-photo preparation failure happened before any upload body was sent.
