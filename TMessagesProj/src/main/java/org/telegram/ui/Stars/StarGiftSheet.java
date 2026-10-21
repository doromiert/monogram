package org.telegram.ui.Stars;

import org.telegram.ui.Components.ReactionParticles;
import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.formatDuration;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.AndroidUtilities.randomOf;
import static org.telegram.messenger.AndroidUtilities.replaceArrows;
import static org.telegram.messenger.LocaleController.formatNumber;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatPluralStringComma;
import static org.telegram.messenger.LocaleController.formatSpannable;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.messenger.MessagesController.findUpdates;
import static org.telegram.ui.Stars.StarsController.findAttribute;
import static org.telegram.ui.Stars.StarsController.findAttributes;
import static org.telegram.ui.Stars.StarsController.showNoSupportDialog;
import static org.telegram.ui.Stars.StarsIntroActivity.addAvailabilityRow;
import static org.telegram.ui.Stars.StarsIntroActivity.replaceStars;
import static org.telegram.ui.Stars.StarsIntroActivity.replaceStarsWithPlain;
import static org.telegram.ui.Stars.StarsIntroActivity.setGiftImage;
import static org.telegram.messenger.utils.CurrencyFormatter.percents;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ChatThemeController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.utils.CountdownTimer;
import org.telegram.messenger.utils.tlutils.AmountUtils;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.AccountFrozenAlert;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.theme.ThemeKey;
import org.telegram.ui.Cells.SessionCell;
import org.telegram.ui.Cells.ShareDialogCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BottomSheetLayouted;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ButtonSpan;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CompatDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EllipsizeSpanAnimator;
import org.telegram.ui.Components.FireworksOverlay;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkPath;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.LoadingSpan;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Components.Text;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.spoilers.SpoilersTextView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Gifts.GiftMessageView;
import org.telegram.ui.Gifts.GiftVisuals;
import org.telegram.ui.Gifts.ProfileGiftsContainer;
import org.telegram.ui.GradientClip;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PremiumPreviewFragment;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.StatisticActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Stories.recorder.HintView2;
import org.telegram.ui.Stories.recorder.StoryEntry;
import org.telegram.ui.Stories.recorder.StoryRecorder;
import org.telegram.ui.Components.FeatureInfoCell;

import java.util.ArrayList;
import java.util.Date;

public class StarGiftSheet extends BottomSheetWithRecyclerListView implements NotificationCenter.NotificationCenterDelegate {

    private final long dialogId;
    private ContainerView container;
    private ViewPagerFixed viewPager;
    private FireworksOverlay fireworksOverlay;
    private final View bottomView;

    private StarGiftSheet left, right;

    private final ActionView actionView;
    private final TopView topView;

    private final LinearLayout infoLayout;
    private final LinkSpanDrawable.LinksTextView beforeTableTextView;
    private final TableView tableView;
    private final LinkSpanDrawable.LinksTextView afterTableTextView;
    private final ButtonWithCounterView button;
    private final FrameLayout buttonContainer;
    private final LinkSpanDrawable.LinksTextView underButtonLinkTextView;
    private final FrameLayout underButtonContainer;
    private final View buttonShadow;
    private final FrameLayout bottomBulletinContainer;

    private boolean onlyWearInfo;
    private final LinearLayout wearLayout;
    private final TextView wearTitle;
    private final TextView wearSubtitle;
    private final FeatureInfoCell[] wearFeatureCells;

    private boolean myProfile;
    private TL_stars.SavedStarGift savedStarGift;
    private StarsController.IGiftsList giftsList;

    private MessageObject messageObject;
    private String slug;
    private TL_stars.TL_starGiftUnique slugStarGift;
    private boolean resale;
    private boolean messageObjectRepolling;
    private boolean messageObjectRepolled;
    private boolean userStarGiftRepolling;
    private boolean userStarGiftRepolled;

    private Roller roller;
    private boolean rolling;

    private Utilities.Callback<Boolean> closeParentSheet;
    public StarGiftSheet setCloseParentSheet(Utilities.Callback<Boolean> closeParentSheet) {
        this.closeParentSheet = closeParentSheet;
        return this;
    }

    public interface BoughtGiftCallback {
        void onBoughtGift(TL_stars.TL_starGiftUnique gift, long dialogId, boolean fragmentsImmediately);
    }

    private BoughtGiftCallback boughtGift;

    public StarGiftSheet setOnBoughtGift(BoughtGiftCallback boughtGift) {
        this.boughtGift = boughtGift;
        return this;
    }

    public StarGiftSheet(Context context, int currentAccount, long dialogId, Theme.ResourcesProvider resourcesProvider) {
        this(context, currentAccount, dialogId, resourcesProvider, null);
    }

    public StarGiftSheet(Context context, int currentAccount, long dialogId, Theme.ResourcesProvider resourcesProvider, View parentDecorView) {
        super(context, null, false, false, false, resourcesProvider);
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        topPadding = Math.max(0.05f, (float) dp(82) / (AndroidUtilities.displaySize.y + AndroidUtilities.statusBarHeight));

        occupyNavigationBar = true;
        containerView = new FrameLayout(context) {
            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                if (actionView != null && actionView.getVisibility() == View.VISIBLE) {
                    actionView.invalidate();
                }
            }
        };
        container = new ContainerView(context);
        viewPager = new ViewPagerFixed(context) {
            @Override
            protected void swapViews() {
                super.swapViews();
                if (currentPosition != (hasNeighbour(false) ? 1 : 0)) {
                    final boolean r = currentPosition > (hasNeighbour(false) ? 1 : 0);
                    AndroidUtilities.runOnUIThread(() -> {
                        final TL_stars.SavedStarGift gift = getNeighbourSavedGift(r);
                        if (gift != null) {
                            firstSet = true;
                            set(gift, giftsList);
                        } else {
                            final TL_stars.TL_starGiftUnique giftUnique = getNeighbourSlugGift(r);
                            if (giftUnique != null) {
                                firstSet = true;
                                set(giftUnique.slug, giftUnique, giftsList);
                            }
                        }
                        overrideNextIndex = -1;
                        if (Bulletin.getVisibleBulletin() != null) {
                            Bulletin.getVisibleBulletin().hide(false, 0);
                        }
                    });
                }
            }

            @Override
            protected void setTranslationX(View view, float tx) {
                if (getMeasuredWidth() <= 0) {
                    view.setTranslationX(tx);
//                    if (view instanceof ViewGroup && ((ViewGroup) view).getChildAt(0) instanceof ContainerView) {
//                        ((ContainerView) ((ViewGroup) view).getChildAt(0)).setDimAlpha(0);
//                    }
                    return;
                }
                final float t = Utilities.clamp(tx / getMeasuredWidth(), +1, -1);
                view.setTranslationX(tx + -t * 2 * backgroundPaddingLeft);
                view.setPivotX(t > 0 ? 0 : view.getMeasuredWidth());
                view.setCameraDistance(view.getMeasuredHeight() * 3.4f);
                view.setScaleX(1.0f - Math.abs(t * .25f));
                view.setRotationY(t * 10);
                View child = view instanceof FrameLayout && ((FrameLayout) view).getChildCount() > 0 ? ((FrameLayout) view).getChildAt(0) : null;
                if (left != null && child == left.container && left.actionView != null) {
                    left.actionView.invalidate();
                }
                if (child == container && actionView != null) {
                    actionView.invalidate();
                }
                if (right != null && child == right.container && right.actionView != null) {
                    right.actionView.invalidate();
                }
            }

            @Override
            protected boolean canScroll(MotionEvent e) {
                return currentPage == null || currentPage.is(PAGE_INFO);
            }
        };
        viewPager.setAdapter(new ViewPagerFixed.Adapter() {
            @Override
            public int getItemCount() {
                return 1 + (hasNeighbour(false) ? 1 : 0) + (hasNeighbour(true) ? 1 : 0);
            }

            @Override
            public View createView(int viewType) {
                View thisContainer;
                if (viewType == 0) {
                    setupNeighbour(false, false);
                    if (left == null) return null;
                    thisContainer = left.container;
                } else if (viewType == 1) {
                    thisContainer = container;
                } else if (viewType == 2) {
                    setupNeighbour(true, false);
                    if (right == null) return null;
                    thisContainer = right.container;
                } else return null;
                AndroidUtilities.removeFromParent(thisContainer);
                final FrameLayout frameLayout = new FrameLayout(context);
                frameLayout.addView(thisContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
                return frameLayout;
            }

            @Override
            public void bindView(View view, int position, int viewType) {
                View newContainer;
                if (viewType == 0) {
                    setupNeighbour(false, true);
                    if (left == null) return;
                    newContainer = left.container;
                } else if (viewType == 2) {
                    setupNeighbour(true, true);
                    if (right == null) return;
                    newContainer = right.container;
                } else return;
                FrameLayout parent = (FrameLayout) view;
                parent.removeAllViews();
                AndroidUtilities.removeFromParent(newContainer);
                parent.addView(newContainer);
            }

            @Override
            public int getItemViewType(int position) {
                position -= hasNeighbour(false) ? 1 : 0;
                return 1 + position;
            }
        });
        updateViewPager();
        bottomView = new View(context);
        bottomView.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
        containerView.addView(bottomView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.BOTTOM));
        containerView.addView(viewPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        AndroidUtilities.removeFromParent(recyclerListView);
        container.addView(recyclerListView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        infoLayout = new LinearLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (!currentPage.is(PAGE_INFO)) return false;
                return super.dispatchTouchEvent(ev);
            }
        };
        infoLayout.setOrientation(LinearLayout.VERTICAL);
        infoLayout.setPadding(backgroundPaddingLeft + dp(14), dp(16), backgroundPaddingLeft + dp(14), dp(12 + 48 + 8));
        container.addView(infoLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        beforeTableTextView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        beforeTableTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        beforeTableTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        beforeTableTextView.setGravity(Gravity.CENTER);
        beforeTableTextView.setLineSpacing(dp(2), 1f);
        beforeTableTextView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
        beforeTableTextView.setDisablePaddingsOffsetY(true);
        infoLayout.addView(beforeTableTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 4, -2, 4, 16));
        beforeTableTextView.setVisibility(View.GONE);

        tableView = new TableView(context, resourcesProvider);

        infoLayout.addView(tableView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        afterTableTextView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        afterTableTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        afterTableTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        afterTableTextView.setGravity(Gravity.CENTER);
        afterTableTextView.setLineSpacing(dp(2), 1f);
        afterTableTextView.setLinkTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
        afterTableTextView.setDisablePaddingsOffsetY(true);
        afterTableTextView.setPadding(dp(5), 0, dp(5), 0);
        infoLayout.addView(afterTableTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 4, 2, 4, 8));
        afterTableTextView.setVisibility(View.GONE);

