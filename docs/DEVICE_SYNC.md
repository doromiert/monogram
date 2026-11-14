# Device sync

Enable **Preferences → Devices → Manage Paired Devices**. Android exposes
**Settings → Device sync**, including from the sign-in screen. Connect devices
to the same LAN, show a pairing QR on one, and scan it on the other. Desktop can
scan an image containing a QR or paste its pairing URI.

For camera-free pairing, choose **Show rotating pairing code**, then choose
**Pair with an eight-digit code** on the other device, select the nearby device,
and enter its code. The SHA-256 TOTP changes every 30 seconds. A code screen
expires after two minutes; five attempts exhaust that invitation. Clocks may
differ by one time step. This is a pairing code, not an authenticator seed.

Every device can serve and initiate sync. A phone–computer–computer or
phone–phone chain forwards the same encrypted records without an account server.
Discovery requires LAN multicast; QR/pasted-URI pairing also works when discovery
is filtered, provided direct LAN TCP connections are allowed.

## Portable data

* XMPP JIDs, host/port, TLS mode and saved passwords. The receiving desktop saves
  passwords in Secret Service; Android rewraps them with its own Keystore.
* Sticker and custom emoji packs, artwork, names, item tags, installed/source
  policy, stable pack/item UUIDs. Local numeric IDs remain stable across updates.
  Ordinary manual XMSP exports do not share sync identities or connection policy.
* Folder rules, order, explicit chat membership, pinned membership, icons and
  All-folder visibility. JIDs, rather than platform-local dialog IDs, identify
  chats. Folder names must be unique. Folder changes form one atomic record.
* Named Monero wallet recovery capsules: encrypted `.keys` data, the password
  needed to open it, public address/network/name, node, and receiving-address
  high-water mark. Android validates the recovered primary address with the real
  wallet engine and wraps the password with the destination's authenticated
  Keystore. A new restoration starts with a full rescan; existing native caches,
  transactions, claim-wallet approvals and payment authorizations stay local.
  Desktop securely receives and relays capsules and lists their public metadata;
  its existing wallet UI still has no production spending/recovery engine.
* Public Legacy/modern/PQ OMEMO device fingerprints. These are verification
  references, never private identities, prekeys, live ratchets or automatic
  trust decisions. Each client continues using its own OMEMO device.
* GIPHY, Telegram-pack bot, translation and Monero-node API credentials. Provider
  configuration outside these named stores, KeePass associations, server
  certificate exceptions and operating-system credentials are not transferred.

Local changes publish automatically while sync runs. Deterministic last-writer
wins versions converge through any paired path. Fresh native defaults do not
replace an import already pending. Pack UUIDs avoid duplicates when a pack moves
through several platforms. Removing an account excludes it on that device and
purges its sync records, preventing automatic sign-in resurrection. Signing in
manually allows that account again. Pack/folder removal is not a remote wipe;
folder membership/order changes are synchronized as the complete folder record.

Pairings are direct connections. Removing one link stops transfers along that
link; other paired paths remain authorized, and previously received data stays
on each device. Stop device sync to stop all of that device's transfers.

## Protection and lifecycle

TLS 1.3 authenticates the certificate pin conveyed by the QR. Typed codes use
SPAKE2 with explicit mutual key confirmation bound to both identities, certificate
pins, names, endpoint and PAKE messages; multicast advertisements do not authorize
pairing. No account password or API/wallet credential is sent before authentication.

The private record index, certificate key, peer tokens and object chunks use
AES-256-GCM at rest, with random nonces and object/chunk metadata as associated
data. A platform-protected random master key unlocks that store. Android requires
a device screen lock, pauses networking on screen-off, and requires valid system
authentication to resume access to its sync/wallet keys. Its foreground notification
keeps LAN work visible. Desktop uses the system keyring.

All network, archive and wallet work runs on background workers. Buttons show
waiting feedback immediately. Transfers are bounded to 128 MiB per record,
4,096 records and 8 GiB indexed data; XMSP has its stricter existing limits.
Imports are staged and authenticated before replacement. Interrupted transfers
retry later. Restart removes abandoned plaintext export stages from private storage.

See the [local protocol proposal](xeps/monogram-lan-sync.md), which is not an
assigned or accepted XMPP extension. Remaining platform gaps are tracked in
[the desktop parity ledger](../../desktop/docs/FEATURE_PARITY.md).
