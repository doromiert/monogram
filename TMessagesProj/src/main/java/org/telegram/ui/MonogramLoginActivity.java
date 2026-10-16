// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import java.util.ArrayList;

public class MonogramLoginActivity extends BaseFragment {
    private boolean connecting;
    public MonogramLoginActivity() { super(); }
    public MonogramLoginActivity(int account) { super(); currentAccount = account; }
    private TextView text(Context context, String value, int size) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }
    private EditTextBoldCursor input(Context context, LinearLayout parent, String hint, int type) {
        EditTextBoldCursor view = new EditTextBoldCursor(context);
        view.setHint(hint); view.setSingleLine(true); view.setInputType(type); view.setTextSize(18);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        view.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        parent.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 0, 8, 0, 0));
        return view;
    }
    @Override public View createView(Context context) {
        actionBar.setTitle("Monogram");
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(24); body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        body.addView(text(context, "Your conversations. Your server.", 26), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        body.addView(text(context, "Sign in with your Jabber account. Use your full address, such as you@example.org.", 16), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16));
        EditTextBoldCursor jid = input(context, body, "Jabber address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditTextBoldCursor password = input(context, body, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        EditTextBoldCursor host = input(context, body, "Server override (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditTextBoldCursor port = input(context, body, "Port (5222)", InputType.TYPE_CLASS_NUMBER);
        TextView status = text(context, "", 15); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 12));
        TextView signIn = text(context, "Sign in", 18); signIn.setGravity(Gravity.CENTER);
        signIn.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        signIn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        body.addView(signIn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));
        signIn.setOnClickListener(v -> {
            if (connecting) return;
            int selectedPort;
            try { selectedPort = port.length() == 0 ? 5222 : Integer.parseInt(port.getText().toString()); }
            catch (NumberFormatException e) { status.setText("Enter a valid port."); return; }
            if (jid.length() == 0 || password.length() == 0) { status.setText("Enter your Jabber address and password."); return; }
            connecting = true; signIn.setEnabled(false); signIn.setText("Connecting…"); status.setText("");
            MonogramController.get(currentAccount).login(jid.getText().toString().trim(), password.getText().toString(), host.getText().toString().trim(), selectedPort, error -> {
                connecting = false; signIn.setEnabled(true); signIn.setText("Sign in");
                if (getParentActivity() == null) return;
                if (error != null) { status.setText("Could not sign in. Check your address, password and server TLS certificate. (" + error.getClass().getSimpleName() + ")"); return; }
                password.setText("");
                TLRPC.User user = MonogramController.get(currentAccount).ownUser();
                UserConfig.getInstance(currentAccount).setCurrentUser(user);
                UserConfig.getInstance(currentAccount).syncContacts = false;
                UserConfig.getInstance(currentAccount).saveConfig(true);
                ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user);
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, true, true);
                MessagesController.getInstance(currentAccount).putUser(user, false);
                if (UserConfig.selectedAccount != currentAccount && getParentActivity() instanceof LaunchActivity) {
                    ((LaunchActivity) getParentActivity()).switchToAccount(currentAccount, true);
                } else { presentFragment(new DialogsActivity(null), true); }
            });
        });
        fragmentView = scroll;
        return fragmentView;
    }
}
