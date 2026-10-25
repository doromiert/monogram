// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.xmpp.MonogramChatBehavior;
import org.telegram.messenger.xmpp.MonogramUiPreferences;
import org.telegram.ui.Business.QuickRepliesActivity;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class MonogramChatBehaviorActivity extends MonogramSettingsActivity {
    public MonogramChatBehaviorActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return "Chat behavior"; }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asButton(14,"Automatic link embeds"));
        items.add(UItem.asShadow("Create link previews on this device, including an optional setting for encrypted chats."));
        items.add(UItem.asHeader("Messaging"));
        items.add(UItem.asButton(15,"Quick replies"));
        items.add(UItem.asShadow("Create reusable replies stored on this device. They are sent as ordinary messages and do not require a subscription."));
        items.add(UItem.asHeader("Appearance"));
        items.add(UItem.asButton(17,"Folder tabs",new String[]{"Names only","Icons only","Icons and names"}[org.telegram.messenger.xmpp.MonogramFolderIcons.displayMode(currentAccount)]));
        items.add(UItem.asButton(20,"Avatar corners",MonogramUiPreferences.avatarCorners(currentAccount)==28?"Round":MonogramUiPreferences.avatarCorners(currentAccount)==0?"Square":Integer.toString(MonogramUiPreferences.avatarCorners(currentAccount))));
        items.add(UItem.asCheck(18,"Center home title").setChecked(MonogramUiPreferences.centerHomeTitle(currentAccount)));
        items.add(UItem.asCheck(19,"Show compose button").setChecked(!MonogramUiPreferences.hideComposeButton(currentAccount)));
        items.add(UItem.asCheck(21,"Always collapse home search").setChecked(MonogramUiPreferences.collapseHomeSearch(currentAccount)));
        items.add(UItem.asShadow("Keep the search bar hidden until you tap the search icon."));
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
        items.add(UItem.asHeader(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.VoipCamera)));
        items.add(UItem.asCheck(13,org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramUseSystemCamera)).setChecked(!org.telegram.messenger.SharedConfig.inappCamera));
        items.add(UItem.asShadow(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramUseSystemCameraInfo)));
        items.add(UItem.asHeader("Round video messages"));
        items.add(UItem.asButton(16,"Opening camera",roundVideoCameraLabel(MonogramUiPreferences.roundVideoCamera(currentAccount))));
        items.add(UItem.asShadow("Choose which camera opens first, or ask each time you record a round video."));
        items.add(UItem.asHeader(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramMapsTitle)));
        android.content.Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
        items.add(UItem.asCheck(24,"Load map previews").setChecked(org.telegram.messenger.MonogramMaps.previewsEnabled(context)));
        items.add(UItem.asShadow("Location previews fetch nearby OpenStreetMap tiles. Opening a full map uses the map provider selected below."));
        int provider = org.telegram.messenger.MonogramMaps.selected(context);
        items.add(UItem.asRadio(11,"OpenStreetMap").setChecked(provider == org.telegram.messenger.MonogramMaps.OPEN_STREET_MAP));
        items.add(UItem.asRadio(12,"Google Maps").setChecked(provider == org.telegram.messenger.MonogramMaps.GOOGLE));
        items.add(UItem.asShadow(org.telegram.messenger.LocaleController.getString(
                provider == org.telegram.messenger.MonogramMaps.GOOGLE ? org.telegram.messenger.MonogramMaps.statusText(context)
                : org.telegram.messenger.R.string.MonogramMapsOsmInfo)));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==24) {
            android.content.Context context=org.telegram.messenger.ApplicationLoader.applicationContext;
            org.telegram.messenger.MonogramMaps.previewsEnabled(context,!org.telegram.messenger.MonogramMaps.previewsEnabled(context));listView.adapter.update(true);return;
        }
        if(item.id==14) { presentFragment(new MonogramLinkPreviewsActivity(currentAccount));return; }
        if(item.id==15) {
            QuickRepliesActivity fragment=new QuickRepliesActivity();
            fragment.setCurrentAccount(currentAccount);
            presentFragment(fragment);
            return;
        }
        if(item.id==20) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Avatar corners")
                    .setItems(new CharSequence[]{"Square","Slightly rounded","Rounded","More rounded","Round"},(dialog,which)->{
                        MonogramUiPreferences.setAvatarCorners(currentAccount,which*7);
                        org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.monogramAvatarShapeChanged,currentAccount);
                        getNotificationCenter().postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces,org.telegram.messenger.MessagesController.UPDATE_MASK_ALL);
                        listView.adapter.update(true);
                    }).create());return;
        }
        if(item.id==17) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Folder tabs")
                    .setItems(new CharSequence[]{"Names only","Icons only","Icons and names"},(dialog,which)->{
                        org.telegram.messenger.xmpp.MonogramFolderIcons.setDisplayMode(currentAccount,which);
                        getNotificationCenter().postNotificationName(org.telegram.messenger.NotificationCenter.dialogFiltersUpdated);
                        listView.adapter.update(true);
                    }).create());return;
        }
        if(item.id==21) MonogramUiPreferences.setCollapseHomeSearch(currentAccount,!MonogramUiPreferences.collapseHomeSearch(currentAccount));
        else if(item.id==18) MonogramUiPreferences.setCenterHomeTitle(currentAccount,!MonogramUiPreferences.centerHomeTitle(currentAccount));
        else if(item.id==19) MonogramUiPreferences.setHideComposeButton(currentAccount,!MonogramUiPreferences.hideComposeButton(currentAccount));
        else if(item.id==1) MonogramChatBehavior.hideKeyboardOnScroll(currentAccount,!MonogramChatBehavior.hideKeyboardOnScroll(currentAccount));
        else if(item.id==2) MonogramChatBehavior.commaAfterMention(currentAccount,!MonogramChatBehavior.commaAfterMention(currentAccount));
        else if(item.id==3) MonogramChatBehavior.showSaveButton(currentAccount,!MonogramChatBehavior.showSaveButton(currentAccount));
        else if(item.id==4) MonogramChatBehavior.showRepeatButton(currentAccount,!MonogramChatBehavior.showRepeatButton(currentAccount));
        else if(item.id==5) MonogramChatBehavior.showCopyPhotoButton(currentAccount,!MonogramChatBehavior.showCopyPhotoButton(currentAccount));
        else if(item.id==6) MonogramChatBehavior.showDetailsButton(currentAccount,!MonogramChatBehavior.showDetailsButton(currentAccount));
        else if(item.id==7) MonogramChatBehavior.groupMessageMenu(currentAccount,!MonogramChatBehavior.groupMessageMenu(currentAccount));
        else if(item.id==8) MonogramChatBehavior.replyColors(currentAccount,!MonogramChatBehavior.replyColors(currentAccount));
        else if(item.id==9) MonogramChatBehavior.replyEmoji(currentAccount,!MonogramChatBehavior.replyEmoji(currentAccount));
        else if(item.id==10) MonogramChatBehavior.replyBackground(currentAccount,!MonogramChatBehavior.replyBackground(currentAccount));
        else if(item.id==13) org.telegram.messenger.SharedConfig.toggleInappCamera();
        else if(item.id==16) {
            CharSequence[] choices={"Front camera","Back camera","Ask every time"};
            showDialog(new AlertDialog.Builder(getContext())
                    .setTitle("Opening camera")
                    .setItems(choices,(dialog,which) -> {
                        int mode=which==1 ? MonogramUiPreferences.ROUND_BACK
                                : which==2 ? MonogramUiPreferences.ROUND_ASK
                                : MonogramUiPreferences.ROUND_FRONT;
                        MonogramUiPreferences.setRoundVideoCamera(currentAccount,mode);
                        listView.adapter.update(true);
                    })
                    .setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel),null)
                    .create());
            return;
        }
        else if(item.id==11 || item.id==12) {
            if (!org.telegram.messenger.MonogramMaps.select(org.telegram.messenger.ApplicationLoader.applicationContext,
                    item.id == 12 ? org.telegram.messenger.MonogramMaps.GOOGLE : org.telegram.messenger.MonogramMaps.OPEN_STREET_MAP)) {
                org.telegram.ui.Components.BulletinFactory.of(this).createErrorBulletin(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ErrorOccurred)).show();
            }
        }
        else return;
        listView.adapter.update(true);
    }
    private static String roundVideoCameraLabel(int mode) {
        if(mode==MonogramUiPreferences.ROUND_BACK) return "Back camera";
        if(mode==MonogramUiPreferences.ROUND_ASK) return "Ask every time";
        return "Front camera";
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
