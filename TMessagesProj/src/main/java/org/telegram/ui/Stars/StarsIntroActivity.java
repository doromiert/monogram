package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatPluralStringComma;
import static org.telegram.messenger.LocaleController.formatPluralStringSpaced;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Stars.StarsController.findAttribute;
import static org.telegram.ui.Stars.StarsIntroActivity.StarsTransactionView.getPlatformDrawable;
import static org.telegram.messenger.utils.CurrencyFormatter.percents;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.WebFile;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.AvatarSpan;
import org.telegram.ui.Cells.SessionCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ButtonSpan;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.LoadingSpan;
import org.telegram.ui.Components.Premium.GLIcon.GLIconRenderer;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.StarParticlesView;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.StarAppsSheet;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Components.Text;
import org.telegram.ui.Components.TextHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.ImageReceiverSpan;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/** Shared rendering helpers for remaining legacy message representations. */
public final class StarsIntroActivity {

    private StarsIntroActivity() {}

    public static StarParticlesView makeParticlesView(Context context, int particlesCount, int type) {
        return new StarParticlesView(context) {
            Paint[] paints;

            @Override
            protected void configure() {
                drawable = new Drawable(particlesCount);
                drawable.type = 105;
                drawable.roundEffect = false;
                drawable.useRotate = false;
                drawable.useBlur = true;
                drawable.checkBounds = true;
                drawable.isCircle = false;
                drawable.useScale = true;
                drawable.startFromCenter = true;
                if (type == 1) {
                    drawable.centerOffsetY = dp(32 - 8);
                }
                paints = new Paint[20];
                for (int i = 0; i < paints.length; ++i) {
                    paints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paints[i].setColorFilter(new PorterDuffColorFilter(ColorUtils.blendARGB(0xFFFA5416, 0xFFFFC837, i / (float) (paints.length - 1)), PorterDuff.Mode.SRC_IN));
                }
                drawable.getPaint = i -> paints[i % paints.length];
                drawable.size1 = 17;
                drawable.size2 = 18;
                drawable.size3 = 19;
                drawable.colorKey = Theme.key_windowBackgroundWhiteBlackText;
                drawable.init();
            }

            @Override
            protected int getStarsRectWidth() {
                return getMeasuredWidth();
            }

            { setClipWithGradient(); }
        };
    }





    public static class ExpandView extends FrameLayout {

        public final AnimatedTextView textView;
        public final ImageView arrowView;

        public ExpandView(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);

            textView = new AnimatedTextView(context);
            textView.getDrawable().setHacks(true, true, true);
            textView.setTextSize(dp(15));
            addView(textView, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL | Gravity.START, 22, 0, 17 + 24 + 17, 0));

