# Monogram Monero messages (private implementation draft)

Monogram uses ordinary XMPP message bodies and the conversation's existing encryption. No Telegram wallet account, TON service or new XMPP stanza type is involved. These are application conventions, not claims of a standardized XEP.

## Receiving addresses and requests

The local Monero SDK derives and durably reserves a fresh receiving subaddress for each request. Mainnet subaddresses begin with `8`; they belong to the restored wallet and are not independent disposable wallets or addresses with an automatic expiry.

Requests have the body:

```
XMR payment request
monero:<address>?tx_amount=<decimal XMR>&...
```

The URI parser validates network, checksum, supported fields, UTF-8 text and exact amounts. XMR has twelve atomic decimal places. No binary floating-point conversion is used for signing amounts. The protocol accepts the Monero uint64 atomic range; the copied native UI's signed-long model explicitly rejects larger amounts instead of wrapping or saturating.

The requester need not know the recipient's wallet address. Tapping the request supplies its destination and amount to the native send screen; payment still requires the user's review and confirmation. Normal clients can display/copy the URI without understanding Monogram cards.

## Transfer notices

```
XMR payment broadcast
monogram-xmr:transfer?v=1&payment=<percent-encoded canonical Monero URI>&txid=<hash>[,<hash>...]
```

A notice is queued only after an accepted local SDK broadcast. It is a sender's report, not proof that the recipient received funds. The recipient's own SDK scan/history supplies verification and spendable balance. Hashes are normalized hexadecimal, bounded in count and validated. Comments are carried in the chat/URI metadata, not published as a Monero blockchain memo.

Chat delivery and blockchain settlement are distinct. Retrying a failed chat notice must resend metadata only. It must never repeat the wallet transaction.

## Funded bearer links

Unknown-recipient sends create a separate wallet with its own mnemonic and a receiving subaddress. The main wallet funds that wallet only after exact fee review. The link format is:

```
XMR claim link — anyone with this link can claim the funds
monogram-xmr:claim?v=1&network=<MAINNET|STAGENET|TESTNET>&height=<restore height>&address=<independent wallet address>&amount=<decimal XMR>#<base64url mnemonic>

<optional chat note>
```

The fragment contains the independent claim wallet's 25-word English mnemonic, never the main wallet's recovery phrase or spend key. It is a bearer secret: anyone possessing it can attempt to spend that independent wallet's remaining funds. It must not be sent through plaintext fallback. The optional note is bounded to 960 UTF-8 bytes and remains outside the canonical bearer URI.

The sender retains the encrypted independent wallet to recover an undelivered link or reclaim unclaimed funds. It is not server escrow and does not guarantee that only one particular XMPP identity can claim it. The recipient moves the available funds into a fresh address of their own wallet, after scanning the independent wallet and reviewing the actual sweep amount and fee. Blockchain confirmation and the SDK's unlocked-balance rules determine availability; a chat card alone does not.

Creation/funding records are scoped to the local XMPP account and Monero network. Funding transaction hashes and an uncertain status are saved before broadcast. Accepted and uncertain broadcasts are distinguished; uncertain funding must be inspected before making another payment. Re-sharing uses the existing claim wallet rather than funding a new one.

## Presentation and opt-in

Monogram projects valid opted-in messages into native wallet cards locally. Original XMPP bodies remain intact. Invalid or unsupported messages remain ordinary text. Native rendering models are not transmitted as Telegram service messages. Receiving a URI does not authorize spending or importing it silently.

The feature remains behind the Monero Settings row until a wallet is connected. Public fiat endpoints receive currency-code queries only. Monero daemon requests use the configured node; wallet mnemonics and spend keys are not sent to the price provider or daemon.

This document describes the source implementation. APK/device verification is tracked separately in `docs/MONERO_NATIVE_WALLET_PORT.md`; it is not a completed security audit or a published interoperable standard.

### Human-readable payment notes

The native Send and Request amount screens expose an optional editable note (960 UTF-8 bytes in the composer). Ordinary requests encode it as `tx_description` in the standard `monero:` URI; direct transfer notices carry the same URI inside the existing transfer envelope. Funded claim links retain the existing separate, bounded note after a blank line, outside the canonical bearer URI. Receiving clients project these notes onto the native payment cards and review UI.

Descriptions preserve Unicode and line feeds via URI percent encoding. Recipient names still reject control characters; notes reject other control characters and bidirectional override/isolate controls. Notes never change the destination, atomic amount or fee and are not sent to the Monero node or included in the on-chain transaction. Chat encryption follows the conversation's existing policy. The old TON-specific public/encrypted-comment toggle is not exposed for XMR notes.

### Local wallet recovery format
New main wallets default to 16-word Polyseed using the bundled Monero SDK. This is local recovery material and is never published over XMPP. Existing legacy wallets retain their original mnemonic. Dedicated claim-link wallets continue using their existing seed format; this change does not alter claim-link interoperability.

### Claiming without Monogram
New claim messages retain the v1 bearer URI and append a deterministic compatibility section: a `monero-wallet:?seed=<plus-separated 25-word mnemonic>&height=<restore height>` URI on mainnet, followed by the independent wallet's readable Legacy recovery phrase, explicit network and restore height. This is Cake Wallet's documented universal wallet-restore format: https://docs.cakewallet.com/faq/glossary/uri-scheme . Wallets without a URI handler can restore the separate wallet manually, synchronize it, then transfer its spendable funds. Never import it over an existing personal wallet. Non-mainnet claims provide manual network-specific restore details rather than a misleading mainnet deeplink.
Monogram checks the appended section against the canonical bearer data before hiding it behind a card; altered sections remain plain messages. New claim bodies and the Copy claim details action include this fallback. Older messages remain readable; re-copying their retained claim adds the fallback. No hosted claim service, seed-bearing HTTPS URL, server escrow, or automatic payment is introduced. The extra restore URI and recovery words are equally sensitive bearer secrets and keep the existing encryption requirement.

### Cancellation and confirmation locks
Cancellation is a return sweep from the independently funded wallet. It cannot revoke a secret already shared or reverse the funding transaction. Unconfirmed/locked outputs cannot be swept. The prepare flow periodically checks spendability while its waiting UI is open; once unlocked it presents the actual return amount and fee for explicit confirmation. Waiting alone sends no transaction and does not reserve the money against a recipient sweep. Closing/stopping the flow cancels local checks. Either holder can win the spending race until the return transaction settles.
A recent device authentication is reused only if Android Keystore itself authorizes the second wallet key; otherwise system authentication is requested. Main and claim wallet keys remain separate.
