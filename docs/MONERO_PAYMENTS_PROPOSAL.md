# Optional Monero payments research and proposed design

Status: **proposal only; not implemented**. Researched 2026-10-23. No wallet was
created, no funds were handled, and no app code, build or phone state was changed.
The user requested research before implementation and an unobtrusive opt-in feature.

## Firm visibility requirement

Disabled by default, independently for each XMPP account. A plain **Settings →
Monero** row is the only feature UI visible before enabling it.

While disabled: no wallet/home/navigation tab, balance pill, onboarding banner,
upsell, payment attachment action, contact-profile wallet row, rich payment card,
payment notification campaign, price lookup, wallet discovery, payment-node fetch,
wallet SDK initialization or background scanning/polling. Do not advertise a Monero
capability or subscribe to payment metadata just because the app started.
Incoming payment messages remain ordinary readable messages. Receiving a URI never
automatically opens an app or enables the feature. Ordinary message content is not
censored or replaced with an enable-feature advertisement.

After explicit enablement, expose payment actions only where useful: the enabled
account's contact/chat menu and the nested Monero screen. Keep the main home view
and navigation unchanged. Disabling returns payment cards to readable text and
stops Monogram-owned payment jobs/subscriptions. It does not delete message history
or pretend to revoke previously shared addresses. It does not stop another wallet app.

## Research findings

