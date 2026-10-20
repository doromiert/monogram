// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.xmpp.MonogramPacks;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

/** Native Telegram settings, pack preview and pickers backed by account-local XMPP assets. */
public final class MonogramPacksActivity extends MonogramSettingsActivity {
    private int initialAction;
    private String initialLink, lastError;
    private ArrayList<MonogramPacks.Pack> packs = new ArrayList<>();
    private boolean busy, creatingEmojis;
    private String creatingName;
    private MonogramPacks.Request request;
    private MonogramPacks.ImportProgress importState;
    private boolean watchingImport;
    private org.telegram.ui.Components.LineProgressView importLine;
    private android.widget.FrameLayout importMeter;
    private final MonogramPacks.ImportObserver importObserver=this::importChanged;
    public MonogramPacksActivity(int account) { currentAccount=account; }
    public MonogramPacksActivity(int account, boolean importPack) { this(account); initialAction = importPack ? 1 : 2; }
    public MonogramPacksActivity(int account, String link) { this(account); initialAction = 1; initialLink = link; }
    @Override public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if(isOpen && !backward && initialAction!=0) {
            int action=initialAction;initialAction=0;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if(getParentActivity()!=null) onClick(UItem.asButton(action,""),null,0,0,0);
            },100);
        }
    }
    @Override protected CharSequence getTitle() { return "Stickers and custom emoji"; }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        packs = MonogramPacks.packs(currentAccount);
        {
            if(importState!=null) {
                items.add(UItem.asHeader("Telegram import"));
                String count=importState.total>0 ? importState.completed+" / "+importState.total : importState.active ? "Preparing…" : "";
                items.add(UItem.asSettingsCell(0,importState.complete ? "Pack imported" : importState.cancelled ? "Import cancelled" : importState.active ? "Stickers downloaded" : "Import failed",count).setEnabled(false));
                if(importState.active) {
                    if(importMeter==null) {
                        importMeter=new android.widget.FrameLayout(getContext());
                        importLine=new org.telegram.ui.Components.LineProgressView(getContext());
                        importMeter.addView(importLine,org.telegram.ui.Components.LayoutHelper.createFrame(-1,3,android.view.Gravity.CENTER,21,0,21,0));
                        importMeter.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1,org.telegram.messenger.AndroidUtilities.dp(24)));
                    }
                    importLine.setProgressColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueText4,getResourceProvider()));
                    importLine.setBackColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_listSelector,getResourceProvider()));
                    float fraction=importState.totalBytes>0 ? Math.min(1f,(float)importState.currentBytes/importState.totalBytes) : 0;
                    importLine.setProgress(importState.total>0 ? Math.min(1f,(importState.completed+fraction)/importState.total) : 0,true);
                    items.add(UItem.asCustom(importMeter));
                    items.add(UItem.asButton(20,"Cancel import").red());
                }
                if(importState.error!=null) items.add(UItem.asButton(21,"View import error"));
                String detail=importState.stage;
                if(!importState.packName.isEmpty()) detail+=" · "+importState.packName;
                if(importState.active && importState.currentBytes>0) detail+="\n"+org.telegram.messenger.AndroidUtilities.formatFileSize(importState.currentBytes)
                        +(importState.totalBytes>0 ? " / "+org.telegram.messenger.AndroidUtilities.formatFileSize(importState.totalBytes) : " downloaded");
                if(importState.active) detail+="\nYou can leave this screen while the import continues.";
                if(importState.error!=null) detail+="\n"+importState.error;
                items.add(UItem.asShadow(detail));
            }
            items.add(UItem.asHeader("Private packs"));
            if (MonogramPacks.storageError(currentAccount)) items.add(UItem.asShadow("Saved pack data could not be read and has been preserved. Restore a valid backup before adding or changing packs."));
            items.add(UItem.asButton(1, "Import Telegram pack links"));
            items.add(UItem.asButton(2, "Create sticker pack"));
            items.add(UItem.asButton(3, "Create custom emoji pack"));
            items.add(UItem.asShadow("Packs stay on this phone for this Jabber account. Sending a sticker uses the chat’s existing file encryption. No pack manifest is published to your server. Small custom emoji are included in plain and OMEMO 2 direct messages. Legacy OMEMO, encrypted groups and oversized emoji use an ordinary emoji fallback in other clients. Imported packs remain available offline. Open a pack’s menu to update or detach it; automatic updates are off by default. Inline emoji in media captions are not supported yet."));
            if (busy) items.add(UItem.asShadow("Preparing local pack…"));
            if (lastError != null) items.add(UItem.asShadow(lastError));
            items.add(UItem.asHeader("Your packs"));
            int index = 0;
            for (MonogramPacks.Pack entry : packs) if (entry.installed) items.add(row(100 + index++, entry.name, entry.items.size() + (entry.emojis ? " emoji" : " stickers") + (MonogramPacks.telegramSource(entry) ? entry.autoSync ? " · Telegram · auto-update" : " · Telegram · manual updates" : " · editable"), entry));
            if (index == 0) items.add(UItem.asSettingsCell(0,"Installed packs","None").setEnabled(false));
            if (index == 0) items.add(UItem.asShadow("Import a Telegram pack or create one from images, TGS animations or WebM files. Every item can have its own name and separate emoji tag."));
            items.add(UItem.asHeader("Telegram import"));
            items.add(UItem.asButton(4, MonogramPacks.configured(currentAccount) ? "Replace bot token" : "Add bot token", MonogramPacks.configured(currentAccount) ? "Configured" : "Not configured"));
            if (MonogramPacks.configured(currentAccount)) items.add(UItem.asButton(5, "Remove bot token").red());
            items.add(UItem.asShadow("Import uses Telegram’s official Bot API and contacts api.telegram.org directly. Create a bot with @BotFather and paste its token here. The token is stored encrypted on this phone. It is never sent to your Jabber server. Imported artwork remains subject to its creator’s rights."));
        }
    }

    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if(item.id==21 && importState!=null && importState.error!=null) {
            String detail=importState.error;
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Import error").setMessage(detail)
                    .setPositiveButton("OK",null).setNeutralButton("Copy",(dialog,which) -> org.telegram.messenger.AndroidUtilities.addToClipboard(detail)).create());
            return;
        }
        if(item.id==20) { MonogramPacks.cancelImport(currentAccount);return; }
        if (busy || importState!=null && importState.active) return;
        if (item.object instanceof MonogramPacks.Pack) {
            TLRPC.TL_inputStickerSetID input=new TLRPC.TL_inputStickerSetID();input.id=((MonogramPacks.Pack)item.object).id;
            showDialog(new StickersAlert(getContext(),this,input,MonogramPacks.find(currentAccount,input),null,false));return;
        }
        {
            if (item.id == 1) {
                String link=initialLink;initialLink=null;
                input("Telegram pack links", link==null ? "" : link, false, this::importPack);
            }
            else if (item.id == 2 || item.id == 3) {
                creatingEmojis = item.id == 3;
                input("Pack name", "", false, value -> {
                    try { creatingName = value.trim(); if (creatingName.isEmpty()) throw new IllegalArgumentException("Enter a pack name.");
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/webm", "application/x-tgsticker", "application/gzip", "application/octet-stream"});
                        getParentActivity().startActivityForResult(intent, 7201);
                    } catch (Exception failed) { error("Could not open the file picker."); }
                });
            } else if (item.id == 4) input("Telegram bot token", "", true, value -> {
                try { if (value.trim().isEmpty()) throw new IllegalArgumentException("Enter a bot token."); MonogramPacks.saveToken(currentAccount, value); update(); }
                catch (Exception failed) { error("Could not save the token. Use the token supplied by @BotFather."); }
            });
            else if (item.id == 5) showDialog(new AlertDialog.Builder(getContext()).setTitle("Remove bot token?").setMessage("Imported packs remain available offline. Telegram updates pause until you add a token again.")
                    .setPositiveButton("Remove", (dialog, which) -> { try { MonogramPacks.saveToken(currentAccount, ""); update(); } catch (Exception failed) { error("Could not remove the token."); } }).setNegativeButton("Cancel", null).create());
        }
    }

    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != 7201 || resultCode != Activity.RESULT_OK || data == null || creatingName == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        busy = true; update(); request = MonogramPacks.create(currentAccount, creatingName, creatingEmojis, uris, error -> {
            finished(error);
            if(error==null && getParentActivity()!=null && !packs.isEmpty()) {
                TLRPC.TL_inputStickerSetID input=new TLRPC.TL_inputStickerSetID();input.id=packs.get(0).id;
                showDialog(new StickersAlert(getContext(),this,input,MonogramPacks.find(currentAccount,input),null,false));
            }
        }); creatingName = null;
    }
    private void importPack(String link) {
        if(!MonogramPacks.configured(currentAccount)) {
            input("Telegram bot token", "", true, token -> {
                try { if(token.trim().isEmpty()) throw new IllegalArgumentException(); MonogramPacks.saveToken(currentAccount,token); importPack(link); }
                catch(Exception failure) { finished("Could not save the token. Use the token supplied by @BotFather."); }
            });
            return;
        }
        lastError=null;
        try { MonogramPacks.startImport(currentAccount,link); importState=MonogramPacks.importProgress(currentAccount);update(); }
        catch(Exception invalid) { finished(invalid.getMessage()); }
    }
    private void input(String title, String value, boolean secret, java.util.function.Consumer<String> save) {
        EditTextCell input = new EditTextCell(getContext(), title, false); input.editText.setSingleLine(!title.equals("Telegram pack links"));
        input.editText.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(secret ? 256 : title.equals("Telegram pack links") ? 65536 : 512)}); input.editText.setText(value);
        if (android.os.Build.VERSION.SDK_INT >= 26 && secret) input.editText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        showDialog(new AlertDialog.Builder(getContext()).setTitle(title).setMessage(secret ? "Create a bot with @BotFather and paste its token. Telegram imports need this token; it stays encrypted on this phone." : null).setView(input).setPositiveButton(secret ? "Save" : "Continue", (dialog, which) -> {
                    String text=input.getText().toString();
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                        if(getParentActivity()!=null) save.accept(text);
                    },1);
                })
                .setNegativeButton("Cancel", null).create());
    }
    private UItem row(int id, String title, String subtitle, Object object) { UItem item = UItem.asButton(id, title, subtitle); item.object = object; return item; }
    private void update() { if (listView != null) { listView.adapter.update(true); } }
    private void finished(String error) {
        busy = false; lastError=error; update(); if (error != null) error(error);
    }
    private void error(String text) { if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getContext()).setTitle("Stickers and custom emoji").setMessage(text == null ? "Could not update the pack." : text).setPositiveButton("OK", null).create()); }
    private void importChanged(MonogramPacks.ImportProgress progress) {
        if(!watchingImport) return;
        importState=progress;update();
    }
    @Override public void onFragmentDestroy() {
        watchingImport=false;MonogramPacks.removeImportObserver(currentAccount,importObserver);
        if (request != null) request.cancel();
        super.onFragmentDestroy();
    }
    @Override protected void onDialogDismiss(android.app.Dialog dialog) { super.onDialogDismiss(dialog);update(); }
    @Override public void onPause() {
        watchingImport=false;MonogramPacks.removeImportObserver(currentAccount,importObserver);
        super.onPause();
    }
    @Override public void onResume() {
        super.onResume();watchingImport=true;
        MonogramPacks.addImportObserver(currentAccount,importObserver);
        importState=MonogramPacks.importProgress(currentAccount);update();
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
