// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

public final class MonogramNewChatActivity extends UniversalFragment {
    private EditTextCell address;
    private boolean opening;
    private String status;
    public MonogramNewChatActivity(int account) { currentAccount = account; }
    @Override protected CharSequence getTitle() { return "New conversation"; }
    @Override public View createView(Context context) {
        address = new EditTextCell(context, "Contact's Jabber address", false);
        address.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        address.whenHitEnter(this::open);
        return super.createView(context);
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Jabber address")); items.add(UItem.asCustom(address));
        items.add(UItem.asShadow("Use the contact's full address, such as friend@example.org."));
        items.add(UItem.asButton(1, opening ? "Opening…" : "Start conversation").accent().setEnabled(!opening));
        items.add(UItem.asShadow(status));
        items.add(UItem.asButton(2, "Join an XMPP room"));
    }
    private void open() {
        if (opening) return;
        opening = true; status = null; listView.adapter.update(true);
        MonogramController.get(currentAccount).openPeer(address.getText().toString().trim(), (user, error) -> {
            opening = false;
            if (getParentActivity() == null) return;
            if (error != null) { status = "Could not open this address. Check the address and your connection."; listView.adapter.update(true); return; }
            Bundle args = new Bundle(); args.putLong("user_id", ((TLRPC.User) user).id);
            ChatActivity chat = new ChatActivity(args); chat.setCurrentAccount(currentAccount); presentFragment(chat, true);
        });
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) { if (item.id == 1) open(); else if (item.id==2) presentFragment(new MonogramRoomActivity(currentAccount,0)); }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
