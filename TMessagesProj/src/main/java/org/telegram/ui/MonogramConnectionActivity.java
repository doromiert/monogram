// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsService;
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
        int queued=MonogramController.get(currentAccount).queuedSendCount();
        if(queued>0) items.add(UItem.asShadow(queued+" messages waiting to send after reconnecting."));
        String failure=MonogramController.get(currentAccount).lastDisconnect();
        if(failure!=null) items.add(UItem.asShadow("Last disconnect: "+failure));
        items.add(UItem.asButton(1, R.drawable.msg_retry, "Reconnect").accent().setEnabled(state != ConnectionsManager.ConnectionStateConnecting));
        items.add(UItem.asShadow("Monogram retries automatically when the connection drops. Reconnecting uses your saved login and preserves your messages and OMEMO keys."));
        android.os.PowerManager power=(android.os.PowerManager)getContext().getSystemService(android.content.Context.POWER_SERVICE);
        boolean unrestricted=power!=null && power.isIgnoringBatteryOptimizations(getContext().getPackageName());
        items.add(UItem.asHeader("Background connection"));
        items.add(UItem.asButton(3,unrestricted ? "Battery optimization exemption enabled" : "Allow unrestricted background connection").setEnabled(!unrestricted));
        items.add(UItem.asButton(4,"App battery and background settings"));
        if(android.os.Build.VERSION.SDK_INT>=26) {
            android.app.NotificationManager manager=(android.app.NotificationManager)getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            android.app.NotificationChannel channel=manager==null ? null : manager.getNotificationChannel(NotificationsService.CONNECTION_CHANNEL);
            String visibility=channel==null ? "System settings" : !manager.areNotificationsEnabled() || channel.getImportance()==android.app.NotificationManager.IMPORTANCE_NONE ? "Hidden" : "Shown";
            items.add(UItem.asButton(5,R.drawable.msg_notifications,"Connection notification",visibility));
        }
        items.add(UItem.asShadow("Monogram keeps its XMPP connection in a foreground notification. Android battery optimization and additional phone-specific restrictions can still interrupt it; allow background activity in the system settings. To hide the ongoing notice, turn off only the Connection notification category in Android settings. Keep message notifications enabled."));
        items.add(UItem.asButton(2,"Requests and invitations",Integer.toString(MonogramController.get(currentAccount).pendingContactRequests().size()+MonogramController.get(currentAccount).pendingRoomInvitations().size())));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) MonogramController.get(currentAccount).reconnect();
        else if(item.id==2) presentFragment(new MonogramContactActivity(currentAccount,null));
        else if(item.id==3 || item.id==4 || item.id==5) {
            android.content.Intent intent=item.id==5 ? NotificationsService.notificationSettings(getContext()) : new android.content.Intent(item.id==3 ? android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:"+getContext().getPackageName()));
            try { getParentActivity().startActivity(intent); }
            catch(android.content.ActivityNotFoundException | SecurityException unsupported) {
                try { getParentActivity().startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)); } catch(android.content.ActivityNotFoundException | SecurityException unavailable) { }
            }
        }
    }
    @Override public void onResume() { super.onResume(); if(listView!=null) listView.adapter.update(true); }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void didReceivedNotification(int id, int account, Object... args) { if (listView != null) listView.adapter.update(true); }
}
