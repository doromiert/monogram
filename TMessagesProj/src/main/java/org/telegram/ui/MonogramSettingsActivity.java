// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.UniversalFragment;

/** Uses the same section renderer and adaptive navigation as native Chat Settings. */
public abstract class MonogramSettingsActivity extends UniversalFragment {
    @Override public View createView(Context context) {
        View view=super.createView(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        listView.setSections();
        listView.setVerticalScrollBarEnabled(false);
        actionBar.setAdaptiveBackground(listView);
        return view;
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> colors=new ArrayList<>();
        colors.add(new ThemeDescription(fragmentView,ThemeDescription.FLAG_BACKGROUND,null,null,null,null,Theme.key_windowBackgroundGray));
        colors.add(new ThemeDescription(actionBar,ThemeDescription.FLAG_AB_ITEMSCOLOR,null,null,null,null,Theme.key_actionBarDefaultIcon));
        colors.add(new ThemeDescription(actionBar,ThemeDescription.FLAG_AB_TITLECOLOR,null,null,null,null,Theme.key_actionBarDefaultTitle));
        colors.add(new ThemeDescription(actionBar,ThemeDescription.FLAG_AB_SELECTORCOLOR,null,null,null,null,Theme.key_actionBarDefaultSelector));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_SELECTOR,null,null,null,null,Theme.key_settings_listSelector));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_LISTGLOWCOLOR,null,null,null,null,Theme.key_actionBarDefault));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,new Class[]{HeaderCell.class,TextCell.class,TextCheckCell.class,TextSettingsCell.class,EditTextCell.class},null,null,null,Theme.key_windowBackgroundWhite));
        colors.add(new ThemeDescription(listView,0,new Class[]{View.class},Theme.dividerPaint,null,null,Theme.key_divider));
        colors.add(new ThemeDescription(listView,0,new Class[]{HeaderCell.class},new String[]{"textView"},null,null,null,Theme.key_windowBackgroundWhiteBlueHeader));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCheckCell.class,TextSettingsCell.class},new String[]{"textView","textView"},null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        for(int key:new int[]{Theme.key_windowBackgroundWhiteBlackText,Theme.key_windowBackgroundWhiteBlueText4,Theme.key_text_RedRegular}) {
            colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_CHECKTAG,new Class[]{TextCell.class},new String[]{"textView"},null,null,null,key));
        }
        for(int key:new int[]{Theme.key_windowBackgroundWhiteGrayIcon,Theme.key_windowBackgroundWhiteBlueText4,Theme.key_text_RedBold}) {
            colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_CHECKTAG,new Class[]{TextCell.class},new String[]{"imageView"},null,null,null,key));
        }
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCell.class},new String[]{"valueTextView"},null,null,null,Theme.key_windowBackgroundWhiteValueText));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextSettingsCell.class},new String[]{"valueTextView"},null,null,null,Theme.key_windowBackgroundWhiteValueText));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCheckCell.class},new String[]{"checkBox"},null,null,null,Theme.key_switchTrack));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCheckCell.class},new String[]{"checkBox"},null,null,null,Theme.key_switchTrackChecked));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextInfoPrivacyCell.class},new String[]{"textView"},null,null,null,Theme.key_windowBackgroundWhiteGrayText4));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_LINKCOLOR,new Class[]{TextInfoPrivacyCell.class},new String[]{"textView"},null,null,null,Theme.key_windowBackgroundWhiteLinkText));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_TEXTCOLOR,new Class[]{EditTextCell.class},new String[]{"editText"},null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_HINTTEXTCOLOR,new Class[]{EditTextCell.class},new String[]{"editText"},null,null,null,Theme.key_windowBackgroundWhiteHintText));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_CURSORCOLOR,new Class[]{EditTextCell.class},new String[]{"editText"},null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        return colors;
    }
}
