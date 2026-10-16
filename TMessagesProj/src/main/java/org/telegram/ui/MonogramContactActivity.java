// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import com.negzero.monogram.xmpp.XmppContacts;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;

/** Native contact authorization and the account's durable incoming request list. */
public final class MonogramContactActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private final String address;
    private final ArrayList<String> requests=new ArrayList<>();
    private XmppContacts.State state;
    private boolean busy;
    private String status;
    private int generation;
    public MonogramContactActivity(int account,String address) { currentAccount=account; this.address=address; }
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this,NotificationCenter.contactsDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this,NotificationCenter.didUpdateConnectionState);
        reload(); return true;
    }
    @Override public void onFragmentDestroy() {
        generation++;
        NotificationCenter.getInstance(currentAccount).removeObserver(this,NotificationCenter.contactsDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this,NotificationCenter.didUpdateConnectionState);
        super.onFragmentDestroy();
    }
    @Override protected CharSequence getTitle() { return address==null ? "Contact requests" : "Contact authorization"; }
    private void reload() {
        MonogramController controller=MonogramController.get(currentAccount);
        requests.clear(); requests.addAll(controller.pendingContactRequests()); state=null;
        if(address!=null) try { state=controller.contactAuthorization(address); } catch(Exception unavailable) { /* Pending requests remain visible offline. */ }
        if(listView!=null) listView.adapter.update(true);
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        if(address==null) {
            items.add(UItem.asHeader("Incoming contact requests"));
            if(requests.isEmpty()) items.add(UItem.asShadow("No pending contact requests."));
            for(int i=0;i<requests.size();i++) items.add(UItem.asButton(100+i,requests.get(i)));
        } else {
            items.add(UItem.asHeader(address));
            items.add(UItem.asShadow("Some providers require an approved contact relationship before sharing OMEMO device lists. Contact authorization and fingerprint trust are separate: refresh fingerprints after access is granted."));
            if(state==null) items.add(UItem.asShadow("Reconnect to view or change authorization. Saved incoming requests remain available."));
            else {
                items.add(UItem.asShadow("Their presence shared with you: "+(state.seesTheirPresence ? "Allowed" : "Not allowed")+"\nYour presence shared with them: "+(state.sharesOwnPresence ? "Allowed" : "Not allowed")));
                if(state.outgoingPending) {
                    items.add(UItem.asShadow("Request sent. Waiting for this contact to accept."));
                    items.add(UItem.asButton(4,"Cancel outgoing request").setEnabled(!busy));
                } else if(!state.seesTheirPresence) items.add(UItem.asButton(1,"Request contact authorization").accent().setEnabled(!busy));
                if(state.sharesOwnPresence) items.add(UItem.asButton(5,"Stop sharing your presence").setEnabled(!busy));
            }
            if(requests.contains(address)) {
                items.add(UItem.asHeader("Incoming request"));
                items.add(UItem.asShadow("This contact wants to see your online status. Accepting also requests their presence if you do not already have access."));
                items.add(UItem.asButton(2,"Accept and request back").accent().setEnabled(!busy && state!=null));
                items.add(UItem.asButton(3,"Decline request").setEnabled(!busy && state!=null));
            }
            if(busy) items.add(UItem.asShadow("Updating contact authorization…"));
            if(status!=null) items.add(UItem.asShadow(status));
        }
        items.add(UItem.asButton(10,"XMPP connection"));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==10) { presentFragment(new MonogramConnectionActivity(currentAccount)); return; }
        if(address==null) {
            if(item.id>=100 && item.id-100<requests.size()) presentFragment(new MonogramContactActivity(currentAccount,requests.get(item.id-100)));
            return;
        }
        if(busy || state==null) return;
        switch(item.id) {
            case 1: act(XmppContacts.Action.REQUEST); break;
            case 2: act(XmppContacts.Action.ACCEPT); break;
            case 3: act(XmppContacts.Action.DENY); break;
            case 4: act(XmppContacts.Action.CANCEL); break;
            case 5: showDialog(new AlertDialog.Builder(getContext()).setTitle("Stop sharing presence?")
                .setMessage("This contact will no longer receive your online status. Their server may also restrict access to presence-protected OMEMO keys. Your messages and trust decisions remain saved.")
                .setPositiveButton("Stop sharing",(dialog,which) -> act(XmppContacts.Action.REVOKE)).setNegativeButton("Cancel",null).create()); break;
        }
    }
    private void act(XmppContacts.Action action) {
        busy=true; status=null; reload(); final int loading=generation;
        MonogramController.get(currentAccount).contactAuthorization(address,action,error -> {
            if(loading!=generation) return;
            busy=false; status=error==null ? "Request sent to the XMPP server. Authorization status updates when the server or contact replies." : "Could not complete the request: "+error;
            reload();
        });
    }
    @Override public void didReceivedNotification(int id,int account,Object... args) { reload(); }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
