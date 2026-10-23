# Bundled public Monero nodes

Checked 2026-10-23. These are optional Mainnet presets, not automatic connections,
operator endorsements, guarantees of availability or trusted-daemon flags. The
wallet keeps every remote daemon untrusted and verifies TLS chain and hostname.
Selecting a preset only stores its URL; Sync wallet starts the connection.
Custom HTTPS URLs remain available on all networks. Mainnet presets are never
shown for Testnet or Stagenet. Existing node selections are preserved.

| Operator | HTTPS endpoint | Operator source |
| --- | --- | --- |
| Cake Wallet | https://xmr-node.cakewallet.com:18081 | [Cake Wallet node catalogue](https://github.com/cake-tech/cake_wallet/blob/main/assets/node_list.yml), marked default and SSL |
| Seth For Privacy | https://node.sethforprivacy.com | [Operator's mainnet node documentation](https://sethforprivacy.com/about/#mainnet) |
| Monero.one Europe | https://eu.node.monero.one | [Operator's node documentation](https://monero.one/blog/monero-remote-node-how-to-connect) |
| Monero.one United States | https://us.node.monero.one | [Operator's node documentation](https://monero.one/blog/monero-remote-node-how-to-connect) |

All four returned `get_info` status OK, nettype mainnet and matching height 3790450
through verified TLS during this check. This is a point-in-time public RPC check,
not a funded-wallet test. Evidence: `.state/xmr-public-node-checks.json`.
HashVault on port 18081 and the tested StormyCloud/Unredacted endpoints presented
self-signed certificates, so they are not bundled and TLS verification is not
weakened to include them. Cake's onion endpoint is not listed because this wallet
candidate does not yet implement Tor transport.
