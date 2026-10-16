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
import com.negzero.monogram.xmpp.XmppMessage;
import com.negzero.monogram.xmpp.Omemo2Bundle;
import org.telegram.messenger.Utilities;
import org.jxmpp.jid.impl.JidCreate;

public final class MonogramSecurityActivity extends UniversalFragment {
    private final long peer;
    private final Map<OmemoDevice, OmemoFingerprint> identities = new LinkedHashMap<>();
    private final Map<OmemoDevice, String> states = new LinkedHashMap<>();
    private com.negzero.monogram.xmpp.XmppSession loadedSession;
    private String ownFingerprint;
    private String status = "Loading fingerprints…";
    private boolean saving;
    private boolean modern;
    private int generation;
    public MonogramSecurityActivity(int account, long peer) { currentAccount = account; this.peer = peer; }
    private void refresh() { if (listView != null) listView.adapter.update(true); }
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadIdentities();
        return true;
    }
    private void loadIdentities() {
        MonogramController controller = MonogramController.get(currentAccount);
        final int loading = ++generation;
        modern = controller.modernEncryption(peer);
        final boolean selectedModern = modern;
        identities.clear(); states.clear(); loadedSession=null; ownFingerprint=null; status="Loading fingerprints…"; refresh();
        controller.worker.execute(() -> {
            try {
                com.negzero.monogram.xmpp.XmppSession session = controller.session();
                if (selectedModern && !session.modernAvailable()) throw new IllegalStateException("OMEMO 2 is not initialized");
                String own = selectedModern ? Utilities.bytesToHex(session.modern().device.prepare().identity()).toLowerCase(java.util.Locale.ROOT) : session.ownFingerprint().toString();
                Map<OmemoDevice, OmemoFingerprint> found = new LinkedHashMap<>();
                Map<OmemoDevice, String> trust = new LinkedHashMap<>();
                boolean contactHasDevices = false;
                java.util.Set<String> addresses=new java.util.LinkedHashSet<>();
                if (peer<0) for(org.jxmpp.jid.BareJid member:session.rooms.members(controller.jid(peer))) addresses.add(member.toString());
                else addresses.add(controller.jid(peer));
                addresses.add(session.ownJid());
                for (String jid : addresses) {
                    if (selectedModern) {
                        for (Map.Entry<Integer,Omemo2Bundle> entry : session.modern().discover(jid).entrySet()) {
                            OmemoDevice device = new OmemoDevice(JidCreate.entityBareFrom(jid),entry.getKey());
                            byte[] key = entry.getValue().identity(); found.put(device,new OmemoFingerprint(Utilities.bytesToHex(key).toLowerCase(java.util.Locale.ROOT)));
                            switch (session.modern().device.trust(jid,entry.getKey(),key)) {
                                case TRUSTED: trust.put(device,"Trusted"); break;
                                case REJECTED: trust.put(device,"Distrusted"); break;
                                default: trust.put(device,"Not reviewed");
                            }
                            if (jid.equals(controller.jid(peer))) contactHasDevices=true;
                        }
                        continue;
                    }
                    for (OmemoDevice device : session.devices(jid)) {
                        if (device.getJid().toString().equals(session.ownJid()) && device.getDeviceId() == session.ownDeviceId()) continue;
                        if (peer<0 || jid.equals(controller.jid(peer))) contactHasDevices = true;
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
                    if (loading!=generation) return;
                    loadedSession = session; ownFingerprint = own; identities.putAll(found); states.putAll(trust);
                    status = hasContact ? null : "This contact has not published a compatible OMEMO device."; refresh();
                });
            } catch (Exception failed) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (loading!=generation) return;
                    status = peer<0 ? "Could not load room member fingerprints. Join a members-only, non-anonymous room and check your connection. Encrypted messages remain unsent until every member has a compatible trusted device." : selectedModern ? "Could not load OMEMO 2 fingerprints. Check your connection and server support, then refresh. Messages will stay unsent until encryption is ready."
                            : "Could not load legacy OMEMO fingerprints. Check your connection and the contact's support, then refresh."; refresh();
                });
            }
        });
    }
    @Override protected CharSequence getTitle() { return "Conversation security"; }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        MonogramController controller = MonogramController.get(currentAccount);
        items.add(UItem.asHeader(controller.jid(peer)));
        items.add(UItem.asCheck(1, "Use OMEMO encryption").setChecked(controller.encrypted(peer)));
        if(peer>0) items.add(UItem.asButton(5,"OMEMO version",modern ? "OMEMO 2" : "Legacy OMEMO").setEnabled(!saving));
        else items.add(UItem.asShadow("Room encryption uses legacy OMEMO and requires a members-only room that reveals participant addresses. Every member, including offline members, needs a compatible trusted device. OMEMO 2 rooms are not available yet."));
        items.add(UItem.asShadow("Compare fingerprints through another trusted channel before trusting devices. New devices and changed keys require a new decision."));
        items.add(UItem.asCheck(7,"Allow unencrypted reactions (legacy OMEMO)").setChecked(controller.plainReactionsAllowed(peer)));
        items.add(UItem.asShadow("Standard reactions are visible to the server with legacy OMEMO. OMEMO 2 direct chats encrypt reactions; this permission never changes message encryption."));
        items.add(UItem.asButton(4, R.drawable.msg_retry, "XMPP connection"));
        items.add(UItem.asButton(6,R.drawable.msg_retry,"Refresh fingerprints").setEnabled(!saving));
        items.add(UItem.asShadow(null));
        if (status != null) items.add(UItem.asShadow(status));
        if (ownFingerprint != null) {
            items.add(UItem.asButton(3,modern ? "Your OMEMO 2 fingerprint" : "Your legacy OMEMO fingerprint")); items.add(UItem.asShadow(ownFingerprint));
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
        if(item.id==7) {
            if(controller.plainReactionsAllowed(peer)) { controller.setPlainReactions(peer,false); refresh(); }
            else showDialog(new AlertDialog.Builder(getContext()).setTitle("Allow unencrypted reactions?")
                .setMessage("Legacy OMEMO protects message text but cannot encrypt standard reaction metadata. The XMPP server can see your reactions.")
                .setPositiveButton("Allow reactions",(dialog,which) -> { controller.allowPlainReactions(peer); refresh(); }).setNegativeButton("Cancel",null).create());
        } else if (item.id == 1) {
            if (controller.encrypted(peer)) showDialog(new AlertDialog.Builder(getContext()).setTitle("Send without OMEMO?")
                    .setMessage("New messages in this conversation will be readable by the XMPP servers. Transport encryption remains enabled.")
                    .setPositiveButton("Use plain messages", (dialog, which) -> { controller.setEncrypted(peer, false); refresh(); })
                    .setNegativeButton("Cancel", null).create());
            else { controller.setEncrypted(peer, true); refresh(); }
        } else if (item.id==5 && !saving) showDialog(new AlertDialog.Builder(getContext()).setTitle("OMEMO version")
                .setItems(new CharSequence[]{"OMEMO 2 · encrypted captions","Legacy OMEMO · older clients"},(dialog,which) -> {
                    controller.setEncryption(peer,which==0 ? XmppMessage.Encryption.OMEMO2 : XmppMessage.Encryption.LEGACY_OMEMO); loadIdentities();
                }).create());
        else if (item.id==6 && !saving) loadIdentities();
        else if (item.id == 4) presentFragment(new MonogramConnectionActivity(currentAccount));
        else if (item.id == 3) showDialog(new AlertDialog.Builder(getContext()).setTitle("Your fingerprint").setMessage(ownFingerprint)
                .setPositiveButton("Copy", (d, w) -> AndroidUtilities.addToClipboard(ownFingerprint)).setNegativeButton("Close", null).create());
        else if (item.id == 2 && !saving) showDialog(new AlertDialog.Builder(getContext()).setTitle("Trust all fingerprints?")
                .setMessage("Trust the " + identities.size() + (modern ? " OMEMO 2" : " legacy OMEMO") + " fingerprints currently shown, including any marked Distrusted. New devices and changed keys will still need review.")
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
                if (modern) {
                    Map<String,Map<Integer,byte[]>> batch=new LinkedHashMap<>();
                    for (Map.Entry<OmemoDevice,OmemoFingerprint> entry:selected.entrySet()) {
                        String jid=entry.getKey().getJid().toString();
                        if (!batch.containsKey(jid)) batch.put(jid,new LinkedHashMap<>());
                        batch.get(jid).put(entry.getKey().getDeviceId(),Utilities.hexToBytes(entry.getValue().toString()));
                    }
                    loadedSession.modern().device.decideAll(batch,trusted);
                    if (trusted) controller.retryEncryptedHistory(peer,batch.containsKey(loadedSession.ownJid()));
                } else loadedSession.decideAll(selected, trusted);
                AndroidUtilities.runOnUIThread(() -> { for (OmemoDevice device : selected.keySet()) states.put(device, trusted ? "Trusted" : "Distrusted"); saving = false; refresh(); });
            } catch (Exception failed) {
                AndroidUtilities.runOnUIThread(() -> { saving = false; refresh(); if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getContext())
                        .setTitle("Could not save trust decisions").setMessage("Nothing was changed. Check your connection and try again.").setPositiveButton("OK", null).create()); });
            }
        });
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
