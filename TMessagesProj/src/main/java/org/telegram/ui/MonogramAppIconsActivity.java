// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import org.telegram.ui.Cells.AppIconsSelectorCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
public final class MonogramAppIconsActivity extends MonogramSettingsActivity {
    private AppIconsSelectorCell icons;
    public MonogramAppIconsActivity(int account) {currentAccount=account;}
    @Override public View createView(Context context) {icons=new AppIconsSelectorCell(context,this,currentAccount);return super.createView(context);}
    @Override protected CharSequence getTitle() {return "App icon";}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asCustom(icons));items.add(UItem.asShadow("Choose the icon shown on your home screen."));
    }
    @Override protected void onClick(UItem i,View v,int p,float x,float y) {}
    @Override protected boolean onLongClick(UItem i,View v,int p,float x,float y) {return false;}
}
