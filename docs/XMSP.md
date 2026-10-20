# XMSP: portable XMPP Monogram Sticker Pack

Status: version 1 backend implemented; UI/transport integration is being coordinated. This
file describes an interchange format, not an existing XMPP standard. It supports
sticker packs and custom emoji packs without a Telegram account or bot token.

## Privacy and scope

An `.xmsp` archive is an **unencrypted snapshot**. Anyone who obtains the file can
read the artwork, names and emoji tags. A `private` boolean in JSON would not
provide encryption; version 1 deliberately has no such field.

For server-private sharing, encrypt the entire archive before upload using the
existing XEP-0454 media pipeline, and transmit its key-bearing reference only in
an authenticated OMEMO message. Never upload plaintext previews, human filenames,
names, manifest content or keys alongside the ciphertext. An opaque upload name
and `application/octet-stream` prevent artwork names from becoming upload
metadata. The server still observes traffic size, timing and participants.
Sharing through an unencrypted chat must not be described as private. Saving a
copy through the system file picker explicitly exports an unencrypted file.

Possession of a decrypted archive or its download key permits copying it.
Removing a recipient cannot revoke a copy or an old download key. New versions
must use fresh keys and explicitly selected recipients. Version 1 is a snapshot:
no subscription, automatic update, remote deletion or revocation is implied.
Importing never republishes artwork, metadata or keys to PEP.

## Container

Suggested media type: `application/vnd.monogram.xmsp+zip` (not registered).
Extension: `.xmsp`. The container is a single-disk, unencrypted ZIP with no
ZIP64 requirement. Every entry uses ZIP method 0 (STORED), with its size and CRC
validated against the central directory. Already-compressed media needs no further ZIP
compression; rejecting other methods also prevents ZIP decompression bombs.

The first entry is exactly `manifest.json`, UTF-8 JSON with no byte-order mark.
The remaining entries are exactly the assets referenced by the manifest. No
other files, directory entries, symlinks, paths, comments-as-content or nested
archives are meaningful. Importers must not extract ZIP paths directly to disk.
Use generated opaque destination names after validating exact archive names.

Version 1 reader bounds:

- At least one item and one asset, plus one manifest entry; no fixed item-count limit. Byte-size and integrity limits below still apply.
- Manifest at most 4 MiB; each asset 1–20 MiB; all asset bytes at most 100 MiB.
- Entire container at most 105 MiB, including ZIP headers and central directory.
- Asset dimensions 1–512 on each axis; media types below are the only supported
  payloads. A reader must validate media independently of file extension/JSON.
- Reject duplicate entry names, duplicate item IDs, unreferenced assets, missing
  assets, mismatched declared/actual sizes, malformed hashes and hash mismatch.
- Repeated references to the same asset are permitted; item IDs remain unique.
- Limits apply to bytes actually read, even when ZIP/JSON size declarations lie.

## Manifest

All listed fields are required. Other fields may be ignored, but unknown format
or version values must be rejected before reading assets. There are no external
asset URLs, credentials, source-account identifiers or native Telegram IDs.

```json
{
  "format": "xmsp",
  "version": 1,
  "name": "Friends",
  "kind": "stickers",
  "items": [
    {
      "id": "5cbba4da-8fa8-4b41-9133-1fb5069fbb2a",
      "name": "Happy wave",
      "emoji": "👋",
      "mime": "image/png",
      "width": 512,
      "height": 512,
      "size": 12345,
      "sha256": "<64 lowercase hexadecimal characters>",
      "path": "assets/<the same sha256>.png"
    }
  ]
}
```

`kind` is exactly `stickers` or `emoji`. Array order is display order. `id` is a
canonical lowercase UUID identifying an item within this snapshot; importing
allocates fresh account-local native IDs. It is not an update or ownership key.
`name` and each item name are human-editable labels separate from `emoji` tags.
Names contain 1–100 UTF-16 code units after trimming, without ISO control
characters. Emoji tags contain 1–64 UTF-16 code units without ISO controls.
These bounds match the existing native pack store.

