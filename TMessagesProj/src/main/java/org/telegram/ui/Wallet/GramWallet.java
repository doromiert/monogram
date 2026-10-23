// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import com.negzero.monogram.wallet.MonogramWallet;
import com.negzero.monogram.wallet.WalletAmounts;
import com.negzero.monogram.wallet.WalletFiat;
import com.negzero.monogram.xmpp.MoneroPayment;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.xmpp.MonogramXmr;
import org.telegram.messenger.xmpp.MonogramXmrMessage;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_wallet;

/** Monero adapter for Telegram's original wallet views. No Telegram wallet RPCs or TON signing. */
public final class GramWallet {
    private static final HashMap<Integer, GramWallet> instances = new HashMap<>();
    public final int currentAccount;
    public final CurrenciesController currencies;
    private final TransactionsList history = new TransactionsList();
    private final CollectiblesList collectibles = new CollectiblesList();
    private MonogramWallet.Quote reviewed;
    private MonogramWallet reviewedWallet;
    private long reviewGeneration;
    public boolean hasReviewedPayment(String address, long amount) {
        return reviewed != null && amount > 0 && addressEquals(address, reviewed.payment.address)
                && BigInteger.valueOf(amount).equals(reviewed.payment.atomicAmount);
    }

    public static synchronized GramWallet getInstance(int account) {
        GramWallet wallet = instances.get(account);
        if (wallet == null) {
            wallet = new GramWallet(account);
            instances.put(account, wallet);
        }
        return wallet;
    }
    public static synchronized void cleanup(int account) {
        GramWallet previous = instances.remove(account);
        if (previous != null) AndroidUtilities.runOnUIThread(() -> {
            previous.clearReview();
            previous.history.transactions.clear();
            previous.history.allTransactions.clear();
        });
    }

    private GramWallet(int account) {
        currentAccount = account;
        currencies = new CurrenciesController(this);
    }

    public MonogramWallet backend() throws Exception {
        try (MonogramXmr settings = new MonogramXmr(currentAccount)) {
            return MonogramWallet.get(currentAccount, settings.network());
        }
    }

    public boolean isReady() {
        try {
            MonogramWallet wallet = backend();
            if (!wallet.unlocked() || !wallet.scanned() || wallet.balance == null || wallet.balance.length < 3) return false;
            WalletAmounts.checkedAtomic(new BigInteger(wallet.balance[1]));
            WalletAmounts.checkedAtomic(new BigInteger(wallet.balance[2]));
            return true;
        }
        catch (Exception unavailable) { return false; }
    }

    public boolean canSend() {
        try { return backend().readyToSend() && getSpendableBalance() > 0; }
        catch (Exception unavailable) { return false; }
    }

    public String getAddress() {
        try { return backend().primary(); }
        catch (Exception unavailable) { return ""; }
    }

    public byte[] getPublicKey() { return null; } // Monero keys are never recipient-discovery metadata.
    public boolean isGaslessAvailable() { return false; }
    public boolean hasWaltBalance() { return false; }
    public String getWaltUrl() { return "https://www.getmonero.org/"; }
    public long getBalanceOfPreviousWallets() { return 0; }
    public boolean cantAccessWallet() { return !isReady(); }
    public boolean isBackupEnabled() { return false; }
    public boolean canEnableBackup() { return false; }
    public boolean canExportPhrase() { return false; }

    private long balance(int index) {
        try {
            String[] snapshot = backend().balance;
            if (snapshot == null || snapshot.length <= index) return 0;
            return WalletAmounts.checkedAtomic(new BigInteger(snapshot[index]));
        } catch (Exception invalid) { return 0; }
    }
    public long getBalance() { return balance(1); }
    public long getSpendableBalance() { return balance(2); }

    public void whenReady(Runnable callback) {
        if (isReady()) callback.run();
        // Locked/restoring wallets are handled by the native screen's unlock state.
    }
    public void whenReadyGasless(Runnable callback) { whenReady(callback); }

