# Public profile photos

Mobile converts uploaded personal/group photos and accepted photo suggestions to
PNG on its worker queue. It retains transparency and aspect ratio, starts at no
more than 512 pixels per edge, and reduces dimensions until the PNG is at most
32 KiB. Local-only contact overrides keep their separate 1 MiB allowance.

The publication limit is deliberately separate from the 1 MiB incoming-avatar
limit. Base64 expands binary data by about one third. A formerly accepted 1 MiB
PNG could produce a request above 1.3 MiB and cause servers to close the stream
rather than return an IQ error. Prosody's default client stanza limit is 256 KiB;
64 KiB limits also exist in deployments. Outgoing vCard writes additionally check
the complete serialized UTF-8 request against a 60 KiB budget before sending.
Oversized cards produce a local instruction to reduce the photo/profile fields.

After acknowledged avatar writes, the client returns the known saved card and
retains the cached PEP nickname. A redundant profile fetch cannot make the upload
appear to fail after it has succeeded. Normal PEP/presence updates still refresh
other clients.

Verification covers native Android PNG encoding/resizing, transparency, removal,
local-photo independence, UTF-8 request sizes, group/personal writes, and keeping
the connection available after a rejected oversized request. The supplied
November 17 log archive records profile timeouts/disconnected requests but omits
the original StreamErrorException condition, so it cannot establish the precise
cause of that individual server disconnect. Physical-device acceptance remains
pending; no phone was controlled for this fix.

Reference: [Prosody client stanza limits](https://prosody.dev/doc/modules/mod_c2s).
