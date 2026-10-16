// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

public final class MonogramNewChatActivity extends BaseFragment {
    public MonogramNewChatActivity(int account) { currentAccount = account; }
    @Override public View createView(Context context) {
        actionBar.setTitle("New conversation"); actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(24); body.setPadding(padding, padding, padding, padding);
        body.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        EditTextBoldCursor address = new EditTextBoldCursor(context); address.setHint("Contact's Jabber address");
        address.setTextSize(18); address.setSingleLine(true);
        address.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        address.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        body.addView(address, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        TextView status = new TextView(context); status.setTextSize(15);
        status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16));
        TextView open = new TextView(context); open.setText("Start conversation"); open.setTextSize(18); open.setGravity(Gravity.CENTER);
        open.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        open.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        body.addView(open, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));
        open.setOnClickListener(v -> {
            open.setEnabled(false); status.setText("Opening…");
            MonogramController.get(currentAccount).openPeer(address.getText().toString().trim(), (user, error) -> {
                open.setEnabled(true);
                if (getParentActivity() == null) return;
                if (error != null) { status.setText("Could not open this address. Check the address and your connection."); return; }
                Bundle args = new Bundle(); args.putLong("user_id", ((TLRPC.User) user).id);
                ChatActivity chat = new ChatActivity(args); chat.setCurrentAccount(currentAccount);
                presentFragment(chat, true);
            });
        });
        fragmentView = body; return body;
    }
}
