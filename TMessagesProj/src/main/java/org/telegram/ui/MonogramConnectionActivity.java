// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

public final class MonogramConnectionActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    public MonogramConnectionActivity(int account) { currentAccount = account; }
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.contactsDidLoad);
        return true;
    }
    @Override public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.contactsDidLoad);
        super.onFragmentDestroy();
    }
    @Override protected CharSequence getTitle() { return "XMPP connection"; }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int state = MonogramController.get(currentAccount).state();
        items.add(UItem.asHeader(state == ConnectionsManager.ConnectionStateConnected ? "Connected to XMPP"
                : state == ConnectionsManager.ConnectionStateConnecting ? "Connecting to XMPP…" : "Disconnected from XMPP"));
        items.add(UItem.asButton(1, R.drawable.msg_retry, "Reconnect").accent().setEnabled(state != ConnectionsManager.ConnectionStateConnecting));
        items.add(UItem.asShadow("Monogram retries automatically when the connection drops. Reconnecting uses your saved login and preserves your messages and OMEMO keys."));
        items.add(UItem.asShadow("If your phone restricts Monogram in the background, check its battery and background data settings."));
        items.add(UItem.asButton(2,"Contact requests",Integer.toString(MonogramController.get(currentAccount).pendingContactRequests().size())));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) MonogramController.get(currentAccount).reconnect();
        else if(item.id==2) presentFragment(new MonogramContactActivity(currentAccount,null));
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void didReceivedNotification(int id, int account, Object... args) { if (listView != null) listView.adapter.update(true); }
}
