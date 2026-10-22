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
    private MonogramAppearancePreview preview;
    @Override public View createView(Context context) {preview=new MonogramAppearancePreview(context,currentAccount);return super.createView(context);}
    private final ArrayList<BooleanSetting> flags = new ArrayList<>();
    public MonogramAppearanceActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return "Appearance"; }
    private void flag(ArrayList<UItem> items, String label, BooleanSetting setting) {
        int id=100+flags.size(); flags.add(setting);
        items.add(UItem.asCheck(id,label).setChecked(setting.get()));
    }
    private void choice(ArrayList<UItem> items,int id,String label,IntSetting setting,String... values) {
        items.add(UItem.asButton(id,label,values[bounded(setting,values.length-1)]));
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        flags.clear();
        items.add(UItem.asCustom(preview));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Avatars"));
        items.add(UItem.asIntSlideView(1,0,MonogramUiPreferences.avatarCorners(currentAccount),28,v -> v+" dp",v -> {
            MonogramUiPreferences.setAvatarCorners(currentAccount,v);rebuild();
        }));
        flag(items,"Use the same shape for forums and chats",singleCornerRadius);
        items.add(UItem.asShadow("Forums use the same avatar shape as other chats."));
        items.add(UItem.asHeader("Chat list"));
        flag(items,"Snow animation",snow);
        items.add(UItem.asCheck(2,"Center title").setChecked(MonogramUiPreferences.centerHomeTitle(currentAccount)));
        flag(items,"Hide stories",hideStories);
        items.add(UItem.asCheck(3,"Hide compose button").setChecked(MonogramUiPreferences.hideComposeButton(currentAccount)));
        items.add(UItem.asCheck(4,"Always collapse home search").setChecked(MonogramUiPreferences.collapseHomeSearch(currentAccount)));
        flag(items,"Sender mini avatars",senderAvatars);
        choice(items,5,"Home title",title,"Monogram","Jabber ID","Your name","Chats");
        items.add(UItem.asShadow("Search remains available from its icon. Hiding stories does not delete them."));
        items.add(UItem.asHeader("Folders"));
        items.add(UItem.asButton(6,"Folder tabs",new String[]{"Names only","Icons only","Icons and names"}[MonogramFolderIcons.displayMode(currentAccount)]));
        choice(items,7,"Folder counters",folderCounters,"All chats","Unmuted chats","Off");
        flag(items,"Hide All Chats",hideAllChats);
        items.add(UItem.asShadow("All Chats is hidden only when there are at least two custom folders."));
        items.add(UItem.asButton(8,"App Navigation"));
        items.add(UItem.asButton(9,"App icon"));
        items.add(UItem.asButton(10,"Icon packs"));
        items.add(UItem.asButton(11,"Pills"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Appearance"));
        flag(items,"Square floating buttons",squareFab);
        flag(items,"Use system fonts",systemFonts);
        items.add(UItem.asCheck(12,"Use system emoji").setChecked(SharedConfig.useSystemEmoji));
        items.add(UItem.asButton(13,"Material Design 3",materialEnabled()?"On":materialAny()?"Custom":"Off"));
        flag(items,"Material loading indicator",loading);
        flag(items,"Material sliders",slider);
        flag(items,"Material switches",switches);
        flag(items,"Material chat header",chatHeader);
        flag(items,"Material navigation bar",navigationStyle);
        flag(items,"Material floating buttons",fabStyle);
        flag(items,"Gooey avatar animation",gooeyAvatar);
        flag(items,"Custom chat themes",customThemes);
        items.add(UItem.asShadow("Custom chat themes can be disabled without forgetting your saved choices."));
        items.add(UItem.asHeader("Sections"));
        items.add(UItem.asIntSlideView(1,0,bounded(sectionRadius,28),28,v -> v==0?"Square":v+" dp",v -> {sectionRadius.set(v);rebuild();}));
        flag(items,"Separate section headers",separateHeaders);
        choice(items,15,"Dividers",dividers,"Hidden","Lines","Segments");
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Blur"));
        choice(items,16,"Glass outline",glassOutline,"Glare","Solid","Hidden");
        flag(items,"Glass message menus",glassMenu);
        flag(items,"Force blur",forceBlur);
        items.add(UItem.asShadow("Force blur overrides the performance preference on supported devices."));
    }
    private boolean materialAny() {return loading.get()||slider.get()||switches.get()||chatHeader.get()||navigationStyle.get()||fabStyle.get();}
    private boolean materialEnabled() {return loading.get()&&slider.get()&&switches.get()&&chatHeader.get()&&navigationStyle.get()&&fabStyle.get();}
    private void choose(String label,int selected,String[] values,java.util.function.IntConsumer save) {
        showDialog(new AlertDialog.Builder(getContext()).setTitle(label).setItems(values,(d,i)->{save.accept(i);rebuild();})
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel),null).create());
    }
    private void select(String label,IntSetting setting,String... values) {choose(label,setting.get(),values,setting::set);}
    private void rebuild() {
        if(preview!=null)preview.invalidate();
        org.telegram.messenger.AndroidUtilities.mediumTypeface=null;
        if(listView!=null) {listView.setSections();listView.adapter.update(false);listView.invalidateItemDecorations();}
        if(parentLayout!=null) parentLayout.rebuildFragments(0);
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id>=100 && item.id<100+flags.size()) {flags.get(item.id-100).toggle();rebuild();return;}
        switch(item.id) {
            case 2:MonogramUiPreferences.setCenterHomeTitle(currentAccount,!MonogramUiPreferences.centerHomeTitle(currentAccount));break;
            case 3:MonogramUiPreferences.setHideComposeButton(currentAccount,!MonogramUiPreferences.hideComposeButton(currentAccount));break;
            case 4:MonogramUiPreferences.setCollapseHomeSearch(currentAccount,!MonogramUiPreferences.collapseHomeSearch(currentAccount));break;
            case 5:select("Home title",title,"Monogram","Jabber ID","Your name","Chats");return;
            case 6:choose("Folder tabs",MonogramFolderIcons.displayMode(currentAccount),new String[]{"Names only","Icons only","Icons and names"},i -> MonogramFolderIcons.setDisplayMode(currentAccount,i));return;
            case 7:select("Folder counters",folderCounters,"All chats","Unmuted chats","Off");return;
            case 8:presentFragment(new MonogramNavigationActivity(currentAccount));return;
            case 9:presentFragment(new MonogramAppIconsActivity(currentAccount));return;
            case 10:presentFragment(new MonogramIconPacksActivity(currentAccount));return;
            case 11:presentFragment(new MonogramPillsActivity(currentAccount));return;
            case 12:SharedConfig.useSystemEmoji=!SharedConfig.useSystemEmoji;org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putBoolean("useSystemEmoji",SharedConfig.useSystemEmoji).apply();break;
            case 13:
                boolean on=!materialEnabled();
                for(BooleanSetting s:new BooleanSetting[]{loading,slider,switches,chatHeader,navigationStyle,fabStyle}) s.set(on);
                break;
            case 15:select("Dividers",dividers,"Hidden","Lines","Segments");return;
            case 16:select("Glass outline",glassOutline,"Glare","Solid","Hidden");return;
            default:return;
        }
        rebuild();
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) {return false;}
}
