package org.telegram.ui.Stars;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.MediaDataController.calcHash;

import android.content.Context;
import android.text.TextUtils;
import android.util.LongSparseArray;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

public class StarsController {

    public static final String currency = "XTR";

    public static final int PERIOD_MONTHLY = 2592000;
    // test backend only:
    public static final int PERIOD_MINUTE = 60;
    public static final int PERIOD_5MINUTES = 300;

    private static final StarsController[] instances = new StarsController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] instanceLocks = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < instanceLocks.length; i++) {
            instanceLocks[i] = new Object();
        }
    }

    public static StarsController getInstance(int account) {
        synchronized (instanceLocks[account]) {
            if (instances[account] == null) {
                instances[account] = new StarsController(account);
            }
            return instances[account];
        }
    }

    public final int currentAccount;

    private StarsController(int account) {
        currentAccount = account;
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

    public Theme.ResourcesProvider getResourceProvider() {
        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment != null) {
            return lastFragment.getResourceProvider();
        }
        return null;
    }


































    // ===== STAR REACTIONS =====












    public Context getContext(BaseFragment fragment) {
        if (fragment != null && fragment.getContext() != null)
            return fragment.getContext();
        if (LaunchActivity.instance != null && !LaunchActivity.instance.isFinishing())
            return LaunchActivity.instance;
        if (ApplicationLoader.applicationContext != null)
            return ApplicationLoader.applicationContext;
        return null;
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



        public void load() {
            if (loading || endReached) return;

            boolean first = lastOffset == null;
            loading = true;
            final TLObject request;
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
































}
