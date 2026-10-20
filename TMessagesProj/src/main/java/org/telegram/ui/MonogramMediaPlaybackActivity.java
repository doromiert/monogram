// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramMediaPlayback;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class MonogramMediaPlaybackActivity extends MonogramSettingsActivity {
    public MonogramMediaPlaybackActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.MonogramMediaPlaybackTitle); }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramMediaPauseHeader)));
        items.add(UItem.asCheck(1,LocaleController.getString(R.string.MonogramMediaPauseVideo)).setChecked(MonogramMediaPlayback.pauseVideoOnMinimize(currentAccount)));
        items.add(UItem.asCheck(2,LocaleController.getString(R.string.MonogramMediaPauseVoice)).setChecked(MonogramMediaPlayback.pauseVoiceOnMinimize(currentAccount)));
        items.add(UItem.asCheck(3,LocaleController.getString(R.string.MonogramMediaPauseRound)).setChecked(MonogramMediaPlayback.pauseRoundOnMinimize(currentAccount)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramMediaPauseInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramMediaVolumeHeader)));
        items.add(UItem.asCheck(4,LocaleController.getString(R.string.MonogramMediaVolumeUnmute)).setChecked(MonogramMediaPlayback.unmuteWithVolumeButtons(currentAccount)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramMediaVolumeInfo)));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==1) MonogramMediaPlayback.pauseVideoOnMinimize(currentAccount,!MonogramMediaPlayback.pauseVideoOnMinimize(currentAccount));
        else if(item.id==2) MonogramMediaPlayback.pauseVoiceOnMinimize(currentAccount,!MonogramMediaPlayback.pauseVoiceOnMinimize(currentAccount));
        else if(item.id==3) MonogramMediaPlayback.pauseRoundOnMinimize(currentAccount,!MonogramMediaPlayback.pauseRoundOnMinimize(currentAccount));
        else if(item.id==4) MonogramMediaPlayback.unmuteWithVolumeButtons(currentAccount,!MonogramMediaPlayback.unmuteWithVolumeButtons(currentAccount));
        else return;
        listView.adapter.update(true);
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
