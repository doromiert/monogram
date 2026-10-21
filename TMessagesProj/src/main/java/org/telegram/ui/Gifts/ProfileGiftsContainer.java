package org.telegram.ui.Gifts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.ilerp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.messenger.Utilities.clamp01;

import android.content.Context;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.Stars.StarGiftSheet;
import org.telegram.ui.Stars.StarsController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;


public class ProfileGiftsContainer extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final BaseFragment fragment;
    private final int currentAccount;
    private final long dialogId;
    private final StarsController.GiftsList list;
    public final StarsController.GiftsCollections collections;
    private final Theme.ResourcesProvider resourcesProvider;
    private int backgroundColor;

    private final ViewPagerFixed viewPager;
    private final ViewPagerFixed.TabsView tabsView;


    protected int processColor(int color) {
        return color;
    }

    private void fillTabs(boolean animated) {
        if (viewPager == null || tabsView == null) return;
        viewPager.fillTabs(animated);
        checkScrollToCollection();
    }

    private int externalPaddingTop;

    public void setPaddingTop(int paddingTop) {
        if (externalPaddingTop != paddingTop) {
            externalPaddingTop = paddingTop;

            View[] pages = viewPager.getViewPages();
            for (View view : pages) {
                if (view instanceof Page) {
                    Page page = (Page) view;
                    final int paddingTopOld = page.listView.getPaddingTop();
                    page.listView.setPadding(dp(9), externalPaddingTop, dp(9), dp(30 + 56));
                    final int paddingTopNew = page.listView.getPaddingTop();
                    final int scroll = paddingTopOld - paddingTopNew;
                    AndroidUtilities.doOnLayout(page.listView, () -> page.listView.scrollBy(0, scroll));
                }
            }

            updateTabsY();

        }
    }

    public static class Page extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

        private final ProfileGiftsContainer parent;
        private final int currentAccount;
        private final Theme.ResourcesProvider resourcesProvider;

        public boolean isCollection;
        @Nullable
        public StarsController.GiftsList list;

        private final UniversalRecyclerView listView;
        public @Nullable IBlur3Capture iBlur3Capture;


        public void update(boolean animated) {
            if (listView == null || listView.adapter == null) return;
            final boolean atTop = !listView.canScrollVertically(-1);
            listView.adapter.update(animated);
            if (atTop) {
                listView.scrollToPosition(0);
            }
        }

        public Page(ProfileGiftsContainer parent, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
            super(parent.getContext());

            final Context context = parent.getContext();
            this.parent = parent;
            this.currentAccount = currentAccount;
            this.resourcesProvider = resourcesProvider;

            listView = new UniversalRecyclerView(context, currentAccount, 0, false, this::fillItems, this::onItemClick, null, resourcesProvider, 3, LinearLayoutManager.VERTICAL) {
                @Override
                protected void onLayout(boolean changed, int l, int t, int r, int b) {
                    super.onLayout(changed, l, t, r, b);
                    parent.updateTabsY();
                }
            };
            listView.adapter.setApplyBackground(false);
            listView.setSelectorType(9);
            listView.setSelectorDrawableColor(0);
            listView.setPadding(dp(9), parent.externalPaddingTop, dp(9), dp(30 + 56));
            listView.setClipToPadding(false);
            listView.setClipChildren(false);
            addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
            listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (isAttachedToWindow() && (!listView.canScrollVertically(1) || isLoadingVisible())) {
                        list.load();
                    }
                    parent.updateTabsY();
                }
            });
            DefaultItemAnimator itemAnimator = new DefaultItemAnimator() {
                @Override
                protected void onMoveAnimationUpdate(RecyclerView.ViewHolder holder) {
                    super.onMoveAnimationUpdate(holder);
                    parent.updateTabsY();
                }
                @Override
                protected void onAddAnimationUpdate(RecyclerView.ViewHolder holder) {
                    super.onAddAnimationUpdate(holder);
                    parent.updateTabsY();
                }
                @Override
                protected void onChangeAnimationUpdate(RecyclerView.ViewHolder holder) {
                    super.onChangeAnimationUpdate(holder);
                    parent.updateTabsY();
                }
                @Override
                protected void onRemoveAnimationUpdate(RecyclerView.ViewHolder holder) {
                    super.onRemoveAnimationUpdate(holder);
                    parent.updateTabsY();
                }
            };
            itemAnimator.setSupportsChangeAnimations(false);
            itemAnimator.setDelayAnimations(false);
            itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            itemAnimator.setDurations(350);
            listView.setItemAnimator(itemAnimator);


            updateEmptyView();
        }

        public void bind(boolean isCollection, StarsController.GiftsList list) {
            this.isCollection = isCollection;
            this.list = list;
            if (list != null) {
                list.load();
            }
            update(false);
            if (emptyView2Layout != null) {
                emptyView2Layout.setVisibility(parent.collections.isMine() ? View.VISIBLE : View.GONE);
            }
        }

        private int visibleHeight = AndroidUtilities.displaySize.y;
        public void setVisibleHeight(int height) {
            this.visibleHeight = height;
            final float alpha = clamp01(ilerp((float) visibleHeight, dp(150), dp(220)));
            final float scale = lerp(0.6f, 1.0f, alpha);
            if (emptyView1Layout != null) {
                emptyView1Layout.setAlpha(alpha);
                emptyView1Layout.setScaleX(scale);
                emptyView1Layout.setScaleY(scale);
            }
            if (emptyView1 != null) {
                emptyView1.setTranslationY(-(getMeasuredHeight() - visibleHeight) / 2.0f);
            }
            if (emptyView2Layout != null) {
                emptyView2Layout.setAlpha(alpha);
                emptyView2Layout.setScaleX(scale);
                emptyView2Layout.setScaleY(scale);
            }
            if (emptyView2 != null) {
                emptyView2.setTranslationY(-(getMeasuredHeight() - visibleHeight) / 2.0f);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setVisibleHeight(visibleHeight);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starUserGiftsLoaded);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftsLoaded);
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.starUserGiftsLoaded) {
                if (args[1] != list)
                    return;

                update(true);
                if (list != null && isAttachedToWindow() && (!listView.canScrollVertically(1) || isLoadingVisible())) {
                    list.load();
                }
            }
        }

        private FrameLayout emptyView1;
        private LinearLayout emptyView1Layout;
        private TextView emptyView1Title;
        private TextView emptyView1Button;

        private FrameLayout emptyView2;
        private LinearLayout emptyView2Layout;
        private TextView emptyView2Title;
        private TextView emptyView2Subtitle;
        private void updateEmptyView() {
            if (emptyView1 != null) {
                removeView(emptyView1);
            }
            if (emptyView2 != null) {
                removeView(emptyView2);
            }
            if (parent.list == list) {
                emptyView2 = null;
                emptyView2Title = null;
                emptyView2Subtitle = null;
                emptyView2Layout = null;

                emptyView1 = new FrameLayout(getContext());

                emptyView1Layout = new LinearLayout(getContext());
                emptyView1Layout.setOrientation(LinearLayout.VERTICAL);
                emptyView1.addView(emptyView1Layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

                BackupImageView emptyView1Image = new BackupImageView(getContext());
                emptyView1Image.setImageDrawable(new RLottieDrawable(R.raw.utyan_empty, dp(120), dp(120)));
                emptyView1Layout.addView(emptyView1Image, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

                emptyView1Title = new TextView(getContext());
                emptyView1Title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
                emptyView1Title.setTypeface(AndroidUtilities.bold());
                emptyView1Title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                emptyView1Title.setText(LocaleController.getString(R.string.ProfileGiftsNotFoundTitle));
                emptyView1Layout.addView(emptyView1Title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

                emptyView1Button = new TextView(getContext());
                emptyView1Button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                emptyView1Button.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
                emptyView1Button.setText(LocaleController.getString(R.string.ProfileGiftsNotFoundButton));
                emptyView1Button.setOnClickListener(v -> {
                    if (list != null) {
                        list.resetFilters();
                    }
                });
                emptyView1Button.setPadding(dp(10), dp(4), dp(10), dp(4));
                emptyView1Button.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), .10f), 4, 4));
                ScaleStateListAnimator.apply(emptyView1Button);
                emptyView1Layout.addView(emptyView1Button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

                addView(emptyView1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
                listView.setEmptyView(emptyView1);
            } else {
                emptyView1 = null;
                emptyView1Title = null;
                emptyView1Button = null;
                emptyView1Layout = null;

                emptyView2 = new FrameLayout(getContext());

                emptyView2Layout = new LinearLayout(getContext());
                emptyView2Layout.setOrientation(LinearLayout.VERTICAL);
                emptyView2.addView(emptyView2Layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

                emptyView2Title = new TextView(getContext());
                emptyView2Title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                emptyView2Title.setTypeface(AndroidUtilities.bold());
                emptyView2Title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                emptyView2Title.setText(getString(R.string.Gift2CollectionEmptyTitle));
                emptyView2Layout.addView(emptyView2Title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

                emptyView2Subtitle = new TextView(getContext());
                emptyView2Subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                emptyView2Subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                emptyView2Subtitle.setVisibility(View.GONE);
                emptyView2Layout.addView(emptyView2Subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

                addView(emptyView2, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, -12, 0, 0));
                listView.setEmptyView(emptyView2);

                if (emptyView2Layout != null) {
                    emptyView2Layout.setVisibility(parent.collections.isMine() ? View.VISIBLE : View.GONE);
                }
            }
        }

        private boolean isLoadingVisible() {
            for (int i = 0; i < listView.getChildCount(); ++i) {
                if (listView.getChildAt(i) instanceof FlickerLoadingView)
                    return true;
            }
            return false;
        }


        private boolean hasTabs;
        public void setHasTabs(boolean hasTabs) {
            if (this.hasTabs == hasTabs) return;
            this.hasTabs = hasTabs;
            final boolean atTop = !listView.canScrollVertically(-1);
            listView.adapter.update(true);
            if (atTop) {
                listView.scrollToPosition(0);
            }
            parent.updateTabsY();
        }

        public float getTabsHeight() {
            for (int i = 0; i < listView.getChildCount(); ++i) {
                final View child = listView.getChildAt(i);
                final int position = listView.getChildAdapterPosition(child);
                if (child instanceof GiftVisuals.GiftCell) {
                    if (position == 0) {
                        return Math.max(0, child.getY());
                    }
                } else {
                    if (position == 0) {
                        return Math.max(0, child.getY() + child.getHeight() * child.getAlpha());
                    }
                }
            }
            return 0;
        }


        public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
            if (list == null)
                return;
            if (list.hasFilters() && list.gifts.size() <= 0 && list.endReached && !list.loading)
                return;
            final int spanCount = Math.max(1, list == null || list.totalCount == 0 ? 3 : Math.min(3, list.totalCount));
            if (list != null) {
                int spanCountLeft = 3;
                for (TL_stars.SavedStarGift userGift : list.gifts) {
                    items.add(
                        GiftVisuals.GiftCell.Factory.asStarGift(0, userGift, true, false, isCollection)
                    );
                    spanCountLeft--;
                    if (spanCountLeft == 0) {
                        spanCountLeft = 3;
                    }
                }
                if (list.loading || !list.endReached) {
                    for (int i = 0; i < (spanCountLeft <= 0 ? 3 : spanCountLeft); ++i) {
                        items.add(UItem.asFlicker(1 + i, FlickerLoadingView.STAR_GIFT).setSpanCount(1));
                    }
                }
            }
            if (parent.list == list) {
                items.add(UItem.asSpace(dp(20)));
                if (parent.dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
                    items.add(TextFactory.asText(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider), Gravity.CENTER, 14, LocaleController.getString(R.string.ProfileGiftsInfo), false, dp(24), 0));
                }
                items.add(UItem.asSpace(dp(24 + 48 + 10)));
            } else if (!items.isEmpty()) {
                items.add(UItem.asSpace(dp(24 + 48 + 10)));
            }

            if (!items.isEmpty()) {
                items.add(0, UItem.asSpace(dp(hasTabs ? 42 : 12)));
            }

            if (listView.getSpanCount() != spanCount) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (listView != null) {
                        listView.setSpanCount(spanCount);
                    }
                });
            }

            if (parent != null) {
                parent.updateTabsY();
                parent.post(parent::updateTabsY);
            }
        }

        public void onItemClick(UItem item, View view, int position, float x, float y) {
            if (list == null) return;
            if (item.object instanceof TL_stars.SavedStarGift) {
                final TL_stars.SavedStarGift userGift = (TL_stars.SavedStarGift) item.object;
                new StarGiftSheet(getContext(), currentAccount, parent.dialogId, resourcesProvider)
                    .setOnGiftUpdatedListener(() -> {
                        update(false);
                    })
                    .set(userGift, list)
                    .show();
            }
        }


        public void updateColors() {
            if (emptyView1 != null) {
                emptyView1Title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                emptyView1Button.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
                emptyView1Button.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), .10f), 4, 4));
            } else {
                emptyView2Title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                emptyView2Subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            }
        }
    }

    public boolean canScroll(boolean forward) {
        if (forward)
            return viewPager.getCurrentPosition() >= collections.getCollections().size();
        return viewPager.getCurrentPosition() <= 0;
    }

    public ProfileGiftsContainer(
        BaseFragment fragment,
        Context context,
        int currentAccount,
        long did,
        Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        this.fragment = fragment;

        this.currentAccount = currentAccount;
        if (DialogObject.isEncryptedDialog(did)) {
            TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(currentAccount).getEncryptedChat(DialogObject.getEncryptedChatId(did));
            if (encryptedChat != null) {
                this.dialogId = encryptedChat.user_id;
            } else {
                this.dialogId = did;
            }
        } else {
            this.dialogId = did;
        }
        StarsController.getInstance(currentAccount).invalidateProfileGifts(dialogId);
        this.list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);
        this.collections = StarsController.getInstance(currentAccount).getProfileGiftCollectionsList(dialogId, true);
        this.collections.all = list;
        this.list.shown = true;
        if (fragment instanceof ProfileActivity && ((ProfileActivity) fragment).openGiftsUpgradable) {
            this.list.setFilters(StarsController.GiftsList.INCLUDE_TYPE_UPGRADABLE_FLAG);
        } else {
            this.list.resetFilters();
        }
        this.list.load();
        this.resourcesProvider = resourcesProvider;

        viewPager = new ViewPagerFixed(context) {
            @Override
            public void onTabAnimationUpdate(boolean manual) {
                super.onTabAnimationUpdate(manual);

                if (fragment instanceof ProfileActivity) {
                    ((ProfileActivity) fragment).updateSelectedMediaTabText();
                    View v = fragment.getFragmentView();
                    if (v != null) {
                        v.invalidate();
                    }
                }
                updateTabsY();
            }

            @Override
            protected void onTabScrollEnd(int position) {
                super.onTabScrollEnd(position);

                if (fragment instanceof ProfileActivity) {
                    ((ProfileActivity) fragment).updateSelectedMediaTabText();
                }
            }


        };
        viewPager.setAllowDisallowInterceptTouch(true);
        viewPager.setAdapter(new ViewPagerFixed.Adapter() {
            @Override
            public int getItemCount() {
                return 1 + collections.getCollections().size();
            }

            @Override
            public View createView(int viewType) {
                if (viewType == -1) {
                    return null;
                }
                return new Page(ProfileGiftsContainer.this, currentAccount, resourcesProvider);
            }

            @Override
            public int getItemId(int position) {
                if (position == 0) return -2;
                return collections.getCollections().get(position - 1).collection_id;
            }

            @Override
            public void bindView(View view, int position, int viewType) {
                final Page page = (Page) view;
                final StarsController.GiftsList thisList;
                final boolean isCollection;
                if (viewType == 0) {
                    isCollection = false;
                    thisList = list;
                } else {
                    isCollection = true;
                    thisList = collections.getListByIndex(position - 1);
                }
                page.bind(isCollection, thisList);
                page.setVisibleHeight(visibleHeight);
                page.setHasTabs(!collections.getCollections().isEmpty());
            }

            @Override
            public int getItemViewType(int position) {
                if (position == 0)
                    return 0;
                return 1;
            }

            @Override
            public CharSequence getItemTitle(int position) {
                if (position == 0) {
                    return getString(R.string.Gift2CollectionAll);
                }

                final TL_stars.TL_starGiftCollection collection = collections.getCollections().get(position - 1);
                if (collection == null) {
                    return null;
                }
                final SpannableStringBuilder sb = new SpannableStringBuilder(collection.title);
                if (collection.icon != null) {
                    final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setTextSize(dp(16));
                    final SpannableStringBuilder emoji = new SpannableStringBuilder("e ");
                    final AnimatedEmojiSpan span = new AnimatedEmojiSpan(collection.icon, textPaint.getFontMetricsInt());
                    emoji.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.insert(0, emoji);
                }
                return sb;
            }


        });
        addView(viewPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        tabsView = viewPager.createTabsView(true, 10);
        tabsView.setColors(
            Theme.key_profile_tabSelectedLine,
            Theme.key_windowBackgroundWhiteBlackText,
            Theme.key_profile_tabText,
            Theme.key_profile_tabSelector,
            Theme.key_actionBarDefault
        );
        tabsView.setPadding(dp(8), 0, dp(8), 0);
        tabsView.setClipToPadding(false);
        tabsView.tabMarginDp = 12;


//        tabsView.setBackgroundColor(backgroundColor);
        addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42, Gravity.TOP));

        updateColors();
        updateTabsShown(false);
    }

    public void updateTabsShown(boolean animated) {
        final boolean shown = !collections.getCollections().isEmpty();
        if (viewPager.getViewPages() != null) {
            final View[] views = viewPager.getViewPages();
            for (View view : views) {
                if (view instanceof Page) {
                    ((Page) view).setHasTabs(shown);
                }
            }
        }
    }

    public float getTabsHeight() {
        float h = 0;
        if (viewPager.getViewPages() != null) {
            final View[] views = viewPager.getViewPages();
            for (View view : views) {
                if (view instanceof Page) {
                    h += (1.0f - (float) view.getTranslationX() / view.getWidth()) * ((Page) view).getTabsHeight();
                }
            }
        }
        return h;
    }

    private float hasTabs() {
        float h = 0;
        if (viewPager.getViewPages() != null) {
            final View[] views = viewPager.getViewPages();
            for (View view : views) {
                if (view instanceof Page) {
                    h += ((Page) view).hasTabs ? 1 : 0;
                }
            }
        }
        return MathUtils.clamp(h, 0, 1);
    }

    public void updateTabsY() {
        if (tabsView == null) return;
        final float ty = Math.min(externalPaddingTop, getTabsHeight() - dp(42));
        final float alpha = clamp01(ilerp(ty - externalPaddingTop, -dp(42), 0));
        final float scale = lerp(0.9f, 1.0f, alpha);
        tabsView.setTranslationY(ty);
        tabsView.setScaleX(scale);
        tabsView.setScaleY(scale);
        tabsView.setAlpha(alpha * hasTabs());
    }

    public float getTabsVisibility() {
        return tabsView != null ? tabsView.getAlpha() : 0;
    }


    private int pendingScrollToCollectionId;
    public void scrollToCollectionId(int collectionId) {
        pendingScrollToCollectionId = collectionId;
        checkScrollToCollection();
    }

    private void checkScrollToCollection() {
        if (pendingScrollToCollectionId <= 0) return;
        int index = -1;
        TL_stars.TL_starGiftCollection collection = null;
        final ArrayList<TL_stars.TL_starGiftCollection> collections = this.collections.getCollections();
        for (int i = 0; i < collections.size(); ++i) {
            if (collections.get(i).collection_id == pendingScrollToCollectionId) {
                collection = collections.get(i);
                index = i;
                break;
            }
        }
        if (index >= 0 && collection != null) {
            pendingScrollToCollectionId = 0;
            tabsView.scrollToTab(collection.collection_id, 1 + index);
        }
    }


    public boolean canFilter() {
        return true;
    }

    public boolean canFilterHidden() {
        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) return true;
        if (dialogId >= 0) return false;
        final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        return ChatObject.canUserDoAction(chat, ChatObject.ACTION_POST);
    }


    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftCollectionsLoaded) {
            if ((Long) args[0] != dialogId) return;
            fillTabs(true);
            updateTabsShown(true);
        } else if (id == NotificationCenter.updateInterfaces) {
            setVisibleHeight(visibleHeight);
        }
    }

    public Page getCurrentPage() {
        final View view = viewPager.getCurrentView();
        if (view == null) {
            return null;
        }
        return (Page) view;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starUserGiftCollectionsLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
        final Page currentPage = getCurrentPage();
        if (currentPage != null) {
            currentPage.update(false);
        }
        fillTabs(false);
        updateTabsShown(false);
        if (list != null) {
            list.shown = true;
            list.load();
        }
        if (collections != null) {
            collections.shown = true;
            collections.load();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftCollectionsLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        if (list != null) {
            list.shown = false;
        }
        if (collections != null) {
            collections.shown = false;
        }
    }

    public StarsController.GiftsList getCurrentList() {
        Page currentPage = getCurrentPage();
        if (currentPage != null) {
            return currentPage.list;
        }
        return list;
    }

    public int getGiftsCount() {
        final Page page = getCurrentPage();
        if (page == null || page.list == list) {
            if (list != null && list.totalCount > 0) return list.totalCount;
        } else {
            if (page.list != null && page.list.totalCount > 0) return page.list.totalCount;
        }
        if (dialogId >= 0) {
            final TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(dialogId);
            return userFull != null ? userFull.stargifts_count : 0;
        } else {
            final TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(-dialogId);
            return chatFull != null ? chatFull.stargifts_count : 0;
        }
    }

    private final static HashMap<Pair<Integer, Long>, CharSequence> cachedLastEmojis = new HashMap<>();
    public CharSequence getLastEmojis(Paint.FontMetricsInt fontMetricsInt) {
        if (list == null) return "";
        final Pair<Integer, Long> key = new Pair<>(UserConfig.selectedAccount, dialogId);
        if (list.gifts.isEmpty()) {
            if (list.loading) {
                final CharSequence cached = cachedLastEmojis.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            return "";
        }

        final HashSet<Long> giftsIds = new HashSet<>();
        final ArrayList<TLRPC.Document> gifts = new ArrayList<>();
        for (int i = 0; gifts.size() < 3 && i < list.gifts.size(); ++i) {
            final TL_stars.SavedStarGift gift = list.gifts.get(i);
            final TLRPC.Document doc = gift.gift.getDocument();
            if (doc == null) continue;
            if (giftsIds.contains(doc.id)) continue;
            giftsIds.add(doc.id);
            gifts.add(doc);
        }

        if (gifts.isEmpty()) return "";
        final SpannableStringBuilder ssb = new SpannableStringBuilder(" ");
        for (int i = 0; i < gifts.size(); ++i) {
            final TLRPC.Document sticker = gifts.get(i);
            final SpannableStringBuilder emoji = new SpannableStringBuilder(MessageObject.getEmoji(sticker));
            final AnimatedEmojiSpan span = new AnimatedEmojiSpan(sticker, .9f, fontMetricsInt);
            emoji.setSpan(span, 0, emoji.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.append(emoji);
        }

        cachedLastEmojis.put(key, ssb);
        return ssb;
    }

    public long getLastEmojisHash() {
        if (list == null || list.gifts.isEmpty()) return 0;

        long hash = 0;
        int giftsCount = 0;
        final HashSet<Long> giftsIds = new HashSet<>();
        for (int i = 0; giftsCount < 3 && i < list.gifts.size(); ++i) {
            final TL_stars.SavedStarGift gift = list.gifts.get(i);
            final TLRPC.Document doc = gift.gift.getDocument();
            if (doc == null) continue;
            giftsIds.add(doc.id);
            hash = Objects.hash(hash, doc.id);
            giftsCount++;
        }

        return hash;
    }

    private int visibleHeight = AndroidUtilities.displaySize.y;
    public void setVisibleHeight(int height) {
        visibleHeight = height;

        if (viewPager != null) {
            for (View view : viewPager.getViewPages()) {
                if (view instanceof Page) {
                    ((Page) view).setVisibleHeight(visibleHeight);
                }
            }
        }
    }

    public RecyclerListView getCurrentListView() {
        final Page currentPage = getCurrentPage();
        if (currentPage != null) {
            return currentPage.listView;
        }
        return null;
    }

    public static class TextFactory extends UItem.UItemFactory<LinkSpanDrawable.LinksTextView> {
        static { setup(new TextFactory()); }

        @Override
        public LinkSpanDrawable.LinksTextView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new LinkSpanDrawable.LinksTextView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                        heightMeasureSpec
                    );
                }
            };
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            final LinkSpanDrawable.LinksTextView textView = (LinkSpanDrawable.LinksTextView) view;
            textView.setGravity(item.intValue);
            textView.setTextColor((int) item.longValue);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, item.floatValue);
            textView.setTypeface(item.accent ? AndroidUtilities.bold() : null);
            textView.setPadding(item.pad, 0, item.pad, item.iconResId);
            textView.setText(item.text);
        }

        public static UItem asBoldText(int color, int gravity, float textSizeDp, CharSequence text) {
            return asText(color, gravity, textSizeDp, text, true, 0, 0);
        }

        public static UItem asText(int color, int gravity, float textSizeDp, CharSequence text) {
            return asText(color, gravity, textSizeDp, text, false, 0, 0);
        }

        public static UItem asText(int color, int gravity, float textSizeDp, CharSequence text, boolean bold, int padding, int bottomPadding) {
            UItem item = UItem.ofFactory(TextFactory.class);
            item.text = text;
            item.intValue = gravity;
            item.longValue = color;
            item.floatValue = textSizeDp;
            item.pad = padding;
            item.iconResId = bottomPadding;
            item.accent = bold;
            return item;
        }
    }

    public void updateColors() {
//        setBackgroundColor(backgroundColor = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
//        tabsView.setBackgroundColor(backgroundColor);
        View[] pages = viewPager.getViewPages();
        if (pages != null) {
            for (int i = 0; i < pages.length; ++i) {
                if (pages[i] != null) {
                    ((Page) pages[i]).updateColors();
                }
            }
        }
    }


    public static void setGiftFilterOptionsClickListeners(View view, StarsController.GiftsList list, Runnable update, int flag) {
        view.setOnClickListener(v -> {
            list.toggleTypeIncludeFlag(flag);
            update.run();
        });
        view.setOnLongClickListener(v -> {
            list.forceTypeIncludeFlag(flag, true);
            update.run();
            return true;
        });
    }

    public @Nullable IBlur3Capture iBlur3Capture;
    private ViewGroup iBlur3CaptureParent;

    public void initBlurCapture(ViewGroup parent) {
        iBlur3CaptureParent = parent;

        iBlur3Capture = (canvas, position) -> {
            View[] pages = viewPager.getViewPages();
            for (View view : pages) {
                if (view instanceof Page) {
                    Page page = (Page) view;
                    if (page.iBlur3Capture == null) {
                        page.iBlur3Capture = new ViewGroupPartRenderer(page.listView, iBlur3CaptureParent, page.listView::drawChild);
                    }
                    page.iBlur3Capture.capture(canvas, position);
                }
            }
        };
    }
}
