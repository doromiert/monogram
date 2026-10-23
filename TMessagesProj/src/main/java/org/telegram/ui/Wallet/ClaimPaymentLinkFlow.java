// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import com.negzero.monogram.wallet.MonogramWallet;
import com.negzero.monogram.wallet.WalletAmounts;
import com.negzero.monogram.xmpp.MoneroClaim;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.MonogramWalletAuth;

/** A bearer link is scanned and swept into a fresh address of the user's own wallet. */
public final class ClaimPaymentLinkFlow {
    /** Choose the destination before opening a claim scoped to that local wallet. */
    public static void open(BaseFragment host,int account,String uri,java.util.function.Consumer<ClaimPaymentLinkFlow> opened) {
        try {
            MonogramWallet active=GramWallet.getInstance(account).backend();
            java.util.ArrayList<MonogramWallet> wallets=new java.util.ArrayList<>();
            for(MonogramWallet wallet:active.wallets())if(wallet.exists())wallets.add(wallet);
            if(wallets.isEmpty())throw new IllegalStateException("Create or restore a wallet first.");
            java.util.function.Consumer<MonogramWallet> choose=wallet->{
                try {
                    MonogramWallet current=GramWallet.getInstance(account).backend();
                    if(!current.walletId.equals(wallet.walletId)){current.select(wallet.walletId);GramWallet.cleanup(account);}
                    ClaimPaymentLinkFlow flow=new ClaimPaymentLinkFlow(host,account,uri);
                    opened.accept(flow);flow.show();
                }catch(Exception error){BulletinFactory.of(host).showForError(message(error));}
            };
            if(wallets.size()==1){choose.accept(wallets.get(0));return;}
            CharSequence[] names=new CharSequence[wallets.size()];
            for(int i=0;i<wallets.size();i++)names[i]=wallets.get(i).name()+(wallets.get(i).walletId.equals(active.walletId) ? " · selected" : "");
            new AlertDialog.Builder(host.getContext(),host.getResourceProvider()).setTitle("Deposit to which wallet?")
                .setItems(names,(dialog,index)->choose.accept(wallets.get(index))).setNegativeButton("Cancel",null).show();
        }catch(Exception error){BulletinFactory.of(host).showForError(message(error));}
    }
    private final BaseFragment host;
    private final int account;
    private final MonogramWallet main, claim;
    private final MoneroClaim link;
    private final MonogramWalletAuth mainAuth, claimAuth;
    private MonogramWallet.Quote quote;
    private String destination;
    private AlertDialog waitingDialog;
    private Runnable retryScan;
    private boolean closed, busy, committing;
    private TL_wallet.walletTransaction pendingReview;

    public ClaimPaymentLinkFlow(BaseFragment host, int account, String uri) throws Exception {
        this.host = host;
        this.account = account;
        main = GramWallet.getInstance(account).backend();
        link = MoneroClaim.parse(uri, main.network);
        MonogramWallet existing = null;
        MonogramWallet opened = null;
        boolean mainLeased = false, claimLeased = false;
        try {
            if (!main.exists() || main.node().isEmpty()) throw new IllegalStateException("Restore your wallet and choose a Monero node in Settings → Monero first.");
            for (MonogramWallet candidate : main.claims()) {
                if (candidate.primary().equals(link.payment.address) && !candidate.claimId.equals(link.id())) { existing = candidate; break; }
            }
            claim = existing == null ? MonogramWallet.getClaim(account, main.network, link.id()) : existing;
            opened = claim;
            main.acquire();
            mainLeased = true;
            claim.acquire();
            claimLeased = true;
            mainAuth = new MonogramWalletAuth(host, main, 9637);
            claimAuth = new MonogramWalletAuth(host, claim, 9638);
        } catch (Exception failure) {
            if (claimLeased) opened.release();
            if (mainLeased) main.release();
            link.close();
            throw failure;
        }
    }

