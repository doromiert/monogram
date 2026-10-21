package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatPluralStringComma;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.messenger.MediaDataController.calcHash;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BirthdayController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.TopicsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.tlutils.AmountUtils;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SharedMediaLayout;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.bots.BotWebViewSheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StarsController {

    public static final String currency = "XTR";

    public static final int PERIOD_MONTHLY = 2592000;
    // test backend only:
    public static final int PERIOD_MINUTE = 60;
    public static final int PERIOD_5MINUTES = 300;

    private static volatile StarsController[][] Instance = new StarsController[2][UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[][] lockObjects = new Object[2][UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int a = 0; a < 2; ++a) {
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                lockObjects[a][i] = new Object();
            }
        }
    }

    public static StarsController getTonInstance(int num) {
        return getInstance(num, true);
    }

    public static StarsController getInstance(int num) {
        return getInstance(num, false);
    }

    public static StarsController getInstance(int num, AmountUtils.Currency currency) {
        return getInstance(num, currency == AmountUtils.Currency.TON);
    }

    public static StarsController getInstance(int num, boolean ton) {
        StarsController localInstance = Instance[ton ? 1 : 0][num];
        if (localInstance == null) {
            synchronized (lockObjects[ton ? 1 : 0][num]) {
                localInstance = Instance[ton ? 1 : 0][num];
                if (localInstance == null) {
                    Instance[ton ? 1 : 0][num] = localInstance = new StarsController(num, ton);
                }
            }
        }
        return localInstance;
    }

    public final int currentAccount;
    public final boolean ton;

    private StarsController(int account, boolean ton) {
        this.currentAccount = account;
        this.ton = ton;
    }

    // ===== STAR BALANCE =====

    private long lastBalanceLoaded;
    private boolean balanceLoading, balanceLoaded;
    @NonNull
    public TL_stars.StarsAmount balance = TL_stars.StarsAmount.ofStars(0);
    public long minus;

    public TL_stars.StarsAmount getBalance() {
        return getBalance(null);
    }

    @NonNull
    public AmountUtils.Amount getBalanceAmount() {
        AmountUtils.Amount amount = AmountUtils.Amount.of(getBalance());
        if (amount == null) {
            amount = AmountUtils.Amount.fromNano(0, ton ? AmountUtils.Currency.TON : AmountUtils.Currency.STARS);
        }

        return amount;
    }

    public long getBalance(boolean withMinus) {
        return getBalance(withMinus, null, false).amount;
    }

    public TL_stars.StarsAmount getBalance(Runnable loaded) {
        return getBalance(true, loaded, false);
    }

    public TL_stars.StarsAmount getBalance(boolean withMinus, Runnable loaded, boolean force) {
        balance = TL_stars.StarsAmount.ofStars(0);
        minus = 0;
        balanceLoading = false;
        balanceLoaded = true;
        if (loaded != null) AndroidUtilities.runOnUIThread(loaded);
        return balance;
    }

    public boolean canUseTon() {
        return false;
    }

    public void invalidateBalance() {
        balanceLoaded = false;
        getBalance();
        balanceLoaded = true;
    }

    public void invalidateBalance(Runnable loaded) {
        balanceLoaded = false;
        getBalance(false, loaded, true);
        balanceLoaded = true;
    }

    public void updateBalance(TL_stars.StarsAmount balance) {
        if (!this.balance.equals(balance)) {
            this.balance = balance;
            this.minus = 0;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starBalanceUpdated);
        } else if (this.minus != 0) {
            this.minus = 0;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starBalanceUpdated);
        }
    }

    public boolean balanceAvailable() {
        return balanceLoaded;
    }

    private boolean optionsLoading, optionsLoaded;
    private ArrayList<TL_stars.TL_starsTopupOption> options;
    public ArrayList<TL_stars.TL_starsTopupOption> getOptionsCached() {
        return options;
    }

    public ArrayList<TL_stars.TL_starsTopupOption> getOptions() {
        if (options == null) options = new ArrayList<>();
        optionsLoading = false;
        optionsLoaded = true;
        return options;
    }

    private boolean giftOptionsLoading, giftOptionsLoaded;
    private ArrayList<TL_stars.TL_starsGiftOption> giftOptions;
    public ArrayList<TL_stars.TL_starsGiftOption> getGiftOptionsCached() {
        return giftOptions;
    }
    public ArrayList<TL_stars.TL_starsGiftOption> getGiftOptions() {
        if (giftOptions == null) giftOptions = new ArrayList<>();
        giftOptionsLoading = false;
        giftOptionsLoaded = true;
        return giftOptions;
    }

    private boolean giveawayOptionsLoading, giveawayOptionsLoaded;
    private ArrayList<TL_stars.TL_starsGiveawayOption> giveawayOptions;
    public ArrayList<TL_stars.TL_starsGiveawayOption> getGiveawayOptionsCached() {
        return giveawayOptions;
    }
    public ArrayList<TL_stars.TL_starsGiveawayOption> getGiveawayOptions() {
        if (giveawayOptions == null) giveawayOptions = new ArrayList<>();
        giveawayOptionsLoading = false;
        giveawayOptionsLoaded = true;
        return giveawayOptions;
    }

    private void bulletinError(TLRPC.TL_error err, String str) {
        bulletinError(err == null ? str : err.text);
    }
    private void bulletinError(String err) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        BulletinFactory b = fragment != null && fragment.visibleDialog == null ? BulletinFactory.of(fragment) : BulletinFactory.global();
        b.createSimpleBulletin(R.raw.error, formatString(R.string.UnknownErrorCode, err)).show();
    }



    // ===== STAR TRANSACTIONS =====

    public static final int ALL_TRANSACTIONS = 0;
    public static final int INCOMING_TRANSACTIONS = 1;
    public static final int OUTGOING_TRANSACTIONS = 2;

    public final ArrayList<TL_stars.StarsTransaction>[] transactions = new ArrayList[] { new ArrayList<>(), new ArrayList<>(), new ArrayList<>() };
    public final boolean[] transactionsExist = new boolean[3];
    private final String[] offset = new String[3];
    private final boolean[] loading = new boolean[3];
    private final boolean[] endReached = new boolean[3];

    public void invalidateTransactions(boolean load) {
        for (int i = 0; i < 3; ++i) {
            if (loading[i]) continue;
            transactions[i].clear();
            offset[i] = null;
            loading[i] = false;
            endReached[i] = false;
            if (load)
                loadTransactions(i);
        }
    }
    public void preloadTransactions() {
        for (int i = 0; i < 3; ++i) {
            if (!loading[i] && !endReached[i] && offset[i] == null) {
                loadTransactions(i);
            }
        }
    }
    public void loadTransactions(int type) {
        transactions[type].clear();
        transactionsExist[type] = false;
        loading[type] = false;
        endReached[type] = true;
    }
    public boolean isLoadingTransactions(int type) {
        return loading[type];
    }
    public boolean didFullyLoadTransactions(int type) {
        return endReached[type];
    }
    public boolean hasTransactions() {
        return hasTransactions(ALL_TRANSACTIONS);
    }
    public boolean hasTransactions(int type) {
        return balanceAvailable() && !transactions[type].isEmpty();
    }



    // ===== STAR SUBSCRIPTIONS =====

    public final ArrayList<TL_stars.StarsSubscription> subscriptions = new ArrayList<>();
    public String subscriptionsOffset;
    public boolean subscriptionsLoading, subscriptionsEndReached;

    public boolean hasSubscriptions() {
        return balanceAvailable() && !subscriptions.isEmpty();
    }

    public void invalidateSubscriptions(boolean load) {
        if (subscriptionsLoading) return;
        subscriptions.clear();
        subscriptionsOffset = null;
        subscriptionsLoading = false;
        subscriptionsEndReached = false;
        if (load) loadSubscriptions();
    }

    public void loadSubscriptions() {
        subscriptions.clear();
        subscriptionsLoading = false;
        subscriptionsEndReached = true;
    }
    public boolean isLoadingSubscriptions() {
        return subscriptionsLoading;
    }
    public boolean didFullyLoadSubscriptions() {
        return subscriptionsEndReached;
    }

    public final ArrayList<TL_stars.StarsSubscription> insufficientSubscriptions = new ArrayList<>();
    private boolean insufficientSubscriptionsLoading;
    public void loadInsufficientSubscriptions() {
        insufficientSubscriptions.clear();
        insufficientSubscriptionsLoading = false;
    }
    public void invalidateInsufficientSubscriptions(boolean load) {
        if (insufficientSubscriptionsLoading) return;
        insufficientSubscriptions.clear();
        insufficientSubscriptionsLoading = false;
        if (load) loadInsufficientSubscriptions();
    }
    public boolean hasInsufficientSubscriptions() {
        return !insufficientSubscriptions.isEmpty();
    }


    public Theme.ResourcesProvider getResourceProvider() {
        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment != null) {
            return lastFragment.getResourceProvider();
        }
        return null;
    }







    public boolean canBuy(TLRPC.InputPeer purposePeer) {
        return false;
    }

    public void buy(
        Activity activity,
        TL_stars.TL_starsTopupOption option,
        Utilities.Callback2<Boolean, String> whenDone,
        TLRPC.InputPeer purposePeer
    ) {
        if (whenDone != null) whenDone.run(false, "MONOGRAM_FEATURE_UNAVAILABLE");
    }

    public void buyGift(Activity activity, TL_stars.TL_starsGiftOption option, long user_id, Utilities.Callback2<Boolean, String> whenDone) {
        if (whenDone != null) whenDone.run(false, "MONOGRAM_FEATURE_UNAVAILABLE");
    }

    public void buyGiveaway(
            Activity activity,
            TLRPC.Chat chat, List<TLObject> chats,
            TL_stars.TL_starsGiveawayOption option, int users,
            List<TLObject> countries,
            int date,
            boolean winnersVisible,
            boolean onlyNewSubscribers,
            boolean withAdditionPrize, String prizeDescription,
            Utilities.Callback2<Boolean, String> whenDone
    ) {
        if (whenDone != null) whenDone.run(false, "MONOGRAM_FEATURE_UNAVAILABLE");
    }








    public static void showNoSupportDialog(Context context, Theme.ResourcesProvider resourcesProvider) {
        new AlertDialog.Builder(context, resourcesProvider)
            .setTitle(getString(R.string.StarsNotAvailableTitle))
            .setMessage(getString(R.string.StarsNotAvailableText))
            .setPositiveButton(getString(R.string.OK), null)
            .show();
    }











    // ===== STAR REACTIONS =====

    public static final long REACTIONS_TIMEOUT = 5_000;
    public PendingPaidReactions currentPendingReactions;

    public static class MessageId {
        public long did;
        public int mid;
        private MessageId(long did, int mid) {
            this.did = did;
            this.mid = mid;
        }
        public static MessageId from(long did, int mid) {
            return new MessageId(did, mid);
        }
        public static MessageId from(MessageObject msg) {
            if (msg == null) return null;
            if (msg.messageOwner != null && (msg.messageOwner.isThreadMessage || msg.isForwardedChannelPost()) && msg.messageOwner.fwd_from != null) {
                return new MessageId(msg.getFromChatId(), msg.messageOwner.fwd_from.saved_from_msg_id);
            } else {
                return new MessageId(msg.getDialogId(), msg.getId());
            }
        }
        @Override
        public int hashCode() {
            return Objects.hash(did, mid);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof MessageId) {
                MessageId id = (MessageId) obj;
                return id.did == did && id.mid == mid;
            }
            return false;
        }
    }

    public long getPaidReactionsDialogId(MessageObject messageObject) {
        if (currentPendingReactions != null && currentPendingReactions.message.equals(MessageId.from(messageObject)) && currentPendingReactions.peer != null) {
            return currentPendingReactions.peer;
        }
        Long messageSettings = messageObject == null ? null : messageObject.getMyPaidReactionPeer();
        if (messageSettings != null) {
            return messageSettings;
        }
        final MessagesController messagesController = MessagesController.getInstance(currentAccount);
        Long peer = messagesController.getPaidReactionsDialogId();
        return peer != null ? peer : 0;
    }

    public long getPaidReactionsDialogId(MessageId id, TLRPC.MessageReactions reactions) {
        if (currentPendingReactions != null && currentPendingReactions.message.equals(id) && currentPendingReactions.peer != null) {
            return currentPendingReactions.peer;
        }
        Long messageSettings = MessageObject.getMyPaidReactionPeer(reactions);
        if (messageSettings != null) {
            return messageSettings;
        }
        final MessagesController messagesController = MessagesController.getInstance(currentAccount);
        Long peer = messagesController.getPaidReactionsDialogId();
        return peer != null ? peer : 0;
    }

    public class PendingPaidReactions {

        public MessageId message;
        public MessageObject messageObject;
        public ChatActivity chatActivity;
        public Bulletin bulletin;
        public Bulletin.TwoLineAnimatedLottieLayout bulletinLayout;
        public Bulletin.UndoButton bulletinButton;
        public Bulletin.TimerView timerView;

        public boolean wasChosen;

        public long amount;
        public long lastTime;
        public boolean committed = false;
        public boolean cancelled = false;

        public long not_added;
        public boolean applied;
        public boolean shownBulletin;

        public Long peer = null;
        public long getPeerId() {
            if (peer != null) return peer;
            return getPaidReactionsDialogId(messageObject);
        }

        public boolean isAnonymous() {
            return getPeerId() == UserObject.ANONYMOUS;
        }

        public StarReactionsOverlay overlay;
        public void setOverlay(StarReactionsOverlay overlay) {
            this.overlay = overlay;
        }

        public String getToastTitle() {
            if (isAnonymous()) {
                return getString(R.string.StarsSentAnonymouslyTitle);
            } else if (getPeerId() != 0 && getPeerId() != UserConfig.getInstance(currentAccount).getClientUserId()) {
                return formatString(R.string.StarsSentTitleChannel, DialogObject.getShortName(getPeerId()));
            } else {
                return getString(R.string.StarsSentTitle);
            }
        }

        public PendingPaidReactions(
            MessageId message,
            MessageObject messageObject,
            ChatActivity chatActivity,
            long currentTime,
            boolean affect
        ) {
            this.message = message;
            this.messageObject = messageObject;
            this.chatActivity = chatActivity;

            final Context context = getContext(chatActivity);
            bulletinLayout = new Bulletin.TwoLineAnimatedLottieLayout(context, chatActivity.themeDelegate);
            bulletinLayout.setAnimation(R.raw.stars_topup);
            bulletinLayout.titleTextView.setText(getToastTitle());
            bulletinButton = new Bulletin.UndoButton(context, true, false, chatActivity.themeDelegate);
            bulletinButton.setText(LocaleController.getString(R.string.StarsSentUndo));
            bulletinButton.setUndoAction(this::cancel);
            timerView = new Bulletin.TimerView(context, chatActivity.themeDelegate);
            timerView.timeLeft = REACTIONS_TIMEOUT;
            timerView.setColor(Theme.getColor(Theme.key_undo_cancelColor, chatActivity.themeDelegate));
            bulletinButton.addView(timerView, LayoutHelper.createFrame(20, 20, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
            bulletinButton.undoTextView.setPadding(dp(12), dp(8), dp(20 + 10), dp(8));
            bulletinLayout.setButton(bulletinButton);
            bulletin = BulletinFactory.of(chatActivity).create(bulletinLayout, -1);
            bulletin.hideAfterBottomSheet = false;
            if (affect) {
                bulletin.show(true);
                shownBulletin = true;
            }
            bulletin.setOnHideListener(closeRunnable);

            this.amount = 0;
            this.lastTime = System.currentTimeMillis();

            wasChosen = messageObject.isPaidReactionChosen();
        }

        public void add(long amount, boolean affect) {
            if (committed || cancelled) {
                if (BuildVars.DEBUG_PRIVATE_VERSION) {
                    throw new RuntimeException("adding more amount to committed reactions");
                } else {
                    return;
                }
            }
            this.amount += amount;
            this.lastTime = System.currentTimeMillis();

            bulletinLayout.subtitleTextView.cancelAnimation();
            bulletinLayout.subtitleTextView.setText(AndroidUtilities.replaceTags(LocaleController.formatPluralString("StarsSentText", (int) this.amount)), true);

            if (shownBulletin) {
                timerView.timeLeft = REACTIONS_TIMEOUT;
                AndroidUtilities.cancelRunOnUIThread(closeRunnable);
                AndroidUtilities.runOnUIThread(closeRunnable, REACTIONS_TIMEOUT);
            }

            if (affect) {
                applied = true;
                messageObject.addPaidReactions((int) +amount, true, getPeerId());
                minus += amount;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.didUpdateReactions, messageObject.getDialogId(), messageObject.getId(), messageObject.messageOwner.reactions);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starBalanceUpdated);
            } else {
                applied = false;
                if (messageObject.ensurePaidReactionsExist(true)) {
                    not_added--;
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.didUpdateReactions, messageObject.getDialogId(), messageObject.getId(), messageObject.messageOwner.reactions);
                not_added += amount;
            }

            bulletinLayout.titleTextView.setText(getToastTitle());
        }

        public void apply() {
            if (!applied) {
                applied = true;
                messageObject.addPaidReactions((int) +not_added, true, getPeerId());
                minus += not_added;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starBalanceUpdated);
                not_added = 0;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.didUpdateReactions, messageObject.getDialogId(), messageObject.getId(), messageObject.messageOwner.reactions);
            }
            if (!shownBulletin) {
                shownBulletin = true;

                timerView.timeLeft = REACTIONS_TIMEOUT;
                AndroidUtilities.cancelRunOnUIThread(closeRunnable);
                AndroidUtilities.runOnUIThread(closeRunnable, REACTIONS_TIMEOUT);

                bulletin.show(true);
                bulletin.setOnHideListener(closeRunnable);
            }

            bulletinLayout.titleTextView.setText(getToastTitle());
        }

        public final Runnable closeRunnable = this::close;
        public void close() {
            AndroidUtilities.cancelRunOnUIThread(closeRunnable);

            if (applied) {
                commit();
            } else {
                cancelled = true;
                messageObject.addPaidReactions((int) -amount, wasChosen, getPeerId());
                minus -= amount;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starBalanceUpdated);
            }
            bulletin.hide();
            if (overlay != null && overlay.isShowing(messageObject)) {
                overlay.hide();
            }

            if (currentPendingReactions == this) {
                currentPendingReactions = null;
            }
        }

        public final Runnable cancelRunnable = this::cancel;
        public void cancel() {
            AndroidUtilities.cancelRunOnUIThread(closeRunnable);

            cancelled = true;
            bulletin.hide();
            if (overlay != null) {
                overlay.hide();
            }

            messageObject.addPaidReactions((int) -amount, wasChosen, getPeerId());
            minus -= amount;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starBalanceUpdated);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.didUpdateReactions, messageObject.getDialogId(), messageObject.getId(), messageObject.messageOwner.reactions);

            if (currentPendingReactions == this) {
                currentPendingReactions = null;
            }
        }

        public void commit() {
            if (committed || cancelled) {
                return;
            }

            final StarsController starsController = StarsController.getInstance(currentAccount);
            final MessagesController messagesController = MessagesController.getInstance(currentAccount);
            final ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);

            final long totalStars = amount;
            if (starsController.balanceAvailable() && starsController.getBalance(false) < totalStars) {
                cancelled = true;

                messageObject.addPaidReactions((int) -amount, wasChosen, getPeerId());
                minus = 0;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starBalanceUpdated);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.didUpdateReactions, messageObject.getDialogId(), messageObject.getId(), messageObject.messageOwner.reactions);

                String name;
                if (message.did >= 0) {
                    TLRPC.User user = chatActivity.getMessagesController().getUser(message.did);
                    name = UserObject.getForcedFirstName(user);
                } else {
                    TLRPC.Chat chat = chatActivity.getMessagesController().getChat(-message.did);
                    name = chat == null ? "" : chat.title;
                }
                Context context = chatActivity.getContext();
                if (context == null) context = LaunchActivity.instance;
                if (context == null) context = ApplicationLoader.applicationContext;
                new StarsIntroActivity.StarsNeededSheet(context, chatActivity.getResourceProvider(), totalStars, StarsIntroActivity.StarsNeededSheet.TYPE_REACTIONS, name, () -> {
                    sendPaidReaction(messageObject, chatActivity, totalStars, true, true, peer);
                }, 0).show();

                return;
            }

            committed = true;

            final TLRPC.TL_messages_sendPaidReaction req = new TLRPC.TL_messages_sendPaidReaction();
            req.peer = messagesController.getInputPeer(message.did);
            req.msg_id = message.mid;
            req.random_id = Utilities.random.nextLong() & 0xFFFFFFFFL | ((long) connectionsManager.getCurrentTime() << 32L);
            req.count = (int) amount;
            req.flags |= 1;
            final long privacyDialogId = getPeerId();
            if (privacyDialogId == 0 || privacyDialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
                req.privacy = new TL_stars.paidReactionPrivacyDefault();
            } else if (privacyDialogId == UserObject.ANONYMOUS) {
                req.privacy = new TL_stars.paidReactionPrivacyAnonymous();
            } else {
                req.privacy = new TL_stars.paidReactionPrivacyPeer();
                req.privacy.peer = messagesController.getInputPeer(privacyDialogId);
            }

            invalidateBalance();

            connectionsManager.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response != null) {
                    Utilities.stageQueue.postRunnable(() -> {
                        messagesController.processUpdates((TLRPC.Updates) response, false);
                    });
                } else if (error != null) {
                    messageObject.addPaidReactions((int) -amount, wasChosen, getPeerId());
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.didUpdateReactions, messageObject.getDialogId(), messageObject.getId(), messageObject.messageOwner.reactions);

                    if ("BALANCE_TOO_LOW".equals(error.text)) {
                        String name;
                        if (message.did >= 0) {
                            TLRPC.User user = chatActivity.getMessagesController().getUser(message.did);
                            name = UserObject.getForcedFirstName(user);
                        } else {
                            TLRPC.Chat chat = chatActivity.getMessagesController().getChat(-message.did);
                            name = chat == null ? "" : chat.title;
                        }
                        Context context = chatActivity.getContext();
                        if (context == null) context = LaunchActivity.instance;
                        if (context == null) context = ApplicationLoader.applicationContext;
                        new StarsIntroActivity.StarsNeededSheet(context, chatActivity.getResourceProvider(), totalStars, StarsIntroActivity.StarsNeededSheet.TYPE_REACTIONS, name, () -> {
                            sendPaidReaction(messageObject, chatActivity, totalStars, true, true, peer);
                        }, 0).show();
                    }

                    invalidateTransactions(false);
                    invalidateBalance();
                }
            }));
        }
    }

    public StarsController.PendingPaidReactions sendPaidReaction(MessageObject messageObject, ChatActivity chatActivity) {
        return sendPaidReaction(messageObject, chatActivity, +1, true, true, null);
    }

    public Context getContext(BaseFragment fragment) {
        if (fragment != null && fragment.getContext() != null)
            return fragment.getContext();
        if (LaunchActivity.instance != null && !LaunchActivity.instance.isFinishing())
            return LaunchActivity.instance;
        if (ApplicationLoader.applicationContext != null)
            return ApplicationLoader.applicationContext;
        return null;
    }

    public StarsController.PendingPaidReactions sendPaidReaction(
        MessageObject messageObject,
        ChatActivity chatActivity,
        long amount,
        boolean affect,
        boolean checkBalance,
        Long peer
    ) {
        final MessageId key = MessageId.from(messageObject);
        final StarsController s = StarsController.getInstance(currentAccount);
        final long totalStars = amount;
        final Context context = getContext(chatActivity);
        if (context == null) return null;
        if (checkBalance && s.balanceAvailable() && s.getBalance(false) <= 0) {
            final long dialogId = chatActivity.getDialogId();
            String name;
            if (dialogId >= 0) {
                TLRPC.User user = chatActivity.getMessagesController().getUser(dialogId);
                name = UserObject.getForcedFirstName(user);
            } else {
                TLRPC.Chat chat = chatActivity.getMessagesController().getChat(-dialogId);
                name = chat == null ? "" : chat.title;
            }
            if (context == null) return null;
            new StarsIntroActivity.StarsNeededSheet(context, chatActivity.getResourceProvider(), totalStars, StarsIntroActivity.StarsNeededSheet.TYPE_REACTIONS, name, () -> {
                sendPaidReaction(messageObject, chatActivity, totalStars, true, true, peer);
            }, 0).show();
            return null;
        }
        if (currentPendingReactions == null || !currentPendingReactions.message.equals(key)) {
            if (currentPendingReactions != null) {
                currentPendingReactions.close();
            }
            currentPendingReactions = new PendingPaidReactions(key, messageObject, chatActivity, ConnectionsManager.getInstance(currentAccount).getCurrentTime(), affect);
            currentPendingReactions.peer = peer;
        }
        if (currentPendingReactions.amount + amount > MessagesController.getInstance(currentAccount).starsPaidReactionAmountMax) {
            currentPendingReactions.close();
            currentPendingReactions = new PendingPaidReactions(key, messageObject, chatActivity, ConnectionsManager.getInstance(currentAccount).getCurrentTime(), affect);
        }
        final long totalStars2 = currentPendingReactions.amount + amount;
        if (checkBalance && s.balanceAvailable() && s.getBalance(false) < totalStars2) {
            currentPendingReactions.cancel();
            final long dialogId = chatActivity.getDialogId();
            String name;
            if (dialogId >= 0) {
                TLRPC.User user = chatActivity.getMessagesController().getUser(dialogId);
                name = UserObject.getForcedFirstName(user);
            } else {
                TLRPC.Chat chat = chatActivity.getMessagesController().getChat(-dialogId);
                name = chat == null ? "" : chat.title;
            }
            new StarsIntroActivity.StarsNeededSheet(context, chatActivity.getResourceProvider(), totalStars2, StarsIntroActivity.StarsNeededSheet.TYPE_REACTIONS, name, () -> {
                sendPaidReaction(messageObject, chatActivity, totalStars2, true, true, peer);
            }, 0).show();
            return null;
        }
        currentPendingReactions.add(amount, (messageObject != null && !messageObject.doesPaidReactionExist()) || affect);
        currentPendingReactions.peer = peer;
        return currentPendingReactions;
    }

    public void undoPaidReaction() {
        if (currentPendingReactions != null) {
            currentPendingReactions.cancel();
        }
    }

    public void commitPaidReaction() {
        if (currentPendingReactions != null) {
            currentPendingReactions.close();
        }
    }

    public boolean hasPendingPaidReactions(MessageObject messageObject) {
        if (currentPendingReactions == null) return false;
        if (messageObject == null) return false;
        final MessageId key = MessageId.from(messageObject);
        if (currentPendingReactions.message.did != key.did || currentPendingReactions.message.mid != key.mid) return false;
        if (!currentPendingReactions.applied) return false;
        return true;
    }

    public long getPendingPaidReactions(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) return 0;
        if ((messageObject.messageOwner.isThreadMessage || messageObject.isForwardedChannelPost()) && messageObject.messageOwner.fwd_from != null) {
            return getPendingPaidReactions(messageObject.getFromChatId(), messageObject.messageOwner.fwd_from.saved_from_msg_id);
        } else {
            return getPendingPaidReactions(messageObject.getDialogId(), messageObject.getId());
        }
    }

    public long getPendingPaidReactions(long dialogId, int messageId) {
        if (currentPendingReactions == null) return 0;
        if (currentPendingReactions.message.did != dialogId || currentPendingReactions.message.mid != messageId) return 0;
        if (!currentPendingReactions.applied) return 0;
        return currentPendingReactions.amount;
    }


    // ===== STAR GIFTS =====

    public boolean giftsLoading, giftsLoaded;
    private boolean giftsCacheLoaded;
    public int giftsHash;
    public long giftsRemoteTime;
    public final ArrayList<TL_stars.StarGift> gifts = new ArrayList<>();
    public final ArrayList<TL_stars.StarGift> sortedGifts = new ArrayList<>();
    public final ArrayList<TL_stars.StarGift> birthdaySortedGifts = new ArrayList<>();

    public void invalidateStarGifts() {
        giftsLoaded = false;
        giftsCacheLoaded = true;
        giftsRemoteTime = 0;
        loadStarGifts();
    }

    public void loadStarGifts() {
        if (giftsLoading || giftsLoaded && (System.currentTimeMillis() - giftsRemoteTime) < 1000 * 60) return;
        giftsLoading = true;

        if (!giftsCacheLoaded) {
            getStarGiftsCached((giftsCached, hash, time, users, chats) -> {
                MessagesController.getInstance(currentAccount).putUsers(users, true);
                MessagesController.getInstance(currentAccount).putChats(chats, true);

                giftsCacheLoaded = true;
                gifts.clear();
                gifts.addAll(giftsCached);
                birthdaySortedGifts.clear();
                birthdaySortedGifts.addAll(gifts);
                Collections.sort(birthdaySortedGifts, Comparator.comparingInt((TL_stars.StarGift a) -> (a.sold_out ? 1 : 0)).thenComparingInt((TL_stars.StarGift a) -> (a.birthday ? -1 : 0)));
                sortedGifts.clear();
                sortedGifts.addAll(gifts);
                Collections.sort(sortedGifts, Comparator.comparingInt((TL_stars.StarGift a) -> (a.sold_out ? 1 : 0)));
                giftsHash = hash;
                giftsRemoteTime = time;
                giftsLoading = false;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starGiftsLoaded);

                loadStarGifts();
            });
        } else {
            getStarGiftsRemote(giftsHash, giftsRemote -> {
                giftsLoading = false;
                giftsLoaded = true;
                if (giftsRemote instanceof TL_stars.TL_starGifts) {
                    final TL_stars.TL_starGifts res = (TL_stars.TL_starGifts) giftsRemote;
                    MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                    MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                    gifts.clear();
                    gifts.addAll(res.gifts);
                    birthdaySortedGifts.clear();
                    birthdaySortedGifts.addAll(gifts);
                    Collections.sort(birthdaySortedGifts, Comparator.comparingInt((TL_stars.StarGift a) -> (a.sold_out ? 1 : 0)).thenComparingInt((TL_stars.StarGift a) -> (a.birthday ? -1 : 0)));
                    sortedGifts.clear();
                    sortedGifts.addAll(gifts);
                    Collections.sort(sortedGifts, Comparator.comparingInt((TL_stars.StarGift a) -> (a.sold_out ? 1 : 0)));
                    giftsHash = res.hash;
                    giftsRemoteTime = System.currentTimeMillis();
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starGiftsLoaded);
                    saveStarGiftsCached(res.gifts, giftsHash, giftsRemoteTime);
                } else if (giftsRemote instanceof TL_stars.TL_starGiftsNotModified) {
                    saveStarGiftsCached(gifts, giftsHash, giftsRemoteTime = System.currentTimeMillis());
                }
            });
        }
    }

    public void makeStarGiftSoldOut(TL_stars.StarGift starGift) {
        if (starGift == null || !giftsLoaded) return;
        starGift.availability_remains = 0;
        saveStarGiftsCached(gifts, giftsHash, giftsRemoteTime);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starGiftSoldOut, starGift);
    }

    private void getStarGiftsCached(Utilities.Callback5<ArrayList<TL_stars.StarGift>, Integer, Long, ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>> whenDone) {
        if (whenDone == null) return;
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final ArrayList<TL_stars.StarGift> result = new ArrayList<>();
        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            final SQLiteDatabase db = storage.getDatabase();
            int hash = 0;
            long time = 0;
            SQLiteCursor cursor = null;
            try {
                cursor = db.queryFinalized("SELECT data, hash, time FROM star_gifts2 ORDER BY pos ASC");
                while (cursor.next()) {
                    final NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        TL_stars.StarGift gift = TL_stars.StarGift.TLdeserialize(data, data.readInt32(false), false);
                        if (gift != null) {
                            result.add(gift);
                        }
                        data.reuse();
                        hash = (int) cursor.longValue(1);
                        time = cursor.longValue(2);
                    }
                }


                final ArrayList<Long> usersToLoad = new ArrayList<>();
                final ArrayList<Long> chatsToLoad = new ArrayList<>();
                for (TL_stars.StarGift starGift : result) {
                    if (starGift.released_by != null) {
                        final long dialogId = DialogObject.getPeerDialogId(starGift.released_by);
                        if (dialogId > 0) {
                            usersToLoad.add(dialogId);
                        } else if (dialogId < 0) {
                            chatsToLoad.add(-dialogId);
                        }
                    }
                }

                if (!chatsToLoad.isEmpty()) {
                    storage.getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                }
                if (!usersToLoad.isEmpty()) {
                    storage.getUsersInternal(usersToLoad, users);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            final int finalHash = hash;
            final long finalTime = time;
            AndroidUtilities.runOnUIThread(() -> {
                whenDone.run(result, finalHash, finalTime, users, chats);
            });
        });
    }
    private void saveStarGiftsCached(ArrayList<TL_stars.StarGift> gifts, int hash, long time) {
        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            final SQLiteDatabase db = storage.getDatabase();
            SQLitePreparedStatement state = null;
            try {
                db.executeFast("DELETE FROM star_gifts2").stepThis().dispose();
                if (gifts != null) {
                    state = db.executeFast("REPLACE INTO star_gifts2 VALUES(?, ?, ?, ?, ?)");
                    for (int i = 0; i < gifts.size(); ++i) {
                        final TL_stars.StarGift gift = gifts.get(i);
                        state.requery();
                        state.bindLong(1, gift.id);
                        NativeByteBuffer data = new NativeByteBuffer(gift.getObjectSize());
                        gift.serializeToStream(data);
                        state.bindByteBuffer(2, data);
                        state.bindLong(3, hash);
                        state.bindLong(4, time);
                        state.bindInteger(5, i);
                        state.step();
                        data.reuse();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }
        });
    }
    private void getStarGiftsRemote(int hash, Utilities.Callback<TL_stars.StarGifts> whenDone) {
        if (whenDone == null) return;
        TL_stars.getStarGifts req = new TL_stars.getStarGifts();
        req.hash = hash;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
           if (res instanceof TL_stars.StarGifts) {
               whenDone.run((TL_stars.StarGifts) res);
           } else {
               whenDone.run(null);
           }
        }));
    }

    @Nullable
    public TL_stars.StarGift getStarGift(long gift_id) {
        loadStarGifts();
        for (int i = 0; i < gifts.size(); ++i) {
            final TL_stars.StarGift gift = gifts.get(i);
            if (gift.id == gift_id)
                return gift;
        }
        return null;
    }

    public Runnable getStarGift(long gift_id, Utilities.Callback<TL_stars.StarGift> whenDone) {
//        final AlertDialog progressDialog = new AlertDialog(ApplicationLoader.applicationContext, AlertDialog.ALERT_TYPE_SPINNER);
//        progressDialog.showDelayed(500);

        final boolean[] done = new boolean[] { false };
        NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        observer[0] = (id, account, args) -> {
            if (done[0]) return;
            if (id == NotificationCenter.starGiftsLoaded) {
                TL_stars.StarGift gift = getStarGift(gift_id);
                if (gift != null) {
//                    progressDialog.dismissUnless(500);
                    done[0] = true;
                    NotificationCenter.getInstance(currentAccount).removeObserver(observer[0], NotificationCenter.starGiftsLoaded);
                    whenDone.run(gift);
                }
            }
        };
        NotificationCenter.getInstance(currentAccount).addObserver(observer[0], NotificationCenter.starGiftsLoaded);
        TL_stars.StarGift gift = getStarGift(gift_id);
        if (gift != null) {
            done[0] = true;
//            progressDialog.dismissUnless(500);
            NotificationCenter.getInstance(currentAccount).removeObserver(observer[0], NotificationCenter.starGiftsLoaded);
            whenDone.run(gift);
        }
        return () -> {
            done[0] = true;
//            progressDialog.dismissUnless(500);
            NotificationCenter.getInstance(currentAccount).removeObserver(observer[0], NotificationCenter.starGiftsLoaded);
        };
    }



    public void buyStarGift(TL_stars.StarGift gift, boolean anonymous, boolean upgraded, long dialogId, TLRPC.TL_textWithEntities text, Utilities.Callback2<Boolean, String> whenDone) {
        if (whenDone != null) AndroidUtilities.runOnUIThread(() -> whenDone.run(false, "MONOGRAM_FEATURE_UNAVAILABLE"));
    }

    @Deprecated
    public void getResellingGiftForm(TL_stars.StarGift gift, long dialogId, Utilities.Callback<TLRPC.TL_payments_paymentFormStarGift> whenDone) {
        getResellingGiftForm(gift, dialogId, null, true, whenDone);
    }

    public void getResellingGiftForm(TL_stars.StarGift gift, long dialogId, TLRPC.TL_textWithEntities message, boolean hideMyName, Utilities.Callback<TLRPC.TL_payments_paymentFormStarGift> whenDone) {
        if (whenDone != null) AndroidUtilities.runOnUIThread(() -> whenDone.run(null));
    }

    public static long getFormStarsPrice(TLRPC.PaymentForm form) {
        long stars = 0;
        if (form != null) {
            for (TLRPC.TL_labeledPrice price : form.invoice.prices) {
                stars += price.amount;
            }
        }
        return stars;
    }

    @Deprecated
    public void buyResellingGift(TLRPC.TL_payments_paymentFormStarGift form, TL_stars.StarGift gift, long dialogId, Utilities.Callback2<Boolean, String> whenDone) {
        buyResellingGift(form, gift, dialogId, null, true, whenDone);
    }

    public void buyResellingGift(TLRPC.TL_payments_paymentFormStarGift form, TL_stars.StarGift gift, long dialogId, TLRPC.TL_textWithEntities message, boolean hideMyName, Utilities.Callback2<Boolean, String> whenDone) {
        if (whenDone != null) AndroidUtilities.runOnUIThread(() -> whenDone.run(false, "MONOGRAM_FEATURE_UNAVAILABLE"));
    }

    public final LongSparseArray<GiftsCollections> giftCollections = new LongSparseArray<>();
    public final LongSparseArray<GiftsList> giftLists = new LongSparseArray<>();
    public GiftsList getProfileGiftsList(long dialogId) {
        return getProfileGiftsList(dialogId, true);
    }
    public GiftsList getProfileGiftsList(long dialogId, boolean create) {
        GiftsList list = giftLists.get(dialogId);
        if (list == null && create) {
            giftLists.put(dialogId, list = new GiftsList(currentAccount, dialogId));
        }
        return list;
    }
    public GiftsCollections getProfileGiftCollectionsList(long dialogId, boolean create) {
        GiftsCollections list = giftCollections.get(dialogId);
        if (list == null && create) {
            giftCollections.put(dialogId, list = new GiftsCollections(currentAccount, dialogId));
        }
        return list;
    }

    public void invalidateProfileGifts(long dialogId) {
        GiftsList list = getProfileGiftsList(dialogId, false);
        if (list != null) {
            list.invalidate(false);
        }
        GiftsCollections collections = giftCollections.get(dialogId);
        if (collections != null) {
            collections.invalidate(false);
        }
    }

    public void invalidateProfileGifts(TLRPC.UserFull userFull) {
        if (userFull == null) return;
        long dialogId = userFull.id;
        GiftsList list = getProfileGiftsList(dialogId, false);
        if (list != null && list.totalCount != userFull.stargifts_count) {
            list.invalidate(false);
        }
        GiftsCollections collections = giftCollections.get(dialogId);
        if (collections != null) {
            collections.invalidate(false);
        }
    }

    public static class GiftsCollections {

        public final int currentAccount;
        public final long dialogId;

        public GiftsCollections(int currentAccount, long dialogId) {
            this(currentAccount, dialogId, true);
        }
        public GiftsCollections(int currentAccount, long dialogId, boolean load) {
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
            if (load) load();
        }

        public boolean loading;
        public boolean loaded;
        private ArrayList<TL_stars.TL_starGiftCollection> collections = new ArrayList<>();
        private ArrayList<TL_stars.TL_starGiftCollection> filteredCollections = new ArrayList<>();
        public GiftsList all;
        public HashMap<Integer, GiftsList> gifts = new HashMap<>();
        public int currentRequestId = -1;

        public boolean isMine() {
            if (dialogId >= 0)
                return dialogId == 0 || dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
            return ChatObject.canUserDoAction(MessagesController.getInstance(currentAccount).getChat(-dialogId), ChatObject.ACTION_POST);
        }

        public ArrayList<TL_stars.TL_starGiftCollection> getCollections() {
            return isMine() ? collections : filteredCollections;
        }

        private void refilterCollections() {
            filteredCollections.clear();
            for (int i = 0; i < collections.size(); ++i) {
                final TL_stars.TL_starGiftCollection collection = collections.get(i);
                if (collection.gifts_count <= 0)
                    continue;
                filteredCollections.add(collection);
            }
        }

        public void updateGiftsCollections(TL_stars.SavedStarGift gift, int collection_id, boolean included) {
            for (GiftsList list : gifts.values()) {
                list.updateGiftsCollections(gift, collection_id, included);
            }
            if (all != null) {
                all.updateGiftsCollections(gift, collection_id, included);
            }
        }

        public void updateGiftsUnsaved(TL_stars.SavedStarGift gift, boolean unsaved) {
            for (GiftsList list : gifts.values()) {
                list.updateGiftsUnsaved(gift, unsaved);
            }
            if (all != null) {
                all.updateGiftsUnsaved(gift, unsaved);
            }
        }

        private long getHash(ArrayList<TL_stars.TL_starGiftCollection> array) {
            long hash = 0;
            for (TL_stars.TL_starGiftCollection collection : array) {
                hash = calcHash(hash, collection.hash);
            }
            return hash;
        }

        public GiftsList getListByIndex(int index) {
            if (index < 0 || index >= getCollections().size())
                return null;
            final TL_stars.TL_starGiftCollection collection = getCollections().get(index);
            return getListById(collection.collection_id);
        }

        public GiftsList getListById(int collection_id) {
            return gifts.get(collection_id);
        }

        public void load() {
            if (loading || loaded) return;

            loading = true;

            final TL_stars.getStarGiftCollections req = new TL_stars.getStarGiftCollections();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.hash = getHash(collections);
            currentRequestId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (res instanceof TL_stars.TL_starGiftCollections) {
                    final TL_stars.TL_starGiftCollections r = (TL_stars.TL_starGiftCollections) res;

                    collections.clear();
                    collections.addAll(r.collections);
                    refilterCollections();

                    for (TL_stars.TL_starGiftCollection collection : collections) {
                        GiftsList list = getListById(collection.collection_id);
                        if (list != null) continue;
                        list = new GiftsList(currentAccount, dialogId, false);
                        list.setCollectionId(collection.collection_id);
                        gifts.put(collection.collection_id, list);
                    }

                    loaded = true;
                    loading = false;

                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftCollectionsLoaded, dialogId, GiftsCollections.this);

                } else if (res instanceof TL_stars.TL_starGiftCollectionsNotModified) {
                    refilterCollections();

                    loaded = true;
                    loading = false;

                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftCollectionsLoaded, dialogId, GiftsCollections.this);
                }
            }));
        }

        public boolean shown;

        public void invalidate(boolean load) {
            if (currentRequestId != -1) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(currentRequestId, true);
                currentRequestId = -1;
            }
            loading = false;
            loaded = false;
            if (load || shown) load();
        }

        public boolean creating;
        public void createCollection(String title, Utilities.Callback<TL_stars.TL_starGiftCollection> created) {
            if (creating) return;

            creating = true;

            final TL_stars.TL_starGiftCollection tempCollection = new TL_stars.TL_starGiftCollection();
            tempCollection.collection_id = -1;
            tempCollection.title = title;
            collections.add(tempCollection);
            refilterCollections();

            final GiftsList list = new GiftsList(currentAccount, dialogId, false);
            list.setCollectionId(-1);
            list.totalCount = 0;
            list.endReached = true;
            gifts.put(-1, list);

            final TL_stars.createStarGiftCollection req = new TL_stars.createStarGiftCollection();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.title = title;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                creating = false;
                if (res instanceof TL_stars.TL_starGiftCollection) {
                    final TL_stars.TL_starGiftCollection loaded = (TL_stars.TL_starGiftCollection) res;
                    collections.remove(tempCollection);
                    collections.add(loaded);
                    gifts.remove(-1);
                    list.collectionId = loaded.collection_id;
                    gifts.put(loaded.collection_id, list);
                    refilterCollections();

                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftCollectionsLoaded, dialogId, GiftsCollections.this);

                    if (created != null) {
                        created.run(loaded);
                    }
                } else {
                    if (err != null) {
                        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            BulletinFactory.of(lastFragment).showForError(err);
                        }
                    }
                    collections.remove(tempCollection);
                    gifts.remove(-1);
                    refilterCollections();

                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftCollectionsLoaded, dialogId, GiftsCollections.this);
                }
            }));
        }

        public TL_stars.TL_starGiftCollection findById(int id) {
            for (int i = 0; i < collections.size(); ++i) {
                TL_stars.TL_starGiftCollection collection = collections.get(i);
                if (id == collection.collection_id) {
                    return collection;
                }
            }
            return null;
        }

        public int indexOf(int id) {
            for (int i = 0; i < collections.size(); ++i) {
                final TL_stars.TL_starGiftCollection collection = collections.get(i);
                if (id == collection.collection_id) {
                    return i;
                }
            }
            return -1;
        }

        public void removeCollection(int id) {
            final int index = indexOf(id);
            if (index == -1) return;

            final TL_stars.TL_starGiftCollection collection = collections.remove(index);
            gifts.remove(collection.collection_id);

            final TL_stars.deleteStarGiftCollection req = new TL_stars.deleteStarGiftCollection();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.collection_id = collection.collection_id;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
        }

        public void updateIcon(int id) {
            final GiftsList list = getListById(id);
            final TL_stars.TL_starGiftCollection collection = findById(id);
            if (list == null || collection == null) return;
            final TL_stars.SavedStarGift firstGift = list.gifts.isEmpty() ? null : list.gifts.get(0);
            if (firstGift == null) {
                collection.flags &=~ 1;
                collection.icon = null;
            } else {
                collection.flags |= 1;
                collection.icon = firstGift.gift.getDocument();
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftCollectionsLoaded, dialogId, this);
        }

        public void rename(int id, String newName) {
            final TL_stars.updateStarGiftCollection req = new TL_stars.updateStarGiftCollection();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.collection_id = id;
            req.flags |= 1;
            req.title = newName;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
        }

        public void addGift(int id, TL_stars.SavedStarGift gift, boolean insert) {
            final ArrayList<TL_stars.SavedStarGift> gifts = new ArrayList<>();
            gifts.add(gift);
            addGifts(id, gifts, insert);
        }

        public void addGifts(int id, ArrayList<TL_stars.SavedStarGift> gifts, boolean insert) {
            if (gifts.isEmpty()) return;
            final GiftsList list = getListById(id);
            if (list != null && insert) {
                list.gifts.addAll(0, gifts);
                list.totalCount += gifts.size();
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, list);
                updateIcon(id);
            }
            final TL_stars.updateStarGiftCollection req = new TL_stars.updateStarGiftCollection();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.collection_id = id;
            req.flags |= 4;
            for (TL_stars.SavedStarGift gift : gifts) {
                updateGiftsCollections(gift, id, true);
                if (gift.msg_id > 0) {
                    final TL_stars.TL_inputSavedStarGiftUser inputGift = new TL_stars.TL_inputSavedStarGiftUser();
                    inputGift.msg_id = gift.msg_id;
                    req.add_stargift.add(inputGift);
                } else if (gift.saved_id != 0) {
                    final TL_stars.TL_inputSavedStarGiftChat inputGift = new TL_stars.TL_inputSavedStarGiftChat();
                    inputGift.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                    inputGift.saved_id = gift.saved_id;
                    req.add_stargift.add(inputGift);
                } else {
                    FileLog.w("can't convert gift to inputgift to add into the collection");
                }
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (res instanceof TL_stars.TL_starGiftCollection) {
                    final TL_stars.TL_starGiftCollection loaded = (TL_stars.TL_starGiftCollection) res;
                    int index = indexOf(loaded.collection_id);
                    if (index >= 0) {
                        collections.set(index, loaded);
                    }
                }
            }));
        }

        public void removeGift(int id, final TL_stars.SavedStarGift gift) {
            final ArrayList<TL_stars.SavedStarGift> gifts = new ArrayList<>();
            gifts.add(gift);
            removeGifts(id, gifts);
        }

        public void removeGifts(int id, final ArrayList<TL_stars.SavedStarGift> gifts) {
            if (gifts.isEmpty()) return;
            final GiftsList list = getListById(id);
            if (list != null && !list.gifts.isEmpty()) {
                for (int i = 0; i < list.gifts.size(); ++i) {
                    final TL_stars.SavedStarGift g = list.gifts.get(i);
                    boolean remove = false;
                    for (int j = 0; j < gifts.size(); ++j) {
                        if (eq(g, gifts.get(j))) {
                            remove = true;
                            break;
                        }
                    }
                    if (remove) {
                        list.gifts.remove(i);
                        list.totalCount = Math.max(0, list.totalCount - 1);
                        i--;
                    }
                }
            }
            updateIcon(id);
            final TL_stars.updateStarGiftCollection req = new TL_stars.updateStarGiftCollection();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.collection_id = id;
            req.flags |= 2;
            for (TL_stars.SavedStarGift gift : gifts) {
                updateGiftsCollections(gift, id, false);
                if (gift.msg_id > 0) {
                    final TL_stars.TL_inputSavedStarGiftUser inputGift = new TL_stars.TL_inputSavedStarGiftUser();
                    inputGift.msg_id = gift.msg_id;
                    req.delete_stargift.add(inputGift);
                } else if (gift.saved_id != 0) {
                    final TL_stars.TL_inputSavedStarGiftChat inputGift = new TL_stars.TL_inputSavedStarGiftChat();
                    inputGift.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                    inputGift.saved_id = gift.saved_id;
                    req.delete_stargift.add(inputGift);
                } else {
                    FileLog.w("can't convert gift to inputgift to add into the collection");
                }
            }
            final int count = req.delete_stargift.size();
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (res instanceof TL_stars.TL_starGiftCollection) {
                    final TL_stars.TL_starGiftCollection loaded = (TL_stars.TL_starGiftCollection) res;
                    int index = indexOf(loaded.collection_id);
                    if (index >= 0) {
                        collections.set(index, loaded);
                    }
                }
            }));
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, list);
        }

        public void reorder(ArrayList<Integer> collectionIds) {
            final HashMap<Integer, TL_stars.TL_starGiftCollection> map = new HashMap<>();
            for (final TL_stars.TL_starGiftCollection collection : collections) {
                map.put(collection.collection_id, collection);
            }
            final ArrayList<TL_stars.TL_starGiftCollection> newCollections = new ArrayList<>();
            for (final int id : collectionIds) {
                final TL_stars.TL_starGiftCollection collection = map.get(id);
                if (collection != null) {
                    newCollections.add(collection);
                }
            }

            collections.clear();
            collections.addAll(newCollections);
            refilterCollections();
        }

        public void sendOrder() {
            final TL_stars.reorderStarGiftCollections req = new TL_stars.reorderStarGiftCollections();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            for (final TL_stars.TL_starGiftCollection collection : collections) {
                req.order.add(collection.collection_id);
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
            refilterCollections();
        }
    }

    public interface IGiftsList {
        void notifyUpdate();

        int getLoadedCount();
        void set(int index, Object obj);
        Object get(int index);
        int indexOf(Object object);
        int getTotalCount();
        void load();

        int findGiftToUpgrade(int from);
    }

    public static class GiftsList implements IGiftsList {

        public final int currentAccount;
        public final long dialogId;

        public GiftsList(int currentAccount, long dialogId) {
            this(currentAccount, dialogId, true);
        }
        public GiftsList(int currentAccount, long dialogId, boolean load) {
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
            if (load) load();
        }

        public boolean isCollection = false;
        public int collectionId;
        public void setCollectionId(int collection_id) {
            isCollection = true;
            collectionId = collection_id;
        }

        @Override
        public void notifyUpdate() {
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, GiftsList.this);
        }

        public void updateGiftsCollections(TL_stars.SavedStarGift gift, int collection_id, boolean included) {
            for (final TL_stars.SavedStarGift g : gifts) {
                if (StarsController.eq(g, gift)) {
                    if (included) {
                        if (!g.collection_id.contains(collection_id))
                            g.collection_id.add((Integer) collection_id);
                    } else {
                        g.collection_id.remove((Integer) collection_id);
                    }
                }
            }
        }

        public void updateGiftsUnsaved(TL_stars.SavedStarGift gift, boolean unsaved) {
            boolean changed = false;
            for (final TL_stars.SavedStarGift g : gifts) {
                if (StarsController.eq(g, gift) && g.unsaved != unsaved) {
                    g.unsaved = unsaved;
                    changed = true;
                }
            }
            if (changed) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, GiftsList.this);
            }
        }

        public int findGiftToUpgrade(int fromIndex) {
            if (!StarGiftSheet.isMineWithActions(currentAccount, dialogId)) return -1;
            for (int index = fromIndex + 1; index < gifts.size(); ++index) {
                final TL_stars.SavedStarGift gift = gifts.get(index);
                if (gift.can_upgrade) {
                    return index;
                }
            }
            for (int index = fromIndex - 1; index >= 0; --index) {
                final TL_stars.SavedStarGift gift = gifts.get(index);
                if (gift.can_upgrade) {
                    return index;
                }
            }
            return -1;
        }

        public void set(int index, Object obj) {
            if (obj instanceof TL_stars.SavedStarGift) {
                try {
                    this.gifts.set(index, (TL_stars.SavedStarGift) obj);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }

        public boolean sort_by_date = true; // false => sort_by_value
        public boolean peer_color_available = false;

        public static final int INCLUDE_TYPE_UNLIMITED_FLAG = 1;
        public static final int INCLUDE_TYPE_LIMITED_FLAG = 1 << 1;
        public static final int INCLUDE_TYPE_UPGRADABLE_FLAG = 1 << 2;
        public static final int INCLUDE_TYPE_UNIQUE_FLAG = 1 << 3;
        private static final int INCLUDE_TYPE_MASK = INCLUDE_TYPE_UNLIMITED_FLAG | INCLUDE_TYPE_LIMITED_FLAG | INCLUDE_TYPE_UPGRADABLE_FLAG | INCLUDE_TYPE_UNIQUE_FLAG;

        public static final int INCLUDE_VISIBILITY_DISPLAYED_FLAG = 1 << 8;
        public static final int INCLUDE_VISIBILITY_HIDDEN_FLAG = 1 << 9;
        private static final int INCLUDE_VISIBILITY_MASK = INCLUDE_VISIBILITY_DISPLAYED_FLAG | INCLUDE_VISIBILITY_HIDDEN_FLAG;

        private int includeFlags = INCLUDE_TYPE_MASK | INCLUDE_VISIBILITY_MASK;

        private int getMask(int flag) {
            if ((flag & INCLUDE_TYPE_MASK) != 0) {
                return INCLUDE_TYPE_MASK;
            }
            if ((flag & INCLUDE_VISIBILITY_MASK) != 0) {
                return INCLUDE_VISIBILITY_MASK;
            }
            return 0;
        }

        public void forceTypeIncludeFlag(int flag, boolean invalidate) {
            final int mask = getMask(flag);

            int newFlags = includeFlags & ~mask | flag;
            if (this.includeFlags != newFlags) {
                this.includeFlags = newFlags;
                if (invalidate) {
                    invalidate(true);
                }
            }
        }

        public void toggleTypeIncludeFlag(int flag) {
            final int mask = getMask(flag);

            int flags = includeFlags & mask;
            flags = TLObject.setFlag(flags, flag, !TLObject.hasFlag(flags, flag));
            if (flags == 0) {
                flags = mask & ~flag;
            }

            int newFlags = includeFlags & ~mask | flags;

            if (this.includeFlags != newFlags) {
                this.includeFlags = newFlags;
                invalidate(true);
            }
        }

        public Boolean chat_notifications_enabled;

        public void resetFilters() {
            if (!hasFilters()) return;
            includeFlags = INCLUDE_TYPE_MASK | INCLUDE_VISIBILITY_MASK;
            sort_by_date = true;
            invalidate(true);
        }

        public void setFilters(int flags) {
            includeFlags = flags;
            sort_by_date = true;
            invalidate(true);
        }

        public boolean hasFilters() {
            return !(sort_by_date && includeFlags == (INCLUDE_TYPE_MASK | INCLUDE_VISIBILITY_MASK));
        }

        public boolean isInclude_unlimited() {
            return TLObject.hasFlag(includeFlags, INCLUDE_TYPE_UNLIMITED_FLAG);
        }

        public boolean isInclude_limited() {
            return TLObject.hasFlag(includeFlags, INCLUDE_TYPE_LIMITED_FLAG);
        }

        public boolean isInclude_upgradable() {
            return TLObject.hasFlag(includeFlags, INCLUDE_TYPE_UPGRADABLE_FLAG);
        }

        public boolean isInclude_unique() {
            return TLObject.hasFlag(includeFlags, INCLUDE_TYPE_UNIQUE_FLAG);
        }

        public boolean isInclude_displayed() {
            return TLObject.hasFlag(includeFlags, INCLUDE_VISIBILITY_DISPLAYED_FLAG);
        }

        public boolean isInclude_hidden() {
            return TLObject.hasFlag(includeFlags, INCLUDE_VISIBILITY_HIDDEN_FLAG);
        }

        public boolean loading;
        public boolean endReached;
        public String lastOffset;
        public ArrayList<TL_stars.SavedStarGift> gifts = new ArrayList<>();
        public int currentRequestId = -1;
        public int totalCount;

        public int getTotalCount() {
            return totalCount;
        }

        public int getLoadedCount() {
            return gifts.size();
        }

        public Object get(int index) {
            if (index < 0 || index >= gifts.size())
                return null;
            return gifts.get(index);
        }

        public int indexOf(Object object) {
            return gifts.indexOf(object);
        }

        public boolean shown;

        public void invalidate(boolean load) {
            if (currentRequestId != -1) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(currentRequestId, true);
                currentRequestId = -1;
            }
            loading = false;
            gifts.clear();
            lastOffset = null;
            endReached = false;
            if (load || shown) load();
        }

        private long craftingGiftId = 0;
        public void forCrafting(long gift_id) {
            craftingGiftId = gift_id;
        }

        public void load() {
            if (loading || endReached) return;

            boolean first = lastOffset == null;
            loading = true;
            final TLObject request;
            if (craftingGiftId != 0) {
                final TL_stars.getCraftStarGifts req = new TL_stars.getCraftStarGifts();
                req.gift_id = craftingGiftId;
                req.offset = first ? "" : lastOffset;
                req.limit = first ? 15 : 30;
                request = req;
            } else {
                final TL_stars.getSavedStarGifts req = new TL_stars.getSavedStarGifts();
                req.sort_by_value = !sort_by_date;
                req.exclude_unupgradable = !isInclude_limited();
                req.exclude_upgradable = !isInclude_upgradable();
                req.exclude_unlimited = !isInclude_unlimited();
                req.exclude_unique = !isInclude_unique();
                req.exclude_saved = !isInclude_displayed();
                req.exclude_unsaved = !isInclude_hidden();
                req.peer_color_available = peer_color_available;
                if (dialogId == 0) {
                    req.peer = new TLRPC.TL_inputPeerSelf();
                } else {
                    req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                }
                req.offset = first ? "" : lastOffset;
                req.limit = first ? Math.max(MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit, 15) : 30;
                if (isCollection) {
                    req.flags |= 64;
                    req.collection_id = collectionId;
                }
                request = req;
            }
            final int[] reqId = new int[1];
            reqId[0] = currentRequestId = ConnectionsManager.getInstance(currentAccount).sendRequest(request, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (reqId[0] != currentRequestId) return;
                loading = false;
                currentRequestId = -1;
                if (res instanceof TL_stars.TL_payments_savedStarGifts) {
                    final TL_stars.TL_payments_savedStarGifts rez = (TL_stars.TL_payments_savedStarGifts) res;
                    MessagesController.getInstance(currentAccount).putUsers(rez.users, false);
                    MessagesController.getInstance(currentAccount).putChats(rez.chats, false);

                    if (first) {
                        gifts.clear();
                    }
                    gifts.addAll(rez.gifts);
                    lastOffset = rez.next_offset;
                    totalCount = rez.count;
                    chat_notifications_enabled = (rez.flags & 2) != 0 ? rez.chat_notifications_enabled : null;
                    endReached = gifts.size() > totalCount || lastOffset == null;
                } else {
                    endReached = true;
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, GiftsList.this);
            }));
        }

        public void cancel() {
            if (currentRequestId != -1) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(currentRequestId, true);
                currentRequestId = -1;
            }
            loading = false;
        }

        public void processCrafting(ArrayList<TL_stars.StarGift> giftsToRemove, TL_stars.StarGift giftToAdd) {
            if (giftsToRemove != null && !giftsToRemove.isEmpty()) {
                boolean changed = false;
                for (final TL_stars.StarGift gift : giftsToRemove) {
                    for (int i = 0; i < gifts.size(); ++i) {
                        final TL_stars.SavedStarGift savedGift = gifts.get(i);
                        if (savedGift.gift != null && savedGift.gift.id == gift.id) {
                            gifts.remove(i);
                            totalCount = Math.max(0, totalCount - 1);
                            changed = true;
                            break;
                        }
                    }
                }
                if (changed) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, GiftsList.this);
                }
            }
            if (giftToAdd != null) {
                final TL_stars.getSavedStarGift req = new TL_stars.getSavedStarGift();
                final TL_stars.TL_inputSavedStarGiftSlug input = new TL_stars.TL_inputSavedStarGiftSlug();
                input.slug = giftToAdd.slug;
                req.stargift.add(input);
                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                    if (res instanceof TL_stars.TL_payments_savedStarGifts) {
                        final TL_stars.TL_payments_savedStarGifts r = (TL_stars.TL_payments_savedStarGifts) res;
                        MessagesController.getInstance(currentAccount).putUsers(r.users, false);
                        MessagesController.getInstance(currentAccount).putChats(r.chats, false);
                        if (r.gifts.size() > 0) {
                            final TL_stars.SavedStarGift savedGift = r.gifts.get(0);
                            int index = 0;
                            while (index < gifts.size() && gifts.get(index).pinned_to_top) index++;
                            gifts.add(index, savedGift);
                            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, GiftsList.this);
                        }
                    }
                }));
            }
        }

        public int getCount() {
            return totalCount;
        }

        public ArrayList<TL_stars.SavedStarGift> getPinned() {
            final ArrayList<TL_stars.SavedStarGift> pinned = new ArrayList<>();
            for (int i = 0; i < gifts.size(); ++i) {
                final TL_stars.SavedStarGift gift = gifts.get(i);
                if (gift.pinned_to_top && !gift.unsaved) {
                    pinned.add(gift);
                }
            }
            return pinned;
        }

        public boolean eq(ArrayList<TL_stars.SavedStarGift> a, ArrayList<TL_stars.SavedStarGift> b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); ++i) {
                if (a.get(i) != b.get(i)) return false;
            }
            return true;
        }

        public TL_stars.InputSavedStarGift getInput(TL_stars.SavedStarGift gift) {
            if (gift == null) return null;
            if ((gift.flags & 8) != 0) {
                TL_stars.TL_inputSavedStarGiftUser input = new TL_stars.TL_inputSavedStarGiftUser();
                input.msg_id = gift.msg_id;
                return input;
            } else {
                TL_stars.TL_inputSavedStarGiftChat input = new TL_stars.TL_inputSavedStarGiftChat();
                input.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                input.saved_id = gift.saved_id;
                return input;
            }
        }

        public void setPinned(ArrayList<TL_stars.SavedStarGift> newPinned) {
            gifts.removeAll(newPinned);
            if (sort_by_date && !isCollection) {
                Collections.sort(gifts, (a, b) -> b.date - a.date);
            }
            gifts.addAll(0, newPinned);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, GiftsList.this);
            sendPinnedOrder();
        }

        public boolean togglePinned(TL_stars.SavedStarGift gift, boolean pin, boolean fixLimit) {
            if (gift == null) {
                return false;
            }
            boolean hitLimit = false;
            final ArrayList<TL_stars.SavedStarGift> pinned = getPinned();
            if (pinned.contains(gift)) {
                if (pin) {
                    return false;
                }
                pinned.remove(gift);
            } else {
                if (!pin) {
                    return false;
                }
                if (pinned.size() + 1 > MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit) {
                    if (fixLimit) {
                        hitLimit = true;
                        while (pinned.size() > 0 && pinned.size() + 1 > MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit) {
                            TL_stars.SavedStarGift pinnedGift = pinned.remove(pinned.size() - 1);
                            pinnedGift.pinned_to_top = false;
                        }
                    } else {
                        return true;
                    }
                }
                pinned.add(gift);
            }
            gift.pinned_to_top = pin;
            gifts.removeAll(pinned);
            if (sort_by_date && !isCollection) {
                Collections.sort(gifts, (a, b) -> b.date - a.date);
            }
            gifts.addAll(0, pinned);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.starUserGiftsLoaded, dialogId, GiftsList.this);
            sendPinnedOrder();
            return hitLimit;
        }

        private ArrayList<TL_stars.SavedStarGift> savedPinnedState;

        public void reorderPinned(int fromPosition, int toPosition) {
            if (savedPinnedState == null) {
                savedPinnedState = getPinned();
            }
            reorder(fromPosition, toPosition);
        }

        public void reorder(int fromPosition, int toPosition) {
            fromPosition = Utilities.clamp(fromPosition, gifts.size() - 1, 0);
            if (fromPosition < 0 || fromPosition >= gifts.size()) return;

            final TL_stars.SavedStarGift g = gifts.remove(fromPosition);

            toPosition = Utilities.clamp(toPosition, gifts.size() - 1, 0);
            if (toPosition < 0 || toPosition >= gifts.size()) return;

            gifts.add(toPosition, g);
        }

        public void reorderDone() {
            if (savedPinnedState == null || eq(savedPinnedState, getPinned())) {
                savedPinnedState = null;
                return;
            }
            sendPinnedOrder();
            savedPinnedState = null;
        }

        public void sendPinnedOrder() {
            if (isCollection) {
                final TL_stars.updateStarGiftCollection req = new TL_stars.updateStarGiftCollection();
                req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                req.collection_id = collectionId;
                req.flags |= 8;
                for (TL_stars.SavedStarGift g : gifts) {
                    req.order.add(getInput(g));
                }
                ConnectionsManager.getInstance(currentAccount).sendRequest(req, null, ConnectionsManager.RequestFlagInvokeAfter);
            } else {
                final TL_stars.toggleStarGiftsPinnedToTop req = new TL_stars.toggleStarGiftsPinnedToTop();
                req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                for (TL_stars.SavedStarGift pinnedGift : getPinned()) {
                    req.stargift.add(getInput(pinnedGift));
                }
                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {

                }, ConnectionsManager.RequestFlagInvokeAfter);
            }
        }

        public boolean contains(final TL_stars.SavedStarGift gift) {
            for (final TL_stars.SavedStarGift g : gifts) {
                if (StarsController.eq(g, gift)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean eq(final TL_stars.SavedStarGift a, final TL_stars.SavedStarGift b) {
        return (
            (a.flags & 2048) != 0 && (b.flags & 2048) != 0 && a.saved_id == b.saved_id ||
            (a.flags & 8) != 0 && (b.flags & 8) != 0 && a.msg_id == b.msg_id
        );
    }

    public TL_stars.SavedStarGift findUserStarGift(long collection_id) {
        for (int i = 0; i < giftLists.size(); ++i) {
            final GiftsList list = giftLists.valueAt(i);
            for (int j = 0; j < list.gifts.size(); ++j) {
                final TL_stars.SavedStarGift gift = list.gifts.get(j);
                if (gift != null && gift.gift != null && gift.gift.id == collection_id) {
                    return gift;
                }
            }
        }
        return null;
    }

    public static <T extends TL_stars.StarGiftAttribute> T findAttribute(ArrayList<TL_stars.StarGiftAttribute> attributes, Class<T> clazz) {
        if (attributes == null) {
            return null;
        }
        for (TL_stars.StarGiftAttribute attribute : attributes) {
            if (clazz.isInstance(attribute)) {
                return clazz.cast(attribute);
            }
        }
        return null;
    }

    public static <T extends TL_stars.StarGiftAttribute> ArrayList<T> findAttributes(ArrayList<TL_stars.StarGiftAttribute> attributes, Class<T> clazz) {
        final ArrayList<T> result = new ArrayList<>();
        for (TL_stars.StarGiftAttribute attribute : attributes) {
            if (clazz.isInstance(attribute)) {
                result.add(clazz.cast(attribute));
            }
        }
        return result;
    }

    private ConcurrentHashMap<Long, TL_stars.starGiftUpgradePreview> giftPreviews = new ConcurrentHashMap<>();

    public void getStarGiftPreview(long gift_id, Utilities.Callback<TL_stars.starGiftUpgradePreview> got) {
        if (got == null) return;
        TL_stars.starGiftUpgradePreview cached = giftPreviews.get(gift_id);
        if (cached != null) {
            got.run(cached);
            return;
        }

        TL_stars.getStarGiftUpgradePreview req = new TL_stars.getStarGiftUpgradePreview();
        req.gift_id = gift_id;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TL_stars.starGiftUpgradePreview) {
                giftPreviews.put(gift_id, (TL_stars.starGiftUpgradePreview) res);
                got.run((TL_stars.starGiftUpgradePreview) res);
            } else {
                got.run(null);
            }
        }));
    }

    public void getUserStarGift(TL_stars.InputSavedStarGift inputSavedStarGift, Utilities.Callback<TL_stars.SavedStarGift> got) {
        if (got == null) return;
        final AlertDialog progressDialog = new AlertDialog(ApplicationLoader.applicationContext, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.showDelayed(200);
        final TL_stars.getSavedStarGift req = new TL_stars.getSavedStarGift();
        req.stargift.add(inputSavedStarGift);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            progressDialog.dismiss();
            TL_stars.SavedStarGift upgradedGift = null;
            if (res instanceof TL_stars.TL_payments_savedStarGifts) {
                TL_stars.TL_payments_savedStarGifts r = (TL_stars.TL_payments_savedStarGifts) res;
                MessagesController.getInstance(currentAccount).putUsers(r.users, false);
                MessagesController.getInstance(currentAccount).putChats(r.chats, false);
                for (int i = 0; i < r.gifts.size(); ++i) {
                    TL_stars.SavedStarGift savedStarGift = r.gifts.get(i);
                    if (
                        inputSavedStarGift instanceof TL_stars.TL_inputSavedStarGiftUser && ((TL_stars.TL_inputSavedStarGiftUser) inputSavedStarGift).msg_id == savedStarGift.msg_id ||
                        inputSavedStarGift instanceof TL_stars.TL_inputSavedStarGiftChat && ((TL_stars.TL_inputSavedStarGiftChat) inputSavedStarGift).saved_id == savedStarGift.saved_id
                    ) {
                        upgradedGift = savedStarGift;
                        break;
                    }
                }
            }
            got.run(upgradedGift);
        }));
    }

    public void getPaidRevenue(long user_id, long parent_id, Utilities.Callback<Long> got) {
        final TL_account.getPaidMessagesRevenue req = new TL_account.getPaidMessagesRevenue();
        req.user_id = MessagesController.getInstance(currentAccount).getInputUser(user_id);
        if (parent_id != 0) {
            req.parent_peer = MessagesController.getInstance(currentAccount).getInputPeer(parent_id);
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TL_account.paidMessagesRevenue) {
                got.run(((TL_account.paidMessagesRevenue) res).stars_amount);
            } else {
                got.run(0L);
            }
        }));
    }

    public void stopPaidMessages(long user_id, long parent_id, boolean refund, boolean stop) {
        final TL_account.toggleNoPaidMessagesException req = new TL_account.toggleNoPaidMessagesException();
        req.user_id = MessagesController.getInstance(currentAccount).getInputUser(user_id);
        if (parent_id != 0) {
            req.parent_peer = MessagesController.getInstance(currentAccount).getInputPeer(parent_id);
        }
        req.refund_charged = refund;
        req.require_payment = !stop;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.TL_boolTrue) {
                if (parent_id != 0) {
                    processUpdateMonoForumNoPaidException(-parent_id, user_id, stop);
                } else {
                    TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(user_id);
                    if (userFull != null && userFull.settings != null) {
                        userFull.settings.flags &= ~16384;
                        userFull.settings.charge_paid_message_stars = 0;
                    }
                    MessagesController.getNotificationsSettings(currentAccount).edit().putLong("dialog_bar_paying_" + user_id, 0L).apply();
                    MessagesController.getInstance(currentAccount).loadPeerSettings(
                        MessagesController.getInstance(currentAccount).getUser(user_id),
                        MessagesController.getInstance(currentAccount).getChat(-user_id),
                        true
                    );
                    ContactsController.getInstance(currentAccount).loadPrivacySettings(true);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesFeeUpdated, user_id);
                }
            }
        }));
    }

    public void processUpdateMonoForumNoPaidException(long channelId, long userId, boolean nopaidMessagesException) {
        TopicsController topicsController = MessagesController.getInstance(currentAccount).getTopicsController();
        TLRPC.TL_forumTopic topic = topicsController.findTopic(channelId, userId);
        if (topic != null) {
            topic.nopaid_messages_exception = nopaidMessagesException;
            topicsController.saveTopics(channelId);

            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesFeeUpdated, userId);
        }
    }



    public static final long PAID_MESSAGES_TIMEOUT = 3_000;












    // returns true if request should be sent




    public static long getAllowedPaidStars(TLObject req) {
        if (req instanceof TLRPC.TL_messages_sendMessage) {
            return ((TLRPC.TL_messages_sendMessage) req).allow_paid_stars;
        } else if (req instanceof TLRPC.TL_messages_sendMultiMedia) {
            return ((TLRPC.TL_messages_sendMultiMedia) req).allow_paid_stars;
        } else if (req instanceof TLRPC.TL_messages_sendInlineBotResult) {
            return ((TLRPC.TL_messages_sendInlineBotResult) req).allow_paid_stars;
        } else if (req instanceof TLRPC.TL_messages_forwardMessages) {
            return ((TLRPC.TL_messages_forwardMessages) req).allow_paid_stars / ((TLRPC.TL_messages_forwardMessages) req).id.size();
        } else if (req instanceof TLRPC.TL_messages_sendMedia) {
            return ((TLRPC.TL_messages_sendMedia) req).allow_paid_stars;
        }
        return 0;
    }

    public static long getPeer(TLObject req) {
        if (req instanceof TLRPC.TL_messages_sendMessage) {
            return DialogObject.getPeerDialogId(((TLRPC.TL_messages_sendMessage) req).peer);
        } else if (req instanceof TLRPC.TL_messages_sendMultiMedia) {
            return DialogObject.getPeerDialogId(((TLRPC.TL_messages_sendMultiMedia) req).peer);
        } else if (req instanceof TLRPC.TL_messages_sendInlineBotResult) {
            return DialogObject.getPeerDialogId(((TLRPC.TL_messages_sendInlineBotResult) req).peer);
        } else if (req instanceof TLRPC.TL_messages_forwardMessages) {
            return DialogObject.getPeerDialogId(((TLRPC.TL_messages_forwardMessages) req).to_peer);
        } else if (req instanceof TLRPC.TL_messages_sendMedia) {
            return DialogObject.getPeerDialogId(((TLRPC.TL_messages_sendMedia) req).peer);
        }
        return 0;
    }

    public void showPriceChangedToast(List<MessageObject> msgs) {
        if (msgs == null || msgs.isEmpty()) return;
        final MessageObject msg = msgs.get(0);
        final long dialogId = msg.getDialogId();
        if (dialogId >= 0) {
            MessagesController.getInstance(currentAccount).loadFullUser(MessagesController.getInstance(currentAccount).getUser(dialogId), 0, true);
        } else {
            MessagesController.getInstance(currentAccount).loadFullChat(-dialogId, 0, true);
        }
        final CharSequence text = StarsIntroActivity.replaceStars(TextUtils.concat(
            LocaleController.formatPluralString("PaidMessagesSendErrorToast1", (int) msg.messageOwner.errorAllowedPriceStars),
            " ",
            LocaleController.formatPluralString("PaidMessagesSendErrorToast2", (int) msg.messageOwner.errorNewPriceStars)
        ));
        BulletinFactory.of(LaunchActivity.getSafeLastFragment())
            .createSimpleBulletin(R.raw.error, text)
            .show();
    }

    public static boolean isEnoughAmount(int currentAccount, AmountUtils.Amount amount) {
        if (amount == null) {
            return true;
        }

        AmountUtils.Amount balance = getInstance(currentAccount, amount.currency).getBalanceAmount();
        return balance.asNano() >= amount.asNano();
    }
}
