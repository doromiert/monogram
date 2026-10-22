// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;
import static org.telegram.utils.settings.MonogramAppearance.*;

final class MonogramNavigation {
    static int bottomBarHeightDp(boolean hosted) {
        if(!hosted || bottomNavigation.get()==1) return 0;
        return navigationStyle.get()?64:bottomNavigation.get()==0?DialogsActivity.MAIN_TABS_HEIGHT:DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS;
    }
    static int bottomBarFabOffsetDp(boolean hosted) {
        int height=bottomBarHeightDp(hosted);
        return bottomNavigation.get()==2 && !navigationStyle.get() ? Math.max(0,height-DialogsActivity.MAIN_TABS_MARGIN) : height;
    }
    static boolean closeDrawer(BaseFragment host, boolean close) {
        DrawerLayoutContainer root = host.getParentLayout() == null ? null : host.getParentLayout().getDrawerLayoutContainer();
        return root != null && root.closeNavigationDrawer(host, close);
    }
    private static final String[] KEYS={"profile","archive","group","contacts","calls","saved","settings","theme","appearance"};
    static ArrayList<String> order() {
        ArrayList<String> out=new ArrayList<>();String saved=menuOrder.get();
        if(saved!=null) for(String key:saved.split(",")) if((Arrays.asList(KEYS).contains(key)||key.matches("divider[0-9]{1,4}"))&&!out.contains(key))out.add(key);
        for(String key:KEYS) if(!out.contains(key))out.add(key);
        return out;
    }
    static Set<String> hidden() {String s=menuHidden.get();return new LinkedHashSet<>(Arrays.asList(s==null?new String[0]:s.split(",")));}
    static int index(String key) {if(key.startsWith("divider"))return 20+Integer.parseInt(key.substring(7));return Arrays.asList(KEYS).indexOf(key);}
    static String key(int id) {return id>=20?"divider"+(id-20):KEYS[Math.max(0,Math.min(KEYS.length-1,id))];}
    static String label(String key) {
        switch(key) {case "profile":return "My profile";case "archive":return "Archived chats";case "group":return "New group";case "contacts":return "Contacts";case "calls":return "Calls";case "saved":return "Bookmarks";case "settings":return "Settings";case "theme":return "Day / night mode";case "appearance":return "Appearance";default:return "Separator";}
    }
    static int icon(String key) {
        switch(key){case "profile":return R.drawable.msg_contacts;case "archive":return R.drawable.msg_archive;case "group":return R.drawable.msg_groups;case "contacts":return R.drawable.msg_contacts;case "calls":return R.drawable.msg_calls;case "saved":return R.drawable.msg_saved;case "theme":return R.drawable.menu_night_mode_24;case "appearance":return R.drawable.menu_edit_appearance;default:return R.drawable.msg_settings;}
    }
    static void open(BaseFragment host,String key) {
        Bundle args=new Bundle();
        switch(key) {
            case "profile":args.putLong("user_id",UserConfig.getInstance(host.getCurrentAccount()).getClientUserId());args.putBoolean("my_profile",true);host.presentFragment(new ProfileActivity(args));break;
            case "archive":args.putInt("folderId",1);host.presentFragment(new DialogsActivity(args));break;
            case "group":host.presentFragment(new GroupCreateActivity(args));break;
            case "contacts":args.putBoolean("needFinishFragment",false);host.presentFragment(new ContactsActivity(args));break;
            case "calls":host.presentFragment(new CallLogActivity(args));break;
            case "saved":args.putLong("user_id",UserConfig.getInstance(host.getCurrentAccount()).getClientUserId());host.presentFragment(new ChatActivity(args));break;
            case "settings":host.presentFragment(new SettingsActivity());break;
            case "appearance":host.presentFragment(new MonogramAppearanceActivity(host.getCurrentAccount()));break;
            case "theme":if(host instanceof DialogsActivity)((DialogsActivity)host).monogramToggleNight();break;
        }
    }
    static void show(BaseFragment host,View anchor) {
        if(drawer.get()) {showDrawer(host);return;}
        ItemOptions menu=ItemOptions.makeOptions(host,anchor);
        for(String key:order()) if(!hidden().contains(key)) {
            if(key.startsWith("divider"))menu.addGap();else menu.add(icon(key),label(key),()->open(host,key));
        }
        menu.show();
    }
    static void showDrawer(BaseFragment host) {
        if (!(host instanceof DialogsActivity) || host.getParentLayout() == null) return;
        DrawerLayoutContainer root = host.getParentLayout().getDrawerLayoutContainer();
        if (root != null) root.openNavigationDrawer((DialogsActivity) host);
    }
}
