// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

public class MonogramLoginActivity extends UniversalFragment {
    private boolean connecting;
    private EditTextCell jid, password, host, port;
    private String status;
    public MonogramLoginActivity() { super(); }
    public MonogramLoginActivity(int account) { super(); currentAccount = account; }
    @Override protected CharSequence getTitle() { return "Monogram"; }
    private EditTextCell input(Context context, String hint, int type) {
        EditTextCell cell = new EditTextCell(context, hint, false); cell.editText.setInputType(type); return cell;
    }
    @Override public View createView(Context context) {
        jid = input(context, "Jabber address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); jid.setDivider(true);
        password = input(context, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.editText.setSaveEnabled(false); password.setSaveEnabled(false);
        host = input(context, "Server override (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); host.setDivider(true);
        port = input(context, "Port (5222)", InputType.TYPE_CLASS_NUMBER); port.whenHitEnter(this::login);
        return super.createView(context);
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Your conversations. Your server."));
        items.add(UItem.asCustom(jid)); items.add(UItem.asCustom(password));
        items.add(UItem.asShadow("Sign in with your Jabber account. Use your full address, such as you@example.org."));
        items.add(UItem.asHeader("Advanced connection settings"));
        items.add(UItem.asCustom(host)); items.add(UItem.asCustom(port));
        items.add(UItem.asShadow("Leave these empty to discover your provider's server automatically."));
        items.add(UItem.asButton(1, connecting ? "Connecting…" : "Sign in").accent().setEnabled(!connecting));
        items.add(UItem.asShadow(status));
    }
    private void refresh() { if (listView != null) listView.adapter.update(true); }
    private void login() {
        if (connecting) return;
        int selectedPort;
        try { selectedPort = port.getText().length() == 0 ? 5222 : Integer.parseInt(port.getText().toString()); }
        catch (NumberFormatException invalid) { status = "Enter a valid port."; refresh(); return; }
        if (jid.getText().length() == 0 || password.getText().length() == 0) { status = "Enter your Jabber address and password."; refresh(); return; }
        connecting = true; status = null; refresh();
        MonogramController.get(currentAccount).login(jid.getText().toString().trim(), password.getText().toString(), host.getText().toString().trim(), selectedPort, error -> {
            connecting = false;
            if (getParentActivity() == null) return;
            if (error != null) { status = "Could not sign in. Check your address, password and server TLS certificate."; refresh(); return; }
            password.setText("");
            TLRPC.User user = MonogramController.get(currentAccount).ownUser();
            UserConfig.getInstance(currentAccount).setCurrentUser(user); UserConfig.getInstance(currentAccount).syncContacts = false;
            UserConfig.getInstance(currentAccount).saveConfig(true);
            ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user);
            MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, true, true);
            MessagesController.getInstance(currentAccount).putUser(user, false);
            if (UserConfig.selectedAccount != currentAccount && getParentActivity() instanceof LaunchActivity)
                ((LaunchActivity) getParentActivity()).switchToAccount(currentAccount, true);
            else { MainTabsActivity tabs = new MainTabsActivity(); tabs.setCurrentAccount(currentAccount); presentFragment(tabs, true); }
        });
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) { if (item.id == 1) login(); }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
