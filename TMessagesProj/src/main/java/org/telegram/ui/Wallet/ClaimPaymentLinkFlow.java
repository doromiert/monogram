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
    private String destination;
    private java.math.BigInteger maxFee;
    private boolean watchOnly,closed,busy,queued;
    private int depositPriority;

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
        initial.comment = "Destination: “"+main.name()+"”. Approve a maximum network fee, then leave this screen. Monogram will deposit the link’s spendable funds into that wallet in the background. Its claim wallet stays unlocked while checking; you can stop checking from the notification. If Android stops the app, reopen the link to resume.";
        initial.actionLabel = "Deposit in background";
        mainAuth.resume();claimAuth.resume();
        WalletActivity.openTransactionSheet(host.getContext(), account, initial, (note, encrypted, done) -> {
            if (busy || closed) return;
            busy=true;
            mainAuth.unlock((unlocked,failure)->{
                if(closed)return;
                if(!unlocked){busy=false;done.run(failure==null ? "Unlock cancelled." : message(failure));return;}
                prepareFeeChoice((choice,error)->{
                    if(closed)return;
                    if(choice==null){busy=false;done.run(error);return;}
                    watchOnly=choice.watch;maxFee=choice.limit;depositPriority=choice.priority;
                    unlockClaim((unused,queueError)->{
                        busy=false;if(closed)return;
                        queued=queueError==null;done.run(queueError);
                    });
                });
            });
        }, (note, encrypted) -> {
            if(queued)BulletinFactory.of(host).createSimpleBulletin(org.telegram.messenger.R.raw.done,watchOnly ? "Checking link status in the background" : "Background deposit queued").show();
            close();
        }, host.getResourceProvider());
    }

    private void prepareFeeChoice(org.telegram.messenger.Utilities.Callback2<DepositFeePicker.Choice,String> done){
        Runnable estimate=()->{
            try{main.estimateDepositFees(destination,(values,error)->{
                if(!closed)DepositFeePicker.show(host,main,link.payment.atomicAmount,error==null ? values : null,done);
            });}catch(Exception error){if(!closed)DepositFeePicker.show(host,main,link.payment.atomicAmount,null,done);}
        };
        if(destination!=null){estimate.run();return;}
        try{main.newAddress((address,error)->{
            if(closed)return;
            if(error!=null){done.run(null,message(error));return;}
            destination=address;estimate.run();
        });}catch(Exception error){done.run(null,message(error));}
    }

    private void unlockClaim(org.telegram.messenger.Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        if (claim.exists()) {
            claimAuth.unlockWithRecentDeviceAuthentication((approved, error) -> {
                if (closed) return;
                if (!approved) { done.run(null, error == null ? "Unlock cancelled." : message(error)); return; }
                queue(done);
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
                                queue(done);
                            });
                        } catch (Exception restoreError) { done.run(null, message(restoreError)); }
                    });
                } catch (Exception nodeError) { done.run(null, message(nodeError)); }
            });
        }
    }

    private void queue(org.telegram.messenger.Utilities.Callback2<TL_wallet.walletTransaction,String> done){
        try{
            MonogramWallet.Result<Void> result=(unused,error)->{if(!closed)done.run(null,error==null ? null : message(error));};
            if(watchOnly)com.negzero.monogram.wallet.ClaimDepositService.watch(claim,result);
            else com.negzero.monogram.wallet.ClaimDepositService.start(claim,destination,maxFee,depositPriority,result);
        }catch(Exception error){done.run(null,message(error));}
    }

    public void resume() { mainAuth.resume(); claimAuth.resume(); }
    public void pause() { mainAuth.pause(); claimAuth.pause(); }
    public boolean activityResult(int code, int result) { return mainAuth.activityResult(code, result) || claimAuth.activityResult(code, result); }
    public boolean busy() { return busy; }
    public void close() {
        if (closed) return;
        closed = true;
        release();
    }
    private boolean released;
    private void release() {
        if (released) return;
        released = true;
        mainAuth.destroy();
        claimAuth.destroy();
        main.release();
        claim.release();
        link.close();
    }
    private static String message(Exception error) { return error.getMessage() == null ? "Claim operation failed." : error.getMessage(); }
}
