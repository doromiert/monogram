# Monero scan comparison — 2026-10-23

Cake source inspected at commit `499b6f4468d6002b823dcecaa8613223868e4b90`. This is source-level diagnosis, not a controlled throughput benchmark.

- Cake saves every 30 seconds (`cw_monero/lib/monero_wallet.dart`, `_autoSaveInterval` and `_autoSaveTimer`), and passes the requested restore height into SDK restoration (`monero_wallet_service.dart`).
- Monogram’s repair scan explicitly resets refresh height to zero. It currently stores the native wallet after successful refresh and orderly close, with no periodic scan checkpoint. Interrupting a repair can lose work, and restarting repair resets to zero again.
- The earlier Cake screenshot showed a known balance and only 43,758 blocks remaining. Immediately before installing xmr.11, Monogram showed block 599,401 against node height 3,790,506 (roughly 3.19 million remaining). These are very different scan workloads.
- Monogram’s HTTPS bridge opens a new TCP/TLS connection for each RPC and requests `Connection: close`. This adds handshake latency; its share of elapsed scan time has not been measured. Certificate-chain and hostname verification must remain enabled in any optimization.
- The pinned native engine already requests pruned batched blocks and can fast-skip history before a configured restore height. It does not fetch one block per RPC.

Priorities: resumable repair state and safely synchronized periodic native checkpoints; explicit restore-height/date support without silently skipping funds; persistent verified HTTPS transport. None of these performance changes is included in xmr.11 or the xmr.12 sheet fix.

Primary Cake source: https://github.com/cake-tech/cake_wallet/blob/499b6f4468d6002b823dcecaa8613223868e4b90/cw_monero/lib/monero_wallet.dart and corresponding `monero_wallet_service.dart`.

## Implemented in private xmr.15

The points above describe the earlier builds. xmr.15 now reuses authenticated/verified upstream TLS connections, saves partial native scan progress at approximately 30-second block boundaries, and resumes a repaired cache after its first durable checkpoint. Native progress callbacks are throttled rather than crossing JNI for every block. Unlock and scan are separate actions; the native card, amount entry surfaces and foreground notification expose progress instead of hiding it behind a spinner. The settings row uses a monochrome wallet glyph.

Checkpoint implementation deliberately differs from Cake's concurrent timer: pinned `WalletImpl::store()` calls wallet2 serialization without acquiring the refresh mutex. Monogram therefore requests stop from a native block callback, waits for the synchronous refresh to return, saves on its serialized actor and continues incremental refresh. No disk write occurs inside the callback or concurrently with refresh. An interrupted/erroring repair is never declared fully synchronized. Restore-height optimization remains explicit; automatically choosing a recent birthday could miss funds.

Transport delivery fixture confirms four sequential binary RPCs used one verified TLS connection (including chunked responses), credentials stayed local, and untrusted certificates/wrong hostnames failed. It does not measure real public-node scan throughput. Actual wallet scan progress and durable resume still need live verification after unlock.

Exact pinned SDK source inspected: https://codeberg.org/Monfluo/monero/src/commit/9c2e01c6599d11b6283cb4789b943eaa6c854e97/src/wallet/api/wallet.cpp (`store`, `doRefresh`, `startRefresh`) and `src/wallet/wallet2.cpp` (`refresh`).

xmr.16 live verification: fixed the SDK's separate `connected()` initialization before reading daemon height; `connectToDaemon()` alone left getters at zero. Native fixture now obtains Cake height in 2.14s. Phone screenshots/user confirm advancing remaining-block progress (76,362/5% to 67,378/16%); partial repair metadata and native cache are saved. Full scan completion and controlled relative throughput remain unverified.