        {
            wearLayout = new LinearLayout(context) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (!currentPage.is(PAGE_WEAR)) return false;
                    return super.dispatchTouchEvent(ev);
                }
            };
            wearLayout.setOrientation(LinearLayout.VERTICAL);
            wearLayout.setPadding(dp(4) + backgroundPaddingLeft, dp(20), dp(4) + backgroundPaddingLeft, dp(12 + 48 + 6));
            container.addView(wearLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

            wearTitle = new TextView(context);
            wearTitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            wearTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            wearTitle.setGravity(Gravity.CENTER);
            wearTitle.setTypeface(AndroidUtilities.bold());
            wearLayout.addView(wearTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL, 20, 0, 20, 0));

            wearSubtitle = new TextView(context);
            wearSubtitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            wearSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            wearSubtitle.setGravity(Gravity.CENTER);
            wearSubtitle.setText(LocaleController.getString(R.string.Gift2WearSubtitle));
            wearLayout.addView(wearSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL, 20, 6, 20, 24));

            wearFeatureCells = new FeatureInfoCell[3];
            wearFeatureCells[0] = new FeatureInfoCell(context, resourcesProvider);
            wearFeatureCells[0].set(R.drawable.menu_feature_unique, getString(R.string.Gift2WearFeature1Title), getString(R.string.Gift2WearFeature1Text));
            wearLayout.addView(wearFeatureCells[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            wearFeatureCells[1] = new FeatureInfoCell(context, resourcesProvider);
            wearFeatureCells[1].set(R.drawable.menu_feature_cover, getString(R.string.Gift2WearFeature2Title), getString(R.string.Gift2WearFeature2Text));
            wearLayout.addView(wearFeatureCells[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            wearFeatureCells[2] = new FeatureInfoCell(context, resourcesProvider);
            wearFeatureCells[2].set(R.drawable.menu_verification, getString(R.string.Gift2WearFeature3Title), getString(R.string.Gift2WearFeature3Text));
            wearLayout.addView(wearFeatureCells[2], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        infoLayout.setAlpha(1.0f);
        wearLayout.setAlpha(0.0f);

        topView = new TopView(context, resourcesProvider, this::onBackPressed, this::onMenuPressed, this::onWearPressed, this::onSharePressed);

        topView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
        container.addView(topView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));
        layoutManager.setReverseLayout(reverseLayout = true);

        buttonContainer = new FrameLayout(context);
        buttonContainer.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));

        buttonShadow = new View(context);
        buttonShadow.setBackgroundColor(getThemedColor(Theme.key_divider));
        buttonShadow.setAlpha(0.0f);
        buttonContainer.addView(buttonShadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1.0f / AndroidUtilities.density, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(getString(R.string.OK), false);
        button.setSubText(null, false);
        final FrameLayout.LayoutParams buttonLayoutParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL, 0, 12, 0, 12);
        buttonLayoutParams.leftMargin = backgroundPaddingLeft + dp(14);
        buttonLayoutParams.rightMargin = backgroundPaddingLeft + dp(14);
        buttonContainer.addView(button, buttonLayoutParams);
        container.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 12 + 48 + 12, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL));

        underButtonContainer = new FrameLayout(context);
        underButtonContainer.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));

        underButtonLinkTextView = new LinkSpanDrawable.LinksTextView(context);
        underButtonLinkTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        underButtonLinkTextView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
        underButtonLinkTextView.setLinkTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
        underButtonLinkTextView.setGravity(Gravity.CENTER);
        underButtonContainer.addView(underButtonLinkTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 16, 8, 16, 14));
        container.addView(underButtonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL));
        underButtonContainer.setVisibility(View.GONE);

        recyclerListView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                container.updateTranslations();
            }
        });

        fireworksOverlay = new FireworksOverlay(context);
        container.addView(fireworksOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        bottomBulletinContainer = new FrameLayout(context);
        bottomBulletinContainer.setPadding(backgroundPaddingLeft + dp(14 - 8), 0, backgroundPaddingLeft + dp(14 - 8), 0);
        container.addView(bottomBulletinContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 12 + 48));

        AndroidUtilities.removeFromParent(actionBar);
        container.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 6, 0));

        actionView = new ActionView(context);
        container.addView(actionView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL));

        actionView.prepareBlur(parentDecorView);
    }

    private final int[] heights = new int[2];
    private Adapter adapter;
    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new BottomSheetLayouted.SpaceView(getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            position = heights.length - 1 - position;
            ((BottomSheetLayouted.SpaceView) holder.itemView).setHeight(heights[position], position);
        }

        @Override
        public int getItemCount() {
            return heights.length;
        }

        public void setHeights(int top, int bottom) {
            if (heights[0] != top || heights[1] != bottom) {
                heights[0] = top;
                heights[1] = bottom;
                notifyDataSetChanged();
            }
        }
    }

    private int getListPosition() {
        if (giftsList == null) return -1;
        int index;
        if (savedStarGift != null) {
            index = giftsList.indexOf(savedStarGift);
        } else if (slugStarGift != null) {
            index = giftsList.indexOf(slugStarGift);
        } else {
            return -1;
        }
        if (index >= 0) return index;
        final TL_stars.StarGift currentGift = getGift();
        for (int i = 0; i < giftsList.getLoadedCount(); ++i) {
            final Object gift = giftsList.get(i);
            if (gift instanceof TL_stars.SavedStarGift) {
                if (
                    savedStarGift != null && eq(savedStarGift, (TL_stars.SavedStarGift) gift) ||
                    currentGift != null && eq(currentGift, (TL_stars.SavedStarGift) gift)
                ) {
                    return i;
                }
            } else if (gift instanceof TL_stars.TL_starGiftUnique) {
                if (eq(slugStarGift, (TL_stars.TL_starGiftUnique) gift)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private TL_stars.SavedStarGift getNeighbourSavedGift(boolean r) {
        final int thisPosition = getListPosition();
        if (thisPosition < 0) return null;
        Object gift = null;
        int index = thisPosition + (r ? +1 : -1);
        if (overrideNextIndex >= 0 && (r ? overrideNextIndex > thisPosition : overrideNextIndex < thisPosition)) {
            index = overrideNextIndex;
        }
        if (giftsList != null && index >= 0 && index < giftsList.getLoadedCount()) {
            gift = giftsList.get(index);
        }
        if (gift instanceof TL_stars.SavedStarGift) {
            return (TL_stars.SavedStarGift) gift;
        }
        return null;
    }

    private TL_stars.TL_starGiftUnique getNeighbourSlugGift(boolean r) {
        final int thisPosition = getListPosition();
        if (thisPosition < 0) return null;
        Object gift = null;
        int index = thisPosition + (r ? +1 : -1);
        if (overrideNextIndex >= 0 && (r ? overrideNextIndex > thisPosition : overrideNextIndex < thisPosition)) {
            index = overrideNextIndex;
        }
        if (giftsList != null && index >= 0 && index < giftsList.getLoadedCount()) {
            gift = giftsList.get(index);
        }
        if (gift instanceof TL_stars.TL_starGiftUnique) {
            return (TL_stars.TL_starGiftUnique) gift;
        }
        return null;
    }

    private boolean hasNeighbour(boolean r) {
        return getNeighbourSavedGift(r) != null || getNeighbourSlugGift(r) != null;
    }

    private int overrideNextIndex = -1;
    private void setupNeighbour(boolean r, boolean bind) {
        int thisPosition = getListPosition();
        if (thisPosition < 0) return;
        Object gift = null;
        int index = thisPosition + (r ? +1 : -1);
        if (overrideNextIndex >= 0 && (r ? overrideNextIndex > thisPosition : overrideNextIndex < thisPosition)) {
            index = overrideNextIndex;
        }
        if (giftsList != null && index >= 0 && index < giftsList.getLoadedCount()) {
            gift = giftsList.get(index);
        }
        if (gift == null) return;
        if ((r ? right : left) != null) {
            if (gift instanceof TL_stars.SavedStarGift && eq((r ? right : left).savedStarGift, (TL_stars.SavedStarGift) gift)) {
                return;
            } else if (gift instanceof TL_stars.TL_starGiftUnique && eq((r ? right : left).slugStarGift, (TL_stars.TL_starGiftUnique) gift)) {
                return;
            }
        }
        StarGiftSheet sheet = new StarGiftSheet(getContext(), currentAccount, dialogId, resourcesProvider, container.getRootView()) {
            @Override
            public int getBottomInset() {
                return StarGiftSheet.this.getBottomInset();
            }
        };
        if (gift instanceof TL_stars.SavedStarGift) {
            sheet.set((TL_stars.SavedStarGift) gift, giftsList);
        } else if (gift instanceof TL_stars.TL_starGiftUnique) {
            final TL_stars.TL_starGiftUnique giftUnique = (TL_stars.TL_starGiftUnique) gift;
            sheet.set(giftUnique.slug, giftUnique, giftsList);
        }
        AndroidUtilities.removeFromParent(sheet.containerView);
        if (r) {
            right = sheet;
        } else {
            left = sheet;
        }
    }

    private void updateViewPager() {
        viewPager.setPosition(hasNeighbour(false) ? 1 : 0);
        viewPager.rebuild(false);
        if (giftsList != null && !hasNeighbour(true) && giftsList.getLoadedCount() < giftsList.getTotalCount()) {
            giftsList.load();
        }
    }

    public boolean eq(TL_stars.TL_starGiftUnique a, TL_stars.TL_starGiftUnique b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.id == b.id || TextUtils.equals(a.slug, b.slug);
    }

    public boolean eq(TL_stars.SavedStarGift a, TL_stars.SavedStarGift b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.gift == b.gift) return true;
        if (a.gift instanceof TL_stars.TL_starGiftUnique && b.gift instanceof TL_stars.TL_starGiftUnique) {
            return a.gift.id == b.gift.id;
        }
        if (a.gift instanceof TL_stars.TL_starGift && b.gift instanceof TL_stars.TL_starGift) {
            return a.gift.id == b.gift.id && a.date == b.date;
        }
        return false;
    }

    public boolean eq(TL_stars.SavedStarGift a, TL_stars.InputSavedStarGift input) {
        if (a == null) return false;
        if (input instanceof TL_stars.TL_inputSavedStarGiftUser) {
            return a.msg_id == ((TL_stars.TL_inputSavedStarGiftUser) input).msg_id;
        }
        if (input instanceof TL_stars.TL_inputSavedStarGiftChat) {
            return a.saved_id == ((TL_stars.TL_inputSavedStarGiftChat) input).saved_id;
        }
        if (input instanceof TL_stars.TL_inputSavedStarGiftSlug) {
            return a.gift != null && TextUtils.equals(a.gift.slug, ((TL_stars.TL_inputSavedStarGiftSlug) input).slug);
        }
        return false;
    }

    public boolean eq(TL_stars.StarGift a, TL_stars.SavedStarGift b) {
        if (a == null || b == null) return false;
        if (a == b.gift) return true;
        if (a instanceof TL_stars.TL_starGiftUnique && b.gift instanceof TL_stars.TL_starGiftUnique) {
            return a.id == b.gift.id;
        }
        return false;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starUserGiftsLoaded);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftsLoaded);
    }

    @Override
    protected boolean shouldDrawBackground() {
        return false;
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return adapter = new Adapter();
    }

    private String title = "";
    @Override
    protected CharSequence getTitle() {
        return title;
    }

    public static boolean isMine(int currentAccount, long dialogId) {
        if (dialogId >= 0) {
            return UserConfig.getInstance(currentAccount).getClientUserId() == dialogId;
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            return ChatObject.canUserDoAction(chat, ChatObject.ACTION_POST);
        }
    }

    public static boolean isMineWithActions(int currentAccount, long dialogId) {
        if (dialogId >= 0) {
            return UserConfig.getInstance(currentAccount).getClientUserId() == dialogId;
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            return chat != null && chat.creator;
        }
    }

    private void onMenuPressed(View btn) {
        final String link = getLink();
        final TL_stars.TL_starGiftUnique giftUnique = getUniqueGift();
        ItemOptions.makeOptions(container, resourcesProvider, btn)
            .addIf(getUniqueGift() != null && isMineWithActions(currentAccount, DialogObject.getPeerDialogId(getUniqueGift().owner_id)) && giftsList instanceof StarsController.GiftsList && savedStarGift != null && getInputStarGift() != null, (savedStarGift != null && savedStarGift.pinned_to_top) ? R.drawable.msg_unpin : R.drawable.msg_pin, getString((savedStarGift != null && savedStarGift.pinned_to_top) ? R.string.Gift2Unpin : R.string.Gift2Pin), () -> {
                if (savedStarGift.unsaved) {
                    savedStarGift.unsaved = false;
                    final StarsController.GiftsCollections collections = StarsController.getInstance(currentAccount).getProfileGiftCollectionsList(dialogId, false);
                    if (collections != null) {
                        collections.updateGiftsUnsaved(savedStarGift, savedStarGift.unsaved);
                    }

                    final TL_stars.saveStarGift req = new TL_stars.saveStarGift();
                    req.stargift = getInputStarGift();
                    req.unsave = savedStarGift.unsaved;
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, null, ConnectionsManager.RequestFlagInvokeAfter);
                }

                final boolean newPinned = !savedStarGift.pinned_to_top;
                if (((StarsController.GiftsList) giftsList).togglePinned(savedStarGift, newPinned, false)) {
                    new ProfileGiftsContainer.UnpinSheet(getContext(), dialogId, savedStarGift, resourcesProvider, this::getBulletinFactory).show();
                } else if (newPinned) {
                    getBulletinFactory()
                        .createSimpleBulletin(R.raw.ic_pin, getString(R.string.Gift2PinnedTitle), getString(R.string.Gift2PinnedSubtitle))
                        .show();
                } else {
                    getBulletinFactory()
                        .createSimpleBulletin(R.raw.ic_unpin, getString(R.string.Gift2Unpinned))
                        .show();
                }
            })

            .addIf(link != null, R.drawable.msg_link, getString(R.string.CopyLink), () -> {
                AndroidUtilities.addToClipboard(link);
                getBulletinFactory()
                    .createCopyLinkBulletin(false)
                    .ignoreDetach()
                    .show();
            })
            .addIf(link != null, R.drawable.msg_share, getString(R.string.ShareFile), () -> {
                onSharePressed(null);
            })
            .addIf(canSetAsTheme(), R.drawable.msg_colors, getString(R.string.GiftThemesSetIn), this::openSetAsTheme)
            .addIf(savedStarGift == null && getDialogId() != 0, R.drawable.msg_view_file, getString(R.string.Gift2ViewInProfile), this::openInProfile)
            .setDrawScrim(false)
            .setOnTopOfScrim()
            .setDimAlpha(0)
            .translate(0, -dp(2))
            .show();
    }

    private boolean canSetAsTheme() {
        final TL_stars.TL_starGiftUnique giftUnique = getUniqueGift();
        if (giftUnique == null || !giftUnique.theme_available) {
            return false;
        }

        final long owner_id = DialogObject.getPeerDialogId(giftUnique.owner_id);
        final long host_id = DialogObject.getPeerDialogId(giftUnique.host_id);
        return (
            owner_id > 0 && isMineWithActions(currentAccount, owner_id) ||
            host_id > 0 && isMineWithActions(currentAccount, host_id)
        );
    }

    private void openSetAsTheme() {
        dismiss();

        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        final TL_stars.TL_starGiftUnique giftUnique = getUniqueGift();
        if (lastFragment == null || giftUnique == null) return;

        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);

        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.isEmpty()) {
                return false;
            }

            final long userId = dids.get(0).dialogId;
            final long busyBy = ChatThemeController.getInstance(currentAccount).getGiftThemeUser(giftUnique.slug);
            if (busyBy != 0 && busyBy != userId) {
                AlertsCreator.showGiftThemeApplyConfirm(getContext(), resourcesProvider, currentAccount, giftUnique, busyBy, () -> {
                    ChatThemeController.getInstance(currentAccount).setDialogTheme(userId, ThemeKey.ofGiftSlug(giftUnique.slug));
                    fragment.presentFragment(ChatActivity.of(userId), true);
                });
                return true;
            }

            ChatThemeController.getInstance(currentAccount).setDialogTheme(userId, ThemeKey.ofGiftSlug(giftUnique.slug));
            fragment.presentFragment(ChatActivity.of(userId), true);

            return true;
        });

        lastFragment.presentFragment(fragment);
    }

    private ColoredImageSpan lockSpan;
    private boolean shownWearInfo;
    private void onWearPressed(View v) {
        if (UserConfig.getInstance(currentAccount).isPremium() && (isWorn(currentAccount, getUniqueGift()) || shownWearInfo)) {
            toggleWear();
            return;
        }
        final TL_stars.TL_starGiftUnique gift = getUniqueGift();
        if (gift == null) return;
        final long owner_id = gift.owner_id != null ? DialogObject.getPeerDialogId(gift.owner_id) : DialogObject.getPeerDialogId(gift.host_id);
        final String product = gift.title + " #" + LocaleController.formatNumber(gift.num, ',');
        wearTitle.setText(LocaleController.formatString(R.string.Gift2WearTitle, product));
        SpannableStringBuilder buttonText = new SpannableStringBuilder(getString(R.string.Gift2WearStart));
        if (!UserConfig.getInstance(currentAccount).isPremium()) {
            buttonText.append(" l");
            if (lockSpan == null) {
                lockSpan = new ColoredImageSpan(R.drawable.msg_mini_lock3);
            }
            buttonText.setSpan(lockSpan, buttonText.length() - 1, buttonText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(buttonText, true);
        button.setSubText(null, true);
        button.setOnClickListener(v2 -> {
            shownWearInfo = true;
            toggleWear();
        });
        topView.setWearPreview(MessagesController.getInstance(currentAccount).getUserOrChat(owner_id));
        switchPage(PAGE_WEAR, true);
    }

    public StarGiftSheet setupWearPage() {
        final TL_stars.TL_starGiftUnique gift = getUniqueGift();
        if (gift == null) return this;
        final long dialogId = gift.owner_id != null ? DialogObject.getPeerDialogId(gift.owner_id) : DialogObject.getPeerDialogId(gift.host_id);
        final String product = gift.title + " #" + LocaleController.formatNumber(gift.num, ',');
        wearTitle.setText(LocaleController.formatString(R.string.Gift2WearTitle, product));
        SpannableStringBuilder buttonText = new SpannableStringBuilder(getString(R.string.Gift2WearStart));
        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId() && !UserConfig.getInstance(currentAccount).isPremium()) {
            buttonText.append(" l");
            if (lockSpan == null) {
                lockSpan = new ColoredImageSpan(R.drawable.msg_mini_lock3);
            }
            buttonText.setSpan(lockSpan, buttonText.length() - 1, buttonText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(buttonText, true);
        button.setSubText(null, true);
        button.setOnClickListener(v2 -> {
            shownWearInfo = true;
            toggleWear();
        });
        topView.setWearPreview(MessagesController.getInstance(currentAccount).getUserOrChat(dialogId));
        switchPage(PAGE_WEAR, false);
        onlyWearInfo = true;
        return this;
    }

    public static boolean isWorn(int currentAccount, TL_stars.TL_starGiftUnique gift) {
        if (gift == null) return false;
        final long dialogId = gift.owner_id != null ? DialogObject.getPeerDialogId(gift.owner_id) : DialogObject.getPeerDialogId(gift.host_id);
        if (dialogId == 0) return false;
        if (dialogId > 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user != null && user.emoji_status instanceof TLRPC.TL_emojiStatusCollectible) {
                final TLRPC.TL_emojiStatusCollectible status = (TLRPC.TL_emojiStatusCollectible) user.emoji_status;
                return status.collectible_id == gift.id;
            }
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (chat != null && chat.emoji_status instanceof TLRPC.TL_emojiStatusCollectible) {
                final TLRPC.TL_emojiStatusCollectible status = (TLRPC.TL_emojiStatusCollectible) chat.emoji_status;
                return status.collectible_id == gift.id;
            }
        }
        return false;
    }

    public void toggleWear() {
        toggleWear(false);
    }
    public void toggleWear(boolean checkedLevel) {
        final TL_stars.TL_starGiftUnique gift = getUniqueGift();
        if (gift == null) return;
        MessagesController.getGlobalMainSettings().edit().putInt("statusgiftpage", 3).apply();
        final boolean worn = !isWorn(currentAccount, getUniqueGift());
        if (isWorn(currentAccount, getUniqueGift())) {
            MessagesController.getInstance(currentAccount).updateEmojiStatus(getDialogId(), new TLRPC.TL_emojiStatusEmpty(), null);
        } else {
            final long did = getDialogId();
            if (did >= 0) {
                if (!UserConfig.getInstance(currentAccount).isPremium()) {
                    getBulletinFactory()
                        .createSimpleBulletinDetail(R.raw.star_premium_2, AndroidUtilities.premiumText(getString(R.string.Gift2ActionWearNeededPremium), () -> {
                            new PremiumFeatureBottomSheet(getDummyFragment(), PremiumPreviewFragment.PREMIUM_FEATURE_EMOJI_STATUS, false).show();
                        }))
                        .ignoreDetach()
                        .show();
                    return;
                }
            } else if (!checkedLevel) {
                final MessagesController m = MessagesController.getInstance(currentAccount);
                button.setLoading(true);
                MessagesController.getInstance(currentAccount).getBoostsController().getBoostsStats(did, boostsStatus -> {
                    if (boostsStatus == null || boostsStatus.level >= m.channelEmojiStatusLevelMin) {
                        button.setLoading(false);
                        toggleWear(true);
                    } else {
                        m.getBoostsController().userCanBoostChannel(did, boostsStatus, canApplyBoost -> {
                            button.setLoading(false);
                            LimitReachedBottomSheet limitReachedBottomSheet = new LimitReachedBottomSheet(getDummyFragment(), getContext(), LimitReachedBottomSheet.TYPE_BOOSTS_FOR_WEAR_COLLECTIBLE, currentAccount, resourcesProvider);
                            limitReachedBottomSheet.setCanApplyBoost(canApplyBoost);
                            limitReachedBottomSheet.setBoostsStats(boostsStatus, true);
                            limitReachedBottomSheet.setDialogId(did);
                            final TLRPC.Chat channel = m.getChat(-did);
                            if (channel != null) {
                                limitReachedBottomSheet.showStatisticButtonInLink(() -> {
                                    presentFragment(StatisticActivity.create(channel));
                                });
                            }
                            limitReachedBottomSheet.show();
                        });
                    }
                });
                return;
            }
            final TLRPC.TL_inputEmojiStatusCollectible status = new TLRPC.TL_inputEmojiStatusCollectible();
            status.collectible_id = gift.id;
            MessagesController.getInstance(currentAccount).updateEmojiStatus(getDialogId(), status, gift);
        }
        topView.buttons[0].set(worn ? R.drawable.filled_crown_off : R.drawable.filled_crown_on, getString(worn ? R.string.Gift2ActionWearOff : R.string.Gift2ActionWear), true);
        if (onlyWearInfo) {
            dismiss();
            return;
        }
        final Runnable showHint = () -> showHint(AndroidUtilities.replaceTags(formatString(worn ? R.string.Gift2ActionWearDone : R.string.Gift2ActionWearOffDone, getGiftName())), ownerTextView, true);
        if (currentPage.is(PAGE_INFO)) {
            showHint.run();
        } else {
            switchPage(PAGE_INFO, true, showHint);
        }
        button.setText(getString(R.string.OK), !firstSet);
        button.setSubText(null, !firstSet);
        button.setOnClickListener(v -> onBackPressed());
    }

    private BaseFragment getDummyFragment() {
        return new BaseFragment() {
            @Override
            public int getCurrentAccount() {
                return currentAccount;
            }

            @Override
            public Context getContext() {
                return StarGiftSheet.this.getContext();
            }

            @Override
            public Activity getParentActivity() {
                Context context = getContext();
                while (context instanceof ContextWrapper) {
                    if (context instanceof Activity) {
                        return (Activity) context;
                    }
                    context = ((ContextWrapper) context).getBaseContext();
                }
                return null;
            }

            @Override
            public Dialog showDialog(Dialog dialog) {
                dialog.show();
                return dialog;
            }
        };
    }

    private ShareAlert shareAlert;
    public void onSharePressed(View btn) {
        if (shareAlert != null && shareAlert.isShown()) {
            shareAlert.dismiss();
        }
        final String link = getLink();
        shareAlert = new ShareAlert(getContext(), null, null, link, null, false, link, null, false, false, true, null, resourcesProvider) {
            { includeStoryFromMessage = true; }

            @Override
            protected void onShareStory(View cell) {
                repostStory(cell);
            }

            @Override
            protected void onSend(LongSparseArray<TLRPC.Dialog> dids, int count, TLRPC.TL_forumTopic topic, boolean showToast) {
                if (!showToast) return;
                super.onSend(dids, count, topic, showToast);
                BulletinFactory bulletinFactory = getBulletinFactory();
                if (bulletinFactory != null) {
                    if (dids.size() == 1) {
                        long did = dids.keyAt(0);
                        if (did == UserConfig.getInstance(currentAccount).clientUserId) {
                            bulletinFactory.createSimpleBulletin(R.raw.saved_messages, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.LinkSharedToSavedMessages)), Bulletin.DURATION_PROLONG).hideAfterBottomSheet(false).ignoreDetach().show();
                        } else if (did < 0) {
                            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                            bulletinFactory.createSimpleBulletin(R.raw.forward, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.LinkSharedTo, topic != null ? topic.title : chat.title)), Bulletin.DURATION_PROLONG).hideAfterBottomSheet(false).ignoreDetach().show();
                        } else {
                            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(did);
                            bulletinFactory.createSimpleBulletin(R.raw.forward, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.LinkSharedTo, user.first_name)), Bulletin.DURATION_PROLONG).hideAfterBottomSheet(false).ignoreDetach().show();
                        }
                    } else {
                        bulletinFactory.createSimpleBulletin(R.raw.forward, AndroidUtilities.replaceTags(LocaleController.formatPluralString("LinkSharedToManyChats", dids.size(), dids.size()))).hideAfterBottomSheet(false).ignoreDetach().show();
                    }
                    try {
                        container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    } catch (Exception ignored) {}
                }
            }
        };
        shareAlert.setDelegate(new ShareAlert.ShareAlertDelegate() {
            @Override
            public boolean didCopy() {
                getBulletinFactory()
                    .createCopyLinkBulletin(false)
                    .ignoreDetach()
                    .show();
                return true;
            }
        });
        shareAlert.show();
    }

    private void repostStory(View cell) {
        Activity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        StoryRecorder.SourceView sourceView = null;
        if (cell instanceof ShareDialogCell) {
            sourceView = StoryRecorder.SourceView.fromShareCell((ShareDialogCell) cell);
        }
        final ArrayList<MessageObject> messageObjects = new ArrayList<>();
        if (messageObject != null) {
            messageObjects.add(messageObject);
        } else if (getGift() instanceof TL_stars.TL_starGiftUnique) {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            final TL_stars.TL_starGiftUnique gift = (TL_stars.TL_starGiftUnique) getGift();

            final TLRPC.TL_messageService message = new TLRPC.TL_messageService();
            message.peer_id = MessagesController.getInstance(currentAccount).getPeer(selfId);
            message.from_id = MessagesController.getInstance(currentAccount).getPeer(selfId);
            message.date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            final TLRPC.TL_messageActionStarGiftUnique action = new TLRPC.TL_messageActionStarGiftUnique();
            action.gift = gift;
            action.upgrade = true;
            message.action = action;

            final MessageObject msg = new MessageObject(currentAccount, message, false, false);
            msg.setType();
            messageObjects.add(msg);
        } else {
            return;
        }
        StoryRecorder editor = StoryRecorder.getInstance(activity, currentAccount);
        editor.setOnPrepareCloseListener((t, close, sent, did) -> {
            if (sent) {
                AndroidUtilities.runOnUIThread(() -> {
                    String chatTitle = "";
                    if (did < 0) {
                        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                        if (chat != null) {
                            chatTitle = chat.title;
                        }
                    }
                    getBulletinFactory()
                        .createSimpleBulletin(R.raw.contact_check, AndroidUtilities.replaceTags(
                            TextUtils.isEmpty(chatTitle) ?
                                LocaleController.getString(R.string.GiftRepostedToProfile) :
                                LocaleController.formatString(R.string.GiftRepostedToChannelProfile, chatTitle)
                    )).ignoreDetach().show();
                });
                editor.replaceSourceView(null);
                if (shareAlert != null) {
                    shareAlert.dismiss();
                    shareAlert = null;
                }
            } else {
                StoryRecorder.SourceView sourceView2 = null;
                if (cell instanceof ShareDialogCell && cell.isAttachedToWindow()) {
                    sourceView2 = StoryRecorder.SourceView.fromShareCell((ShareDialogCell) cell);
                }
                editor.replaceSourceView(sourceView2);
            }
            AndroidUtilities.runOnUIThread(close);
        });
        editor.openRepost(sourceView, StoryEntry.repostMessage(messageObjects));
    }

    @Override
    protected int getActionBarProgressHeight() {
        return dp(12);
    }

    private class ContainerView extends FrameLayout {

        private final RectF rect = new RectF();
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        public ContainerView(Context context) {
            super(context);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getY() < top() && containerView.isAttachedToWindow()) {
                dismiss();
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            preDrawInternal(canvas, this);
            canvas.save();

            final float top = top();
            final float r = dp(12);
            rect.set(backgroundPaddingLeft, top, getWidth() - backgroundPaddingLeft, getHeight() + r);
            backgroundPaint.setColor(getThemedColor(Theme.key_dialogBackground));
            path.rewind();
            path.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.drawPath(path, backgroundPaint);

            super.dispatchDraw(canvas);
            if (dimAlpha != 0) {
                canvas.drawColor(Theme.multAlpha(0xFF000000, dimAlpha));
            }
            updateTranslations();

            canvas.restore();
            drawView(canvas, actionBar);

            postDrawInternal(canvas, this);
        }

        private float dimAlpha = 0;
        public void setDimAlpha(float dimAlpha) {
            if ((int) (0xFF * dimAlpha) != (int) (0xFF * this.dimAlpha)) {
                this.dimAlpha = dimAlpha;
                invalidate();
            }
        }

        private void drawView(Canvas canvas, View view) {
            if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0) return;
            if (view.getAlpha() < 1) {
                canvas.saveLayerAlpha(view.getX(), view.getY(), view.getX() + view.getMeasuredWidth(), view.getY() + view.getMeasuredHeight(), (int) (0xFF * actionBar.getAlpha()), Canvas.ALL_SAVE_FLAG);
            } else {
                canvas.save();
                canvas.clipRect(view.getX(), view.getY(), view.getX() + view.getMeasuredWidth(), view.getY() + actionBar.getMeasuredHeight());
            }
            canvas.translate(view.getX(), view.getY());
            view.draw(canvas);
            canvas.restore();
        }

        @Override
        protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
            if (child == actionBar) {
                return false;
            }
            if (child != actionView) {
                canvas.save();
                canvas.clipPath(path);
                boolean r = super.drawChild(canvas, child, drawingTime);
                canvas.restore();
                return r;
            }
            return super.drawChild(canvas, child, drawingTime);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (adapter != null) {
                adapter.setHeights(
                    topView.getFinalHeight(),
                    getBottomHeight() + (currentPage.to(PAGE_UPGRADE) && underButtonContainer.getVisibility() == View.VISIBLE ? underButtonContainer.getMeasuredHeight() : 0)
                );
            }
            onSwitchedPage();
        }

        public void updateTranslations() {
            float top = top();
            actionView.setTranslationY(top - actionView.getHeight());
            final float actionAlpha = Utilities.clamp01(AndroidUtilities.ilerp(top - actionView.getHeight(), 0, dp(32)));
            actionView.setAlpha(currentPage.at(PAGE_INFO) * actionAlpha);
            actionView.setScaleX(lerp(0.5f, 1.0f, actionAlpha));
            actionView.setScaleY(lerp(0.5f, 1.0f, actionAlpha));

            topView.setTranslationY(top);
            infoLayout.setTranslationY(top + topView.getRealHeight());
            wearLayout.setTranslationY(top + topView.getRealHeight());
            if (topBulletinContainer != null) {
                topBulletinContainer.setTranslationY(getTranslationY() - height() - AndroidUtilities.navigationBarHeight);
            }
            AndroidUtilities.updateViewVisibilityAnimated(buttonShadow, recyclerListView.canScrollVertically(1));
        }

        @Override
        public void setTranslationY(float translationY) {
            super.setTranslationY(translationY);
            if (topBulletinContainer != null) {
                topBulletinContainer.setTranslationY(getTranslationY() - height() - AndroidUtilities.navigationBarHeight);
            }
        }

        public float height() {
            float h = 0;
            h += topView.getRealHeight();
            h += currentPage.at(PAGE_INFO) * infoLayout.getMeasuredHeight();
            h += currentPage.at(PAGE_WEAR) * wearLayout.getMeasuredHeight();
            return h;
        }

        public float top() {
            float top = Math.max(0, getHeight() - height());
            for (int i = recyclerListView.getChildCount() - 1; i >= 0; --i) {
                final View child = recyclerListView.getChildAt(i);
                int position = recyclerListView.getChildAdapterPosition(child);
                if (position < 0) continue;
                if (position == 2) {
                    top = child.getTop() + child.getTranslationY() + child.getHeight();
                    break;
                } else if (position == 1) {
                    top = child.getY();
                    break;
                } else if (position == 0) {
                    top = child.getY() - topView.getRealHeight();
                    break;
                }
            }
            top += getBottomInset() * currentPage.at(PAGE_CRAFTING);
            if (lastTop != null && currentPage != null && currentPage.progress < 1) {
                return lerp(lastTop, top, currentPage.progress);
            }
            return top;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int bottomInset = getBottomInset();
            setPadding(0, 0, 0, bottomInset);
            final int height = MeasureSpec.getSize(heightMeasureSpec);
            contentHeight = height;
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            for (int i = 0; i < getChildCount(); ++i) {
                View child = getChildAt(i);
                if (child instanceof HintView2) {
                    child.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(100), MeasureSpec.EXACTLY));
                } else if (child == recyclerListView) {
                    child.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height - bottomInset, MeasureSpec.EXACTLY));
                } else {
                    final int allowedHeight = child.getLayoutParams() != null && child.getLayoutParams().height == LayoutHelper.MATCH_PARENT ?
                        height : 9999;  // why 9999?

                    child.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(allowedHeight, MeasureSpec.AT_MOST));
                }
            }
            setMeasuredDimension(width, height);
            if (adapter != null) {
                adapter.setHeights(
                    topView.getFinalHeight(),
                    getBottomHeight() + (currentPage.to(PAGE_UPGRADE) && underButtonContainer.getVisibility() == View.VISIBLE ? underButtonContainer.getMeasuredHeight() : 0)
                );
            }
        }
    }

    private static class StickersRollView extends View {

        private final Theme.ResourcesProvider resourcesProvider;

        public StickersRollView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            drawSticker(canvas, a, aT, aIsFinish);
            drawSticker(canvas, b, bT, bIsFinish);
            drawSticker(canvas, c, cT, cIsFinish);
        }

        private Roller.Sticker a, b, c;
        private float aT, bT, cT;
        private boolean aIsFinish, bIsFinish, cIsFinish;

        private Roller.Background bgA, bgB, bgC;
        private float bgAT, bgBT, bgCT;
        private boolean bgAIsFinish, bgBIsFinish, bgCIsFinish;

        public void resetDrawing() {
            final boolean wasDrawing = a != null || b != null || c != null || bgA != null || bgB != null || bgC != null;
            a = b = c = null;
            aT = bT = cT = 0;
            aIsFinish = bIsFinish = cIsFinish = false;

            bgA = bgB = bgC = null;
            bgAT = bgBT = bgCT = 0;
            bgAIsFinish = bgBIsFinish = bgCIsFinish = false;

            if (wasDrawing) invalidate();
        }

        public void setDrawing(
            Roller.Sticker a, float aT, boolean aIsFinish,
            Roller.Sticker b, float bT, boolean bIsFinish,
            Roller.Sticker c, float cT, boolean cIsFinish,

            Roller.Background bgA, float bgAT, boolean bgAIsFinish,
            Roller.Background bgB, float bgBT, boolean bgBIsFinish,
            Roller.Background bgC, float bgCT, boolean bgCIsFinish
        ) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.aT = aT;
            this.bT = bT;
            this.cT = cT;
            this.aIsFinish = aIsFinish;
            this.bIsFinish = bIsFinish;
            this.cIsFinish = cIsFinish;
            this.bgA = bgA;
            this.bgB = bgB;
            this.bgC = bgC;
            this.bgAT = bgAT;
            this.bgBT = bgBT;
            this.bgCT = bgCT;
            this.bgAIsFinish = bgAIsFinish;
            this.bgBIsFinish = bgBIsFinish;
            this.bgCIsFinish = bgCIsFinish;
            invalidate();
        }

        private int lastBlurRx = 0;
        private void setBlurring(int rx) {
            if (lastBlurRx == rx) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                if (rx < 1) {
//                    lastBlurRx = rx;
//                    setRenderEffect(null);
//                } else {
//                    setRenderEffect(RenderEffect.createBlurEffect(lastBlurRx = rx, 0, Shader.TileMode.CLAMP));
//                }
            }
        }

        public boolean hasBackgrounds() {
            return bgA != null || bgB != null || bgC != null;
        }

        private final Camera camera = new Camera();
        private void drawSticker(Canvas canvas, Roller.Sticker sticker, float t, boolean isFinish) {
            if (sticker == null) return;
            if (isFinish) {
                t = Math.max(0.5f, t);
            }

            final float wasX = sticker.imageReceiver.getImageX();
            final float wasY = sticker.imageReceiver.getImageY();
            final float wasW = sticker.imageReceiver.getImageWidth();
            final float wasH = sticker.imageReceiver.getImageHeight();
            final float wasAlpha = sticker.imageReceiver.getAlpha();

//            final float alpha = Utilities.clamp01(1f - Math.abs(t));
//            final float alpha = Utilities.clamp01((float) Math.cos(t * Math.PI / 2.0f));
//            final float alpha = Utilities.clamp01(1.0f - (float) Math.pow(Math.abs(t), 2.0f));
            final float tt = (t - 0.5f) / 1.5f;
            final float alpha = Utilities.clamp01(1.0f - Math.abs(tt));

            final float cx = getWidth() / 2.0f - tt * dp(220);
            final float cy = dp(80);

            final float scale = lerp(0.85f, 1.0f, alpha);
            final float sz = dp(160);

            canvas.save();
            canvas.translate(cx + sz / 2.0f * tt, cy);
            camera.save();
            camera.rotateY(-30.0f * tt);
            camera.applyToCanvas(canvas);
            camera.restore();
            canvas.translate(-(cx + sz / 2.0f * tt), -cy);
            sticker.imageReceiver.setImageCoords(
                cx - sz * scale / 2.0f,
                cy - sz * scale / 2.0f,
                sz * scale,
                sz * scale
            );
            sticker.imageReceiver.setAlpha(alpha);
            sticker.imageReceiver.draw(canvas);
            sticker.imageReceiver.setImageCoords(wasX, wasY, wasW, wasH);
            sticker.imageReceiver.setAlpha(wasAlpha);
            canvas.restore();
        }

        private final GradientClip clip = new GradientClip();
        private final RectF rect = new RectF();
        private void drawBackground(Canvas canvas, Roller.Background bg, float t, float width, float height, int[] textColors, int[] backgroundColors, int[] patternColors) {
            if (bg == null || bg.backgroundPaint == null) return;

            final float tt = (t - 0.5f) / 1.5f;
            final float alpha = Utilities.clamp01(1.0f - Math.abs(tt));

            final float r = Math.max(.8f * width, dp(180));
            final float cx = width / 2.0f - tt * r * 1.8f;
            final float cy = Math.min(dp(16 + 160), height) / 2.0f;

            canvas.saveLayerAlpha(cx - r, 0, cx + r, height, 0xFF, Canvas.ALL_SAVE_FLAG);

            bg.backgroundMatrix.reset();
            bg.backgroundMatrix.postTranslate(cx, cy);
            bg.backgroundGradient.setLocalMatrix(bg.backgroundMatrix);
            bg.backgroundPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawRect(cx - r, 0, cx + r, height, bg.backgroundPaint);

            canvas.save();
            final float R = dp(90);
            rect.set(cx - r, 0, cx - r + R, height);
            clip.draw(canvas, rect, GradientClip.LEFT, 1.0f);
            rect.set(cx + r - R, 0, cx + r, height);
            clip.draw(canvas, rect, GradientClip.RIGHT, 1.0f);
            canvas.restore();

            canvas.restore();

            for (int i = 0; i < textColors.length; ++i) {
                final float x = i * ((float) getWidth() / (textColors.length - 1));
                final float thisAlpha = x < cx - r || x > cx + r ? 0 : Math.min(Utilities.clamp01((x - (cx - r)) / r), Utilities.clamp01(1.0f - (x - (cx + r - r)) / r));
                textColors[i] = Theme.blendOver(textColors[i], Theme.multAlpha(bg.textColor, alpha * thisAlpha));
            }
            for (int i = 0; i < backgroundColors.length; ++i) {
                final float x = i * ((float) getWidth() / (backgroundColors.length - 1));
                final float thisAlpha = x < cx - r || x > cx + r ? 0 : Math.min(Utilities.clamp01((x - (cx - r)) / r), Utilities.clamp01(1.0f - (x - (cx + r - r)) / r));
                backgroundColors[i] = Theme.blendOver(backgroundColors[i], Theme.multAlpha(bg.backgroundColor, alpha * thisAlpha));
            }
            for (int i = 0; i < patternColors.length; ++i) {
                final float x = i * ((float) getWidth() / (backgroundColors.length - 1));
                final float thisAlpha = x < cx - r || x > cx + r ? 0 : Math.min(Utilities.clamp01((x - (cx - r)) / r), Utilities.clamp01(1.0f - (x - (cx + r - r)) / r));
                patternColors[i] = Theme.blendOver(patternColors[i], Theme.multAlpha(bg.patternColor, alpha * thisAlpha));
            }
        }

        public void drawBackgrounds(Canvas canvas, float width, float height, int[] textColors, int[] backgroundColors, int[] patternColors) {
            drawBackground(canvas, bgA, bgAT, width, height, textColors, backgroundColors, patternColors);
            drawBackground(canvas, bgB, bgBT, width, height, textColors, backgroundColors, patternColors);
            drawBackground(canvas, bgC, bgCT, width, height, textColors, backgroundColors, patternColors);
        }

    }

    public static class TopView extends FrameLayout {

        private final Theme.ResourcesProvider resourcesProvider;
        public final FrameLayout imageLayout;
        private final StickersRollView imagesRollView;
        private final BackupImageView[] imageView = new BackupImageView[5];
        private final TL_stars.starGiftAttributeModel[] imageViewAttributes = new TL_stars.starGiftAttributeModel[3];
        private int currentImageIndex = 0;

        private final LinearLayout[] layout = new LinearLayout[5];
        private final FrameLayout.LayoutParams[] layoutLayoutParams = new FrameLayout.LayoutParams[5];

        private final GiftVisuals.Ribbon ribbon;
        private final LinkSpanDrawable.LinksTextView[] titleView = new LinkSpanDrawable.LinksTextView[5];
        private final LinkSpanDrawable.LinksTextView releasedView;
        private final TextView collectionReleasedView;
        private int collectionReleasedViewColor;
        private final FrameLayout subtitleContainer;
        private final LinkSpanDrawable.LinksTextView[] subtitleView = new LinkSpanDrawable.LinksTextView[5];
        private final LinearLayout.LayoutParams[] subtitleViewLayoutParams = new LinearLayout.LayoutParams[5];
        private final GiftMessageView[] messageTextView = new GiftMessageView[5];
        private TextPaint messageTextPaint;
        private final LinearLayout buttonsLayout;
        public final Button[] buttons;

        private FrameLayout userLayout;
        private BackupImageView avatarView;

        private boolean hasResellPrice;
        private final TextView resellPriceView;
        private final ImageView closeView;
        public final ImageView optionsView;

        public static class Button extends FrameLayout {

            public ImageView imageView;
            public TextView textView;

            public Button(Context context) {
                super(context);

                imageView = new ImageView(context);
                imageView.setScaleType(ImageView.ScaleType.CENTER);
                imageView.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
                addView(imageView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 8, 0, 0));

                textView = new TextView(context);
                textView.setTypeface(AndroidUtilities.bold());
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                textView.setTextColor(0xFFFFFFFF);
                textView.setGravity(Gravity.CENTER);
                addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 4, 35, 4, 0));
            }

            public void set(int iconResId, CharSequence text, boolean animated) {
                if (animated) {
                    AndroidUtilities.updateImageViewImageAnimated(imageView, iconResId);
                } else {
                    imageView.setImageResource(iconResId);
                }
                textView.setText(text);
            }
        }

        private View.OnClickListener onShareClick;
        public TopView(Context context, Theme.ResourcesProvider resourcesProvider, Runnable dismiss, OnClickListener onMenuClick, OnClickListener onWearClick, OnClickListener onShareClick) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            this.onShareClick = onShareClick;

            setWillNotDraw(false);

            imageLayout = new FrameLayout(context);
            for (int i = 0; i < imageView.length; ++i) {
                imageView[i] = new BackupImageView(context) {
                    @Override
                    public void setAlpha(float alpha) {
                        super.setAlpha(alpha);
                        setVisibility(alpha > 0 ? VISIBLE : INVISIBLE);
                    }
                };
                imageView[i].setLayerNum(4 | 6656);
                if (i > 0) {
                    imageView[i].getImageReceiver().setCrossfadeDuration(1);
                }
                imageLayout.addView(imageView[i], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
                imageView[i].setAlpha(i == currentImageIndex ? 1.0f : 0.0f);
            }

            releasedView = new LinkSpanDrawable.LinksTextView(context);
            releasedView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            releasedView.setGravity(Gravity.CENTER);
            releasedView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            releasedView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
            releasedView.setPadding(dp(4), 0, dp(4), 0);

            collectionReleasedView = new TextView(context);
            collectionReleasedView.setOnClickListener(v -> {
                CharSequence cs = collectionReleasedView.getText();
                if (!(cs instanceof Spanned)) return;
                ClickableSpan[] spans = ((Spanned) cs).getSpans(0, cs.length(), ClickableSpan.class);
                if (spans.length > 0) {
                    spans[0].onClick(v);
                }
            });
            ScaleStateListAnimator.apply(collectionReleasedView, .05f, 1.25f);
            collectionReleasedView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            collectionReleasedView.setGravity(Gravity.CENTER);
            collectionReleasedView.setLinkTextColor(0xFFFFFFFF);
            collectionReleasedView.setPadding(dp(7), 0, dp(7), 0);

            resellPriceView = new TextView(context);
            resellPriceView.setPadding(dp(8), 0, dp(8), 0);
            resellPriceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            resellPriceView.setTextColor(0xFFFFFFFF);
            resellPriceView.setTypeface(AndroidUtilities.bold());
            resellPriceView.setAlpha(0.f);
            resellPriceView.setScaleX(0.4f);
            resellPriceView.setScaleY(0.4f);
            resellPriceView.setVisibility(View.GONE);
            resellPriceView.setGravity(Gravity.CENTER);
            ScaleStateListAnimator.apply(resellPriceView);

            buttonsLayout = new LinearLayout(context) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (!currentPage.is(PAGE_INFO)) return false;
                    return super.dispatchTouchEvent(ev);
                }
            };
            buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
            buttons = new Button[2];
            for (int i = 0; i < buttons.length; ++i) {
                buttons[i] = new Button(context);
                switch (i) {
                    case 0:
                        buttons[i].set(R.drawable.filled_crown_on, getString(R.string.Gift2ActionWear), false);
                        buttons[i].setOnClickListener(onWearClick);
                        break;
                    case 1:
                        buttons[i].set(R.drawable.filled_share, getString(R.string.Gift2ActionShare), false);
                        buttons[i].setOnClickListener(onShareClick);
                        break;
                }
                buttons[i].setBackground(Theme.createRadSelectorDrawable(0, 0x10FFFFFF, 16, 16));
                ScaleStateListAnimator.apply(buttons[i], .075f, 1.5f);
                buttonsLayout.addView(buttons[i], LayoutHelper.createLinear(0, 56, 1, Gravity.FILL, 0, 0, i != buttons.length - 1 ? 11 : 0, 0));
            }

            subtitleContainer = new FrameLayout(context);
            for (int i = 0; i < layout.length; ++i) {
                layout[i] = new LinearLayout(context);
                layout[i].setOrientation(LinearLayout.VERTICAL);

                if (i == PAGE_WEAR) {
                    userLayout = new FrameLayout(context);
                    layout[i].addView(userLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 144, Gravity.FILL));

                    avatarView = new BackupImageView(context);
                    avatarView.setRoundRadius(dp(41));
                    userLayout.addView(avatarView, LayoutHelper.createFrame(82, 82, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

                    titleView[i] = new LinkSpanDrawable.LinksTextView(context);
                    titleView[i].setTextColor(0xFFFFFFFF);
                    titleView[i].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                    titleView[i].setTypeface(AndroidUtilities.bold());
                    titleView[i].setSingleLine();
                    titleView[i].setEllipsize(TextUtils.TruncateAt.END);
                    titleView[i].setGravity(Gravity.CENTER);
                    userLayout.addView(titleView[i], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 16, 95.33f, 16, 0));

                    subtitleView[i] = new LinkSpanDrawable.LinksTextView(context);
                    subtitleView[i].setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
                    subtitleView[i].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    subtitleView[i].setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
                    subtitleView[i].setLineSpacing(dp(2), 1f);
                    subtitleView[i].setDisablePaddingsOffsetY(true);
                    subtitleView[i].setSingleLine();
                    subtitleView[i].setGravity(Gravity.CENTER);
                    subtitleView[i].setEllipsize(TextUtils.TruncateAt.END);
                    userLayout.addView(subtitleView[i], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 16, 122, 16, 0));

                } else {
                    titleView[i] = new LinkSpanDrawable.LinksTextView(context);
                    titleView[i].setTextColor(i == PAGE_CRAFT ? 0xFFFFFFFF : Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
                    titleView[i].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                    titleView[i].setTypeface(AndroidUtilities.bold());
                    titleView[i].setGravity(Gravity.CENTER);
                    layout[i].addView(titleView[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, i == PAGE_CRAFT ? 10 : 0, 24, 0));

                    if (i == 0) {
                        layout[i].addView(releasedView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 4, 0, 4));
                        layout[i].addView(collectionReleasedView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 19.33f, Gravity.CENTER, 0, 6, 0, 2));
                    }

                    if (i == PAGE_INFO) {
                        subtitleView[i] = new LinkSpanDrawable.LinksTextView(context);
                        subtitleView[i].setTextColor(i == PAGE_CRAFT ? Theme.multAlpha(0xFFFFFFFF, .75f) : Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
                        subtitleView[i].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                        subtitleView[i].setGravity(Gravity.CENTER);
                        subtitleView[i].setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
                        subtitleView[i].setLineSpacing(dp(2), 1f);
                        subtitleView[i].setDisablePaddingsOffsetY(true);

                        subtitleContainer.addView(subtitleView[i], LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
                        subtitleContainer.addView(resellPriceView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20.33f, Gravity.CENTER));

                        layout[i].addView(subtitleContainer, subtitleViewLayoutParams[i] = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, i == PAGE_CRAFT ? 6 : 0));
                    } else {
                        subtitleView[i] = new LinkSpanDrawable.LinksTextView(context);
                        subtitleView[i].setTextColor(i == PAGE_CRAFT ? Theme.multAlpha(0xFFFFFFFF, .75f) : Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
                        subtitleView[i].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                        subtitleView[i].setGravity(Gravity.CENTER);
                        subtitleView[i].setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
                        subtitleView[i].setLineSpacing(dp(2), 1f);
                        subtitleView[i].setDisablePaddingsOffsetY(true);
                        layout[i].addView(subtitleView[i], subtitleViewLayoutParams[i] = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, i == PAGE_CRAFT ? 6 : 0));
                    }
                    subtitleViewLayoutParams[i].topMargin = dp(i == PAGE_CRAFT ? 6 : ((i == 1 ? 7.33f : (backdrop[0] == null ? 9 : 5.66f)) - 4));
                    messageTextView[i] = new GiftMessageView(context);
                    messageTextView[i].setVisibility(View.GONE);
                    messageTextView[i].setPadding(dp(30), dp(2), dp(30), dp(2));
                    if (i == 0) {
                        messageTextPaint = messageTextView[i].getTextPaint();
                    }
                    layout[i].addView(messageTextView[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 8, 24, 0));
                }

                if (i == PAGE_INFO) {
                    layout[i].addView(buttonsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL, 0, 15, 0, 0));
                }

                addView(layout[i], layoutLayoutParams[i] = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL, 16, i == 2 ? 32 : 8 + 160 + 2, 16, 0));
            }
            addView(imageLayout, LayoutHelper.createFrame(160, 160, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 8, 0, 0));

            imagesRollView = new StickersRollView(context, resourcesProvider);
            addView(imagesRollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 160, Gravity.FILL_HORIZONTAL | Gravity.TOP, 0, 8, 0, 0));

            closeView = new ImageView(context);
            closeView.setBackground(Theme.createCircleDrawable(dp(28), 0x24FFFFFF));
            closeView.setImageResource(R.drawable.msg_close);
            ScaleStateListAnimator.apply(closeView);
            addView(closeView, LayoutHelper.createFrame(28, 28, Gravity.RIGHT | Gravity.TOP, 0, 12, 12, 0));
            closeView.setOnClickListener(v -> dismiss.run());
            closeView.setVisibility(View.GONE);
