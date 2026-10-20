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
        items.add(UItem.asCheck(5,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramCopyPhoto)).setChecked(MonogramChatBehavior.showCopyPhotoButton(currentAccount)));
        items.add(UItem.asCheck(6,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramMessageDetails)).setChecked(MonogramChatBehavior.showDetailsButton(currentAccount)));
        items.add(UItem.asShadow("Save copies a message to Saved Messages. Repeat sends a copy to the current conversation. Available only for messages you can forward."));
        items.add(UItem.asCheck(7,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramGroupedMenu)).setChecked(MonogramChatBehavior.groupMessageMenu(currentAccount)));
        items.add(UItem.asShadow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramGroupedMenuInfo)));
        items.add(UItem.asHeader(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramReplyAppearanceHeader)));
        items.add(UItem.asCheck(8,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramReplyAppearanceColors)).setChecked(MonogramChatBehavior.replyColors(currentAccount)));
        items.add(UItem.asCheck(9,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramReplyAppearanceEmoji)).setChecked(MonogramChatBehavior.replyEmoji(currentAccount)));
        items.add(UItem.asCheck(10,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramReplyAppearanceBackground)).setChecked(MonogramChatBehavior.replyBackground(currentAccount)));
        items.add(UItem.asShadow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramReplyAppearanceInfo)));
        items.add(UItem.asHeader(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramMapsTitle)));
        android.content.Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
        int provider = org.telegram.messenger.MonogramMaps.selected(context);
        items.add(UItem.asRadio(11,"OpenStreetMap").setChecked(provider == org.telegram.messenger.MonogramMaps.OPEN_STREET_MAP));
        items.add(UItem.asRadio(12,"Google Maps").setChecked(provider == org.telegram.messenger.MonogramMaps.GOOGLE));
        items.add(UItem.asShadow(org.telegram.messenger.LocaleController.getString(
                provider == org.telegram.messenger.MonogramMaps.GOOGLE ? org.telegram.messenger.MonogramMaps.statusText(context)
                : org.telegram.messenger.R.string.MonogramMapsOsmInfo)));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==1) MonogramChatBehavior.hideKeyboardOnScroll(currentAccount,!MonogramChatBehavior.hideKeyboardOnScroll(currentAccount));
        else if(item.id==2) MonogramChatBehavior.commaAfterMention(currentAccount,!MonogramChatBehavior.commaAfterMention(currentAccount));
        else if(item.id==3) MonogramChatBehavior.showSaveButton(currentAccount,!MonogramChatBehavior.showSaveButton(currentAccount));
        else if(item.id==4) MonogramChatBehavior.showRepeatButton(currentAccount,!MonogramChatBehavior.showRepeatButton(currentAccount));
        else if(item.id==5) MonogramChatBehavior.showCopyPhotoButton(currentAccount,!MonogramChatBehavior.showCopyPhotoButton(currentAccount));
        else if(item.id==6) MonogramChatBehavior.showDetailsButton(currentAccount,!MonogramChatBehavior.showDetailsButton(currentAccount));
        else if(item.id==7) MonogramChatBehavior.groupMessageMenu(currentAccount,!MonogramChatBehavior.groupMessageMenu(currentAccount));
        else if(item.id==8) MonogramChatBehavior.replyColors(currentAccount,!MonogramChatBehavior.replyColors(currentAccount));
        else if(item.id==9) MonogramChatBehavior.replyEmoji(currentAccount,!MonogramChatBehavior.replyEmoji(currentAccount));
        else if(item.id==10) MonogramChatBehavior.replyBackground(currentAccount,!MonogramChatBehavior.replyBackground(currentAccount));
        else if(item.id==11 || item.id==12) {
            if (!org.telegram.messenger.MonogramMaps.select(org.telegram.messenger.ApplicationLoader.applicationContext,
                    item.id == 12 ? org.telegram.messenger.MonogramMaps.GOOGLE : org.telegram.messenger.MonogramMaps.OPEN_STREET_MAP)) {
                org.telegram.ui.Components.BulletinFactory.of(this).createErrorBulletin(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ErrorOccurred)).show();
            }
        }
        else return;
        listView.adapter.update(true);
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
