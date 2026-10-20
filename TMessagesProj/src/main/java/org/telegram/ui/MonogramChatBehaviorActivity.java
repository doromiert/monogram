// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.xmpp.MonogramChatBehavior;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class MonogramChatBehaviorActivity extends MonogramSettingsActivity {
    public MonogramChatBehaviorActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return "Chat behavior"; }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader("Keyboard"));
        items.add(UItem.asCheck(1,"Hide keyboard on scroll").setChecked(MonogramChatBehavior.hideKeyboardOnScroll(currentAccount)));
        items.add(UItem.asShadow("Dragging the conversation closes the keyboard or emoji panel."));
        items.add(UItem.asHeader("Mentions"));
        items.add(UItem.asCheck(2,"Add comma after mention").setChecked(MonogramChatBehavior.commaAfterMention(currentAccount)));
        items.add(UItem.asShadow("Add a comma and space after selecting a person or group from mention suggestions. Bot mentions keep a space."));
        items.add(UItem.asHeader("Message menu"));
        items.add(UItem.asCheck(3,"Show Save message").setChecked(MonogramChatBehavior.showSaveButton(currentAccount)));
        items.add(UItem.asCheck(4,"Show Repeat message").setChecked(MonogramChatBehavior.showRepeatButton(currentAccount)));
        items.add(UItem.asShadow("Save copies a message to Saved Messages. Repeat sends a copy to the current conversation. Available only for messages you can forward."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==1) MonogramChatBehavior.hideKeyboardOnScroll(currentAccount,!MonogramChatBehavior.hideKeyboardOnScroll(currentAccount));
        else if(item.id==2) MonogramChatBehavior.commaAfterMention(currentAccount,!MonogramChatBehavior.commaAfterMention(currentAccount));
        else if(item.id==3) MonogramChatBehavior.showSaveButton(currentAccount,!MonogramChatBehavior.showSaveButton(currentAccount));
        else if(item.id==4) MonogramChatBehavior.showRepeatButton(currentAccount,!MonogramChatBehavior.showRepeatButton(currentAccount));
        else return;
        listView.adapter.update(true);
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