//            addView(resellPriceView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 24, Gravity.LEFT | Gravity.TOP, 12, 14, 0, 0));

            optionsView = new ImageView(context);
            optionsView.setContentDescription(getString(R.string.AccDescrGoBack));
            optionsView.setImageResource(R.drawable.media_more);
            optionsView.setScaleType(ImageView.ScaleType.CENTER);
            optionsView.setBackground(Theme.createSelectorDrawable(0x20ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
            ScaleStateListAnimator.apply(optionsView);
            addView(optionsView, LayoutHelper.createFrame(42, 42, Gravity.TOP | Gravity.RIGHT, 0, 5, 5, 0));
            optionsView.setOnClickListener(onMenuClick);
            optionsView.setVisibility(View.GONE);

            ribbon = new GiftVisuals.Ribbon(context);
            ribbon.setText(getString(R.string.GiftCrafted), true);
            ribbon.drawable.setParticles(true);
            ribbon.drawable.setLeft(true);
            ribbon.setScaleX(1.2f);
            ribbon.setScaleY(1.2f);
            addView(ribbon, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));
            ribbon.setVisibility(View.GONE);
        }

        public void setText(int page, CharSequence title, CharSequence subtitle, CharSequence releasedSubtitle, CharSequence released) {
            setText(page, title, subtitle, releasedSubtitle, released, null, null);
        }

        public void setText(int page, CharSequence title, CharSequence subtitle, CharSequence releasedSubtitle, CharSequence released, TLObject fromObject, CharSequence message) {
            titleView[page].setText(title);
            if (page == 0 && !TextUtils.isEmpty(releasedSubtitle)) {
                collectionReleasedView.setText(releasedSubtitle);
                collectionReleasedView.setVisibility(View.VISIBLE);
                releasedView.setVisibility(View.GONE);
                if (page == PAGE_INFO) {
                    subtitleContainer.setVisibility(View.GONE);
                } else {
                    subtitleView[page].setVisibility(View.GONE);
                }
            } else if (page == 0 && !TextUtils.isEmpty(released)) {
                releasedView.setText(released);
                releasedView.setVisibility(View.VISIBLE);
                collectionReleasedView.setVisibility(View.GONE);
                if (page == PAGE_INFO) {
                    subtitleContainer.setVisibility(View.GONE);
                } else {
                    subtitleView[page].setVisibility(View.GONE);
                }
            } else {
                subtitleView[page].setText(subtitle);
                if (page == PAGE_INFO) {
                    subtitleContainer.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
                } else {
                    subtitleView[page].setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
                }
                releasedView.setVisibility(View.GONE);
                collectionReleasedView.setVisibility(View.GONE);
            }
            if (messageTextView[page] != null) {
                messageTextView[page].setVisibility(TextUtils.isEmpty(message) ? GONE : VISIBLE);
                messageTextView[page].setUser(fromObject);
                messageTextView[page].setMessage(message);
            }
        }

        private PageTransition currentPage = new PageTransition(PAGE_INFO, PAGE_INFO, 1.0f);
        public void onSwitchPage(PageTransition p) {
            currentPage = p;
            for (int i = 0; i < layout.length; ++i) {
                final  float alpha = p.at(i);
                layout[i].setAlpha(alpha);
                layout[i].setVisibility(alpha > 0 ? VISIBLE : INVISIBLE);
            }
            closeView.setAlpha(Math.max(backdrop[0] != null ? p.at(PAGE_WEAR) : 0.0f, backdrop[1] != null ? p.at(PAGE_UPGRADE) : 0.0f));
            closeView.setVisibility(backdrop[0] != null && p.to == PAGE_WEAR || backdrop[1] != null && p.to == PAGE_UPGRADE ? View.VISIBLE : View.GONE);
            optionsView.setAlpha(lerp(false, backdrop[0] != null, p.at(PAGE_INFO)));
            optionsView.setVisibility(backdrop[0] != null && p.to == PAGE_INFO ? View.VISIBLE : View.GONE);
            if (!resellPriceViewInProgress) {
                resellPriceView.setAlpha(lerp(false, hasResellPrice, p.at(PAGE_INFO)));
                resellPriceView.setScaleX(lerp(0.4f, hasResellPrice ? 1.0f : 0.4f, p.at(PAGE_INFO)));
                resellPriceView.setScaleY(lerp(0.4f, hasResellPrice ? 1.0f : 0.4f, p.at(PAGE_INFO)));
                resellPriceView.setVisibility(hasResellPrice && p.to == PAGE_INFO ? View.VISIBLE : View.INVISIBLE);
            }
            final int black = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
            for (int i = 0; i < 2; ++i) {
                titleView[i].setTextColor(backdrop[Math.min(1, i)] == null ? black : 0xFFFFFFFF);
                subtitleView[i].setTextColor(i == 0 || i == 2 ? (backdrop[i] == null ? black : backdrop[i].text_color | 0xFF000000) : ColorUtils.blendARGB(backdrop[1] == null ? black : backdrop[1].text_color | 0xFF000000, backdrop[2] == null ? black : backdrop[2].text_color | 0xFF000000, toggleBackdrop));

                boolean changed;
                if (backdrop[i] != null) {
                    changed = dp(24 + 160) != layoutLayoutParams[i].topMargin || layout[i].getPaddingBottom() != dp(18);
                    if (changed) {
                        layout[i].setPadding(0, 0, 0, dp(18));
                        layoutLayoutParams[i].topMargin = dp(24 + 160);
                    }
                } else {
                    changed = dp(8 + 160 + 2) != layoutLayoutParams[i].topMargin || layout[i].getPaddingBottom() != dp(3);
                    if (changed) {
                        layout[i].setPadding(0, 0, 0, dp(3));
                        layoutLayoutParams[i].topMargin = dp(8 + 160 + 2);
                    }
                }
                subtitleViewLayoutParams[i].topMargin = dp((i == 1 ? 7.33f : (backdrop[0] == null ? 9 : 5.66f)) - 4);
                if (changed) {
                    layout[i].setLayoutParams(layoutLayoutParams[i]);
                    if (i == PAGE_INFO) {
                        subtitleContainer.setLayoutParams(subtitleViewLayoutParams[i]);
                    } else {
                        subtitleView[i].setLayoutParams(subtitleViewLayoutParams[i]);
                    }
                }
            }
            collectionReleasedView.setBackground(Theme.createRoundRectDrawable(dp(24), backdrop[0] == null ? 0x20FFFFFF : ColorUtils.blendARGB(backdrop[0].edge_color | 0xFF000000, backdrop[0].pattern_color | 0xFF000000, .25f)));
            subtitleView[2].setTextColor(backdrop[0] == null ? black : backdrop[0].text_color | 0xFF000000);
            imageView[0].setAlpha(Math.max(currentPage.at(PAGE_INFO, PAGE_WEAR), currentPage.at(PAGE_CRAFT)));
            imageView[1].setAlpha(p.at(1) * (1.0f - toggleBackdrop));
            imageView[2].setAlpha(p.at(1) * toggleBackdrop);
            imageLayout.setScaleX(lerp(1.0f, wearImageScale, p.at(PAGE_WEAR)));
            imageLayout.setScaleY(lerp(1.0f, wearImageScale, p.at(PAGE_WEAR)));
            imageLayout.setTranslationX(wearImageTx * p.at(PAGE_WEAR));
            imageLayout.setTranslationY(dp(16) * p.at(PAGE_UPGRADE) + wearImageTy * p.at(PAGE_WEAR));
            layout[2].setTranslationY(p.from != PAGE_WEAR || p.to != PAGE_WEAR ? -(layout[p.from == PAGE_WEAR ? p.to : p.from].getMeasuredHeight() - layout[2].getMeasuredHeight()) * (1.0f - p.at(PAGE_WEAR)) : 0);
            ribbon.setVisibility(hasRibbon && currentPage.contains(PAGE_INFO) ? View.VISIBLE : View.GONE);
            ribbon.setAlpha(currentPage.at(PAGE_INFO));
            invalidate();
        }

        public void hideCloseButton() {
            removeView(closeView);
        }

        public void prepareSwitchPage(final PageTransition t) {
            if (t.from != t.to) {
                final RLottieDrawable fromAnimation = imageView[t.from].getImageReceiver().getLottieAnimation();
                final RLottieDrawable toAnimation = imageView[t.to].getImageReceiver().getLottieAnimation();
                if (toAnimation != null && fromAnimation != null) {
                    toAnimation.setProgress(fromAnimation.getProgress(), false);
                }
            }
        }

        protected final TL_stars.starGiftAttributeBackdrop[] backdrop = new TL_stars.starGiftAttributeBackdrop[3];

        private ArrayList<TL_stars.StarGiftAttribute> sampleAttributes;
        private BagRandomizer<TL_stars.starGiftAttributeModel> models;
        private BagRandomizer<TL_stars.starGiftAttributePattern> patterns;
        private BagRandomizer<TL_stars.starGiftAttributeBackdrop> backdrops;

        private boolean hasRibbon;
        private boolean hasLink;
        public void setGift(TL_stars.StarGift gift, boolean isOwner, boolean isHost, boolean worn, boolean hasLink, boolean rolling) {
            hasResellPrice = false;
            final int page = 0;
            final boolean withButtons = isOwner || isHost;
            if (gift instanceof TL_stars.TL_starGiftUnique) {
                backdrop[page] = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
                setPattern(page, findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class), false);
                subtitleView[page].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                buttonsLayout.setVisibility(withButtons ? View.VISIBLE : View.GONE);
                if (withButtons) {
                    buttons[0].set(worn ? R.drawable.filled_crown_off : R.drawable.filled_crown_on, getString(worn ? R.string.Gift2ActionWearOff : R.string.Gift2ActionWear), false);
                }
                if (gift.resell_amount != null) {
                    hasResellPrice = true;

                    final AmountUtils.Amount price = gift.getResellAmount(gift.resale_ton_only ? AmountUtils.Currency.TON : AmountUtils.Currency.STARS);
                    resellPriceView.setText(LocaleController.formatSpannable(R.string.GiftOnSale, StarsIntroActivity.replaceStars(
                        price.currency == AmountUtils.Currency.TON,
                        "⭐️ " + StarsIntroActivity.formatStarsAmount(price.toTl(), 1, ',')),
                        0.9f
                    ));

                    final int backgroundColor = ColorUtils.blendARGB(backdrop[0].edge_color | 0xFF000000, backdrop[0].pattern_color | 0xFF000000, .25f);
                    resellPriceView.setBackground(Theme.createRoundRectDrawable(dp(12), backgroundColor));
                    resellPriceView.setOnClickListener(null);
                    ScaleStateListAnimator.reset(resellPriceView);
                }

                buttons[0].setAlpha(isOwner || isHost ? 1.0f : 0.5f);

                buttons[1].set(R.drawable.filled_share, getString(R.string.Gift2ActionShare), false);
                buttons[1].setOnClickListener(onShareClick);

                hasRibbon = gift.crafted;
                ribbon.drawable.setBackdrop(backdrop[page], false, true);
            } else {
                backdrop[page] = null;
                setPattern(page, null, false);
                subtitleView[page].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                hasRibbon = false;
                buttonsLayout.setVisibility(View.GONE);
            }
            this.hasLink = hasLink;
            setBackdropPaint(page, backdrop[page]);
//            if (rolling && gift instanceof TL_stars.TL_starGiftUnique) {
//                imageLayout.setVisibility(View.INVISIBLE);
//                imagesRollView.start(getUpgradeImageView(), findAttribute(gift.attributes, TL_stars.starGiftAttributeModel.class), () -> {
//                    imageLayout.setVisibility(View.VISIBLE);
//                    setGiftImage(imageView[page].getImageReceiver(), gift, 160);
//                });
//            } else {
                setGiftImage(imageView[page].getImageReceiver(), gift, 160);
                imageViewAttributes[page] = findAttribute(gift.attributes, TL_stars.starGiftAttributeModel.class);
