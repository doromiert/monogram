package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;


import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Wallet.GramWallet;
import org.telegram.ui.Wallet.WalletAccessController;
import org.telegram.ui.Wallet.WalletChatSendTransition;
import org.telegram.ui.Wallet.WalletSendTransition;
import org.telegram.ui.Wallet.WalletActivity;
import org.telegram.ui.Wallet.WalletEngine2;
import org.telegram.ui.Wallet.WalletSendEnterAmount;
import org.telegram.ui.Wallet.WalletRecipientView;
import org.telegram.ui.ChatActivity;

import java.math.BigDecimal;
import com.negzero.monogram.wallet.WalletAmounts;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class ChatAttachAlertGramLayout extends ChatAttachAlert.AttachAlertLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final float PREVIEW_FRACTION_SCALE = 12.0f / 14.0f;
    private static final int TABS_RESERVED_HEIGHT_DP = 70;

    private boolean requestMode;
    private WalletActivity.TabsView paymentTabs;
    private org.telegram.ui.Wallet.FundedPaymentLinkFlow paymentLinkFlow;
    private WalletAccessController walletAccess;
    private ButtonWithCounterView unlockButton;
    private final int currentAccount;
    private final TLRPC.User recipientUser;
    private final FrameLayout contentContainer;
    private final FrameLayout amountArea;
    private final androidx.core.widget.NestedScrollView contentScroll;
    private int expandScrollRange,lastTrayInset=-1;
    private final WalletRecipientView recipientView;
    private final ActionBarMenuItem moreItem;
    private final ActionBarMenuItem inlineMoreItem;

    private String recipientAddress;
    private byte[] recipientPublicKey;
    private boolean loadingAddress;
    private boolean walletUnavailable;
    private boolean destroyed;
    private Activity portraitLockedActivity;
    private int previousOrientation;

    private final LinearLayout center;
    private final LinearLayout centerBottom;
    private final FrameLayout centerBottomContainer;
    private final TextView tapToSetAmount;
    private final WalletSendEnterAmount.AmountInputView amountInput;
    private final EditTextBoldCursor amountEdit;
    private final AnimatedTextView currencyView;
    private final TextView commentView;
    private final LinkSpanDrawable.LinksTextView insufficientFundsView;
    private final org.telegram.ui.Wallet.WalletBalanceStrip walletStrip;
    private final TextView feeView;
    private final SpannableString feeViewLoading;
    private boolean feeVisible;
    private boolean hasEnteredAmount;
    private float feeVisibilityProgress;
    private ValueAnimator feeVisibilityAnimator;
    private long fee = -1;
    private boolean feeEmulationAvailable;
    private Runnable cancelFeeLoading;
    private final FrameLayout bottomContainer;
    private final FrameLayout bottomIntroContainer;
    private SpringAnimation introAnimator;
    private float introProgress = 1f;
    private float introRevealProgress = 1f;
    private ButtonWithCounterView sendButton;
    private boolean sendButtonVisible;

    private final CharSequence currencyExchangeIcon;
    private final CharSequence gramAmountIcon;

    private String comment;
    private boolean commentEncrypted = false;
    private boolean inputInCurrency;
    private int shakeDp = 6;
    private DecimalFormat gramsPreviewFormat;
    private final int[] screenLocation = new int[2];
    private boolean keyboardVisible;
    private float canvasPanTranslationY;
    private float keyboardTransitionProgress;
    private boolean panTransitionInProgress;
    private boolean panTransitionOpening;
    private boolean hasSettledGeometry;
    private float settledBottomY;
    private float settledRecipientY;
    private float transitionFromRecipientY;
    private float transitionToRecipientY;
    private float transitionFromBottomY;
    private float transitionToBottomY;
    private boolean finishTransitionOnLayout;
    private boolean layoutShown;
    private boolean pendingKeyboardRequest;
    private boolean amountKeyboardSuppressed;
    private Runnable pendingCommentAlert;
    private TL_wallet.walletTransaction queuedTransaction;
    private WalletChatSendTransition sendTransition;
    private WalletSendTransition walletSendTransition;
    private boolean sendToWallet;
    private AlertDialog commentDialog;

    public byte[] getPublicKey() {
        if (recipientPublicKey == null) return null;
        for (int i = 0; i < recipientPublicKey.length; ++i)
            if (recipientPublicKey[i] != 0)
                return recipientPublicKey;
        return null;
    }

    public ChatAttachAlertGramLayout(
            ChatAttachAlert alert,
            Context context,
            int currentAccount,
            TLRPC.User recipientUser,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(alert, context, resourcesProvider);
        this.currentAccount = currentAccount;
        this.recipientUser = recipientUser;

        contentContainer = new FrameLayout(context);
        contentContainer.setClipChildren(false);
        contentContainer.setClipToPadding(false);
        contentScroll=new androidx.core.widget.NestedScrollView(context);
        contentScroll.setFillViewport(true);contentScroll.setNestedScrollingEnabled(true);
        contentScroll.setClipToPadding(true);contentScroll.setVerticalScrollBarEnabled(false);
        contentContainer.setPadding(0,dp(12),0,dp(32));
        contentScroll.addView(contentContainer,new androidx.core.widget.NestedScrollView.LayoutParams(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT));
        addView(contentScroll,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.MATCH_PARENT));

        final ActionBarMenu menu = parentAlert.actionBar.createMenu();
        moreItem = menu.addItem(31, R.drawable.ic_ab_other);
        moreItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        moreItem.addSubItem(2, R.drawable.msg_addbot, getString(R.string.WalletDepositFunds));
        moreItem.addSubItem(3, R.drawable.menu_comments, getString(R.string.MonogramWalletAddNote));
        moreItem.setVisibility(View.GONE);

        inlineMoreItem = new ActionBarMenuItem(
                context,
                null,
                0,
                getThemedColor(Theme.key_windowBackgroundWhiteBlackText),
                false,
                resourcesProvider
        );
        inlineMoreItem.setIcon(R.drawable.ic_ab_other);
        inlineMoreItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        inlineMoreItem.setBackground(Theme.createSelectorDrawable(
                getThemedColor(Theme.key_listSelector),
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        inlineMoreItem.setOnClickListener(v -> {
            ItemOptions.makeOptions(ChatAttachAlertGramLayout.this, resourcesProvider, inlineMoreItem)
                .dontFocus()
                .add(R.drawable.msg_addbot, getString(R.string.WalletDepositFunds), () -> {
                    WalletActivity.openWalletQRAlert(getContext(), currentAccount, resourcesProvider);
                })
                .add(
                    R.drawable.menu_comments,
                    getString(R.string.MonogramWalletAddNote),
                    this::openAddCommentAlert
                )
                .setDimAlpha(0)
                .translate(0, -dp(48))
                .show();
        });
//        addView(inlineMoreItem, LayoutHelper.createFrame(
//                48,
//                48,
//                Gravity.TOP | Gravity.RIGHT,
//                0, 0, 4, 0
//        ));

        recipientView = new WalletRecipientView(context, resourcesProvider,
                () -> {
                    parentAlert.dismiss();
                    parentAlert.getBaseFragment().presentFragment(ChatActivity.of(recipientUser.id));
                },
                () -> BulletinFactory.of(this, resourcesProvider)
                        .createSimpleBulletin(R.raw.copy, getString(R.string.WalletAddressCopiedBulletin))
                        .show());
        recipientView.setRecipient(recipientUser, recipientAddress);
        contentContainer.addView(recipientView, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 64, Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                6, 0, 6, 0));

        amountArea = new FrameLayout(context);
        amountArea.setClipChildren(false);
        amountArea.setClipToPadding(false);
        contentContainer.addView(amountArea,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,Gravity.TOP,0,88,0,0));

        center = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        center.setOrientation(LinearLayout.VERTICAL);
        center.setClipChildren(false);
        center.setClipToPadding(false);
        center.setOnClickListener(v -> requestAmountKeyboard());
        center.setGravity(Gravity.CENTER_VERTICAL);
        amountArea.addView(center,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,Gravity.TOP));

        unlockButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
        unlockButton.setText("Unlock wallet");
        unlockButton.setOnClickListener(v -> { if (walletAccess != null) walletAccess.open(); });
        amountArea.addView(unlockButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 32, 0, 32, 0));
        contentScroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener)(view,x,y,oldX,oldY)->{
            updateBodyViewport();
            parentAlert.updateLayout(ChatAttachAlertGramLayout.this,true,y-oldY);
        });

        paymentTabs = new WalletActivity.TabsView(context, new CharSequence[]{"Send", "Request"}, selected -> {
            requestMode = selected == 1;
            paymentTabs.setSelected(selected);
            parentAlert.actionBar.setTitle(requestMode ? "Request XMR from" : getString(R.string.WalletSendMoneyTo));
            loadFee();
            updateAmount();
        }, resourcesProvider);
        center.addView(paymentTabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 16));

        amountInput = new WalletSendEnterAmount.AmountInputView(context, resourcesProvider);
        amountInput.setOnClickListener(v -> requestAmountKeyboard());
        center.addView(amountInput, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 64, Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                16, 0, 16, 0));
        amountEdit = amountInput.getEditText();
        amountEdit.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            String value = dest.subSequence(0, dstart).toString()
                    + source.subSequence(start, end) + dest.subSequence(dend, dest.length());
            BigDecimal maxAmount = BigDecimal.valueOf(Long.MAX_VALUE, WalletAmounts.DECIMALS);
            int fractionDigits = WalletAmounts.DECIMALS;
            if (inputInCurrency) {
                final BigDecimal currencyPerGram = getCurrencyPerGram();
                maxAmount = maxAmount.multiply(currencyPerGram);
                fractionDigits = getCurrencyAmountFractionDigits(currencyPerGram);
            }
            final int decimalIndex = Math.max(value.indexOf('.'), value.indexOf(','));
            if (decimalIndex >= 0 && value.length() - decimalIndex - 1 > fractionDigits) {
                return dest.subSequence(dstart, dend);
            }
            if (parseAmount(value).compareTo(maxAmount) > 0) {
                return dest.subSequence(dstart, dend);
            }
            if (start == end) {
                return null;
            }
            boolean hasDecimal = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '.' || c == ',') {
                    if (hasDecimal) {
                        return dest.subSequence(dstart, dend);
                    }
                    hasDecimal = true;
                }
            }
            if (dstart == 0 && (value.startsWith(".") || value.startsWith(","))) {
                return "0" + source.subSequence(start, end);
            }
            return value.startsWith("00") ? dest.subSequence(dstart, dend) : null;
        }});
        amountEdit.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_DONE);
        amountEdit.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !v.hasFocus()) {
                requestAmountKeyboard();
            }
            return false;
        });
        amountEdit.setOnClickListener(v -> requestAmountKeyboard());
        amountEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && sendButton.isEnabled()) {
                sendButton.performClick();
                return true;
            }
            return false;
        });
        amountEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateAmount();
            }
        });
        currencyExchangeIcon = new SpannableString("⇅");
        final ColoredImageSpan exchangeSpan = new ColoredImageSpan(R.drawable.wallet_currency_exchange);
        exchangeSpan.setAlpha(0.72f);
        ((SpannableString) currencyExchangeIcon).setSpan(
                exchangeSpan,
                0,
                currencyExchangeIcon.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        gramAmountIcon = new SpannableString("G");
        final ColoredImageSpan gramAmountSpan = new ColoredImageSpan(R.drawable.wallet_monero_small);
        gramAmountSpan.recolorDrawable = false;
        ((SpannableString) gramAmountIcon).setSpan(
                gramAmountSpan,
                0,
                gramAmountIcon.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        currencyView = new AnimatedTextView(context, false, true, true, true, true);
        currencyView.setAnimationProperties(.35f, 0, 320, 3.5f, CubicBezierInterpolator.EASE_OUT_QUINT);
        currencyView.setScaleProperty(0.35f);
        currencyView.setText(getCurrencyPreviewText(GramWallet.getInstance(currentAccount), 0));
        currencyView.setTextColor(Theme.blendOver(
                getThemedColor(Theme.key_windowBackgroundWhite),
                getThemedColor(Theme.key_windowBackgroundWhiteGrayText2)
        ));
        currencyView.setTextSize(dp(14));
        currencyView.setTypeface(AndroidUtilities.getTypeface("fonts/gram.ttf"));
        currencyView.setGravity(Gravity.CENTER);
        currencyView.setPadding(dp(10), 0, dp(10), 0);
        currencyView.setAllowCancel(true);
        currencyView.setSizeableBackground(Theme.createRoundRectDrawable(
                dp(14),
                Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), 0.08f)
        ));
        center.addView(currencyView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT,
                28,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                0, 8, 0, 0
        ));
        ScaleStateListAnimator.apply(currencyView);
        currencyView.setOnClickListener(v -> switchInputMode());

        centerBottomContainer = new FrameLayout(context) {
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                super.onMeasure(widthSpec,heightSpec);
                // Invisible legacy error/hint rows must not reserve an empty block.
                if(insufficientFundsView!=null && insufficientFundsView.getVisibility()==View.GONE)
                    setMeasuredDimension(getMeasuredWidth(),0);
            }
        };
        center.addView(centerBottomContainer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        centerBottom = new LinearLayout(context);
        centerBottom.setOrientation(LinearLayout.VERTICAL);
        centerBottomContainer.addView(centerBottom, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        tapToSetAmount = new TextView(context);
        tapToSetAmount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tapToSetAmount.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        tapToSetAmount.setPadding(dp(10), 0, dp(10), 0);
        tapToSetAmount.setGravity(Gravity.CENTER);
        tapToSetAmount.setText(LocaleController.getString(R.string.WalletTapToSetAmount));
        centerBottomContainer.addView(tapToSetAmount, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT,
            28,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL,
            0, 8, 0, 0
        ));
        ScaleStateListAnimator.apply(tapToSetAmount);
        tapToSetAmount.setOnClickListener(v -> {
            requestAmountKeyboard();
        });

        centerBottom.setVisibility(View.INVISIBLE);
        centerBottom.setAlpha(0.0f);
        centerBottom.setScaleX(0.8f);
        centerBottom.setScaleY(0.8f);
        tapToSetAmount.setVisibility(View.GONE);
        tapToSetAmount.setAlpha(1.0f);
        tapToSetAmount.setScaleX(1.0f);
        tapToSetAmount.setScaleY(1.0f);
        centerBottomVisible = false;

        commentView = new TextView(context);
        final MessageDrawable commentBackground = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, false);
        commentBackground.shadow = false;
        commentBackground.paint.setStyle(Paint.Style.STROKE);
        commentBackground.paint.setStrokeWidth(Math.max(1, dpf2(0.5f)));
        commentBackground.paint.setColor(getThemedColor(Theme.key_wallet_out_commentBorder));
        commentBackground.overrideColor = getThemedColor(Theme.key_wallet_out_commentBorder);
        commentView.setPadding(dp(15), dp(7), dp(19), dp(8));
        commentView.setBackground(commentBackground);
        commentView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        commentView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        commentView.setTypeface(AndroidUtilities.bold());
        commentView.setText(getString(R.string.MonogramWalletAddNote));
                commentView.setVisibility(View.VISIBLE);
        commentView.setEllipsize(TextUtils.TruncateAt.END);
        commentView.setMaxLines(4);
        center.addView(commentView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                32, 12, 32, 0
        ));
        commentView.setOnClickListener(v -> {
            openAddCommentAlert();
        });
        ScaleStateListAnimator.apply(commentView, .02f, 1.2f);

        insufficientFundsView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        insufficientFundsView.setTextColor(getThemedColor(Theme.key_text_RedBold));
        insufficientFundsView.setLinkTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        insufficientFundsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        insufficientFundsView.setGravity(Gravity.CENTER);
        insufficientFundsView.setTypeface(AndroidUtilities.bold());
        insufficientFundsView.setPadding(dp(10), 0, dp(10), 0);
        showError(false, true);
        centerBottom.addView(insufficientFundsView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT,
            LayoutHelper.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL,
            0, 10, 0, 0
        ));

        bottomContainer = new FrameLayout(context);
        bottomContainer.setPadding(dp(16), 0, dp(16), dp(12));
        // Keep intro offsets separate from keyboard pan and send-button visibility.
        bottomIntroContainer = new FrameLayout(context);
        bottomContainer.setClipChildren(false);
        bottomContainer.setClipToPadding(false);
        bottomIntroContainer.setClipChildren(false);
        bottomIntroContainer.setClipToPadding(false);
        bottomContainer.addView(bottomIntroContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        walletStrip=new org.telegram.ui.Wallet.WalletBalanceStrip(context);
        walletStrip.setOnWalletSelected(this::selectSheetWallet);
        bottomIntroContainer.addView(walletStrip,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,48,Gravity.TOP));

        feeView = new TextView(context);
        feeView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        feeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        feeView.setGravity(Gravity.CENTER);
        feeView.setMaxLines(2);
        feeView.setPadding(0, dp(4), 0, dp(4));
        feeView.setVisibility(View.GONE);
        bottomIntroContainer.addView(feeView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.FILL_HORIZONTAL,
                0, 52, 0, 0
        ));
        feeViewLoading = new SpannableString(getString(R.string.Loading));
        feeViewLoading.setSpan(new LoadingSpan(feeView, dp(80)), 0, feeViewLoading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sendButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
        sendButton.setText(LocaleController.getString(R.string.WalletSendGrams));
        sendButton.setEnabled(false);
        sendButton.setOnClickListener(v -> send());
        bottomIntroContainer.addView(sendButton, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                48,
                Gravity.BOTTOM | Gravity.FILL_HORIZONTAL
        ));
        addView(bottomContainer, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                92,
                Gravity.BOTTOM | Gravity.FILL_HORIZONTAL,
                0, 0, 0, TABS_RESERVED_HEIGHT_DP
        ));
        updateSendButtonVisibility(false, false);

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.walletUpdate);
        updateBalance();
        loadRecipientAddress();
    }

    private boolean centerBottomVisible;
    private void updateCenterBottom() {
        if (centerBottom == null || tapToSetAmount == null) return;
        final boolean visible = getGramNanoAmount() > 0 || !TextUtils.isEmpty(comment) || keyboardVisible;
        if (visible == centerBottomVisible) return;
        centerBottomVisible = visible;
        centerBottom.setVisibility(View.VISIBLE);
        tapToSetAmount.setVisibility(View.GONE);
        centerBottom.animate().alpha(visible ? 1.0f : 0.0f).scaleX(visible ? 1.0f : 0.8f).scaleY(visible ? 1.0f : 0.8f).withEndAction(() -> {
            centerBottom.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        tapToSetAmount.setVisibility(View.GONE);
    }

    private void showError(boolean show, boolean insufficientFunds) {
        if (show) {
            if (insufficientFunds && GramWallet.getInstance(currentAccount).getBalance() > GramWallet.getInstance(currentAccount).getSpendableBalance()
                    && getGramNanoAmount() <= GramWallet.getInstance(currentAccount).getBalance()) {
                insufficientFundsView.setText("Funds are awaiting confirmations. Available: "
                    + GramWallet.formatGramsWithUnit(GramWallet.getInstance(currentAccount).getSpendableBalance()));
            } else if (insufficientFunds) {
                final SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(getString(R.string.WalletInsufficientFunds));
                sb.append(" ");
                int from = sb.length();
                sb.append(getString(R.string.WalletDepositFunds));
                sb.append(" >");
                sb.setSpan(new ColoredImageSpan(R.drawable.settings_arrow).setOverrideColor(getThemedColor(Theme.key_featuredStickers_addButton)).translate(0, dpf2(0.66f)), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View view) {
                        WalletActivity.openWalletQRAlert(getContext(), currentAccount, resourcesProvider);
                    }
                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                        ds.setUnderlineText(false);
                    }
                }, from, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                insufficientFundsView.setText(sb);
            } else {
                insufficientFundsView.setText(LocaleController.formatSpannable(R.string.WalletMinimumAmount, GramWallet.formatGramCapital(1L)));
            }
        }
        insufficientFundsView.setVisibility(show ? View.VISIBLE : View.GONE);
        insufficientFundsView.animate()
            .alpha(show ? 1.0f : 0.0f)
            .scaleX(show ? 1.0f : 0.6f)
            .scaleY(show ? 1.0f : 0.6f)
            .setDuration(320)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .start();
    }

    private void loadRecipientAddress() {
        loadingAddress = true;
        GramWallet.getInstance(currentAccount).resolveWallet(
            recipientUser,
            true,
            (a, error) -> {
                if (destroyed) return;
                loadingAddress = false;
                if (TextUtils.equals(error, "WALLET_USER_UNAVAILABLE")) {
                    walletUnavailable = true;
                }
                if (a != null && a.user_id == recipientUser.id) {
                    recipientAddress = a.address;
                    recipientPublicKey = a.public_key;
                }
                recipientView.setRecipient(recipientUser, recipientAddress);
                updateAmount();
                loadFee();
            }
        );
    }

    private long feeAmount = -1;
    private boolean accessWasReady;
    private void updateWalletAccess() {
        if (unlockButton == null) return;
        boolean ready = walletAccess != null && walletAccess.ready();
        if (ready && !accessWasReady) feeAmount = -1;
        boolean accessChanged=ready!=accessWasReady;
        accessWasReady = ready;
        center.setVisibility(ready ? View.VISIBLE : View.GONE);
        bottomContainer.setVisibility(View.VISIBLE);
        if(accessChanged)requestLayout();
        unlockButton.setVisibility(ready ? View.GONE : View.VISIBLE);
        unlockButton.setText(walletAccess == null ? "Wallet unavailable" : walletAccess.title());
        unlockButton.setLoading(walletAccess != null && walletAccess.busy() && !walletAccess.scanning());
        unlockButton.setEnabled(walletAccess != null && (!walletAccess.busy() || walletAccess.scanning()));
        if (amountEdit != null && sendButton != null) updateAmount();
    }

    private void selectSheetWallet(com.negzero.monogram.wallet.MonogramWallet chosen){
        try{
            com.negzero.monogram.wallet.MonogramWallet current=GramWallet.getInstance(currentAccount).backend();
            if(current.walletId.equals(chosen.walletId))return;
            if(paymentLinkFlow!=null && paymentLinkFlow.committing())throw new IllegalStateException("Wait for the payment broadcast to finish.");
            if(current.busy && !current.scanning)throw new IllegalStateException("Finish the current wallet operation first.");
            if(cancelFeeLoading!=null){cancelFeeLoading.run();cancelFeeLoading=null;}
            if(paymentLinkFlow!=null){paymentLinkFlow.close();paymentLinkFlow=null;}
            current.select(chosen.walletId);
            if(walletAccess!=null){walletAccess.close();walletAccess=null;}
            GramWallet.cleanup(currentAccount);accessWasReady=false;feeAmount=-1;fee=-1;
            walletAccess=new WalletAccessController(parentAlert.getBaseFragment(),currentAccount,9634,this::updateWalletAccess);
            walletAccess.resume();loadFee();requestLayout();
        }catch(Exception failure){BulletinFactory.global().showForError(failure.getMessage());updateBalance();}
    }

    public void onWalletResume() { if (walletAccess != null) walletAccess.resume(); if (paymentLinkFlow != null) paymentLinkFlow.resume(); }
    public void onWalletPause() { if (walletAccess != null) walletAccess.pause(); if (paymentLinkFlow != null) paymentLinkFlow.pause(); }
    public boolean onWalletActivityResult(int code, int result, android.content.Intent data) {
        return paymentLinkFlow != null && paymentLinkFlow.activityResult(code, result) || walletAccess != null && walletAccess.activityResult(code, result, data);
    }

    private void loadFee() {
        if (cancelFeeLoading != null) { cancelFeeLoading.run(); cancelFeeLoading = null; }
        feeAmount = getGramNanoAmount();
        fee = -1;
        updateBalance();
        if (requestMode || feeAmount <= 0 || !WalletEngine2.isValidRecipientAddress(recipientAddress)) return;
        final long requested = feeAmount;
        cancelFeeLoading = GramWallet.getInstance(currentAccount).emulateSend(
                recipientAddress, requested, comment, commentEncrypted ? getPublicKey() : null,
                (transaction, error) -> {
                    if (requested != getGramNanoAmount()) return;
                    if (transaction != null) fee = transaction.fee;
                    updateAmount();
                });
    }

    private void openAddCommentAlert() {
        if (destroyed || amountKeyboardSuppressed || pendingCommentAlert != null || commentDialog != null) {
            return;
        }
        requestAmountKeyboard();
        // Let the amount layout finish resizing before the dialog takes IME focus.
        final long deadline = android.os.SystemClock.uptimeMillis() + 1500;
        pendingCommentAlert = () -> {
            if (destroyed || !layoutShown || !isShown()) {
                pendingCommentAlert = null;
                return;
            }
            final boolean keyboardReady = parentAlert.sizeNotifierFrameLayout.measureKeyboardHeight() > dp(20)
                    && !panTransitionInProgress && !finishTransitionOnLayout;
            if (!keyboardReady && android.os.SystemClock.uptimeMillis() < deadline) {
                postDelayed(pendingCommentAlert, 32);
                return;
            }
            // Also allow comments when a hardware keyboard prevents the IME opening.
            pendingCommentAlert = null;
            showAddCommentAlert();
        };
        post(pendingCommentAlert);
    }

    private void cancelPendingCommentAlert() {
        if (pendingCommentAlert != null) {
            removeCallbacks(pendingCommentAlert);
            pendingCommentAlert = null;
        }
    }

    private void showAddCommentAlert() {
        final Context context = parentAlert.baseFragment != null
                && parentAlert.baseFragment.getParentActivity() != null
                ? parentAlert.baseFragment.getParentActivity()
                : getContext();
        if (context == null) {
            return;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.MonogramWalletAddNote));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context) {
            private int remaining = 960;
            private final AnimatedColor limitColor = new AnimatedColor(this);
            private final AnimatedTextView.AnimatedTextDrawable limit = new AnimatedTextView.AnimatedTextDrawable(false, true, true);
            {
                limit.setAnimationProperties(.2f, 0, 160, CubicBezierInterpolator.EASE_OUT_QUINT);
                limit.setTextSize(dp(15.33f));
                limit.setGravity(Gravity.RIGHT);
                limit.setCallback(this);
            }

            @Override
            protected boolean verifyDrawable(@NonNull Drawable who) {
                return who == limit || super.verifyDrawable(who);
            }

            @Override
            protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
                super.onTextChanged(text, start, lengthBefore, lengthAfter);
                if (limit == null) return;
                remaining = 960 - text.toString().getBytes(StandardCharsets.UTF_8).length;
                limit.cancelAnimation();
                limit.setText(remaining <= 100 ? Integer.toString(remaining) : "", isAttachedToWindow());
                invalidate();
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                limit.setTextColor(limitColor.set(Theme.getColor(
                        remaining <= 0 ? Theme.key_text_RedRegular : Theme.key_dialogSearchHint,
                        resourcesProvider)));
                final int right = getScrollX() + getWidth() - getPaddingRight() + dp(48);
                final int bottom = getScrollY() + getHeight();
                limit.setBounds(right - dp(48), bottom - Math.min(dp(44), getHeight()), right, bottom);
                limit.draw(canvas);
            }
        };
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        editText.setHint(getString(R.string.MonogramWalletNoteHint));
        editText.setText(comment);
        editText.setSelection(editText.length());
        editText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMaxLines(5);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setLineColors(
                getThemedColor(Theme.key_windowBackgroundWhiteInputField),
                getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                getThemedColor(Theme.key_text_RedRegular)
        );
        editText.setBackground(null);
        editText.setPadding(0, dp(10), dp(48), dp(10));

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                24, 4, 24, 4
        ));
        builder.setCustomViewOffset(6);
        builder.setView(container);
        builder.setWidth(dp(292));
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            if (editText.getText().toString().getBytes(StandardCharsets.UTF_8).length > 960) {
                AndroidUtilities.shakeViewSpring(editText, shakeDp = -shakeDp);
                return;
            }
            final String text;
            try { text = com.negzero.monogram.xmpp.MoneroPayment.note(editText.getText().toString().trim()); }
            catch (IllegalArgumentException invalid) {
                AndroidUtilities.shakeViewSpring(editText, shakeDp = -shakeDp);
                return;
            }
            comment = TextUtils.isEmpty(text) ? null : text;
            commentEncrypted = false;
            if (TextUtils.isEmpty(comment)) {
                commentView.setText(getString(R.string.MonogramWalletAddNote));
                commentView.setVisibility(View.VISIBLE);
            } else {
                commentView.setText(comment);
                commentView.setVisibility(View.VISIBLE);
            }
            updateCenterBottom();
            requestLayout();
            parentAlert.sizeNotifierFrameLayout.requestLayout();
            dialog.dismiss();
            loadFee();
        });

        final AlertDialog dialog = commentDialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        dialog.setOnShowListener(ignored -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        dialog.setOnDismissListener(ignored -> {
            commentDialog = null;
            amountEdit.post(() -> {
                if (!destroyed && layoutShown && isShown()) {
                    requestAmountKeyboard();
                }
            });
        });
        dialog.create();
        final Window window = dialog.getWindow();
        final Rect visibleFrame = new Rect();
        parentAlert.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
        if (window != null && visibleFrame.height() > 0) {
            final WindowManager.LayoutParams params = window.getAttributes();
            final View decor = window.getDecorView();
            decor.measure(
                    MeasureSpec.makeMeasureSpec(params.width > 0 ? params.width : visibleFrame.width(),
                            params.width > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(visibleFrame.height(), MeasureSpec.AT_MOST)
            );
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = Math.max(0, (visibleFrame.height() - decor.getMeasuredHeight()) / 2);
            window.setAttributes(params);
        }
        dialog.show();

        final View positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positiveButton instanceof TextView && !TextUtils.isEmpty(comment)) {
            final TextView positiveButtonText = (TextView) positiveButton;
            editText.addTextChangedListener(new TextWatcher() {
                private boolean wasEmpty = TextUtils.isEmpty(editText.getText());
                @Override
                public void afterTextChanged(Editable editable) {
                    final boolean isEmpty = TextUtils.isEmpty(editable);
                    if (isEmpty != wasEmpty) {
                        positiveButtonText.setText(getString((wasEmpty = isEmpty) ? R.string.Remove : R.string.Add));
                    }
                }
                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
            });
        }
    }

    private void requestAmountKeyboard() {
        if (destroyed || amountKeyboardSuppressed || parentAlert.isDismissed()) {
            return;
        }
        if (!layoutShown) {
            pendingKeyboardRequest = true;
            return;
        }
        pendingKeyboardRequest = false;
        amountEdit.setFocusableInTouchMode(true);
        amountEdit.setShowSoftInputOnFocus(true);
        parentAlert.makeFocusable(amountEdit, true);
        amountEdit.requestFocus();
        AndroidUtilities.showKeyboard(amountEdit);
        amountEdit.postDelayed(() -> {
            if (!destroyed && !amountKeyboardSuppressed && !parentAlert.isDismissed() && layoutShown && commentDialog == null && amountEdit.hasFocus() && isShown()) {
                AndroidUtilities.showKeyboard(amountEdit);
            }
        }, 220);
    }

    private DecimalFormat gramsTextFormat;
    private String getGramsText() {
        final long amount = getGramNanoAmount();
        if (gramsTextFormat == null) {
            gramsTextFormat = new DecimalFormat("#,##0.############", new DecimalFormatSymbols(Locale.US));
        }
        return amount > 0
                ? gramsTextFormat.format(BigDecimal.valueOf(amount, WalletAmounts.DECIMALS))
                : "";
    }

    private String getGramsInputText(long amount) {
        return BigDecimal.valueOf(amount)
                .movePointLeft(WalletAmounts.DECIMALS)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String getGramsPreviewText(long amount) {
        if (gramsPreviewFormat == null) {
            gramsPreviewFormat = new DecimalFormat("#,##0.############", new DecimalFormatSymbols(Locale.US));
            gramsPreviewFormat.setRoundingMode(RoundingMode.HALF_UP);
        }
        return gramsPreviewFormat.format(BigDecimal.valueOf(amount).movePointLeft(WalletAmounts.DECIMALS));
    }

    private CharSequence getGramsPreview(long amount) {
        final SpannableStringBuilder result = new SpannableStringBuilder();
        result.append(gramAmountIcon).append(" ");
        final int amountStart = result.length();
        result.append(getGramsPreviewText(amount));
        setPreviewFractionSpan(result, amountStart, result.length(), '.');
        result.append(" ");
        final int gramStart = result.length();
        result.append("XMR");
        result.setSpan(
                new RelativeSizeSpan(PREVIEW_FRACTION_SCALE),
                gramStart,
                result.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return result.append(" ").append(currencyExchangeIcon);
    }

    private CharSequence getCurrencyPreviewText(GramWallet wallet, long amount) {
        final SpannableStringBuilder result = new SpannableStringBuilder("≈ ");
        final int amountStart = result.length();
        result.append(wallet.formatCurrency(amount));
        setPreviewFractionSpan(result, amountStart, result.length(), getCurrencyDecimalSeparator());
        return result.append(" ").append(currencyExchangeIcon);
    }

    private char getCurrencyDecimalSeparator() {
        final TL_wallet.currencyRate rate = getSelectedCurrencyRate();
        return rate == null || TextUtils.isEmpty(rate.decimalSeparator)
                ? '.'
                : rate.decimalSeparator.charAt(0);
    }

    private static void setPreviewFractionSpan(
            SpannableStringBuilder text,
            int start,
            int end,
            char decimalSeparator
    ) {
        for (int i = start + 1; i + 1 < end; i++) {
            if (text.charAt(i) != decimalSeparator
                    || !Character.isDigit(text.charAt(i - 1))
                    || !Character.isDigit(text.charAt(i + 1))) {
                continue;
            }
            int fractionEnd = i + 1;
            while (fractionEnd < end && Character.isDigit(text.charAt(fractionEnd))) {
                fractionEnd++;
            }
            text.setSpan(
                    new RelativeSizeSpan(PREVIEW_FRACTION_SCALE),
                    i + 1,
                    fractionEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            return;
        }
    }

    private long getGramNanoAmount() {
        BigDecimal entered = parseAmount(amountEdit.getText().toString());
        return WalletAmounts.inputAtomic(entered, inputInCurrency,
                GramWallet.getInstance(currentAccount).currencies.exactRate());
    }

    private void switchInputMode() {
        final BigDecimal currencyPerGram = getCurrencyPerGram();
        if (!inputInCurrency && currencyPerGram.signum() <= 0) {
            return;
        }

        final long amount = getGramNanoAmount();
        inputInCurrency = !inputInCurrency;
        updateInputModeViews();

        final String value;
        if (amount <= 0) {
            value = "";
        } else if (inputInCurrency) {
            value = formatCurrencyInput(amount, currencyPerGram);
        } else {
            value = getGramsInputText(amount);
        }
        amountEdit.setText(value);
        amountEdit.setSelection(value.length());
    }

    private void updateInputModeViews() {
        amountInput.setCurrency(GramWallet.getInstance(currentAccount), inputInCurrency);
        walletStrip.setDisplayCurrency(GramWallet.getInstance(currentAccount),inputInCurrency);
    }

    private int getCurrencyAmountFractionDigits(BigDecimal currencyPerGram) {
        if (currencyPerGram.signum() <= 0) {
            return WalletAmounts.DECIMALS;
        }
        final BigDecimal nanogramInCurrency = currencyPerGram.movePointLeft(WalletAmounts.DECIMALS);
        int fractionDigits = 0;
        BigDecimal step = BigDecimal.ONE.movePointLeft(1);
        while (step.compareTo(nanogramInCurrency) >= 0) {
            fractionDigits++;
            step = step.movePointLeft(1);
        }
        return fractionDigits;
    }

    private String formatCurrencyInput(long nanograms, BigDecimal currencyPerGram) {
        final BigDecimal value = BigDecimal.valueOf(nanograms)
                .multiply(currencyPerGram)
                .movePointLeft(WalletAmounts.DECIMALS);
        final int fractionDigits = Math.max(0, Math.min(getCurrencyFractionDigits(), 20));
        BigDecimal rounded = value.setScale(fractionDigits, RoundingMode.HALF_UP);
        if (rounded.signum() == 0 && value.signum() != 0) {
            rounded = value.setScale(
                    Math.min(20, Math.max(fractionDigits, value.scale())),
                    RoundingMode.HALF_UP
            );
        }
        final int maxFractionDigits = getCurrencyAmountFractionDigits(currencyPerGram);
        if (rounded.scale() > maxFractionDigits) {
            rounded = rounded.setScale(maxFractionDigits, RoundingMode.DOWN);
        }
        final BigDecimal maxAmount = BigDecimal.valueOf(Long.MAX_VALUE, WalletAmounts.DECIMALS).multiply(currencyPerGram);
        if (rounded.compareTo(maxAmount) > 0) {
            rounded = maxAmount.setScale(rounded.scale(), RoundingMode.DOWN);
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    private BigDecimal getCurrencyPerGram() {
        return GramWallet.getInstance(currentAccount).currencies.currencyPerXmr();
    }

    private int getCurrencyFractionDigits() {
        final TL_wallet.currencyRate rate = getSelectedCurrencyRate();
        if (rate != null) {
            return rate.exp;
        }
        return TextUtils.equals(GramWallet.getInstance(currentAccount).currencies.getCurrency(), "USD") ? 2 : 0;
    }

    private TL_wallet.currencyRate getSelectedCurrencyRate() {
        final GramWallet wallet = GramWallet.getInstance(currentAccount);
        final TL_wallet.currencyRates rates = wallet.currencies.getCurrencies();
        if (rates == null) {
            return null;
        }
        for (TL_wallet.currencyRate rate : rates.rates) {
            if (rate != null && TextUtils.equals(rate.currency, wallet.currencies.getCurrency())) {
                return rate;
            }
        }
        return null;
    }

    private void updateSendButtonVisibility(boolean visible, boolean animated) {
        if (animated && sendButtonVisible == visible) {
            return;
        }
        if(sendButtonVisible!=visible)requestLayout();
        sendButtonVisible = visible;
        sendButton.animate().cancel();
        walletStrip.animate().cancel();
        feeView.animate().cancel();

        final float alpha = visible ? 1.0f : 0.0f;
        final float scale = visible ? 1.0f : 0.8f;
        final float textTranslationY = 0;
        if (!animated) {
            sendButton.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            sendButton.setAlpha(alpha);
            sendButton.setScaleX(scale);
            sendButton.setScaleY(scale);
            feeView.setTranslationY(textTranslationY);
            return;
        }

        sendButton.setVisibility(View.VISIBLE);
        sendButton.animate()
                .alpha(alpha)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(320)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> {
                    if (!sendButtonVisible) {
                        sendButton.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
        feeView.animate()
                .translationY(textTranslationY)
                .setDuration(320)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .start();
    }

    private void updateAmount() {
        updateCenterBottom();
        final GramWallet wallet = GramWallet.getInstance(currentAccount);
        final long balance = wallet.getSpendableBalance();
        final long amount = getGramNanoAmount();
        if (amount != feeAmount) loadFee();
        final long minAmount = 1L;
        if (amount > 0 && amount < minAmount) {
            final String minimum = inputInCurrency
                    ? formatCurrencyInput(minAmount, getCurrencyPerGram())
                    : getGramsInputText(minAmount);
            amountEdit.setText(minimum);
            amountEdit.setSelection(minimum.length());
            AndroidUtilities.shakeViewSpring(amountEdit, shakeDp = -shakeDp);
            return;
        }
        final boolean valid = WalletEngine2.isValidAddress(recipientAddress) && amount > 0 && amount <= balance && wallet.canSend();

        showError(!requestMode && !TextUtils.isEmpty(amountEdit.getText()) && amount > balance, true);

        final String amountText = getGramsText();
        sendButton.setLoading(recipientAddress == null && loadingAddress);
        sendButton.setText(TextUtils.isEmpty(amountText) ? LocaleController.getString(R.string.WalletSendGrams) : LocaleController.formatSpannable(R.string.WalletSendAmount, GramWallet.formatGramsWithUnit(amountText)), true);
        final boolean hasAmount = !TextUtils.isEmpty(amountEdit.getText());
        if (requestMode) {
            sendButton.setText(amount > 0 ? "Request " + GramWallet.formatGramsWithUnit(amount) : "Request XMR", true);
            sendButton.setLoading(false);
            sendButton.setEnabled(amount > 0 && walletAccess != null && walletAccess.ready() && !walletAccess.busy());
        } else {
            final boolean link = recipientAddress == null && !loadingAddress;
            sendButton.setEnabled(hasAmount && amount > 0 && amount <= balance && wallet.canSend()
                    && (link || valid && !walletUnavailable && wallet.hasReviewedPayment(recipientAddress, amount))
                    && (paymentLinkFlow == null || !paymentLinkFlow.busy()));
        }
        updateSendButtonVisibility(hasAmount && walletAccess!=null && walletAccess.ready(), layoutShown);
        updateBalance();

        updateInputModeViews();
        currencyView.setText(inputInCurrency
                ? getGramsPreview(amount)
                : getCurrencyPreviewText(wallet, amount));
    }

    private void sendRequest() {
        final long amount = getGramNanoAmount();
        if (amount <= 0) return;
        try {
            final com.negzero.monogram.wallet.MonogramWallet wallet = GramWallet.getInstance(currentAccount).backend();
            final String note = comment;
            sendButton.setLoading(true);
            wallet.newAddress((address, failure) -> {
                if (destroyed) return;
                if (failure != null) { sendButton.setLoading(false); BulletinFactory.global().showForError(failure.getMessage()); return; }
                try (org.telegram.messenger.xmpp.MonogramXmr settings = new org.telegram.messenger.xmpp.MonogramXmr(currentAccount)) {
                    com.negzero.monogram.xmpp.MoneroPayment payment = com.negzero.monogram.xmpp.MoneroPayment.create(
                            address, WalletAmounts.coins(amount).toPlainString(), "", note == null ? "" : note, wallet.network);
                    String id = java.util.UUID.randomUUID().toString();
                    String peer = org.telegram.messenger.xmpp.MonogramController.get(currentAccount).jid(recipientUser.id);
                    settings.addSubaddresses(address);
                    settings.createRequest(id, payment, peer);
                    java.util.HashMap<String, String> params = new java.util.HashMap<>();
                    params.put(org.telegram.messenger.xmpp.MonogramXmr.REQUEST_PARAM, id);
                    params.put(org.telegram.messenger.xmpp.MonogramXmr.PRIVATE_PARAM, "true");
                    org.telegram.messenger.SendMessagesHelper.getInstance(currentAccount).sendMessage(
                            org.telegram.messenger.SendMessagesHelper.SendMessageParams.of("XMR payment request\n" + payment.uri(),
                                    recipientUser.id, null, null, null, false, null, null, params, true, 0, 0, null, false));
                    settings.mark(id, "Queued in chat");
                    parentAlert.dismiss();
                } catch (Exception error) { sendButton.setLoading(false); BulletinFactory.global().showForError(error.getMessage()); }
            });
        } catch (Exception error) { sendButton.setLoading(false); BulletinFactory.global().showForError(error.getMessage()); }
    }

    private void preparePaymentLink() {
        try {
            if (paymentLinkFlow == null) paymentLinkFlow = new org.telegram.ui.Wallet.FundedPaymentLinkFlow(
                    parentAlert.getBaseFragment(), currentAccount, 9636, recipientUser);
            sendButton.setLoading(true);
            paymentLinkFlow.prepare(getGramNanoAmount(), (transaction, error) -> {
                sendButton.setLoading(false);
                if (error != null) {
                    if (!"PASSCODE_FAILED".equals(error)) BulletinFactory.global().showForError(error);
                    return;
                }
                transaction.comment = comment;
                AndroidUtilities.hideKeyboard(amountEdit);
                WalletActivity.openTransactionSheet(getContext(), currentAccount, transaction, (note, encrypted, done) -> {
                    transaction.comment = note;
                    paymentLinkFlow.commit(transaction, result -> {
                        done.run(result);
                        if (result == null) parentAlert.dismiss();
                    }, queued -> queuedTransaction = queued);
                }, null, resourcesProvider);
            });
        } catch (Exception error) { sendButton.setLoading(false); BulletinFactory.global().showForError(error.getMessage()); }
    }

    private void send() {
        if (!sendButton.isEnabled() || sendButton.isLoading()) return;
        if (requestMode) { sendRequest(); return; }
        final GramWallet wallet = GramWallet.getInstance(currentAccount);
        if (recipientAddress == null && recipientUser != null) { preparePaymentLink(); return; }
        final long amount = getGramNanoAmount();
        final long balance = wallet.getSpendableBalance();
        if (!WalletEngine2.isValidAddress(recipientAddress) || amount <= 0) {
            return;
        }
        final long minAmount = 1L;
        if (amount < minAmount) {
            final String minimum = inputInCurrency
                    ? formatCurrencyInput(minAmount, getCurrencyPerGram())
                    : getGramsInputText(minAmount);
            amountEdit.setText(minimum);
            amountEdit.setSelection(minimum.length());
            AndroidUtilities.shakeViewSpring(amountEdit, shakeDp = -shakeDp);
            return;
        }
        if (amount > balance) {
            BotWebViewVibrationEffect.APP_ERROR.vibrate();
            AndroidUtilities.shakeViewSpring(insufficientFundsView, shakeDp = -shakeDp);
            return;
        }

        if (fee < 0 || fee > balance || amount > balance - fee) {
            BotWebViewVibrationEffect.APP_ERROR.vibrate();
            AndroidUtilities.shakeViewSpring(insufficientFundsView, shakeDp = -shakeDp);
            return;
        }
        sendToWallet = false;

        sendButton.setLoading(true);
        amountKeyboardSuppressed = true;
        pendingKeyboardRequest = false;
        cancelPendingCommentAlert();
        // Keep the focused editor and IME alive until the flight actually starts.
        queuedTransaction = null;
        wallet.send(
            recipientUser,
            recipientAddress,
            amount,
            comment,
            commentEncrypted ? getPublicKey() : null,
            null,
            error2 -> {
                if (destroyed || parentAlert.isDismissed()) {
                    return;
                }
                if ("PASSCODE_FAILED".equalsIgnoreCase(error2)) {
                    sendButton.setLoading(false);
                    amountKeyboardSuppressed = false;
                    return;
                }
                if (TextUtils.isEmpty(error2)) {
                    parentAlert.dismiss();
                } else {
                    sendButton.setLoading(false);
                    amountKeyboardSuppressed = false;
                    BulletinFactory.global().showForError(error2);
                }
            },
            transaction -> queuedTransaction = transaction
        );
    }

    public boolean hasQueuedSend() {
        return !destroyed && queuedTransaction != null;
    }

    public void setSendTransitionProgress(float progress) {
        recipientView.setAlpha(1f - progress);
    }

    public AnimatorSet createSendTransition(Runnable completion) {
        if (destroyed || queuedTransaction == null || sendTransition != null || walletSendTransition != null) return null;
        if (sendToWallet) {
            final WalletActivity wallet = new WalletActivity();
            wallet.setCurrentAccount(currentAccount);
            if (parentAlert.getBaseFragment() == null
                    || !parentAlert.getBaseFragment().presentFragment(wallet, false, true)) return null;
            walletSendTransition = new WalletSendTransition(parentAlert, amountInput.getDiamondView(),
                    wallet, queuedTransaction, () -> {
                        if (!destroyed) completion.run();
                    });
            return walletSendTransition.animator();
        }
        if (queuedTransaction.localMessageId == 0 || !amountInput.getDiamondView().canAnimateFlight()
                || !(parentAlert.getBaseFragment() instanceof ChatActivity)) return null;
        ChatActivity chat = (ChatActivity) parentAlert.getBaseFragment();
        if (chat.getCurrentAccount() != currentAccount || chat.getDialogId() != recipientUser.id
                || chat.getChatMode() != 0 || chat.getFragmentView() == null
                || !chat.getFragmentView().isAttachedToWindow()) return null;
        sendTransition = new WalletChatSendTransition(parentAlert, amountInput.getDiamondView(),
                chat, queuedTransaction, () -> {
                    if (!destroyed) completion.run();
                });
        return sendTransition.animator();
    }

    private BigDecimal parseAmount(String value) {
        if (TextUtils.isEmpty(value)) {
            return BigDecimal.ZERO;
        }
        try {
            final BigDecimal amount = new BigDecimal(value.replace(',', '.'));
            return amount.signum() < 0 ? BigDecimal.ZERO : amount;
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private void updateFeeVisibility(boolean visible) {
        if (feeVisible == visible) {
            return;
        }
        feeVisible = visible;
        if (feeVisibilityAnimator != null) {
            feeVisibilityAnimator.cancel();
            feeVisibilityAnimator = null;
        }
        final float target = visible ? 1.0f : 0.0f;
        if (!(layoutShown && isAttachedToWindow())) {
            setFeeVisibilityProgress(target);
            return;
        }
        feeVisibilityAnimator = ValueAnimator.ofFloat(feeVisibilityProgress, target);
        feeVisibilityAnimator.setDuration(320);
        feeVisibilityAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        feeVisibilityAnimator.addUpdateListener(animation -> setFeeVisibilityProgress((float) animation.getAnimatedValue()));
        feeVisibilityAnimator.start();
    }

    private void setFeeVisibilityProgress(float progress) {
        feeVisibilityProgress = progress;
        feeView.setAlpha(progress);
        feeView.setVisibility(progress > 0 ? View.VISIBLE : View.GONE);
        // Keep footer growth in sync with the fade and the center's pre-draw geometry.
        requestLayout();
    }

    private void updateBalance() {
        final GramWallet wallet = GramWallet.getInstance(currentAccount);
        walletStrip.setDisplayCurrency(wallet,inputInCurrency);
        try{com.negzero.monogram.wallet.MonogramWallet backend=wallet.backend();walletStrip.bind(backend.wallets(),backend.walletId);}catch(Exception ignored){}
        if (!wallet.isReady()) {

            updateFeeVisibility(false);
            return;
        }

        final long minAmount = 1L;
        feeEmulationAvailable = !wallet.isGaslessAvailable() && wallet.getSpendableBalance() >= minAmount;
        hasEnteredAmount |= !TextUtils.isEmpty(amountEdit.getText());
        final boolean lockedFunds=wallet.getBalance()>wallet.getSpendableBalance();
        updateFeeVisibility(lockedFunds || !requestMode && feeEmulationAvailable && hasEnteredAmount);
        if(lockedFunds){
            feeView.setText("Available: " + GramWallet.formatGrams(wallet.getSpendableBalance()) + " XMR · awaiting confirmations");
        } else if (recipientAddress == null && !loadingAddress && !requestMode) {
            feeView.setText("Network fee calculated on review");
        } else if (fee >= 0) {
            feeView.setText(LocaleController.formatSpannable(R.string.WalletNetworkFeeAmount, GramWallet.formatGramsWithUnit(fee)));
        } else {
            feeView.setText(LocaleController.formatSpannable(R.string.WalletNetworkFeeAmount, feeViewLoading));
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.walletUpdate) {
            final boolean couldEmulateFee = feeEmulationAvailable;
            updateAmount();
            if (couldEmulateFee != feeEmulationAvailable) {
                // Resume after a top-up, or cancel an estimate that can no longer succeed.
                loadFee();
            }
        }
    }

    private void suppressAmountKeyboardAfterAuthorization() {
        if (!amountKeyboardSuppressed || destroyed) return;
        pendingKeyboardRequest = false;
        // Authentication temporarily takes window focus. Do not let Android or
        // a delayed makeFocusable callback restore this editor/IME on return.
        amountEdit.setShowSoftInputOnFocus(false);
        amountEdit.setFocusable(false);
        amountEdit.clearFocus();
        setFocusableInTouchMode(true);
        requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (!hasWindowFocus) suppressAmountKeyboardAfterAuthorization();
        super.onWindowFocusChanged(hasWindowFocus);
    }

    @Override
    public void onPause() {
        finishIntroAnimation();
        suppressAmountKeyboardAfterAuthorization();
        super.onPause();
    }

    @Override
    public void onShow(ChatAttachAlert.AttachAlertLayout previousLayout) {
        if (walletAccess == null) {
            try { walletAccess = new WalletAccessController(parentAlert.getBaseFragment(), currentAccount, 9634, this::updateWalletAccess); }
            catch (Exception error) { BulletinFactory.global().showForError(error.getMessage()); }
        }
        if (walletAccess != null) walletAccess.resume();
        finishIntroAnimation();
        lockPortraitIfNeeded();
        layoutShown = false;
        amountKeyboardSuppressed = false;
        amountEdit.setFocusableInTouchMode(true);
        amountEdit.setShowSoftInputOnFocus(true);
        pendingKeyboardRequest = false;
        panTransitionInProgress = false;
        finishTransitionOnLayout = false;
        center.setTranslationY(0);
        bottomContainer.setTranslationY(0);
        recipientView.setTranslationY(0);
        recipientView.setAlpha(1f);
        hasSettledGeometry = false;
        parentAlert.actionBar.setTitle(getString(R.string.WalletSendMoneyTo));
        parentAlert.actionBar.setDrawGlassTitle(false);
        moreItem.setVisibility(View.VISIBLE);
//        inlineMoreItem.setVisibility(View.VISIBLE);
//        inlineMoreItem.bringToFront();
        amountEdit.clearFocus();
        loadFee();
        introProgress = introRevealProgress = amountInput.getDiamondView().canAnimateIntro() ? 0f : 1f;
        updateIntroGeometry();
        if (introProgress < 1f) {
            amountInput.getDiamondView().whenReady(this::startIntroAnimation);
        }
    }

    private void startIntroAnimation() {
        if (destroyed || introProgress >= 1f || introAnimator != null) return;
        introAnimator = new SpringAnimation(new FloatValueHolder(introProgress));
        introAnimator.setSpring(new SpringForce(1f).setDampingRatio(.55f).setStiffness(65f));
        introAnimator.setMinimumVisibleChange(.001f);
        introAnimator.addUpdateListener((animation, value, velocity) -> {
            introProgress = value;
            updateIntroGeometry();
        });
        introAnimator.addEndListener((animation, canceled, value, velocity) -> {
            introAnimator = null;
            introProgress = 1f;
            updateIntroGeometry();
        });
        introAnimator.start();
    }

    private void finishIntroAnimation() {
        amountInput.getDiamondView().whenReady(null);
        if (introAnimator != null) introAnimator.cancel();
        introProgress = 1f;
        updateIntroGeometry();
    }

    private void updateIntroGeometry() {
        introRevealProgress = Math.max(introRevealProgress, Math.max(0f, Math.min(1f, introProgress)));
        final float remaining = 1f - introRevealProgress;
        amountInput.setIntroProgress(introProgress, introRevealProgress, introAnimator == null && introProgress == 1f);
        amountInput.setTranslationY((center.getHeight() / 2f - amountInput.getTop()
                - amountInput.getHeight() / 2f) * (1f - introProgress));
        currencyView.setAlpha(introRevealProgress);
        currencyView.setTranslationY(dp(16) * remaining);
        centerBottomContainer.setAlpha(introRevealProgress);
        centerBottomContainer.setTranslationY(dp(16) * remaining);
        bottomIntroContainer.setAlpha(introRevealProgress);
        bottomIntroContainer.setTranslationY(dp(24) * remaining);
    }

    @Override
    public void onShown() {
        layoutShown = true;
        post(() -> {
            if (!layoutShown || destroyed) {
                return;
            }
            rememberSettledGeometry();
            if (pendingKeyboardRequest) {
                requestAmountKeyboard();
            }
        });
    }

    @Override
    public void onMenuItemClick(int id) {
        if (id == 2) {
            WalletActivity.openWalletQRAlert(getContext(), currentAccount, resourcesProvider);
        } else if (id == 3) {
            openAddCommentAlert();
        }
    }

    @Override
    public boolean onBackPressed() {
        if (parentAlert.sizeNotifierFrameLayout.measureKeyboardHeight() > dp(20)) {
            pendingKeyboardRequest = false;
            cancelPendingCommentAlert();
            amountEdit.clearFocus();
            AndroidUtilities.hideKeyboard(amountEdit);
            return true;
        }
        return super.onBackPressed();
    }

    @Override
    public boolean shouldRestoreKeyboardOnDismiss() {
        return !amountKeyboardSuppressed;
    }

    @Override
    public void onHide() {
        if (paymentLinkFlow != null) { paymentLinkFlow.close(); paymentLinkFlow = null; }
        if (walletAccess != null) { walletAccess.close(); walletAccess = null; }
        finishIntroAnimation();
        restoreOrientation();
        parentAlert.actionBar.setDrawGlassTitle(true);
        layoutShown = false;
        pendingKeyboardRequest = false;
        cancelPendingCommentAlert();
        if (commentDialog != null) {
            commentDialog.dismiss();
        }
        moreItem.setVisibility(View.GONE);
//        inlineMoreItem.setVisibility(View.GONE);
        AndroidUtilities.hideKeyboard(amountEdit);
        amountEdit.clearFocus();
    }

    private void lockPortraitIfNeeded() {
        // The shorter display side is the height in landscape, regardless of current rotation.
        if (portraitLockedActivity != null || AndroidUtilities.isTablet()
                || Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) >= dp(700)) {
            return;
        }
        Activity activity = AndroidUtilities.findActivity(getContext());
        if (activity == null) {
            return;
        }
        try {
            previousOrientation = activity.getRequestedOrientation();
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            portraitLockedActivity = activity;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void restoreOrientation() {
        if (portraitLockedActivity == null) {
            return;
        }
        try {
            portraitLockedActivity.setRequestedOrientation(previousOrientation);
        } catch (Exception e) {
            FileLog.e(e);
        }
        portraitLockedActivity = null;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        final float actionBarAlpha = parentAlert.actionBar.getAlpha();
        final float inlineAlpha = 1.0f - actionBarAlpha;
//        inlineMoreItem.setAlpha(inlineAlpha);
//        inlineMoreItem.setScaleX(0.8f + 0.2f * inlineAlpha);
//        inlineMoreItem.setScaleY(0.8f + 0.2f * inlineAlpha);
//        inlineMoreItem.setClickable(inlineAlpha > 0.5f);
        updateCenterGeometry();
        if (introAnimator != null || introProgress < 1f) updateIntroGeometry();
        super.dispatchDraw(canvas);
        rememberSettledGeometry();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (finishTransitionOnLayout) {
            finishTransitionOnLayout = false;
            center.setTranslationY(0);
            bottomContainer.setTranslationY(0);
            recipientView.setTranslationY(0);
            rememberSettledGeometry();
        } else if (panTransitionInProgress) {
            updateKeyboardTransitionPositions();
        }
        updateBodyViewport();
        updateCenterGeometry();
    }

    private void updateBodyViewport(){
        int height=contentScroll.getHeight();if(height<=0)return;
        expandScrollRange=Math.max(0,contentContainer.getPaddingTop()-dp(12));
        int minimum=height+expandScrollRange;
        if(contentContainer.getMinimumHeight()!=minimum)contentContainer.setMinimumHeight(minimum);
        boolean ready=walletAccess!=null && walletAccess.ready();
        int amountMinimum=ready ? 0 : Math.max(dp(48),height-expandScrollRange-dp(88)-dp(44)+Math.min(contentScroll.getScrollY(),expandScrollRange));
        if(amountArea.getMinimumHeight()!=amountMinimum)amountArea.setMinimumHeight(amountMinimum);
    }

    @Override public void onButtonsTranslationYUpdated(){
        int inset=keyboardVisible ? 0 : parentAlert.getVisibleTypeButtonsInset();
        if(inset!=lastTrayInset){lastTrayInset=inset;requestLayout();}
    }

    @Override
    public int needsActionBar() {
        return 1;
    }

    @Override
    public int getButtonsHideOffset() {
        return dp(62);
    }

    @Override
    public int getListTopPadding() {
        final FrameLayout.LayoutParams layoutParams =
                (FrameLayout.LayoutParams) contentScroll.getLayoutParams();
        return layoutParams == null ? 0 : layoutParams.topMargin+contentContainer.getPaddingTop()-dp(12);
    }

    @Override
    public int getFirstOffset() {
        return getListTopPadding() + dp(5);
    }

    @Override
    public int getCurrentItemTop() {
        int top = contentScroll.getTop()+contentContainer.getPaddingTop()-dp(12)-contentScroll.getScrollY();
        if (top <= 0) {
            top = getListTopPadding();
        }
        top -= AndroidUtilities.statusBarHeight + dp(12);
        return Math.max(0, top) + dp(13);
    }

    @Override
    public void onPreMeasure(int availableWidth, int availableHeight) {
        keyboardVisible =
                parentAlert.sizeNotifierFrameLayout.measureKeyboardHeight() > dp(20);
        occupyNavigationBar = keyboardVisible;
        final int tabsReservedHeight = keyboardVisible ? 0 : parentAlert.getVisibleTypeButtonsInset();
        lastTrayInset=tabsReservedHeight;
        final boolean composerReady=walletAccess!=null && walletAccess.ready();
        final int footerHeight = dp(60)+(composerReady && sendButtonVisible ? dp(60) : 0)+Math.round(dp(48)*feeVisibilityProgress);

        final FrameLayout.LayoutParams bottomLayoutParams =
                (FrameLayout.LayoutParams) bottomContainer.getLayoutParams();
        // Keep the wallet footer's internal geometry fixed. Reserving tabs with
        // padding made the send button jump independently of the balance label.
        bottomLayoutParams.height = footerHeight;
        bottomLayoutParams.bottomMargin = tabsReservedHeight;

        int padding;
        // A note needs the same expanded sheet space as the amount keyboard.
        // Grow the attachment surface instead of shrinking the entire amount/note group.
        if (keyboardVisible || !TextUtils.isEmpty(comment)) {
            padding = dp(56);
            parentAlert.setAllowNestedScroll(true);
        } else if (!AndroidUtilities.isTablet()
                && AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            padding = (int) (availableHeight / 3.5f);
            parentAlert.setAllowNestedScroll(true);
        } else {
            padding = availableHeight / 5 * 2;
            parentAlert.setAllowNestedScroll(true);
        }
        padding = Math.max(0, padding - dp(1)) + AndroidUtilities.statusBarHeight;

        final FrameLayout.LayoutParams layoutParams =
                (FrameLayout.LayoutParams) contentScroll.getLayoutParams();
        final int bottomMargin=footerHeight+tabsReservedHeight;
        // The collapsed space belongs to scrollable content, not a fixed viewport
        // margin. Scrolling therefore lifts the entire body up to the sticky header.
        final int viewportTop=ActionBar.getCurrentActionBarHeight()+dp(8);
        contentContainer.setPadding(0,Math.max(dp(12),padding-viewportTop+dp(12)),0,dp(32));
        if (layoutParams.topMargin != viewportTop || layoutParams.bottomMargin!=bottomMargin) {
            layoutParams.topMargin = viewportTop;
            layoutParams.bottomMargin=bottomMargin;
            contentScroll.setLayoutParams(layoutParams);
        }
        expandScrollRange=Math.max(0,contentContainer.getPaddingTop()-dp(12));
        updateBodyViewport();

        updateCenterBottom();
    }

    @Override
    public void onPanTransitionStart(boolean keyboardVisible, int contentHeight) {
        panTransitionInProgress = true;
        finishTransitionOnLayout = false;
        panTransitionOpening = keyboardVisible;
        keyboardTransitionProgress = keyboardVisible ? 0.0f : 1.0f;
        transitionFromBottomY = hasSettledGeometry ? settledBottomY : getBottomYOnScreen();
        transitionFromRecipientY = hasSettledGeometry ? settledRecipientY : getScreenY(recipientView);
        transitionToBottomY = getBottomYOnScreen() - bottomContainer.getTranslationY();
        transitionToRecipientY = getScreenY(recipientView) - recipientView.getTranslationY();
        updateKeyboardTransitionPositions();
    }

    @Override
    public void onPanTransitionEnd() {
        if (!panTransitionInProgress) {
            return;
        }
        panTransitionInProgress = false;
        final float bottomBaseY = getBottomYOnScreen() - bottomContainer.getTranslationY();
        bottomContainer.setTranslationY(transitionToBottomY - bottomBaseY);
        final float recipientBaseY = getScreenY(recipientView) - recipientView.getTranslationY();
        recipientView.setTranslationY(transitionToRecipientY - recipientBaseY);
        finishTransitionOnLayout = true;
        requestLayout();
    }

    @Override
    public void onContainerTranslationUpdated(
            float currentPanTranslationY,
            float progress,
            boolean keyboardVisible
    ) {
        canvasPanTranslationY = currentPanTranslationY;
        keyboardTransitionProgress = progress;
        updateKeyboardTransitionPositions();
    }

    private void rememberSettledGeometry() {
        if (!layoutShown
                || panTransitionInProgress
                || finishTransitionOnLayout
                || !isAttachedToWindow()
                || Math.abs(getTranslationY()) >= 0.5f
                || center.getHeight() <= 0
                || bottomContainer.getHeight() <= 0) {
            return;
        }
        settledBottomY = getBottomYOnScreen();
        settledRecipientY = getScreenY(recipientView);
        hasSettledGeometry = true;
    }

    private void updateCenterGeometry() {
        center.setScaleX(1f);center.setScaleY(1f);center.setTranslationY(0f);
    }

    private float getVisibleTopOnScreen(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0) {
            return Float.POSITIVE_INFINITY;
        }
        // Center against the footer's destination, not its temporary intro offset.
        return getScreenY(view) - bottomIntroContainer.getTranslationY()
                + view.getPivotY() * (1.0f - view.getScaleY());
    }

    private float getBottomYOnScreen() {
        return getScreenY(bottomContainer);
    }

    private float getScreenY(View view) {
        float y = 0;
        while (view.getParent() instanceof View) {
            final View parent = (View) view.getParent();
            y += view.getY() - parent.getScrollY();
            view = parent;
        }
        view.getLocationOnScreen(screenLocation);
        // Keep keyboard geometry independent of the sheet's exit, otherwise
        // its compensation pins the recipient/footer on screen during dismissal.
        return y + screenLocation[1] + canvasPanTranslationY - parentAlert.getGramSendExitTranslationY();
    }

    private void updateKeyboardTransitionPositions() {
        if (!panTransitionInProgress || center.getHeight() <= 0 || bottomContainer.getHeight() <= 0) {
            return;
        }
        final float progress = panTransitionOpening
                ? keyboardTransitionProgress
                : 1.0f - keyboardTransitionProgress;
        final float desiredBottomY = transitionFromBottomY
                + (transitionToBottomY - transitionFromBottomY) * progress;
        final float desiredRecipientY = transitionFromRecipientY
                + (transitionToRecipientY - transitionFromRecipientY) * progress;

        final float bottomBaseY = getBottomYOnScreen() - bottomContainer.getTranslationY();
        bottomContainer.setTranslationY(desiredBottomY - bottomBaseY);
        final float recipientBaseY = getScreenY(recipientView) - recipientView.getTranslationY();
        recipientView.setTranslationY(desiredRecipientY - recipientBaseY);
        updateCenterGeometry();
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        parentAlert.getSheetContainer().invalidate();
    }

    @Override
    public boolean hasCustomBackground() {
        return true;
    }

    @Override
    public int getCustomBackground() {
        return getThemedColor(Theme.key_windowBackgroundWhite);
    }

    @Override
    public boolean disableTopFade() {
        return true;
    }

    @Override
    public boolean disableBottomFade() {
        return true;
    }

    @Override
    public void onDestroy() {
        if (paymentLinkFlow != null) { paymentLinkFlow.close(); paymentLinkFlow = null; }
        if (walletAccess != null) { walletAccess.close(); walletAccess = null; }
        if (destroyed) {
            return;
        }
        destroyed = true;
        finishIntroAnimation();
        if (sendTransition != null) {
            sendTransition.cancel();
            sendTransition = null;
        }
        if (walletSendTransition != null) {
            walletSendTransition.cancel();
            walletSendTransition = null;
        }
        if (feeVisibilityAnimator != null) {
            feeVisibilityAnimator.cancel();
            feeVisibilityAnimator = null;
        }
        restoreOrientation();
        centerBottom.animate().cancel();
        tapToSetAmount.animate().cancel();
        insufficientFundsView.animate().cancel();
        sendButton.animate().cancel();
        walletStrip.animate().cancel();
        feeView.animate().cancel();
        cancelPendingCommentAlert();
        if (commentDialog != null) {
            commentDialog.dismiss();
        }
        if (cancelFeeLoading != null) {
            cancelFeeLoading.run();
            cancelFeeLoading = null;
        }
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.walletUpdate);
        final ActionBarMenu menu = parentAlert.actionBar.createMenu();
        menu.removeView(moreItem);
        AndroidUtilities.hideKeyboard(amountEdit);
    }
}
