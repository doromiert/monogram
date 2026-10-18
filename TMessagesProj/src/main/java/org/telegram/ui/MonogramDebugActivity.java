// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.graphics.Typeface;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.negzero.monogram.xmpp.XmppDebugLog;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

/** Local diagnostics using the native settings section renderer. */
public final class MonogramDebugActivity extends MonogramSettingsActivity {
    private List<XmppDebugLog.Entry> entries=Collections.emptyList();
    public MonogramDebugActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return "XMPP debugging"; }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        XmppDebugLog log=MonogramController.get(currentAccount).debugLog;
        items.add(UItem.asCheck(1,"Capture message XML").setChecked(log.enabled()));
        items.add(UItem.asShadow("Captures new sent and received message stanzas, including history responses. Encrypted messages show their wire XML, not decrypted text. Capture stays in memory for this account: up to 100 entries, with large entries truncated. Turning capture off, logging out or restarting clears it. While enabled, long-press a chat message for Message diagnostics."));
        items.add(UItem.asButton(2,R.drawable.msg_retry,"Refresh"));
        items.add(UItem.asButton(3,R.drawable.msg_delete,"Clear captured XML").red());
        items.add(UItem.asShadow(null));
        entries=log.snapshot();Collections.reverse(entries);
        items.add(UItem.asHeader("Captured messages"));
        if(entries.isEmpty()) items.add(UItem.asShadow(log.enabled() ? "No XML captured yet. Send or receive a sticker, then tap Refresh." : "Turn on capture to view message XML."));
        for(int i=0;i<entries.size();i++) {
            XmppDebugLog.Entry entry=entries.get(i);
            items.add(UItem.asButton(100+i,entry.phase+" · "+android.text.format.DateFormat.format("HH:mm:ss",entry.time),"#"+entry.number));
        }
        if(!entries.isEmpty()) items.add(UItem.asShadow("Tap an entry to view and copy its XML. Copied XML may contain message text, addresses and attachment keys."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        XmppDebugLog log=MonogramController.get(currentAccount).debugLog;
        if(item.id==1) MonogramController.get(currentAccount).debugCapture(!log.enabled());
        else if(item.id==3) log.clear();
        else if(item.id>=100 && item.id-100<entries.size()) {
            XmppDebugLog.Entry entry=entries.get(item.id-100);showText(this,entry.phase,entry.text);return;
        }
        if(listView!=null) listView.adapter.update(true);
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
    @Override public void onResume() { super.onResume();if(listView!=null) listView.adapter.update(true); }
    public static void showText(BaseFragment fragment,String title,String content) {
        if(fragment.getParentActivity()==null) return;
        TextView text=new TextView(fragment.getContext());
        text.setText(content);text.setTextSize(12);text.setTypeface(Typeface.MONOSPACE);text.setTextIsSelectable(true);
        text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,fragment.getResourceProvider()));
        int padding=AndroidUtilities.dp(20);text.setPadding(padding,padding,padding,padding);
        ScrollView scroll=new ScrollView(fragment.getContext());scroll.setFillViewport(true);scroll.addView(text);
        fragment.showDialog(new AlertDialog.Builder(fragment.getContext(),fragment.getResourceProvider()).setTitle(title).setView(scroll)
                .setPositiveButton(LocaleController.getString(R.string.Close),(dialog,which)->{})
                .setNeutralButton(LocaleController.getString(R.string.Copy),(dialog,which)->AndroidUtilities.addToClipboard(content)).create());
    }
}