//            }
            onSwitchPage(currentPage);
        }

        public BackupImageView getUpgradeImageView() {
            return toggleBackdrop > 0.5f ? imageView[2] : imageView[1];
        }

        public TL_stars.starGiftAttributeModel getUpgradeImageViewAttribute() {
            return toggleBackdrop > 0.5f ? imageViewAttributes[2] : imageViewAttributes[1];
        }

        public TL_stars.starGiftAttributeBackdrop getUpgradeBackdropAttribute() {
            return toggleBackdrop > 0.5f ? backdrop[2] : backdrop[1];
        }

        public TL_stars.starGiftAttributePattern getUpgradePatternAttribute() {
            return patternAttribute[1];
        }

        private boolean resellPriceViewInProgress;

        public void setPreviewingAttributes(/* int page = 1, */ArrayList<TL_stars.StarGiftAttribute> sampleAttributes) {
            final int page = 1;

            this.sampleAttributes = sampleAttributes;
            models = new BagRandomizer(findAttributes(sampleAttributes, TL_stars.starGiftAttributeModel.class));
            patterns = new BagRandomizer(findAttributes(sampleAttributes, TL_stars.starGiftAttributePattern.class));
            backdrops = new BagRandomizer(findAttributes(sampleAttributes, TL_stars.starGiftAttributeBackdrop.class));

//            imagesRollView.preload(sampleAttributes);

            subtitleView[page].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            buttonsLayout.setVisibility(View.GONE);

            toggleBackdrop = 0.0f;
            toggled = 0;
            setPattern(1, patterns.next(), true);

            imageViewAttributes[page] = models.next();
            setGiftImage(imageView[page].getImageReceiver(), imageViewAttributes[page].document, 160);
            setBackdropPaint(1, backdrop[1] = backdrops.next());

            imageViewAttributes[page + 1] = models.getNext();
            setGiftImage(imageView[page + 1].getImageReceiver(), imageViewAttributes[page + 1].document, 160);

            AndroidUtilities.cancelRunOnUIThread(checkToRotateRunnable);
            AndroidUtilities.runOnUIThread(checkToRotateRunnable, 2500);

            invalidate();
        }

        private TLObject wearPreviewObject;
        private float wearImageTx, wearImageTy, wearImageScale;
        public void setWearPreview(TLObject object) {
            wearPreviewObject = object;
            String title, subtitle;
            if (object instanceof TLRPC.User) {
                final TLRPC.User user = (TLRPC.User) object;
                title = UserObject.getUserName(user);
                subtitle = getString(R.string.Online);
            } else if (object instanceof TLRPC.Chat) {
                final TLRPC.Chat chat = (TLRPC.Chat) object;
                title = chat == null ? "" : chat.title;
                if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                    if (chat.participants_count > 1) {
                        subtitle = LocaleController.formatPluralStringComma("Subscribers", chat.participants_count);
                    } else {
                        subtitle = LocaleController.getString(R.string.DiscussChannel);
                    }
                } else {
                    if (chat.participants_count > 1) {
                        subtitle = LocaleController.formatPluralStringComma("Members", chat.participants_count);
                    } else {
                        subtitle = LocaleController.getString(R.string.AccDescrGroup).toLowerCase();
                    }
                }
            } else {
                return;
            }

            final AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(object);
            avatarView.setForUserOrChat(object, avatarDrawable);
            titleView[PAGE_WEAR].setText(title);
            subtitleView[PAGE_WEAR].setText(subtitle);

            updateWearImageTranslation();
            onSwitchPage(currentPage);
        }

        private void updateWearImageTranslation() {
            wearImageScale = dpf2(33.33f) / dpf2(160.0f);
            wearImageTx = -imageLayout.getLeft() + titleView[PAGE_WEAR].getX() + (titleView[PAGE_WEAR].getWidth() + Math.min(titleView[PAGE_WEAR].getPaint().measureText(titleView[PAGE_WEAR].getText().toString()), titleView[PAGE_WEAR].getWidth())) / 2.0f + dp(24) - dp(160 - 33.33f) / 2.0f;
            wearImageTy = -imageLayout.getTop() + dp(124) - dp(160 - 33.33f) / 2.0f;
        }

        private ValueAnimator rotationAnimator;
        private final Runnable checkToRotateRunnable = () -> {
            BackupImageView imageView = this.imageView[1 + (1 - this.toggled)];
            if (imageView.getImageReceiver().hasImageLoaded()) {
                rotateAttributes();
            } else {
                AndroidUtilities.cancelRunOnUIThread(this.checkToRotateRunnable);
                AndroidUtilities.runOnUIThread(this.checkToRotateRunnable, 150);
            }
        };



        private void rotateAttributes() {
            if (currentPage == null || currentPage.to != PAGE_UPGRADE || !isAttachedToWindow()) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(checkToRotateRunnable);
            if (rotationAnimator != null) {
                rotationAnimator.cancel();
                rotationAnimator = null;
            }

            toggled = 1 - toggled;

            final RLottieDrawable fromAnimation = imageView[1 + (1 - toggled)].getImageReceiver().getLottieAnimation();
            final RLottieDrawable toAnimation = imageView[1 + toggled].getImageReceiver().getLottieAnimation();
            if (toAnimation != null && fromAnimation != null) {
                toAnimation.setProgress(fromAnimation.getProgress(), false);
            }

            models.next();
            setBackdropPaint(1 + toggled, backdrop[1 + toggled] = backdrops.next());
            setPattern(1, patterns.next(), true);
            animateSwitch();

            rotationAnimator = ValueAnimator.ofFloat(1.0f - toggled, toggled);
            rotationAnimator.addUpdateListener(anm -> {
                toggleBackdrop = (float) anm.getAnimatedValue();
                onSwitchPage(currentPage);
            });
            rotationAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    toggleBackdrop = toggled;
                    onSwitchPage(currentPage);
                    imageViewAttributes[1 + (1 - toggled)] = models.getNext();
                    setGiftImage(imageView[1 + (1 - toggled)].getImageReceiver(), imageViewAttributes[1 + (1 - toggled)].document, 160);
                    preloadPattern(patterns.getNext());

                    AndroidUtilities.cancelRunOnUIThread(checkToRotateRunnable);
                    AndroidUtilities.runOnUIThread(checkToRotateRunnable, 2500);
                }
            });
            rotationAnimator.setDuration(320);
            rotationAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            rotationAnimator.start();
        }

        private final Paint[] backgroundPaint = new Paint[3];
        private final RadialGradient[] backgroundGradient = new RadialGradient[3];
        private final Matrix[] backgroundMatrix = new Matrix[3];
        private RadialGradient profileBackgroundGradient;
        private final Matrix profileBackgroundMatrix = new Matrix();
        private Paint profileBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final TL_stars.starGiftAttributePattern[] patternAttribute = new TL_stars.starGiftAttributePattern[2];
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable[] pattern = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable[2];
        {
            for (int i = 0; i < backgroundPaint.length; ++i) {
                backgroundPaint[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            }
            for (int i = 0; i < pattern.length; ++i) {
                pattern[i] = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(this, dp(28));
            }
        }

        private int toggled;
        private float toggleBackdrop;

        private void setBackdropPaint(int p, TL_stars.starGiftAttributeBackdrop backdrop) {
            if (backdrop == null) return;
            backgroundGradient[p] = new RadialGradient(0, 0, dp(200), new int[] { backdrop.center_color | 0xFF000000, backdrop.edge_color | 0xFF000000 }, new float[] { 0, 1 }, Shader.TileMode.CLAMP);
            if (p == 0) {
                profileBackgroundGradient = new RadialGradient(
                    0, 0, dp(168),
                    new int[] { backdrop.center_color | 0xFF000000, backdrop.edge_color | 0xFF000000 },
                    new float[] { 0, 1 },
                    Shader.TileMode.CLAMP
                );
                profileBackgroundPaint.setShader(profileBackgroundGradient);
            }
            if (backgroundMatrix[p] == null) {
                backgroundMatrix[p] = new Matrix();
            }
            backgroundPaint[p].setShader(backgroundGradient[p]);
        }

        public void setPattern(int p, TL_stars.starGiftAttributePattern pattern, boolean animated) {
            if (pattern == null || this.patternAttribute[p] == pattern) return;
            this.patternAttribute[p] = pattern;
            this.pattern[p].set(pattern.document, animated);
        }

        private void preloadPattern(TL_stars.starGiftAttributePattern pattern) {
            if (pattern == null) return;
            AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.CACHE_TYPE_EMOJI_STATUS, pattern.document).preload();
        }

        private boolean attached;
        @Override
        protected void onAttachedToWindow() {
            attached = true;
            super.onAttachedToWindow();
            this.pattern[0].attach();
            this.pattern[1].attach();
        }

        @Override
        protected void onDetachedFromWindow() {
            attached = false;
            super.onDetachedFromWindow();
            this.pattern[0].detach();
            this.pattern[1].detach();
            AndroidUtilities.cancelRunOnUIThread(checkToRotateRunnable);
        }

        private float switchScale = 1;
        private ValueAnimator switchAnimator;
        private void animateSwitch() {
            if (switchAnimator != null) {
                switchAnimator.cancel();
                switchAnimator = null;
            }

            switchAnimator = ValueAnimator.ofFloat(0, 1);
            switchAnimator.addUpdateListener(anm -> {
                final float x = (float) anm.getAnimatedValue();
                switchScale = 1.0f + 0.6f * .125f * (float) Math.pow(2 * x - 2, 2) * x;
                imageLayout.setScaleX(switchScale);
                imageLayout.setScaleY(switchScale);
                invalidate();
            });
            switchAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    switchScale = 1;
                    imageLayout.setScaleX(switchScale);
                    imageLayout.setScaleY(switchScale);
                    invalidate();
                }
            });
            switchAnimator.setDuration(320);
            switchAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            switchAnimator.start();
        }

        private final RectF particlesBounds = new RectF();
        private ReactionParticles particles;
        protected final int[] backgroundColors = new int[12];
        private final int[] textColors = new int[12];
        private final int[] patternColors = new int[12];

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            final float height = getRealHeight();

            canvas.save();
            canvas.clipRect(0, 0, getWidth(), height);

            final float cx = getWidth() / 2.0f;
            final float cy = lerp(dp(8), dp(24), currentPage.at(PAGE_UPGRADE)) + dp(160 / 2);

            final float infoBackdrop = currentPage.at(PAGE_INFO, PAGE_WEAR, PAGE_CRAFT);
            if (infoBackdrop > 0 && backdrop[0] != null) {
                if (profileBackgroundGradient == null || currentPage.at(PAGE_WEAR) < 1) {
                    backgroundPaint[0].setAlpha((int) (0xFF * infoBackdrop));
                    backgroundMatrix[0].reset();
                    backgroundMatrix[0].postTranslate(cx, cy);
                    backgroundGradient[0].setLocalMatrix(backgroundMatrix[0]);
                    canvas.drawRect(0, 0, getWidth(), height, backgroundPaint[0]);
                }

                if (profileBackgroundGradient != null && currentPage.at(PAGE_WEAR) > 0) {
                    profileBackgroundPaint.setAlpha((int) (0xFF * currentPage.at(PAGE_WEAR)));
                    profileBackgroundMatrix.reset();
                    profileBackgroundMatrix.postTranslate(getWidth() / 2.0f, height * 0.4f);
                    profileBackgroundGradient.setLocalMatrix(profileBackgroundMatrix);
                    canvas.drawRect(0, 0, getWidth(), height, profileBackgroundPaint);
                }
            }
            if (currentPage.at(PAGE_UPGRADE) > 0) {
                int color = drawBackground(canvas, cx, cy, getWidth(), height);
                updateButtonsBackgrounds(color);
            }

            if (backdrop[0] != null) {
                for (int i = 0; i < backgroundColors.length; ++i) {
                    textColors[i] = backdrop[0].text_color | 0xFF000000;
                    backgroundColors[i] = ColorUtils.blendARGB(backdrop[0].edge_color | 0xFF000000, backdrop[0].pattern_color | 0xFF000000, .25f);
                    patternColors[i] = backdrop[0].pattern_color | 0xFF000000;
                }
            }
            if (imagesRollView.hasBackgrounds()) {
                imagesRollView.drawBackgrounds(canvas, getWidth(), height, textColors, backgroundColors, patternColors);
                invalidate();
            }

            if (infoBackdrop > 0 && backdrop[0] != null) {
                final int centerPatternColor = patternColors[patternColors.length / 2];
                final float pattern0Alpha = currentPage.at(PAGE_INFO, PAGE_CRAFT);
                if (pattern0Alpha > 0) {
                    canvas.save();
                    canvas.translate(cx, cy);
                    pattern[0].setColor(centerPatternColor);
                    StarGiftPatterns.drawPattern(canvas, pattern[0], getWidth(), height, pattern0Alpha, 1.0f);
                    canvas.restore();
                }
                if (currentPage.at(PAGE_WEAR) > 0) {
                    canvas.save();
                    pattern[0].setColor(centerPatternColor);
                    AndroidUtilities.rectTmp.set(
                        layout[2].getX() + userLayout.getX() + avatarView.getX(), layout[2].getY() + userLayout.getY() + avatarView.getY(),
                        layout[2].getX() + userLayout.getX() + avatarView.getX() + avatarView.getWidth(), layout[2].getY() + userLayout.getY() + avatarView.getY() + avatarView.getHeight()
                    );
                    StarGiftPatterns.drawProfileAnimatedPattern(canvas, pattern[0], getWidth(), height * 0.7f, 1.0f, AndroidUtilities.rectTmp, currentPage.at(PAGE_WEAR));
                    canvas.restore();
                }

                for (Button btn : buttons) {
                    final float bcx = btn.getX() + btn.getWidth() / 2.0f;
                    final int color = backgroundColors[Utilities.clamp(Math.round(bcx / getWidth() * (backgroundColors.length - 1)), backgroundColors.length - 1, 0)];
                    if (Theme.setSelectorDrawableColor(btn.getBackground(), color, false)) {
                        btn.invalidate();
                    }
                }

                final int centerTextColor = textColors[textColors.length / 2];
                final int centerBackgroundColor = backgroundColors[backgroundColors.length / 2];
                if (collectionReleasedView != null && collectionReleasedViewColor != centerTextColor) {
                    collectionReleasedView.setTextColor(collectionReleasedViewColor = centerTextColor);
                    Theme.setSelectorDrawableColor(collectionReleasedView.getBackground(), centerBackgroundColor, false);
                }
                if (imagesRollView.hasBackgrounds()) {
                    subtitleView[0].setTextColor(centerTextColor);
                }

                if (currentPage.at(PAGE_WEAR) > 0) {
                    if (particles == null) {
                        particles = new ReactionParticles(ReactionParticles.TYPE_RADIAL, 12);
                    }
                    final float imageCx = imageLayout.getX() + imageLayout.getMeasuredWidth() / 2.0f, imageHw = imageLayout.getMeasuredWidth() * imageLayout.getScaleX() / 2.0f;
                    final float imageCy = imageLayout.getY() + imageLayout.getMeasuredHeight() / 2.0f, imageHh = imageLayout.getMeasuredHeight() * imageLayout.getScaleY() / 2.0f;
                    particlesBounds.set(imageCx - imageHw, imageCy - imageHh, imageCx + imageHw, imageCy + imageHh);
                    particles.setBounds(particlesBounds);
                    particles.process();
                    particles.draw(canvas, Theme.multAlpha(0xFFFFFFFF, currentPage.at(PAGE_WEAR)));
                    invalidate();
                }
            }
            if (currentPage.at(PAGE_UPGRADE) > 0) {
                drawPattern(canvas, cx, cy, getWidth(), getRealHeight());
            }

            super.dispatchDraw(canvas);
            canvas.restore();
        }

        public void drawPattern(Canvas canvas, float cx, float cy, float width, float height) {
            canvas.save();
            canvas.translate(cx, cy);
            final int patternColor = ColorUtils.blendARGB(backdrop[1] == null ? 0 : backdrop[1].pattern_color | 0xFF000000, backdrop[2] == null ? 0 : backdrop[2].pattern_color | 0xFF000000, toggleBackdrop);
            pattern[1].setColor(patternColor);
            StarGiftPatterns.drawPattern(canvas, pattern[1], width, height, currentPage.at(PAGE_UPGRADE), switchScale);
            canvas.restore();
        }

        public int drawBackground(Canvas canvas, float cx, float cy, float width, float height) {
            int buttonsColor = 0;
            if (toggled == 0) {
                if (toggleBackdrop > 0.0f && backdrop[2] != null) {
                    backgroundPaint[2].setAlpha((int) (0xFF * currentPage.at(PAGE_UPGRADE)));
                    backgroundMatrix[2].reset();
                    backgroundMatrix[2].postTranslate(cx, cy);
                    backgroundGradient[2].setLocalMatrix(backgroundMatrix[2]);
                    canvas.drawRect(0, 0, width, height, backgroundPaint[2]);

                    final int c = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(
                            backdrop[2].edge_color | 0xFF000000,
                            backdrop[2].pattern_color | 0xFF000000, .25f
                    ), backgroundPaint[2].getAlpha());
                    buttonsColor = ColorUtils.compositeColors(c, buttonsColor);
                }
                if (toggleBackdrop < 1.0f && backdrop[1] != null) {
                    backgroundPaint[1].setAlpha((int) (0xFF * currentPage.at(PAGE_UPGRADE) * (1.0f - toggleBackdrop)));
                    backgroundMatrix[1].reset();
                    backgroundMatrix[1].postTranslate(cx, cy);
                    backgroundGradient[1].setLocalMatrix(backgroundMatrix[1]);
                    canvas.drawRect(0, 0, width, height, backgroundPaint[1]);

                    final int c = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(
                            backdrop[1].edge_color | 0xFF000000,
                            backdrop[1].pattern_color | 0xFF000000, .25f
                    ), backgroundPaint[1].getAlpha());
                    buttonsColor = ColorUtils.compositeColors(c, buttonsColor);
                }
            } else {
                if (toggleBackdrop < 1.0f && backdrop[1] != null) {
                    backgroundPaint[1].setAlpha((int) (0xFF * currentPage.at(PAGE_UPGRADE)));
                    backgroundMatrix[1].reset();
                    backgroundMatrix[1].postTranslate(cx, cy);
                    backgroundGradient[1].setLocalMatrix(backgroundMatrix[1]);
                    canvas.drawRect(0, 0, width, height, backgroundPaint[1]);

                    final int c = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(
                            backdrop[1].edge_color | 0xFF000000,
                            backdrop[1].pattern_color | 0xFF000000, .25f
                    ), backgroundPaint[1].getAlpha());
                    buttonsColor = ColorUtils.compositeColors(c, buttonsColor);
                }
                if (toggleBackdrop > 0.0f && backdrop[2] != null) {
                    backgroundPaint[2].setAlpha((int) (0xFF * currentPage.at(PAGE_UPGRADE) * toggleBackdrop));
                    backgroundMatrix[2].reset();
                    backgroundMatrix[2].postTranslate(cx, cy);
                    backgroundGradient[2].setLocalMatrix(backgroundMatrix[2]);
                    canvas.drawRect(0, 0, width, height, backgroundPaint[2]);

                    final int c = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(
                            backdrop[2].edge_color | 0xFF000000,
                            backdrop[2].pattern_color | 0xFF000000, .25f
                    ), backgroundPaint[2].getAlpha());
                    buttonsColor = ColorUtils.compositeColors(c, buttonsColor);
                }
            }

            return buttonsColor;
        }

        public float getRealHeight() {
            float h = 0;
            h += (dp(backdrop[0] != null ? 24 : 10) + dp(160) + layout[0].getMeasuredHeight()) * currentPage.at(PAGE_INFO);
            h += (dp(backdrop[1] != null ? 24 : 10) + dp(160) + layout[1].getMeasuredHeight()) * currentPage.at(PAGE_UPGRADE);
            h += (dp(64) + layout[PAGE_WEAR].getMeasuredHeight()) * currentPage.at(PAGE_WEAR);
            h += (dp(backdrop[0] != null ? 24 : 10) + dp(160) + layout[PAGE_CRAFT].getMeasuredHeight()) * currentPage.at(PAGE_CRAFT);
            return h;
        }

        public int getFinalHeight() {
            if (currentPage.to(PAGE_INFO)) {
                return dp(backdrop[0] != null ? 24 : 10) + dp(160) + layout[0].getMeasuredHeight();
            }
            if (currentPage.to(PAGE_UPGRADE)) {
                return dp(backdrop[1] != null ? 24 : 10) + dp(160) + layout[1].getMeasuredHeight();
            }
            if (currentPage.to(PAGE_WEAR)) {
                return dp(64) + layout[2].getMeasuredHeight();
            }
            if (currentPage.to(PAGE_CRAFT)) {
                return dp(160) + layout[PAGE_CRAFT].getMeasuredHeight();
            }

            return 0;
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (currentPage.contains(PAGE_WEAR)) {
                updateWearImageTranslation();
                onSwitchPage(currentPage);
            }
        }

        protected void updateButtonsBackgrounds(int color) {

        }
    }

    public static final int PAGE_INFO = 0;
    public static final int PAGE_UPGRADE = 1;
    public static final int PAGE_WEAR = 2;
    public static final int PAGE_CRAFT = 3;
    public static final int PAGE_CRAFTING = 4;

    private Float lastTop;
    private PageTransition currentPage = new PageTransition(PAGE_INFO, PAGE_INFO, 1.0f);
    private ValueAnimator switchingPagesAnimator;
    public void switchPage(int page, boolean animated) {
        switchPage(page, animated, null);
    }
    public void switchPage(int page, boolean animated, Runnable done) {
        if (switchingPagesAnimator != null) {
            switchingPagesAnimator.cancel();
            switchingPagesAnimator = null;
        }

        if (page != PAGE_UPGRADE) {
            AndroidUtilities.cancelRunOnUIThread(topView.checkToRotateRunnable);
        }

        if (!firstSet) {
            lastTop = container.top();
        }
        currentPage = new PageTransition(currentPage == null ? PAGE_INFO : currentPage.to, page, 0.0f);
        adapter.setHeights(topView.getFinalHeight(), getBottomHeight() + (currentPage.to(PAGE_UPGRADE) && underButtonContainer.getVisibility() == View.VISIBLE ? underButtonContainer.getMeasuredHeight() : 0));
        if (currentPage.to == PAGE_INFO && roller != null) {
            roller.stopPreload();
        }
        if (animated) {
            infoLayout.setVisibility(currentPage.contains(PAGE_INFO) ? View.VISIBLE : View.GONE);
            wearLayout.setVisibility(currentPage.contains(PAGE_WEAR) ? View.VISIBLE : View.GONE);
            switchingPagesAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            switchingPagesAnimator.addUpdateListener(a -> {
                currentPage.setProgress((float) a.getAnimatedValue());
                onSwitchedPage();
            });
            switchingPagesAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onSwitchedPage();
                    infoLayout.setVisibility(page == PAGE_INFO ? View.VISIBLE : View.GONE);
                    wearLayout.setVisibility(page == PAGE_WEAR ? View.VISIBLE : View.GONE);
                    updateUnderButtonContainer();
                    switchingPagesAnimator = null;
                    if (done != null) {
                        done.run();
                    }
                }
            });
            switchingPagesAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            switchingPagesAnimator.setDuration(320);
            switchingPagesAnimator.start();
            topView.prepareSwitchPage(currentPage);
        } else {
            currentPage.setProgress(1.0f);
            onSwitchedPage();
            infoLayout.setVisibility(page == PAGE_INFO ? View.VISIBLE : View.GONE);
            wearLayout.setVisibility(page == PAGE_WEAR ? View.VISIBLE : View.GONE);
            updateUnderButtonContainer();
            if (done != null) {
                done.run();
            }
        }

        if (currentHintView != null) {
            currentHintView.hide();
            currentHintView = null;
        }
    }

    private void updateUnderButtonContainer() {
        if (underButtonContainer.getVisibility() == View.VISIBLE) {
            buttonContainer.setTranslationY(-underButtonContainer.getMeasuredHeight() * currentPage.at(PAGE_UPGRADE));
            underButtonContainer.setTranslationY(underButtonContainer.getMeasuredHeight() * (1.0f - currentPage.at(PAGE_UPGRADE)));
            bottomBulletinContainer.setTranslationY(-underButtonContainer.getMeasuredHeight() * currentPage.at(PAGE_UPGRADE));
        } else {
            buttonContainer.setTranslationY(0);
            underButtonContainer.setTranslationY(0);
            bottomBulletinContainer.setTranslationY(0);
        }
    }

    private int getBottomHeight() {
        if (currentPage.to(PAGE_WEAR)) return wearLayout.getMeasuredHeight();
        if (currentPage.to(PAGE_CRAFTING)) return 0;
        return infoLayout.getMeasuredHeight();
    }

    private void onSwitchedPage() {
        infoLayout.setAlpha(currentPage.at(PAGE_INFO));
        wearLayout.setAlpha(currentPage.at(PAGE_WEAR));
        buttonContainer.setAlpha(1.0f - currentPage.at(PAGE_CRAFTING));
        topView.onSwitchPage(currentPage);
        final float actionAlpha = Utilities.clamp01(AndroidUtilities.ilerp(container.top() - actionView.getHeight(), 0, dp(32)));
        actionView.setAlpha(currentPage.at(PAGE_INFO) * actionAlpha);
        container.updateTranslations();
        container.invalidate();
        buttonContainer.setVisibility(currentPage.is(PAGE_CRAFTING) ? View.GONE : View.VISIBLE);
        updateUnderButtonContainer();
    }

    public static void addAttributeRow(TableView tableView, TL_stars.StarGiftAttribute attr) {
        String name;
        if (attr instanceof TL_stars.starGiftAttributeModel) {
            name = getString(R.string.Gift2AttributeModel);
        } else if (attr instanceof TL_stars.starGiftAttributePattern) {
            name = getString(R.string.Gift2AttributeSymbol);
        } else if (attr instanceof TL_stars.starGiftAttributeBackdrop) {
            name = getString(R.string.Gift2AttributeBackdrop);
        } else return;

        final Integer[] color = new Integer[1];
        final CharSequence rarity = getRarityName(attr.rarity, color);
        tableView.addRow(name, attr.name, rarity, null, color[0]);
    }

    public static CharSequence getRarityName(TL_stars.StarGiftAttributeRarity rarity) {
        return getRarityName(rarity, null);
    }
    public static CharSequence getRarityName(TL_stars.StarGiftAttributeRarity rarity, Integer[] color) {
        if (rarity instanceof TL_stars.TL_starGiftAttributeRarityUncommon) {
            if (color != null) color[0] = 0xFF40A920;
            return getString(R.string.GiftRarityUncommon);
        } else if (rarity instanceof TL_stars.TL_starGiftAttributeRarityRare) {
            if (color != null) color[0] = 0xFF11AABE;
            return getString(R.string.GiftRarityRare);
        } else if (rarity instanceof TL_stars.TL_starGiftAttributeRarityEpic) {
            if (color != null) color[0] = 0xFF955CDB;
            return getString(R.string.GiftRarityEpic);
        } else if (rarity instanceof TL_stars.TL_starGiftAttributeRarityLegendary) {
            if (color != null) color[0] = 0xFFBF7600;
            return getString(R.string.GiftRarityLegendary);
        } else if (rarity instanceof TL_stars.TL_starGiftAttributeRarity) {
            final int permille = ((TL_stars.TL_starGiftAttributeRarity) rarity).permille;
            if (permille <= 0)
                return "<0.1%";
            return percents(permille);
        }
        return "";
    }

    private void addAttributeRow(TL_stars.StarGiftAttribute attr) {
        int index;
        Class clazz;
        String name;
        if (attr instanceof TL_stars.starGiftAttributeModel) {
            index = 2;
            clazz = TL_stars.starGiftAttributeModel.class;
            name = getString(R.string.Gift2AttributeModel);
        } else if (attr instanceof TL_stars.starGiftAttributePattern) {
            index = 1;
            clazz = TL_stars.starGiftAttributePattern.class;
            name = getString(R.string.Gift2AttributeSymbol);
        } else if (attr instanceof TL_stars.starGiftAttributeBackdrop) {
            index = 0;
            clazz = TL_stars.starGiftAttributeBackdrop.class;
            name = getString(R.string.Gift2AttributeBackdrop);
        } else return;
        if (rolling || roller != null && roller.isRolling()) {
            TextViewRoll textView = new TextViewRoll(getContext(), resourcesProvider, this::showHint);

            final TableRow row = new TableRow(getContext());
            TableRow.LayoutParams lp;
            lp = new TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            row.addView(new TableView.TableRowTitle(tableView, name), lp);
            lp = new TableRow.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            row.addView(new TableView.TableRowContent(tableView, textView, true), lp);
            tableView.addView(row);

            if (roller != null) {
                if (index == 0) roller.backdropText = textView;
                if (index == 1) roller.patternText = textView;
                if (index == 2) roller.modelText = textView;
            }
        } else {
            final boolean[] loading = new boolean[1];
            final ButtonSpan.TextViewButtons[] textViewArr = new ButtonSpan.TextViewButtons[1];
            final TableRow row;
            final Integer[] color = new Integer[1];
            final CharSequence rarity = getRarityName(attr.rarity, color);
            final Runnable onClick;
            if (attr.rarity instanceof TL_stars.TL_starGiftAttributeRarity) {
                onClick = () -> showHint(LocaleController.formatString(R.string.Gift2RarityHint, percents(attr.getRarityPermille())), textViewArr[0], false);
            } else {
                onClick = null;
            }
            row = tableView.addRow(name, attr.name, rarity, onClick, color[0]);
            textViewArr[0] = (ButtonSpan.TextViewButtons) ((TableView.TableRowContent) row.getChildAt(1)).getChildAt(0);
        }
    }

    private static class Roller {

        public final TopView topView;

        TextViewRoll modelText;
        TextViewRoll patternText;
        TextViewRoll backdropText;

        private Sticker from;
        private final ArrayList<Sticker> models = new ArrayList<>();
        private final ArrayList<Background> backgrounds = new ArrayList<>();
        private final ArrayList<Symbol> symbols = new ArrayList<>();

        private AttrRoller<Sticker> modelRoller;
        private AttrRoller<Symbol> symbolRoller;
        private AttrRoller<Background> backdropRoller;
        private AttrRoller<Background> backdropRoller2;

        private static class AttrRoller<T extends Attr> {

            private final Runnable invalidate;

            public T prev;
            public T current;
            public T next;

            public int currentT;
            public float time = 0;

            public final ArrayList<T> attributes;
            public final T start;
            public final T finish;

            private final float speedMult;
            private final int totalSlowing;
            private int slowing;

            private final AnimatedFloat fast;

            public AttrRoller(
                Runnable invalidate,
                ArrayList<T> attributes,
                T start, T finish,

                float speedMult, int totalSlowing
            ) {
                this.invalidate = invalidate;
                this.attributes = attributes;
                this.start = start;
                this.finish = finish;
                this.speedMult = speedMult;
                this.totalSlowing = totalSlowing;

                this.fast = new AnimatedFloat(invalidate, 300, CubicBezierInterpolator.EASE_OUT_QUINT);
                this.fast.force(true);
                time = -0.5f;

                currentT = 1;
                slowing = totalSlowing;

                prev = start;
                current = next(false);
                next = next(false);
            }

            public void skip() {
                prev = current;
                current = finish;
                next = null;
                currentT++;
                time = currentT + 0.5f;
            }

            public boolean isFinished() {
                return current == finish && time >= currentT + 0.5f;
            }

            public boolean isAlmostFinished() {
                return isAlmostFinished(.25f);
            }

            public boolean isAlmostFinished(float offset) {
                return current == finish && time + offset >= currentT + 0.5f;
            }

            public float step(float deltaTime, boolean allowFinish) {
                fast.setDuration(slowing >= totalSlowing ? 450 : (totalSlowing == 3 ? 4500 : 2500));
                final float isFast = fast.set(slowing >= totalSlowing);
                final float speed = lerp((totalSlowing == 3 ? 0.75f : 2.0f), 7.5f, isFast) * speedMult;

                time += deltaTime * speed;

                float t = time;

                if (t >= 0 && Math.floor(t) + 1 > currentT && current != finish) {
                    prev = current;
                    current = next;
                    next = current == finish ? null : next(allowFinish);
                    currentT = (int) Math.floor(t) + 1;
                }

                if (current == finish) {
                    final float finalT = currentT + 0.5f;
                    t = Math.min(t, finalT);
                }

                return t;
            }

            private int lastNextIndex = -1;
            public T next(boolean allowFinish) {
                if (allowFinish && finish.isLoaded()) {
                    if (slowing <= 0)
                        return finish;
                    else
                        slowing--;
                }

                final ArrayList<Integer> loadedIndices = new ArrayList<>();
                for (int i = 0; i < attributes.size(); ++i) {
                    if (i != lastNextIndex && attributes.get(i).isLoaded())
                        loadedIndices.add(i);
                }

                if (loadedIndices.isEmpty()) {
                    for (int i = 0; i < attributes.size(); ++i) {
                        if (attributes.get(i).isLoaded())
                            loadedIndices.add(i);
                    }
                    if (loadedIndices.isEmpty()) {
                        return start;
                    }
                }
                final int index = randomOf(loadedIndices);
                lastNextIndex = index;
                return attributes.get(index);
            }

            public void detach() {
                if (start != null) {
                    start.detach();
                }
                if (finish != null) {
                    finish.detach();
                }
            }
        }

        public Roller(TopView topView) {
            this.topView = topView;
            topView.imagesRollView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View view) {
                    for (Sticker sticker : models) {
                        sticker.attach();
                    }
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View view) {
                    for (Sticker sticker : models) {
                        sticker.detach();
                    }
                }
            });
        }

        public void preload(ArrayList<TL_stars.StarGiftAttribute> sampleAttributes) {
            for (Sticker sticker : models) {
                sticker.detach();
            }
            models.clear();
            backgrounds.clear();
            symbols.clear();

            final ArrayList<TL_stars.starGiftAttributeModel> attrModels = findAttributes(sampleAttributes, TL_stars.starGiftAttributeModel.class);
            for (TL_stars.starGiftAttributeModel attrModel : attrModels) {
                final Sticker sticker = new Sticker(topView.imagesRollView, attrModel);
                if (topView.isAttachedToWindow()) sticker.attach();
                models.add(sticker);
            }

            final ArrayList<TL_stars.starGiftAttributeBackdrop> attrBackdrops = findAttributes(sampleAttributes, TL_stars.starGiftAttributeBackdrop.class);
            for (TL_stars.starGiftAttributeBackdrop attrBackdrop : attrBackdrops) {
                backgrounds.add(new Background(attrBackdrop));
            }

            final ArrayList<TL_stars.starGiftAttributePattern> attrPatterns = findAttributes(sampleAttributes, TL_stars.starGiftAttributePattern.class);
            for (TL_stars.starGiftAttributePattern attrPattern : attrPatterns) {
                symbols.add(new Symbol(attrPattern));
            }
        }

        public void stopPreload() {
            if (rolling) return;

            for (Sticker sticker : models) {
                sticker.detach();
            }
            models.clear();
            backgrounds.clear();
            symbols.clear();
        }

        public void detach() {
            rolling = false;
            topView.imagesRollView.resetDrawing();
            if (modelRoller != null) {
                modelRoller.detach();
            }
            if (symbolRoller != null) {
                symbolRoller.detach();
            }
            if (backdropRoller != null) {
                backdropRoller.detach();
            }
            if (backdropRoller2 != null) {
                backdropRoller2.detach();
            }
            stopPreload();
        }

        private TL_stars.TL_starGiftUnique rollingGift;
        private long lastFrameTime;
        private float realTime = 0;
        private boolean rolling = false;
        private boolean sentDone = false, sentDone2 = false;
        private Runnable whenDone, whenDone2;
        private float durationT;

        public boolean set(
            TL_stars.TL_starGiftUnique toGift,
            boolean roll,
            Runnable whenDone,
            Runnable whenDone2
        ) {
            if (toGift == null) roll = false;
            if (rollingGift != null && rollingGift.id == toGift.id) {
                return rolling;
            }
            if (!roll)
                return false;

            final BackupImageView fromImageView = topView.getUpgradeImageView();

            final TL_stars.starGiftAttributeModel fromModel = topView.getUpgradeImageViewAttribute();
            final TL_stars.starGiftAttributePattern fromSymbol = topView.getUpgradePatternAttribute();
            final TL_stars.starGiftAttributeBackdrop fromBackdrop = topView.getUpgradeBackdropAttribute();

            final TL_stars.starGiftAttributeModel finishModel = findAttribute(toGift.attributes, TL_stars.starGiftAttributeModel.class);
            final TL_stars.starGiftAttributePattern finishSymbol = findAttribute(toGift.attributes, TL_stars.starGiftAttributePattern.class);
            final TL_stars.starGiftAttributeBackdrop finishBackdrop = findAttribute(toGift.attributes, TL_stars.starGiftAttributeBackdrop.class);

            this.rolling = true;
            this.rollingGift = toGift;
            this.whenDone = whenDone;
            this.whenDone2 = whenDone2;

            this.durationT = (float) Math.random();

            lastFrameTime = System.currentTimeMillis();
            realTime = 0;

            sentDone = false;
            sentDone2 = false;
            rolling = true;

            if (modelRoller != null) {
                modelRoller.detach();
            }
            final Sticker finishSticker = new Sticker(topView.imagesRollView, finishModel);
            if (topView.imagesRollView.isAttachedToWindow()) finishSticker.attach();
            modelRoller = new AttrRoller<>(
                this::invalidate,
                models,
                new Sticker(fromImageView, fromModel),
                finishSticker,

                0.9f, durationT > 0.5f ? 3 : 2
            );

            if (symbolRoller != null) {
                symbolRoller.detach();
            }
            symbolRoller = new AttrRoller<>(
                this::invalidate,
                symbols,
                new Symbol(fromSymbol),
                new Symbol(finishSymbol),

                1.0f, durationT > 0.5f ? 2 : 1
            );

            if (backdropRoller != null) {
                backdropRoller.detach();
            }
            backdropRoller = new AttrRoller<>(
                this::invalidate,
                backgrounds,
                new Background(fromBackdrop),
                new Background(finishBackdrop),

                0.5f, durationT > 0.5f ? 2 : 1
            );

            if (backdropRoller2 != null) {
                backdropRoller2.detach();
            }
            backdropRoller2 = new AttrRoller<>(
                this::invalidate,
                backgrounds,
                new Background(fromBackdrop),
                new Background(finishBackdrop),

                1.25f, durationT > 0.5f ? 2 : 1
            );

            invalidate();
            return true;
        }

        public void skip() {
            modelRoller.skip();
            symbolRoller.skip();
            backdropRoller.skip();
            backdropRoller2.skip();
        }

        public boolean isRolling() {
            return rolling;
        }

        private boolean drawing;
        private boolean posted;
        public void invalidate() {
            if (!rolling) return;
            if (!posted) {
                posted = true;
                AndroidUtilities.runOnUIThread(this::update);
            }
        }

        public void update() {
            if (drawing) return;
            posted = false;
            if (!rolling) return;

            drawing = true;

            final long now = System.currentTimeMillis();
            final long deltaTimeMs = now - lastFrameTime;
            final float deltaTime = Math.min(deltaTimeMs / 1000.0f, .25f);

            realTime += deltaTime;

            final float bgT = backdropRoller.step(deltaTime, realTime > lerp(0.10f, 1.0f, durationT));
            final float bg2T = backdropRoller2.step(deltaTime, realTime > lerp(0.10f, 1.0f, durationT));
            final float symbolT = symbolRoller.step(deltaTime, backdropRoller.isAlmostFinished(.5f));
            final float modelT = modelRoller.step(deltaTime, backdropRoller.isAlmostFinished(.5f) && symbolRoller.isAlmostFinished(.5f));

            lastFrameTime = now;

            if (backdropRoller.isFinished() && symbolRoller.isFinished() && modelRoller.isFinished() && !sentDone) {
                sentDone = true;
                AndroidUtilities.runOnUIThread(() -> {
                    rolling = false;
                    topView.imagesRollView.resetDrawing();
                    if (whenDone != null) {
                        whenDone.run();
                    }
                });
            }
            if (backdropRoller.isFinished() && symbolRoller.isFinished() && modelRoller.isAlmostFinished() && !sentDone2) {
                sentDone2 = true;
                AndroidUtilities.runOnUIThread(() -> {
                    if (whenDone2 != null) {
                        whenDone2.run();
                    }
                });
            }

            if (modelText != null) {
                modelText.update(
                    modelRoller.prev, ((float) modelRoller.currentT - modelT) - 1, modelRoller.prev == modelRoller.finish,
                    modelRoller.current, ((float) modelRoller.currentT - modelT), modelRoller.current == modelRoller.finish,
                    modelRoller.next, ((float) modelRoller.currentT - modelT) + 1, modelRoller.next == modelRoller.finish
                );
            }

            if (patternText != null) {
                patternText.update(
                    symbolRoller.prev, ((float) symbolRoller.currentT - symbolT) - 1, symbolRoller.prev == symbolRoller.finish,
                    symbolRoller.current, ((float) symbolRoller.currentT - symbolT), symbolRoller.current == symbolRoller.finish,
                    symbolRoller.next, ((float) symbolRoller.currentT - symbolT) + 1, symbolRoller.next == symbolRoller.finish
                );
            }

            if (backdropText != null) {
                backdropText.update(
                    backdropRoller2.prev, ((float) backdropRoller2.currentT - bg2T) - 1, backdropRoller2.prev == backdropRoller2.finish,
                    backdropRoller2.current, ((float) backdropRoller2.currentT - bg2T), backdropRoller2.current == backdropRoller2.finish,
                    backdropRoller2.next, ((float) backdropRoller2.currentT - bg2T) + 1, backdropRoller2.next == backdropRoller2.finish
                );
            }

            topView.setPattern(0, symbolRoller.current.attr, true);

            topView.imagesRollView.setDrawing(
                modelRoller.prev, ((float) modelRoller.currentT - modelT) - 1, modelRoller.prev == modelRoller.finish,
                modelRoller.current, ((float) modelRoller.currentT - modelT), modelRoller.current == modelRoller.finish,
                modelRoller.next, ((float) modelRoller.currentT - modelT) + 1, modelRoller.next == modelRoller.finish,

                backdropRoller.prev, ((float) backdropRoller.currentT - bgT) - 1, backdropRoller.prev == backdropRoller.finish,
                backdropRoller.current, ((float) backdropRoller.currentT - bgT), backdropRoller.current == backdropRoller.finish,
                backdropRoller.next, ((float) backdropRoller.currentT - bgT) + 1, backdropRoller.next == backdropRoller.finish
            );

            drawing = false;
            invalidate();
        }

        public static class Attr {
            public String name;
            public int rarity_permille;
            public boolean isLoaded() { return true; }
            public void attach() {}
            public void detach() {}
        }

        public static class Sticker extends Attr {

            public final boolean mine;
            public final ImageReceiver imageReceiver;

            public Sticker(View parentView, TL_stars.starGiftAttributeModel model) {
                name = model.name;
                rarity_permille = model.getRarityPermille();

                mine = true;
                imageReceiver = new ImageReceiver(parentView);
                setGiftImage(imageReceiver, model.document, 160);
            }

            public Sticker(BackupImageView fromView, TL_stars.starGiftAttributeModel model) {
                name = model.name;
                rarity_permille = model.getRarityPermille();

                mine = false;
                imageReceiver = fromView.getImageReceiver();
            }

            @Override
            public void attach() {
                if (mine) imageReceiver.onAttachedToWindow();
            }
            @Override
            public void detach() {
                if (mine) imageReceiver.onDetachedFromWindow();
            }

            @Override
            public boolean isLoaded() {
                return imageReceiver.getLottieAnimation() != null;
            }
        }
        public static class Symbol extends Attr {

            public final TL_stars.starGiftAttributePattern attr;

            public Symbol(TL_stars.starGiftAttributePattern pattern) {
                name = pattern.name;
                rarity_permille = pattern.getRarityPermille();

                attr = pattern;
            }
        }
        public static class Background extends Attr {

            public final Paint backgroundPaint;
            public final Matrix backgroundMatrix;
            public final RadialGradient backgroundGradient;

            public final int backgroundColor;
            public final int textColor;
            public final int patternColor;

            public Background(TL_stars.starGiftAttributeBackdrop backdrop) {
                this.name = backdrop.name;
                this.rarity_permille = backdrop.getRarityPermille();

                if (backdrop != null) {
                    backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    backgroundMatrix = new Matrix();
                    backgroundGradient = new RadialGradient(0, 0, dp(200), new int[] { backdrop.center_color | 0xFF000000, backdrop.edge_color | 0xFF000000 }, new float[] { 0, 1 }, Shader.TileMode.CLAMP);
                    backgroundPaint.setShader(backgroundGradient);

                    textColor = backdrop.text_color | 0xFF000000;
                    patternColor = backdrop.pattern_color | 0xFF000000;
                    backgroundColor = ColorUtils.blendARGB(backdrop.edge_color | 0xFF000000, backdrop.pattern_color | 0xFF000000, .25f);
                } else {
                    backgroundPaint = null;
                    backgroundMatrix = null;
                    backgroundGradient = null;

                    backgroundColor = 0;
                    patternColor = 0;
                    textColor = 0;
                }
            }
        }
    }

    private static class TextViewRoll extends FrameLayout {

        private final Theme.ResourcesProvider resourcesProvider;
        private final Utilities.Callback3<CharSequence, View, Boolean> showHint;

        private final TextView prev, current, next;

        public TextViewRoll(Context context, Theme.ResourcesProvider resourcesProvider, Utilities.Callback3<CharSequence, View, Boolean> showHint) {
            super(context);

            this.showHint = showHint;
            this.resourcesProvider = resourcesProvider;

            prev = new TextView(context, resourcesProvider);
            current = new TextView(context, resourcesProvider);
            next = new TextView(context, resourcesProvider);

            addView(prev, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 12.66f, 5.33f, 12.66f, 5.33f));
            addView(current, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 12.66f, 5.33f, 12.66f, 5.33f));
            addView(next, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 12.66f, 5.33f, 12.66f, 5.33f));
        }

        private boolean rolling;
        public boolean isRolling() {
            return rolling;
        }
