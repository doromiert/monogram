/*
 * Adapted from Telegram for Android's LoginActivity (PhoneView and PasswordView).
 * Copyright Nikolai Kudashov, 2013-2018. GNU GPL v. 2 or later; see LICENSE.
 */
package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import java.util.ArrayList;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.*;

public class MonogramLoginActivity extends BaseFragment {
    private boolean connecting, destroyed;
    private JabberView jabberView;
    private FragmentFloatingButton floatingButton;
    private ImageView backButtonView;
    private Bundle savedFields;
    private com.negzero.monogram.xmpp.RegistrationInvitation invitation;
    private boolean accountCreated;

    public MonogramLoginActivity() { super(); }
    public MonogramLoginActivity(int account) { super(); currentAccount = account; }
    public MonogramLoginActivity(int account,com.negzero.monogram.xmpp.RegistrationInvitation invitation) { this(account);this.invitation=invitation; }
    public boolean isConnecting() { return connecting; }

    @Override public boolean onFragmentCreate() {
        return !UserConfig.getInstance(currentAccount).isClientActivated() && super.onFragmentCreate();
    }

    @Override public View createView(Context context) {
        actionBar.setAddToContainer(false);
        SizeNotifierFrameLayout layout = new SizeNotifierFrameLayout(context);
        fragmentView = layout;
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, 0, 0, dp(84));
        layout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout keyboardLinearLayout = new LinearLayout(context);
        keyboardLinearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(keyboardLinearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
        Space spacer = new Space(context);
        spacer.setMinimumHeight(AndroidUtilities.isTablet() ? 0 : AndroidUtilities.statusBarHeight);
        keyboardLinearLayout.addView(spacer);
        jabberView = new JabberView(context);
        keyboardLinearLayout.addView(jabberView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                AndroidUtilities.isTablet() ? 26 : 18, 30, AndroidUtilities.isTablet() ? 26 : 18, 0));

        floatingButton = new FragmentFloatingButton(context, resourceProvider);
        layout.addView(floatingButton, FragmentFloatingButton.createDefaultLayoutParamsBig());
        VerticalPositionAutoAnimator.attach(floatingButton);
        TransformableLoginButtonView floatingButtonIcon = new TransformableLoginButtonView(context);
        floatingButtonIcon.setTransformType(TransformableLoginButtonView.TRANSFORM_OPEN_ARROW);
        floatingButtonIcon.setProgress(1f);
        floatingButtonIcon.setDrawBackground(false);
        floatingButton.addView(floatingButtonIcon, LayoutHelper.createFrame(56, 56, Gravity.CENTER));
        floatingButton.addAdditionalView(floatingButtonIcon);
        floatingButton.setContentDescription(getString(R.string.Continue));
        floatingButton.setOnClickListener(view -> jabberView.onNextPressed(null));

