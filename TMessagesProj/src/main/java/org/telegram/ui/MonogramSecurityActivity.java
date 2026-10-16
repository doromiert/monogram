// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;

public final class MonogramSecurityActivity extends UniversalFragment {
    private final long peer;
    private final Map<OmemoDevice, OmemoFingerprint> identities = new LinkedHashMap<>();
    private final Map<OmemoDevice, String> states = new LinkedHashMap<>();
    private com.negzero.monogram.xmpp.XmppSession loadedSession;
    private String ownFingerprint;
    private String status = "Loading fingerprints…";
    private boolean saving;
    public MonogramSecurityActivity(int account, long peer) { currentAccount = account; this.peer = peer; }
    private void refresh() { if (listView != null) listView.adapter.update(true); }
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        MonogramController controller = MonogramController.get(currentAccount);
        controller.worker.execute(() -> {
            try {
                com.negzero.monogram.xmpp.XmppSession session = controller.session();
                String own = session.ownFingerprint().toString();
                Map<OmemoDevice, OmemoFingerprint> found = new LinkedHashMap<>();
                Map<OmemoDevice, String> trust = new LinkedHashMap<>();
                boolean contactHasDevices = false;
                for (String jid : new String[]{controller.jid(peer), session.ownJid()}) {
                    for (OmemoDevice device : session.devices(jid)) {
                        if (device.getJid().toString().equals(session.ownJid()) && device.getDeviceId() == session.ownDeviceId()) continue;
                        if (jid.equals(controller.jid(peer))) contactHasDevices = true;
                        OmemoFingerprint fingerprint = session.fingerprint(device); found.put(device, fingerprint);
                        switch (session.trustState(device, fingerprint)) {
                            case trusted: trust.put(device, "Trusted"); break;
                            case untrusted: trust.put(device, "Distrusted"); break;
                            default: trust.put(device, "Not reviewed");
                        }
                    }
                }
                boolean hasContact = contactHasDevices;
                AndroidUtilities.runOnUIThread(() -> {
                    loadedSession = session; ownFingerprint = own; identities.putAll(found); states.putAll(trust);
                    status = hasContact ? null : "This contact has not published a compatible OMEMO device."; refresh();
                });
            } catch (Exception failed) {
                AndroidUtilities.runOnUIThread(() -> { status = "Could not load fingerprints. Check your connection and the contact's OMEMO support."; refresh(); });
            }
        });
        return true;
    }
    @Override protected CharSequence getTitle() { return "Conversation security"; }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        MonogramController controller = MonogramController.get(currentAccount);
        items.add(UItem.asHeader(controller.jid(peer)));
        items.add(UItem.asCheck(1, "Use OMEMO encryption").setChecked(controller.encrypted(peer)));
        items.add(UItem.asShadow("Compare fingerprints through another trusted channel before trusting devices. New devices and changed keys require a new decision."));
        items.add(UItem.asButton(4, R.drawable.msg_retry, "XMPP connection"));
        items.add(UItem.asShadow(null));
        if (status != null) items.add(UItem.asShadow(status));
        if (ownFingerprint != null) {
            items.add(UItem.asButton(3, "Your fingerprint")); items.add(UItem.asShadow(ownFingerprint));
        }
        if (!identities.isEmpty()) {
            items.add(UItem.asButton(2, saving ? "Saving trust decisions…" : "Trust all fingerprints").accent().setEnabled(!saving));
            items.add(UItem.asShadow(null));
            int id = 100;
            for (Map.Entry<OmemoDevice, OmemoFingerprint> entry : identities.entrySet()) {
                OmemoDevice device = entry.getKey();
                items.add(UItem.asButton(id++, device.getJid() + " · device " + device.getDeviceId(), states.get(device)).setEnabled(!saving));
                items.add(UItem.asShadow(entry.getValue().toString()));
            }
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        MonogramController controller = MonogramController.get(currentAccount);
        if (item.id == 1) {
            if (controller.encrypted(peer)) showDialog(new AlertDialog.Builder(getContext()).setTitle("Send without OMEMO?")
                    .setMessage("New messages in this conversation will be readable by the XMPP servers. Transport encryption remains enabled.")
                    .setPositiveButton("Use plain messages", (dialog, which) -> { controller.setEncrypted(peer, false); refresh(); })
                    .setNegativeButton("Cancel", null).create());
            else { controller.setEncrypted(peer, true); refresh(); }
        } else if (item.id == 4) presentFragment(new MonogramConnectionActivity(currentAccount));
        else if (item.id == 3) showDialog(new AlertDialog.Builder(getContext()).setTitle("Your fingerprint").setMessage(ownFingerprint)
                .setPositiveButton("Copy", (d, w) -> AndroidUtilities.addToClipboard(ownFingerprint)).setNegativeButton("Close", null).create());
        else if (item.id == 2 && !saving) showDialog(new AlertDialog.Builder(getContext()).setTitle("Trust all fingerprints?")
                .setMessage("Trust the " + identities.size() + " fingerprints currently shown, including any marked Distrusted. New devices and changed keys will still need review.")
                .setPositiveButton("Trust all", (dialog, which) -> decide(new LinkedHashMap<>(identities), true))
                .setNegativeButton("Cancel", null).create());
        else if (item.id >= 100 && !saving && item.id - 100 < identities.size()) {
            OmemoDevice device = new ArrayList<>(identities.keySet()).get(item.id - 100);
            OmemoFingerprint fingerprint = identities.get(device);
            Map<OmemoDevice, OmemoFingerprint> selected = new LinkedHashMap<>(); selected.put(device, fingerprint);
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Device " + device.getDeviceId()).setMessage(device.getJid() + "\n\n" + fingerprint)
                    .setPositiveButton("Trust", (d, w) -> decide(selected, true)).setNegativeButton("Distrust", (d, w) -> decide(selected, false))
                    .setNeutralButton("Copy", (d, w) -> AndroidUtilities.addToClipboard(fingerprint.toString())).create());
        }
    }
    private void decide(Map<OmemoDevice, OmemoFingerprint> selected, boolean trusted) {
        saving = true; refresh();
        MonogramController controller = MonogramController.get(currentAccount);
        controller.worker.execute(() -> {
            try {
                if (controller.session() != loadedSession) throw new IllegalStateException("Account session changed");
                loadedSession.decideAll(selected, trusted);
                AndroidUtilities.runOnUIThread(() -> { for (OmemoDevice device : selected.keySet()) states.put(device, trusted ? "Trusted" : "Distrusted"); saving = false; refresh(); });
            } catch (Exception failed) {
                AndroidUtilities.runOnUIThread(() -> { saving = false; refresh(); if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getContext())
                        .setTitle("Could not save trust decisions").setMessage("Nothing was changed. Check your connection and try again.").setPositiveButton("OK", null).create()); });
            }
        });
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
