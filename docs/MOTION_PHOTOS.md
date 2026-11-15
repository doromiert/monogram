# Motion photos over XMPP

A motion photo is one attachment containing the full-resolution still and its video, rather than an MP4 with a low-resolution video-frame cover. Android sends a JPEG Motion Photo using the existing XEP-0363 upload, XEP-0447 stateless file sharing/XEP-0446 metadata, and XEP-0454 encrypted-file paths. No Monogram-specific XML namespace or second attachment is needed.

The container follows [Android Motion Photo format 1.0](https://developer.android.com/media/platform/motion-photo-format): JPEG first, Camera/Container XMP, and the MP4 clip last. An unedited JPEG source is uploaded byte-for-byte. If the clip is edited, the existing motion-photo writer combines the full still with the edited clip. A non-JPEG source cover is decoded at its original dimensions and written as JPEG on the file worker, with a 32 MiB input / 40 MP / 12000-pixel edge limit. It is never silently downscaled. This conversion does not preserve HDR or all original metadata; the original draft remains available if preparation fails. Direct HEIC/AVIF motion-container reception is not implemented in either client.

Both the still and clip are covered by one authenticated encrypted transfer and file hash. Fresh upload-slot retries reuse the immutable encrypted wire object. Parsing/extraction runs only after verified download, or verified cache metadata enrichment; malformed metadata is displayed as a normal still rather than launching the video decoder. JPEG metadata scanning is bounded to 2 MiB / 256 segments; the MP4 must have a bounded valid `ftyp` header and a final clip of at most 512 MiB. The existing Media3 parser also recognizes legacy Google MicroVideo metadata.

On Android, the persisted native media is a `TL_messageMediaPhoto` with `live_photo` set, the full still as `photo`, and the extracted video as `document`. The original container remains the source used for forwarding and transfer integrity. The extracted playback path is restored after native TL deserialization, and missing derived clips are rebuilt on the file worker. The selection draft also preserves the live-photo flag, original source, offsets, timestamp and the user's motion toggle.

Chat cells show the still without autoplay. Clicking opens the existing live-photo viewer, which plays the clip once and fades back to the original still. Native gallery export recombines the original still and clip without appending the old clip twice.

## Verification

`MonogramMotionPhotoTest` uses the production writer and a synthetic 3200×2400 still with the desktop's small MP4 fixture. It checks full still dimensions, exact extracted video bytes, native live-photo projection through database close/reopen, derived-cache repair, malformed offsets and truncated MP4 rejection, original retry snapshots, edited rebuilding, cancellation and full-resolution non-JPEG cover conversion. `MonogramMediaDraftsTest.motionPhotoFlagAndOriginalSourceSurviveDraftReopen` covers selection persistence.

`MotionPhotoTransferTest` exercises the actual full JPEG+MP4 through production encrypted upload, a rejected single-use slot, fresh-slot retry, authenticated download, and a corrupted authentication tag without replacing the verified cache. Existing attachment cache/commit regressions run alongside it. The Android writer's `tests/fixtures/motion-photo/fullMP.jpg` is also accepted by the desktop parser and native preview/player fixture.

Physical Android playback and a real cross-device message have not been tested for this update: the phone's previous wireless ADB endpoint is unreachable. No messages were sent to real contacts during these checks.