    public void show() {
        TL_wallet.walletTransaction initial = new TL_wallet.walletTransaction();
        initial.id = "claim:" + link.id();
        initial.amount = WalletAmounts.checkedAtomic(link.payment.atomicAmount);
        initial.incoming = true;
        initial.preview = true;
        initial.feeUnknown = true;
        initial.readOnlyComment = true;
        initial.peer = GramWallet.createRecipientPeer(null, link.payment.address, null);
        initial.comment = "Deposit into “"+main.name()+"”. The wallet checks confirmations and shows the amount and network fee before you approve. Anyone else holding the link can also spend its funds until they are transferred.";
        initial.actionLabel = "Deposit to your wallet";
        mainAuth.resume();
        claimAuth.resume();
        WalletActivity.openTransactionSheet(host.getContext(), account, initial, (note, encrypted, done) -> {
            if (busy || closed) return;
            busy = true;
            mainAuth.unlock((approved, error) -> {
                if (closed) return;
                if (!approved) { busy = false; done.run(error == null ? "Unlock cancelled." : message(error)); return; }
                prepareDestination((transaction, failure) -> {
                    busy = false;
                    if (closed) return;
                    if (failure != null) { done.run(failure); return; }
                    pendingReview = transaction;
                    done.run(null);
                });
            });
        }, (note, encrypted) -> {
            TL_wallet.walletTransaction prepared = pendingReview;
            pendingReview = null;
            if (prepared == null) close();
            else AndroidUtilities.runOnUIThread(() -> { if (!closed) review(prepared); });
        }, host.getResourceProvider());
    }

    private void prepareDestination(org.telegram.messenger.Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        if (destination != null) { unlockClaim(done); return; }
        try {
            main.newAddress((address, error) -> {
                if (closed) return;
                if (error != null) { done.run(null, message(error)); return; }
                destination = address;
                unlockClaim(done);
            });
        } catch (Exception error) { done.run(null, message(error)); }
    }

    private void unlockClaim(org.telegram.messenger.Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        if (claim.exists()) {
            claimAuth.unlockWithRecentDeviceAuthentication((approved, error) -> {
                if (closed) return;
                if (!approved) { done.run(null, error == null ? "Unlock cancelled." : message(error)); return; }
                scan(done);
            });
        } else {
            claimAuth.authenticate("Open payment link", "Protect this independent claim wallet with your device credential", false, (approved, error) -> {
                if (closed) return;
                if (!approved) { done.run(null, error == null ? "Unlock cancelled." : message(error)); return; }
                try {
                    claim.node(main.node(), (unused, nodeError) -> {
                        if (closed) return;
                        if (nodeError != null) { done.run(null, message(nodeError)); return; }
                        try {
                            claim.restoreClaim(claim.authorizeRestore(), link, (value, restoreError) -> {
                                if (closed) return;
                                if (restoreError != null) { done.run(null, message(restoreError)); return; }
                                scan(done);
                            });
                        } catch (Exception restoreError) { done.run(null, message(restoreError)); }
                    });
                } catch (Exception nodeError) { done.run(null, message(nodeError)); }
            });
        }
    }

    private void scan(org.telegram.messenger.Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        try {
            MonogramWallet.Result<Void> scanned = (unused, scanError) -> {
                if (closed) return;
                if (scanError != null) { stopWaiting(); done.run(null, message(scanError)); return; }
                try {
                    if (claim.balance != null && claim.balance.length > 2
                            && new java.math.BigInteger(claim.balance[1]).signum() > 0
                            && !claim.balance[1].equals(claim.balance[2])) {
                        waitForConfirmations(done);
                        return;
                    }
                    stopWaiting();
                    claim.prepareClaimSweep(destination, (prepared, feeError) -> {
                        if (closed) { claim.cancel(prepared); return; }
                        if (feeError != null) { done.run(null, message(feeError)); return; }
                        try {
                            if (quote != null) claim.cancel(quote);
                            quote = prepared;
                            TL_wallet.walletTransaction transaction = new TL_wallet.walletTransaction();
                            transaction.id = transaction.tx_hash = prepared.hashes;
                            transaction.amount = WalletAmounts.checkedAtomic(prepared.payment.atomicAmount);
                            transaction.fee = WalletAmounts.checkedAtomic(prepared.fee);
                            transaction.peer = GramWallet.createRecipientPeer(null, destination, null);
                            transaction.preview = true;
                            transaction.incoming = true;
                            transaction.readOnlyComment = true;
                            transaction.actionLabel = "Deposit " + GramWallet.formatGramsWithUnit(transaction.amount);
                            done.run(transaction, null);
                        } catch (Exception invalid) { claim.cancel(prepared); quote = null; done.run(null, message(invalid)); }
                    });
                } catch (Exception feeError) { done.run(null, message(feeError)); }
            };
            if (claim.needsRescan()) claim.rescan(scanned); else claim.sync(scanned);
        } catch (Exception error) { done.run(null, message(error)); }
    }

