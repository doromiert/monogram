// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.*;
import com.negzero.monogram.xmpp.XmppProfile;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.*;

/** Native editor for public vCard fields; unknown/repeated fields are retained on the server. */
public final class MonogramProfileFieldsActivity extends MonogramSettingsActivity {
    private boolean busy;
    private final ArrayList<String> fields=new ArrayList<>(XmppProfile.EDITABLE_FIELDS.keySet());
    public MonogramProfileFieldsActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return "Profile fields"; }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        Map<String,String> values=MonogramController.get(currentAccount).ownProfileFields();
        items.add(UItem.asHeader("Public XMPP profile"));
        for(int i=0;i<fields.size();i++) items.add(UItem.asButton(i+1,XmppProfile.EDITABLE_FIELDS.get(fields.get(i)),values.getOrDefault(fields.get(i),"").isEmpty() ? "Add" : values.get(fields.get(i))).setEnabled(!busy));
        items.add(UItem.asShadow("Saved in your XMPP vCard. Leave a field empty to remove its value. Other fields and additional email, phone and address entries are preserved."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(busy || item.id<1 || item.id>fields.size()) return;
        String field=fields.get(item.id-1),value=MonogramController.get(currentAccount).ownProfileFields().get(field);
        EditTextCell input=new EditTextCell(getContext(),XmppProfile.EDITABLE_FIELDS.get(field),false);
        input.editText.setText(value);input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)});
        input.editText.setSingleLine(!"DESC".equals(field));
        showDialog(new AlertDialog.Builder(getContext()).setTitle(XmppProfile.EDITABLE_FIELDS.get(field)).setView(input)
            .setPositiveButton("Save",(dialog,which) -> {
                busy=true;listView.adapter.update(true);
                MonogramController.get(currentAccount).editOwnProfileField(field,input.getText().toString().trim(),error -> {
                    busy=false;if(listView==null || getParentActivity()==null) return;listView.adapter.update(true);
                    if(error!=null) showDialog(new AlertDialog.Builder(getContext()).setTitle("Profile field").setMessage(error).setPositiveButton("OK",null).create());
                });
            }).setNegativeButton("Cancel",null).create());
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