//
//        public void start(int offset, ArrayList<TL_stars.StarGiftAttribute> samples, TL_stars.StarGiftAttribute finish, Utilities.Callback3<CharSequence, View, Boolean> showHint) {
//            for (int i = 0; i < 35; ++i)
//                add(randomOf(samples), null);
//            lastView = add(finish, showHint);
//
//            rolling = true;
//            final float ty = -dp(27) * (layout.getChildCount() - 1);
//            final OvershootInterpolator i = new OvershootInterpolator(2);
//            final ValueAnimator va = ValueAnimator.ofFloat(0, 1.0f);
//            va.addUpdateListener(a -> {
//                float t = (float) a.getAnimatedValue();
//                layout.setTranslationY(t * ty - dp(12) * Math.max(0, i.getInterpolation(t) - 1f));
////                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
////                    final float radiusY = 0;//dp(12) * (1.0f - t);
////                    if (radiusY <= 1) {
////                        setRenderEffect(null);
////                    } else {
////                        setRenderEffect(RenderEffect.createBlurEffect(0, radiusY, Shader.TileMode.CLAMP));
////                    }
////                }
//                if (t > 0.9965f) bounce();
//            });
//            va.addListener(new AnimatorListenerAdapter() {
//                @Override
//                public void onAnimationEnd(Animator animation) {
//                    rolling = false;
//                    layout.setTranslationY(ty);
////                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
////                        setRenderEffect(null);
////                    }
//                    bounce();
//                }
//            });
//            va.setDuration(3500 + offset * 1000);
//            va.setInterpolator(CubicBezierInterpolator.EASE_OUT);
//            va.start();
//        }
//
        private boolean bounced;
        private void bounce(View view) {
            if (bounced) return;
            if (view == null) return;
            bounced = true;
            final ValueAnimator va = ValueAnimator.ofFloat(0, 1.0f);
            va.addUpdateListener(a -> {
                final float scale = 1 + .03f * (float) Math.sin(Math.PI * (float) a.getAnimatedValue());
                view.setScaleX(scale);
                view.setScaleY(scale);
            });
            va.setDuration(180);
            va.start();
        }

        private static class TextView extends ButtonSpan.TextViewButtons {

            private final Theme.ResourcesProvider resourcesProvider;

            public TextView(Context context, Theme.ResourcesProvider resourcesProvider) {
                super(context);
                this.resourcesProvider = resourcesProvider;
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                setPadding(0, dp(4), 0, dp(4));
            }

            private String lastName;
            private int lastRarity;

            public void set(String name, int rarity_permille, Utilities.Callback3<CharSequence, View, Boolean> showHint) {
                if (name == lastName && lastRarity == rarity_permille) return;

                final SpannableStringBuilder ssb = new SpannableStringBuilder(Emoji.replaceEmoji(name, getPaint().getFontMetricsInt(), false));
                ssb.append(" ").append(ButtonSpan.make(percents(rarity_permille), showHint != null ? () -> showHint.run(LocaleController.formatString(R.string.Gift2RarityHint, percents(rarity_permille)), this, false) : null, resourcesProvider));
                setText(ssb);

                this.lastName = name;
                this.lastRarity = rarity_permille;
            }
        }

        public void update(
            Roller.Attr prev,    float prevT,    boolean isPrevFinish,
            Roller.Attr current, float currentT, boolean isCurrentFinish,
            Roller.Attr next,    float nextT,    boolean isNextFinish
        ) {
            if (prev != null) {
                float t = prevT;
                if (isPrevFinish) {
                    t = Math.max(0.5f, t);
                }
                final float tt = (t - 0.5f) / 1.5f;
                this.prev.setVisibility(View.VISIBLE);
                this.prev.set(prev.name, prev.rarity_permille, this.showHint);
                this.prev.setTranslationY(dp(36) * tt);
            } else {
                this.prev.setVisibility(View.INVISIBLE);
            }

            if (current != null) {
                float t = currentT;
                if (isCurrentFinish) {
                    t = Math.max(0.5f, t);
                }
                final float tt = (t - 0.5f) / 1.5f;
                this.current.setVisibility(View.VISIBLE);
                this.current.set(current.name, current.rarity_permille, this.showHint);
                this.current.setTranslationY(dp(36) * tt);

                if (isCurrentFinish && tt <= 0) {
                    this.bounce(this.current);
                }
            } else {
                this.current.setVisibility(View.INVISIBLE);
            }

            if (next != null) {
                float t = nextT;
                if (isNextFinish) {
                    t = Math.max(0.5f, t);
                }
                final float tt = (t - 0.5f) / 1.5f;
                this.next.setVisibility(View.VISIBLE);
                this.next.set(next.name, next.rarity_permille, this.showHint);
                this.next.setTranslationY(dp(36) * tt);
            } else {
                this.next.setVisibility(View.INVISIBLE);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(37.66f), MeasureSpec.EXACTLY)
            );
        }

        private final GradientClip clip = new GradientClip();
        private final RectF rect = new RectF();
        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 0xFF, Canvas.ALL_SAVE_FLAG);
            super.dispatchDraw(canvas);
            canvas.save();
            rect.set(0, 0, getWidth(), dp(8));
            clip.draw(canvas, rect, GradientClip.TOP, 1.0f);
            rect.set(0, getHeight() - dp(8), getWidth(), getHeight());
            clip.draw(canvas, rect, GradientClip.BOTTOM, 1.0f);
            canvas.restore();
            canvas.restore();
        }
    }

    private boolean firstSet = true;

    public StarGiftSheet set(String slug, TL_stars.TL_starGiftUnique gift, StarsController.IGiftsList list) {
        this.slug = slug;
        this.slugStarGift = gift;
        this.giftsList = list;
        this.resale = gift.resell_amount != null && !isMine(currentAccount, DialogObject.getPeerDialogId(gift.owner_id));

        if (!rolling && roller != null && roller.isRolling() && roller.rollingGift != null && roller.rollingGift.id != gift.id) {
            roller.detach();
            roller = null;
            topView.imageLayout.setAlpha(1.0f);
            topView.imagesRollView.setAlpha(0.0f);
        }
        actionView.set(currentAccount, savedStarGift);

        set(gift, false);

        final String owner_address = gift == null ? null : gift.owner_address;
        final String gift_address = gift == null ? null : gift.gift_address;
        final boolean hosting = gift != null && gift.host_id != null;
        beforeTableTextView.setVisibility(View.GONE);

        afterTableTextView.setVisibility(View.GONE);

        if (firstSet) {
            switchPage(PAGE_INFO, false);
            layoutManager.scrollToPosition(1);
            firstSet = false;
        }
        updateViewPager();

        return this;
    }

    private CharSequence releasedByText(TL_stars.StarGift gift) {
        if (gift == null || gift instanceof TL_stars.TL_starGiftUnique) return null;
        return releasedByText(gift.released_by);
    }

    private CharSequence releasedByText(TLRPC.Peer reserved_by) {
        if (reserved_by == null)
            return null;
        final String username = DialogObject.getPublicUsername(MessagesController.getInstance(currentAccount).getUserOrChat(DialogObject.getPeerDialogId(reserved_by)));
        if (TextUtils.isEmpty(username))
            return null;
        return AndroidUtilities.replaceSingleTag(
            LocaleController.formatString(R.string.Gift2ReleasedBy2, "@" + username),
            () -> {
                dismiss();
                Browser.openUrl(getContext(), "https://" + MessagesController.getInstance(currentAccount).linkPrefix + "/" + username);
            }
        );
    }

    private CharSequence releasedByUniqueText(int num, TLRPC.Peer reserved_by) {
        if (reserved_by == null)
            return null;
        final String username = DialogObject.getPublicUsername(MessagesController.getInstance(currentAccount).getUserOrChat(DialogObject.getPeerDialogId(reserved_by)));
        if (TextUtils.isEmpty(username))
            return null;
        return replaceSingleTagToLink(LocaleController.formatPluralStringComma("Gift2CollectionNumberBy", num, "@" + username), () -> {
            dismiss();
            Browser.openUrl(getContext(), "https://" + MessagesController.getInstance(currentAccount).linkPrefix + "/" + username);
        });
    }

    public static SpannableStringBuilder replaceSingleTagToLink(String str, Runnable click) {
        int startIndex = str.indexOf("**");
        int endIndex = str.indexOf("**", startIndex + 1);
        str = str.replace("**", "");
        int index = -1;
        int len = 0;
        if (startIndex >= 0 && endIndex >= 0 && endIndex - startIndex > 2) {
            len = endIndex - startIndex - 2;
            index = startIndex;
        }
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder(str);
        if (index >= 0) {
            spannableStringBuilder.setSpan(new ClickableSpan() {

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    ds.setUnderlineText(false);
                    ds.setColor(0xFFFFFFFF);
                }

                @Override
                public void onClick(@NonNull View view) {
                    if (click != null) {
                        click.run();
                    }
                }
            }, index, index + len, 0);
        }
        return spannableStringBuilder;
    }

    private View ownerTextView;
    public void set(TL_stars.TL_starGiftUnique gift, boolean refunded) {
        set(gift, refunded, null, null);
    }

    public void set(TL_stars.TL_starGiftUnique gift, boolean refunded, TLObject fromObject, TLRPC.TL_textWithEntities message) {
        final long owner_id = DialogObject.getPeerDialogId(gift.owner_id);
        final long host_id = DialogObject.getPeerDialogId(gift.host_id);

        title = gift.title + " #" + LocaleController.formatNumber(gift.num, ',');

        if (!rolling && roller != null && roller.isRolling() && roller.rollingGift != null && roller.rollingGift.id != gift.id) {
            roller.detach();
            roller = null;
            topView.imageLayout.setAlpha(1.0f);
            topView.imagesRollView.setAlpha(0.0f);
        } else if (rolling && roller == null) {
            roller = new Roller(topView);
        }
        topView.setGift(gift, isMineWithActions(currentAccount, owner_id), isMineWithActions(currentAccount, host_id), isWorn(currentAccount, getUniqueGift()), getLink() != null, rolling);

        final TL_stars.starGiftAttributeModel modelAttribute = findAttribute(gift.attributes, TL_stars.starGiftAttributeModel.class);

        final SpannableStringBuilder title = new SpannableStringBuilder();
        title.append(gift.title);
        title.append(" ");
        int fromIndex = title.length();
        title.append("#" + LocaleController.formatNumber(gift.num, ','));
        title.setSpan(new RelativeSizeSpan(0.85f), fromIndex, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setSpan(new EllipsizeSpanAnimator.TextAlphaSpan(0xBE), fromIndex, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final CharSequence subtitle;
        if (fromObject != null) {
            subtitle = AndroidUtilities.replaceTags(formatString(R.string.UniqueGiftFrom, DialogObject.getShortName(fromObject)));
        } else if (gift.released_by == null && modelAttribute != null) {
            subtitle = modelAttribute.name;
        } else {
            subtitle = null;
        }

        final CharSequence textMessage;
        if (message != null && topView.messageTextPaint != null) {
            CharSequence text = new SpannableStringBuilder(message.text);
            MessageObject.addEntitiesToText(text, message.entities, false, false, false, false);
            text = Emoji.replaceEmoji(text, topView.messageTextPaint.getFontMetricsInt(), false);
            textMessage = MessageObject.replaceAnimatedEmoji(text, message.entities, topView.messageTextPaint.getFontMetricsInt());
        } else {
            textMessage = null;
        }

        topView.setText(0, title, subtitle, releasedByText(gift.released_by), null, fromObject, textMessage);

        ownerTextView = null;
        tableView.clear();
        if (!refunded) {
            if (gift.host_id != null) {

                if (host_id != 0) {
                    TableRow row = tableView.addRowUserWithEmojiStatus(getString(R.string.Gift2Host), currentAccount, host_id, () -> openProfile(host_id));
                    ownerTextView = ((TableView.TableRowContent) row.getChildAt(1)).getChildAt(0);
                }
            } else {
                if (owner_id == 0 && gift.owner_name != null) {
                    tableView.addRow(getString(R.string.Gift2Owner), gift.owner_name);
                } else if (owner_id != 0) {
                    TableRow row = tableView.addRowUserWithEmojiStatus(getString(R.string.Gift2Owner), currentAccount, owner_id, () -> openProfile(owner_id));
                    ownerTextView = ((TableView.TableRowContent) row.getChildAt(1)).getChildAt(0);
                }
            }
        }
        addAttributeRow(findAttribute(gift.attributes, TL_stars.starGiftAttributeModel.class));
        addAttributeRow(findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class));
        addAttributeRow(findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class));
        if (!refunded) {
            if (messageObject != null) {
                if (!messageObjectRepolled) {
                    final TableRow row = tableView.addRow(getString(R.string.Gift2Quantity), "");
                    final TextView rowTextView = (TextView) ((TableView.TableRowContent) row.getChildAt(1)).getChildAt(0);
                    final SpannableStringBuilder sb = new SpannableStringBuilder("x ");
                    final LoadingSpan span = new LoadingSpan(rowTextView, dp(90), 0, resourcesProvider);
                    span.setColors(
                            Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), .21f),
                            Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), .08f)
                    );
                    sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    rowTextView.setText(sb, TextView.BufferType.SPANNABLE);

                    repollMessage();
                } else {
                    tableView.addRow(getString(R.string.Gift2Quantity), formatPluralStringComma("Gift2QuantityIssued1", gift.availability_issued) + formatPluralStringComma("Gift2QuantityIssued2", gift.availability_total));
                }
            } else {
                tableView.addRow(getString(R.string.Gift2Quantity), formatPluralStringComma("Gift2QuantityIssued1", gift.availability_issued) + formatPluralStringComma("Gift2QuantityIssued2", gift.availability_total));
            }

        }
        final TL_stars.starGiftAttributeOriginalDetails details = findAttribute(gift.attributes, TL_stars.starGiftAttributeOriginalDetails.class);
        if (details != null) {
            SpannableString from = null;
            if ((details.flags & 1) != 0) {
                final long sender_id = DialogObject.getPeerDialogId(details.sender_id);
                from = new SpannableString(DialogObject.getName(sender_id));
                from.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        openProfile(sender_id);
                    }
                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setColor(ds.linkColor);
                    }
                }, 0, from.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            final long recipient_id = DialogObject.getPeerDialogId(details.recipient_id);
            SpannableString to = new SpannableString(DialogObject.getName(recipient_id));
            to.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    openProfile(recipient_id);
                }
                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    ds.setColor(ds.linkColor);
                }
            }, 0, to.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            CharSequence comment = null;
            if (details.message != null) {
                TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                textPaint.setTextSize(dp(14));

                comment = new SpannableStringBuilder(details.message.text);
                MessageObject.addEntitiesToText(comment, details.message.entities, false, false, false, false);
                comment = Emoji.replaceEmoji(comment, textPaint.getFontMetricsInt(), false);
                comment = MessageObject.replaceAnimatedEmoji(comment, details.message.entities, textPaint.getFontMetricsInt());
            }
            CharSequence date = LocaleController.getInstance().getFormatterYear().format(details.date * 1000L).replaceAll("\\.", "/");
            TableView.TableRowFullContent tableRow;
            CharSequence detailsText;
            if (details.sender_id == details.recipient_id) {
                if (comment == null) {
                    detailsText = formatSpannable(R.string.Gift2AttributeOriginalDetailsSelf, from, date);
                } else {
                    detailsText = formatSpannable(R.string.Gift2AttributeOriginalDetailsSelfComment, from, date, comment);
                }
            } else if (from != null) {
                if (comment == null) {
                    detailsText = formatSpannable(R.string.Gift2AttributeOriginalDetails, from, to, date);
                } else {
                    detailsText = formatSpannable(R.string.Gift2AttributeOriginalDetailsComment, from, to, date, comment);
                }
            } else {
                if (comment == null) {
                    detailsText = formatSpannable(R.string.Gift2AttributeOriginalDetailsNoSender, to, date);
                } else {
                    detailsText = formatSpannable(R.string.Gift2AttributeOriginalDetailsNoSenderComment, to, date, comment);
                }
            }

            tableRow = tableView.addFullRow(detailsText);
            tableRow.setFilled(true);
            SpoilersTextView textView = (SpoilersTextView) tableRow.getChildAt(0);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            textView.setGravity(Gravity.CENTER);

        }

        if (!(roller != null && roller.isRolling())) {
            button.setFilled(true);
            button.setText(getString(R.string.OK), !firstSet);
            button.setSubText(null, !firstSet);
            button.setOnClickListener(v -> onBackPressed());
        }

        actionBar.setTitle(getTitle());

        if (roller != null) {
            if (roller.set(gift, rolling, () -> {
                final ImageReceiver fromImageView = roller.modelRoller != null && roller.modelRoller.current != null ? roller.modelRoller.current.imageReceiver : null;
                final BackupImageView toImageView = topView.imageView[0];
                if (fromImageView != null && toImageView != null && toImageView.getImageReceiver() != null) {
                    final RLottieDrawable fromAnimation = fromImageView.getLottieAnimation();
                    final RLottieDrawable toAnimation = toImageView.getImageReceiver().getLottieAnimation();
                    if (toAnimation != null && fromAnimation != null) {
                        toAnimation.setProgress(fromAnimation.getProgress(), false);
                    } else if (toAnimation == null && fromAnimation != null) {
                        fromImageView.clearImage();
                        toImageView.setImageDrawable(fromAnimation);
                    }
                }

                topView.imageLayout.setAlpha(1.0f);
                topView.imagesRollView.setAlpha(0.0f);

                button.setFilled(true);
                button.setText(getString(R.string.OK), true);
                button.setSubText(null, true);
                button.setOnClickListener(v -> onBackPressed());
            }, () -> {
                String product = "";
                if (getGift() != null) {
                    product = getGift().title + " #" + LocaleController.formatNumber(getGift().num, ',');
                }
                getBulletinFactory()
                    .createSimpleBulletin(R.raw.gift_upgrade, getString(R.string.Gift2UpgradedTitle), AndroidUtilities.replaceTags(formatString(R.string.Gift2UpgradedText, product)))
                    .setDuration(Bulletin.DURATION_PROLONG)
                    .ignoreDetach()
                    .show();

                if (fireworksOverlay != null) {
                    fireworksOverlay.start(true);
                }
            })) {
                topView.imageLayout.setAlpha(0.0f);
                topView.imagesRollView.setAlpha(1.0f);

                button.setText(getString(R.string.GiftSkipAnimation), true);
                button.setFilled(true);
                button.setOnClickListener(v -> {
                    roller.skip();
                });

                recyclerListView.scrollToPosition(adapter.getItemCount() - 1);
                recyclerListView.post(() -> {
                    recyclerListView.scrollToPosition(adapter.getItemCount() - 1);
                });
            }
        }
    }

    public boolean isSaved() {
        if (messageObject != null && messageObject.messageOwner != null) {
            TLRPC.MessageAction _action = messageObject.messageOwner.action;
            if (_action instanceof TLRPC.TL_messageActionStarGift) {
                return ((TLRPC.TL_messageActionStarGift) _action).saved;
            } else if (_action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                return ((TLRPC.TL_messageActionStarGiftUnique) _action).saved;
            }
        } else if (savedStarGift != null) {
            return !savedStarGift.unsaved;
        }
        return false;
    }

    private Runnable onGiftUpdatedListener;
    public StarGiftSheet setOnGiftUpdatedListener(Runnable listener) {
        onGiftUpdatedListener = listener;
        return this;
    }

    public StarGiftSheet set(TL_stars.SavedStarGift savedStarGift, StarsController.IGiftsList list) {
        if (savedStarGift == null) {
            return this;
        }

        this.myProfile = isMine(currentAccount, dialogId);
        this.savedStarGift = savedStarGift;
        this.giftsList = list;
        this.messageObject = null;

        if (!rolling && roller != null && roller.isRolling() && roller.rollingGift != null) {
            roller.detach();
            roller = null;
            topView.imageLayout.setVisibility(View.VISIBLE);
            topView.imagesRollView.setVisibility(View.INVISIBLE);
        }

        actionView.set(currentAccount, savedStarGift);

        final String name = DialogObject.getShortName(dialogId);

        final long from_id = DialogObject.getPeerDialogId(savedStarGift.from_id);
        final boolean fromBot = UserObject.isBot(MessagesController.getInstance(currentAccount).getUser(from_id));
        final int within = MessagesController.getInstance(currentAccount).stargiftsConvertPeriodMax - (ConnectionsManager.getInstance(currentAccount).getCurrentTime() - savedStarGift.date);
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        final long fromId = (savedStarGift.flags & 2) != 0 ? from_id : UserObject.ANONYMOUS;
        final boolean isForChannel = dialogId < 0;
        final String owner_address, gift_address;
        final TLRPC.TL_textWithEntities message = savedStarGift.message;
        final boolean hosted;

        boolean refunded = savedStarGift.refunded, self = false;

        if (savedStarGift.gift instanceof TL_stars.TL_starGiftUnique) {
            owner_address = savedStarGift.gift.owner_address;
            gift_address = savedStarGift.gift.gift_address;
            hosted = savedStarGift.gift.host_id != null;
            TL_stars.TL_starGiftUnique starGiftUnique = (TL_stars.TL_starGiftUnique) savedStarGift.gift;
            set(starGiftUnique, refunded, !savedStarGift.name_hidden ?
                MessagesController.getInstance(currentAccount).getUserOrChat(DialogObject.getPeerDialogId(savedStarGift.from_id)) : null,
                savedStarGift.message);
        } else {
            self = myProfile && selfId == fromId && dialogId >= 0;
            owner_address = null;
            gift_address = null;
            hosted = false;

                    topView.setGift(savedStarGift.gift, false, false, isWorn(currentAccount, getUniqueGift()), getLink() != null, false);
            tableView.clear();
            if (self) {
                title = savedStarGift.gift_num != 0 && savedStarGift.gift != null && savedStarGift.gift.title != null ?
                    savedStarGift.gift.title + " #" + formatNumber(savedStarGift.gift_num, ',') : getString(R.string.Gift2TitleSaved);

                topView.setText(0, title, refunded ? null :
                    savedStarGift.can_upgrade ? AndroidUtilities.replaceTags(getString(R.string.Gift2SelfInfoUpgrade)) :
                    savedStarGift.convert_stars > 0 ? AndroidUtilities.replaceTags(formatPluralStringComma("Gift2SelfInfoConvert", (int) savedStarGift.convert_stars)) :
                    AndroidUtilities.replaceTags(getString(R.string.Gift2SelfInfo)),
                    null, releasedByText(savedStarGift.gift)
                );
            } else if (isForChannel && !myProfile) {
                topView.setText(0, title = getString(R.string.Gift2TitleProfile), null, null, releasedByText(savedStarGift.gift.released_by));
            } else if ((!myProfile || savedStarGift.can_upgrade) && savedStarGift.upgrade_stars > 0) {
                topView.setText(0,
                        title = getString(myProfile ? R.string.Gift2TitleReceived : R.string.Gift2TitleProfile),
                    refunded ? null : myProfile ? getString(R.string.Gift2InfoInFreeUpgrade) : null,
                    null, releasedByText(savedStarGift.gift)
                );
            } else {
                title = savedStarGift.gift_num != 0 && savedStarGift.gift != null && savedStarGift.gift.title != null ?
                        savedStarGift.gift.title + " #" + formatNumber(savedStarGift.gift_num, ',') : getString(myProfile ? R.string.Gift2TitleReceived : R.string.Gift2TitleProfile);

                topView.setText(
                    0,
                    title, null,
                    null, releasedByText(savedStarGift.gift)
                );
            }

            if (selfId != fromId || isForChannel) {
                final TLRPC.User fromUser = MessagesController.getInstance(currentAccount).getUser(fromId);
                tableView.addRowUser(getString(R.string.Gift2From), currentAccount, fromId, () -> openProfile(fromId), null, null);
            }
            tableView.addRow(getString(R.string.StarsTransactionDate), LocaleController.formatString(R.string.formatDateAtTime, LocaleController.getInstance().getFormatterGiveawayCard().format(new Date(savedStarGift.date * 1000L)), LocaleController.getInstance().getFormatterDay().format(new Date(savedStarGift.date * 1000L))));
            tableView.addRow(getString(R.string.Gift2Value), replaceStarsWithPlain(TextUtils.concat("⭐️ " + LocaleController.formatNumber(savedStarGift.gift.stars + savedStarGift.upgrade_stars, ','), " ", ""), .8f));
            if (savedStarGift.gift.limited && !refunded) {
                addAvailabilityRow(tableView, currentAccount, savedStarGift.gift, resourcesProvider);
            }
//            }
            if (savedStarGift.message != null && !TextUtils.isEmpty(savedStarGift.message.text) && !refunded) {
                tableView.addFullRow(savedStarGift.message.text, savedStarGift.message.entities);
            }

            button.setFilled(true);
            button.setText(getString(R.string.OK), !firstSet);
            button.setSubText(null, !firstSet);
            button.setOnClickListener(v -> onBackPressed());
        }

        if (savedStarGift.refunded) {
            beforeTableTextView.setVisibility(View.VISIBLE);
            beforeTableTextView.setText(getString(R.string.Gift2Refunded));
            beforeTableTextView.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        } else if (TextUtils.isEmpty(owner_address) && TextUtils.isEmpty(gift_address) && myProfile && savedStarGift.gift instanceof TL_stars.TL_starGift && savedStarGift.name_hidden) {
            beforeTableTextView.setVisibility(View.VISIBLE);
            beforeTableTextView.setText(getString(
                (message != null && !TextUtils.isEmpty(message.text)) ? R.string.Gift2InSenderMessageHidden2 : R.string.Gift2InSenderHidden2)
            );
            beforeTableTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        } else {
            beforeTableTextView.setVisibility(View.GONE);
        }

        if (myProfile && isMine(currentAccount, dialogId)) {
            if (dialogId >= 0) {
                final SpannableStringBuilder sb = new SpannableStringBuilder();
                if (savedStarGift.unsaved) {
                    sb.append(". ");
                    final ColoredImageSpan span = new ColoredImageSpan(R.drawable.mini_gift_hidden);
                    sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                sb.append(AndroidUtilities.replaceSingleTag(getString(!savedStarGift.unsaved ? R.string.Gift2ProfileVisible4 : R.string.Gift2ProfileInvisible4), this::toggleShow));
                afterTableTextView.setText(AndroidUtilities.replaceArrows(sb, true, dp(8f / 3f), dp(.66f)));
            } else {
                afterTableTextView.setText(AndroidUtilities.replaceArrows(AndroidUtilities.replaceSingleTag(getString(!savedStarGift.unsaved ? R.string.Gift2ChannelProfileVisible3 : R.string.Gift2ChannelProfileInvisible3), this::toggleShow), true, dp(8f / 3f), dp(.66f)));
            }
            afterTableTextView.setVisibility(View.VISIBLE);
        } else {
            afterTableTextView.setVisibility(View.GONE);
        }

        if (firstSet) {
            switchPage(PAGE_INFO, false);
            layoutManager.scrollToPosition(1);
            firstSet = false;
        }

        actionBar.setTitle(getTitle());
        updateViewPager();

        return this;
    }

    public TL_stars.TL_starGiftUnique getUniqueGift() {
        final TL_stars.StarGift gift = getGift();
        if (gift instanceof TL_stars.TL_starGiftUnique) {
            return (TL_stars.TL_starGiftUnique) gift;
        }
        return null;
    }

    public String getGiftName() {
        final TL_stars.StarGift gift = getGift();
        if (gift instanceof TL_stars.TL_starGiftUnique) {
            final TL_stars.TL_starGiftUnique uniqueGift = (TL_stars.TL_starGiftUnique) gift;
            return uniqueGift.title + " #" + LocaleController.formatNumber(uniqueGift.num, ',');
        }
        return "";
    }

    public static String getGiftName(TL_stars.StarGift gift) {
        if (gift instanceof TL_stars.TL_starGiftUnique) {
            final TL_stars.TL_starGiftUnique uniqueGift = (TL_stars.TL_starGiftUnique) gift;
            return uniqueGift.title + " #" + LocaleController.formatNumber(uniqueGift.num, ',');
        } else if (gift instanceof TL_stars.TL_starGift) {
            if (!TextUtils.isEmpty(gift.title)) {
                return gift.title;
            }
        }
        return getString(R.string.Gift2Gift);
    }

    public TL_stars.StarGift getGift() {
        if (messageObject != null) {
            if (messageObject.messageOwner == null) return null;
            if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
                return ((TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action).gift;
            } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                return ((TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action).gift;
            }
        } else if (savedStarGift != null) {
            return savedStarGift.gift;
        } else if (slugStarGift != null) {
            return slugStarGift;
        }
        return null;
    }

    public StarGiftSheet set(MessageObject messageObject) {
        return set(messageObject, null);
    }

    public StarGiftSheet set(MessageObject messageObject, StarsController.IGiftsList list) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return this;
        }

        this.myProfile = false;
        this.savedStarGift = null;
        this.messageObject = messageObject;
        this.giftsList = list;
        boolean converted, saved, out, refunded, name_hidden;

        actionView.set(messageObject);

        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        final boolean self = messageObject.getDialogId() == selfId;
        TL_stars.StarGift stargift;
        TLRPC.TL_textWithEntities message;
        TLRPC.Peer auctionPeer = null;
        if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift || messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique && ((TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action).gift instanceof TL_stars.TL_starGift) {
            if (!rolling && roller != null && roller.isRolling() && roller.rollingGift != null) {
                roller.detach();
                roller = null;
                topView.imageLayout.setVisibility(View.VISIBLE);
                topView.imagesRollView.setVisibility(View.INVISIBLE);
            }

            out = messageObject.isOutOwner();
            if (self) {
                out = false;
            }
            final int date = messageObject.messageOwner.date;
            boolean can_upgrade, upgraded, prepaid_upgrade;
            long convert_stars, upgrade_stars;
            TLRPC.Peer from_id, peer;
            String prepaid_upgrade_hash;
            int giftNum = 0;
            if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
                final TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;
                converted = action.converted;
                saved = action.saved;
                refunded = action.refunded;
                name_hidden = action.name_hidden;
                stargift = action.gift;
                can_upgrade = action.can_upgrade;
                convert_stars = action.convert_stars;
                upgrade_stars = action.upgrade_stars;
                message = action.message;
                upgraded = action.upgraded;
                from_id = action.from_id;
                peer = action.peer;
                prepaid_upgrade = action.prepaid_upgrade;
                prepaid_upgrade_hash = action.prepaid_upgrade_hash;
                auctionPeer = action.auction_acquired ? action.to_id : null;
                giftNum = action.gift_num;
            } else {
                final TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;
                converted = false;
                saved = action.saved;
                refunded = action.refunded;
                name_hidden = false;
                stargift = action.gift;
                can_upgrade = false;
                convert_stars = 0;
                upgrade_stars = 0;
                message = null;
                upgraded = true;
                from_id = action.from_id;
                peer = action.peer;
                prepaid_upgrade = false;
                prepaid_upgrade_hash = null;
            }

            final String name = DialogObject.getShortName(dialogId);
            final boolean fromBot = UserObject.isBot(MessagesController.getInstance(currentAccount).getUser(dialogId));
            final boolean isForChannel = peer != null && DialogObject.getPeerDialogId(peer) < 0;

            topView.setGift(stargift, false, false, isWorn(currentAccount, getUniqueGift()), getLink() != null, false);
            if (self) {
                title = giftNum != 0 && stargift != null && stargift.title != null ?
                    stargift.title + " #" + formatNumber(giftNum, ',') : getString(R.string.Gift2TitleSaved);

                topView.setText(0, title, refunded ? null :
                    can_upgrade ? AndroidUtilities.replaceTags(getString(R.string.Gift2SelfInfoUpgrade)) :
                    convert_stars > 0 ? AndroidUtilities.replaceTags(formatPluralStringComma(converted ? "Gift2SelfInfoConverted" : "Gift2SelfInfoConvert", (int) convert_stars)) :
                    AndroidUtilities.replaceTags(getString(R.string.Gift2SelfInfo)),
                    null, releasedByText(stargift)
                );
            } else if (isForChannel && !myProfile) {
                topView.setText(0, getString(R.string.Gift2TitleProfile), null, null, releasedByText(stargift));
            } else if ((out || can_upgrade) && upgrade_stars > 0) {
                topView.setText(0,
                    title = getString(out ? R.string.Gift2TitleSent : R.string.Gift2TitleReceived),
                    refunded ? null : !out ? getString(R.string.Gift2InfoInFreeUpgrade) : formatString(R.string.Gift2InfoFreeUpgrade, name),
                    null, releasedByText(stargift)
                );
            } else {
                title = giftNum != 0 && stargift != null && stargift.title != null ?
                        stargift.title + " #" + formatNumber(giftNum, ',') : getString(out ? R.string.Gift2TitleSent : R.string.Gift2TitleReceived);

                topView.setText(
                    0,
                    title, null,
                    null, releasedByText(stargift)
                );
            }
            tableView.clear();
            final long fromId = from_id != null ? DialogObject.getPeerDialogId(from_id) : out ? selfId : dialogId;
            final long toId = peer != null ? DialogObject.getPeerDialogId(peer) : out ? dialogId : selfId;
            final TLRPC.User fromUser = MessagesController.getInstance(currentAccount).getUser(fromId);
            if (auctionPeer != null) {
                long auctionToDid = DialogObject.getPeerDialogId(auctionPeer);
                tableView.addRowUser(getString(R.string.Gift2To), currentAccount, auctionToDid, () -> openProfile(auctionToDid), null, null);
            } else {
                if (fromId != selfId || prepaid_upgrade || isForChannel) {
                    tableView.addRowUser(getString(R.string.Gift2From), currentAccount, fromId, () -> openProfile(fromId), null, null);
                }
                if (toId != selfId || isForChannel) {
                    tableView.addRowUser(getString(R.string.Gift2To), currentAccount, toId, () -> openProfile(toId), null, null);
                }
            }
            tableView.addRowDateTime(getString(R.string.StarsTransactionDate), date);
            if (stargift.stars > 0) {
                tableView.addRow(getString(R.string.Gift2Value), replaceStarsWithPlain(TextUtils.concat("⭐️ " + LocaleController.formatNumber(stargift.stars + upgrade_stars, ','), " ", ""), .8f));
            }
            if (stargift != null && stargift.limited && !refunded) {
                addAvailabilityRow(tableView, currentAccount, stargift, resourcesProvider);
            }
            if (message != null && !TextUtils.isEmpty(message.text) && !refunded) {
                tableView.addFullRow(message.text, message.entities);
            }

            button.setFilled(true);
            button.setText(getString(R.string.OK), !firstSet);
            button.setSubText(null, !firstSet);
            button.setOnClickListener(v -> onBackPressed());
        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique) {
            final TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;
            if (!(action.gift instanceof TL_stars.TL_starGiftUnique)) {
                return this;
            }
            message = null;

            final TLObject giftFrom = action.name_hidden ? null :
                MessagesController.getInstance(currentAccount).getUserOrChat(action.from_id != null ?
                    DialogObject.getPeerDialogId(action.from_id) :
                    messageObject.getFromChatId());
            set((TL_stars.TL_starGiftUnique) action.gift, refunded = action.refunded, giftFrom, action.message);
            converted = false;
            saved = action.saved;
            stargift = action.gift;
            out = (!action.upgrade == messageObject.isOutOwner());
            if (messageObject.getDialogId() == selfId) {
                out = false;
            }
            name_hidden = false;
            repollSavedStarGift();

            if (!rolling && roller != null && roller.isRolling() && roller.rollingGift != null && (stargift == null || roller.rollingGift.id != stargift.id)) {
                roller.detach();
                roller = null;
                topView.imageLayout.setAlpha(1.0f);
                topView.imagesRollView.setAlpha(0.0f);
            }
        } else {
            return this;
        }

        final String owner_address = stargift == null ? null : stargift.owner_address;
        final String gift_address = stargift == null ? null : stargift.gift_address;
        final boolean hosted = stargift != null && stargift.host_id != null;

        if (refunded) {
            beforeTableTextView.setVisibility(View.VISIBLE);
            beforeTableTextView.setText(getString(R.string.Gift2Refunded));
            beforeTableTextView.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        } else if (TextUtils.isEmpty(owner_address) && TextUtils.isEmpty(gift_address) && name_hidden && !self) {
            beforeTableTextView.setVisibility(View.VISIBLE);
            beforeTableTextView.setText(
                out ?
                    formatString((message != null && !TextUtils.isEmpty(message.text)) ? R.string.Gift2OutSenderMessageHidden2 : R.string.Gift2OutSenderHidden2, DialogObject.getShortName(messageObject.getDialogId())) :
                    getString((message != null && !TextUtils.isEmpty(message.text)) ? R.string.Gift2InSenderMessageHidden2 : R.string.Gift2InSenderHidden2)
            );
            beforeTableTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        } else {
            beforeTableTextView.setVisibility(View.GONE);
        }

        if (!converted && !refunded && stargift != null && isMine(currentAccount, getDialogId()) && auctionPeer == null) {
            afterTableTextView.setVisibility(View.VISIBLE);
            if (getDialogId() >= 0) {
                final SpannableStringBuilder sb = new SpannableStringBuilder();
                if (!saved) {
                    sb.append(". ");
                    final ColoredImageSpan span = new ColoredImageSpan(R.drawable.mini_gift_hidden);
                    sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                sb.append(AndroidUtilities.replaceSingleTag(getString(saved ? R.string.Gift2ProfileVisible4 : R.string.Gift2ProfileInvisible4), this::toggleShow));
                afterTableTextView.setText(AndroidUtilities.replaceArrows(sb, true, dp(8f / 3f), dp(.66f)));
            } else {
                afterTableTextView.setText(AndroidUtilities.replaceArrows(AndroidUtilities.replaceSingleTag(getString(saved ? R.string.Gift2ChannelProfileVisible3 : R.string.Gift2ChannelProfileInvisible3), this::toggleShow), true, dp(8f / 3f), dp(.66f)));
            }
        } else {
            afterTableTextView.setVisibility(View.GONE);
        }

        if (firstSet) {
            switchPage(PAGE_INFO, false);
            layoutManager.scrollToPosition(1);
            firstSet = false;
        }

        actionBar.setTitle(getTitle());
        updateViewPager();

        return this;
    }

    private void repollMessage() {
        if (messageObjectRepolling || messageObjectRepolled || messageObject == null) {
            return;
        }
        messageObjectRepolling = true;
        final int id = messageObject.getId();
        final TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
        req.id.add(id);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            MessageObject newMessageObject = null;
            if (res instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages messages = (TLRPC.messages_Messages) res;
                for (int i = 0; i < messages.messages.size(); ++i) {
                    final TLRPC.Message msg = messages.messages.get(i);
                    if (msg != null && msg.id == id && (msg.action instanceof TLRPC.TL_messageActionStarGift || msg.action instanceof TLRPC.TL_messageActionStarGiftUnique)) {
                        newMessageObject = new MessageObject(currentAccount, msg, false, false);
                        newMessageObject.setType();
                        break;
                    }
                }
            }
            if (newMessageObject != null) {
                final MessageObject msg = newMessageObject;
                AndroidUtilities.runOnUIThread(() -> {
                    final TLRPC.messages_Messages messages = (TLRPC.messages_Messages) res;
                    MessagesController.getInstance(currentAccount).putUsers(messages.users, false);
                    MessagesController.getInstance(currentAccount).putChats(messages.chats, false);
                    messageObjectRepolled = true;
                    messageObjectRepolling = false;
                    if (unsavedFromSavedStarGift != null && msg != null && msg.messageOwner != null) {
                        if (msg.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
                            ((TLRPC.TL_messageActionStarGift) msg.messageOwner.action).saved = !unsavedFromSavedStarGift;
                        } else if (msg.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                            ((TLRPC.TL_messageActionStarGiftUnique) msg.messageOwner.action).saved = !unsavedFromSavedStarGift;
                        }
                    }
                    set(msg);
                });
            }
        });
    }

    private Boolean unsavedFromSavedStarGift;
    private void repollSavedStarGift() {
        if (userStarGiftRepolling || userStarGiftRepolled || messageObject == null) return;
        final TL_stars.InputSavedStarGift inputSavedStarGift = getInputStarGift();
        if (inputSavedStarGift == null) return;
        userStarGiftRepolling = true;
        StarsController.getInstance(currentAccount).getUserStarGift(inputSavedStarGift, upgradedGift -> {
            userStarGiftRepolling = false;
            userStarGiftRepolled = true;
            if (upgradedGift != null) {
                unsavedFromSavedStarGift = upgradedGift.unsaved;
                if (messageObject != null && messageObject.messageOwner != null) {
                    if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                        TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;
                        if (action.saved == !upgradedGift.unsaved) return;
                        action.saved = !upgradedGift.unsaved;
                    } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
                        TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;
                        if (action.saved == !upgradedGift.unsaved) return;
                        action.saved = !upgradedGift.unsaved;
                    }
                    set(messageObject);
                }
            }
        });
    }

    private long getDialogId() {
        if (messageObject != null) {
            if (messageObject.messageOwner == null) return 0;
            if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
                final TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;
                if (action.peer != null) {
                    return DialogObject.getPeerDialogId(action.peer);
                }
                return messageObject.isOutOwner() ? messageObject.getDialogId() : UserConfig.getInstance(currentAccount).getClientUserId();
            } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                final TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;
                if (action.gift instanceof TL_stars.TL_starGiftUnique && action.gift.owner_id != null) {
                    return DialogObject.getPeerDialogId(action.gift.owner_id);
                }
                if (action.peer != null) {
                    return DialogObject.getPeerDialogId(action.peer);
                }
            }
        } else if (savedStarGift != null) {
            if (savedStarGift.gift instanceof TL_stars.TL_starGiftUnique) {
                final TL_stars.TL_starGiftUnique gift = (TL_stars.TL_starGiftUnique) savedStarGift.gift;
                return DialogObject.getPeerDialogId(gift.owner_id);
            }
            return dialogId;
        } else if (slugStarGift != null) {
            if (slugStarGift instanceof TL_stars.TL_starGiftUnique) {
                final TL_stars.TL_starGiftUnique gift = (TL_stars.TL_starGiftUnique) slugStarGift;
                return DialogObject.getPeerDialogId(gift.owner_id);
            }
        }
        return 0;
    }

    private String getLink() {
        final TL_stars.StarGift starGift = getGift();
        if (starGift instanceof TL_stars.TL_starGiftUnique && starGift.slug != null) {
            return MessagesController.getInstance(currentAccount).linkPrefix + "/nft/" + starGift.slug;
        }
        return null;
    }

    private void openInProfile() {
        final long dialogId = getDialogId();
        if (dialogId == 0) return;
        openProfile(dialogId);
    }

    private void openProfile(long did) {
        if (currentHintView != null) {
            currentHintView.hide();
            currentHintView = null;
        }
        dismiss();
        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment != null) {
            if (UserObject.isService(did)) return;
            Bundle args = new Bundle();
            if (did > 0) {
                args.putLong("user_id", did);
                if (did == UserConfig.getInstance(currentAccount).getClientUserId()) {
                    args.putBoolean("my_profile", true);
                }
            } else {
                args.putLong("chat_id", -did);
            }
            args.putBoolean("open_gifts", true);
            lastFragment.presentFragment(new ProfileActivity(args));
        }
    }

    private void toggleShow() {
        if (button.isLoading()) return;

        final boolean saved;
        final TLRPC.Document sticker;
        final TL_stars.InputSavedStarGift inputStarGift = getInputStarGift();
        if (messageObject != null && messageObject.messageOwner != null) {
            final TLRPC.MessageAction _action = messageObject.messageOwner.action;
            if (_action instanceof TLRPC.TL_messageActionStarGift) {
                final TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;
                saved = action.saved;
                sticker = action.gift.getDocument();
            } else if (_action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                final TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;
                saved = action.saved;
                sticker = action.gift.getDocument();
            } else {
                return;
            }
        } else if (savedStarGift != null) {
            saved = !savedStarGift.unsaved;
            sticker = savedStarGift.gift.getDocument();
        } else {
            return;
        }

        button.setLoading(true);

        final TL_stars.saveStarGift req = new TL_stars.saveStarGift();
        final boolean unsave = req.unsave = saved;
        req.stargift = inputStarGift;
        boolean updatedInList = false;
        if (savedStarGift != null) {
            final StarsController.GiftsCollections collections = StarsController.getInstance(currentAccount).getProfileGiftCollectionsList(dialogId, false);
            if (collections != null) {
                collections.updateGiftsUnsaved(savedStarGift, req.unsave);
                updatedInList = true;
            }
        }
        final boolean finalUpdatedInList = updatedInList;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
            if (lastFragment == null) return;
            if (res instanceof TLRPC.TL_boolTrue) {
                dismiss();
                final long did = getDialogId();
                if (!finalUpdatedInList) {
                    StarsController.getInstance(currentAccount).invalidateProfileGifts(did);
                }
                if (did >= 0) {
                    BulletinFactory.of(lastFragment)
                        .createEmojiBulletin(
                            sticker,
                            LocaleController.getString(unsave ? R.string.Gift2MadePrivateTitle : R.string.Gift2MadePublicTitle),
                            AndroidUtilities.replaceSingleTag(LocaleController.getString(unsave ? R.string.Gift2MadePrivate : R.string.Gift2MadePublic), lastFragment instanceof ProfileActivity ? null : () -> {
                                final Bundle args = new Bundle();
                                if (did >= 0) {
                                    args.putLong("user_id", did);
                                } else {
                                    args.putLong("chat_id", -did);
                                }
                                args.putBoolean("my_profile", true);
                                args.putBoolean("open_gifts", true);
                                final ProfileActivity profileActivity = new ProfileActivity(args);
                                lastFragment.presentFragment(profileActivity);
                            })
                        )
                        .show(true);
                } else {
                    BulletinFactory.of(lastFragment)
                        .createEmojiBulletin(
                            sticker,
                            LocaleController.getString(unsave ? R.string.Gift2ChannelMadePrivateTitle : R.string.Gift2ChannelMadePublicTitle),
                            LocaleController.getString(unsave ? R.string.Gift2ChannelMadePrivate : R.string.Gift2ChannelMadePublic)
                        )
                        .show();
                }
            } else if (err != null) {
                if (finalUpdatedInList && savedStarGift != null) {
                    final StarsController.GiftsCollections collections = StarsController.getInstance(currentAccount).getProfileGiftCollectionsList(dialogId, false);
                    if (collections != null) {
                        collections.updateGiftsUnsaved(savedStarGift, !req.unsave);
                    }
                }
                getBulletinFactory().createErrorBulletin(formatString(R.string.UnknownErrorCode, err.text)).show(false);
            }
        }));
    }

    @Override
    public void dismiss() {

        if (roller != null) {
            roller.detach();
        }
        super.dismiss();
    }

    @Override
    public void show() {
        if (MessagesController.getInstance(currentAccount).isFrozen()) {
            AccountFrozenAlert.show(currentAccount);
            return;
        }
        if (slug != null && slugStarGift == null) {
            final AlertDialog progressDialog = new AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER);
            progressDialog.showDelayed(500);

            final TL_stars.getUniqueStarGift req = new TL_stars.getUniqueStarGift();
            req.slug = slug;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                if (res instanceof TL_stars.TL_payments_uniqueStarGift) {
                    final TL_stars.TL_payments_uniqueStarGift r = (TL_stars.TL_payments_uniqueStarGift) res;
                    MessagesController.getInstance(currentAccount).putUsers(r.users, false);
                    MessagesController.getInstance(currentAccount).putChats(r.chats, false);
                    if (r.gift instanceof TL_stars.TL_starGiftUnique) {
                        AndroidUtilities.runOnUIThread(() -> {
                            slugStarGift = (TL_stars.TL_starGiftUnique) r.gift;
                            set(slugStarGift, false);
                            super.show();
                        });
                        return;
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    progressDialog.dismiss();
                    BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                    if (lastFragment != null) {
                        if (err != null && "STARGIFT_ALREADY_BURNED".equalsIgnoreCase(err.text)) {
                            BulletinFactory.of(lastFragment)
                                .createSimpleBulletin(R.raw.fire_on, getString(R.string.UniqueGiftNotFoundBurned))
                                .show();
                        } else {
                            BulletinFactory.of(lastFragment)
                                .createSimpleBulletin(R.raw.error, getString(R.string.UniqueGiftNotFound))
                                .show();
                        }
                    }
                });
            });
        } else if (savedStarGift == null && messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
            final TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;
            if (action.upgraded) {
                if (action.upgrade_msg_id != 0) {
                    final AlertDialog progressDialog = new AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER);
                    progressDialog.showDelayed(500);

                    final TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
                    req.id.add(action.upgrade_msg_id);
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
                        MessageObject newMessageObject = null;
                        if (res instanceof TLRPC.messages_Messages) {
                            final TLRPC.messages_Messages messages = (TLRPC.messages_Messages) res;
                            MessagesController.getInstance(currentAccount).putUsers(messages.users, false);
                            MessagesController.getInstance(currentAccount).putChats(messages.chats, false);
                            for (int i = 0; i < messages.messages.size(); ++i) {
                                final TLRPC.Message msg = messages.messages.get(i);
                                if (msg != null && !(msg instanceof TLRPC.TL_messageEmpty) && msg.id == action.upgrade_msg_id) {
                                    newMessageObject = new MessageObject(currentAccount, msg, false, false);
                                    newMessageObject.setType();
                                    break;
                                }
                            }
                        }
                        if (newMessageObject != null) {
                            final MessageObject msg = newMessageObject;
                            AndroidUtilities.runOnUIThread(() -> {
                                progressDialog.dismiss();
                                messageObjectRepolled = true;
                                set(msg);
                                super.show();
                            });
                        } else {
                            AndroidUtilities.runOnUIThread(() -> {
                                progressDialog.dismiss();
                                final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                                if (lastFragment != null) {
                                    BulletinFactory.of(lastFragment)
                                        .createSimpleBulletin(R.raw.error, getString(R.string.MessageNotFound))
                                        .ignoreDetach()
                                        .show();
                                }
                            });
                        }
                    });
                    return;
                } else if (getInputStarGift() != null) {
                    final AlertDialog progressDialog = new AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER);
                    progressDialog.showDelayed(500);

                    StarsController.getInstance(currentAccount).getUserStarGift(getInputStarGift(), savedGift -> {
                        if (savedGift != null) {
                            progressDialog.dismiss();
                            userStarGiftRepolled = true;
                            set(savedGift, null);
                            super.show();
                        } else {
                            progressDialog.dismiss();
                            final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                            if (lastFragment != null) {
                                BulletinFactory.of(lastFragment)
                                    .createSimpleBulletin(R.raw.error, getString(R.string.MessageNotFound))
                                    .ignoreDetach()
                                    .show();
                            }
                        }
                    });
                    return;
                }
            }
        }

        super.show();
    }

    private void presentFragment(BaseFragment fragment) {
        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment == null) return;

        final BaseFragment.BottomSheetParams params = new BaseFragment.BottomSheetParams();
        params.transitionFromLeft = true;
        params.allowNestedScroll = false;
        lastFragment.showAsSheet(fragment, params);
    }

    public static TL_stars.InputSavedStarGift getInputStarGift(long dialogId, TL_stars.SavedStarGift g) {
        if (!TextUtils.isEmpty(g.gift.slug)) {
            final TL_stars.TL_inputSavedStarGiftSlug inputSavedStarGiftSlug = new TL_stars.TL_inputSavedStarGiftSlug();
            inputSavedStarGiftSlug.slug = g.gift.slug;
            return inputSavedStarGiftSlug;
        }
        final TL_stars.TL_inputSavedStarGiftChat stargift = new TL_stars.TL_inputSavedStarGiftChat();
        stargift.peer = MessagesController.getInstance(UserConfig.selectedAccount).getInputPeer(dialogId);
        stargift.saved_id = g.saved_id;
        return stargift;
    }

    private TL_stars.InputSavedStarGift getInputStarGift() {
        if (dialogId < 0) {
            final TL_stars.TL_inputSavedStarGiftChat stargift = new TL_stars.TL_inputSavedStarGiftChat();
            stargift.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            if (messageObject != null && messageObject.messageOwner != null) {
                if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
                    final TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;
                    if ((action.flags & 4096) == 0) {
                        return null;
                    }
                    stargift.saved_id = action.saved_id;
                } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                    final TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;
                    if ((action.flags & 128) == 0) {
                        return null;
                    }
                    stargift.saved_id = action.saved_id;
                } else return null;
            } else if (savedStarGift != null) {
                if ((savedStarGift.flags & 2048) == 0) {
                    return null;
                }
                stargift.saved_id = savedStarGift.saved_id;
            } else if (slugStarGift != null && !TextUtils.isEmpty(slug)) {
                final TL_stars.TL_inputSavedStarGiftSlug inputSavedStarGiftSlug = new TL_stars.TL_inputSavedStarGiftSlug();
                inputSavedStarGiftSlug.slug = slug;
                return inputSavedStarGiftSlug;
            }
            return stargift;
        } else if (messageObject != null && messageObject.getDialogId() < 0 && messageObject.messageOwner != null && messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift && (messageObject.messageOwner.action.flags & 4096) != 0) {
            final TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;
            final TL_stars.TL_inputSavedStarGiftChat stargift = new TL_stars.TL_inputSavedStarGiftChat();
            stargift.peer = MessagesController.getInstance(currentAccount).getInputPeer(messageObject.getDialogId());
            stargift.saved_id = action.saved_id;
            return stargift;
        } else if (messageObject != null && messageObject.getDialogId() < 0 && messageObject.messageOwner != null && messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique && (messageObject.messageOwner.action.flags & 128) != 0) {
            final TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;
            final TL_stars.TL_inputSavedStarGiftChat stargift = new TL_stars.TL_inputSavedStarGiftChat();
            stargift.peer = MessagesController.getInstance(currentAccount).getInputPeer(messageObject.getDialogId());
            stargift.saved_id = action.saved_id;
            return stargift;
        } else {
            final TL_stars.TL_inputSavedStarGiftUser stargift = new TL_stars.TL_inputSavedStarGiftUser();
            if (messageObject != null) {
                if (messageObject.messageOwner != null && messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift && (messageObject.messageOwner.action.flags & 32768) != 0) {
                    stargift.msg_id = ((TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action).gift_msg_id;
                } else {
                    stargift.msg_id = messageObject.getId();
                }
            } else if (savedStarGift != null) {
                stargift.msg_id = savedStarGift.msg_id;
            } else if (slugStarGift != null && !TextUtils.isEmpty(slug)) {
                final TL_stars.TL_inputSavedStarGiftSlug inputSavedStarGiftSlug = new TL_stars.TL_inputSavedStarGiftSlug();
                inputSavedStarGiftSlug.slug = slug;
                return inputSavedStarGiftSlug;
            }
            return stargift;
        }
    }

    public BulletinFactory getBulletinFactory() {
        return BulletinFactory.of(bottomBulletinContainer, resourcesProvider);
    }

    @Override
    public void onBackPressed() {

        if (!onlyWearInfo && currentPage.to > 0 && !button.isLoading()) {
            if (messageObject != null) {
                set(messageObject);
            } else if (savedStarGift != null) {
                set(savedStarGift, giftsList);
            } else if (slugStarGift != null) {
                set(slug, slugStarGift, giftsList);
            }
            switchPage(PAGE_INFO, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSwipeStarts() {
        if (currentHintView != null) {
            currentHintView.hide();
            currentHintView = null;
        }
    }

    private HintView2 currentHintView;
    private View currentHintViewTextView;
    public void showHint(CharSequence hintText, View textView, boolean onRightDrawable) {
        if (currentHintView != null && currentHintView.shown() && currentHintViewTextView == textView) {
            return;
        }
        if (textView == null) return;

        final float cx;
        if (onRightDrawable) {
            if (textView instanceof SimpleTextView) {
                cx = ((SimpleTextView) textView).getRightDrawableX() + ((SimpleTextView) textView).getRightDrawableWidth() / 2.0f;
            } else {
                return;
            }
        } else {
            final Layout layout;
            if (textView instanceof TextView) {
                layout = ((TextView) textView).getLayout();
            } else if (textView instanceof SimpleTextView) {
                layout = ((SimpleTextView) textView).getLayout();
            } else {
                return;
            }
            if (layout == null) return;
            final CharSequence text = layout.getText();
            if (!(text instanceof Spanned)) return;
            final Spanned spanned = (Spanned) text;

            final ButtonSpan[] buttons = spanned.getSpans(0, spanned.length(), ButtonSpan.class);
            if (buttons == null || buttons.length <= 0) return;

            final ButtonSpan span = buttons[buttons.length - 1];
            final int start = spanned.getSpanStart(span);
            cx = textView.getPaddingLeft() + layout.getPrimaryHorizontal(start) + span.getSize() / 2.0f;
        }

        final int[] loc = new int[2];
        final int[] loc2 = new int[2];
        textView.getLocationOnScreen(loc);
        container.getLocationOnScreen(loc2);
        loc[0] -= loc2[0];
        loc[1] -= loc2[1];

        if (currentHintView != null) {
            currentHintView.hide();
            currentHintView = null;
        }
        final HintView2 hintView = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
        hintView.setMultilineText(!onRightDrawable);
        hintView.setText(hintText);
        hintView.setJointPx(0, loc[0] + cx - (dp(16) + backgroundPaddingLeft));
        hintView.setTranslationY(loc[1] - dp(100) - textView.getHeight() / 2.0f + dp(4.33f + (onRightDrawable ? 18 : 0)));
        hintView.setDuration(3000);
        hintView.setPadding(dp(16) + backgroundPaddingLeft, 0, dp(16) + backgroundPaddingLeft, 0);
        hintView.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(hintView));
        hintView.show();
        container.addView(hintView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100));
        currentHintView = hintView;
        currentHintViewTextView = textView;
    }

    public static class GiftTransferTopView extends View {

        private final StarGiftDrawableIcon giftDrawable;
        private final ImageReceiver userImageReceiver;

        private final Path arrowPath = new Path();
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public GiftTransferTopView(Context context, TL_stars.StarGift gift, TLObject obj) {
            super(context);

            giftDrawable = new StarGiftDrawableIcon(this, gift, 60, .27f);
            giftDrawable.setPatternsType(StarGiftPatterns.TYPE_LINK_PREVIEW);
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(obj);
            userImageReceiver = new ImageReceiver(this);
            userImageReceiver.setRoundRadius(dp(30));
            userImageReceiver.setForUserOrChat(obj, avatarDrawable);

            arrowPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText7));
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setStrokeWidth(dp(2));

            arrowPath.rewind();
            arrowPath.moveTo(0, -dp(8));
            arrowPath.lineTo(dp(6.166f), 0);
            arrowPath.lineTo(0, dp(8));
        }

        public GiftTransferTopView(Context context, TL_stars.StarGift gift) {
            super(context);

            giftDrawable = new StarGiftDrawableIcon(this, gift, 60, .27f);
            giftDrawable.setPatternsType(StarGiftPatterns.TYPE_LINK_PREVIEW);
            userImageReceiver = new ImageReceiver(this);
            userImageReceiver.setRoundRadius(dp(30));
            userImageReceiver.setImageBitmap(SessionCell.createDrawable(60, "fragment"));

            arrowPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText7));
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setStrokeWidth(dp(2.33f));

            arrowPath.rewind();
            arrowPath.moveTo(0, -dp(8));
            arrowPath.lineTo(dp(6.166f), 0);
            arrowPath.lineTo(0, dp(8));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(100), MeasureSpec.EXACTLY)
            );
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int width = dp(60 + 36 + 60);
            int left = getWidth() / 2 - width / 2, top = getHeight() / 2 - dp(30);
            giftDrawable.setBounds(left, top, left + dp(60), top + dp(60));
            giftDrawable.draw(canvas);

            canvas.save();
            canvas.translate(getWidth() / 2.0f - dp(6.166f) / 2.0f, getHeight() / 2.0f);
            canvas.drawPath(arrowPath, arrowPaint);
            canvas.restore();

            userImageReceiver.setImageCoords(left + dp(60 + 36), top, dp(60), dp(60));
            userImageReceiver.draw(canvas);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            userImageReceiver.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            userImageReceiver.onDetachedFromWindow();
        }
    }

    public static class UserToUserTransferTopView extends View {

        private final ImageReceiver fromUserImageReceiver;
        private final ImageReceiver toUserImageReceiver;

        private final Path arrowPath = new Path();
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public UserToUserTransferTopView(Context context, TLObject fromObj, TLObject toObj) {
            super(context);

            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(fromObj);
            fromUserImageReceiver = new ImageReceiver(this);
            fromUserImageReceiver.setRoundRadius(dp(30));
            fromUserImageReceiver.setForUserOrChat(fromObj, avatarDrawable);

            AvatarDrawable avatarDrawable2 = new AvatarDrawable();
            avatarDrawable2.setInfo(toObj);
            toUserImageReceiver = new ImageReceiver(this);
            toUserImageReceiver.setRoundRadius(dp(30));
            toUserImageReceiver.setForUserOrChat(toObj, avatarDrawable2);

            arrowPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText7));
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setStrokeWidth(dp(2));

            arrowPath.rewind();
            arrowPath.moveTo(0, -dp(8));
            arrowPath.lineTo(dp(6.166f), 0);
            arrowPath.lineTo(0, dp(8));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(100), MeasureSpec.EXACTLY)
            );
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int width = dp(60 + 36 + 60);
            int left = getWidth() / 2 - width / 2, top = getHeight() / 2 - dp(30);
            fromUserImageReceiver.setImageCoords(left, top, dp(60), dp(60));
            fromUserImageReceiver.draw(canvas);

            canvas.save();
            canvas.translate(getWidth() / 2.0f - dp(6.166f) / 2.0f, getHeight() / 2.0f);
            canvas.drawPath(arrowPath, arrowPaint);
            canvas.restore();

            toUserImageReceiver.setImageCoords(left + dp(60 + 36), top, dp(60), dp(60));
            toUserImageReceiver.draw(canvas);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            fromUserImageReceiver.onAttachedToWindow();
            toUserImageReceiver.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            fromUserImageReceiver.onDetachedFromWindow();
            toUserImageReceiver.onDetachedFromWindow();
        }
    }

    public static class GiftThemeReuseTopView extends View {

        private final StarGiftDrawableIcon giftDrawable;
        private final ImageReceiver userImageReceiver;
        private final Drawable drawable;

        public GiftThemeReuseTopView(Context context, TL_stars.StarGift gift, TLObject obj) {
            super(context);

            giftDrawable = new StarGiftDrawableIcon(this, gift, 60, .27f);
            giftDrawable.setPatternsType(StarGiftPatterns.TYPE_LINK_PREVIEW);
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(obj);
            userImageReceiver = new ImageReceiver(this);
            userImageReceiver.setRoundRadius(dp(30));
            userImageReceiver.setForUserOrChat(obj, avatarDrawable);

            drawable = context.getDrawable(R.drawable.chats_undo).mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.MULTIPLY));
            drawable.setBounds(dp(-12), dp(-12), dp(12), dp(12));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(100), MeasureSpec.EXACTLY)
            );
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int width = dp(60 + 36 + 60);
            int left = getWidth() / 2 - width / 2, top = getHeight() / 2 - dp(30);
            giftDrawable.setBounds(left, top, left + dp(60), top + dp(60));
            giftDrawable.draw(canvas);

            canvas.save();
            canvas.translate(getWidth() / 2.0f, getHeight() / 2.0f);
            drawable.draw(canvas);
            canvas.restore();

            userImageReceiver.setImageCoords(left + dp(60 + 36), top, dp(60), dp(60));
            userImageReceiver.draw(canvas);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            userImageReceiver.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            userImageReceiver.onDetachedFromWindow();
        }
    }

    public static class StarGiftDrawableIcon extends CompatDrawable {

        private final Path path = new Path();
        private final RectF rect = new RectF();
        private ReactionParticles particles;
        private final int sizeDp;
        private final TL_stars.StarGift starGift;
        private final View view;

        private final ImageReceiver imageReceiver;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable pattern;
        private RadialGradient gradient;
        private final Matrix matrix = new Matrix();
        private Text giftName;
        private Text giftStatus;
        private AnimatedTextView.AnimatedTextDrawable countdownText;

        private float patternsScale;

        public StarGiftDrawableIcon(View view, TL_stars.StarGift gift, int sizeDp, float patternsScale) {
            super(view);

            this.starGift = gift;
            this.view = view;
            this.patternsScale = patternsScale;
            imageReceiver = new ImageReceiver(view);
            pattern = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(view, false, dp(sizeDp > 180 ? 24 : 18));
            this.sizeDp = sizeDp;

            if (gift instanceof TL_stars.TL_starGift) {
                setGiftImage(imageReceiver, gift.sticker, (int) (sizeDp * .75f));

                giftName = new Text(gift.title != null ? gift.title : "Gift", 16, AndroidUtilities.bold());
                giftName.setColor(Color.WHITE);
                giftName.setMaxWidth(dp(sizeDp - 30));
                giftName.align(Layout.Alignment.ALIGN_CENTER);
                giftName.multiline(1);

                giftStatus = new Text(gift.sold_out ? getString(R.string.Gift2SoldOutTitle) :
                    formatPluralString("Gift2SoldAuctionPreviewGifts", gift.availability_total), 13);
                giftStatus.setMaxWidth(dp(sizeDp - 30));
                giftStatus.align(Layout.Alignment.ALIGN_CENTER);
                giftStatus.multiline(1);

                particles = new ReactionParticles(ReactionParticles.TYPE_RADIAL, 40);
                particles.setBounds(-dp(sizeDp * 0.45f), -dp(sizeDp * 0.45f), dp(sizeDp * 0.45f), dp(sizeDp * 0.25f));
                particles.generateGrid();
            } else if (gift != null) {
                final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
                final TL_stars.starGiftAttributePattern pattern = findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class);
                final TL_stars.starGiftAttributeModel model = findAttribute(gift.attributes, TL_stars.starGiftAttributeModel.class);

                if (pattern != null) {
                    this.pattern.set(pattern.document, false);
                }
                if (backdrop != null) {
                    gradient = new RadialGradient(0, 0, dpf2(sizeDp) / 2, new int[]{ backdrop.center_color | 0xFF000000, backdrop.edge_color | 0xFF000000 }, new float[] { 0, 1 }, Shader.TileMode.CLAMP);
                    this.pattern.setColor(backdrop.pattern_color | 0xFF000000);
                }
                if (model != null) {
                    setGiftImage(imageReceiver, model.document, (int) (sizeDp * .75f));
                }
            }
            paint.setShader(gradient);

            if (view.isAttachedToWindow()) {
                onAttachedToWindow();
            }
        }

        public void setGradient(int centerColor, int edgeColor) {
            gradient = new RadialGradient(0, 0, dpf2(sizeDp) / 2, new int[]{ centerColor | 0xFF000000, edgeColor | 0xFF000000 }, new float[] { 0, 1 }, Shader.TileMode.CLAMP);
            paint.setShader(gradient);
        }

        public void setAuctionStateTextColor(int textColor) {
            if (giftStatus != null) {
                giftStatus.setColor(textColor | 0xFF000000);
            }
        }

        private CountdownTimer countdownTimer;
        private int endTime;
        private int startTime;

        public void setCountdownRemainingTime(int startTime, int endTime) {
            this.startTime = startTime;
            this.endTime = endTime;

            if (countdownTimer == null) {
                countdownTimer = new CountdownTimer((r) -> {
                    updateCountdownText(r, true);
                });
            }

            final int current = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
            final int remaining;
            if (current < startTime) {
                remaining = startTime - current;
            } else {
                remaining = endTime - current;
            }

            countdownTimer.start(remaining);
            if (countdownText == null) {
                countdownText = new AnimatedTextView.AnimatedTextDrawable();
                countdownText.setTextColor(Color.WHITE);
                countdownText.setTextSize(dp(12));
                countdownText.setCallback(new Callback() {
                    @Override
                    public void invalidateDrawable(@NonNull Drawable who) {
                        view.invalidate();
                    }

                    @Override
                    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {

                    }

                    @Override
                    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {

                    }
                });
            }
            updateCountdownText(remaining, false);
        }

        private void updateCountdownText(long ignoredRemaining, boolean animated) {
            final int current = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
            final int remaining;
            if (current > endTime) {
                countdownText.setText(getString(R.string.Gift2AuctionCountdownFinished));
            } else if (current < startTime) {
                remaining = startTime - current;
                countdownText.setText(formatString(R.string.Gift2AuctionCountdownStartsIn, formatDuration(remaining, true)));
            } else {
                remaining = endTime - current;
                countdownText.setText(formatDuration(remaining, true));
            }
            if (current > endTime && giftStatus != null) {
                giftStatus.setText(getString(R.string.Gift2SoldOutTitle));
            }
        }

        private final Paint countdownPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int rounding = dp(16);
        public StarGiftDrawableIcon setRounding(int r) {
            this.rounding = r;
            return this;
        }

        private int patternsType = StarGiftPatterns.TYPE_DEFAULT;
        public StarGiftDrawableIcon setPatternsType(int type) {
            this.patternsType = type;
            return this;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());

            canvas.save();
            path.rewind();
            path.addRoundRect(rect, rounding, rounding, Path.Direction.CW);
            canvas.clipPath(path);

            if (gradient != null) {
                matrix.reset();
                matrix.postTranslate(rect.centerX(), rect.centerY());
                gradient.setLocalMatrix(matrix);
                paint.setShader(gradient);
            }
            canvas.drawPaint(paint);

            canvas.save();
            canvas.translate(rect.centerX(), rect.centerY());
            StarGiftPatterns.drawPattern(canvas, patternsType, pattern, rect.width(), rect.height(), 1.0f, patternsScale);
            if (particles != null) {
                particles.draw(canvas, Color.WHITE, 1f);
            }
            canvas.restore();

            if (giftName != null && giftStatus != null) {
                if (countdownText != null) {
                    countdownPaint.setColor(0x50000000);
                    canvas.drawRoundRect(
                        rect.left + dp(6),
                        rect.top + dp(6),
                        rect.left + dp(6 + 14) + Math.max(countdownText.getCurrentWidth(), dp(3)),
                        rect.top + dp(6 + 17),
                        dp(17 / 2f),
                        dp(17 / 2f),
                        countdownPaint
                    );
                    canvas.save();
                    canvas.translate(rect.left + dp(13), rect.top + dp(14f));
                    countdownText.draw(canvas);
                    canvas.restore();
                }

                final float imageSize = Math.min(rect.width(), rect.height()) * 0.6f;

                imageReceiver.setImageCoords(rect.centerX() - imageSize / 2, rect.top + rect.height() * 0.12f, imageSize, imageSize);
                imageReceiver.draw(canvas);

                giftName.draw(canvas, rect.centerX() - giftName.getWidth() / 2f, rect.bottom - dp(50));
                giftStatus.draw(canvas, rect.centerX() - giftStatus.getWidth() / 2f, rect.bottom - dp(30));
            } else {
                final float imageSize = Math.min(rect.width(), rect.height()) * (0.75f);
                imageReceiver.setImageCoords(rect.centerX() - imageSize / 2, rect.centerY() - imageSize / 2, imageSize, imageSize);
                imageReceiver.draw(canvas);
            }

            canvas.restore();
        }

        @Override
        public void onAttachedToWindow() {
            pattern.attach();
            imageReceiver.onAttachedToWindow();
            if (countdownTimer != null) {
                final int current = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
                final int remaining;
                if (current < startTime) {
                    remaining = startTime - current;
                } else {
                    remaining = endTime - current;
                }
                countdownTimer.start(remaining);
            }
        }

        @Override
        public void onDetachedToWindow() {
            pattern.detach();
            imageReceiver.onDetachedFromWindow();
            if (countdownTimer != null) {
                countdownTimer.stop();
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return dp(sizeDp);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(sizeDp);
        }
    }

    public static class UpgradeIcon extends CompatDrawable {

        private final View view;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final long start = System.currentTimeMillis();

        public UpgradeIcon(View view, int color) {
            super(view);
            this.view = view;

            paint.setColor(0xFFFFFFFF);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setColor(color);

            arrow.rewind();
            arrow.moveTo(-dpf2(2.91f), dpf2(1.08f));
            arrow.lineTo(0, -dpf2(1.08f));
            arrow.lineTo(dpf2(2.91f), dpf2(1.08f));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            paint.setAlpha((int) (0xFF * alpha));
            canvas.drawCircle(getBounds().centerX(), getBounds().centerY(), getBounds().width() / 2.0f, paint);

            final float t = (System.currentTimeMillis() - start) % 400 / 400.0f;

            final int wasAlpha = strokePaint.getAlpha();
            strokePaint.setAlpha((int) (wasAlpha * alpha));
            strokePaint.setStrokeWidth(dpf2(1.33f));
            canvas.save();
            final float arrowsHeight = dpf2(2.16f) * 3 + dpf2(1.166f) * 2;
            canvas.translate(getBounds().centerX(), getBounds().centerY() - (arrowsHeight) / 2.0f);
            for (int i = 0; i < 4; ++i) {
                float alpha = 1.0f;
                if (i == 0) {
                    alpha = 1.0f - t;
                } else if (i == 3) {
                    alpha = t;
                }
                strokePaint.setAlpha((int) (0xFF * alpha * this.alpha));
                canvas.save();
                final float s = lerp(0.5f, 1.0f, alpha);
                canvas.scale(s, s);
                canvas.drawPath(arrow, strokePaint);
                canvas.restore();
                canvas.translate(0, dpf2(2.16f + 1.166f) * alpha);
            }
            canvas.restore();
            strokePaint.setAlpha(wasAlpha);

            if (view != null) {
                view.invalidate();
            }
        }

        private float alpha = 1.0f;
        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha / 255.0f;
        }

        @Override
        public int getIntrinsicWidth() {
            return dp(18);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(18);
        }
    }

    public static class PageTransition {
        public int from, to;
        public float progress;

        public PageTransition(int from, int to, float t) {
            this.from = from;
            this.to = to;
        }

        public void setProgress(float t) {
            this.progress = t;
        }

        public float at(int page) {
            if (to == page && from == page) {
                return 1.0f;
            }
            if (to == page) {
                return progress;
            }
            if (from == page) {
                return 1.0f - progress;
            }
            return 0.0f;
        }

        public boolean to(int page) {
            return to == page;
        }

        public float at(int page1, int page2) {
            if (contains(page1) && contains(page2)) {
                return 1.0f;
            }
            return Math.max(at(page1), at(page2));
        }
        public float at(int page1, int page2, int page3) {
            if (
                contains(page1) && contains(page2) ||
                contains(page2) && contains(page3) ||
                contains(page3) && contains(page1)
            ) {
                return 1.0f;
            }
            return Math.max(at(page1), Math.max(at(page2), at(page3)));
        }

        public boolean contains(int page) {
            return from == page || to == page;
        }

        public boolean is(int page) {
            return to == page;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftsLoaded) {
            StarsController.GiftsList notifiedList = (StarsController.GiftsList) args[1];
            if (giftsList == notifiedList) {
                updateViewPager();
            }
        }
    }

    public static CharSequence replaceUnderstood(CharSequence cs) {
        return replaceUnderstood(cs, null);
    }
    public static CharSequence replaceUnderstood(CharSequence cs, ColoredImageSpan[] cache) {
        if (cs == null) return null;
        SpannableStringBuilder ssb;
        if (!(cs instanceof SpannableStringBuilder)) {
            ssb = new SpannableStringBuilder(cs);
        } else {
            ssb = (SpannableStringBuilder) cs;
        }

        final SpannableString ok = new SpannableString("👌");
        ok.setSpan(new ColoredImageSpan(R.drawable.filled_understood), 0, ok.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final SpannableString thumbs = new SpannableString("👍");
        thumbs.setSpan(new ColoredImageSpan(R.drawable.filled_reactions), 0, thumbs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        AndroidUtilities.replaceMultipleCharSequence("👌", ssb, ok);
        AndroidUtilities.replaceMultipleCharSequence("👍", ssb, thumbs);

        return ssb;
    }

    public static class ActionView extends View {

        private final TextPaint paint;
        private final LinkPath path;
        private final Paint bgPaint;
        private final Paint bgDarkerPaint;
        private StaticLayout layout;
        private boolean fullRect;
        private int px = dp(6), py = dp(2);

        public ActionView(Context context) {
            super(context);

            paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(13));

            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setPathEffect(new CornerPathEffect(dp(9.66f)));

            bgDarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgDarkerPaint.setPathEffect(new CornerPathEffect(dp(9.66f)));

            path = new LinkPath(true);
        }

        public void setFullRect(boolean b) {
            this.fullRect = b;
        }

        public void setPadding(int x, int y) {
            this.px = x;
            this.py = y;
        }

        public void setRoundRadius(float r) {
            bgPaint.setPathEffect(new CornerPathEffect(r));
            bgDarkerPaint.setPathEffect(new CornerPathEffect(r));
        }

        private BitmapShader blurBitmapShader;
        private Matrix blurMatrix;
        private Matrix blurInvertMatrix;

        public void prepareBlur(View parentDecorView) {
            final ArrayList<View> list = new ArrayList<View>();
            if (parentDecorView != null) {
                list.add(parentDecorView);
            }
            AndroidUtilities.makeGlobalBlurBitmap(bitmap -> {
                blurMatrix = new Matrix();
                blurInvertMatrix = new Matrix();
                blurBitmapShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bgPaint.setShader(blurBitmapShader);
                final ColorMatrix colorMatrix = new ColorMatrix();
                AndroidUtilities.adjustSaturationColorMatrix(colorMatrix, 0.25f);
                bgPaint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
            }, 12, 12, null, list);
        }

        private CharSequence textToSet;

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            if (textToSet != null) {
                set(textToSet, width);
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(layout == null ? 0 : layout.getHeight() + dp(32), MeasureSpec.EXACTLY)
            );

            setPivotX(getMeasuredWidth() / 2.0f);
            setPivotY(getMeasuredHeight());
        }

        public void set(MessageObject messageObject) {
            if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.action == null) {
                setVisibility(View.GONE);
                return;
            }

            final int currentAccount = messageObject.currentAccount;
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();

            if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGift) {
                final TLRPC.TL_messageActionStarGift action = (TLRPC.TL_messageActionStarGift) messageObject.messageOwner.action;

//                if (action.prepaid_upgrade) {
//                     TODO

                    setVisibility(View.GONE);
//                    return;
//                }
//
//                final long fromId = messageObject.isOutOwner() ? selfId : messageObject.getDialogId();
//
//                if (selfId == fromId) {
//                    set(AndroidUtilities.replaceTags(formatString(
//                        R.string.GiftSelfTopAction,
//                        LocaleController.formatDate(messageObject.messageOwner.date)
//                    )));
//                } else {
//                    set(AndroidUtilities.replaceTags(formatString(
//                        R.string.GiftTopAction,
//                        DialogObject.getShortName(currentAccount, fromId),
//                        LocaleController.formatDate(messageObject.messageOwner.date)
//                    )));
//                }
//                setVisibility(View.VISIBLE);

            } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique) {
                final TLRPC.TL_messageActionStarGiftUnique action = (TLRPC.TL_messageActionStarGiftUnique) messageObject.messageOwner.action;

                if (action.from_id == null) {
                    setVisibility(View.GONE);
                    return;
                }

                final long fromId = DialogObject.getPeerDialogId(action.from_id);
                final long toId = DialogObject.getPeerDialogId(action.peer);

                if (selfId == fromId) {
                    set(AndroidUtilities.replaceTags(formatString(
                        action.craft || action.gift.crafted ? R.string.GiftSelfTopActionCrafted : R.string.GiftSelfTopAction,
                        LocaleController.formatDate(messageObject.messageOwner.date)
                    )));
                } else if (selfId == toId) {
                    set(AndroidUtilities.replaceTags(formatString(
                        R.string.GiftTopAction,
                        DialogObject.getShortName(currentAccount, fromId),
                        LocaleController.formatDate(messageObject.messageOwner.date)
                    )));
                } else {
                    set(AndroidUtilities.replaceTags(formatString(
                        R.string.GiftTopActionFromTo,
                        DialogObject.getShortName(currentAccount, fromId),
                        DialogObject.getShortName(currentAccount, toId),
                        LocaleController.formatDate(messageObject.messageOwner.date)
                    )));
                }
                setVisibility(View.VISIBLE);

            } else {
                setVisibility(View.GONE);
            }
        }

        public void set(int currentAccount, TL_stars.SavedStarGift gift) {
            if (gift == null || gift.from_id == null || !(gift.gift instanceof TL_stars.TL_starGiftUnique)) {
                setVisibility(View.GONE);
                return;
            }
            setVisibility(View.VISIBLE);

            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            final long fromId = DialogObject.getPeerDialogId(gift.from_id);
            final long toId = DialogObject.getPeerDialogId(gift.gift.owner_id);

            if (selfId == fromId) {
                set(AndroidUtilities.replaceTags(formatString(
                    gift.gift.crafted ? R.string.GiftSelfTopActionCrafted : R.string.GiftSelfTopAction,
                    LocaleController.formatDate(gift.date)
                )));
            } else if (selfId == toId) {
                set(AndroidUtilities.replaceTags(formatString(
                    R.string.GiftTopAction,
                    DialogObject.getShortName(currentAccount, fromId),
                    LocaleController.formatDate(gift.date)
                )));
            } else {
                set(AndroidUtilities.replaceTags(formatString(
                    R.string.GiftTopActionFromTo,
                    DialogObject.getShortName(currentAccount, fromId),
                    DialogObject.getShortName(currentAccount, toId),
                    LocaleController.formatDate(gift.date)
                )));
            }
        }

        public void set(CharSequence text) {
            set(text, getMeasuredWidth());
        }

        private void set(CharSequence text, int fullWidth) {
            if (fullWidth <= 0) {
                textToSet = text;
                return;
            }

            final int width = fullWidth - dp(18);
            layout = new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
            path.rewind();
            path.setPadding(px, py);
            if (fullRect) {
                path.setCurrentLayout(null, 0, 0, 0);
                float top = Float.MIN_VALUE, bottom = Float.MAX_VALUE;
                float left = layout.getWidth(), right = 0;
                for (int i = 0; i < layout.getLineCount(); ++i) {
                    left = Math.min(left, layout.getLineLeft(i));
                    top = Math.min(top, layout.getLineTop(i));
                    right = Math.max(right, layout.getLineRight(i));
                    bottom = Math.max(bottom, layout.getLineBottom(i));
                }
                path.addRect(
                    left,
                    top,
                    right,
                    layout.getHeight(),
                    Path.Direction.CW
                );
            } else {
                path.setCurrentLayout(layout, 0, 0, 0);
                layout.getSelectionPath(0, layout.getText().length(), path);
                path.closeRects();
            }
            invalidate();
        }

        @Override
        public void setTranslationY(float translationY) {
            super.setTranslationY(translationY);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            if (layout != null) {
                canvas.save();
                canvas.translate((getWidth() - layout.getWidth()) / 2.0f, dp(16));
                if (blurMatrix != null) {
                    blurMatrix.reset();
                    blurInvertMatrix.reset();
                    View view = this;
                    while (view != null) {
                        blurInvertMatrix.postConcat(view.getMatrix());
                        view = view.getParent() instanceof View ? ((View) view.getParent()) : null;
                    }
                    blurInvertMatrix.invert(blurMatrix);
                    blurMatrix.preTranslate(-px / 2, -dp(16));
                    blurMatrix.preScale(12, 12);
                    blurBitmapShader.setLocalMatrix(blurMatrix);
                }
                canvas.drawPath(path, bgPaint);
                bgDarkerPaint.setColor(Theme.multAlpha(Color.BLACK, 0.35f));
                canvas.drawPath(path, bgDarkerPaint);
                layout.draw(canvas);
                canvas.restore();
            }
        }
    }

    public static final class RoundRectStrokeDrawable extends Drawable {
        private float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public RoundRectStrokeDrawable(float r, int color) {
            radius = r;
            paint.setColor(color);
        }

        public void setColor(int color) {
            paint.setColor(color);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            AndroidUtilities.rectTmp.set(getBounds());
            canvas.drawRoundRect(AndroidUtilities.rectTmp, radius, radius, paint);
            AndroidUtilities.drawStroke(canvas, AndroidUtilities.rectTmp, radius);
        }

        @Override
        public void setAlpha(int alpha) {

        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {

        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }
    }
}
