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
    private static final java.util.WeakHashMap<BaseFragment,Runnable> drawers=new java.util.WeakHashMap<>();
    static boolean closeDrawer(BaseFragment host,boolean close) {Runnable action=drawers.get(host);if(action==null)return false;if(close)action.run();return true;}
    private static final String[] KEYS={"profile","archive","group","contacts","calls","saved","settings","theme"};
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
        switch(key) {case "profile":return "My profile";case "archive":return "Archived chats";case "group":return "New group";case "contacts":return "Contacts";case "calls":return "Calls";case "saved":return "Bookmarks";case "settings":return "Settings";case "theme":return "Day / night mode";default:return "Separator";}
    }
    static int icon(String key) {
        switch(key){case "profile":return R.drawable.msg_contacts;case "archive":return R.drawable.msg_archive;case "group":return R.drawable.msg_groups;case "contacts":return R.drawable.msg_contacts;case "calls":return R.drawable.msg_calls;case "saved":return R.drawable.msg_saved;case "theme":return R.drawable.menu_night_mode_24;default:return R.drawable.msg_settings;}
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
            case "theme":if(host instanceof DialogsActivity)((DialogsActivity)host).monogramToggleNight();break;
        }
    }
    static void show(BaseFragment host,View anchor) {
        if(drawer.get()) {showDrawer(host);return;}
        ItemOptions menu=ItemOptions.makeOptions(host,anchor);
        for(String key:order()) if(!hidden().contains(key)) {
            if(key.startsWith("divider"))menu.addGap();else menu.add(icon(key),label(key),()->open(host,key));
        }
        // Keep settings reachable even if every configurable menu entry was hidden.
        menu.addGap();menu.add(R.drawable.menu_edit_appearance,"Appearance",()->host.presentFragment(new MonogramAppearanceActivity(host.getCurrentAccount())));menu.show();
    }
    static void showDrawer(BaseFragment host) {
        if(drawers.containsKey(host))return;
        if(!(host.getFragmentView() instanceof android.view.ViewGroup))return;
        ViewGroup root=(ViewGroup)host.getFragmentView();
        FrameLayout overlay=new FrameLayout(root.getContext());overlay.setClickable(true);overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        View dim=new View(root.getContext());dim.setBackgroundColor(0x66000000);overlay.addView(dim,LayoutHelper.createFrame(-1,-1));
        ScrollView scroll=new ScrollView(root.getContext());scroll.setFillViewport(true);scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout rows=new LinearLayout(root.getContext()) {
            private org.telegram.ui.Components.SnowflakesEffect snow;
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if(org.telegram.utils.settings.MonogramAppearance.snow.get()) {
                    if(snow==null){snow=new org.telegram.ui.Components.SnowflakesEffect(1);snow.setForcedColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));}
                    snow.onDraw(this,canvas);
                }
            }
        };rows.setWillNotDraw(false);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(0,AndroidUtilities.statusBarHeight+AndroidUtilities.dp(16),0,AndroidUtilities.dp(24));scroll.addView(rows);
        TextView title=new TextView(root.getContext());title.setText("Monogram");title.setTextSize(23);title.setTypeface(AndroidUtilities.bold());title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));title.setPadding(AndroidUtilities.dp(24),0,AndroidUtilities.dp(24),AndroidUtilities.dp(24));rows.addView(title);
        boolean rtl=LocaleController.isRTL;int width=Math.min(AndroidUtilities.dp(304),Math.max(AndroidUtilities.dp(240),root.getWidth()-AndroidUtilities.dp(56)));
        overlay.addView(scroll,LayoutHelper.createFrame(width/AndroidUtilities.density,-1,rtl?Gravity.RIGHT:Gravity.LEFT));
        Runnable close=()->{if(drawers.remove(host)==null)return;scroll.animate().translationX(rtl?width:-width).setDuration(220).start();dim.animate().alpha(0).setDuration(220).withEndAction(()->root.removeView(overlay)).start();};
        drawers.put(host,close);
        dim.setOnClickListener(v->close.run());
        for(String key:order()) if(!hidden().contains(key)) {
            if(key.startsWith("divider")) {View line=new View(root.getContext());line.setBackgroundColor(Theme.getColor(Theme.key_divider));rows.addView(line,LayoutHelper.createLinear(-1,1,16,8,16,8));continue;}
            addRow(rows,label(key),icon(key),()->{close.run();AndroidUtilities.runOnUIThread(()->open(host,key),220);});
        }
        addRow(rows,"Appearance",R.drawable.menu_edit_appearance,()->{close.run();AndroidUtilities.runOnUIThread(()->host.presentFragment(new MonogramAppearanceActivity(host.getCurrentAccount())),220);});
        root.addView(overlay,new ViewGroup.LayoutParams(-1,-1));overlay.setElevation(AndroidUtilities.dp(24));
        scroll.setTranslationX(rtl?width:-width);dim.setAlpha(0);scroll.animate().translationX(0).setDuration(280).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();dim.animate().alpha(1).setDuration(280).start();
        if(immersiveDrawer.get()){scroll.setScaleY(.96f);scroll.animate().scaleY(1).translationX(0).setDuration(280).start();}
        overlay.setFocusableInTouchMode(true);overlay.requestFocus();overlay.setOnKeyListener((v,k,e)->{if(k==android.view.KeyEvent.KEYCODE_BACK&&e.getAction()==android.view.KeyEvent.ACTION_UP){close.run();return true;}return false;});
    }
    private static void addRow(LinearLayout rows,String title,int icon,Runnable action) {
        org.telegram.ui.Cells.TextCell row=new org.telegram.ui.Cells.TextCell(rows.getContext());row.setTextAndIcon(title,icon,false);row.setBackground(Theme.getSelectorDrawable(false));row.setOnClickListener(v->action.run());rows.addView(row,LayoutHelper.createLinear(-1,52));
    }
}
