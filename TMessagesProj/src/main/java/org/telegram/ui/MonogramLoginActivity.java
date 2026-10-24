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
    private XmppView xmppView;
    private FragmentFloatingButton floatingButton;
    private ImageView backButtonView;
    private Bundle savedFields;
    private com.negzero.monogram.xmpp.RegistrationInvitation invitation;
    private boolean accountCreated;
    private int registrationSource = -1;
    private String webRegistrationDomain;

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
        xmppView = new XmppView(context);
        keyboardLinearLayout.addView(xmppView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
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
        floatingButton.setOnClickListener(view -> xmppView.onNextPressed(null));

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
        for (EditTextBoldCursor field : xmppView.fields) field.setEnabled(!value);
        xmppView.advancedButton.setEnabled(!value);
        xmppView.proxyButton.setEnabled(!value);
        xmppView.invitationButton.setEnabled(!value);
        xmppView.createAccountButton.setEnabled(!value);
        xmppView.fields[0].setEnabled(!value && (invitation==null || invitation.username==null));
    }

    private void updateColors() {
        if (fragmentView == null) return;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        xmppView.updateColors();
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
        if (isOpen && !connecting && xmppView != null) xmppView.fields[0].requestFocus();
    }
    @Override public boolean onBackPressed(boolean invoked) { return !connecting && super.onBackPressed(invoked); }
    @Override public void onFragmentDestroy() {
        destroyed = true;
        if (xmppView != null) xmppView.fields[1].setText("");
        super.onFragmentDestroy();
    }
    @Override public void saveSelfArgs(Bundle args) {
        if (xmppView == null) return;
        args.putString("monogram_jid", xmppView.fields[0].getText().toString());
        args.putString("monogram_host", xmppView.fields[2].getText().toString());
        args.putString("monogram_port", xmppView.fields[3].getText().toString());
        args.putBoolean("monogram_advanced", xmppView.advancedFields.getVisibility() == View.VISIBLE);
        args.putInt("monogram_account",currentAccount);args.putBoolean("monogram_account_created",accountCreated);
        args.remove("monogram_invite");args.remove("monogram_registration_server");
        args.putInt("monogram_registration_source",registrationSource);
        args.putString("monogram_web_registration_domain",webRegistrationDomain);
        if(invitation!=null && !invitation.requiresToken()) args.putString("monogram_registration_server",invitation.server.toString());
        if(invitation!=null && invitation.requiresToken()) try { args.putString("monogram_invite",MonogramController.get(currentAccount).sealRegistrationInvite(invitation.uri)); }
        catch(Exception unavailable) { /* Reopen the invite rather than save a token without Keystore protection. */ }
    }
    @Override public void restoreSelfArgs(Bundle args) {
        savedFields = args;
        if (xmppView == null) return;
        xmppView.fields[0].setText(args.getString("monogram_jid", ""));
        xmppView.fields[2].setText(args.getString("monogram_host", ""));
        xmppView.fields[3].setText(args.getString("monogram_port", ""));
        xmppView.showAdvanced(args.getBoolean("monogram_advanced"));
        accountCreated=args.getBoolean("monogram_account_created");
        registrationSource=args.getInt("monogram_registration_source",-1);
        webRegistrationDomain=args.getString("monogram_web_registration_domain");
        if(args.containsKey("monogram_registration_server")) try {
            invitation=com.negzero.monogram.xmpp.RegistrationInvitation.forServer(args.getString("monogram_registration_server"));
        } catch(Exception invalid) { invitation=null;registrationSource=-1; }
        String sealed=args.getString("monogram_invite");
        if(sealed!=null) {
            try { invitation=MonogramController.get(currentAccount).openRegistrationInvite(sealed); }
            catch(Exception unavailable) { invitation=null;xmppView.error(0,"Reopen your invitation link to continue registration."); }
        }
        if(invitation!=null && invitation.username!=null) xmppView.fields[0].setText(invitation.username+"@"+invitation.server);
        applyMode();
    }

    private void prefillInvitation() {
        xmppView.fields[1].setText("");xmppView.fields[2].setText("");xmppView.fields[3].setText("");xmppView.showAdvanced(false);
        xmppView.fields[0].setText((invitation.username==null ? "" : invitation.username.toString())+"@"+invitation.server);
        xmppView.fields[0].setSelection(invitation.username==null ? 0 : xmppView.fields[0].length());
    }
    private void applyMode() {
        if(xmppView==null) return;
        boolean registering=invitation!=null;
        xmppView.titleView.setText(registering ? "Create your XMPP account" : getString(R.string.MonogramLoginTitle));
        xmppView.confirmTextView.setText(registering ? "Create an account on "+invitation.server+". "+(invitation.username==null ? "Choose a username and password."+(!invitation.requiresToken() ? " If an invitation is required, use an invitation link below." : "") : "This invitation reserves the address below. Choose your password.")
                : accountCreated ? "Your account was created. Sign in with the address and password you chose."
                : webRegistrationDomain!=null ? "Create your account on the provider’s website, then return here and enter your full XMPP ID and password to sign in." : getString(R.string.MonogramLoginHelp));
        xmppView.outlines[1].setText(registering ? "Choose a password" : getString(R.string.EnterPassword));
        xmppView.invitationButton.setText("Use an invitation link");
        xmppView.createAccountButton.setText(registering ? "Sign in instead" : "Create an account");
        xmppView.createAccountButton.setVisibility(View.VISIBLE);
        if(floatingButton!=null) floatingButton.setContentDescription(registering ? "Create account" : getString(R.string.Continue));
        if(android.os.Build.VERSION.SDK_INT>=26) xmppView.fields[1].setAutofillHints(registering ? "newPassword" : View.AUTOFILL_HINT_PASSWORD);
        setConnecting(connecting);
    }
    private void createAccount() {
        if(connecting) return;
        if(invitation!=null) {
            invitation=null;registrationSource=-1;accountCreated=false;webRegistrationDomain=null;
            xmppView.fields[1].setText("");applyMode();return;
        }
        ArrayList<Runnable> actions=new ArrayList<>();ArrayList<CharSequence> labels=new ArrayList<>();
        labels.add("yax.im · Register in Monogram");actions.add(() -> usePublicServer("yax.im",null));
        labels.add("Hot-Chilli · Register on website");actions.add(() -> usePublicServer("jabber.hot-chilli.net","https://jabber.hot-chilli.net/forms/create/"));
        labels.add("Disroot · Register on website");actions.add(() -> usePublicServer("disroot.org","https://user.disroot.org/pwm/public/newuser"));
        for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++) if(UserConfig.getInstance(i).isClientActivated()) try {
            com.negzero.monogram.xmpp.Account template=MonogramController.get(i).registrationTemplate();
            final int source=i;
            labels.add(template.jid.asDomainBareJid()+" · Existing account’s server");actions.add(() -> useRegistrationServer(source));
        } catch(Exception ignored) { }
        labels.add("Another server…");actions.add(this::customRegistrationServer);
        showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext())
                .setTitle("Create account on…")
                .setMessage("Choose a public provider or your own server. All can communicate across the XMPP network.")
                .setItems(labels.toArray(new CharSequence[0]),(dialog,which) -> actions.get(which).run())
                .setNegativeButton(getString(R.string.Cancel),null).create());
    }
    private void usePublicServer(String domain,String signupUrl) {
        try {
            invitation=com.negzero.monogram.xmpp.RegistrationInvitation.forServer(domain);
            registrationSource=-1;accountCreated=false;webRegistrationDomain=signupUrl==null?null:domain;
            prefillInvitation();
            if(signupUrl!=null) invitation=null;
            applyMode();
            if(signupUrl!=null) org.telegram.messenger.browser.Browser.openUrl(getParentActivity(),signupUrl);
            else {xmppView.fields[0].requestFocus();AndroidUtilities.showKeyboard(xmppView.fields[0]);}
        } catch(Exception invalid) {xmppView.error(0,"Enter a valid XMPP server domain.");}
    }
    private void customRegistrationServer() {
        org.telegram.ui.Cells.EditTextCell input=new org.telegram.ui.Cells.EditTextCell(getContext(),"example.org",false);
        input.editText.setSingleLine(true);input.editText.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        org.telegram.ui.ActionBar.AlertDialog dialog=new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext())
                .setTitle("XMPP server").setMessage("Enter the server’s domain. If it requires an invitation, use your invitation link instead.")
                .setView(input).setPositiveButton("Continue",null).setNegativeButton(getString(R.string.Cancel),null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(org.telegram.ui.ActionBar.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String domain=input.editText.getText().toString().trim();
            try {com.negzero.monogram.xmpp.RegistrationInvitation.forServer(domain);}
            catch(Exception invalid) {input.editText.setError("Enter a server domain, such as example.org");return;}
            dialog.dismiss();usePublicServer(domain,null);
        }));
        showDialog(dialog);
    }
    private void useRegistrationServer(int source) {
        try {
            com.negzero.monogram.xmpp.Account template=MonogramController.get(source).registrationTemplate();
            invitation=com.negzero.monogram.xmpp.RegistrationInvitation.forServer(template.jid.asDomainBareJid().toString());
            registrationSource=source;accountCreated=false;webRegistrationDomain=null;prefillInvitation();
            xmppView.fields[2].setText(template.host==null ? "" : template.host);
            xmppView.fields[3].setText(template.port==5222 ? "" : Integer.toString(template.port));
            xmppView.showAdvanced(template.host!=null || template.port!=5222);
            applyMode();xmppView.fields[0].requestFocus();AndroidUtilities.showKeyboard(xmppView.fields[0]);
        } catch(Exception unavailable) { xmppView.error(0,"This account’s server settings are unavailable. Sign in to it again first."); }
    }
    private void getServerInvitation() {
        int source=registrationSource;
        if(source<0 || !UserConfig.getInstance(source).isClientActivated()) return;
        presentFragment(new MonogramAccountInviteActivity(source,created -> {
            if(destroyed || connecting) return;
            invitation=created;accountCreated=false;
            String host=xmppView.fields[2].getText().toString(),port=xmppView.fields[3].getText().toString();
            prefillInvitation();xmppView.fields[2].setText(host);xmppView.fields[3].setText(port);
            xmppView.showAdvanced(!host.isEmpty() || !port.isEmpty());applyMode();
        }));
    }
    private void invitationLink() {
        if(connecting) return;
        org.telegram.ui.Cells.EditTextCell input=new org.telegram.ui.Cells.EditTextCell(getContext(),"xmpp: invitation link",false);
        input.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.editText.setSingleLine(true);input.editText.setSaveEnabled(false);
        input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16384)});
        if(android.os.Build.VERSION.SDK_INT>=26) input.editText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        org.telegram.ui.ActionBar.AlertDialog.Builder builder=new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext()).setTitle("Use an invitation link")
                .setMessage("Paste the xmpp: link from your server's invitation page. No account is created until you submit the registration screen.")
                .setView(input).setPositiveButton("Continue",(dialog,which) -> {
                    try { invitation=com.negzero.monogram.xmpp.RegistrationInvitation.parse(input.getText().toString().trim());accountCreated=false;webRegistrationDomain=null;prefillInvitation();applyMode(); }
                    catch(com.negzero.monogram.xmpp.XmppInvitations.InvitationException invalid) { xmppView.error(0,invalid.getMessage()); }
                    input.editText.setText("");
                }).setNegativeButton("Cancel",null);
        if(registrationSource>=0 && UserConfig.getInstance(registrationSource).isClientActivated())
            builder.setNeutralButton("Get invitation",(dialog,which) -> getServerInvitation());
        showDialog(builder.create());
    }

    private void login() {
        if (connecting) return;
        String jid = xmppView.fields[0].getText().toString().trim();
        String password = xmppView.fields[1].getText().toString();
        String host = xmppView.fields[2].getText().toString().trim();
        int port;
        try {
            String text = xmppView.fields[3].getText().toString().trim();
            port = text.isEmpty() ? 5222 : Integer.parseInt(text);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            xmppView.showAdvanced(true);
            xmppView.error(3, getString(R.string.MonogramLoginPortError)); return;
        }
        try { new com.negzero.monogram.xmpp.Account(jid, host, port, "Monogram"); }
        catch (Exception invalid) { xmppView.error(0, getString(R.string.MonogramLoginAddressError)); return; }
        if (password.isEmpty()) { xmppView.error(1, getString(R.string.MonogramLoginPasswordError)); return; }
        if(invitation!=null) try { invitation.validate(new com.negzero.monogram.xmpp.Account(jid,host,port,"Monogram")); }
        catch(Exception invalid) { xmppView.error(0,invalid.getMessage());return; }
        setConnecting(true);
        AndroidUtilities.hideKeyboard(xmppView.fields[1]);
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
                String detail=error instanceof org.telegram.messenger.xmpp.MonogramProxy.ConfigurationException ? error.getMessage()
                        : error instanceof com.negzero.monogram.xmpp.XmppInvitations.InvitationException ? error.getMessage()
                        : invitation!=null && !accountCreated ? com.negzero.monogram.xmpp.XmppRequestException.describe(error) : com.negzero.monogram.xmpp.XmppRequestException.describeLogin(error);
                xmppView.error(1,(accountCreated ? "Account created; sign-in failed. " : "")+detail);
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
        xmppView.fields[1].setText("");
        if (UserConfig.selectedAccount != currentAccount && getParentActivity() instanceof LaunchActivity)
            ((LaunchActivity) getParentActivity()).switchToAccount(currentAccount, true);
        else { MainTabsActivity tabs = new MainTabsActivity(); tabs.setCurrentAccount(currentAccount); presentFragment(tabs, true); }
    }

    /** Telegram's password-page layout with XMPP fields in place of phone/SMS authentication. */
    private class XmppView extends SlideView {
        final EditTextBoldCursor[] fields = new EditTextBoldCursor[4];
        final OutlineTextContainerView[] outlines = new OutlineTextContainerView[4];
        final TextView titleView, confirmTextView, advancedButton, errorView,invitationButton,createAccountButton;
        final TextView proxyButton;
        final LinearLayout advancedFields;
        XmppView(Context context) {
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
            createAccountButton=new TextView(context);
            createAccountButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP,15);createAccountButton.setGravity(Gravity.CENTER);
            createAccountButton.setMinimumHeight(dp(48));
            createAccountButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector),2));
            createAccountButton.setOnClickListener(v -> createAccount());
            addView(createAccountButton,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,48,16,0,16,0));
            proxyButton=new TextView(context);
            proxyButton.setText(getString(R.string.ProxySettings));proxyButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP,15);
            proxyButton.setGravity(Gravity.CENTER);proxyButton.setMinimumHeight(dp(48));
            proxyButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector),2));
            proxyButton.setOnClickListener(v->{if(!connecting){AndroidUtilities.hideKeyboard(proxyButton);ProxyListActivity screen=new ProxyListActivity();screen.setCurrentAccount(currentAccount);presentFragment(screen);}});
            addView(proxyButton,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,48,16,0,16,0));
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
            proxyButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
            createAccountButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
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
