// SPDX-License-Identifier: AGPL-3.0-only
package org.telegram.ui;

import android.view.View;
import com.negzero.monogram.xmpp.PqOmemoDevice;
import com.negzero.monogram.xmpp.XmppPqOmemo;
import com.negzero.monogram.xmpp.XmppSession;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

/** Native settings and explicit hybrid-fingerprint review for the separate experimental protocol. */
public final class MonogramPqSecurityActivity extends MonogramSettingsActivity {
    private final long peer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"PQ fingerprints"); t.setDaemon(true); return t; });
    private final ArrayList<Entry> entries = new ArrayList<>();
    private String ownFingerprint, status;
    private boolean busy, enabled;
    private int generation;
    private static final class Entry {
        final String owner; final PqOmemoDevice.Fingerprint key;
        Entry(String owner,PqOmemoDevice.Fingerprint key) { this.owner = owner; this.key = key; }
    }
    public MonogramPqSecurityActivity(int account,long peer) { currentAccount = account; this.peer = peer; }
    @Override protected String getTitle() { return "OMEMO2"; }
    @Override public boolean onFragmentCreate() { super.onFragmentCreate(); load(); return true; }
    @Override public void onFragmentDestroy() { generation++; worker.shutdownNow(); super.onFragmentDestroy(); }
    private void refresh() { if (listView != null) listView.adapter.update(true); }
    private static String grouped(String fingerprint) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < fingerprint.length(); i += 8) { if (i > 0) text.append(i % 32 == 0 ? '\n' : ' '); text.append(fingerprint,i,Math.min(i + 8,fingerprint.length())); }
        return text.toString();
    }
    private void load() {
        final int request = ++generation; busy = true; status = "Refreshing OMEMO2 fingerprints…"; refresh();
        worker.execute(() -> {
            ArrayList<Entry> found = new ArrayList<>(); String own = null, message = null; boolean available = false;
            try {
                MonogramController controller = MonogramController.get(currentAccount); XmppSession session = controller.session();
                available = session.pqAvailable();
                if (available) {
                    XmppPqOmemo pq = session.pq(); own = pq.ownFingerprint();
                    String contact = controller.jid(peer), account = controller.accountJid();
                    String conversation = contact.equals(account) ? null : contact;
                    java.util.Set<String> contacts=new java.util.TreeSet<>();
                    if(peer<0)for(org.jxmpp.jid.BareJid member:session.rooms.members(contact))contacts.add(member.toString());
                    else contacts.add(contact);
                    contacts.add(account);
                    java.util.List<String> failures=new java.util.ArrayList<>();
                    for(String owner:contacts)try {
                        for(PqOmemoDevice.Fingerprint key:pq.discover(owner,conversation))found.add(new Entry(owner,key));
                    }catch(Exception failure){failures.add(owner+": "+com.negzero.monogram.xmpp.XmppRequestException.describe(failure));}
                    if(!failures.isEmpty())throw new java.io.IOException(String.join("\n",failures));
                    message = found.isEmpty() ? "No other OMEMO2 devices found." : "Compare OMEMO2 fingerprints before trusting devices.";
                } else message = "Enable OMEMO2 to publish a post-quantum hybrid identity and discover compatible devices.";
            } catch (Exception error) { message = com.negzero.monogram.xmpp.XmppRequestException.describe(error); }
            final String fingerprint = own, result = message; final boolean ready = available;
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != request) return;
                entries.clear(); entries.addAll(found); ownFingerprint = fingerprint; status = result; enabled = ready; busy = false; refresh();
            });
        });
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader("Post-quantum OMEMO2"));
        items.add(UItem.asShadow("OMEMO2 uses Monocles-compatible post-quantum hybrid keys. Everyone in the conversation needs a compatible client. When blind trust is enabled, it approves new or changed participant keys and authenticated keys from your own devices for that conversation; explicitly blocked devices stay blocked. Without blind trust, compare fingerprints before trusting. OMEMO2 never falls back to another encryption mode."));
        items.add(UItem.asButton(1,enabled ? "Refresh OMEMO2 fingerprints" : "Enable OMEMO2").setEnabled(!busy));
        if (status != null) items.add(UItem.asShadow(status));
        if (ownFingerprint != null) {
            items.add(UItem.asHeader("Your OMEMO2 fingerprint")); items.add(UItem.asShadow(grouped(ownFingerprint)));
            items.add(UItem.asButton(2,R.drawable.msg_copy,"Copy fingerprint"));
        }
        if (!entries.isEmpty()) items.add(UItem.asHeader("Devices"));
        int id = 100;
        for (Entry entry : entries) {
            UItem item = UItem.asButton(id++,entry.owner + " · " + entry.key.device,
                    (entry.key.blocked ? "Blocked" : entry.key.trusted ? "Trusted" : "Needs review") + (entry.key.active ? "" : " · inactive")).setEnabled(!busy);
            item.object = entry; items.add(item);
        }
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if (busy) return;
        if (item.id == 1) {
            if (enabled) { load(); return; }
            busy = true; status = "Enabling OMEMO2…"; refresh(); final int request = ++generation;
            MonogramController.get(currentAccount).enablePq(error -> {
                if (request != generation) return; busy = false;
                if (error != null) { status = com.negzero.monogram.xmpp.XmppRequestException.describe(error); refresh(); }
                else load();
            });
        } else if (item.id == 2 && ownFingerprint != null) AndroidUtilities.addToClipboard(ownFingerprint);
        else if (item.object instanceof Entry) {
            Entry entry = (Entry)item.object;
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Device " + entry.key.device)
                    .setMessage(entry.owner + "\n\n" + grouped(entry.key.fingerprint) + "\n\nCompare this OMEMO2 fingerprint through another trusted channel. Trusting approves both identity keys for future OMEMO2 messages; blocking prevents future encryption to this device.")
                    .setPositiveButton("Trust",(dialog,which) -> decide(entry,true))
                    .setNeutralButton("Block",(dialog,which) -> decide(entry,false)).setNegativeButton("Cancel",null).create());
        }
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
    private void decide(Entry entry,boolean trusted) {
        busy = true; refresh(); final int request = ++generation;
        worker.execute(() -> {
            Exception failed = null;
            try { MonogramController.get(currentAccount).session().pq().device.trust(entry.owner,entry.key.device,entry.key.fingerprint,trusted); }
            catch (Exception error) { failed = error; }
            final Exception error = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != request) return; busy = false;
                if (error != null) { status = com.negzero.monogram.xmpp.XmppRequestException.describe(error); refresh(); }
                else { MonogramController.get(currentAccount).retryEncryptedHistory(peer,false); load(); }
            });
        });
    }
}
