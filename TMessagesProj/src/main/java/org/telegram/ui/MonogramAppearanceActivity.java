// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.xmpp.MonogramFolderIcons;
import org.telegram.messenger.xmpp.MonogramUiPreferences;
import org.telegram.utils.settings.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import static org.telegram.utils.settings.MonogramAppearance.*;

/** Appearance controls use the same native section, slider and theme system as Chat Settings. */
public final class MonogramAppearanceActivity extends MonogramSettingsActivity {
    private MonogramAppearancePreview preview,chatPreview,folderPreview,fabPreview;
    private boolean materialExpanded;
    @Override public View createView(Context context) {
        preview=new MonogramAppearancePreview(context,currentAccount,MonogramAppearancePreview.Kind.AVATAR,this::refreshOtherScreens);
        chatPreview=new MonogramAppearancePreview(context,currentAccount,MonogramAppearancePreview.Kind.CHAT_LIST,this::refreshOtherScreens);
        folderPreview=new MonogramAppearancePreview(context,currentAccount,MonogramAppearancePreview.Kind.FOLDERS,this::refreshOtherScreens);
        fabPreview=new MonogramAppearancePreview(context,currentAccount,MonogramAppearancePreview.Kind.FAB,this::refreshOtherScreens);
        return super.createView(context);
    }
    private void refreshOtherScreens() {if(parentLayout!=null) parentLayout.rebuildFragments(0);}
    private final ArrayList<BooleanSetting> flags = new ArrayList<>();
    public MonogramAppearanceActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return "Appearance"; }
    private void flag(ArrayList<UItem> items, String label, BooleanSetting setting) {
        int id=100+flags.size(); flags.add(setting);
        items.add(UItem.asCheck(id,label).setChecked(setting.get()));
    }
    private void flag(ArrayList<UItem> items,String label,BooleanSetting setting,String description) {
        int id=100+flags.size();flags.add(setting);
        items.add(UItem.asCheck(id,label,description,true).setChecked(setting.get()));
    }
    private void materialFlag(ArrayList<UItem> items,String label,BooleanSetting setting) {
        int id=100+flags.size();flags.add(setting);
        UItem row=UItem.asRoundCheckbox(id,label).setChecked(setting.get()).pad();
        row.hideDivider=true;items.add(row);
    }
    private void choice(ArrayList<UItem> items,int id,String label,IntSetting setting,String... values) {
        items.add(UItem.asButton(id,label,values[bounded(setting,values.length-1)]));
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        flags.clear();
        items.add(UItem.asCustom(preview));
        flag(items,"Single Corner Radius",singleCornerRadius);
        items.add(UItem.asShadow("Forums use the same avatar shape as other chats."));
        items.add(UItem.asHeader("Chat List"));
        items.add(UItem.asCustom(chatPreview));
        flag(items,"Force Snowflakes",snow,"Falling snowflakes appear in the action bar, side menu, and chat backgrounds.");
        items.add(UItem.asCheck(2,"Center title").setChecked(MonogramUiPreferences.centerHomeTitle(currentAccount)));
        flag(items,"Hide stories",hideStories);
        items.add(UItem.asCheck(3,"Hide compose button").setChecked(MonogramUiPreferences.hideComposeButton(currentAccount)));
        items.add(UItem.asCheck(4,"Always collapse home search").setChecked(MonogramUiPreferences.collapseHomeSearch(currentAccount)));
        flag(items,"Sender mini avatars",senderAvatars);
        choice(items,5,"Home title",title,"Monogram","XMPP ID","Your name","Chats");
        items.add(UItem.asShadow("Search remains available from its icon. Hiding stories does not delete them."));
        items.add(UItem.asHeader("Chat Folders"));
        items.add(UItem.asCustom(folderPreview));
        items.add(UItem.asButton(6,"Folder tabs",new String[]{"Names only","Icons only","Icons and names"}[MonogramFolderIcons.displayMode(currentAccount)]));
        choice(items,7,"Folder counters",folderCounters,"All chats","Unmuted chats","Off");
        flag(items,"Hide All Chats",hideAllChats);
        items.add(UItem.asShadow("All Chats is hidden only when there are at least two custom folders."));
        items.add(UItem.asButton(8,org.telegram.messenger.R.drawable.msg_newphone,"App Navigation"));
        items.add(UItem.asButton(9,org.telegram.messenger.R.drawable.menu_edit_appearance,"App icon"));
        items.add(UItem.asButton(10,org.telegram.messenger.R.drawable.msg_sticker,"Icon packs"));
        items.add(UItem.asButton(11,org.telegram.messenger.R.drawable.outline_header_search,"Pills"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Appearance"));
        items.add(UItem.asCustom(fabPreview));
        flag(items,"Use system fonts",systemFonts);
        items.add(UItem.asCheck(12,"Use system emoji").setChecked(SharedConfig.useSystemEmoji));
        items.add(UItem.asExpandableSwitch(13,"Material Design 3",materialCount()+"/6")
                .setChecked(materialAny()).setCollapsed(!materialExpanded).setClickCallback(v -> {
                    boolean on=!materialAny();
                    for(BooleanSetting s:new BooleanSetting[]{loading,slider,switches,chatHeader,navigationStyle,fabStyle}) s.set(on);
                    rebuild();
                }));
        if(materialExpanded) {
            materialFlag(items,"Loading Style",loading);
            materialFlag(items,"Slider Style",slider);
            materialFlag(items,"Switch Style",switches);
            materialFlag(items,"Chat Header",chatHeader);
            materialFlag(items,"Bottom Navigation Bar",navigationStyle);
            materialFlag(items,"Floating Button",fabStyle);
        }
        flag(items,"Gooey avatar animation",gooeyAvatar);
        flag(items,"Custom chat themes",customThemes);
        items.add(UItem.asShadow("Custom chat themes can be disabled without forgetting your saved choices."));
        items.add(UItem.asHeader("Sections"));
        items.add(UItem.asIntSlideView(1,0,bounded(sectionRadius,28),28,v -> v==0?"Square":v+" dp",v -> {sectionRadius.set(v);if(listView!=null) {listView.setSections();listView.invalidateItemDecorations();listView.invalidate();}refreshOtherScreens();}));
        items.add(UItem.asCheck(14,"Separate section headers").setChecked(separatedHeaders()).setEnabled(dividers.get()!=2));
        choice(items,15,"Dividers",dividers,"Hidden","Lines","Segments");
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Blur Options"));
        flag(items,"Chat header background",chatHeaderBackground,"Show the background behind the avatar, name, and status in chats.");
        choice(items,16,"Glass outline",glassOutline,"Glare","Solid","Hidden");
        flag(items,"Glass Message Menu",glassMenu,"The message menu, header menu, and reaction bar use frosted glass.");
        flag(items,"Force blur",forceBlur);
        items.add(UItem.asShadow("Force blur overrides the performance preference on supported devices."));
    }
    private boolean materialAny() {return loading.get()||slider.get()||switches.get()||chatHeader.get()||navigationStyle.get()||fabStyle.get();}
    private int materialCount() {int count=0;for(BooleanSetting s:new BooleanSetting[]{loading,slider,switches,chatHeader,navigationStyle,fabStyle}) if(s.get()) count++;return count;}
    private void choose(String label,int selected,String[] values,java.util.function.IntConsumer save) {
        showDialog(new AlertDialog.Builder(getContext()).setTitle(label).setItems(values,(d,i)->{save.accept(i);rebuild();})
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel),null).create());
    }
    private void select(String label,IntSetting setting,String... values) {choose(label,setting.get(),values,setting::set);}
    private void rebuild() {
        for(MonogramAppearancePreview sample:new MonogramAppearancePreview[]{preview,chatPreview,folderPreview,fabPreview}) if(sample!=null) sample.refresh();
        if(listView!=null) {
            listView.adapter.update(true);listView.setSections();
            listView.invalidateItemDecorations();
            for(int i=0;i<listView.getChildCount();i++) refreshAttachedStyle(listView.getChildAt(i));
        }
        if(parentLayout!=null) parentLayout.rebuildFragments(0);
    }
    private void refreshAttachedStyle(View view) {
        if(view instanceof org.telegram.ui.Components.Switch) ((org.telegram.ui.Components.Switch)view).refreshAppearanceStyle();
        view.requestLayout();view.invalidate();
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) refreshAttachedStyle(group.getChildAt(i));
        }
    }
    private void animateToggle(UItem item,View view,boolean checked) {
        item.setChecked(checked);
        if(view instanceof org.telegram.ui.Cells.TextCheckCell) ((org.telegram.ui.Cells.TextCheckCell)view).setChecked(checked);
        else if(view instanceof org.telegram.ui.Cells.CheckBoxCell) ((org.telegram.ui.Cells.CheckBoxCell)view).setChecked(checked,true);
        else if(view instanceof org.telegram.ui.Cells.TextCheckCell2) ((org.telegram.ui.Cells.TextCheckCell2)view).setChecked(checked);
    }
    private void refreshFontsAfterToggle() {
        // Recreate text views only after the initiating switch has finished animating.
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            org.telegram.ui.ActionBar.Theme.applyCommonTheme();org.telegram.messenger.AndroidUtilities.mediumTypeface=null;
            if(listView==null || getContext()==null) return;
            android.os.Parcelable position=listView.getLayoutManager().onSaveInstanceState();
            listView.setAdapter(null);listView.getRecycledViewPool().clear();listView.adapter.updateWithoutNotify();
            listView.setAdapter(listView.adapter);listView.setSections();listView.getLayoutManager().onRestoreInstanceState(position);
        },500);
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id>=100 && item.id<100+flags.size()) {
            BooleanSetting setting=flags.get(item.id-100);boolean checked=setting.toggle();animateToggle(item,view,checked);rebuild();
            if(setting==systemFonts) refreshFontsAfterToggle();return;
        }
        switch(item.id) {
            case 2:MonogramUiPreferences.setCenterHomeTitle(currentAccount,!MonogramUiPreferences.centerHomeTitle(currentAccount));break;
            case 3:MonogramUiPreferences.setHideComposeButton(currentAccount,!MonogramUiPreferences.hideComposeButton(currentAccount));break;
            case 4:MonogramUiPreferences.setCollapseHomeSearch(currentAccount,!MonogramUiPreferences.collapseHomeSearch(currentAccount));break;
            case 5:select("Home title",title,"Monogram","XMPP ID","Your name","Chats");return;
            case 6:choose("Folder tabs",MonogramFolderIcons.displayMode(currentAccount),new String[]{"Names only","Icons only","Icons and names"},i -> MonogramFolderIcons.setDisplayMode(currentAccount,i));return;
            case 7:select("Folder counters",folderCounters,"All chats","Unmuted chats","Off");return;
            case 8:presentFragment(new MonogramNavigationActivity(currentAccount));return;
            case 9:presentFragment(new MonogramAppIconsActivity(currentAccount));return;
            case 10:presentFragment(new MonogramIconPacksActivity(currentAccount));return;
            case 11:presentFragment(new MonogramPillsActivity(currentAccount));return;
            case 12:SharedConfig.useSystemEmoji=!SharedConfig.useSystemEmoji;org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putBoolean("useSystemEmoji",SharedConfig.useSystemEmoji).apply();break;
            case 13:
                materialExpanded=!materialExpanded;listView.adapter.update(true);return;
            case 14:if(dividers.get()!=2) separateHeaders.toggle();break;
            case 15:choose("Dividers",dividers.get(),new String[]{"Hidden","Lines","Segments"},i -> {dividers.set(i);if(i==2) separateHeaders.set(true);});return;
            case 16:select("Glass outline",glassOutline,"Glare","Solid","Hidden");return;
            default:return;
        }
        rebuild();
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) {return false;}
}
