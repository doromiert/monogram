package org.telegram.ui.Wallet;

import org.telegram.ui.MonogramWalletAuth;
import com.negzero.monogram.wallet.MonogramWallet;
import com.negzero.monogram.wallet.WalletVault;
import org.telegram.messenger.xmpp.MonogramXmr;
import android.content.Intent;
import android.view.WindowManager;
import android.text.InputType;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.messenger.LocaleController;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.getAccountInProduction;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Rect;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.BackupImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EllipsizeSpanAnimator;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Wallet.WalletActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WalletImportFragment extends BaseFragment {

    private int currentCount = 25;
    private MonogramWalletAuth authentication;
    private MonogramWallet backend;
    private EditTextBoldCursor restoreHeight, seedPassphrase;
    private boolean resumed, secureWasSet;
    private boolean destroyed;

    private WalletActivity.TabsView tabs;
    private LinearLayout cellsContainer;
    private ButtonWithCounterView importButton;
    private ImageView closeButton;
    private LinearLayout rootView;
    private ScrollView scrollView;
    private LinkSpanDrawable.LinksTextView subtitleView;
    private int keyboardInset;
    private boolean wipe;
    private byte[] matchPublicKey;

    public WalletImportFragment setWipe(boolean wipe) {
        this.wipe = wipe;
        return this;
    }

    public WalletImportFragment setMatchPublicKey(byte[] publicKey) {
        this.matchPublicKey = publicKey;
        return this;
    }

    private final ArrayList<WalletWordCell> cells = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        actionBar.setOccupyStatusBar(false);

        fragmentView = new SizeNotifierFrameLayout(context);
        ((SizeNotifierFrameLayout) fragmentView).setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(96), 0, 0);
        this.scrollView = scrollView;
        scrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top < oldBottom - oldTop) {
                for (WalletWordCell cell : cells) {
                    if (cell.hasFocus()) {
                        // Recheck focus after the keyboard has reduced the actual viewport.
                        cell.requestRectangleOnScreen(new Rect(0, 0, cell.getWidth(), cell.getHeight()), true);
                        break;
                    }
                }
            }
        });
        ((FrameLayout) fragmentView).addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(36), 0, dp(36), dp(24));
        root.setClipToPadding(false);
        rootView = root;
        scrollView.addView(root, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        closeButton = new ImageView(context);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider), android.graphics.PorterDuff.Mode.SRC_IN));
        closeButton.setImageResource(R.drawable.ic_ab_close);
        closeButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 3));
        closeButton.setOnClickListener(v -> finishFragment());
        ((FrameLayout) fragmentView).addView(closeButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.LEFT, 4, 12, 0, 0));

        BackupImageView stickerView = new BackupImageView(context);
        stickerView.setAspectFit(true);
        stickerView.getImageReceiver().setCurrentAccount(getAccountInProduction());
        MediaDataController.getInstance(getAccountInProduction()).setPlaceholderImage(stickerView, "RestrictedEmoji", "\uD83D\uDCDD", "100_100");
        stickerView.getImageReceiver().setAutoRepeatCount(1);
        root.addView(stickerView, LayoutHelper.createLinear(100, 100, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        titleView.setText(matchPublicKey != null ? LocaleController.getString(R.string.WalletSecretPhrase) : LocaleController.getString(R.string.WalletImport));
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 10));

        subtitleView = new LinkSpanDrawable.LinksTextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourceProvider));
        subtitleView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourceProvider));
        if (matchPublicKey != null) {
            subtitleView.setText(AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.WalletEnterSecretPhrase), () -> {
                new AlertDialog.Builder(getContext(), getResourceProvider())
                    .setMessage(LocaleController.getString(R.string.WalletEnterSecretPhraseInfo))
                    .setPositiveButton(getString(R.string.OK), null)
                    .show();
            }));
        } else {
            subtitleView.setText(LocaleController.formatSpannable(R.string.WalletImportPhraseInfo, 25));
        }
        root.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 8));

        tabs = new WalletActivity.TabsView(context, new CharSequence[] { "25 words", "16 words · Polyseed" }, tab -> {
            setCount(tab == 0 ? 25 : 16);
        }, resourceProvider);
        tabs.shadowed = true;
        tabs.updateColors();
        tabs.setPadding(dp(8), dp(8), dp(8), dp(8));
        tabs.setClipToPadding(false);
        root.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 4));

        cellsContainer = new LinearLayout(context);
        cellsContainer.setOrientation(LinearLayout.VERTICAL);
        cellsContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        cellsContainer.setClipChildren(false);
        root.addView(cellsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        seedPassphrase = new EditTextBoldCursor(context);
        seedPassphrase.setTextSize(16);
        seedPassphrase.setSingleLine(true);
        seedPassphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        seedPassphrase.setHint("Seed passphrase (optional)");
        seedPassphrase.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        seedPassphrase.setSaveEnabled(false);
        seedPassphrase.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        seedPassphrase.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        root.addView(seedPassphrase, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        restoreHeight = new EditTextBoldCursor(context);
        restoreHeight.setTextSize(16);
        restoreHeight.setSingleLine(true);
        restoreHeight.setInputType(InputType.TYPE_CLASS_NUMBER);
        restoreHeight.setHint("Restore height (optional)");
        restoreHeight.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        restoreHeight.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        root.addView(restoreHeight, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        importButton = new ButtonWithCounterView(context, resourceProvider);
        importButton.setRound();
        importButton.setText(LocaleController.getString(R.string.WalletImportButton));
        importButton.setEnabled(false);
        importButton.setOnClickListener(v -> onImportPressed());
        root.addView(importButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));

        buildCells(context);
        updateImportButton();

        return fragmentView;
    }

    private void setCount(int count) {
        if (currentCount == count) {
            return;
        }
        currentCount = count;
        buildCells(getContext());
        updateImportButton();

        subtitleView.setText(matchPublicKey != null ? LocaleController.formatSpannable(R.string.WalletImportCurrentPhraseInfo, count) : LocaleController.formatSpannable(R.string.WalletImportPhraseInfo, count));
    }

    private void rebuildCells(Context context) {
        cellsContainer.removeAllViews();
        cells.clear();
        for (int i = 0; i < currentCount; i++) {
            final int index = i;
            WalletWordCell cell = new WalletWordCell(context, i, i == 0, resourceProvider);
            cell.setOnTextChangedListener(this::updateImportButton);
            cell.setOnPasteListener(() -> fillRemainingFromClipboard(index));
            cell.setOnNextListener(() -> {
                if (index + 1 < cells.size()) {
                    cells.get(index + 1).requestFieldFocus();
                } else {
                    AndroidUtilities.hideKeyboard(cell);
                }
            });
            cellsContainer.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, Gravity.CENTER_HORIZONTAL, 0, i == 0 ? 0 : 12, 0, 0));
            cells.add(cell);
        }
    }

    private void buildCells(Context context) {
        ArrayList<String> existing = new ArrayList<>();
        for (WalletWordCell cell : cells) {
            existing.add(cell.getWord());
        }
        rebuildCells(context);
        for (int i = 0; i < existing.size() && i < cells.size(); i++) {
            if (!existing.get(i).isEmpty()) {
                cells.get(i).setText(existing.get(i));
            }
        }
    }

    private void fillRemainingFromClipboard(int startIndex) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || clipboard.getPrimaryClip() == null || clipboard.getPrimaryClip().getItemCount() == 0) {
                return;
            }
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (text == null) return;
            String[] parts = text.toString().trim().toLowerCase(java.util.Locale.ROOT).split("\\s+");
            int p = 0;
            for (int c = startIndex; c < cells.size() && p < parts.length; c++, p++) {
                cells.get(c).setText(parts[p]);
            }
            updateImportButton();
        } catch (Exception e) {
            // ignore
        }
    }

    private void updateImportButton() {
        boolean allFilled = true;
        for (WalletWordCell cell : cells) {
            if (cell.isEmpty()) {
                allFilled = false;
                break;
            }
        }
        importButton.setEnabled(allFilled);
    }

    private void onImportPressed() {
        if (importButton.isLoading()) return;
        final long height;
        try {
            String value = restoreHeight.getText().toString().trim();
            height = value.isEmpty() ? 0 : Long.parseLong(value);
            if (height < 0) throw new IllegalArgumentException();
            if (backend == null) backend = GramWallet.getInstance(currentAccount).backend();
            if (backend.exists()) throw new IllegalStateException("A wallet already exists. Lock and unlock it instead.");
            if (authentication == null) authentication = new MonogramWalletAuth(this, backend, 9633);
        } catch (Exception error) { showError(error.getMessage() == null ? "Enter a valid restore height." : error.getMessage()); return; }
        importButton.setLoading(true);
        if (resumed) authentication.resume();
        authentication.authenticate("Restore Monero wallet", "Protect the wallet with your device credential", false, (approved, error) -> {
            if (!approved) {
                importButton.setLoading(false);
                if (error != null) showError(error.getMessage());
                return;
            }
            char[] seed = null, passphrase = null;
            WalletVault.Authorization authorization = null;
            try {
                StringBuilder words = new StringBuilder();
                for (WalletWordCell cell : cells) {
                    if (words.length() > 0) words.append(' ');
                    words.append(cell.getWord().trim());
                }
                seed = new char[words.length()];
                words.getChars(0, words.length(), seed, 0);
                for (int i = 0; i < words.length(); i++) words.setCharAt(i, '\0');
                passphrase = new char[seedPassphrase.length()];
                android.text.TextUtils.getChars(seedPassphrase.getText(), 0, passphrase.length, passphrase, 0);
                authorization = backend.authorizeRestore();
                backend.restore(authorization, seed, passphrase, currentCount == 16, height, (value, failure) -> {
                    if (destroyed) return;
                    importButton.setLoading(false);
                    if (failure != null) { showError(failure.getMessage()); return; }
                    clearPhrase();
                    GramWallet.getInstance(currentAccount).invalidateState();
                    finishFragment();
                });
            } catch (Exception failure) {
                if (seed != null) Arrays.fill(seed, '\0');
                if (passphrase != null) Arrays.fill(passphrase, '\0');
                if (authorization != null) authorization.close();
                importButton.setLoading(false);
                showError(failure.getMessage());
            }
        });
    }

    private void clearPhrase() {
        for (WalletWordCell cell : cells) cell.setText("");
        if (seedPassphrase != null) seedPassphrase.setText("");
    }

    @Override
    public boolean onFragmentCreate() {
        try (MonogramXmr settings = new MonogramXmr(currentAccount)) { settings.enabled(true); }
        catch (Exception unavailable) { return false; }
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (authentication != null) authentication.resume();
        if (getParentActivity() != null) {
            secureWasSet = (getParentActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
            getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    public void onPause() {
        resumed = false;
        if (authentication != null) authentication.pause();
        if (!secureWasSet && getParentActivity() != null) getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onPause();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (authentication != null && authentication.activityResult(requestCode, resultCode)) return;
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (authentication != null) authentication.destroy();
        clearPhrase();
        super.onFragmentDestroy();
    }

    public static CharSequence formatAddressTwoLines(String address) {
        if (address == null)
            return null;
        final SpannableStringBuilder formatted = new SpannableStringBuilder();
        int from = 0;
        for (int i = 0, j = 0; i < address.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append(i % (6*4) == 0 ? '\n' : ' ');
                if (i % (6*4) == 0) j++;
                from = formatted.length();
            }
            formatted.append(address.charAt(i));
            if (i % 4 == 3) {
                if (j++ % 2 == 1) {
                    formatted.setSpan(new EllipsizeSpanAnimator.TextAlphaSpan(0.75f), from, formatted.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                from = formatted.length();
            }
        }
        return formatted;
    }

    private void showError(String text) {
        if (getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(this)
            .createErrorBulletin(text, resourceProvider)
            .show();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public WindowInsetsCompat onInsetsInternal(@NonNull View view, @NonNull WindowInsetsCompat windowInsets) {
        keyboardInset = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        return super.onInsetsInternal(view, windowInsets);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (scrollView != null) {
            scrollView.setPadding(left, top + dp(96), right, 0);
            // Padding alone leaves covered fields inside ScrollView's focus viewport.
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scrollView.getLayoutParams();
            int bottomMargin = Math.max(bottom, keyboardInset);
            if (lp.bottomMargin != bottomMargin) {
                lp.bottomMargin = bottomMargin;
                scrollView.setLayoutParams(lp);
            }
        }
        if (closeButton != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) closeButton.getLayoutParams();
            if (lp != null) {
                lp.topMargin = top + dp(12);
                closeButton.setLayoutParams(lp);
            }
        }
    }
}
