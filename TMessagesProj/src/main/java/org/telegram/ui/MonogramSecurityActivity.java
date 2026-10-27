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
    private final boolean fingerprintsPage;
    private final String fingerprintContact;
    private boolean accountPage;
    private int ownDeviceId;
    private final Map<OmemoDevice, OmemoFingerprint> identities = new LinkedHashMap<>();
    private final Map<OmemoDevice, String> states = new LinkedHashMap<>();
    private final ArrayList<String> roomMembers=new ArrayList<>();
    private boolean roomMembersChecked;
    private com.negzero.monogram.xmpp.XmppSession loadedSession;
    private String ownFingerprint;
    private String status = "Loading fingerprints…";
    private boolean saving;
    private boolean showRecipients;
    private boolean modern;
    private volatile int generation;
    public MonogramSecurityActivity(int account, long peer) { this(account,peer,false,null); }
    private MonogramSecurityActivity(int account,long peer,boolean fingerprintsPage,String contact) {
        currentAccount=account;this.peer=peer;this.fingerprintsPage=fingerprintsPage;fingerprintContact=contact;
    }
    public static MonogramSecurityActivity forAccount(int account) {
        MonogramController controller=MonogramController.get(account);
        MonogramSecurityActivity page=new MonogramSecurityActivity(account,org.telegram.messenger.UserConfig.getInstance(account).getClientUserId(),true,controller.accountJid());
        page.accountPage=true;page.modern=true;return page;
    }
    private static String groupedFingerprint(String fingerprint) {
        if(fingerprint==null)return "Loading fingerprint…";
        StringBuilder grouped=new StringBuilder();
        for(int i=0;i<fingerprint.length();i+=8){if(i>0)grouped.append(i%32==0 ? '\n' : ' ');grouped.append(fingerprint.substring(i,Math.min(i+8,fingerprint.length())));}
        return grouped.toString();
    }
    private void openFingerprints(String contact) {
        MonogramSecurityActivity page=new MonogramSecurityActivity(currentAccount,peer,true,contact);
        page.identities.putAll(identities);page.states.putAll(states);page.modern=modern;
        page.ownFingerprint=ownFingerprint;page.loadedSession=loadedSession;
        presentFragment(page);
    }
    private Map<OmemoDevice,OmemoFingerprint> displayedIdentities() {
        Map<OmemoDevice,OmemoFingerprint> result=new LinkedHashMap<>();
        identities.forEach((device,fingerprint) -> {
            if(fingerprintContact==null || fingerprintContact.equals(device.getJid().toString())) result.put(device,fingerprint);
        });
        return result;
    }
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
        final boolean selectedModern=accountPage ? modern : controller.modernEncryption(peer);
        if(modern!=selectedModern) { identities.clear(); states.clear(); ownFingerprint=null; }
        modern=selectedModern; status=!fingerprintsPage ? "Checking conversation…" : identities.isEmpty() ? "Loading fingerprints…" : "Showing cached fingerprints; refreshing…"; refresh();
        fingerprintSetupTask=fingerprintSetup.submit(() -> {
            try {
                com.negzero.monogram.xmpp.XmppSession session=controller.session();
                if(selectedModern && !session.modernAvailable()) throw new IllegalStateException("OMEMO 2 is not initialized");
                String own=selectedModern ? Utilities.bytesToHex(session.modern().device.prepare().identity()).toLowerCase(java.util.Locale.ROOT) : session.ownFingerprint().toString();
                AndroidUtilities.runOnUIThread(() -> { if(loading==generation) { loadedSession=session; ownFingerprint=own;ownDeviceId=selectedModern ? session.modern().device.id : session.ownDeviceId(); refresh(); } });
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
        if(!fingerprintsPage) {
            AndroidUtilities.runOnUIThread(() -> { if(loading==generation) { status=warning;refresh(); } });return;
        }
        java.util.Set<String> lookupAddresses=new java.util.LinkedHashSet<>(addresses);
        if(fingerprintContact!=null) lookupAddresses.retainAll(java.util.Collections.singleton(fingerprintContact));
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
                loadedSession=session;
                com.negzero.monogram.xmpp.XmppFingerprints.mergeDisplay(identities,lookupAddresses,found);states.clear();
                for(Map.Entry<OmemoDevice,OmemoFingerprint> entry:identities.entrySet()) {
                    OmemoDevice device=entry.getKey(); String state;
                    try { if(selectedModern) switch(session.modern().device.trust(device.getJid().toString(),device.getDeviceId(),Utilities.hexToBytes(entry.getValue().toString()))) {
                        case TRUSTED: state="Trusted"; break; case REJECTED: state="Blocked"; break; default: state="Not reviewed";
                    } else switch(session.trustState(device,entry.getValue())) {
                        case trusted: state="Trusted"; break; case untrusted: state="Blocked"; break; default: state="Not reviewed";
                    } } catch(Exception unavailable) { state="Trust state unavailable"; }
                    states.put(device,found.complete && found.failures.containsKey(device.getJid().toString()) ? state+" · cached; refresh failed" : state);
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
    @Override protected CharSequence getTitle() { return accountPage ? "Account OMEMO" : !fingerprintsPage ? "Conversation security" : fingerprintContact==null ? "Fingerprints" : "Devices"; }
    private void fillFingerprints(ArrayList<UItem> items) {
        MonogramController controller=MonogramController.get(currentAccount);
        Map<OmemoDevice,OmemoFingerprint> displayed=displayedIdentities();
        if(accountPage) {
            items.add(UItem.asShadow(controller.accountJid()));
            items.add(UItem.asButton(30,"OMEMO version",modern ? "OMEMO 2" : "Legacy OMEMO").setEnabled(!saving));
            items.add(UItem.asHeader("This device"));
            if(ownDeviceId>0)items.add(UItem.asSettingsCell(0,"Device ID",Integer.toString(ownDeviceId)).setEnabled(false));
            items.add(UItem.asShadow(groupedFingerprint(ownFingerprint)));
            if(ownFingerprint!=null)items.add(UItem.asButton(3,R.drawable.msg_copy,"Copy this device’s fingerprint"));
            items.add(UItem.asHeader("Other encryption devices on your account"));
        } else if(fingerprintContact!=null) items.add(UItem.asShadow(fingerprintContact));
        items.add(UItem.asButton(6,R.drawable.msg_retry,"Refresh fingerprints").setEnabled(!saving));
        if(peer>0 && !controller.accountJid().equals(controller.jid(peer))) {
            items.add(UItem.asButton(8,R.drawable.msg_addcontact,"Control access"));
            if(status!=null && !status.startsWith("Loading"))items.add(UItem.asShadow(status));
        }
        if(!displayed.isEmpty()) {
            items.add(UItem.asButton(2,saving ? "Saving trust decisions…" : "Trust all fingerprints").accent().setEnabled(!saving));
            items.add(UItem.asShadow("Verify fingerprints before trusting devices."));
            int id=100;
            if(peer<0 && fingerprintContact==null) {
                Map<String,Integer> contacts=new LinkedHashMap<>();
                displayed.keySet().forEach(device -> contacts.merge(device.getJid().toString(),1,Integer::sum));
                for(Map.Entry<String,Integer> contact:contacts.entrySet()) {
                    UItem row=UItem.asButton(id++,contact.getKey().equals(controller.accountJid()) ? "Your devices" : contact.getKey(),contact.getValue()+(contact.getValue()==1 ? " device" : " devices"));
                    row.object=contact.getKey();items.add(row);
                }
            } else for(Map.Entry<OmemoDevice,OmemoFingerprint> entry:displayed.entrySet()) {
                OmemoDevice device=entry.getKey();
                String label=(peer>0 && device.getJid().toString().equals(controller.accountJid()) ? "Your device " : "Device ")+device.getDeviceId();
                UItem row=UItem.asButton(id++,label,states.get(device)).setEnabled(!saving);
                row.object=device;row.object2=entry.getValue();items.add(row);
                if(accountPage)items.add(UItem.asShadow(groupedFingerprint(entry.getValue().toString())));
            }
        } else items.add(UItem.asShadow(status!=null && status.startsWith("Loading") ? "Loading fingerprints…" : accountPage ? "No other encryption devices found for this OMEMO version." : "No devices available. See Diagnostics for details."));
        items.add(UItem.asShadow(null));
        if(accountPage) {
            if(status!=null)items.add(UItem.asShadow(status));
            items.add(UItem.asShadow("These are published encryption devices, not a list of active login sessions. Verify fingerprints before trusting another device. Blocking stops new message keys from being sent to that identity; it does not sign that client out or erase previously received messages."));
        }
        if(ownFingerprint!=null && !accountPage) items.add(UItem.asButton(3,"Your fingerprint"));
        items.add(UItem.asButton(11,"Diagnostics"));
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        MonogramController controller = MonogramController.get(currentAccount);
        if(fingerprintsPage) { fillFingerprints(items);return; }
        items.add(UItem.asHeader("Encryption"));
        items.add(UItem.asCheck(25,"Choose encryption automatically").setChecked(controller.automaticEncryption(peer)));
        items.add(UItem.asShadow("A closed lock beside a message’s time marks recorded end-to-end encryption. Unencrypted messages have no lock. Queued messages receive an indicator only after sending is confirmed."));

        items.add(UItem.asCheck(1, peer<0 ? "Use legacy OMEMO encryption" : "Use OMEMO encryption").setChecked(controller.encrypted(peer)));
        items.add(UItem.asButton(5,"OMEMO version",modern ? "OMEMO 2" : "Legacy OMEMO").setEnabled(!saving));
        if(peer<0) items.add(UItem.asCheck(9,"Skip unavailable devices").setChecked(controller.automaticRoomRecipients(peer)));
        items.add(UItem.asShadow(null));
        if(peer>0 && !controller.accountJid().equals(controller.jid(peer))) {
            items.add(UItem.asCheck(31,"Blindly trust this contact").setChecked(controller.blindlyTrustContact(peer)).setEnabled(!saving));
            items.add(UItem.asShadow("Automatically trust this contact’s new devices and changed keys in both OMEMO versions. Blocked devices stay blocked. Turning this off stops future automatic approvals; accepted fingerprints remain trusted."));
        }
        if(peer<0) {
            items.add(UItem.asCheck(32,"Automatically trust room fingerprints").setChecked(controller.blindlyTrustRoom(peer)).setEnabled(!saving));
            items.add(UItem.asShadow("Trusts new devices and changed keys for known members of this room. Blocked devices stay blocked. Decisions are shared with direct chats and remain trusted when this is turned off."));
        }
        items.add(UItem.asButton(13,"Devices and fingerprints"));
        if(peer>0 && !controller.accountJid().equals(controller.jid(peer)))items.add(UItem.asButton(26,"Revoke presence access").setEnabled(!saving));
        items.add(UItem.asHeader("More"));
        if(peer<0 && !roomMembers.isEmpty()) {
            java.util.Set<String> excluded=controller.excludedRoomRecipients(peer);
            long included=roomMembers.stream().filter(member -> !excluded.contains(member)).count();
            items.add(UItem.asButton(12,"Encrypted recipients",showRecipients ? "Hide" : included+"/"+roomMembers.size()));
            if(showRecipients) {
                for(int i=0;i<roomMembers.size();i++) {
                    String address=roomMembers.get(i);
                    items.add(UItem.asCheck(100000+i,address).setChecked(!excluded.contains(address)).setEnabled(!saving && roomMembersChecked));
                }
                items.add(UItem.asShadow("Unchecked members cannot read new encrypted messages you send."));
            }
        }
        items.add(UItem.asCheck(7,"Allow unencrypted reactions").setChecked(controller.plainReactionsAllowed(peer)));
        items.add(UItem.asButton(8,"Control access").setEnabled(peer>0 ? !controller.accountJid().equals(controller.jid(peer)) : roomMembers.stream().anyMatch(member -> !controller.accountJid().equals(member))));
        items.add(UItem.asButton(4, R.drawable.msg_retry, "XMPP connection"));
        items.add(UItem.asButton(10,"About encryption"));
        items.add(UItem.asButton(11,"Diagnostics",controller.lastSendError(peer)!=null ? "Send failed" : null));
    }
    private String diagnostics() {
        MonogramController controller=MonogramController.get(currentAccount);
        StringBuilder details=new StringBuilder();
        String error=controller.lastSendError(peer);
        if(error!=null) details.append("Last send failure: ").append(org.telegram.ui.Components.AlertsCreator.monogramSendErrorText(error)).append("\n\n");
        if(status!=null) details.append(status).append("\n\n");
        String skipped=peer<0 ? controller.skippedRoomRecipients(peer) : null;
        if(skipped!=null) details.append(skipped);
        return details.length()==0 ? "No issues reported." : details.toString().trim();
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        MonogramController controller = MonogramController.get(currentAccount);
        if(item.id==32 && peer<0 && !saving) {
            if(controller.blindlyTrustRoom(peer)) {
                try {controller.setBlindlyTrustRoom(peer,false);refresh();}catch(Exception error){showBlindTrustError(error);}
            } else showDialog(new AlertDialog.Builder(getContext()).setTitle("Automatically trust room fingerprints?")
                .setMessage("New devices and changed keys of known members will be trusted without verification. A compromised member account or key publication can expose future messages. These device decisions also apply in direct chats; turning this off does not undo them. Explicitly blocked devices stay blocked.")
                .setPositiveButton("Enable automatic trust",(dialog,which)->{
                    try {controller.setBlindlyTrustRoom(peer,true);loadIdentities(true);}catch(Exception error){showBlindTrustError(error);}
                }).setNegativeButton("Cancel",null).create());
        } else if(item.id==31 && peer>0 && !saving) {
            if(controller.blindlyTrustContact(peer)) {
                try{controller.setBlindlyTrustContact(peer,false);refresh();}catch(Exception error){showBlindTrustError(error);}
            } else showDialog(new AlertDialog.Builder(getContext()).setTitle("Blindly trust this contact?")
                .setMessage("New devices and changed encryption keys for "+controller.jid(peer)+" will be trusted without fingerprint verification, in both OMEMO versions. An attacker who controls their account or key publication could receive your future messages. Blocked devices stay blocked. This does not change whether messages are encrypted.")
                .setPositiveButton("Enable blind trust",(dialog,which)->{
                    try{controller.setBlindlyTrustContact(peer,true);loadIdentities(true);}catch(Exception error){showBlindTrustError(error);}
                }).setNegativeButton("Cancel",null).create());
        } else if(item.id==30 && accountPage && !saving) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("OMEMO version")
                .setItems(new CharSequence[]{"OMEMO 2","Legacy OMEMO"},(dialog,which)->{
                    modern=which==0;identities.clear();states.clear();ownFingerprint=null;ownDeviceId=0;loadIdentities(true);
                }).create());
        } else if(item.id==13) {
            openFingerprints(null);
        } else if(fingerprintsPage && item.object instanceof String) {
            openFingerprints((String)item.object);
        } else if(item.id==10) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("About encryption")
                    .setMessage(controller.jid(peer)+"\n\nCompare fingerprints through another trusted channel. New devices and changed keys need review before they are trusted.\n\n"
                            +(peer<0 ? "Room encryption uses legacy OMEMO in members-only rooms that reveal participant addresses. Automatic skipping sends to available trusted devices; unavailable, inactive, unverified or distrusted devices are omitted and cannot read that message. Skipping never approves fingerprints. Turn it off to require every selected recipient.\n\n" : "")
                            +"Automatic mode checks OMEMO 2, then legacy OMEMO, then unencrypted sending once every 24 hours per conversation. Only unencrypted selection shows a warning. Connection failures keep encryption unchanged. Manual encryption choices disable automatic selection.\n\nLegacy OMEMO reactions are visible to the server. OMEMO 2 direct chats encrypt reactions. Reaction permissions never change message encryption.")
                    .setPositiveButton("Close",null).create());
        } else if(item.id==11) {
            String details=diagnostics();
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Diagnostics").setMessage(details)
                    .setPositiveButton("Close",null).setNeutralButton("Copy",(dialog,which) -> AndroidUtilities.addToClipboard(details)).create());
        } else if(item.id==25) {
            controller.setAutomaticEncryption(peer,!controller.automaticEncryption(peer));refresh();
        } else if(item.id==12) {
            showRecipients=!showRecipients;refresh();
        } else if(item.id==9) {
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
                if(!contacts.isEmpty()) showDialog(new AlertDialog.Builder(getContext()).setTitle("Control access")
                    .setItems(contacts.toArray(new CharSequence[0]),(dialog,which) -> presentFragment(new MonogramContactActivity(currentAccount,contacts.get(which)))).create());
            }
        } else if(item.id==26 && peer>0 && !saving) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Revoke presence access?")
                .setMessage("This contact will stop receiving your online status. Public profile information and OMEMO public keys remain available. Use Devices and fingerprints to block a device from future encrypted messages.")
                .setPositiveButton("Revoke access",(dialog,which)->{
                    saving=true;refresh();
                    controller.contactAuthorization(controller.jid(peer),com.negzero.monogram.xmpp.XmppContacts.Action.REVOKE,error->{
                        saving=false;if(error!=null)showDialog(new AlertDialog.Builder(getContext()).setTitle("Could not revoke access").setMessage(error).setPositiveButton("OK",null).create());
                        loadIdentities();
                    });
                }).setNegativeButton("Cancel",null).create());
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
        } else if(item.id==5 && peer<0 && !saving) {
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Room encryption")
                .setMessage("This Monogram version supports legacy OMEMO in rooms. Select it explicitly to disable automatic selection. This controls your outgoing messages; it cannot change another member’s OMEMO version.")
                .setPositiveButton("Use legacy OMEMO",(dialog,which)->{controller.setEncryption(peer,XmppMessage.Encryption.LEGACY_OMEMO);loadIdentities(true);})
                .setNegativeButton("Cancel",null).create());
        } else if (item.id==5 && !saving) showDialog(new AlertDialog.Builder(getContext()).setTitle("OMEMO version")
                .setItems(new CharSequence[]{"OMEMO 2 · encrypted captions","Legacy OMEMO · older clients"},(dialog,which) -> {
                    controller.setEncryption(peer,which==0 ? XmppMessage.Encryption.OMEMO2 : XmppMessage.Encryption.LEGACY_OMEMO); loadIdentities();
                }).create());
        else if (item.id==6 && !saving) loadIdentities(true);
        else if (item.id == 4) presentFragment(new MonogramConnectionActivity(currentAccount));
        else if(item.id==3 && accountPage && ownFingerprint!=null) {
            AndroidUtilities.addToClipboard(ownFingerprint);
            org.telegram.ui.Components.BulletinFactory.of(this).createCopyBulletin("Fingerprint copied").show();
        }
        else if (item.id == 3) showDialog(new AlertDialog.Builder(getContext()).setTitle("Your fingerprint").setMessage(ownFingerprint)
                .setPositiveButton("Copy", (d, w) -> AndroidUtilities.addToClipboard(ownFingerprint)).setNegativeButton("Close", null).create());
        else if (item.id == 2 && !saving) {
            Map<OmemoDevice,OmemoFingerprint> selected=displayedIdentities();
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Trust all fingerprints?")
                    .setMessage("Trust the " + selected.size() + (modern ? " OMEMO 2" : " legacy OMEMO") + " fingerprints in this list, including any marked Blocked. New devices and changed keys will still need review.")
                    .setPositiveButton("Trust all", (dialog, which) -> decide(selected, true)).setNegativeButton("Cancel", null).create());
        } else if (item.object instanceof OmemoDevice && item.object2 instanceof OmemoFingerprint && !saving) {
            OmemoDevice device=(OmemoDevice)item.object;
            OmemoFingerprint fingerprint=(OmemoFingerprint)item.object2;
            Map<OmemoDevice,OmemoFingerprint> selected=new LinkedHashMap<>();selected.put(device,fingerprint);
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Device " + device.getDeviceId()).setMessage(device.getJid() + "\n\n" + fingerprint + "\n\nBlock this device to stop providing keys for future messages. Previously received messages remain readable. Trusting it again restores permission for new messages.")
                    .setPositiveButton("Trust", (d, w) -> decide(selected, true)).setNegativeButton("Block device", (d, w) -> decide(selected, false))
                    .setNeutralButton("Copy", (d, w) -> AndroidUtilities.addToClipboard(fingerprint.toString())).create());
        }
    }
    private void showBlindTrustError(Exception error) {
        showDialog(new AlertDialog.Builder(getContext()).setTitle("Could not save blind trust").setMessage(error.getMessage()).setPositiveButton("OK",null).create());
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
                controller.trustDecisionsChanged(peer);
                AndroidUtilities.runOnUIThread(() -> { for (OmemoDevice device : selected.keySet()) states.put(device, trusted ? "Trusted" : "Distrusted"); saving = false; refresh(); });
            } catch (Exception failed) {
                AndroidUtilities.runOnUIThread(() -> { saving = false; refresh(); if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getContext())
                        .setTitle("Could not save trust decisions").setMessage("Nothing was changed. Check your connection and try again.").setPositiveButton("OK", null).create()); });
            }
        });
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