    private void waitForConfirmations(org.telegram.messenger.Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        String[] snapshot=claim.balance;
        String progress=snapshot!=null && snapshot.length>3
            ? "Funds: "+com.negzero.monogram.xmpp.MoneroPayment.format(new java.math.BigInteger(snapshot[1]))+" XMR\nSpendable: "+com.negzero.monogram.xmpp.MoneroPayment.format(new java.math.BigInteger(snapshot[2]))+" XMR\nScanned block: "+snapshot[3]+"\n\n" : "";
        String waiting=progress+"The link’s funds need confirmations before they can be deposited. Checking every 30 seconds. Once spendable, you can review the deposit amount and network fee. No deposit has been sent.";
        if (waitingDialog == null) {
            waitingDialog = new AlertDialog.Builder(host.getContext(), host.getResourceProvider())
                .setTitle("Waiting for confirmations")
                .setMessage(waiting)
                .setNegativeButton("Stop waiting", (dialog, which) -> {
                    stopWaiting();busy=false;done.run(null,"Stopped checking. No transaction was sent.");
                }).create();
            waitingDialog.setCancelable(false);
            waitingDialog.setCanceledOnTouchOutside(false);
            waitingDialog.show();
        }else waitingDialog.setMessage(waiting);
        if(retryScan!=null)AndroidUtilities.cancelRunOnUIThread(retryScan);
        retryScan=()->{retryScan=null;if(!closed)scan(done);};
        AndroidUtilities.runOnUIThread(retryScan,30000);
    }
    private void stopWaiting(){
        if(retryScan!=null){AndroidUtilities.cancelRunOnUIThread(retryScan);retryScan=null;}
        if(waitingDialog!=null){waitingDialog.dismiss();waitingDialog=null;}
    }
    private void review(TL_wallet.walletTransaction transaction) {
        WalletActivity.openTransactionSheet(host.getContext(), account, transaction, (note, encrypted, done) -> {
            if (closed || committing || quote == null) return;
            try {
                committing = true;
                claim.commit(quote, (result, error) -> {
                    committing = false;
                    quote = null;
                    if (error != null) { done.run(message(error)); if (closed) release(); return; }
                    if (result == null || result.length < 2 || !"broadcast".equals(result[0])) {
                        done.run("Claim status is uncertain. Check its transaction history before trying again.");
                        if (closed) release();
                        return;
                    }
                    done.run(null);
                    try { if (main.unlocked() && !main.busy) main.sync((unused, failure) -> GramWallet.getInstance(account).invalidateState()); }
                    catch (Exception unavailable) { GramWallet.getInstance(account).invalidateState(); }
                    close();
                });
            } catch (Exception error) { committing = false; done.run(message(error)); }
        }, (note, encrypted) -> close(), host.getResourceProvider());
    }

    public void resume() { mainAuth.resume(); claimAuth.resume(); }
    public void pause() { mainAuth.pause(); claimAuth.pause(); }
    public boolean activityResult(int code, int result) { return mainAuth.activityResult(code, result) || claimAuth.activityResult(code, result); }
    public boolean busy() { return busy || committing; }
    public void close() {
        if (closed) return;
        closed = true;
        stopWaiting();
        if (!committing) release();
    }
    private boolean released;
    private void release() {
        if (released) return;
        released = true;
        if (quote != null) { claim.cancel(quote); quote = null; }
        mainAuth.destroy();
        claimAuth.destroy();
        main.release();
        claim.release();
        link.close();
    }
    private static String message(Exception error) { return error.getMessage() == null ? "Claim operation failed." : error.getMessage(); }
}
