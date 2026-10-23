// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import android.content.Intent;
import com.negzero.monogram.wallet.MonogramWallet;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.MonogramWalletAuth;

/** Lifecycle and system authentication shared by native amount screens; owns no wallet secrets. */
public final class WalletAccessController {
    private final BaseFragment host;
    private final int account;
    private final Runnable changed;
    private final MonogramWallet wallet;
    private final MonogramWalletAuth auth;
    private final Runnable release;
    private final MonogramWallet.Observer observer;
    private boolean closed;

    public WalletAccessController(BaseFragment host, int account, int requestCode, Runnable changed) throws Exception {
        this.host = host;
        this.account = account;
        this.changed = changed;
        wallet = GramWallet.getInstance(account).backend();
        auth = new MonogramWalletAuth(host, wallet, requestCode);
        release = GramWallet.getInstance(account).listen();
        observer = () -> { if (!closed) changed.run(); };
        wallet.observe(observer);
    }

    public boolean ready() { return wallet.sessionScanned(); }
    public boolean busy() { return wallet.busy || auth.pending(); }
    public boolean scanning() { return wallet.scanning; }
    public String title() {
        if (wallet.scanning) return wallet.scanProgress() + " · Stop";
        if (wallet.busy) return wallet.status;
        if (auth.pending()) return "Unlocking wallet…";
        return !wallet.exists() ? "Restore wallet" : !wallet.unlocked() ? "Unlock wallet" : "Sync wallet";
    }
    public void resume() { auth.resume(); changed.run(); }
    public void pause() { auth.pause(); }
    public boolean activityResult(int code, int result, Intent data) { return auth.activityResult(code, result); }

    public void open() {
        if (closed) return;
        if (wallet.scanning) { wallet.stop(); return; }
        if (busy()) return;
        if (!wallet.exists() || wallet.node().isEmpty()) {
            WalletActivity screen = new WalletActivity();
            screen.setCurrentAccount(account);
            host.presentFragment(screen);
            return;
        }
        if (wallet.unlocked()) {
            try {
                startSync();
            } catch (Exception failure) { show(failure); }
        } else {
            auth.unlock((success, error) -> {
                if (closed) return;
                if (error != null) show(error);
                if (success && !wallet.busy) {
                    try { startSync(); } catch (Exception failure) { show(failure); }
                }
                changed.run();
            });
        }
        changed.run();
    }
    private void startSync() {
        MonogramWallet.Result<Void> done = (unused, failure) -> {
            if (closed) return;
            if (failure != null) show(failure);
            changed.run();
        };
        if (wallet.needsRescan()) wallet.rescan(done); else wallet.sync(done);
    }
    private void show(Exception failure) {
        if (!closed) BulletinFactory.of(host).showForError(failure.getMessage() == null ? "Wallet operation failed." : failure.getMessage());
    }
    public void close() {
        if (closed) return;
        closed = true;
        auth.destroy();
        wallet.unobserve(observer);
        release.run();
    }
}
