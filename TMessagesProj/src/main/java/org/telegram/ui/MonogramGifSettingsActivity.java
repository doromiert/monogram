// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.xmpp.MonogramGiphy;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class MonogramGifSettingsActivity extends MonogramSettingsActivity {
    public MonogramGifSettingsActivity(int account) { currentAccount = account; }
    @Override protected CharSequence getTitle() { return "GIF search"; }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean configured = MonogramGiphy.configured(currentAccount);
        items.add(UItem.asHeader("GIPHY"));
        items.add(UItem.asButton(1, configured ? "Replace API key" : "Add API key", configured ? "Configured" : "Not configured"));
        items.add(UItem.asShadow("Search GIFs with your GIPHY application API key. Searches and GIF previews connect directly to GIPHY. Your key is stored encrypted on this phone for this account."));
        if (configured) items.add(UItem.asButton(2, "Remove API key").red());
        items.add(UItem.asShadow("Saved GIFs remain available without a key. Powered by GIPHY."));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            EditTextCell input = new EditTextCell(getContext(), "GIPHY API key", false);
            input.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.editText.setSingleLine(true);
            input.editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
            if (android.os.Build.VERSION.SDK_INT >= 26) input.editText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            showDialog(new AlertDialog.Builder(getContext()).setTitle("GIPHY API key").setView(input)
                    .setPositiveButton("Save", (dialog, which) -> {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty()) { error("Enter an API key, or use Remove API key to disable online search."); return; }
                        save(value);
                    }).setNegativeButton("Cancel", null).create());
        } else if (item.id == 2) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Remove API key?")
                    .setMessage("Online GIF search will be disabled. Saved GIFs will remain available.")
                    .setPositiveButton("Remove", (dialog, which) -> save("")).setNegativeButton("Cancel", null).create());
        }
    }
    private void save(String value) {
        try { MonogramGiphy.saveKey(currentAccount, value); listView.adapter.update(true); }
        catch (IllegalArgumentException invalid) { error("Enter a valid GIPHY API key."); }
        catch (Exception unavailable) { error("Could not save the API key. Please try again."); }
    }
    private void error(String message) {
        showDialog(new AlertDialog.Builder(getContext()).setTitle("GIF search").setMessage(message).setPositiveButton("OK", null).create());
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
