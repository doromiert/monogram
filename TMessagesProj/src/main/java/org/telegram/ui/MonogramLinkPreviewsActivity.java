// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.messenger.xmpp.MonogramLinkPreviews;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

/** Native settings controls make remote fetching an explicit privacy choice. */
public final class MonogramLinkPreviewsActivity extends MonogramSettingsActivity {
    public MonogramLinkPreviewsActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return "Automatic link embeds"; }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        MonogramLinkPreviews previews=MonogramController.get(currentAccount).linkPreviews;
        items.add(UItem.asHeader("On this device"));
        items.add(UItem.asCheck(1,"Automatic link embeds").setChecked(previews.enabled()));
        items.add(UItem.asShadow("Off by default. When enabled, opening a chat or receiving a message can connect directly to the linked website and its thumbnail host. They can see your IP address and which URL was requested. Only public HTTPS links are fetched; no cookies, JavaScript or preview service is used."));
        items.add(UItem.asCheck(2,"Also fetch in encrypted chats").setChecked(previews.encryptedEnabled()).setEnabled(previews.enabled()));
        items.add(UItem.asShadow("OMEMO protects messages in transit, but visiting a link exposes that URL to the website. Previews are created after decryption, cached privately on this phone and never sent to your XMPP server or other chat members. Existing cached previews can still be displayed when fetching is disabled."));
        items.add(UItem.asButton(3,"Clear preview cache"));
        items.add(UItem.asShadow("Removes downloaded preview metadata and thumbnails. Reopen a chat to refresh its displayed previews. Plain links and your messages are kept."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        MonogramController controller=MonogramController.get(currentAccount);MonogramLinkPreviews previews=controller.linkPreviews;
        if(item.id==1 || item.id==2) {
            boolean current=item.id==1 ? previews.enabled() : previews.encryptedEnabled();
            if(current) { if(item.id==1) previews.enabled(false);else previews.encryptedEnabled(false);controller.refreshLinkPreviews();listView.adapter.update(true);return; }
            showDialog(new AlertDialog.Builder(getContext()).setTitle(item.id==1 ? "Fetch link previews?" : "Fetch links from encrypted chats?")
                    .setMessage("Linked websites and thumbnail hosts will see your IP address and the requested URL. Your message text is not sent to a preview service. This choice applies to this account on this phone.")
                    .setPositiveButton("Allow",(dialog,which) -> { if(item.id==1) previews.enabled(true);else previews.encryptedEnabled(true);controller.refreshLinkPreviews();listView.adapter.update(true); })
                    .setNegativeButton("Cancel",null).create());
        } else if(item.id==3) showDialog(new AlertDialog.Builder(getContext()).setTitle("Clear preview cache?")
                .setMessage("Cached metadata and thumbnails will be removed. New previews can download again while fetching is enabled.")
                .setPositiveButton("Clear",(dialog,which) -> controller.clearLinkPreviews()).setNegativeButton("Cancel",null).create());
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