`size` is an integer byte length. `sha256` hashes the complete asset bytes.
`path` is exactly `assets/` + lowercase hash + the extension for its MIME:

| MIME | Extension | Content |
| --- | --- | --- |
| `image/gif` | `.gif` | GIF animation (original frames retained) |
| `image/png` | `.png` | PNG image |
| `image/webp` | `.webp` | WebP image |
| `application/x-tgsticker` | `.tgs` | Gzip-compressed Lottie sticker |
| `video/webm` | `.webm` | WebM video sticker |

For GIF assets, `width` and `height` describe the scaled display size (at most
512 on either axis); the original animation bytes and source resolution are
retained. GIF source dimensions are validated separately before decoding.

TGS is the sole gzip payload: decompressed JSON is capped at 2 MiB and must
satisfy the existing sticker animation validator (512×512 canvas, positive rate
at most 60 fps, duration at most three seconds, layers present). It is never
expanded as an archive. Image/video decoders remain responsible for their media
validation; ZIP and hash validation alone do not make a codec safe.

## Commit and cancellation

Validate manifest, names, IDs, media, sizes and hashes into account-private
staging files. Do not modify installed packs until every asset is validated.
Cancellation, malformed input, account replacement, logout or storage failure
must remove staging files and leave the old manifest/packs intact. Commit with
the existing atomic pack manifest writer and allocate fresh collision-checked
native IDs. An invalid existing manifest must not be overwritten. Never restore
native cache paths or IDs supplied by an archive.

## XMPP standards and future synchronization

[XEP-0449](https://xmpp.org/extensions/xep-0449.html) defines sticker markers and
public pack metadata on PubSub, with standard `xmpp:...?pubsub;...` references.
A public publish action can implement that interoperable route separately. Its
recommended open-access and re-publication behavior is inappropriate for
private artwork. A private import must not silently follow that behavior.

[XEP-0060](https://xmpp.org/extensions/xep-0060.html) whitelist nodes restrict
which XMPP clients retrieve items. That is access control enforced by the
server, not encryption against its administrator. A future private auto-sync
protocol needs encrypted manifests/assets, authenticated version/owner binding,
rollback protection, recipient key distribution, key rotation and clear
revocation limits. A plain whitelist or a long unguessable URL is insufficient.

[XEP-0363](https://xmpp.org/extensions/xep-0363.html) provides upload slots; its
security section explicitly calls for end-to-end encryption for private files.
[XEP-0454](https://xmpp.org/extensions/xep-0454.html) supplies encrypted-media
references, while [XEP-0384](https://xmpp.org/extensions/xep-0384.html) protects
their message transport. Monogram already has bounded streaming AES-256-GCM
upload/download, authenticated finalization, opaque upload names and OMEMO
transport; this is the selected reuse path for archive snapshots.

## Local policy and Telegram provenance

Local installed-pack metadata is distinct from the portable archive. The pack
store adds `privatePack` (default true) and `connected` (default false).
Existing records missing either field take those defaults. No background
network request is enabled by upgrading or importing an archive.

A connected Telegram pack retains its existing Telegram source link and follows
that source; adding local artwork is disallowed. Detaching creates an editable
local pack without a synchronization subscription. Connected/manual resync is
not implemented by this archive change. Before offering it, the resync backend
must retain native IDs for matching upstream items and retain files referenced
by messages/favorites; replacing every native ID would break those references.

An imported `.xmsp` snapshot is detached and private by default, even if its
sender previously used it publicly. No archive-supplied field can silently
publish the recipient's files or enable a Telegram connection. Public publishing
requires a separate explicit action with an explanation that artwork and names
become server-readable. A private setting requires encryption at every sharing
path; it cannot retroactively make earlier public copies private.