            arrowView = new ImageView(context);
            arrowView.setScaleType(ImageView.ScaleType.CENTER);
            arrowView.setImageResource(R.drawable.arrow_more);
            addView(arrowView, LayoutHelper.createFrameRelatively(24, 24, Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, 17, 0));
        }

        private int lastId;
        private boolean needDivider;
        public void set(UItem item, boolean divider) {
            final boolean animated = lastId == item.id;
            lastId = item.id;
            textView.setText(item.text, animated);
            final int color = Theme.getColor(item.accent ? Theme.key_windowBackgroundWhiteBlueText2 : Theme.key_windowBackgroundWhiteBlackText);
            textView.setTextColor(color);
            arrowView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            if (animated) {
                arrowView.animate().rotation(item.collapsed ? 0 : 180).setDuration(340).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            } else {
                arrowView.setRotation(item.collapsed ? 0 : 180);
            }
            setWillNotDraw(!(needDivider = divider));
        }

        public void set(String text, boolean collapsed, boolean accent, boolean divider) {
            final boolean animated = lastId == -1;
            lastId = -1;
            textView.setText(text, animated);
            final int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2);
            textView.setTextColor(color);
            arrowView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            if (animated) {
                arrowView.animate().rotation(collapsed ? 0 : 180).setDuration(340).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            } else {
                arrowView.setRotation(collapsed ? 0 : 180);
            }
            setWillNotDraw(!(needDivider = divider));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (needDivider) {
                canvas.drawRect(LocaleController.isRTL ? 0 : dp(22), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? dp(22) : 0), getMeasuredHeight(), Theme.dividerPaint);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY)
            );
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            lastId = Integer.MAX_VALUE;
        }

        public static class Factory extends UItem.UItemFactory<ExpandView> {
            static { setup(new Factory()); }

            @Override
            public ExpandView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new ExpandView(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((ExpandView) view).set(item, divider);
            }

            public static UItem asExpand(int id, CharSequence text, boolean collapsed) {
                UItem item = UItem.ofFactory(ExpandView.Factory.class);
                item.id = id;
                item.text = text;
                item.collapsed = collapsed;
                return item;
            }
        }
    }





    public static class StarsTransactionView extends LinearLayout {

        private final int currentAccount;

        private final AvatarDrawable avatarDrawable;
        private final FrameLayout imageViewContainer;
        private final BackupImageView imageView;
        private final BackupImageView imageView2;
        private int imageViewCount = 1;
        private final LinearLayout textLayout;
        private final TextView titleTextView;
        private final LinearLayout.LayoutParams titleTextViewParams;
        private final TextView subtitleTextView;
        private final TextView dateTextView;
        private final LinearLayout.LayoutParams dateTextViewParams;
        private final TextView amountTextView;

        private final SpannableString star;
        private final SpannableString ton;

        private boolean threeLines;

        public StarsTransactionView(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.currentAccount = currentAccount;

            setOrientation(HORIZONTAL);

            imageViewContainer = new FrameLayout(context) {
                private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                    if (imageViewCount > 1) {
                        backgroundPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
                        AndroidUtilities.rectTmp.set(child.getX(), child.getY(), child.getX() + child.getWidth(), child.getY() + child.getHeight());
                        AndroidUtilities.rectTmp.inset(-dp(1.66f), -dp(1.66f));
                        canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(13), dp(13), backgroundPaint);
                    }
                    return super.drawChild(canvas, child, drawingTime);
                }
            };
            addView(imageViewContainer, LayoutHelper.createLinear(72, LayoutHelper.MATCH_PARENT, 0, Gravity.LEFT | Gravity.FILL_VERTICAL));

            imageView2 = new BackupImageView(context);
            imageView2.setRoundRadius(dp(46));
            imageViewContainer.addView(imageView2, LayoutHelper.createFrame(46, 46, Gravity.CENTER_VERTICAL, 13, 0, 13, 0));

            avatarDrawable = new AvatarDrawable();
            imageView = new BackupImageView(context);
            imageView.setRoundRadius(dp(46));
            imageViewContainer.addView(imageView, LayoutHelper.createFrame(46, 46, Gravity.CENTER_VERTICAL, 13, 0, 13, 0));

            textLayout = new LinearLayout(context);
            textLayout.setOrientation(VERTICAL);
            textLayout.setGravity(Gravity.CENTER | Gravity.LEFT);
            addView(textLayout, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, 1, Gravity.FILL));

            titleTextView = new TextView(context);
            titleTextView.setTypeface(AndroidUtilities.bold());
            titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            titleTextView.setSingleLine(true);
            textLayout.addView(titleTextView, titleTextViewParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4.33f));

            subtitleTextView = new LinkSpanDrawable.LinksTextView(context);
            subtitleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleTextView.setSingleLine(true);
            textLayout.addView(subtitleTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, .33f));

            dateTextView = new TextView(context);
            dateTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
            dateTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            dateTextView.setEllipsize(TextUtils.TruncateAt.END);
            dateTextView.setSingleLine(true);
            textLayout.addView(dateTextView, dateTextViewParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            amountTextView = new TextView(context);
            amountTextView.setTypeface(AndroidUtilities.bold());
            amountTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.3f);
            amountTextView.setGravity(Gravity.RIGHT);
            addView(amountTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 8, 0, 20, 0));

            star = new SpannableString("⭐️");
            Drawable drawable = context.getResources().getDrawable(R.drawable.star_small_inner).mutate();
            drawable.setBounds(0, 0, dp(21), dp(21));
            star.setSpan(new ImageSpan(drawable), 0, star.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            ton = new SpannableString("TON");
            drawable = context.getResources().getDrawable(R.drawable.mini_gram_72).mutate();
            ColoredImageSpan span = new ColoredImageSpan(drawable);
            span.recolorDrawable = false;
            span.setSize(dp(18));
            span.setTranslateY(dp(.5f));
            ton.setSpan(span, 0, ton.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        public static HashMap<String, CombinedDrawable> cachedPlatformDrawables;
        public static CombinedDrawable getPlatformDrawable(String platform) {
            return getPlatformDrawable(platform, 44);
        }
        public static CombinedDrawable getPlatformDrawable(String platform, int sz) {
            if (sz != 44) return SessionCell.createDrawable(sz, platform);
            if (cachedPlatformDrawables == null) {
                cachedPlatformDrawables = new HashMap<>();
            }
            CombinedDrawable drawable = cachedPlatformDrawables.get(platform);
            if (drawable == null) {
                cachedPlatformDrawables.put(platform, drawable = SessionCell.createDrawable(44, platform));
            }
            return drawable;
        }

        private Runnable cancelCurrentGift;
        private boolean needDivider;
        public void set(TL_stars.StarsTransaction transaction, boolean bot, boolean divider) {
            long did = DialogObject.getPeerDialogId(transaction.peer.peer);

            final boolean isTon = transaction.amount instanceof TL_stars.TL_starsTonAmount;
            final boolean affiliate_to_bot = (transaction.flags & 131072) != 0;
            final boolean affiliate_to_channel = !affiliate_to_bot && (transaction.flags & 65536) != 0;
            threeLines = did != 0 && !transaction.stargift_upgrade && !transaction.stargift_drop_original_details && !transaction.posts_search || transaction.subscription || transaction.floodskip || transaction.stargift != null && !transaction.stargift_upgrade && !transaction.stargift_drop_original_details || transaction.gift && transaction.peer instanceof TL_stars.TL_starsTransactionPeerFragment;
            titleTextViewParams.bottomMargin = threeLines ? 0 : dp(4.33f);
            subtitleTextView.setVisibility(threeLines ? View.VISIBLE : View.GONE);
            dateTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, threeLines ? 13 : 14);

            dateTextView.setText(LocaleController.formatShortDateTime(transaction.date));
            if (transaction.refund) {
                dateTextView.setText(TextUtils.concat(dateTextView.getText(), " — ", getString(R.string.StarsRefunded)));
            } else if (transaction.failed) {
                dateTextView.setText(TextUtils.concat(dateTextView.getText(), " — ", getString(R.string.StarsFailed)));
            } else if (transaction.pending) {
                dateTextView.setText(TextUtils.concat(dateTextView.getText(), " — ", getString(R.string.StarsPending)));
            }

            if (cancelCurrentGift != null) {
                cancelCurrentGift.run();
                cancelCurrentGift = null;
            }

            imageView.setTranslationX(0);
            imageView.setTranslationY(0);
            imageView2.setVisibility(GONE);
            imageView.setRoundRadius(dp(46));
            if (transaction.stargift_upgrade && transaction.stargift != null) {
                imageView.setImageDrawable(new StarGiftSheet.StarGiftDrawableIcon(imageView, transaction.stargift, 46, .25f));
                titleTextView.setText(getString(R.string.Gift2TransactionUpgraded));
                subtitleTextView.setVisibility(GONE);
            } else if (transaction.stargift_drop_original_details && transaction.stargift != null) {
                imageView.setImageDrawable(new StarGiftSheet.StarGiftDrawableIcon(imageView, transaction.stargift, 46, .25f));
                titleTextView.setText(getString(R.string.Gift2TransactionRemovedDescription));
                subtitleTextView.setVisibility(GONE);
            } else if (transaction.posts_search) {
                imageView.setImageDrawable(getPlatformDrawable("search"));
                titleTextView.setText(getString(R.string.StarsTransactionPostsSearch));
                subtitleTextView.setVisibility(GONE);
            } else if (did != 0) {
                boolean deleted = false;
                String username;
                if (UserObject.isService(did)) {
                    username = getString(R.string.StarsTransactionUnknown);
                    imageView.setImageDrawable(getPlatformDrawable("fragment"));
                } else if (did >= 0) {
                    TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(did);
                    deleted = user == null;
                    avatarDrawable.setInfo(user);
                    imageView.setForUserOrChat(user, avatarDrawable);
                    username = UserObject.getUserName(user);
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                    deleted = chat == null;
                    avatarDrawable.setInfo(chat);
                    imageView.setForUserOrChat(chat, avatarDrawable);
                    username = chat == null ? "" : chat.title;
                }
                if (transaction.stargift != null) {
                    ImageReceiverSpan span = new ImageReceiverSpan(subtitleTextView, currentAccount, 16);
                    span.setRoundRadius(4);
                    span.enableShadow(false);
                    SpannableString spanString = new SpannableString("x");
                    spanString.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    setGiftImage(span.imageReceiver, transaction.stargift, 16);
                    titleTextView.setText(username);
                    if (transaction.offer) {
                        final SpannableStringBuilder sb = new SpannableStringBuilder("x ");
                        sb.setSpan(new AnimatedEmojiSpan(transaction.stargift.getDocument(), subtitleTextView.getPaint().getFontMetricsInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (!transaction.amount.negative()) {
                            sb.append(getString(transaction.refund ? R.string.StarGiftTransactionGiftOfferRefund : R.string.StarGiftTransactionGiftSale));
                        } else {
                            sb.append(getString(transaction.refund ? R.string.StarGiftTransactionGiftSaleRefund : R.string.StarGiftTransactionGiftOffer));
                        }
                        subtitleTextView.setText(sb);
                    } else if (transaction.stargift_resale) {
                        final SpannableStringBuilder sb = new SpannableStringBuilder("x ");
                        sb.setSpan(new AnimatedEmojiSpan(transaction.stargift.getDocument(), subtitleTextView.getPaint().getFontMetricsInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (!transaction.amount.negative()) {
                            sb.append(getString(transaction.refund ? R.string.StarGiftTransactionGiftPurchaseRefund : R.string.StarGiftTransactionGiftSale));
                        } else {
                            sb.append(getString(transaction.refund ? R.string.StarGiftTransactionGiftSaleRefund : R.string.StarGiftTransactionGiftPurchase));
                        }
                        subtitleTextView.setText(sb);
                    } else if (transaction.stargift_prepaid_upgrade) {
                        subtitleTextView.setText(TextUtils.concat(spanString, " ", LocaleController.getString(R.string.Gift2TransactionPrepaidUpgrade)));
                    } else if (transaction.stargift instanceof TL_stars.TL_starGiftUnique) {
                        subtitleTextView.setText(getString(transaction.refund ? R.string.StarGiftTransactionGiftTransferRefund : R.string.StarGiftTransactionGiftTransfer));
                    } else if (transaction.refund) {
                        subtitleTextView.setText(TextUtils.concat(spanString, " ", LocaleController.getString(transaction.stargift_auction_bid ? R.string.Gift2TransactionRefundedAuctionBid : transaction.amount.amount > 0 ? (transaction.stargift_upgrade ? R.string.Gift2TransactionRefundedUpgrade : R.string.Gift2TransactionRefundedSent) : R.string.Gift2TransactionRefundedConverted)));
                    } else {
                        subtitleTextView.setText(TextUtils.concat(spanString, " ", LocaleController.getString(transaction.stargift_auction_bid ? R.string.Gift2TransactionAuctionBid : transaction.amount.amount > 0 ? R.string.Gift2TransactionConverted : (transaction.stargift_upgrade ? R.string.Gift2TransactionUpgraded : R.string.Gift2TransactionSent))));
                    }
                } else if (transaction.subscription) {
                    titleTextView.setText(username);
                    if (transaction.subscription_period == StarsController.PERIOD_MONTHLY) {
                        subtitleTextView.setVisibility(VISIBLE);
                        subtitleTextView.setText(getString(R.string.StarsTransactionSubscriptionMonthly));
                    } else {
                        final String period = transaction.subscription_period == StarsController.PERIOD_5MINUTES ? "5 minutes" : "Minute";
                        subtitleTextView.setVisibility(VISIBLE);
                        subtitleTextView.setText(String.format(Locale.US, "%s subscription fee", period));
                    }
                } else if (transaction.phonegroup_message) {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(LocaleController.getString(transaction.reaction ? R.string.StarsTransactionLiveStoryReactionFee : R.string.StarsTransactionLiveStoryMessageFee));
                } else if (transaction.paid_message) {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(LocaleController.formatPluralStringComma("StarsTransactionMessageFee", transaction.paid_messages));
                } else if (transaction.premium_gift) {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(LocaleController.getString(R.string.StarsTransactionPremiumGift));
                } else if (affiliate_to_channel) {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(LocaleController.formatString(R.string.StarTransactionCommission, percents(transaction.starref_commission_permille)));
                } else if (transaction.gift) {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(LocaleController.getString(R.string.StarsGiftReceived));
                } else if ((transaction.flags & 8192) != 0) {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(LocaleController.getString(R.string.StarsGiveawayPrizeReceived));
                } else if (transaction.reaction) {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(LocaleController.getString(R.string.StarsReactionsSent));
                } else if (!transaction.extended_media.isEmpty()) {
                    if (bot) {
                        titleTextView.setText(username);
                        subtitleTextView.setVisibility(VISIBLE);
                        subtitleTextView.setText(LocaleController.getString(R.string.StarMediaPurchase));
                    } else {
                        titleTextView.setText(LocaleController.getString(R.string.StarMediaPurchase));
                        subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                        subtitleTextView.setText(username);
                    }
                    imageViewCount = 0;
                    for (int i = 0; i < Math.min(2, transaction.extended_media.size()); ++i) {
                        TLRPC.MessageMedia media = transaction.extended_media.get(i);
                        BackupImageView imageView = i == 0 ? this.imageView : this.imageView2;

                        imageView.setRoundRadius(dp(12));
                        ImageLocation location = null;
                        if (media instanceof TLRPC.TL_messageMediaPhoto) {
                            location = ImageLocation.getForPhoto(FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, dp(46), true), media.photo);
                        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
                            location = ImageLocation.getForDocument(FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, dp(46), true), media.document);
                        }
                        imageView.setVisibility(View.VISIBLE);
                        imageView.setImage(location, "46_46", null, null, null, 0);
                        imageViewCount++;
                    }
                    for (int i = 0; i < imageViewCount; ++i) {
                        BackupImageView imageView = i == 0 ? this.imageView : this.imageView2;
                        imageView.setTranslationX(dp(2) + (i - imageViewCount / 2f) * dp(4.33f));
                        imageView.setTranslationY((i - imageViewCount / 2f) * dp(4.33f));
                    }
                } else if (transaction.photo != null) {
                    ImageReceiverSpan span = new ImageReceiverSpan(subtitleTextView, currentAccount, 14);
                    span.setRoundRadius(4);
                    span.enableShadow(false);
                    SpannableString spanString = new SpannableString("x");
                    spanString.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.imageReceiver.setImage(ImageLocation.getForWebFile(WebFile.createWithWebDocument(transaction.photo)), "14_14", null, null, 0, 0);
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(Emoji.replaceEmoji(TextUtils.concat(spanString, " ", transaction.title != null ? transaction.title : ""), subtitleTextView.getPaint().getFontMetricsInt(), false));
                } else {
                    titleTextView.setText(username);
                    subtitleTextView.setVisibility(deleted ? GONE : VISIBLE);
                    subtitleTextView.setText(Emoji.replaceEmoji(transaction.title != null ? transaction.title : "", subtitleTextView.getPaint().getFontMetricsInt(), false));
                }
            } else if (transaction.floodskip) {
                titleTextView.setText(getString(R.string.StarsTransactionFloodskip));
                subtitleTextView.setText(LocaleController.formatPluralStringComma("StarsTransactionFloodskipMessages", transaction.floodskip_number));
                imageView.setImageDrawable(getPlatformDrawable("api"));
            } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerAppStore) {
                titleTextView.setText(getString(R.string.StarsTransactionInApp));
                imageView.setImageDrawable(getPlatformDrawable("ios"));
            } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerPlayMarket) {
                titleTextView.setText(getString(R.string.StarsTransactionInApp));
                imageView.setImageDrawable(getPlatformDrawable("android"));
            } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerFragment) {
                if (transaction.gift) {
                    titleTextView.setText(LocaleController.getString(R.string.StarsGiftReceived));
                    subtitleTextView.setText(getString(isTon ? R.string.StarsTransactionTONFromFragment : R.string.StarsTransactionUnknown));
                    subtitleTextView.setVisibility(VISIBLE);
                } else {
                    titleTextView.setText(getString(bot || (transaction.refund ? transaction.amount.positive() : transaction.amount.negative()) ? R.string.StarsTransactionWithdrawFragment : R.string.StarsTransactionFragment));
                }
                imageView.setImageDrawable(getPlatformDrawable("fragment"));
            } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerPremiumBot) {
                titleTextView.setText(getString(R.string.StarsTransactionBot));
                imageView.setImageDrawable(getPlatformDrawable("premiumbot"));
            } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerUnsupported) {
                titleTextView.setText(getString(R.string.StarsTransactionUnsupported));
                imageView.setImageDrawable(getPlatformDrawable("?"));
            } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerAds) {
                titleTextView.setText(getString(R.string.StarsTransactionAds));
                imageView.setImageDrawable(getPlatformDrawable("ads"));
            } else {
                titleTextView.setText("");
                imageView.setImageDrawable(null);
            }

            if (transaction.amount.amount > 0 || transaction.amount.amount == 0 && transaction.amount.nanos > 0) {
                amountTextView.setVisibility(View.VISIBLE);
                amountTextView.setTextColor(Theme.getColor(Theme.key_color_green));
                amountTextView.setText(TextUtils.concat("+", formatStarsAmount(transaction.amount), " ", isTon ? ton : star));
            } else if (transaction.amount.amount < 0 || transaction.amount.amount == 0 && transaction.amount.nanos < 0) {
                amountTextView.setVisibility(View.VISIBLE);
                amountTextView.setTextColor(Theme.getColor(Theme.key_color_red));
                amountTextView.setText(TextUtils.concat(formatStarsAmount(transaction.amount), " ", isTon ? ton : star));
            } else {
                amountTextView.setVisibility(View.GONE);
            }

            setWillNotDraw(!(needDivider = divider));
        }

        public void setLoading() {

        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (needDivider) {
                canvas.drawRect(LocaleController.isRTL ? 0 : dp(72), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? dp(72) : 0), getMeasuredHeight(), Theme.dividerPaint);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(threeLines ? 71 : 58), MeasureSpec.EXACTLY)
            );
        }

        public static class Factory extends UItem.UItemFactory<StarsTransactionView> {
            static { setup(new Factory()); }

            @Override
            public StarsTransactionView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                StarsTransactionView cached = getCached();
                if (cached != null) {
                    return cached;
                }
                return new StarsTransactionView(context, currentAccount, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                StarsTransactionView transactionView = (StarsTransactionView) view;
                transactionView.set((TL_stars.StarsTransaction) item.object, item.accent, divider);
            }

            public static UItem asTransaction(TL_stars.StarsTransaction transaction, boolean bot) {
                UItem item = UItem.ofFactory(StarsTransactionView.Factory.class);
                item.object = transaction;
                item.accent = bot;
                return item;
            }

            public static UItem asLoading() {
                UItem item = UItem.ofFactory(StarsTransactionView.Factory.class);
                item.accent = true;
                return item;
            }
        }
    }













    public static SpannableStringBuilder replaceStars(TL_stars.StarsAmount amount, CharSequence cs) {
        return replaceStars(amount instanceof TL_stars.TL_starsTonAmount, cs, 1.13f);
    }

    public static SpannableStringBuilder replaceStars(boolean ton, CharSequence cs) {
        return replaceStars(ton, cs, 1.13f);
    }

    public static SpannableStringBuilder replaceStars(CharSequence cs) {
        return replaceStars(cs, 1.13f);
    }

    public static SpannableStringBuilder replaceStars(boolean ton, CharSequence cs, final float scale) {
        return replaceStars(ton, cs, scale, null);
    }

    public static SpannableStringBuilder replaceStars(TL_stars.StarsAmount amount, CharSequence cs, final float scale) {
        return replaceStars(amount instanceof TL_stars.TL_starsTonAmount, cs, scale, null);
    }

    public static SpannableStringBuilder replaceStars(CharSequence cs, final float scale) {
        return replaceStars(cs, scale, null);
    }

    public static SpannableStringBuilder replaceStars(boolean ton, CharSequence cs, final float scale, ColoredImageSpan[] cache) {
        return replaceStars(ton, cs, scale, cache, 0, 0, 1.0f);
    }

    public static SpannableStringBuilder replaceStars(TL_stars.StarsAmount amount, CharSequence cs, final float scale, ColoredImageSpan[] cache) {
        return replaceStars(amount instanceof TL_stars.TL_starsTonAmount, cs, scale, cache, 0, 0, 1.0f);
    }

    public static SpannableStringBuilder replaceStars(CharSequence cs, final float scale, ColoredImageSpan[] cache) {
        return replaceStars(cs, scale, cache, 0, 0, 1.0f);
    }

    public static SpannableStringBuilder replaceStars(CharSequence cs, final float scale, ColoredImageSpan[] cache, final float tx, final float ty, final float sx) {
        return replaceStars(false, cs, scale, cache, tx, ty, sx);
    }

    public static SpannableStringBuilder replaceStars(TL_stars.StarsAmount amount, CharSequence cs, final float scale, ColoredImageSpan[] cache, final float tx, final float ty, final float sx) {
        return replaceStars(amount instanceof TL_stars.TL_starsTonAmount, cs, scale, cache, tx, ty, sx);
    }

    public static SpannableStringBuilder replaceStars(boolean ton, CharSequence cs, final float scale, ColoredImageSpan[] cache, final float tx, final float ty, final float sx) {
        if (cs == null) return null;
        SpannableStringBuilder ssb;
        if (!(cs instanceof SpannableStringBuilder)) {
            ssb = new SpannableStringBuilder(cs);
        } else {
            ssb = (SpannableStringBuilder) cs;
        }
        final String symbol = ton ? "TON" : "⭐";
        SpannableString spacedStar = new SpannableString(symbol + " ");
        ColoredImageSpan span;
        if (cache != null && cache[0] != null) {
            span = cache[0];
        } else {
            span = new ColoredImageSpan(ton ? R.drawable.mini_gram_72 : R.drawable.msg_premium_liststar);
            if (cache != null) {
                cache[0] = span;
            }
        }
        span.translate(tx, ty);
        span.spaceScaleX = sx;
        if (ton) {
            span.recolorDrawable = false;
            span.setScale(scale * 0.2f, scale * 0.2f);
        } else {
            span.setScale(scale, scale);
        }
        spacedStar.setSpan(span, 0, spacedStar.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        AndroidUtilities.replaceMultipleCharSequence("⭐️", ssb, "⭐");
        AndroidUtilities.replaceMultipleCharSequence("⭐ ", ssb, "⭐");
        AndroidUtilities.replaceMultipleCharSequence("⭐", ssb, spacedStar);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency + " ", ssb, StarsController.currency);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency, ssb, spacedStar);
        return ssb;
    }

    public static SpannableStringBuilder replaceDiamond(CharSequence cs) {
        return replaceDiamond(cs, 0.9f, null, 0, 0, 1.0f);
    }

    public static SpannableStringBuilder replaceDiamond(CharSequence cs, final float scale) {
        return replaceDiamond(cs, scale, null, 0, 0, 1.0f);
    }

    public static SpannableStringBuilder replaceDiamond(CharSequence cs, final float scale, ColoredImageSpan[] cache, final float tx, final float ty, float sx) {
        if (cs == null) return null;
        SpannableStringBuilder ssb;
        if (!(cs instanceof SpannableStringBuilder)) {
            ssb = new SpannableStringBuilder(cs);
        } else {
            ssb = (SpannableStringBuilder) cs;
        }
        SpannableString spacedStar = new SpannableString("💎 ");
        ColoredImageSpan span;
        if (cache != null && cache[0] != null) {
            span = cache[0];
        } else {
            span = new ColoredImageSpan(R.drawable.diamond);
            if (cache != null) {
                cache[0] = span;
            }
        }
        span.recolorDrawable = false;
        span.translate(tx, ty);
        span.spaceScaleX = sx;
        span.setScale(scale, scale);
        spacedStar.setSpan(span, 0, spacedStar.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        AndroidUtilities.replaceMultipleCharSequence("💎️", ssb, "💎");
        AndroidUtilities.replaceMultipleCharSequence("💎 ", ssb, "💎");
        AndroidUtilities.replaceMultipleCharSequence("💎", ssb, spacedStar);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency + " ", ssb, StarsController.currency);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency, ssb, spacedStar);
        return ssb;
    }

    public static SpannableStringBuilder replaceStars(CharSequence cs, ColoredImageSpan[] spanRef) {
        return replaceStars(false, cs, spanRef);
    }

    public static SpannableStringBuilder replaceStars(boolean ton, CharSequence cs, ColoredImageSpan[] spanRef) {
        if (cs == null) return null;
        SpannableStringBuilder ssb;
        if (!(cs instanceof SpannableStringBuilder)) {
            ssb = new SpannableStringBuilder(cs);
        } else {
            ssb = (SpannableStringBuilder) cs;
        }
        ColoredImageSpan span;
        if (spanRef != null && spanRef[0] != null) {
            span = spanRef[0];
        } else {
            span = new ColoredImageSpan(ton ? R.drawable.mini_gram_72 : R.drawable.msg_premium_liststar);
            span.setScale(ton ? 0.222f : 1.13f, ton ? 0.222f : 1.13f);
        }
        if (ton) {
            span.recolorDrawable = false;
        }
        if (spanRef != null) {
            spanRef[0] = span;
        }
        SpannableString spacedStar = new SpannableString("⭐ ");
        spacedStar.setSpan(span, 0, spacedStar.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        AndroidUtilities.replaceMultipleCharSequence("⭐️", ssb, "⭐");
        AndroidUtilities.replaceMultipleCharSequence("⭐ ", ssb, "⭐");
        AndroidUtilities.replaceMultipleCharSequence("⭐", ssb, spacedStar);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency + " ", ssb, StarsController.currency);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency, ssb, spacedStar);
        return ssb;
    }
    public static SpannableStringBuilder replaceStarsWithPlain(CharSequence cs, float scale) {
        return replaceStarsWithPlain(cs, scale, null);
    }
    public static SpannableStringBuilder replaceStarsWithPlain(boolean ton, CharSequence cs, float scale) {
        return replaceStarsWithPlain(ton, cs, scale, null);
    }
    public static SpannableStringBuilder replaceStarsWithPlain(TL_stars.StarsAmount amount, CharSequence cs, float scale) {
        return replaceStarsWithPlain(amount instanceof TL_stars.TL_starsTonAmount, cs, scale, null);
    }
    public static SpannableStringBuilder replaceStarsWithPlain(CharSequence cs, float scale, ColoredImageSpan[] spanArr) {
        return replaceStarsWithPlain(false, cs, scale, spanArr);
    }
    public static SpannableStringBuilder replaceStarsWithPlain(TL_stars.StarsAmount amount, CharSequence cs, float scale, ColoredImageSpan[] spanArr) {
        return replaceStarsWithPlain(amount instanceof TL_stars.TL_starsTonAmount, cs, scale, spanArr);
    }
    public static SpannableStringBuilder replaceStarsWithPlain(boolean ton, CharSequence cs, float scale, ColoredImageSpan[] spanArr) {
        if (cs == null) return null;
        SpannableStringBuilder ssb;
        if (!(cs instanceof SpannableStringBuilder)) {
            ssb = new SpannableStringBuilder(cs);
        } else {
            ssb = (SpannableStringBuilder) cs;
        }
        final String symbol = ton ? "TON" : "⭐";
        final int resId = ton ? R.drawable.mini_gram_72 : R.drawable.star_small_inner;
        SpannableString spacedStar = new SpannableString(symbol + " ");
        ColoredImageSpan span;
        if (spanArr != null && spanArr[0] != null) {
            span = spanArr[0];
        } else if (spanArr != null && spanArr.length > 0) {
            span = spanArr[0] = new ColoredImageSpan(resId);
        } else {
            span = new ColoredImageSpan(resId);
        }
        span.recolorDrawable = false;
        if (ton) {
            scale *= .33f;
        }
        span.setScale(scale, scale);
        spacedStar.setSpan(span, 0, spacedStar.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        AndroidUtilities.replaceMultipleCharSequence("⭐️", ssb, "⭐");
        AndroidUtilities.replaceMultipleCharSequence("⭐ ", ssb, "⭐");
        AndroidUtilities.replaceMultipleCharSequence("⭐", ssb, spacedStar);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency + " ", ssb, StarsController.currency);
        AndroidUtilities.replaceMultipleCharSequence(StarsController.currency, ssb, spacedStar);
        return ssb;
    }

    public static CharSequence getTransactionTitle(int currentAccount, boolean bot, TL_stars.StarsTransaction t) {
        if (t.stargift_drop_original_details) {
            return getString(R.string.StarsTransactionRemovedDescription);
        }
        if (t.posts_search) {
            return LocaleController.getString(R.string.StarsTransactionPostsSearch);
        }
        if (t.premium_gift) {
            return LocaleController.getString(R.string.StarsTransactionPremiumGift);
        }
        if (t.phonegroup_message) {
            return getString(t.reaction ? R.string.StarsTransactionLiveStoryReactionFee : R.string.StarsTransactionLiveStoryMessageFee);
        }
        if (t.paid_message) {
            return LocaleController.formatPluralStringComma("StarsTransactionMessageFee", t.paid_messages);
        }
        if (t.floodskip) {
            return LocaleController.getString(R.string.StarsTransactionFloodskip);
        }
        if (!t.extended_media.isEmpty()) {
            return getString(R.string.StarMediaPurchase);
        }
        final boolean ton = t.amount instanceof TL_stars.TL_starsTonAmount;
        final boolean affiliate_to_bot = (t.flags & 131072) != 0;
        final boolean affiliate_to_channel = !affiliate_to_bot && (t.flags & 65536) != 0;
        if (affiliate_to_channel) {
            return LocaleController.formatString(R.string.StarTransactionCommission, percents(t.starref_commission_permille));
        }
        if (t.stargift != null) {
            if (t.stargift_prepaid_upgrade) {
                return getString(R.string.Gift2TransactionPrepaidUpgrade);
            } else if (t.refund) {
                return LocaleController.getString(t.stargift_auction_bid ? R.string.Gift2TransactionRefundedAuctionBid : t.amount.amount > 0 ? (t.stargift_upgrade ? R.string.Gift2TransactionRefundedUpgrade : R.string.Gift2TransactionRefundedSent) : R.string.Gift2TransactionRefundedConverted);
            } else {
                return LocaleController.getString(t.stargift_auction_bid ? R.string.Gift2TransactionAuctionBid : t.amount.amount > 0 ? R.string.Gift2TransactionConverted : (t.stargift_upgrade ? R.string.Gift2TransactionUpgraded : R.string.Gift2TransactionSent));
            }
        }
        if (t.subscription) {
            if (t.subscription_period == StarsController.PERIOD_MONTHLY) {
                return getString(R.string.StarSubscriptionPurchase);
            }
            if (t.subscription_period == StarsController.PERIOD_5MINUTES) {
                return "5-minute subscription fee";
            }
            if (t.subscription_period == StarsController.PERIOD_MINUTE) {
                return "Minute subscription fee";
            }
        }
        if ((t.flags & 8192) != 0) {
            return getString(R.string.StarsGiveawayPrizeReceived);
        }
        if (t.gift) {
            if (t.sent_by != null) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(DialogObject.getPeerDialogId(t.sent_by));
                return getString(UserObject.isUserSelf(user) ? R.string.StarsGiftSent : R.string.StarsGiftReceived);
            }
            return getString(R.string.StarsGiftReceived);
        }
        if (t.title != null) {
            return t.title;
        }
        final long did = DialogObject.getPeerDialogId(t.peer.peer);
        if (did != 0) {
            if (did >= 0) {
                return UserObject.getUserName(MessagesController.getInstance(UserConfig.selectedAccount).getUser(did));
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-did);
                return chat == null ? "" : chat.title;
            }
        } else if (t.peer instanceof TL_stars.TL_starsTransactionPeerFragment) {
            return getString(bot || (t.refund ? t.amount.positive() : t.amount.negative()) ? R.string.StarsTransactionWithdrawFragment : R.string.StarsTransactionFragment);
        } else if (t.peer instanceof TL_stars.TL_starsTransactionPeerPremiumBot) {
            return getString(R.string.StarsTransactionBot);
        } else if (t.peer instanceof TL_stars.TL_starsTransactionPeerAds) {
            return getString(R.string.StarsTransactionAds);
        } else {
            return getString(R.string.StarsTransactionUnsupported);
        }
    }

    public static BottomSheet showTransactionSheet(Context context, int currentAccount, int date, TLRPC.Peer sent_by, TLRPC.Peer received_by, TLRPC.TL_messageActionPrizeStars action, Theme.ResourcesProvider resourcesProvider) {
        TL_stars.StarsTransaction t = new TL_stars.StarsTransaction();
        t.title = null;
        t.description = null;
        t.photo = null;
        t.peer = new TL_stars.TL_starsTransactionPeer();
        t.peer.peer = action.boost_peer;
        t.date = date;
        t.amount = TL_stars.StarsAmount.ofStars(action.stars);
        t.id = action.transaction_id;
        t.gift = true;
        t.flags |= 8192;
        t.giveaway_post_id = action.giveaway_msg_id;
        t.sent_by = sent_by;
        t.received_by = received_by;
        return showTransactionSheet(context, false, 0, currentAccount, t, resourcesProvider);
    }

    public static BottomSheet showTransactionSheet(Context context, int currentAccount, int date, TLRPC.Peer sent_by, TLRPC.Peer received_by, TLRPC.TL_messageActionGiftStars action, Theme.ResourcesProvider resourcesProvider) {
        TL_stars.StarsTransaction t = new TL_stars.StarsTransaction();
        t.title = null;
        t.description = null;
        t.photo = null;
        t.peer = new TL_stars.TL_starsTransactionPeer();
        t.peer.peer = sent_by;
        t.date = date;
        t.amount = TL_stars.StarsAmount.ofStars(action.stars);
        t.id = action.transaction_id;
        t.gift = true;
        t.sent_by = sent_by;
        t.received_by = received_by;
        return showTransactionSheet(context, false, 0, currentAccount, t, resourcesProvider);
    }

    public static BottomSheet showTransactionSheet(Context context, int currentAccount, int date, TLRPC.Peer sent_by, TLRPC.Peer received_by, TLRPC.TL_messageActionGiftTon action, Theme.ResourcesProvider resourcesProvider) {
        TL_stars.StarsTransaction t = new TL_stars.StarsTransaction();
        t.title = null;
        t.description = null;
        t.photo = null;
        t.peer = new TL_stars.TL_starsTransactionPeer();
        t.peer.peer = sent_by;
        t.date = date;
        t.amount = new TL_stars.TL_starsTonAmount();
        t.amount.amount = action.cryptoAmount;
        t.id = action.transaction_id;
        t.gift = true;
        t.sent_by = sent_by;
        t.received_by = received_by;
        return showTransactionSheet(context, false, 0, currentAccount, t, resourcesProvider);
    }

    public static BottomSheet showTransactionSheet(Context context, int currentAccount, int date, TLRPC.TL_messageActionPaymentRefunded action, Theme.ResourcesProvider resourcesProvider) {
        TL_stars.StarsTransaction t = new TL_stars.StarsTransaction();
        t.title = null;
        t.description = null;
        t.photo = null;
        t.peer = new TL_stars.TL_starsTransactionPeer();
        t.peer.peer = action.peer;
        t.date = date;
        t.amount = TL_stars.StarsAmount.ofStars(action.total_amount);
        t.id = action.charge.id;
        t.refund = true;
        return showTransactionSheet(context, false, 0, currentAccount, t, resourcesProvider);
    }

    public static BottomSheet showTransactionSheet(Context context, boolean bot, int currentAccount, TLRPC.TL_payments_paymentReceiptStars receipt, Theme.ResourcesProvider resourcesProvider) {
        TL_stars.StarsTransaction t = new TL_stars.StarsTransaction();
        t.title = receipt.title;
        t.description = receipt.description;
        t.photo = receipt.photo;
        t.peer = new TL_stars.TL_starsTransactionPeer();
        t.peer.peer = MessagesController.getInstance(currentAccount).getPeer(receipt.bot_id);
        t.date = receipt.date;
        t.amount = TL_stars.StarsAmount.ofStars(-receipt.total_amount);
        t.id = receipt.transaction_id;
        return showTransactionSheet(context, bot, 0, currentAccount, t, resourcesProvider);
    }

    public static String getGiftStarsEmoji(long stars) {
        if (stars <= 1_000) {
            return "2⃣";
        } else if (stars < 2_500) {
            return "3⃣";
        } else {
            return "4⃣";
        }
    }

    public static Runnable setGiftImage(View view, ImageReceiver imageReceiver, long stars) {
        return setGiftImage(view, imageReceiver, getGiftStarsEmoji(stars));
    }

    public static Runnable setTonGiftImage(View view, ImageReceiver imageReceiver, long nanotons) {
        return setGiftImage(view, imageReceiver, getTonGiftEmoji(nanotons), true);
    }

    public static String getPremiumGiftMonthsEmoji(int months) {
        switch (months) {
            case 1: return 1 + "\u20E3";
            case 3: return 2 + "\u20E3";
            case 6: return 3 + "\u20E3";
            case 12: return 4 + "\u20E3";
            case 24: return 5 + "\u20E3";
            default: return 1 + "\u20E3";
        }
    }

    public static String getTonGiftEmoji(long nanotons) {
        if (nanotons <= 10_000_000_000L) {
            return "2⃣";
        } else if (nanotons <= 50_000_000_000L) {
            return "1⃣";
        } else {
            return "3⃣";
        }
    }

    public static Runnable setPremiumGiftImage(View view, ImageReceiver imageReceiver, int months) {
        return setGiftImage(view, imageReceiver, getPremiumGiftMonthsEmoji(months));
    }

    public static Runnable setGiftImage(View view, ImageReceiver imageReceiver, String emoji) {
        return setGiftImage(view, imageReceiver, emoji, false);
    }

    public static Runnable setGiftImage(View view, ImageReceiver imageReceiver, String emoji, boolean isTon) {
        final boolean[] played = new boolean[1];
        final int currentAccount = imageReceiver.getCurrentAccount();
        Runnable setImage = () -> {
            TLRPC.TL_messages_stickerSet set;
            TLRPC.Document document = null;
            String packName;
            if (isTon) {
                packName = UserConfig.getInstance(currentAccount).premiumTonStickerPack;
                if (packName == null) {
                    MediaDataController.getInstance(currentAccount).checkTonGiftStickers();
                    return;
                }
            } else {
                packName = UserConfig.getInstance(currentAccount).premiumGiftsStickerPack;
                if (packName == null) {
                    MediaDataController.getInstance(currentAccount).checkPremiumGiftStickers();
                    return;
                }
            }

            set = MediaDataController.getInstance(currentAccount).getStickerSetByName(packName);
            if (set == null) {
                set = MediaDataController.getInstance(currentAccount).getStickerSetByEmojiOrName(packName);
            }
            if (set != null) {
                for (int i = 0; i < set.packs.size(); ++i) {
                    TLRPC.TL_stickerPack pack = set.packs.get(i);
                    if (TextUtils.equals(pack.emoticon, emoji) && !pack.documents.isEmpty()) {
                        long documentId = pack.documents.get(0);
                        for (int j = 0; j < set.documents.size(); ++j) {
                            TLRPC.Document d = set.documents.get(j);
                            if (d != null && d.id == documentId) {
                                document = d;
                                break;
                            }
                        }
                        break;
                    }
                }
                if (document == null && !set.documents.isEmpty()) {
                    document = set.documents.get(0);
                }
            }

            if (document != null) {
                imageReceiver.setAllowStartLottieAnimation(true);
                imageReceiver.setDelegate(new ImageReceiver.ImageReceiverDelegate() {
                    @Override
                    public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                        if (set) {
                            RLottieDrawable drawable = imageReceiver.getLottieAnimation();
                            if (drawable != null && !played[0]) {
                                drawable.setCurrentFrame(0, false);
                                AndroidUtilities.runOnUIThread(drawable::restart);
                                played[0] = true;
                            }
                        }
                    }
                });
                SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(document, Theme.key_windowBackgroundGray, 0.3f);
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 160, true, null, true);
                imageReceiver.setAutoRepeat(0);
                imageReceiver.setImage(
                    ImageLocation.getForDocument(document), "160_160_nr",
                    ImageLocation.getForDocument(thumb, document), "160_160",
                    svgThumb,
                    document.size, "tgs",
                    set,
                    1
                );
            } else {
                MediaDataController.getInstance(currentAccount).loadStickersByEmojiOrName(packName, false, set == null);
            }
        };
        setImage.run();
        final Runnable cancel1 = NotificationCenter.getInstance(currentAccount).listen(view, isTon ? NotificationCenter.didUpdateTonGiftStickers : NotificationCenter.didUpdatePremiumGiftStickers, args -> setImage.run());
        final Runnable cancel2 = NotificationCenter.getInstance(currentAccount).listen(view, NotificationCenter.diceStickersDidLoad, args -> setImage.run());
        return () -> {
            cancel1.run();
            cancel2.run();
        };
    }

    public static BottomSheet showTransactionSheet(Context context, boolean bot, long dialogId, int currentAccount, TL_stars.StarsTransaction transaction, Theme.ResourcesProvider resourcesProvider) {
        if (transaction == null || context == null)
            return null;

        final boolean ton = transaction.amount instanceof TL_stars.TL_starsTonAmount;
        final boolean giveaway = (transaction.flags & 8192) != 0;
        final boolean affiliate_to_bot = (transaction.flags & 131072) != 0 && !transaction.paid_message;
        final boolean affiliate_to_channel = !affiliate_to_bot && (transaction.flags & 65536) != 0 && !transaction.paid_message;

        final boolean positive = transaction.amount.positive();
        final boolean negative = transaction.amount.negative();

        BottomSheet.Builder b = new BottomSheet.Builder(context, false, resourcesProvider);
        BottomSheet[] sheet = new BottomSheet[1];

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(0, dp(giveaway || transaction.gift || transaction.stargift_resale && transaction.stargift instanceof TL_stars.TL_starGiftUnique ? 0 : 20), 0, dp(8));
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);

        if (transaction.stargift_resale && transaction.stargift instanceof TL_stars.TL_starGiftUnique) {
            final TL_stars.TL_starGiftUnique gift = (TL_stars.TL_starGiftUnique) transaction.stargift;

            final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
            final TL_stars.starGiftAttributePattern pattern = findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class);

            final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable symbolDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(null, dp(20));
            final RadialGradient gradient = new RadialGradient(0, 0, dp(200), new int[] { backdrop.center_color | 0xFF000000, backdrop.edge_color | 0xFF000000 }, new float[] { 0, 1 }, Shader.TileMode.CLAMP);
            final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Matrix backgroundMatrix = new Matrix();
            backgroundPaint.setShader(gradient);
            LinearLayout topView = new LinearLayout(context) {
                private final Path clipPath = new Path();
                @Override
                protected void dispatchDraw(@NonNull Canvas canvas) {
                    final float r = dp(10);
                    AndroidUtilities.rectTmp.set(0, dp(2) + 1, getWidth(), getHeight() + r);
                    clipPath.rewind();
                    clipPath.addRoundRect(AndroidUtilities.rectTmp, r, r, Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(clipPath);
                    backgroundMatrix.reset();
                    backgroundMatrix.postTranslate(getWidth() / 2f, dp(100));
                    gradient.setLocalMatrix(backgroundMatrix);
                    canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);
                    canvas.save();
                    canvas.translate(getWidth() / 2f, dp(100));
                    StarGiftPatterns.drawPattern(canvas, symbolDrawable, getWidth(), dp(20+160), 1.0f, 1.0f);
                    canvas.restore();
                    super.dispatchDraw(canvas);
                    canvas.restore();
                }

                @Override
                protected void onAttachedToWindow() {
                    super.onAttachedToWindow();
                    symbolDrawable.attach();
                }

                @Override
                protected void onDetachedFromWindow() {
                    super.onDetachedFromWindow();
                    symbolDrawable.detach();
                }
            };
            symbolDrawable.setParentView(topView);
            symbolDrawable.set(pattern.document, false);
            topView.setOrientation(LinearLayout.VERTICAL);

            BackupImageView imageView = new BackupImageView(context);
            setGiftImage(imageView.getImageReceiver(), transaction.stargift, 160);
            topView.addView(imageView, LayoutHelper.createLinear(160, 160, Gravity.CENTER, 0, 20, 0, 0));
            if (!TextUtils.isEmpty(gift.slug)) {
                ScaleStateListAnimator.apply(imageView);
                imageView.setOnClickListener(v -> {
                    Browser.openUrl(context, "https://" + MessagesController.getInstance(currentAccount).linkPrefix + "/nft/" + gift.slug);
                });
            }

            TextView textView = TextHelper.makeTextView(context, 20, 0, true);
            textView.setTextColor(0xFFFFFFFF);
            textView.setText(gift.title);
            topView.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 1, 0, 0));

            textView = TextHelper.makeTextView(context, 13, 0, false);
            textView.setTextColor(backdrop.text_color | 0xFF000000);
            textView.setText(LocaleController.formatPluralStringComma("Gift2CollectionNumber", gift.num));
            topView.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 5, 0, 0));

            textView = TextHelper.makeTextView(context, 18, 0, true);
            textView.setTextColor(0xFFFFFFFF);
            textView.setText(replaceStars(transaction.amount, TextUtils.concat((positive ? "+" : ""), formatStarsAmount(transaction.amount), " ⭐️"), 1.25f));
            SpannableStringBuilder s = new SpannableStringBuilder(textView.getText());
            if (transaction.refund) {
                appendStatus(s, textView, getString(R.string.StarsRefunded));
            } else if (transaction.failed) {
//                textView.setTextColor(Theme.getColor(Theme.key_color_red, resourcesProvider));
                appendStatus(s, textView, getString(R.string.StarsFailed));
            } else if (transaction.pending) {
//                textView.setTextColor(Theme.getColor(Theme.key_color_yellow, resourcesProvider));
                appendStatus(s, textView, getString(R.string.StarsPending));
            }
            textView.setText(s);
            topView.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 11, 0, 17));

            linearLayout.addView(topView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            BackupImageView imageView = new BackupImageView(context);
            if (transaction.premium_gift) {
                setPremiumGiftImage(imageView, imageView.getImageReceiver(), transaction.premium_gift_months);
                linearLayout.addView(imageView, LayoutHelper.createLinear(160, 160, Gravity.CENTER, 0, -8, 0, 10));
            } else if (transaction.posts_search) {
                CombinedDrawable drawable = (CombinedDrawable) SessionCell.createDrawable(100, "search");
                drawable.setIconSize(dp(40), dp(40));
                imageView.setImageDrawable(drawable);
                // linearLayout.addView(imageView, LayoutHelper.createLinear(100, 100, Gravity.CENTER, 0, 0, 0, 10));
            } else if (transaction.stargift != null) {
                if (transaction.stargift instanceof TL_stars.TL_starGiftUnique) {
                    imageView.setImageDrawable(new StarGiftSheet.StarGiftDrawableIcon(imageView, transaction.stargift, 94, .44f));
                    linearLayout.addView(imageView, LayoutHelper.createLinear(94, 94, Gravity.CENTER, 0, 2, 0, 10));
                } else {
                    setGiftImage(imageView.getImageReceiver(), transaction.stargift, 160);
                    linearLayout.addView(imageView, LayoutHelper.createLinear(160, 160, Gravity.CENTER, 0, -8, 0, 10));
                }
            } else if (giveaway || transaction.gift) {
                if (transaction.amount instanceof TL_stars.TL_starsTonAmount) {
                    setTonGiftImage(imageView, imageView.getImageReceiver(), transaction.amount.amount);
                } else {
                    setGiftImage(imageView, imageView.getImageReceiver(), transaction.amount.amount);
                }

                linearLayout.addView(imageView, LayoutHelper.createLinear(160, 160, Gravity.CENTER, 0, -8, 0, 10));
            } else if (!transaction.extended_media.isEmpty()) {
                imageView.setRoundRadius(dp(30));
                TLRPC.MessageMedia media = transaction.extended_media.get(0);
                ImageLocation location = null;
                if (media instanceof TLRPC.TL_messageMediaPhoto) {
                    location = ImageLocation.getForPhoto(FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, dp(100), true), media.photo);
                } else if (media instanceof TLRPC.TL_messageMediaDocument) {
                    location = ImageLocation.getForDocument(FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, dp(100), true), media.document);
                }
                imageView.setImage(location, "100_100", null, null, null, 0);
                linearLayout.addView(imageView, LayoutHelper.createLinear(100, 100, Gravity.CENTER, 0, 0, 0, 10));

                imageView.setOnClickListener(v -> {
                    final long did = bot ? dialogId : DialogObject.getPeerDialogId(transaction.peer.peer);
                    ArrayList<MessageObject> messages = new ArrayList<>();
                    for (int i = 0; i < transaction.extended_media.size(); ++i) {
                        TLRPC.MessageMedia emedia = transaction.extended_media.get(i);
                        TLRPC.TL_message msg = new TLRPC.TL_message();
                        msg.id = transaction.msg_id;
                        msg.dialog_id = did;
                        msg.from_id = new TLRPC.TL_peerChannel();
                        msg.from_id.channel_id = -did;
                        msg.peer_id = new TLRPC.TL_peerChannel();
                        msg.peer_id.channel_id = -did;
                        msg.date = transaction.date;
                        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
                        msg.media = emedia;
                        msg.noforwards = true;
                        MessageObject msgObj = new MessageObject(currentAccount, msg, false, false);
                        messages.add(msgObj);
                    }
                    if (messages.isEmpty()) return;

                    PhotoViewer.getInstance().setParentActivity(LaunchActivity.getLastFragment(), resourcesProvider);
                    PhotoViewer.getInstance().openPhoto(messages, 0, did, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider() {

                        @Override
                        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
                            final ImageReceiver imageReceiver = imageView.getImageReceiver();
                            int[] coords = new int[2];
                            imageView.getLocationInWindow(coords);
                            PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
                            object.viewX = coords[0];
                            object.viewY = coords[1] - (Build.VERSION.SDK_INT >= 21 ? 0 : AndroidUtilities.statusBarHeight);
                            object.parentView = linearLayout;
                            object.animatingImageView = null;
                            object.imageReceiver = imageReceiver;
                            if (needPreview) {
                                object.thumb = imageReceiver.getBitmapSafe();
                            }
                            object.radius = imageReceiver.getRoundRadius(true);
                            object.dialogId = did;
                            object.clipTopAddition = 0; // (int) (chatListViewPaddingTop - chatListViewPaddingVisibleOffset - AndroidUtilities.dp(4));
                            object.clipBottomAddition = 0; // blurredViewBottomOffset;
                            return object;
                        }

                        @Override
                        public boolean forceAllInGroup() {
                            return true;
                        }

                        @Override
                        public boolean validateGroupId(long groupId) {
                            return false;
                        }
                    });
                });
            } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeer) {
                if (transaction.photo != null) {
                    imageView.setRoundRadius(dp(50));
                    imageView.setImage(ImageLocation.getForWebFile(WebFile.createWithWebDocument(transaction.photo)), "100_100", null, 0, null);
                } else {
                    imageView.setRoundRadius(dp(50));
                    final long did = affiliate_to_channel ? DialogObject.getPeerDialogId(transaction.starref_peer) : transaction.subscription && bot ? dialogId : DialogObject.getPeerDialogId(transaction.peer.peer);
                    AvatarDrawable avatarDrawable = new AvatarDrawable();
                    if (did >= 0) {
                        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(did);
                        avatarDrawable.setInfo(user);
                        imageView.setForUserOrChat(user, avatarDrawable);
                    } else {
                        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                        avatarDrawable.setInfo(chat);
                        imageView.setForUserOrChat(chat, avatarDrawable);
                    }
                }
                linearLayout.addView(imageView, LayoutHelper.createLinear(100, 100, Gravity.CENTER, 0, 0, 0, 10));
            } else {
                String platform = "?";
                if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerAppStore) {
                    platform = "ios";
                } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerPlayMarket) {
                    platform = "android";
                } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerPremiumBot) {
                    platform = "premiumbot";
                } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerFragment) {
                    platform = "fragment";
                } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerAds) {
                    platform = "ads";
                } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerAPI) {
                    platform = "api";
                }
                CombinedDrawable drawable = (CombinedDrawable) SessionCell.createDrawable(100, platform);
                drawable.setIconSize(dp(40), dp(40));
                imageView.setImageDrawable(drawable);
                // linearLayout.addView(imageView, LayoutHelper.createLinear(100, 100, Gravity.CENTER, 0, 0, 0, 10));
            }

            TextView textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setGravity(Gravity.CENTER);
            textView.setText(getTransactionTitle(currentAccount, bot, transaction));
            linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 36, 0, 36, 4));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setGravity(Gravity.CENTER);
            textView.setTextColor(Theme.getColor(positive ? Theme.key_color_green : Theme.key_color_red, resourcesProvider));
            textView.setText(replaceStarsWithPlain(transaction.amount, TextUtils.concat((positive ? "+" : ""), formatStarsAmount(transaction.amount), " ⭐️"), .8f));
            SpannableStringBuilder s = new SpannableStringBuilder(textView.getText());
            if (transaction.refund) {
                appendStatus(s, textView, getString(R.string.StarsRefunded));
            } else if (transaction.failed) {
                textView.setTextColor(Theme.getColor(Theme.key_color_red, resourcesProvider));
                appendStatus(s, textView, getString(R.string.StarsFailed));
            } else if (transaction.pending) {
                textView.setTextColor(Theme.getColor(Theme.key_color_yellow, resourcesProvider));
                appendStatus(s, textView, getString(R.string.StarsPending));
            }
            textView.setText(s);
            linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 36, 0, 36, 4));

            if (transaction.description != null && transaction.extended_media.isEmpty()) {
                textView = new TextView(context);
                textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                textView.setGravity(Gravity.CENTER);
                textView.setText(transaction.description);
                linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 36, 0, 36, 4));
            }
        }

        TableView tableView = new TableView(context, resourcesProvider);
        if (transaction.stargift != null) {
            if (transaction.stargift_upgrade) {
                if ((transaction.flags & 256) != 0 && transaction.msg_id > 0) {
                    final TableRow row = tableView.addRow(getString(R.string.StarGiftReason), getString(R.string.StarGiftReasonUpgrade));
                    final ButtonSpan.TextViewButtons rowTextView = (ButtonSpan.TextViewButtons) ((TableView.TableRowContent) row.getChildAt(1)).getChildAt(0);
                    final TL_stars.TL_inputSavedStarGiftUser stargift = new TL_stars.TL_inputSavedStarGiftUser();
                    stargift.msg_id = transaction.msg_id;
                    StarsController.getInstance(currentAccount).getUserStarGift(stargift, gift -> {
                        if (gift != null) {
                            SpannableStringBuilder sb = new SpannableStringBuilder(rowTextView.getText());
                            sb.append(" ").append(ButtonSpan.make(getString(R.string.StarGiftReasonUpgradeView), () -> {
                                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                                new StarGiftSheet(context, currentAccount, selfId, resourcesProvider)
                                    .set(gift, null)
                                    .show();
                            }, resourcesProvider));
                            rowTextView.setText(sb);
                        }
                    });
                }
                if (transaction.peer instanceof TL_stars.TL_starsTransactionPeer) {
                    final TL_stars.TL_starsTransactionPeer peer = (TL_stars.TL_starsTransactionPeer) transaction.peer;
                    final long from_id = DialogObject.getPeerDialogId(peer.peer);
                    tableView.addRowUser(getString(R.string.StarGiftUpgradeGiftFrom), currentAccount, from_id, () -> {
                        sheet[0].dismiss();
                        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            lastFragment.presentFragment(ChatActivity.of(from_id));
                        }
                    });
                }
            } else if (transaction.stargift instanceof TL_stars.TL_starGiftUnique) {
                final String slug = transaction.stargift.slug;
                if (!TextUtils.isEmpty(slug)) {
                    tableView.addRowLink(getString(R.string.Gift2Gift), transaction.stargift.title + " #" + transaction.stargift.num, () -> {
                        Browser.openUrl(context, "https://" + MessagesController.getInstance(currentAccount).linkPrefix + "/nft/" + slug);
                    });
                }
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                final TL_stars.TL_starsTransactionPeer peer = (TL_stars.TL_starsTransactionPeer) transaction.peer;
                final long peerId = DialogObject.getPeerDialogId(peer.peer);
                long from_id, to_id;
                if (transaction.offer) {
                    if (!negative) {
                        tableView.addRow(getString(R.string.StarGiftReason), getString(transaction.refund ? R.string.StarGiftReasonOfferRefund : R.string.StarGiftReasonSale));
                        to_id = selfId;
                        from_id = peerId;
                    } else {
                        tableView.addRow(getString(R.string.StarGiftReason), getString(transaction.refund ? R.string.StarGiftReasonSale : R.string.StarGiftReasonOffer));
                        to_id = peerId;
                        from_id = selfId;
                    }
                } else if (transaction.stargift_resale) {
                    if (!negative) {
                        tableView.addRow(getString(R.string.StarGiftReason), getString(transaction.refund ? R.string.StarGiftReasonPurchase : R.string.StarGiftReasonSale));
                        to_id = selfId;
                        from_id = peerId;
                    } else {
                        tableView.addRow(getString(R.string.StarGiftReason), getString(transaction.refund ? R.string.StarGiftReasonSale : R.string.StarGiftReasonPurchase));
                        to_id = peerId;
                        from_id = selfId;
                    }
                } else if (transaction.stargift_drop_original_details) {
                    tableView.addRow(getString(R.string.StarGiftReason), getString(R.string.StarGiftReasonRemovedDescription));
                    from_id = selfId;
                    to_id = selfId;
                } else {
                    tableView.addRow(getString(R.string.StarGiftReason), getString(R.string.StarGiftReasonTransfer));
                    from_id = selfId;
                    to_id = peerId;
                }
                if (from_id != selfId) {
                    tableView.addRowUser(getString(R.string.Gift2From), currentAccount, from_id, () -> {
                        sheet[0].dismiss();
                        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            final Bundle args = new Bundle();
                            args.putLong("user_id", from_id);
                            if (from_id == selfId) {
                                args.putBoolean("my_profile", true);
                            }
                            args.putBoolean("open_gifts", true);
                            lastFragment.presentFragment(new ProfileActivity(args));
                        }
                    });
                }
                if (to_id != selfId) {
                    tableView.addRowUser(getString(R.string.Gift2To), currentAccount, to_id, () -> {
                        sheet[0].dismiss();
                        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            final Bundle args = new Bundle();
                            args.putLong("user_id", to_id);
                            if (to_id == selfId) {
                                args.putBoolean("my_profile", true);
                            }
                            args.putBoolean("open_gifts", true);
                            lastFragment.presentFragment(new ProfileActivity(args));
                        }
                    });
                }
                if ((from_id == selfId || transaction.stargift_resale) && transaction.starref_amount != null && transaction.starref_commission_permille > 0) {
                    if (transaction.amount instanceof TL_stars.TL_starsTonAmount && transaction.starref_amount instanceof TL_stars.TL_starsTonAmount) {
                        final TL_stars.TL_starsTonAmount fullPriceAmount = new TL_stars.TL_starsTonAmount();
                        fullPriceAmount.amount = transaction.amount.amount + transaction.starref_amount.amount;
                        final ColoredImageSpan[] span = new ColoredImageSpan[1];
                        tableView.addRow(getString(R.string.StarsTransactionFullPrice), replaceStarsWithPlain(transaction.amount, "⭐️ " + formatStarsAmount(fullPriceAmount), .8f, span));
                        if (span[0] != null) {
                            span[0].setOverrideColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
                        }
                    } else {
                        final long fullPrice = Math.abs(Math.round(transaction.amount.toDouble() + transaction.starref_amount.toDouble()));
                        tableView.addRow(getString(R.string.StarsTransactionFullPrice), replaceStarsWithPlain(transaction.amount, "⭐️ " + LocaleController.formatNumber(fullPrice, ','), .8f));
                    }
                }
            } else if (!transaction.refund) {
                final long selfId = dialogId == 0 ? UserConfig.getInstance(currentAccount).getClientUserId() : dialogId;
                final long did = DialogObject.getPeerDialogId(transaction.peer.peer);
                final TLRPC.User didUser = MessagesController.getInstance(currentAccount).getUser(did);
                if (positive) { // converted
                    if (did != selfId) {
                        tableView.addRowUser(getString(R.string.StarGiveawayPrizeFrom), currentAccount, did, () -> {
                            sheet[0].dismiss();
                            final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                            if (lastFragment != null) {
                                if ((transaction.flags & 8192) != 0) {
                                    lastFragment.presentFragment(ChatActivity.of(did, transaction.giveaway_post_id));
                                } else {
                                    lastFragment.presentFragment(ChatActivity.of(did));
                                }
                            }
                        }, null, null);
                    }
                    tableView.addRowUser(getString(R.string.StarGiveawayPrizeTo), currentAccount, selfId, () -> {
                        sheet[0].dismiss();
                        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            final Bundle args = new Bundle();
                            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                            args.putBoolean("my_profile", true);
                            args.putBoolean("open_gifts", true);
                            lastFragment.presentFragment(new ProfileActivity(args));
                        }
                    });
                } else { // sent
                    if (did != selfId) {
                        tableView.addRowUser(getString(R.string.StarGiveawayPrizeFrom), currentAccount, selfId, () -> {
                            sheet[0].dismiss();
                            final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                            if (lastFragment != null) {
                                final Bundle args = new Bundle();
                                args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                                args.putBoolean("my_profile", true);
                                args.putBoolean("open_gifts", true);
                                lastFragment.presentFragment(new ProfileActivity(args));
                            }
                        });
                    }
                    tableView.addRowUser(getString(R.string.StarGiveawayPrizeTo), currentAccount, did, () -> {
                        sheet[0].dismiss();
                        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            if ((transaction.flags & 8192) != 0) {
                                lastFragment.presentFragment(ChatActivity.of(did, transaction.giveaway_post_id));
                            } else {
                                lastFragment.presentFragment(ChatActivity.of(did));
                            }
                        }
                    }, null, null);
                }
            }
        } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeer) {
            final long did = DialogObject.getPeerDialogId(transaction.peer.peer);
            if (transaction.paid_message) {
                tableView.addRowUser(getString(positive ? R.string.Gift2From : R.string.Gift2To), currentAccount, did, () -> {
                    sheet[0].dismiss();
                    BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                    if (lastFragment != null) {
                        lastFragment.presentFragment(ChatActivity.of(did));
                    }
                });
                if (transaction.starref_amount != null && transaction.starref_commission_permille > 0) {
                    final long fullPrice = Math.abs(Math.round(transaction.amount.toDouble() + transaction.starref_amount.toDouble()));
                    tableView.addRow(getString(R.string.StarsTransactionFullPrice), replaceStarsWithPlain(transaction.amount, "⭐️ " + LocaleController.formatNumber(fullPrice, ','), .8f));
                }
            } else if (giveaway) {
                tableView.addRowUser(getString(R.string.StarGiveawayPrizeFrom), currentAccount, did, () -> {
                    sheet[0].dismiss();
                    final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                    if (lastFragment != null) {
                        if ((transaction.flags & 8192) != 0) {
                            lastFragment.presentFragment(ChatActivity.of(did, transaction.giveaway_post_id));
                        } else {
                            lastFragment.presentFragment(ChatActivity.of(did));
                        }
                    }
                });
                tableView.addRowUser(getString(R.string.StarGiveawayPrizeTo), currentAccount, UserConfig.getInstance(currentAccount).getClientUserId(), () -> {
                    sheet[0].dismiss();
                    final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                    if (lastFragment != null) {
                        final Bundle args = new Bundle();
                        args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                        args.putBoolean("my_profile", true);
                        lastFragment.presentFragment(new ProfileActivity(args));
                    }
                });
                tableView.addRowLink(getString(R.string.StarGiveawayReason), getString(R.string.StarGiveawayReasonLink), () -> {
                    sheet[0].dismiss();
                    final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                    if (lastFragment != null) {
                        if ((transaction.flags & 8192) != 0) {
                            lastFragment.presentFragment(ChatActivity.of(did, transaction.giveaway_post_id));
                        } else {
                            lastFragment.presentFragment(ChatActivity.of(did));
                        }
                    }
                });
                tableView.addRow(getString(R.string.StarGiveawayGift), formatStarsAmountString(transaction.amount));
            } else if (transaction.subscription && !bot) {
                tableView.addRowUser(getString(R.string.StarSubscriptionTo), currentAccount, did, () -> {
                    sheet[0].dismiss();
                    if (UserObject.isService(did)) {
                        Browser.openUrl(context, getString(R.string.StarsTransactionUnknownLink));
                    } else {
                        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            lastFragment.presentFragment(ChatActivity.of(did));
                        }
                    }
                });
            } else if (transaction.premium_gift) {
                tableView.addRowUser(getString(R.string.Gift2To), currentAccount, did, () -> {
                    sheet[0].dismiss();
                    if (UserObject.isService(did)) {
                        Browser.openUrl(context, getString(R.string.StarsTransactionUnknownLink));
                    } else {
                        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            lastFragment.presentFragment(ChatActivity.of(did));
                        }
                    }
                });
                tableView.addRow(getString(R.string.StarsTransactionPremiumGiftDuration), formatPluralStringComma("Months", transaction.premium_gift_months));
            } else if (!transaction.posts_search) {
                tableView.addRowUser(getString(R.string.StarsTransactionRecipient), currentAccount, did, () -> {
                    sheet[0].dismiss();
                    if (UserObject.isService(did)) {
                        Browser.openUrl(context, getString(R.string.StarsTransactionUnknownLink));
                    } else {
                        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            lastFragment.presentFragment(ChatActivity.of(did));
                        }
                    }
                });
            }
        } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerFragment) {
            if (transaction.gift) {
                LinkSpanDrawable.LinksTextView textView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
                textView.setPadding(dp(12.66f), dp(9.33f), dp(12.66f), dp(9.33f));
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
                textView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                textView.setSingleLine(true);
                ((LinkSpanDrawable.LinksTextView) textView).setDisablePaddingsOffsetY(true);
                AvatarSpan avatarSpan = new AvatarSpan(textView, currentAccount, 24);
                CharSequence username = getString(ton ? R.string.StarsTransactionTONFromFragment : R.string.StarsTransactionUnknown);
                CombinedDrawable iconDrawable = getPlatformDrawable("fragment", 24);
                iconDrawable.setIconSize(dp(16), dp(16));
                avatarSpan.setImageDrawable(iconDrawable);
                SpannableStringBuilder ssb = new SpannableStringBuilder("x  " + username);
                ssb.setSpan(avatarSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        sheet[0].dismiss();
                        Browser.openUrl(context, getString(ton ? R.string.StarsTransactionTONFromFragmentLink : R.string.StarsTransactionUnknownLink));
                    }

                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setUnderlineText(false);
                    }
                }, 3, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                textView.setText(ssb);
                tableView.addRowUnpadded(getString(R.string.StarsTransactionRecipient), textView);
            } else {
                tableView.addRow(getString(R.string.StarsTransactionSource), getString(R.string.Fragment));
            }
        } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerAppStore) {
            tableView.addRow(getString(R.string.StarsTransactionSource), getString(R.string.AppStore));
        } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerPlayMarket) {
            tableView.addRow(getString(R.string.StarsTransactionSource), getString(R.string.PlayMarket));
        } else if (transaction.peer instanceof TL_stars.TL_starsTransactionPeerPremiumBot) {
            tableView.addRow(getString(R.string.StarsTransactionSource), getString(R.string.StarsTransactionBot));
        }

        if (transaction.peer instanceof TL_stars.TL_starsTransactionPeer && (transaction.flags & 256) != 0) {
            long did = DialogObject.getPeerDialogId(transaction.peer.peer);
            if (bot) {
                did = dialogId;
            }
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
            if (chat != null) {
                LinkSpanDrawable.LinksTextView textView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
                textView.setPadding(dp(12.66f), dp(9.33f), dp(12.66f), dp(9.33f));
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
                textView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                ((LinkSpanDrawable.LinksTextView) textView).setDisablePaddingsOffsetY(true);
                SpannableStringBuilder ssb = new SpannableStringBuilder("");
                if (!transaction.extended_media.isEmpty()) {
                    int count = 0;
                    for (TLRPC.MessageMedia media : transaction.extended_media) {
                        ImageReceiverSpan span = new ImageReceiverSpan(textView, currentAccount, 24);
                        ImageLocation location = null;
                        if (media instanceof TLRPC.TL_messageMediaPhoto) {
                            location = ImageLocation.getForPhoto(FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, dp(24), true), media.photo);
                        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
                            location = ImageLocation.getForDocument(FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, dp(24), true), media.document);
                        }
                        if (location != null) {
                            span.setRoundRadius(6);
                            span.imageReceiver.setImage(location, "24_24", null, null, null, 0);
                            SpannableString str = new SpannableString("x");
                            str.setSpan(span, 0, str.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            ssb.append(str);
                            ssb.append(" ");
                            count++;
                        }
                        if (count >= 3) break;
                    }
                }
                ssb.append(" ");
                int start = ssb.length();
                String username = ChatObject.getPublicUsername(chat);
                if (TextUtils.isEmpty(username)) {
                    ssb.append(chat.title);
                } else {
                    ssb.append(MessagesController.getInstance(currentAccount).linkPrefix + "/" + username + "/" + transaction.msg_id);
                }
                final long finalDialogId = did;
                Runnable open = () -> {
                    sheet[0].dismiss();
                    BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                    if (lastFragment != null) {
                        Bundle args = new Bundle();
                        args.putLong("chat_id", -finalDialogId);
                        args.putInt("message_id", transaction.msg_id);
                        lastFragment.presentFragment(new ChatActivity(args));
                    }
                };
                ssb.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        open.run();
                    }

                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setUnderlineText(false);
                    }
                }, start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                textView.setSingleLine(true);
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setText(ssb);
                textView.setOnClickListener(v -> open.run());
                tableView.addRowUnpadded(getString(transaction.reaction ? R.string.StarsTransactionMessage : R.string.StarsTransactionMedia), textView);
            }
        }

        if (!TextUtils.isEmpty(transaction.id) && !giveaway) {
            tableView.addRowMonospaced(getString(R.string.StarsTransactionID), transaction.id, transaction.id.length() > 25 ? 9 : 10, () -> {
                BulletinFactory.of(sheet[0].topBulletinContainer, resourcesProvider).createSimpleBulletin(R.raw.copy, getString(R.string.StarsTransactionIDCopied)).show(false);
            });
        }

        if (transaction.floodskip && transaction.floodskip_number > 0) {
            tableView.addRow(getString(R.string.StarsTransactionFloodskipNumberName), LocaleController.formatPluralStringComma("StarsTransactionFloodskipNumber", transaction.floodskip_number));
        }

        tableView.addRow(getString(R.string.StarsTransactionDate), LocaleController.formatString(R.string.formatDateAtTime, LocaleController.getInstance().getFormatterGiveawayCard().format(new Date(transaction.date * 1000L)), LocaleController.getInstance().getFormatterDay().format(new Date(transaction.date * 1000L))));
        if (transaction.stargift != null) {
            if (transaction.stargift.limited) {
                addAvailabilityRow(tableView, currentAccount, transaction.stargift, resourcesProvider);
            }
            if (!TextUtils.isEmpty(transaction.description)) {
                CharSequence message = new SpannableStringBuilder(transaction.description);
                tableView.addFullRow(message);
            }
        }
        linearLayout.addView(tableView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 17, 16, 0));

        if ((transaction.flags & 32) != 0) {
            tableView.addRow(getString(R.string.StarsTransactionTONDate), LocaleController.formatString(R.string.formatDateAtTime, LocaleController.getInstance().getFormatterGiveawayCard().format(new Date(transaction.transaction_date * 1000L)), LocaleController.getInstance().getFormatterDay().format(new Date(transaction.transaction_date * 1000L))));
        }

        if (!ton) {
            LinkSpanDrawable.LinksTextView textView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
            textView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setText(AndroidUtilities.replaceSingleTag(getString(R.string.StarsTransactionTOS), () -> {
                Browser.openUrl(context, getString(R.string.StarsTOSLink));
            }));
            textView.setGravity(Gravity.CENTER);
            linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 15, 16, 0));
        }

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        if ((transaction.flags & 32) != 0) {
            button.setText(getString(R.string.StarsTransactionViewInBlockchainExplorer), false);
        } else {
            button.setText(getString(R.string.OK), false);
        }
        linearLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 15, 16, 0));

        b.setCustomView(linearLayout);
        sheet[0] = b.create();
        sheet[0].useBackgroundTopPadding = false;
        if ((transaction.flags & 32) != 0) {
            button.setOnClickListener(v -> {
                Browser.openUrl(context, transaction.transaction_url);
            });
        } else {
            button.setOnClickListener(v -> {
                sheet[0].dismiss();
            });
        }

        sheet[0].fixNavigationBar();
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (!AndroidUtilities.isTablet() && !AndroidUtilities.hasDialogOnTop(fragment)) {
            sheet[0].makeAttached(fragment);
        }
        sheet[0].show();
        return sheet[0];
    }



    public static BottomSheet showBoostsSheet(Context context, int currentAccount, long dialogId, TL_stories.Boost boost, Theme.ResourcesProvider resourcesProvider) {
        if (boost == null || context == null)
            return null;

        BottomSheet.Builder b = new BottomSheet.Builder(context, false, resourcesProvider);
        BottomSheet[] sheet = new BottomSheet[1];

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(0, dp(20), 0, dp(4));
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);

        FrameLayout topView = new FrameLayout(context);
        topView.setClipChildren(false);
        topView.setClipToPadding(false);
        linearLayout.addView(topView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 150, Gravity.FILL_HORIZONTAL, 0, 0, 0, 10));

        final StarParticlesView particlesView = makeParticlesView(context, 70, 0);
        topView.addView(particlesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final GLIconTextureView iconView = new GLIconTextureView(context, GLIconRenderer.DIALOG_STYLE, Icon3D.TYPE_GOLDEN_STAR);
        iconView.mRenderer.colorKey1 = Theme.key_starsGradient1;
        iconView.mRenderer.colorKey2 = Theme.key_starsGradient2;
        iconView.mRenderer.updateColors();
        iconView.setStarParticlesView(particlesView);
        topView.addView(iconView, LayoutHelper.createFrame(170, 170, Gravity.CENTER, 0, 32, 0, 24));
        iconView.setPaused(false);

        TextView textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setGravity(Gravity.CENTER);
        textView.setText(LocaleController.formatPluralStringSpaced("BoostStars", (int) boost.stars));
        linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 4));

        textView = new TextView(context);
        textView.setBackground(Theme.createRoundRectDrawable(dp(20), 0xFF967BFF));
        textView.setTextColor(0xFFFFFFFF);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.33f);
        textView.setPadding(dp(4), 0, dp(8.33f), 0);
        textView.setGravity(Gravity.CENTER);
        textView.setTypeface(AndroidUtilities.bold());
        final SpannableStringBuilder sb = new SpannableStringBuilder("x" + LocaleController.formatPluralStringSpaced("BoostingBoostsCount", boost.multiplier == 0 ? 1 : boost.multiplier));
        final ColoredImageSpan span = new ColoredImageSpan(R.drawable.mini_boost_badge, ColoredImageSpan.ALIGN_CENTER);
        span.translate(0, dp(0.66f));
        sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        textView.setText(sb);
        linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 20, Gravity.CENTER, 20, 4, 20, 4));

        TableView tableView = new TableView(context, resourcesProvider);

        tableView.addRowUser(getString(R.string.BoostFrom), currentAccount, dialogId, () -> {
            if (sheet[0] != null) sheet[0].dismiss();
            final BaseFragment baseFragment = LaunchActivity.getSafeLastFragment();
            if (baseFragment == null) return;
            baseFragment.presentFragment(ChatActivity.of(dialogId));
        });
        tableView.addRow(getString(R.string.BoostGift), formatPluralString("BoostStars", (int) boost.stars));
        if (boost.giveaway_msg_id != 0) {
            tableView.addRowLink(getString(R.string.BoostReason), getString(R.string.BoostReasonGiveaway), () -> {
                if (sheet[0] != null) sheet[0].dismiss();
                final BaseFragment baseFragment = LaunchActivity.getSafeLastFragment();
                if (baseFragment == null) return;
                baseFragment.presentFragment(ChatActivity.of(dialogId, boost.giveaway_msg_id));
            });
        }
        tableView.addRow(getString(R.string.BoostDate), LocaleController.formatString(R.string.formatDateAtTime, LocaleController.getInstance().getFormatterGiveawayCard().format(new Date(boost.date * 1000L)), LocaleController.getInstance().getFormatterDay().format(new Date(boost.date * 1000L))));
        tableView.addRow(getString(R.string.BoostUntil), LocaleController.formatString(R.string.formatDateAtTime, LocaleController.getInstance().getFormatterGiveawayCard().format(new Date(boost.expires * 1000L)), LocaleController.getInstance().getFormatterDay().format(new Date(boost.expires * 1000L))));

        linearLayout.addView(tableView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 17, 16, 0));

        textView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        textView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setText(AndroidUtilities.replaceSingleTag(getString(R.string.StarsTransactionTOS), () -> {
            Browser.openUrl(context, getString(R.string.StarsTOSLink));
        }));
        textView.setGravity(Gravity.CENTER);
        linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 15, 14, 7));

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider);
        button.setText(getString(R.string.OK), false);
        button.setOnClickListener(v -> {
            if (sheet[0] != null) sheet[0].dismiss();
        });
        linearLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 0));

        b.setCustomView(linearLayout);
        sheet[0] = b.create();
        sheet[0].useBackgroundTopPadding = false;

        sheet[0].fixNavigationBar();
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (!AndroidUtilities.isTablet() && !AndroidUtilities.hasDialogOnTop(fragment)) {
            sheet[0].makeAttached(fragment);
        }
        iconView.setPaused(false);
        sheet[0].show();
        sheet[0].setOnDismissListener(() -> {
            iconView.setPaused(true);
        });

        return sheet[0];
    }

    private static CharSequence appendStatus(SpannableStringBuilder s, TextView textView, String string) {
        s.append(" ");
        SpannableString refund = new SpannableString(string);
        final int color = textView.getCurrentTextColor();
        refund.setSpan(new ReplacementSpan() {
            private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            { backgroundPaint.setColor(Theme.multAlpha(color, .10f)); }
            private final Text layout = new Text(string, 13, AndroidUtilities.bold());

            @Override
            public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
                return (int) (dp(6 + 6) + layout.getCurrentWidth());
            }

            @Override
            public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
                AndroidUtilities.rectTmp.set(x, (top + bottom - dp(20)) / 2f, x + dp(6 + 6) + layout.getCurrentWidth(), (top + bottom + dp(20)) / 2f);
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(4), dp(4), backgroundPaint);
                layout.draw(canvas, x + dp(6), (top + bottom) / 2f, color, 1f);
            }
        }, 0, refund.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append(refund);
        return s;
    }







    public static void setGiftImage(ImageReceiver imageReceiver, TLRPC.Document document, int size) {
        if (document == null) {
            imageReceiver.clearImage();
            return;
        }
        final TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, size);
        final SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(document.thumbs, Theme.key_windowBackgroundGray, 0.35f);
        imageReceiver.setImage(
            ImageLocation.getForDocument(document),
            size + "_" + size,
            ImageLocation.getForDocument(photoSize, document),
            size + "_" + size,
            svgThumb,
            0,
            null,
            null,
            0
        );
    }

    public static void setGiftImage(ImageReceiver imageReceiver, TL_stars.StarGift gift, int size) {
        setGiftImage(imageReceiver, gift == null ? null : gift.getDocument(), size);
    }

    public static BottomSheet showSoldOutGiftSheet(Context context, int currentAccount, TL_stars.StarGift gift, Theme.ResourcesProvider resourcesProvider) {
        if (gift == null || context == null)
            return null;

        BottomSheet.Builder b = new BottomSheet.Builder(context, false, resourcesProvider);
        BottomSheet[] sheet = new BottomSheet[1];

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(dp(16), dp(20), dp(16), dp(8));
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);

        BackupImageView imageView = new BackupImageView(context);
        setGiftImage(imageView.getImageReceiver(), gift, 160);
        linearLayout.addView(imageView, LayoutHelper.createLinear(160, 160, Gravity.CENTER, 0, -8, 0, 10));

        TextView textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setGravity(Gravity.CENTER);
        textView.setText(getString(R.string.Gift2SoldOutSheetTitle));
        linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 4));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setGravity(Gravity.CENTER);
        textView.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        textView.setText(getString(R.string.Gift2SoldOutSheetSubtitle));
        linearLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 4));

        TableView tableView = new TableView(context, resourcesProvider);

        if (gift.first_sale_date != 0) {
            tableView.addRowDateTime(getString(R.string.Gift2SoldOutSheetFirstSale), gift.first_sale_date);
        }
        if (gift.last_sale_date != 0) {
            tableView.addRowDateTime(getString(R.string.Gift2SoldOutSheetLastSale), gift.last_sale_date);
        }
        tableView.addRow(getString(R.string.Gift2SoldOutSheetValue), replaceStarsWithPlain("⭐️ " + LocaleController.formatNumber(gift.stars, ','), .8f));
        if (gift.limited) {
            addAvailabilityRow(tableView, currentAccount, gift, resourcesProvider);
        }
        linearLayout.addView(tableView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 17, 0, 12));

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider);
        button.setText(getString(R.string.OK), false);
        linearLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        b.setCustomView(linearLayout);
        sheet[0] = b.create();
        sheet[0].useBackgroundTopPadding = false;

        button.setOnClickListener(v -> {
            sheet[0].dismiss();
        });

        sheet[0].fixNavigationBar();
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (!AndroidUtilities.isTablet() && !AndroidUtilities.hasDialogOnTop(fragment)) {
            sheet[0].makeAttached(fragment);
        }
        sheet[0].show();
        return sheet[0];
    }

    public static void addAvailabilityRow(TableView tableView, int currentAccount, TL_stars.StarGift gift, Theme.ResourcesProvider resourcesProvider) {
        final TableRow row = tableView.addRow(getString(R.string.Gift2Availability), "");
        final TextView rowTextView = (TextView) ((TableView.TableRowContent) row.getChildAt(1)).getChildAt(0);
        final SpannableStringBuilder sb = new SpannableStringBuilder("x ");
        final LoadingSpan span = new LoadingSpan(rowTextView, dp(90), 0, resourcesProvider);
        span.setColors(
            Theme.multAlpha(rowTextView.getPaint().getColor(), .21f),
            Theme.multAlpha(rowTextView.getPaint().getColor(), .08f)
        );
        sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rowTextView.setText(sb, TextView.BufferType.SPANNABLE);
        if (!gift.sold_out) {
            StarsController.getInstance(currentAccount).getStarGift(gift.id, remoteGift -> {
                if (remoteGift == null) return;
                if (remoteGift instanceof TL_stars.TL_starGiftUnique) {
                    rowTextView.setText(remoteGift.availability_remains <= 0 ? formatPluralStringComma("Gift2QuantityIssuedNone", remoteGift.availability_total) : formatPluralStringComma("Gift2QuantityIssued1", remoteGift.availability_issued) + formatPluralStringComma("Gift2QuantityIssued2", remoteGift.availability_total));
                } else {
                    rowTextView.setText(remoteGift.availability_remains <= 0 ? formatPluralStringComma("Gift2Availability2ValueNone", remoteGift.availability_total) : formatPluralStringComma("Gift2Availability4Value", remoteGift.availability_remains, LocaleController.formatNumber(remoteGift.availability_total, ',')));
                }
            });
        } else {
            if (gift instanceof TL_stars.TL_starGiftUnique) {
                rowTextView.setText(gift.availability_remains <= 0 ? formatPluralStringComma("Gift2QuantityIssuedNone", gift.availability_total) : formatPluralStringComma("Gift2QuantityIssued1", gift.availability_issued) + formatPluralStringComma("Gift2QuantityIssued2", gift.availability_total));
            } else {
                rowTextView.setText(gift.availability_remains <= 0 ? formatPluralStringComma("Gift2Availability2ValueNone", gift.availability_total) : formatPluralStringComma("Gift2Availability4Value", gift.availability_remains, LocaleController.formatNumber(gift.availability_total, ',')));
            }
        }
    }

    public static void addStatusRow(TableView tableView, int currentAccount, long user_id, int msg_id, Theme.ResourcesProvider resourcesProvider, Runnable openUpgrade) {
        final TableRow row = tableView.addRow(getString(R.string.Gift2Status), "");
        final TextView rowTextView = (TextView) ((TableView.TableRowContent) row.getChildAt(1)).getChildAt(0);
        final SpannableStringBuilder sb = new SpannableStringBuilder("x ");
        final LoadingSpan span = new LoadingSpan(rowTextView, dp(90), 0, resourcesProvider);
        span.setColors(
            Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), .21f),
            Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), .08f)
        );
        sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rowTextView.setText(sb, TextView.BufferType.SPANNABLE);

        TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
        req.id.add(msg_id);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            TLRPC.TL_messageActionStarGift action = null;
            if (res instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages messages = (TLRPC.messages_Messages) res;
                for (int i = 0; i < messages.messages.size(); ++i) {
                    TLRPC.Message msg = messages.messages.get(i);
                    if (msg != null && msg.action instanceof TLRPC.TL_messageActionStarGift) {
                        action = (TLRPC.TL_messageActionStarGift) msg.action;
                        break;
                    }
                }
            }

            if (action != null && action.can_upgrade) {
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                ssb.append(getString(R.string.Gift2StatusNonUnique));
                ssb.append(" ");
                ssb.append(ButtonSpan.make(getString(R.string.Gift2StatusUpgrade), openUpgrade));
                rowTextView.setText(ssb);
            } else {
                rowTextView.setText(getString(R.string.Gift2StatusNonUnique));
            }
        });
    }

    public static DecimalFormat floatFormat2;
    public static String formatTON(long ton) {
        if (floatFormat2 == null)
            floatFormat2 = new DecimalFormat("0.####", new DecimalFormatSymbols(Locale.US));
        if (ton % 1_000_000_000 != 0) {
            return floatFormat2.format(ton / 1_000_000_000.0);
        } else {
            return (ton < 0 ? "-" : "") + LocaleController.formatNumber(Math.abs(ton / 1_000_000_000L), ',');
        }
    }

    public static CharSequence formatStarsAmount(TL_stars.StarsAmount starsAmount) {
        return formatStarsAmount(starsAmount, 0.777f, ',');
    }
    private static DecimalFormat floatFormat;
    public static CharSequence formatStarsAmount(TL_stars.StarsAmount starsAmount, float relativeSize, char symbol) {
        if (floatFormat == null)
            floatFormat = new DecimalFormat("0.###", new DecimalFormatSymbols(Locale.US));
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        if (starsAmount instanceof TL_stars.TL_starsTonAmount) {
            if (starsAmount.amount % 1_000_000_000 != 0) {
                String str = floatFormat.format(starsAmount.amount / 1_000_000_000.0);
                ssb.append(str);
                int index;
                if ((index = str.indexOf(".")) >= 0) {
                    ssb.setSpan(new RelativeSizeSpan(relativeSize), index, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                ssb.append((starsAmount.negative() ? "-" : "") + LocaleController.formatNumber(Math.abs(starsAmount.amount / 1_000_000_000L), symbol));
            }
        } else {
            final long amount = starsAmount.amount + (starsAmount.nanos < 0 && starsAmount.amount > 0 ? -1 : (starsAmount.nanos > 0 && starsAmount.amount < 0 ? +1 : 0));
            final boolean negative = starsAmount.amount == 0 ? starsAmount.nanos < 0 : starsAmount.amount < 0;
            if (starsAmount.nanos != 0) {
                ssb.append((negative ? "-" : "") + LocaleController.formatNumber(Math.abs(amount), symbol));
                String str = floatFormat.format((starsAmount.nanos < 0 ? 1e9 + starsAmount.nanos : starsAmount.nanos) / 1e9d);
                int index;
                if ((index = str.indexOf(".")) >= 0) {
                    int fromIndex = ssb.length();
                    ssb.append(str.substring(index));
                    ssb.setSpan(new RelativeSizeSpan(relativeSize), fromIndex + 1, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                ssb.append((negative ? "-" : "") + LocaleController.formatNumber(Math.abs(amount), symbol));
            }
        }
        return ssb;
    }

    public static CharSequence formatStarsAmountShort(TL_stars.StarsAmount starsAmount) {
        return formatStarsAmountShort(starsAmount, 0.777f, ' ');
    }

    public static CharSequence formatStarsAmountShort(TL_stars.StarsAmount starsAmount, float relativeSize, char symbol) {
        if (floatFormat == null)
            floatFormat = new DecimalFormat("0.################", new DecimalFormatSymbols(Locale.US));
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        if (starsAmount instanceof TL_stars.TL_starsTonAmount) {
            String str = floatFormat.format(starsAmount.amount / 1_000_000_000.0);
            ssb.append(str);
            int index;
            if ((index = str.indexOf(".")) >= 0) {
                ssb.setSpan(new RelativeSizeSpan(relativeSize), index, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            final long amount = starsAmount.amount + (starsAmount.nanos < 0 && starsAmount.amount > 0 ? -1 : (starsAmount.nanos > 0 && starsAmount.amount < 0 ? +1 : 0));
            final boolean negative = starsAmount.amount == 0 ? starsAmount.nanos < 0 : starsAmount.amount < 0;
            if (Math.abs(amount) <= 1000 && starsAmount.nanos != 0) {
                ssb.append((negative ? "-" : "") + LocaleController.formatNumber(Math.abs(amount), symbol));
                String str = floatFormat.format((starsAmount.nanos < 0 ? 1e9 + starsAmount.nanos : starsAmount.nanos) / 1e9d);
                int index;
                if ((index = str.indexOf(".")) >= 0) {
                    int fromIndex = ssb.length();
                    String part = str.substring(index);
                    if (part.length() > 1) {
                        ssb.append(part.substring(0, Math.min(part.length(), 3)));
                        ssb.setSpan(new RelativeSizeSpan(relativeSize), fromIndex + 1, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            } else if (starsAmount.amount <= 1000) {
                ssb.append((negative ? "-" : "") + LocaleController.formatNumber(Math.abs(amount), symbol));
            } else {
                ssb.append((negative ? "-" : "") + AndroidUtilities.formatWholeNumber((int) Math.abs(amount), 0));
            }
        }
        return ssb;
    }

    public static CharSequence formatStarsAmountString(TL_stars.StarsAmount starsAmount) {
        return formatStarsAmountString(starsAmount, 0.777f, ',');
    }

    public static CharSequence formatStarsAmountString(TL_stars.StarsAmount starsAmount, float relativeSize, char symbol) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        if (starsAmount instanceof TL_stars.TL_starsTonAmount) {
            if (floatFormat == null)
                floatFormat = new DecimalFormat("0.################", new DecimalFormatSymbols(Locale.US));
            String str = floatFormat.format(starsAmount.amount / 1_000_000_000.0);
            ssb.append(str);
            int index;
            if ((index = str.indexOf(".")) >= 0) {
                ssb.setSpan(new RelativeSizeSpan(relativeSize), index, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            final long amount = starsAmount.amount + (starsAmount.nanos < 0 && starsAmount.amount > 0 ? -1 : (starsAmount.nanos > 0 && starsAmount.amount < 0 ? +1 : 0));
            final boolean negative = starsAmount.amount == 0 ? starsAmount.nanos < 0 : starsAmount.amount < 0;
            if (starsAmount.nanos != 0) {
                ssb.append((negative ? "-" : "") + LocaleController.formatNumber(Math.abs(amount), symbol));
                if (floatFormat == null)
                    floatFormat = new DecimalFormat("0.################", new DecimalFormatSymbols(Locale.US));
                String str = floatFormat.format((starsAmount.nanos < 0 ? 1e9 + starsAmount.nanos : starsAmount.nanos) / 1e9d);
                int index;
                if ((index = str.indexOf(".")) >= 0) {
                    int fromIndex = ssb.length();
                    ssb.append(str.substring(index));
                    ssb.setSpan(new RelativeSizeSpan(relativeSize), fromIndex + 1, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                ssb.append(" ").append(getString(R.string.StarsNano));
            } else {
                ssb.append(formatPluralStringComma("Stars", (int) starsAmount.amount));
            }
        }
        return ssb;
    }
}
