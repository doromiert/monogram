// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import com.negzero.monogram.wallet.MonogramWallet;
import com.negzero.monogram.wallet.WalletAmounts;
import com.negzero.monogram.xmpp.MoneroClaim;
import com.negzero.monogram.xmpp.XmppMessage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.messenger.xmpp.MonogramXmr;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.MonogramWalletAuth;
import java.util.HashMap;
import java.util.UUID;

/** Funds a separate bearer wallet only after the original native fee-review sheet is confirmed. */
public final class FundedPaymentLinkFlow {
    private final BaseFragment host;
    private final int account, requestCode;
    private final MonogramWallet main;
    private final TLRPC.User recipient;
    private MonogramWallet claim;
    private MonogramWallet.Quote quote;
    private MonogramWalletAuth auth;
    private boolean preparing, committing, closed, leased;

    public FundedPaymentLinkFlow(BaseFragment host, int account, int requestCode, TLRPC.User recipient) throws Exception {
        this.host = host;
        this.account = account;
        this.requestCode = requestCode;
        this.recipient = recipient;
        main = GramWallet.getInstance(account).backend();
    }

    public boolean busy() { return preparing || committing; }
    public boolean committing() { return committing; }
    public void resume() { if (auth != null) auth.resume(); }
    public void pause() { if (auth != null) auth.pause(); }
    public boolean activityResult(int code, int result) { return auth != null && auth.activityResult(code, result); }

    public void prepare(long amount, Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        if (busy() || closed) return;
        try {
            if (recipient == null || recipient.id <= 0) throw new IllegalArgumentException("Choose an XMPP contact.");
            if (!main.readyToSend()) throw new IllegalStateException("Unlock and sync your wallet first.");
            if (MonogramController.get(account).encryption(recipient.id) == XmppMessage.Encryption.PLAIN) {
                throw new IllegalStateException("Enable encryption before sharing a funded payment link.");
            }
            if (amount <= 0 || amount > GramWallet.getInstance(account).getSpendableBalance()) throw new IllegalArgumentException("Enter an available XMR amount.");
            if (claim != null && !"unfunded".equals(claim.fundingStatus())) throw new IllegalStateException("This link has already been funded. Check its wallet history before sending again.");
            if (quote != null) { main.cancel(quote); quote = null; }
            releaseClaim();
            claim = MonogramWallet.getClaim(account, main.network, UUID.randomUUID().toString());
            claim.acquire();
            leased = true;
            preparing = true;
            auth = new MonogramWalletAuth(host, claim, requestCode);
            auth.resume();
            final long height = main.fundingHeight();
            final String value = WalletAmounts.coins(amount).toPlainString();
            auth.authenticate("Create payment link", "Protect a separate wallet for this payment", false, (approved, error) -> {
                if (closed) return;
                if (!approved) { preparing = false; done.run(null, error == null ? "PASSCODE_FAILED" : message(error)); return; }
                try {
                    claim.createClaim(claim.authorizeRestore(), value, height, main.node(), (unused, creationError) -> {
                        if (closed) return;
                        if (creationError != null) { preparing = false; done.run(null, message(creationError)); return; }
                        try {
                            main.prepareClaimFunding(claim, (prepared, feeError) -> {
                                preparing = false;
                                if (closed) { main.cancel(prepared); return; }
                                if (feeError != null) { done.run(null, message(feeError)); return; }
                                try {
                                    quote = prepared;
                                    TL_wallet.walletTransaction transaction = new TL_wallet.walletTransaction();
                                    transaction.id = transaction.tx_hash = prepared.hashes;
                                    transaction.amount = WalletAmounts.checkedAtomic(prepared.payment.atomicAmount);
                                    transaction.fee = WalletAmounts.checkedAtomic(prepared.fee);
                                    transaction.peer = GramWallet.createRecipientPeer(recipient, prepared.payment.address, null);
                                    transaction.preview = true;
                                    transaction.date = (int) (System.currentTimeMillis() / 1000);
                                    done.run(transaction, null);
                                } catch (Exception invalid) { main.cancel(prepared); quote = null; done.run(null, message(invalid)); }
                            });
                        } catch (Exception feeError) { preparing = false; done.run(null, message(feeError)); }
                    });
                } catch (Exception creationError) { preparing = false; done.run(null, message(creationError)); }
            });
        } catch (Exception error) { preparing = false; done.run(null, message(error)); }
    }

