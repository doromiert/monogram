// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import java.util.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.*;
/** Search native drawable names rather than requiring numeric build-specific resource IDs. */
public final class MonogramIconResourceActivity extends MonogramSettingsActivity {
    private final java.util.function.Consumer<String> chosen;
    private final ArrayList<String> all=new ArrayList<>(),shown=new ArrayList<>();
    private String query="";
    public MonogramIconResourceActivity(int account,java.util.function.Consumer<String> chosen) {
        currentAccount=account;this.chosen=chosen;
        all.addAll(MonogramIconPacks.resourceNames());
    }
    @Override protected CharSequence getTitle(){return "Choose icon";}
    @Override public View createView(Context context) {
        View view=super.createView(context);
        actionBar.createMenu().addItem(0,R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener(){
            @Override public void onTextChanged(EditText input){query=input.getText().toString().toLowerCase(Locale.ROOT);listView.adapter.update(false);}
        });return view;
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        shown.clear();for(String name:all)if(name.contains(query)){int i=shown.size();shown.add(name);int id=name.startsWith("raw.")?0:MonogramIconPacks.resourceId(getContext().getResources(),name);items.add(UItem.asButton(100+i,id,name));}
        if(shown.isEmpty())items.add(UItem.asShadow("No matching icons"));
    }
    @Override protected void onClick(UItem item,View v,int p,float x,float y) {if(item.id>=100&&item.id-100<shown.size()){String name=shown.get(item.id-100);finishFragment();AndroidUtilities.runOnUIThread(()->chosen.accept(name),250);}}
    @Override protected boolean onLongClick(UItem item,View v,int p,float x,float y){return false;}
}
