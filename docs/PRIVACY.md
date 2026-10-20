# Monogram privacy

Monogram is a Jabber/XMPP client. It uses your chosen XMPP provider for messaging. That provider's privacy policy and account terms apply to its service; Telegram's privacy policy does not describe your Monogram account.

## Accounts and messages

The app stores account configuration, encryption keys, trust decisions, conversation history and downloaded media on your device. Account credentials and optional service tokens use Monogram's local encrypted credential storage. Local history and downloaded media are app data; they are not a separately encrypted backup.

Your XMPP provider handles account authentication, routing, presence, group membership and supported services such as message archives and file uploads. Other servers may participate when you communicate across providers. Storage and retention depend on those providers.

When enabled and successfully established, OMEMO encrypts message content for the recipient devices. It does not hide all metadata, such as account addresses, traffic timing or group membership. Unencrypted conversations and unencrypted uploads do not have that content protection. Trust and device availability affect which devices can receive encrypted content.

## Optional services

- Telegram pack imports contact Telegram directly using the configured bot token. They do not turn your Jabber account into a Telegram account.
- GIF search contacts the configured GIF service. Translation may send the selected text to the chosen translation provider. Review the provider and settings before using either feature.
- Opening websites or embedded web content connects to those sites, which have their own policies.
- Sharing files, exporting packs or saving media to Gallery creates copies outside the original conversation or app storage. Those copies may be accessible to other apps or included in your device's backups.

## Diagnostics

Optional message XML capture and diagnostic logs can contain sensitive account, conversation or message information. Enable them only when needed and inspect any export before sharing it. Do not post passwords, tokens, private keys or unredacted conversation dumps in public issues.

You can manage permissions, app storage and uninstall Monogram through Android settings. Removing local app data does not delete copies held by your provider, other participants, exported files or device backups. Use your provider's account controls for server-side deletion requests.

For questions or corrections, see the [Monogram project](https://github.com/doromiert/monogram). GitHub issues are public; keep sensitive details out of them.