- Mainnet Monero subaddresses are the familiar `8…` receiving addresses. They are
  preferable to publishing a primary address, but repeatedly publishing one address
  beside a JID still creates an off-chain identity association. Fresh receiving
  subaddresses per contact/request are preferable where the wallet can provide them.
  See [Monero subaddresses](https://docs.getmonero.org/public-address/subaddress/).
- Standard `monero:` links support a destination and payment amount. A transaction
  hash alone does not prove the destination or amount. The wallet's proof/signature
  APIs support stronger verification. See [wallet RPC](https://docs.getmonero.org/rpc-library/wallet-rpc/)
  and [payment proofs](https://www.getmonero.org/resources/user-guides/prove-payment.html).
- [XEP-0518](https://xmpp.org/extensions/xep-0518.html) is an experimental,
  payment-system-neutral invoice/proof framework, mainly for paying for XMPP
  services. Its payment URI design is relevant; it does not define a complete
  Monero-to-JID binding, external-wallet callback or peer-to-peer wallet integration.
- [Radar](https://radar.chat/support) combines messaging with an actual built-in
  Bitcoin wallet. Its [Android SDK adapter](https://github.com/radar-labs/radar-android/blob/dev/app/src/main/java/org/thoughtcrime/securesms/payments/BreezSdkWrapper.kt)
  uses Breez/Spark. It is a UX/architecture reference, not an XMR backend to relabel.
  Radar is from the Cake Wallet team; no performance comparison was run here.
- Monfluo's current [manifest](https://codeberg.org/Monfluo/monfluo-android/src/branch/master/app/src/main/AndroidManifest.xml)
  registers an Android `monero:` VIEW handler. Its [send activity](https://codeberg.org/Monfluo/monfluo-android/src/branch/master/app/src/main/kotlin/org/monfluo/wallet/activity/ActivitySend.kt)
  imports the URI into its send form, and its [URI parser](https://codeberg.org/Monfluo/monfluo-android/src/branch/master/app/src/main/kotlin/org/monfluo/wallet/util/UriPayment.kt)
  recognizes `tx_amount`, `recipient_name` and `tx_description`. These sources do
  not establish a generic success/transaction-proof callback contract. This was
  source inspection, not a device compatibility test. Its multi-recipient syntax
  must not be emitted as though every Monero wallet supports it.
- Monero supports both [Tor and I2P](https://docs.getmonero.org/running-node/monerod-tori2p/).
  The claim that Tor must never be used is too broad. In the proposed first phase,
  network routing remains the wallet's responsibility; XMPP privacy and wallet
  network privacy are separate concerns.

## First implementation to propose

Use an **external wallet** for keys, confirmation, fees and broadcasting. Monogram
stores receiving addresses and request records, not seeds, private spend keys or
private view keys. This provides an integrated chat entry point but still switches
to the wallet for the final payment confirmation.

| Action | Initial behavior | Limit |
| --- | --- | --- |
| Link wallet | Paste/scan a validated receiving subaddress for this XMPP account | An address is a destination, not a connection to the wallet's balance |
| Share receiving details | Explicitly send a payment link/address in an encrypted DM | No automatic public-profile publication |
| Request XMR | Choose an exact XMR amount and optional note; share a standard URI/QR | No automatic verification just because the URI was sent |
| Send XMR | Resolve an explicitly shared contact address; review it; open the selected wallet | Wallet approval is required; returning to Monogram is not proof of payment |
| Track payment | Initially show link shared / payment reported / manually marked received | No invented confirmed/settled state from an activity result or bare txid |

The first provider contract is URI handoff and copy/QR fallback. Optional provider
capabilities can later add receiving-address generation, payment proofs and receipt
verification. URI-only handoff cannot generate fresh subaddresses: the wallet must
supply them. Capability absence must have a clear fallback; do not assume every
wallet implements the same Android extras or callbacks. Feather and Monero GUI are
desktop references; Android handoff must be tested with actual Android wallet apps.

Receiving-address setup should prefer subaddresses. Sending must remain compatible
with valid ordinary destination types supported by the chosen wallet. Validate the
network, Monero block-base58 encoding and checksum; checking the first character
or using Bitcoin's Base58Check algorithm is insufficient. No funds should be sent
to a wrong-network address, even if it looks superficially plausible.

Use integer atomic units internally and exact decimal conversion at the boundary
(12 fractional XMR digits). Do not use floating point or inherit TON's unit/fee
assumptions. Generate a single canonical URI, for example:

```text
monero:<validated-subaddress>?tx_amount=0.1
```

This is a non-payable placeholder example. Optional text must be UTF-8 escaped.
The amount shown by Monogram must be parsed from that same URI. Reject ambiguous
or duplicate amount/destination fields and unsupported multi-recipient forms;
never display one destination while handing a wallet a different or larger set.
Fiat estimates, if eventually offered, are optional and explicitly estimates.
The external wallet supplies the actual fee and final amount confirmation.

## Linking an address to an XMPP user

Bind address records to the authenticated **bare JID**, not a display name,
nickname, local Telegram peer ID or a string that merely resembles an OpenAlias.
Pin the address for an already-open request; later profile changes must not silently
redirect a pending payment. Show address changes rather than silently replacing
previously accepted receiving details.

The default sharing model is per-contact encrypted messages. A dedicated public
tipping address may be an explicit later option using access-controlled PEP
(XEP-0163/XEP-0060), with a documented Monogram schema if no adopted binding format
fits. A shared subaddress does not reveal the primary address, but it still links
that published address to the XMPP identity. Do not describe public PEP as private.
Do not fetch or expose payment profile rows for viewers who have the feature off.

There are two distinct assurances:

1. **Address supplied by this XMPP account:** existing authenticated messaging and
   accepted device trust identify the publisher. This is not proof of wallet control.
2. **Wallet-control proof:** an optional future wallet-generated spend-key message
   signature can bind a canonical challenge containing the normalized JID, network,
   address, timestamp and nonce. Verification must distinguish spend signatures
   from view-key signatures. Creating and verifying this proof needs wallet support;
   it must not require importing the private key into Monogram.

Linking a wallet does not replace XMPP registration or SASL authentication. Wallet-
signature login would require a separate server-side authentication design. No new
payment-vendor account should be required for sending a normal Monero transaction.

## Requests, compatibility and receipts

Reuse standard Monero payment URIs as the compatibility foundation. Evaluate
XEP-0518 invoice/session/proof elements before defining a new request envelope;
its service-charge flow is not automatically a specification for person-to-person
payment chat bubbles. Any extension must be documented as a draft profile, with
capabilities and exact fallback grammar, not advertised as an existing Monero XEP.

Keep the URI/destination/amount consistent between the visible fallback and any
structured metadata. A request record needs a unique ID, issuer/payee JID,
validated URI/network, optional note and expiry. Monetary edits create a new
request identity; cancellation must not rewrite an existing payment or proof.
Expiry is an application rule, not an on-chain refund or a guarantee that a late
transaction cannot arrive.

For OMEMO2, private structured data can live inside authenticated protected content.
For legacy OMEMO, only the body is protected: use a readable canonical request or
simple URI-only handoff and do not trust outer XML as financial instructions. Basic
links work in either mode. Fully tracked/provable requests need a specified,
authenticated binding; v1 must not pretend that unprotected metadata provides it.
Unlike file sharing, a request is ordinary message content, not an `aesgcm` media
body. Preserve a useful non-Monogram fallback and do not auto-open a wallet when a
message arrives. Start with DMs; group requests need explicit payee identity rules.

A subsequent proof flow should bind the proof's message/challenge to the canonical
request digest. Check the expected address, amount, network and replay identity,
then obtain independent transaction/confirmation information. A proof alone is not
necessarily evidence of funds currently available to spend; the recipient wallet's
receipt and unlock state matter. Distinguish reported, proof-verified, pending,
confirmed and available states. Do not reuse one proof to satisfy multiple requests.

Do not send keys or proof data to a public explorer as the default verification path.
A trusted wallet adapter/local verification path is the proposed direction. An
optional view-only connection is separate work: a private view key reveals wallet
activity and does not alone provide a fully accurate spent balance. It is not a
necessary permission for the initial feature.

## Latest Telegram source and reuse boundary

Official Android source was fetched separately with a sparse checkout:

- Directory: `/home/doromiert/Projects/telegram-reference-latest`
- Upstream: `https://github.com/DrKLO/Telegram`
- Commit: `0aae959806c57be8ad457980f2a5c6277ff18635`
- Version: **13.0.3 (7183)**, upstream commit dated 2026-10-20
- Scope fetched: native Wallet/TON Java UI, wallet TL definitions, small JNI/Rust
  adapter sources and root metadata/license; no native binaries/submodules/build.

The current upstream really does include native wallet UI. This proposal refers to
that code, not an assumed copy of a third-party Wallet mini-app.

| Source | Potential reuse | Required adaptation |
| --- | --- | --- |
| `ui/Wallet/WalletSendEnterAmount.java` | Amount-entry glyph animation, keypad and review layout | Extract view code from GramWallet; exact XMR precision and wallet handoff semantics |
| `ui/Wallet/WalletActivity.java` | Receive/QR and history presentation patterns | Enter only through enabled settings/actions; no wallet homepage or balance affordance for non-users |
| `ui/Wallet/TransactionCell.java` and `WalletTransactionAmountView.java` | Request/receipt layout and amount typography | Monero states, confirmations and verifiable evidence; no Telegram wallet transaction objects |
| `ui/Wallet/WalletChatSendTransition.java` and `WalletSendTransition.java` | Optional action-completion motion | Match the event actually known; opening a wallet must never animate a completed transfer |

Use small view components under Monogram/XMR-specific names and backend interfaces.
Do not restore `GramWallet`, TON Connect, Telegram wallet APIs, exchange/signup flows,
Stars/billing dependencies, the old wallet engine or global wallet navigation.
The current APK audit intentionally excludes those class families. Preserve upstream
notices when copying code. Radar is a behavioral reference; copying its AGPL code
requires a separate licensing review. No latest-source APK or visual comparison was
run for this research, so this is not a pixel/motion parity claim.

## Implementation order and acceptance

1. Confirm this opt-in/external-wallet boundary, then specify and document the
   address/request format and actual supported wallet capabilities.
2. Implement settings gating, strict address/URI handling, copy/QR and manual wallet
   handoff. Add dormant view backports only after checking their reference behavior.
3. Add authenticated contact bindings/request cards and optional proof/provider work
   separately; do not label URI-only handoff as automatic payment verification.

At delivery, verify disabled-state surfaces and zero wallet activity, URI amount/
network/address validation, Android wallet cold/warm start, cancel/no-wallet cases,
identity/address changes, privacy boundaries, archive/replay and feature disablement.
Use testnet/stagenet and dedicated fixtures for payment testing, never real contacts
or user funds. Follow the existing project rule: tests/builds at APK delivery rather
than repeatedly during implementation. No implementation or tests were run here.
