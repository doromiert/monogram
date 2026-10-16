// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;

public final class MonogramSecurityActivity extends BaseFragment {
    private final long peer;
    public MonogramSecurityActivity(int account, long peer) { currentAccount = account; this.peer = peer; }
    private TextView text(Context context, String value) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(16);
        view.setTextIsSelectable(true); view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        int padding = AndroidUtilities.dp(20); view.setPadding(padding, padding, padding, padding);
        return view;
    }
    @Override public View createView(Context context) {
        actionBar.setTitle("Conversation security"); actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        MonogramController controller = MonogramController.get(currentAccount);
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ScrollView scroll = new ScrollView(context); scroll.addView(body);
        body.addView(text(context, controller.jid(peer)));
        TextCheckCell enabled = new TextCheckCell(context);
        enabled.setTextAndCheck("Use OMEMO encryption", controller.encrypted(peer), false);
        body.addView(enabled);
        enabled.setOnClickListener(v -> {
            if (controller.encrypted(peer)) {
                showDialog(new AlertDialog.Builder(context).setTitle("Send without OMEMO?")
                        .setMessage("New messages in this conversation will be readable by the XMPP servers. Transport encryption remains enabled.")
                        .setPositiveButton("Use plain messages", (dialog, which) -> { controller.setEncrypted(peer, false); enabled.setChecked(false); })
                        .setNegativeButton("Cancel", null).create());
            } else { controller.setEncrypted(peer, true); enabled.setChecked(true); }
        });
        body.addView(text(context, "Compare fingerprints with your contact through another trusted channel before trusting their devices. New devices and changed keys require a new decision."));
        TextView status = text(context, "Loading fingerprints…"); body.addView(status);
        TextSettingsCell trustAll = new TextSettingsCell(context);
        trustAll.setText("Trust all fingerprints", false);
        trustAll.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        trustAll.setBackground(Theme.getSelectorDrawable(false));
        trustAll.setVisibility(View.GONE);
        body.addView(trustAll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        controller.worker.execute(() -> {
            try {
                com.negzero.monogram.xmpp.XmppSession session = controller.session();
                String own = session.ownFingerprint().toString();
                java.util.Map<OmemoDevice, OmemoFingerprint> identities = new java.util.LinkedHashMap<>();
                java.util.Map<OmemoDevice, String> states = new java.util.HashMap<>();
                boolean contactHasDevices = false;
                for (String jid : new String[]{controller.jid(peer), session.ownJid()}) {
                    for (OmemoDevice device : session.devices(jid)) {
                        if (device.getJid().toString().equals(session.ownJid()) && device.getDeviceId() == session.ownDeviceId()) continue;
                        if (jid.equals(controller.jid(peer))) contactHasDevices = true;
                        OmemoFingerprint fingerprint = session.fingerprint(device);
                        identities.put(device, fingerprint);
                        switch (session.trustState(device, fingerprint)) {
                            case trusted: states.put(device, "Trusted"); break;
                            case untrusted: states.put(device, "Distrusted"); break;
                            default: states.put(device, "Not reviewed");
                        }
                    }
                }
                final boolean hasContact = contactHasDevices;
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentActivity() == null) return;
                    status.setText("Your fingerprint\n" + own + (!hasContact ? "\n\nThis contact has not published a compatible OMEMO device." : ""));
                    java.util.Map<OmemoDevice, TextView> rows = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<OmemoDevice, OmemoFingerprint> entry : identities.entrySet()) {
                        OmemoDevice device = entry.getKey(); OmemoFingerprint fingerprint = entry.getValue();
                        TextView row = text(context, device.getJid() + " · device " + device.getDeviceId() + "\n" + fingerprint + "\n" + states.get(device) + " · tap to review");
                        row.setTextIsSelectable(false); row.setMinHeight(AndroidUtilities.dp(88));
                        body.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                        rows.put(device, row);
                        row.setOnClickListener(v -> showDialog(new AlertDialog.Builder(context).setTitle("Device " + device.getDeviceId()).setMessage(device.getJid() + "\n\n" + fingerprint)
                                .setPositiveButton("Trust", (d, w) -> decide(controller, session, device, fingerprint, true, row))
                                .setNegativeButton("Distrust", (d, w) -> decide(controller, session, device, fingerprint, false, row)).create()));
                    }
                    if (!identities.isEmpty()) {
                        trustAll.setVisibility(View.VISIBLE);
                        trustAll.setOnClickListener(v -> showDialog(new AlertDialog.Builder(context)
                                .setTitle("Trust all fingerprints?")
                                .setMessage("Trust the " + identities.size() + " fingerprints currently shown, including any marked Distrusted. New devices and changed keys will still need review.")
                                .setPositiveButton("Trust all", (dialog, which) -> trustAll(controller, session, identities, rows, trustAll))
                                .setNegativeButton("Cancel", null).create()));
                    }
                });
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> status.setText("Could not load fingerprints. Check your connection and the contact's OMEMO support."));
            }
        });
        fragmentView = scroll; return fragmentView;
    }
    private void trustAll(MonogramController controller, com.negzero.monogram.xmpp.XmppSession expectedSession,
            java.util.Map<OmemoDevice, OmemoFingerprint> identities, java.util.Map<OmemoDevice, TextView> rows, TextSettingsCell button) {
        button.setEnabled(false); button.setText("Trusting fingerprints…", false);
        controller.worker.execute(() -> {
            try {
                if (controller.session() != expectedSession) throw new IllegalStateException("Account session changed");
                expectedSession.decideAll(identities, true);
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentActivity() == null) return;
                    for (java.util.Map.Entry<OmemoDevice, OmemoFingerprint> identity : identities.entrySet()) {
                        OmemoDevice device = identity.getKey();
                        rows.get(device).setText(device.getJid() + " · device " + device.getDeviceId() + "\n" + identity.getValue() + "\nTrusted · tap to review");
                    }
                    button.setText("Trust all fingerprints", false); button.setEnabled(true);
                    button.announceForAccessibility("All displayed fingerprints trusted");
                });
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentActivity() == null) return;
                    button.setText("Trust all fingerprints", false); button.setEnabled(true);
                    showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("Could not save trust decisions")
                            .setMessage("Nothing was changed. Check your connection and try again.")
                            .setPositiveButton("OK", null).create());
                });
            }
        });
    }
    private void decide(MonogramController controller, com.negzero.monogram.xmpp.XmppSession expectedSession,
            OmemoDevice device, OmemoFingerprint fingerprint, boolean trust, TextView row) {
        controller.worker.execute(() -> {
            try {
                if (controller.session() != expectedSession) throw new IllegalStateException("Account session changed");
                expectedSession.decide(device, fingerprint, trust);
                AndroidUtilities.runOnUIThread(() -> row.setText(device.getJid() + " · device " + device.getDeviceId() + "\n" + fingerprint + "\n" + (trust ? "Trusted" : "Distrusted")));
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> row.setText("Could not save trust decision. Tap to try again."));
            }
        });
    }
}
