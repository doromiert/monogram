# Telegram pack update modes

An imported Telegram sticker or custom-emoji pack is a **connected, read-only**
local copy. Its artwork is usable offline. Telegram imports/updates use the
account's encrypted bot token and contact Telegram directly; no token or pack
manifest is sent to the XMPP server.

The native pack sheet's menu provides:

- **Export pack…** saves the complete local sticker/custom-emoji pack as an
  `.xmsp` archive through Android's system file picker. Connected packs can be
  exported without detaching them. Archive creation and saving run off the UI
  thread with immediate feedback; cancellation removes the temporary snapshot,
  and changing accounts stops a pending export. Artwork and the existing XMSP
  manifest are included, with no bot token or server publication.

- **Check for Telegram updates**: fetch the remote pack now, including additions
  and removals. Existing artwork identified by Telegram's `file_unique_id` is
  reused without downloading it again. Legacy imports without this identity
  download once during their first update.
- **Automatic Telegram updates**: opt in per pack. Off by default. While the
  Monogram process runs, an account-owned timer checks about every six hours;
  account activation also checks overdue packs. This does not wake Android or
  keep the process alive. Failures back off for six hours, and remain visible in
  the pack's update status. Manual updates can retry immediately.
- **Telegram update status**: show counts, byte progress when available, errors,
  and the last successful update time. Closing the view keeps the update running;
  Cancel update disconnects its active downloads. Sticker settings also show
  account-wide job progress and cancellation.
- **Detach from Telegram**: remove the source connection, disable automatic
  updates and keep the same local pack/artwork IDs. The resulting editable local
  pack can be renamed or modified. Detachment is deliberate and irreversible;
  importing the source link again creates a separate connected pack.

Updates commit only after every new file validates and downloads successfully.
A failure/cancellation preserves the previous manifest and cleans temporary
artwork. Detaching or removing a pack during a download prevents the pending
update from replacing it. Switching/logging out of the account cancels/rejects
stale jobs. A bot token is required for updates, but removing it never removes
already imported artwork.

Local pack positions and the order of retained stickers stay unchanged; new
stickers append in Telegram order. Existing sticker IDs, names and files are
reused. Removed/replaced sticker artwork is retained in hidden uninstalled local
history packs so old messages and favorites still resolve. An update does not
silently trust, publish, or upload private pack material.

## Validation

All twenty focused pack cases passed at the 0.2.119 release gate. Do not run
these during feature work; repeat immediately before packaging relevant updates:

```sh
nix develop --command ./gradlew --offline --no-daemon \
  :TMessagesProj:testDebugUnitTest \
  --tests org.telegram.messenger.xmpp.MonogramPacksTest
```

The focused new fixtures cover reused IDs/files, additions/removals, local order,
persistent auto-update metadata, old message/favorite document resolution,
account isolation and a detach racing with update commit. On the phone, import a
public pack, run Check for updates, inspect counts, cancel an update, then detach
and edit its name. Verify that an old received/sent sticker still displays.

## Native pack-export acceptance (0.2.198)

On the owned Android 15 / API 35 tablet fixture, the production application's
native local-pack menu opened **Export pack…**, Android DocumentsUI saved a
64×64 PNG sticker pack in Downloads as `Export acceptance.xmsp`, and the original
temporary export snapshot was removed. The archive's ZIP CRC, manifest version,
artwork length and SHA-256 matched. The actual desktop `packs::Library`
subsequently imported that SAF-written archive, validated and persisted its
metadata/artwork, and re-exported it. This used fictional local pack data and no
provider credentials or message transport.

The chooser uses `application/octet-stream`: `application/zip` made DocumentsUI
append `.zip` to the requested `.xmsp` filename. The content remains the existing
XMSP ZIP container. Source fix: `74e62533f`; the fresh original-signed arm64 APK is
`.state/monogram-0.2.198-arm64.apk`, version code 74159, SHA-256
`ad84996e37223f4eb895671b3aa5267fc7a82bc1c2145c8d387f2b4543a40e20`.
The tablet used a separate fixture-signed copy with identical application payload.
No real phone was updated by this acceptance run.

Evidence: `.state/export198-menu.png`, `export198-save-picker.png`,
`export198-final-picker.xml`, `export198-acceptance.xmsp`, and desktop
`.state/mobile-export-interop-result.log`. Process-death restoration during the
system picker and large-export progress/lifecycle acceptance remain open; this
normal save flow does not establish those behaviors.
