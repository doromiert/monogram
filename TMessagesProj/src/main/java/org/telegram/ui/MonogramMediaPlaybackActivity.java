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
        items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramMediaSeekHeader)));
        int seekDuration=MonogramMediaPlayback.doubleTapSeekDuration(currentAccount);
        items.add(UItem.asRadio(5,LocaleController.getString(R.string.MonogramMediaSeek5)).setChecked(seekDuration==0));
        items.add(UItem.asRadio(6,LocaleController.getString(R.string.MonogramMediaSeek10)).setChecked(seekDuration==1));
        items.add(UItem.asRadio(7,LocaleController.getString(R.string.MonogramMediaSeek15)).setChecked(seekDuration==2));
        items.add(UItem.asRadio(8,LocaleController.getString(R.string.MonogramMediaSeek30)).setChecked(seekDuration==3));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramMediaSeekInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramMediaSwipeHeader)));
        items.add(UItem.asCheck(9,LocaleController.getString(R.string.MonogramMediaSwipeToPip)).setChecked(MonogramMediaPlayback.swipeToPip(currentAccount)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramMediaSwipeToPipInfo)));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==1) MonogramMediaPlayback.pauseVideoOnMinimize(currentAccount,!MonogramMediaPlayback.pauseVideoOnMinimize(currentAccount));
        else if(item.id==2) MonogramMediaPlayback.pauseVoiceOnMinimize(currentAccount,!MonogramMediaPlayback.pauseVoiceOnMinimize(currentAccount));
        else if(item.id==3) MonogramMediaPlayback.pauseRoundOnMinimize(currentAccount,!MonogramMediaPlayback.pauseRoundOnMinimize(currentAccount));
        else if(item.id==4) MonogramMediaPlayback.unmuteWithVolumeButtons(currentAccount,!MonogramMediaPlayback.unmuteWithVolumeButtons(currentAccount));
        else if(item.id>=5 && item.id<=8) MonogramMediaPlayback.doubleTapSeekDuration(currentAccount,item.id-5);
        else if(item.id==9) MonogramMediaPlayback.swipeToPip(currentAccount,!MonogramMediaPlayback.swipeToPip(currentAccount));
        else return;
        listView.adapter.update(true);
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