    public Runnable listen() {
        try {
            MonogramWallet wallet = backend();
            MonogramWallet.Observer observer = () -> { history.reload(); notifyUpdate(); };
            wallet.acquire();
            wallet.observe(observer);
            history.reload();
            return new Runnable() {
                boolean closed;
                public void run() {
                    if (closed) return;
                    closed = true;
                    wallet.unobserve(observer);
                    wallet.release();
                    clearReview();
                }
            };
        } catch (Exception unavailable) { return () -> {}; }
    }

    public void notifyUpdate() {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.walletUpdate, this);
    }
    public void invalidateState() { history.reload(); notifyUpdate(); }

    private void clearReview() {
        reviewGeneration++;
        if (reviewedWallet != null && reviewed != null) reviewedWallet.cancel(reviewed);
        reviewed = null;
        reviewedWallet = null;
    }

    /** Native "emulation" is a real, cancellable Monero fee/transaction preparation. */
    public Runnable emulateSend(String address, long amount, String comment, byte[] unusedPublicKey,
            Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        clearReview();
        final long generation = reviewGeneration;
        final boolean[] cancelled = {false};
        final MonogramWallet.Quote[] pending = {null};
        final MonogramWallet[] owner = {null};
        final Runnable[] prepare = new Runnable[1];
        prepare[0] = () -> {
          if (cancelled[0] || generation != reviewGeneration) return;
          try {
            MonogramWallet wallet = backend();
            owner[0] = wallet;
            if (wallet.busy) { AndroidUtilities.runOnUIThread(prepare[0], 350); return; }
            if (amount <= 0) throw new IllegalArgumentException("Enter a positive XMR amount.");
            MoneroPayment payment = MoneroPayment.create(address,
                    WalletAmounts.coins(amount).toPlainString(), "", comment == null ? "" : comment, wallet.network);
            wallet.prepare(payment, (quote, error) -> {
                pending[0] = quote;
                if (cancelled[0] || generation != reviewGeneration) { wallet.cancel(quote); return; }
                if (error != null) { done.run(null, message(error)); return; }
                try {
                    TL_wallet.walletTransaction transaction = transaction(quote);
                    reviewed = quote;
                    reviewedWallet = wallet;
                    transaction.comment = comment;
                    done.run(transaction, null);
                } catch (Exception invalid) {
                    wallet.cancel(quote);
                    done.run(null, message(invalid));
                }
            });
          } catch (Exception error) { done.run(null, message(error)); }
        };
        AndroidUtilities.runOnUIThread(prepare[0], 350);
        return () -> {
            cancelled[0] = true;
            AndroidUtilities.cancelRunOnUIThread(prepare[0]);
            if (owner[0] != null && pending[0] != null) {
                owner[0].cancel(pending[0]);
                if (reviewed == pending[0]) { reviewed = null; reviewedWallet = null; }
            }
        };
    }

    private TL_wallet.walletTransaction transaction(MonogramWallet.Quote quote) {
        TL_wallet.walletTransaction transaction = new TL_wallet.walletTransaction();
        transaction.id = quote.hashes;
        transaction.tx_hash = quote.hashes;
        transaction.amount = WalletAmounts.checkedAtomic(quote.payment.atomicAmount);
        transaction.fee = WalletAmounts.checkedAtomic(quote.fee);
        transaction.peer = createRecipientPeer(null, quote.payment.address, null);
        transaction.date = (int) (System.currentTimeMillis() / 1000);
        transaction.preview = true;
        return transaction;
    }

    public void send(TLRPC.User user, String address, long amount, String comment, byte[] unusedPublicKey,
            String domain, Utilities.Callback<String> done, Utilities.Callback<TL_wallet.walletTransaction> queued) {
        MonogramWallet.Quote quote = reviewed;
        MonogramWallet wallet = reviewedWallet;
        try {
            if (wallet == null || quote == null || wallet != backend() || !wallet.readyToSend()
                    || !addressEquals(address, quote.payment.address)
                    || amount <= 0 || !BigInteger.valueOf(amount).equals(quote.payment.atomicAmount)) {
                throw new IllegalStateException("Payment review expired. Review the amount and network fee again.");
            }
            TL_wallet.walletTransaction transaction = transaction(quote);
            transaction.peer = createRecipientPeer(user, address, domain);
            transaction.comment = comment;
            transaction.preview = false;
            reviewed = null;
            reviewedWallet = null;
            wallet.commit(quote, (result, error) -> {
                if (error != null) { done.run(message(error)); return; }
                if (result == null || result.length < 2 || !"broadcast".equals(result[0])) {
                    done.run("Broadcast status is uncertain. Check transaction history before sending again.");
                    invalidateState();
                    return;
                }
                transaction.id = result[1];
                transaction.tx_hash = result[1];
                transaction.pending = true;
                transaction.phase = WalletEngine2.SendPhase.SUBMITTED;
                if (user != null) {
                    HashMap<String, String> params = new HashMap<>();
                    params.put(MonogramXmr.REQUEST_PARAM, transaction.tx_hash);
                    params.put(MonogramXmr.PRIVATE_PARAM, "true");
                    MoneroPayment noticePayment = MoneroPayment.create(quote.payment.address,
                            WalletAmounts.coins(amount).toPlainString(), "", comment == null ? "" : comment, wallet.network);
                    SendMessagesHelper.SendMessageParams notice = SendMessagesHelper.SendMessageParams.of(
                            "XMR payment broadcast\n" + MonogramXmrMessage.transfer(noticePayment, transaction.tx_hash),
                            user.id, null, null, null, false, null, null, params, true, 0, 0, null, false);
                    notice.onLocalMessageCreated = message -> transaction.localMessageId = message.getId();
                    try { SendMessagesHelper.getInstance(currentAccount).sendMessage(notice); }
                    catch (Exception notificationFailure) { FileLog.e("XMR broadcast succeeded; chat notification could not be queued."); }
                }
                if (queued != null) queued.run(transaction);
                invalidateState();
                done.run(null);
            });
        } catch (Exception error) { AndroidUtilities.runOnUIThread(() -> done.run(message(error))); }
    }
    public void send(TLRPC.User user, String address, long amount, String comment, byte[] key,
            String domain, Utilities.Callback<String> done) { send(user, address, amount, comment, key, domain, done, null); }
    public void send(String address, long amount, String comment, byte[] key, Utilities.Callback<String> done) {
        send(null, address, amount, comment, key, null, done, null);
    }
    public void makeSureSecretPhraseBackedUpLocally(Utilities.Callback<String> done) {
        try { done.run(backend().unlocked() ? null : "Unlock your wallet first."); }
        catch (Exception error) { done.run(message(error)); }
    }
    public void makeSureSecretPhraseBackedUpLocally(boolean sheet, boolean progress, Utilities.Callback<String> done) {
        makeSureSecretPhraseBackedUpLocally(done);
    }

    public void resolveWallet(String address, Utilities.Callback2<TL_wallet.walletUserAddress, String> done) {
        try {
            TL_wallet.walletUserAddress resolved = new TL_wallet.walletUserAddress();
            resolved.address = MoneroPayment.address(address, backend().network).address;
            AndroidUtilities.runOnUIThread(() -> done.run(resolved, null));
        } catch (Exception error) { done.run(null, message(error)); }
    }
    public void resolveWallet(TLRPC.User user, boolean force, Utilities.Callback2<TL_wallet.walletUserAddress, String> done) {
        try (MonogramXmr settings = new MonogramXmr(currentAccount)) {
            if (user != null) {
                String address = settings.contactAddress(MonogramController.get(currentAccount).jid(user.id));
                if (address != null && !address.isEmpty()) {
                    TL_wallet.walletUserAddress resolved = new TL_wallet.walletUserAddress();
                    resolved.user_id = user.id;
                    resolved.address = MoneroPayment.address(address, settings.network()).address;
                    AndroidUtilities.runOnUIThread(() -> done.run(resolved, null));
                    return;
                }
            }
            AndroidUtilities.runOnUIThread(() -> done.run(null, "WALLET_USER_UNAVAILABLE"));
        } catch (Exception error) { done.run(null, message(error)); }
    }
    public void decryptTransactionComment(String text, String sender, Utilities.Callback2<String, String> done) {
        done.run(null, "TON-encrypted comments are not Monero payment metadata.");
    }
    public Runnable emulateSendNFT(String address, TL_wallet.nftItem nft, Utilities.Callback2<TL_wallet.walletTransaction, String> done) {
        done.run(null, "Collectibles are not supported by Monero.");
        return () -> {};
    }
    public void sendNFT(TLRPC.User user, String address, TL_wallet.nftItem nft, String comment, Utilities.Callback<String> done) {
        done.run("Collectibles are not supported by Monero.");
    }

    public static boolean addressEquals(String first, String second) {
        return first != null && !first.isEmpty() && first.equals(second); // Base58 is case-sensitive.
    }
    public static boolean transactionMatches(TL_wallet.walletTransaction a, TL_wallet.walletTransaction b) {
        return a != null && b != null && ((a.id != null && !a.id.isEmpty() && a.id.equals(b.id))
                || (a.tx_hash != null && !a.tx_hash.isEmpty() && a.tx_hash.equals(b.tx_hash)));
    }
    public static boolean messageMatchesTransaction(MessageObject message, TL_wallet.walletTransaction transaction) {
        if (message == null || transaction == null) return false;
        if (transaction.localMessageId != 0 && message.getId() == transaction.localMessageId) return true;
        return message.messageOwner.action instanceof TLRPC.TL_messageActionGramTransfer
                && TextUtils.equals(transaction.tx_hash, ((TLRPC.TL_messageActionGramTransfer) message.messageOwner.action).transaction_id);
    }
    public TL_wallet.walletTransaction findTransaction(MessageObject message) {
        if (message == null || !(message.messageOwner.action instanceof TLRPC.TL_messageActionGramTransfer)) return null;
        TLRPC.TL_messageActionGramTransfer action = (TLRPC.TL_messageActionGramTransfer) message.messageOwner.action;
        for (TL_wallet.walletTransaction transaction : history.allTransactions) {
            if (transaction.amount == action.amount && transaction.incoming != message.isOutOwner()
                    && !transaction.failed && transaction.tx_hash != null
                    && java.util.Arrays.asList(action.transaction_id.split(",")).contains(transaction.tx_hash)) return transaction;
        }
        return null;
    }
    public static TL_wallet.WalletTransactionPeer createRecipientPeer(TLRPC.User user, String address, String domain) {
        TL_wallet.WalletTransactionPeer peer = user == null
                ? new TL_wallet.walletTransactionPeerAddress() : new TL_wallet.walletTransactionPeerUser();
        if (user != null) peer.user_id = user.id;
        peer.address = address;
        peer.domain = domain;
        return peer;
    }

    public TransactionsList getTransactions() { return history; }
    public CollectiblesList getCollectibles() { return collectibles; }
    public void getTransaction(String id, Utilities.Callback<TL_wallet.walletTransaction> done) {
        history.reload();
        for (TL_wallet.walletTransaction transaction : history.allTransactions) {
            if (TextUtils.equals(id, transaction.id)) { done.run(transaction); return; }
        }
        done.run(null);
    }

    public final class TransactionsList {
        public final TransactionCell.SmartWalletAddressFormatter addressFormatter = new TransactionCell.SmartWalletAddressFormatter();
        public final ArrayList<TL_wallet.walletTransaction> transactions = new ArrayList<>();
        public final ArrayList<TL_wallet.walletTransaction> allTransactions = new ArrayList<>();
        public boolean isLoading() { try { return backend().busy; } catch (Exception missing) { return false; } }
        // The SDK publishes the entire local snapshot. There is no next page to load,
        // including while locked; returning false makes the source empty-list loader recurse.
        public boolean isEndReached() { return true; }
        public boolean hasEndReached() { return isEndReached(); }
        public void load() { reload(); }
        public void cancel() {} // Reading a published snapshot does not start a network scan.
        private void reload() {
            ArrayList<TL_wallet.walletTransaction> snapshot = new ArrayList<>();
            try {
                for (String entry : backend().transactions) {
                    JSONObject json = new JSONObject(entry);
                    TL_wallet.walletTransaction transaction = new TL_wallet.walletTransaction();
                    transaction.id = transaction.tx_hash = json.getString("hash");
                    transaction.amount = WalletAmounts.checkedAtomic(new BigInteger(json.getString("amount")));
                    transaction.fee = WalletAmounts.checkedAtomic(new BigInteger(json.getString("fee")));
                    transaction.date = Math.toIntExact(json.getLong("timestamp"));
                    transaction.incoming = json.getInt("direction") == 0;
                    transaction.pending = json.getBoolean("pending") || json.getLong("confirmations") == 0;
                    transaction.failed = json.getBoolean("failed");
                    transaction.peer = new TL_wallet.walletTransactionPeerUnsupported();
                    transaction.phase = transaction.failed ? WalletEngine2.SendPhase.FAILED
                            : transaction.pending ? WalletEngine2.SendPhase.SUBMITTED : WalletEngine2.SendPhase.CONFIRMED;
                    snapshot.add(transaction);
                }
            } catch (Exception invalid) { FileLog.e(invalid); }
            snapshot.sort((a, b) -> Integer.compare(b.date, a.date));
            transactions.clear(); transactions.addAll(snapshot);
            allTransactions.clear(); allTransactions.addAll(snapshot);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.walletTransactionsUpdate, this);
        }
    }

    /** Keeps source tab bookkeeping intact; Monero has no NFT/collectible backend. */
    public static final class CollectiblesList {
        public final ArrayList<TL_wallet.nftItem> collectibles = new ArrayList<>();
        public boolean isLoading() { return false; }
        public boolean isLoaded() { return true; }
        public boolean isEndReached() { return true; }
        public String getError() { return null; }
        public void load() {}
        public void invalidate() {}
        public void subscribe(Runnable callback) {}
        public void unsubscribe(Runnable callback) {}
    }

    public static String formatGrams(long amount) { return WalletAmounts.coins(amount).stripTrailingZeros().toPlainString(); }
    public static String formatGrams(long amount, boolean manyZeros) { return formatGrams(amount); }
    public static CharSequence formatGramsWithUnit(long amount) { return formatGramsWithUnit(formatGrams(amount)); }
    public static CharSequence formatGramsWithUnit(long amount, boolean manyZeros) { return formatGramsWithUnit(amount); }
    public static CharSequence formatGramsWithUnit(CharSequence amount) { return new SpannableStringBuilder(amount).append(" XMR"); }
    public static CharSequence formatGramCapital(long amount) { return formatGramsWithUnit(amount); }
    public static SpannableStringBuilder formatGramsScaled(long amount) { return formatGramsScaled(amount, .8f); }
    public static SpannableStringBuilder formatGramsScaled(long amount, float scale) { return formatGramsScaled(formatGrams(amount), scale); }
    public static SpannableStringBuilder formatGramsScaled(String value, float scale) {
        SpannableStringBuilder result = new SpannableStringBuilder(value);
        int decimal = value.indexOf('.');
        if (decimal >= 0 && decimal + 1 < value.length()) result.setSpan(new RelativeSizeSpan(scale), decimal + 1, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }
    public CharSequence formatCurrency(long atomic) { return formatCurrency(atomic, false); }
    public CharSequence formatCurrency(long atomic, boolean manyZeros) {
        WalletFiat.Rate rate = currencies.exactRate();
        if (rate == null) return "—";
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return new WalletFiat.Format(rate.currency, locale == null ? Locale.getDefault() : locale)
                .format(rate.fiatFor(BigInteger.valueOf(atomic)));
    }
    private static String message(Exception error) { return error.getMessage() == null ? "Wallet operation failed." : error.getMessage(); }
}
