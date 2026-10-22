# Optional Monogram helper

`mod_monogram_schedule.lua` adds an authenticated scheduled-delivery IQ service to Prosody. It does not replace the XMPP server, and ordinary chats do not require it. It accepts chat message stanzas, including opaque OMEMO ciphertext. It has no client encryption keys.

To add it to an existing NixOS Prosody server:

```nix
{
  imports = [ monogram.nixosModules.monogramHelper ];
  # Configure services.prosody, its virtual hosts, certificates and firewall normally.
}
```

The exported module only adds the plugin path and module name. It does not start a new server, configure DNS, change firewall rules or supply certificates. The integration fixture in `scripts/test-xmpp.sh` demonstrates a complete isolated loopback configuration.

Namespace: `urn:monogram:schedule:0` (private experimental protocol).

- `set <schedule at='UNIX_SECONDS'> <message xmlns='jabber:client' type='chat' to='contact@example.org'>…</message> </schedule>` queues delivery. The reply contains `<scheduled id='JOB_ID' at='…'/>`.
- `get <scheduled/>` lists the caller's job IDs and delivery times.
- `set <cancel id='JOB_ID'/>` cancels the caller's queued job.

Requests use the account's own server/self IQ route. The authenticated account becomes the sender even if the submitted message claims a different sender. Jobs are limited to seven days ahead, 64 KiB per stanza, 100 per user and 10,000 per host. Storage failures return an IQ error. Jobs persist in the configured Prosody storage backend.

Delivery is at most once: queue removal is committed before routing. A crash between removal and routing can lose that scheduled message. Device keys may also change before delivery; pre-encrypted scheduled messages cannot automatically include devices added later.

This module currently requires installation on the account's own XMPP server. A separate helper for users on external XMPP servers, Android scheduling controls, restart tests and remaining Telegram-specific helper features are still pending. Nothing has been deployed to 192.168.1.24.