        backButtonView = new ImageView(context);
        backButtonView.setImageResource(R.drawable.ic_ab_back);
        backButtonView.setContentDescription(getString(R.string.Back));
        backButtonView.setPadding(dp(8), dp(8), dp(8), dp(8));
        layout.addView(backButtonView, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.TOP, 8,
                8 + (AndroidUtilities.isTablet() ? 0 : AndroidUtilities.statusBarHeight / AndroidUtilities.density), 0, 0));
        backButtonView.setVisibility(UserConfig.getActivatedAccountsCount() > 0 ? View.VISIBLE : View.GONE);
        backButtonView.setOnClickListener(v -> { if (onBackPressed(true)) finishFragment(); });
        if (savedFields != null) restoreSelfArgs(savedFields);
        else if(invitation!=null) prefillInvitation();
        applyMode();
        setConnecting(connecting);
        updateColors();
        return fragmentView;
    }

    private void setConnecting(boolean value) {
        connecting = value;
        if (floatingButton == null) return;
        floatingButton.setProgressVisible(value, true);
        floatingButton.setEnabled(!value);
        for (EditTextBoldCursor field : jabberView.fields) field.setEnabled(!value);
        jabberView.advancedButton.setEnabled(!value);
        jabberView.invitationButton.setEnabled(!value);
        jabberView.fields[0].setEnabled(!value && (invitation==null || invitation.username==null));
    }

    private void updateColors() {
        if (fragmentView == null) return;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        jabberView.updateColors();
        backButtonView.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), android.graphics.PorterDuff.Mode.MULTIPLY);
        floatingButton.updateColors();
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        return SimpleThemeDescription.createThemeDescriptions(this::updateColors, Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteGrayText6,
                Theme.key_windowBackgroundWhiteBlueText4, Theme.key_windowBackgroundWhiteInputField,
                Theme.key_windowBackgroundWhiteInputFieldActivated, Theme.key_featuredStickers_addButton);
    }
    @Override public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }
    @Override public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !connecting && jabberView != null) jabberView.fields[0].requestFocus();
    }
    @Override public boolean onBackPressed(boolean invoked) { return !connecting && super.onBackPressed(invoked); }
    @Override public void onFragmentDestroy() {
        destroyed = true;
        if (jabberView != null) jabberView.fields[1].setText("");
        super.onFragmentDestroy();
    }
    @Override public void saveSelfArgs(Bundle args) {
        if (jabberView == null) return;
        args.putString("monogram_jid", jabberView.fields[0].getText().toString());
        args.putString("monogram_host", jabberView.fields[2].getText().toString());
        args.putString("monogram_port", jabberView.fields[3].getText().toString());
        args.putBoolean("monogram_advanced", jabberView.advancedFields.getVisibility() == View.VISIBLE);
        args.putInt("monogram_account",currentAccount);args.putBoolean("monogram_account_created",accountCreated);
        args.remove("monogram_invite");
        if(invitation!=null) try { args.putString("monogram_invite",MonogramController.get(currentAccount).sealRegistrationInvite(invitation.uri)); }
        catch(Exception unavailable) { /* Reopen the invite rather than save a token without Keystore protection. */ }
    }
    @Override public void restoreSelfArgs(Bundle args) {
        savedFields = args;
        if (jabberView == null) return;
        jabberView.fields[0].setText(args.getString("monogram_jid", ""));
        jabberView.fields[2].setText(args.getString("monogram_host", ""));
        jabberView.fields[3].setText(args.getString("monogram_port", ""));
        jabberView.showAdvanced(args.getBoolean("monogram_advanced"));
        accountCreated=args.getBoolean("monogram_account_created");
        String sealed=args.getString("monogram_invite");
        if(sealed!=null) {
            try { invitation=MonogramController.get(currentAccount).openRegistrationInvite(sealed); }
            catch(Exception unavailable) { invitation=null;jabberView.error(0,"Reopen your invitation link to continue registration."); }
        }
        if(invitation!=null && invitation.username!=null) jabberView.fields[0].setText(invitation.username+"@"+invitation.server);
        applyMode();
    }

    private void prefillInvitation() {
        jabberView.fields[1].setText("");jabberView.fields[2].setText("");jabberView.fields[3].setText("");jabberView.showAdvanced(false);
        jabberView.fields[0].setText((invitation.username==null ? "" : invitation.username.toString())+"@"+invitation.server);
        jabberView.fields[0].setSelection(invitation.username==null ? 0 : jabberView.fields[0].length());
    }
    private void applyMode() {
        if(jabberView==null) return;
        boolean registering=invitation!=null;
        jabberView.titleView.setText(registering ? "Create your XMPP account" : getString(R.string.MonogramLoginTitle));
        jabberView.confirmTextView.setText(registering ? "Create an account on "+invitation.server+". "+(invitation.username==null ? "Choose a username and password." : "This invitation reserves the address below. Choose your password.")
                : accountCreated ? "Your account was created. Sign in with the address and password you chose." : getString(R.string.MonogramLoginHelp));
        jabberView.outlines[1].setText(registering ? "Choose a password" : getString(R.string.EnterPassword));
        jabberView.invitationButton.setText(registering ? "Sign in instead" : "Use an invitation link");
        if(floatingButton!=null) floatingButton.setContentDescription(registering ? "Create account" : getString(R.string.Continue));
        if(android.os.Build.VERSION.SDK_INT>=26) jabberView.fields[1].setAutofillHints(registering ? "newPassword" : View.AUTOFILL_HINT_PASSWORD);
        setConnecting(connecting);
    }
    private void invitationLink() {
        if(connecting) return;
        if(invitation!=null) { invitation=null;applyMode();return; }
        org.telegram.ui.Cells.EditTextCell input=new org.telegram.ui.Cells.EditTextCell(getContext(),"xmpp: invitation link",false);
        input.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.editText.setSingleLine(true);input.editText.setSaveEnabled(false);
        input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16384)});
        if(android.os.Build.VERSION.SDK_INT>=26) input.editText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext()).setTitle("Use an invitation link")
                .setMessage("Paste the xmpp: link from your server's invitation page. No account is created until you submit the registration screen.")
                .setView(input).setPositiveButton("Continue",(dialog,which) -> {
                    try { invitation=com.negzero.monogram.xmpp.RegistrationInvitation.parse(input.getText().toString().trim());accountCreated=false;prefillInvitation();applyMode(); }
                    catch(com.negzero.monogram.xmpp.XmppInvitations.InvitationException invalid) { jabberView.error(0,invalid.getMessage()); }
                    input.editText.setText("");
                }).setNegativeButton("Cancel",null).create());
    }

    private void login() {
        if (connecting) return;
        String jid = jabberView.fields[0].getText().toString().trim();
        String password = jabberView.fields[1].getText().toString();
        String host = jabberView.fields[2].getText().toString().trim();
        int port;
        try {
            String text = jabberView.fields[3].getText().toString().trim();
            port = text.isEmpty() ? 5222 : Integer.parseInt(text);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            jabberView.showAdvanced(true);
            jabberView.error(3, getString(R.string.MonogramLoginPortError)); return;
        }
        try { new com.negzero.monogram.xmpp.Account(jid, host, port, "Monogram"); }
        catch (Exception invalid) { jabberView.error(0, getString(R.string.MonogramLoginAddressError)); return; }
        if (password.isEmpty()) { jabberView.error(1, getString(R.string.MonogramLoginPasswordError)); return; }
        if(invitation!=null) try { invitation.validate(new com.negzero.monogram.xmpp.Account(jid,host,port,"Monogram")); }
        catch(Exception invalid) { jabberView.error(0,invalid.getMessage());return; }
        setConnecting(true);
        AndroidUtilities.hideKeyboard(jabberView.fields[1]);
        if(invitation!=null) MonogramController.get(currentAccount).register(invitation,jid,password,host,port,(created,error) -> {
            if(created) { invitation=null;accountCreated=true;if(!destroyed) applyMode(); }
            authenticated(error);
        });
        else MonogramController.get(currentAccount).login(jid,password,host,port,this::authenticated);
    }
    private void authenticated(Exception error) {
        connecting = false;
        if (error != null) {
            if (!destroyed && getParentActivity() != null) {
                setConnecting(false);
                String detail=error instanceof com.negzero.monogram.xmpp.XmppInvitations.InvitationException ? error.getMessage()
                        : invitation!=null || accountCreated ? com.negzero.monogram.xmpp.XmppRequestException.describe(error) : getString(R.string.MonogramLoginFailed);
                jabberView.error(1,(accountCreated ? "Account created; sign-in failed. " : "")+detail);
            }
            return;
        }
        TLRPC.User user = MonogramController.get(currentAccount).ownUser();
        UserConfig config = UserConfig.getInstance(currentAccount);
        config.setCurrentUser(user); config.syncContacts = false; config.saveConfig(true);
        ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user);
        MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(currentAccount).putUser(user, false);
        MessagesController.getGlobalMainSettings().edit().putBoolean("monogram_intro_seen", true).apply();
        ApplicationLoader.startPushService();
        if (destroyed || getParentActivity() == null) return;
        jabberView.fields[1].setText("");
        if (UserConfig.selectedAccount != currentAccount && getParentActivity() instanceof LaunchActivity)
            ((LaunchActivity) getParentActivity()).switchToAccount(currentAccount, true);
        else { MainTabsActivity tabs = new MainTabsActivity(); tabs.setCurrentAccount(currentAccount); presentFragment(tabs, true); }
    }

    /** Telegram's password-page layout with Jabber fields in place of phone/SMS authentication. */
    private class JabberView extends SlideView {
        final EditTextBoldCursor[] fields = new EditTextBoldCursor[4];
        final OutlineTextContainerView[] outlines = new OutlineTextContainerView[4];
        final TextView titleView, confirmTextView, advancedButton, errorView,invitationButton;
        final LinearLayout advancedFields;
        JabberView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            FrameLayout lockFrameLayout = new FrameLayout(context);
            RLottieImageView lockImageView = new RLottieImageView(context);
            lockImageView.setAnimation(R.raw.tsv_setup_intro, 120, 120);
            lockImageView.setAutoRepeat(false);
            lockFrameLayout.addView(lockImageView, LayoutHelper.createFrame(120, 120, Gravity.CENTER_HORIZONTAL));
            lockFrameLayout.setVisibility(AndroidUtilities.isSmallScreen() || (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y && !AndroidUtilities.isTablet()) ? GONE : VISIBLE);
            addView(lockFrameLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            lockImageView.playAnimation();
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setText(getString(R.string.MonogramLoginTitle));
            titleView.setGravity(Gravity.CENTER);
            titleView.setLineSpacing(dp(2), 1f);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 16, 32, 0));
            confirmTextView = new TextView(context);
            confirmTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            confirmTextView.setGravity(Gravity.CENTER_HORIZONTAL);
            confirmTextView.setLineSpacing(dp(2), 1f);
            confirmTextView.setText(getString(R.string.MonogramLoginHelp));
            addView(confirmTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 12, 8, 12, 16));
            addField(this, 0, getString(R.string.MonogramLoginAddress), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            addField(this, 1, getString(R.string.EnterPassword), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            fields[1].setTransformationMethod(PasswordTransformationMethod.getInstance());
            fields[1].setSaveEnabled(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                fields[0].setAutofillHints(View.AUTOFILL_HINT_USERNAME);
                fields[1].setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
            }
            fields[1].setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            advancedButton = new TextView(context);
            advancedButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            advancedButton.setGravity(Gravity.CENTER);
            advancedButton.setMinimumHeight(dp(48));
            advancedButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            addView(advancedButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 0));
            advancedFields = new LinearLayout(context);
            advancedFields.setOrientation(VERTICAL);
            addView(advancedFields, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addField(advancedFields, 2, getString(R.string.MonogramLoginServer), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            addField(advancedFields, 3, getString(R.string.MonogramLoginPort), InputType.TYPE_CLASS_NUMBER);
            fields[3].setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            showAdvanced(false);
            advancedButton.setOnClickListener(v -> showAdvanced(advancedFields.getVisibility() != VISIBLE));
            invitationButton=new TextView(context);
            invitationButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP,15);invitationButton.setGravity(Gravity.CENTER);
            invitationButton.setMinimumHeight(dp(48));
            invitationButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector),2));
            invitationButton.setOnClickListener(v -> invitationLink());
            addView(invitationButton,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,48,16,0,16,0));
            errorView = new TextView(context);
            errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            errorView.setGravity(Gravity.CENTER);
            errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            errorView.setVisibility(GONE);
            addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 8));
        }
        private void addField(LinearLayout parent, int index, String label, int inputType) {
            OutlineTextContainerView outline = outlines[index] = new OutlineTextContainerView(getContext());
            outline.setText(label);
            EditTextBoldCursor field = fields[index] = new EditTextBoldCursor(getContext());
            field.setContentDescription(label);
            field.setCursorSize(dp(20)); field.setCursorWidth(1.5f); field.setBackground(null);
            field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18); field.setMaxLines(1); field.setSingleLine(true);
            field.setPadding(dp(16), dp(16), dp(16), dp(16));
            field.setInputType(inputType); field.setTypeface(Typeface.DEFAULT);
            field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            field.setOnFocusChangeListener((v, focused) -> outline.animateSelection(focused ? 1f : 0f));
            outline.attachEditText(field);
            outline.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            parent.addView(outline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 12, 16, 0));
            field.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(Editable s) { outline.animateError(0); if (errorView != null) errorView.setVisibility(GONE); }
            });
            field.setOnEditorActionListener((v, action, event) -> {
                if (action == EditorInfo.IME_ACTION_DONE) { onNextPressed(null); return true; }
                if (action == EditorInfo.IME_ACTION_NEXT) {
                    if (index + 1 < fields.length && (index < 1 || advancedFields.getVisibility() == VISIBLE)) fields[index + 1].requestFocus();
                    else onNextPressed(null);
                    return true;
                }
                return false;
            });
        }
        void showAdvanced(boolean visible) {
            advancedFields.setVisibility(visible ? VISIBLE : GONE);
            advancedButton.setText(getString(visible ? R.string.MonogramLoginHideAdvanced : R.string.MonogramLoginAdvanced));
        }
        void error(int index, String text) {
            outlines[index].animateError(1);
            fields[index].requestFocus();
            AndroidUtilities.shakeViewSpring(outlines[index]);
            errorView.setText(text); errorView.setVisibility(VISIBLE);
        }
        @Override public void onNextPressed(String code) { login(); }
        @Override public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            confirmTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
            advancedButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
            invitationButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
            errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            for (int index = 0; index < fields.length; index++) {
                fields[index].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                fields[index].setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                fields[index].setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
                outlines[index].updateColor();
            }
        }
    }
}
