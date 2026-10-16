// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;

/** Routes upstream account-entry points to Jabber; Telegram account actions are unavailable. */
public class LoginActivity extends MonogramLoginActivity {
    public static final boolean ENABLE_PASTED_TEXT_PROCESSING = false;
    public static final int AUTH_TYPE_MESSAGE = 1, AUTH_TYPE_MISSED_CALL = 11;
    private boolean telegramAccountAction;

    public LoginActivity() { super(); }
    public LoginActivity(int account) { super(account); }
    public LoginActivity setIntroView(View intro, TextView startButton) { return this; }

    private LoginActivity unavailable() {
        telegramAccountAction = true;
        return this;
    }
    public LoginActivity changeEmail(Runnable onFinish) { return unavailable(); }
    public LoginActivity changeEmail(Runnable onFinish, Runnable onSkip, boolean nonSkippable) { return unavailable(); }
    public LoginActivity changePhoneNumber() { return unavailable(); }
    public LoginActivity cancelAccountDeletion(String phone, Bundle params, TLRPC.TL_auth_sentCode code) { return unavailable(); }

    private static void showUnavailable(BaseFragment fragment) {
        if (fragment != null && fragment.getParentActivity() != null) {
            AlertsCreator.showSimpleAlert(fragment, "Monogram", "Manage your Jabber account with your XMPP provider. Telegram account actions are unavailable.");
        }
    }
    @Override public boolean onFragmentCreate() {
        if (telegramAccountAction) {
            showUnavailable(LaunchActivity.getSafeLastFragment());
            return false;
        }
        return super.onFragmentCreate();
    }
    public void open(String phone, TLRPC.auth_SentCode code) {
        telegramAccountAction = true;
        showUnavailable(LaunchActivity.getSafeLastFragment());
    }
    public static void needShowInvalidAlert(BaseFragment fragment, String phone, boolean banned) {
        showUnavailable(fragment);
    }
}
