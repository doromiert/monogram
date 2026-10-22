// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.os.Build;
import android.content.Context;
import android.view.View;
import java.util.*;
import org.telegram.utils.settings.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.messenger.R;
import static org.telegram.utils.settings.MonogramAppearance.*;
public final class MonogramNavigationActivity extends MonogramSettingsActivity {
    private int orderSection;
    private MonogramAppearanceSlider backPreview;
    public MonogramNavigationActivity(int account) {currentAccount=account;}
    @Override protected CharSequence getTitle() {return "App Navigation";}
    @Override public View createView(Context context) {
        backPreview=new MonogramAppearanceSlider(context,"Predictive Back Intensity","Off","Max",200,
                Math.round(predictiveBack.get()*100),i -> predictiveBack.set(i/100f))
                .setValueFormatter(i -> java.math.BigDecimal.valueOf(i,2).stripTrailingZeros().toPlainString());
        View view=super.createView(context);
        listView.allowReorder(true);
        listView.listenReorder((section,rows)->{
            if(section!=orderSection) return;
            ArrayList<String> order=new ArrayList<>();for(UItem row:rows) order.add(MonogramNavigation.key(row.id-100));
            for(String key:MonogramNavigation.order())if(!order.contains(key))order.add(key);
            menuOrder.set(android.text.TextUtils.join(",",order));
        });
        return view;
    }
    private void select(String label,IntSetting setting,String... options) {
        showDialog(new AlertDialog.Builder(getContext()).setTitle(label).setItems(options,(d,i)->{setting.set(i);if(setting==tablet)listView.adapter.update(true);else refresh();}).setNegativeButton("Cancel",null).create());
    }
    private void refresh() {listView.adapter.update(true);if(parentLayout!=null) parentLayout.rebuildFragments(0);}
    private UItem destination(String key,boolean reorder) {
        return UItem.asButton(100+MonogramNavigation.index(key),key.startsWith("divider")?R.drawable.list_reorder:MonogramNavigation.icon(key),MonogramNavigation.label(key))
                .setReordering(reorder).onBind(v -> {
                    TextCell cell=(TextCell)v;
                    android.widget.ImageView handle=cell.getValueImageView();
                    handle.setImageResource(R.drawable.list_reorder);
                    handle.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),android.graphics.PorterDuff.Mode.SRC_IN);
                    handle.setVisibility(View.VISIBLE);
                });
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader("General"));
        items.add(UItem.asButton(1,"Tablet mode",new String[]{"Automatic","On","Off"}[bounded(tablet,2)]));
        items.add(UItem.asButton(2,"Bottom navigation bar",new String[]{"Show","Hide","Floating"}[bounded(bottomNavigation,2)]));
        items.add(UItem.asButton(3,"Screen transitions",new String[]{"Default","Android","Spring"}[bounded(transitions,2)]));
        items.add(UItem.asShadow("Hidden navigation remains accessible through the home menu. Tablet mode applies after restarting Monogram."));
        if(Build.VERSION.SDK_INT>=34) {
            items.add(UItem.asCustom(backPreview));
            items.add(UItem.asShadow("Change the amount of movement during the system back gesture."));
        }
        items.add(UItem.asHeader("App Navigation"));
        items.add(UItem.asCheck(5,"Navigation drawer").setChecked(drawer.get()));
        if(drawer.get()) items.add(UItem.asCheck(6,"Immersive drawer animation").setChecked(immersiveDrawer.get()));
        items.add(UItem.asShadow("Replaces the top-right menu with a swipe-to-open drawer and a menu button."));
        items.add(UItem.asHeader("Menu Items"));
        orderSection=adapter.reorderSectionStart();
        for(String key:MonogramNavigation.order()) {
            if(MonogramNavigation.hidden().contains(key))continue;
            items.add(destination(key,true));
        }
        adapter.reorderSectionEnd();
        items.add(UItem.asButton(7,R.drawable.msg_add,"Add Divider").accent());
        items.add(UItem.asShadow("Tap to show or hide items; hold to reorder. Layout is shared between the menu and the drawer."));
        if(!MonogramNavigation.hidden().isEmpty()) {
            items.add(UItem.asHeader("Hidden Menu Items"));
            for(String key:MonogramNavigation.order())if(MonogramNavigation.hidden().contains(key))items.add(destination(key,false));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(8,"Reset navigation"));
    }
    @Override protected void onClick(UItem item,View v,int p,float x,float y) {
        if(item.id>=100) {
            String key=MonogramNavigation.key(item.id-100);Set<String> hidden=MonogramNavigation.hidden();
            if(!hidden.remove(key)) hidden.add(key);menuHidden.set(android.text.TextUtils.join(",",hidden));refresh();return;
        }
        switch(item.id) {
            case 1:select("Tablet mode",tablet,"Automatic","On","Off");return;
            case 2:select("Bottom navigation bar",bottomNavigation,"Show","Hide","Floating");return;
            case 3:select("Screen transitions",transitions,"Default","Android","Spring");return;
            case 5:drawer.toggle();break;
            case 6:immersiveDrawer.toggle();break;
            case 7:
                ArrayList<String> order=MonogramNavigation.order();int id=0;while(order.contains("divider"+id))id++;
                order.add("divider"+id);menuOrder.set(android.text.TextUtils.join(",",order));break;
            case 8:
                menuOrder.set("profile,archive,group,contacts,calls,saved,settings");menuHidden.set("");tablet.set(0);bottomNavigation.set(2);transitions.set(0);predictiveBack.set(1);drawer.set(false);break;
            default:return;
        }
        refresh();
    }
    @Override protected boolean onLongClick(UItem item,View v,int p,float x,float y) {
        if(item.id<100)return false;String key=MonogramNavigation.key(item.id-100);if(!key.startsWith("divider"))return false;
        ArrayList<String> order=MonogramNavigation.order();order.remove(key);menuOrder.set(android.text.TextUtils.join(",",order));refresh();return true;
    }
}
