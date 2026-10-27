// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import com.negzero.monogram.xmpp.*;
import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.*;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;

/** Foreground-only device review; discovery and explicit decisions never run on the UI thread. */
final class MonogramTrustPrompt {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<String> shown=new HashSet<>();
    private ChatActivity fragment;private long peer,lastScan;private volatile long generation;
    private volatile boolean active;private boolean force,urgent,dirty;private volatile XmppFingerprints.Request request;
    private Future<?> task;private AlertDialog dialog;
    private final Runnable scan=this::scan;
    void resume(ChatActivity fragment,long peer) { this.fragment=fragment;this.peer=peer;active=true;lastScan=android.os.SystemClock.elapsedRealtime()-60000;changed(false); }
    void pause() {active=false;generation++;AndroidUtilities.cancelRunOnUIThread(scan);if(task!=null)task.cancel(true);if(request!=null)request.close();if(dialog!=null)dialog.dismiss();dialog=null;}
    void close() {pause();worker.shutdownNow();}
    void changed(boolean force) {changed(force,force);}
    void changed(boolean force,boolean urgent) {
        if(!active || peer==0)return;this.force|=force;this.urgent|=urgent;dirty=true;
        AndroidUtilities.cancelRunOnUIThread(scan);
        AndroidUtilities.runOnUIThread(scan,Math.max(300,(this.urgent || this.force ? 5000 : 60000)-(android.os.SystemClock.elapsedRealtime()-lastScan)));
    }
    void devicesChanged(Object target) {
        if(!active)return;
        if(target instanceof Number) {if(((Number)target).longValue()==peer)changed(false,true);}
        else if(target instanceof String) {
            MonogramController controller=MonogramController.get(fragment.getCurrentAccount());
            if(controller.knownFingerprintAddresses(peer).contains(target))changed(true);
        }
    }
    private void scan() {
        if(!active || dialog!=null || fragment.getParentActivity()==null)return;
        MonogramController controller=MonogramController.get(fragment.getCurrentAccount());
        if(!controller.encrypted(peer) && !controller.automaticEncryption(peer))return;
        lastScan=android.os.SystemClock.elapsedRealtime();long stamp=++generation;
        boolean refresh=force;force=false;urgent=false;dirty=false;boolean modern=controller.modernEncryption(peer);String own=controller.accountJid();
        if(task!=null)task.cancel(true);if(request!=null)request.close();
        task=worker.submit(()->{
            try {
                XmppSession session=controller.session();Set<String> addresses=new LinkedHashSet<>();
                if(peer<0)for(org.jxmpp.jid.BareJid member:session.rooms.members(controller.jid(peer)))addresses.add(member.toString());
                else addresses.addAll(controller.knownFingerprintAddresses(peer));
                addresses.removeAll(controller.excludedRoomRecipients(peer));
                if(!active || generation!=stamp)return;
                request=session.fingerprints.loadInteractive(addresses,modern,refresh,snapshot->{
                    if(!snapshot.complete || !active || generation!=stamp)return;
                    try {
                        Map<OmemoDevice,OmemoFingerprint> unknown=new LinkedHashMap<>();
                        for(Map.Entry<OmemoDevice,OmemoFingerprint> entry:snapshot.identities.entrySet()) {
                            OmemoDevice device=entry.getKey();
                            if(device.getJid().toString().equals(own) && device.getDeviceId()==(modern ? session.modern().device.id : session.ownDeviceId()))continue;
                            boolean undecided=modern ? session.modern().device.trust(device.getJid().toString(),device.getDeviceId(),Utilities.hexToBytes(entry.getValue().toString()))==Omemo2Device.Trust.UNDECIDED
                                    : session.trustState(device,entry.getValue())==TrustState.undecided;
                            if(undecided)unknown.put(device,entry.getValue());
                        }
                        AndroidUtilities.runOnUIThread(()->{
                            if(current(controller,session,own,modern,stamp))show(controller,session,own,modern,stamp,unknown);
                        });
                    }catch(Exception unavailable){/* A failed discovery is never a trust decision. */}
                });
                if(!active || generation!=stamp)request.close();
            }catch(Exception unavailable){/* Conversation security retains explicit retry/diagnostics. */}
        });
    }
    private boolean current(MonogramController controller,XmppSession session,String own,boolean modern,long stamp) {
        try {return active && generation==stamp && own.equals(controller.accountJid()) && controller.session()==session && modern==controller.modernEncryption(peer);}
        catch(Exception disconnected){return false;}
    }
    static Map<OmemoDevice,OmemoFingerprint> unseen(Map<OmemoDevice,OmemoFingerprint> unknown,Set<String> shown,boolean modern) {
        Map<OmemoDevice,OmemoFingerprint> result=new LinkedHashMap<>();
        for(Map.Entry<OmemoDevice,OmemoFingerprint> entry:unknown.entrySet()) {
            if(!shown.contains(signature(entry,modern)))result.put(entry.getKey(),entry.getValue());
            if(result.size()==16)break;
        }
        return result;
    }
    private static String signature(Map.Entry<OmemoDevice,OmemoFingerprint> entry,boolean modern) {return modern+":"+entry.getKey()+":"+entry.getValue();}
    private void show(MonogramController controller,XmppSession session,String own,boolean modern,long stamp,Map<OmemoDevice,OmemoFingerprint> unknown) {
        Map<OmemoDevice,OmemoFingerprint> selected=unseen(unknown,shown,modern);if(selected.isEmpty() || dialog!=null)return;
        StringBuilder details=new StringBuilder("Compare these fingerprints through a trusted channel before approving. Trust and deny decisions apply to this account in both direct and group conversations.\n");
        for(Map.Entry<OmemoDevice,OmemoFingerprint> entry:selected.entrySet()) {
            shown.add(signature(entry,modern));details.append("\n").append(entry.getKey().getJid()).append(" · device ").append(entry.getKey().getDeviceId()).append("\n").append(entry.getValue()).append("\n");
        }
        if(unknown.size()>selected.size())details.append("\nMore devices can be reviewed in Conversation security.");
        dialog=new AlertDialog.Builder(fragment.getParentActivity()).setTitle("Unreviewed "+(modern ? "OMEMO 2" : "legacy OMEMO")+" fingerprints")
            .setMessage(details.toString()).setPositiveButton("Trust shown",(d,w)->decide(controller,session,own,modern,stamp,selected,true))
            .setNegativeButton("Deny trust",(d,w)->decide(controller,session,own,modern,stamp,selected,false))
            .setNeutralButton("Later",null).create();
        dialog.setOnDismissListener(d->{dialog=null;if(active && dirty)changed(force,urgent);});fragment.showDialog(dialog);
    }
    private void decide(MonogramController controller,XmppSession session,String own,boolean modern,long stamp,Map<OmemoDevice,OmemoFingerprint> selected,boolean trust) {
        controller.worker.execute(()->{
            try {
                if(!own.equals(controller.accountJid()) || controller.session()!=session)return;
                if(modern) {
                    Map<String,Map<Integer,byte[]>> batch=new LinkedHashMap<>();
                    for(Map.Entry<OmemoDevice,OmemoFingerprint> entry:selected.entrySet())batch.computeIfAbsent(entry.getKey().getJid().toString(),key->new LinkedHashMap<>()).put(entry.getKey().getDeviceId(),Utilities.hexToBytes(entry.getValue().toString()));
                    session.modern().device.decideAll(batch,trust);
                }else session.decideAll(selected,trust);
                controller.trustDecisionsChanged(peer);
                if(trust)controller.retryEncryptedHistory(peer,selected.keySet().stream().anyMatch(device->own.equals(device.getJid().toString())));
                AndroidUtilities.runOnUIThread(()->{if(current(controller,session,own,modern,stamp))changed(false);});
            }catch(Exception failed){AndroidUtilities.runOnUIThread(()->{
                if(current(controller,session,own,modern,stamp))fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity()).setTitle("Could not save trust decisions").setMessage("Review devices in Conversation security and try again.").setPositiveButton("OK",null).create());
            });}
        });
    }
}