    public void commit(TL_wallet.walletTransaction transaction, Utilities.Callback<String> done,
            Utilities.Callback<TL_wallet.walletTransaction> queued) {
        if (closed || committing) return;
        try {
            if (quote == null || claim == null || !main.readyToSend()) throw new IllegalStateException("Payment review expired. Review the network fee again.");
            if (MonogramController.get(account).encryption(recipient.id) == XmppMessage.Encryption.PLAIN) throw new IllegalStateException("Encryption is unavailable. The payment link was not funded.");
            if (transaction.amount != WalletAmounts.checkedAtomic(quote.payment.atomicAmount)
                    || transaction.fee != WalletAmounts.checkedAtomic(quote.fee)) throw new IllegalStateException("Payment review changed. Review again.");
            committing = true;
            main.commit(quote, (result, error) -> {
                quote = null;
                if (error != null) { committing = false; done.run(message(error)); if (closed) releaseClaim(); return; }
                if (result == null || result.length < 2 || !"broadcast".equals(result[0])) {
                    committing = false;
                    done.run("Funding status is uncertain. Check this payment link before creating another.");
                    if (closed) releaseClaim();
                    return;
                }
                transaction.id = transaction.tx_hash = result[1];
                transaction.preview = false;
                transaction.pending = true;
                // Funding status/hashes were persisted by MonogramWallet before broadcast.
                // Keep the independent wallet leased until its bearer link has been materialized.
                try {
                    claim.claimLink((link, linkError) -> {
                        committing = false;
                        if (linkError != null) { done.run("The payment was funded. Reopen its link from wallet history."); if (closed) releaseClaim(); return; }
                        try (MoneroClaim secret = link) {
                            HashMap<String, String> params = new HashMap<>();
                            params.put(MonogramXmr.REQUEST_PARAM, claim.claimId);
                            params.put(MonogramXmr.PRIVATE_PARAM, "true");
                            SendMessagesHelper.SendMessageParams notice = SendMessagesHelper.SendMessageParams.of(
                                    org.telegram.messenger.xmpp.MonogramXmrMessage.claimBody(secret, transaction.comment),
                                    recipient.id, null, null, null, false, null, null, params, true, 0, 0, null, false);
                            notice.onLocalMessageCreated = message -> transaction.localMessageId = message.getId();
                            SendMessagesHelper.getInstance(account).sendMessage(notice);
                            if (queued != null) queued.run(transaction);
                            GramWallet.getInstance(account).invalidateState();
                            done.run(null);
                        } catch (Exception deliveryError) {
                            done.run("The payment was funded, but its link could not be queued. Reopen the existing link from wallet history.");
                        } finally { if (closed) releaseClaim(); }
                    });
                } catch (Exception linkError) {
                    committing = false;
                    done.run("The payment was funded. Reopen its link from wallet history.");
                    if (closed) releaseClaim();
                }
            });
        } catch (Exception error) { committing = false; done.run(message(error)); }
    }

    private void releaseClaim() {
        if (auth != null) { auth.destroy(); auth = null; }
        if (leased && claim != null) { claim.release(); leased = false; }
    }
    public void close() {
        closed = true;
        if (committing) return; // Journaled funding must finish; never race cancellation against broadcast.
        if (quote != null) { main.cancel(quote); quote = null; }
        releaseClaim();
    }
    private static String message(Exception error) { return error.getMessage() == null ? "Payment link operation failed." : error.getMessage(); }
}
