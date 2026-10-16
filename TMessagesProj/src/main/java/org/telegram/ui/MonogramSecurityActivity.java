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
    private volatile int generation;
    public MonogramSecurityActivity(int account, long peer) { currentAccount = account; this.peer = peer; }
    private void refresh() { if (listView != null) listView.adapter.update(true); }
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadIdentities();
        return true;
    }
    private final java.util.concurrent.ExecutorService fingerprintSetup=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile java.util.concurrent.Future<?> fingerprintSetupTask;
    private volatile com.negzero.monogram.xmpp.XmppFingerprints.Request fingerprintRequest;
    private volatile int fingerprintLookup;
    private void loadIdentities() { loadIdentities(false); }
    private void loadIdentities(boolean force) {
        MonogramController controller=MonogramController.get(currentAccount);
        final int loading=++generation; fingerprintLookup++;
        if(fingerprintSetupTask!=null) fingerprintSetupTask.cancel(true);
        if(fingerprintRequest!=null) fingerprintRequest.close();
        final boolean selectedModern=controller.modernEncryption(peer);
        if(modern!=selectedModern) { identities.clear(); states.clear(); ownFingerprint=null; }
        modern=selectedModern; status=identities.isEmpty() ? "Loading fingerprints…" : "Showing cached fingerprints; refreshing…"; refresh();
        fingerprintSetupTask=fingerprintSetup.submit(() -> {
            try {
                com.negzero.monogram.xmpp.XmppSession session=controller.session();
                if(selectedModern && !session.modernAvailable()) throw new IllegalStateException("OMEMO 2 is not initialized");
                String own=selectedModern ? Utilities.bytesToHex(session.modern().device.prepare().identity()).toLowerCase(java.util.Locale.ROOT) : session.ownFingerprint().toString();
                AndroidUtilities.runOnUIThread(() -> { if(loading==generation) { loadedSession=session; ownFingerprint=own; refresh(); } });
                java.util.Set<String> addresses=controller.knownFingerprintAddresses(peer);
                loadFingerprintAddresses(session,addresses,selectedModern,force,loading,peer<0 ? "Checking room members…" : null);
                if(peer<0) {
                    java.util.concurrent.atomic.AtomicBoolean checkingMembers=new java.util.concurrent.atomic.AtomicBoolean(true);
                    java.util.concurrent.ScheduledFuture<?> memberDeadline=session.fingerprints.afterTimeout(() -> {
                        if(loading==generation && checkingMembers.compareAndSet(true,false)) {
                            fingerprintSetupTask.cancel(true);
                            loadFingerprintAddresses(session,controller.knownFingerprintAddresses(peer),selectedModern,false,loading,"Room member discovery timed out. Showing known accounts; refresh to retry.");
                        }
                    });
                    String warning=null;
                    try { for(org.jxmpp.jid.BareJid member:session.rooms.members(controller.jid(peer))) addresses.add(member.toString()); }
                    catch(com.negzero.monogram.xmpp.OmemoSendException unsupported) {
                        warning="This room does not support legacy OMEMO. Showing fingerprints for disclosed accounts only; encrypted room messages remain unsent.";
                    } catch(Exception unavailable) { warning="Could not retrieve the complete room member list. Showing known accounts; refresh to retry."; }
                    memberDeadline.cancel(false);
                    if(checkingMembers.compareAndSet(true,false) && loading==generation && !Thread.currentThread().isInterrupted()) loadFingerprintAddresses(session,addresses,selectedModern,false,loading,warning);
                }
            } catch(Exception failed) {
                AndroidUtilities.runOnUIThread(() -> { if(loading==generation) { status="Could not refresh fingerprints. Cached results remain visible. Check the connection and refresh to retry."; refresh(); } });
            }
        });
    }
    private void loadFingerprintAddresses(com.negzero.monogram.xmpp.XmppSession session,java.util.Set<String> addresses,boolean selectedModern,boolean force,int loading,String warning) {
        if(loading!=generation) return;
        final int lookup=++fingerprintLookup;
        if(fingerprintRequest!=null) fingerprintRequest.close();
        java.util.concurrent.atomic.AtomicReference<com.negzero.monogram.xmpp.XmppFingerprints.Snapshot> latest=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
        fingerprintRequest=session.fingerprints.load(addresses,selectedModern,force,snapshot -> {
            latest.set(snapshot);
            if(!queued.compareAndSet(false,true)) return;
            AndroidUtilities.runOnUIThread(() -> {
                queued.set(false);
                if(loading!=generation || lookup!=fingerprintLookup) return;
                com.negzero.monogram.xmpp.XmppFingerprints.Snapshot found=latest.get();
                loadedSession=session; identities.clear(); identities.putAll(found.identities); states.clear();
                for(Map.Entry<OmemoDevice,OmemoFingerprint> entry:identities.entrySet()) {
                    OmemoDevice device=entry.getKey(); String state;
                    try { if(selectedModern) switch(session.modern().device.trust(device.getJid().toString(),device.getDeviceId(),Utilities.hexToBytes(entry.getValue().toString()))) {
                        case TRUSTED: state="Trusted"; break; case REJECTED: state="Distrusted"; break; default: state="Not reviewed";
                    } else switch(session.trustState(device,entry.getValue())) {
                        case trusted: state="Trusted"; break; case untrusted: state="Distrusted"; break; default: state="Not reviewed";
                    } } catch(Exception unavailable) { state="Trust state unavailable"; }
                    states.put(device,state);
                }
                String progress=!found.complete ? "Loading fingerprints: "+found.completed+"/"+found.total+" accounts…"
                    : found.failed>0 ? "Loaded available fingerprints; "+found.failed+" account lookups failed or timed out. Refresh to retry."
                    : identities.isEmpty() ? "No compatible remote OMEMO devices were published by the available accounts." : null;
                status=warning==null ? progress : warning+(progress==null ? "" : "\n"+progress); refresh();
            },100);
        });
    }
    @Override public void onFragmentDestroy() {
        generation++; fingerprintLookup++;
        if(fingerprintRequest!=null) fingerprintRequest.close();
        fingerprintSetup.shutdownNow(); super.onFragmentDestroy();
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
        else if (item.id==6 && !saving) loadIdentities(true);
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
