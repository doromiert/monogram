// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramTranslation;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class MonogramTranslationSettingsActivity extends MonogramSettingsActivity {
    private static final String LIBRE = "libretranslate";
    private static final String GOOGLE = "google";
    private static final String DEEPL = "deepl";

    private String originalProvider;
    private String originalEndpoint;
    private String provider;
    private String endpoint;
    private String pendingKey;

    public MonogramTranslationSettingsActivity(int account) {
        currentAccount = account;
        String savedProvider = MonogramTranslation.provider(account);
        originalProvider = knownProvider(savedProvider) ? savedProvider : "";
        provider = knownProvider(savedProvider) ? savedProvider : LIBRE;
        originalEndpoint = endpoint = knownProvider(savedProvider) ? MonogramTranslation.endpoint(account) : "";
    }

    @Override
    protected CharSequence getTitle() {
        return "Translation";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean configured = MonogramTranslation.isConfigured(currentAccount);
        items.add(UItem.asHeader("Translation provider"));
        items.add(UItem.asSettingsCell(1, "Provider", providerName(provider)));
        if (LIBRE.equals(provider)) {
            items.add(UItem.asSettingsCell(2, "Server URL", TextUtils.isEmpty(endpoint) ? "Required" : endpoint));
        } else if (DEEPL.equals(provider)) {
            items.add(UItem.asSettingsCell(2, "DeepL plan", MonogramTranslation.DEEPL_PRO_ENDPOINT.equals(endpoint) ? "Pro" : "Free"));
        }
        boolean sameConfiguration = provider.equals(originalProvider) && TextUtils.equals(endpoint, originalEndpoint);
        String keyStatus = pendingKey != null ? "Ready to save" : LIBRE.equals(provider) ? "Optional" : configured && sameConfiguration ? "Stored securely" : "Required";
        items.add(UItem.asSettingsCell(3, "API key", keyStatus));
        items.add(UItem.asButton(4, "Save configuration").accent());
        items.add(UItem.asShadow("Messages are sent as plaintext to the configured provider when you translate a message or enable chat translation. Google Cloud Translation and DeepL require an API key. LibreTranslate requires an explicit HTTPS server URL and may require a key. API keys are stored encrypted for this account and are never shown here. Changing provider or server never reuses the previous key."));
        if (configured) {
            items.add(UItem.asButton(5, "Disable translation").red());
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Translation provider")
                    .setItems(new CharSequence[]{"LibreTranslate", "Google Cloud Translation", "DeepL"}, (dialog, which) -> {
                        String selected = which == 0 ? LIBRE : which == 1 ? GOOGLE : DEEPL;
                        if (!selected.equals(provider)) {
                            provider = selected;
                            endpoint = DEEPL.equals(provider) ? MonogramTranslation.DEEPL_FREE_ENDPOINT : "";
                            pendingKey = null;
                        }
                        listView.adapter.update(true);
                    }).setNegativeButton("Cancel", null).create());
        } else if (item.id == 2 && LIBRE.equals(provider)) {
            editValue("LibreTranslate server", "https://translate.example.org", endpoint, false, value -> {
                endpoint = value;
                listView.adapter.update(true);
            });
        } else if (item.id == 2 && DEEPL.equals(provider)) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("DeepL plan")
                    .setItems(new CharSequence[]{"Free", "Pro"}, (dialog, which) -> {
                        endpoint = which == 0 ? MonogramTranslation.DEEPL_FREE_ENDPOINT : MonogramTranslation.DEEPL_PRO_ENDPOINT;
                        listView.adapter.update(true);
                    }).setNegativeButton("Cancel", null).create());
        } else if (item.id == 3) {
            editValue("API key", "Enter API key", "", true, value -> {
                if (value.isEmpty()) {
                    error("Enter an API key. Cancel to keep the currently stored key.");
                    return;
                }
                pendingKey = value;
                listView.adapter.update(true);
            });
        } else if (item.id == 4) {
            save();
        } else if (item.id == 5) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Disable translation?")
                    .setMessage("The provider, server URL and encrypted API key will be removed from this account.")
                    .setPositiveButton("Disable", (dialog, which) -> clear()).setNegativeButton("Cancel", null).create());
        }
    }

    private void save() {
        boolean providerChanged = !provider.equals(originalProvider);
        if (LIBRE.equals(provider) && TextUtils.isEmpty(endpoint)) {
            error("Enter the HTTPS URL of your LibreTranslate server.");
            return;
        }
        boolean endpointChanged = !TextUtils.equals(endpoint, originalEndpoint);
        if ((GOOGLE.equals(provider) || DEEPL.equals(provider)) && pendingKey == null && (providerChanged || endpointChanged || !MonogramTranslation.isConfigured(currentAccount))) {
            error("Enter the API key for " + providerName(provider) + ".");
            return;
        }
        try {
            MonogramTranslation.saveConfiguration(currentAccount, provider, endpoint, providerChanged && pendingKey == null ? "" : pendingKey);
            originalProvider = provider;
            originalEndpoint = endpoint = MonogramTranslation.endpoint(currentAccount);
            pendingKey = null;
            listView.adapter.update(true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, "Translation settings saved").show();
        } catch (IllegalArgumentException invalid) {
            error(invalid.getMessage() == null ? "Check the provider settings and try again." : invalid.getMessage());
        } catch (Exception unavailable) {
            error("Could not save the translation settings. Please try again.");
        }
    }

    private void clear() {
        try {
            MonogramTranslation.clearConfiguration(currentAccount);
            originalProvider = provider = MonogramTranslation.provider(currentAccount);
            originalEndpoint = endpoint = MonogramTranslation.endpoint(currentAccount);
            pendingKey = null;
            listView.adapter.update(true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, "Translation disabled").show();
        } catch (Exception unavailable) {
            error("Could not remove the translation settings. Please try again.");
        }
    }

    private void editValue(String title, String hint, String value, boolean password, ValueCallback callback) {
        EditTextCell input = new EditTextCell(getContext(), hint, false);
        input.editText.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        input.editText.setSingleLine(true);
        input.editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(password ? 1024 : 2048)});
        if (!password && !TextUtils.isEmpty(value)) {
            input.editText.setText(value);
            input.editText.setSelection(value.length());
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 && password) {
            input.editText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        showDialog(new AlertDialog.Builder(getContext()).setTitle(title).setView(input)
                .setPositiveButton("Save", (dialog, which) -> callback.run(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null).create());
    }

    private static String providerName(String value) {
        if (GOOGLE.equals(value)) return "Google Cloud Translation";
        if (DEEPL.equals(value)) return "DeepL";
        return "LibreTranslate";
    }

    private static boolean knownProvider(String value) {
        return LIBRE.equals(value) || GOOGLE.equals(value) || DEEPL.equals(value);
    }

    private void error(String message) {
        showDialog(new AlertDialog.Builder(getContext()).setTitle("Translation").setMessage(message).setPositiveButton("OK", null).create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private interface ValueCallback {
        void run(String value);
    }
}
