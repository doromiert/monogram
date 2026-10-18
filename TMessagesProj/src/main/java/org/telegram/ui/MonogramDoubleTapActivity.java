// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramDoubleTap;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class MonogramDoubleTapActivity extends MonogramSettingsActivity {
    public MonogramDoubleTapActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.MonogramDoubleTapTitle); }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramDoubleTapTitle)));
        items.add(UItem.asSettingsCell(1,LocaleController.getString(R.string.MonogramDoubleTapIncoming),MonogramDoubleTap.label(MonogramDoubleTap.action(currentAccount,false))));
        items.add(UItem.asSettingsCell(2,LocaleController.getString(R.string.MonogramDoubleTapOutgoing),MonogramDoubleTap.label(MonogramDoubleTap.action(currentAccount,true))));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramDoubleTapInfo)));
        items.add(UItem.asSettingsCell(3,R.drawable.msg2_reactions2,LocaleController.getString(R.string.DoubleTapSetting)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramDoubleTapReactionInfo)));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==3) { ReactionsDoubleTapManageActivity fragment=new ReactionsDoubleTapManageActivity();fragment.setCurrentAccount(currentAccount);presentFragment(fragment);return; }
        if(item.id!=1 && item.id!=2 || getParentActivity()==null) return;
        boolean own=item.id==2;int[] actions=MonogramDoubleTap.actions(own);
        String[] labels=new String[actions.length];int selected=0,current=MonogramDoubleTap.action(currentAccount,own);
        for(int i=0;i<actions.length;i++) { labels[i]=MonogramDoubleTap.label(actions[i]);if(actions[i]==current) selected=i; }
        showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(),labels,item.text.toString(),selected,(dialog,which) -> {
            MonogramDoubleTap.save(currentAccount,own,actions[which]);listView.adapter.update(true);
        }));
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
