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
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import com.negzero.monogram.xmpp.XmppMessage;
import com.negzero.monogram.xmpp.Omemo2Bundle;
import org.telegram.messenger.Utilities;
import org.jxmpp.jid.impl.JidCreate;

public final class MonogramSecurityActivity extends MonogramSettingsActivity {
    private final long peer;
    private final Map<OmemoDevice, OmemoFingerprint> identities = new LinkedHashMap<>();
    private final Map<OmemoDevice, String> states = new LinkedHashMap<>();
    private final ArrayList<String> roomMembers=new ArrayList<>();
    private boolean roomMembersChecked;
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
        roomMembersChecked=false;
        if(fingerprintSetupTask!=null) fingerprintSetupTask.cancel(true);
        if(fingerprintRequest!=null) fingerprintRequest.close();
        if(force) try { controller.session().rooms.retryUnavailable(); } catch(Exception disconnected) {}
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
                    try {
                        java.util.Set<String> members=new java.util.LinkedHashSet<>();
                        for(org.jxmpp.jid.BareJid member:session.rooms.members(controller.jid(peer))) members.add(member.toString());
                        addresses.clear(); addresses.addAll(members);
                        ArrayList<String> listed=new ArrayList<>(members); listed.remove(session.ownJid()); java.util.Collections.sort(listed);
                        AndroidUtilities.runOnUIThread(() -> { if(loading==generation) { roomMembers.clear(); roomMembers.addAll(listed); roomMembersChecked=true; refresh(); } });
                    }
                    catch(com.negzero.monogram.xmpp.OmemoSendException unsupported) {
                        warning=unsupported.reason==com.negzero.monogram.xmpp.OmemoSendException.Reason.ROOM_UNSUPPORTED
                            ? "Room OMEMO is unavailable. Join a members-only room that discloses participant addresses. Showing fingerprints for known accounts; encrypted room messages remain unsent."
                            : "Could not retrieve the complete room member list. Showing known accounts; refresh to retry."+(unsupported.detail==null ? "" : "\n"+unsupported.detail);
                    } catch(Exception unavailable) { warning="Could not retrieve the complete room member list. Showing known accounts; refresh to retry."; }
                    memberDeadline.cancel(false);
                    if(checkingMembers.compareAndSet(true,false) && loading==generation && !Thread.currentThread().isInterrupted()) loadFingerprintAddresses(session,addresses,selectedModern,false,loading,warning);
                }
            } catch(Exception failed) {
                AndroidUtilities.runOnUIThread(() -> { if(loading==generation) { status="Could not refresh fingerprints: "+com.negzero.monogram.xmpp.XmppRequestException.describe(failed)+". Cached results remain visible. Refresh to retry."; refresh(); } });
            }
        });
    }
    private void loadFingerprintAddresses(com.negzero.monogram.xmpp.XmppSession session,java.util.Set<String> addresses,boolean selectedModern,boolean force,int loading,String warning) {
        if(loading!=generation) return;
        java.util.Set<String> lookupAddresses=new java.util.LinkedHashSet<>(addresses);
        if(peer<0) {
            lookupAddresses.removeAll(MonogramController.get(currentAccount).excludedRoomRecipients(peer));
        }
        final int lookup=++fingerprintLookup;
        if(fingerprintRequest!=null) fingerprintRequest.close();
        java.util.concurrent.atomic.AtomicReference<com.negzero.monogram.xmpp.XmppFingerprints.Snapshot> latest=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
        fingerprintRequest=session.fingerprints.loadInteractive(lookupAddresses,selectedModern,force,snapshot -> {
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
                if(found.complete && !found.failures.isEmpty()) {
                    StringBuilder details=new StringBuilder(progress==null ? "" : progress);
                    for(Map.Entry<String,String> failure:found.failures.entrySet()) details.append("\n").append(failure.getKey()).append(": ").append(failure.getValue());
                    progress=details.toString();
                }
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
        String sendError=controller.lastSendError(peer);
        if(sendError!=null) items.add(UItem.asShadow("Last send failure: "+org.telegram.ui.Components.AlertsCreator.monogramSendErrorText(sendError)));
        items.add(UItem.asHeader("Encryption"));
        items.add(UItem.asCheck(1, "Use OMEMO encryption").setChecked(controller.encrypted(peer)));
        if(peer>0) items.add(UItem.asButton(5,"OMEMO version",modern ? "OMEMO 2" : "Legacy OMEMO").setEnabled(!saving));
        else items.add(UItem.asShadow("Room encryption uses legacy OMEMO and requires a members-only room that reveals participant addresses. Available devices need compatible trusted keys. OMEMO 2 rooms are not available yet."));
        items.add(UItem.asShadow(controller.jid(peer)+"\n\nCompare fingerprints through another trusted channel before trusting devices. New devices and changed keys require a new decision."));
        items.add(UItem.asHeader("Reactions"));
        items.add(UItem.asCheck(7,"Allow unencrypted reactions").setChecked(controller.plainReactionsAllowed(peer)));
        items.add(UItem.asShadow("Standard reactions are visible to the server with legacy OMEMO. OMEMO 2 direct chats encrypt reactions; this permission never changes message encryption."));
        items.add(UItem.asHeader("Connection and verification"));
        items.add(UItem.asButton(4, R.drawable.msg_retry, "XMPP connection"));
        items.add(UItem.asButton(8,"Contact authorization").setEnabled(peer>0 ? !controller.accountJid().equals(controller.jid(peer)) : roomMembers.stream().anyMatch(member -> !controller.accountJid().equals(member))));
        items.add(UItem.asButton(6,R.drawable.msg_retry,"Refresh fingerprints").setEnabled(!saving));
        items.add(UItem.asShadow(status));
        if (ownFingerprint != null) {
            items.add(UItem.asHeader("Your device"));
            items.add(UItem.asButton(3,modern ? "Your OMEMO 2 fingerprint" : "Your legacy OMEMO fingerprint")); items.add(UItem.asShadow(ownFingerprint));
        }
        if(peer<0) {
            items.add(UItem.asHeader("Recipient policy"));
            items.add(UItem.asCheck(9,"Skip unreachable devices").setChecked(controller.automaticRoomRecipients(peer)));
            items.add(UItem.asShadow("Unavailable device lists or key bundles are skipped for this send. Skipped devices cannot read it. Offline devices with usable keys remain included; unknown or changed fingerprints still require approval. Turn this off to require every selected recipient."));
            String skipped=controller.skippedRoomRecipients(peer); if(skipped!=null) items.add(UItem.asShadow(skipped));
        }
        if(peer<0 && !roomMembers.isEmpty()) {
            java.util.Set<String> excluded=controller.excludedRoomRecipients(peer);
            items.add(UItem.asHeader("Encrypted recipients"));
            for(int i=0;i<roomMembers.size();i++) {
                String address=roomMembers.get(i);
                items.add(UItem.asCheck(100000+i,address).setChecked(!excluded.contains(address)).setEnabled(!saving && roomMembersChecked));
                if(!excluded.contains(address) && identities.keySet().stream().noneMatch(device -> address.equals(device.getJid().toString())))
                    items.add(UItem.asShadow("No fingerprint loaded for this account. Unavailable keys are skipped only when automatic skipping is enabled; available fingerprints still need trust."));
            }
            items.add(UItem.asShadow("Checked members receive keys for your new encrypted messages. Unchecked members cannot decrypt those messages. This only affects messages you send; it does not change room membership or fingerprint trust."));
        }
        if (!identities.isEmpty()) {
            items.add(UItem.asHeader("Devices"));
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
        if(item.id==9) {
            try { controller.setAutomaticRoomRecipients(peer,!controller.automaticRoomRecipients(peer)); loadIdentities(); }
            catch(Exception failed) { showDialog(new AlertDialog.Builder(getContext()).setTitle("Could not save recipient policy").setMessage("Try again.").setPositiveButton("OK",null).create()); }
        } else if(item.id>=100000 && item.id-100000<roomMembers.size() && !saving && roomMembersChecked) {
            String address=roomMembers.get(item.id-100000);
            boolean included=!controller.excludedRoomRecipients(peer).contains(address);
            if(!included) setRoomRecipient(address,true);
            else showDialog(new AlertDialog.Builder(getContext()).setTitle("Exclude encrypted recipient?")
                .setMessage(address+" will not receive keys for new encrypted messages you send to this room and will not be able to read them. Other included members still require trusted fingerprints. You can include this account again here.")
                .setPositiveButton("Exclude recipient",(dialog,which) -> setRoomRecipient(address,false)).setNegativeButton("Cancel",null).create());
        } else if(item.id==8) {
            if(peer>0) presentFragment(new MonogramContactActivity(currentAccount,controller.jid(peer)));
            else {
                ArrayList<String> contacts=new ArrayList<>(roomMembers); contacts.remove(controller.accountJid());
                if(!contacts.isEmpty()) showDialog(new AlertDialog.Builder(getContext()).setTitle("Contact authorization")
                    .setItems(contacts.toArray(new CharSequence[0]),(dialog,which) -> presentFragment(new MonogramContactActivity(currentAccount,contacts.get(which)))).create());
            }
        } else if(item.id==7) {
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
    private void setRoomRecipient(String address,boolean included) {
        try {
            MonogramController controller=MonogramController.get(currentAccount);
            if(loadedSession==null || !controller.accountJid().equals(loadedSession.ownJid()) || !controller.setRoomRecipient(peer,address,included))
                throw new IllegalStateException("Could not save recipient");
            loadIdentities();
        } catch(Exception failed) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Could not save encrypted recipient")
                .setMessage("The recipient selection was not changed. Try again.").setPositiveButton("OK",null).create());
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
