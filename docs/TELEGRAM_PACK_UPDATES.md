# Telegram pack update modes

An imported Telegram sticker or custom-emoji pack is a **connected, read-only**
local copy. Its artwork is usable offline. Telegram imports/updates use the
account's encrypted bot token and contact Telegram directly; no token or pack
manifest is sent to the XMPP server.

The native pack sheet's menu provides:

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

## Deferred validation before the next phone update

Do not run this during feature work. Immediately before packaging an update:

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
