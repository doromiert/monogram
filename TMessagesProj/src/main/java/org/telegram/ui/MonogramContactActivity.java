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
public final class MonogramContactActivity extends MonogramSettingsActivity implements NotificationCenter.NotificationCenterDelegate {
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
    @Override protected CharSequence getTitle() { return address==null ? "Requests and invitations" : "Control access"; }
    private void reload() {
        MonogramController controller=MonogramController.get(currentAccount);
        requests.clear(); requests.addAll(controller.pendingContactRequests()); state=null;
        if(address!=null) try { state=controller.contactAuthorization(address); } catch(Exception unavailable) { /* Pending requests remain visible offline. */ }
        if(listView!=null) listView.adapter.update(true);
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        if(address==null) {
            items.add(UItem.asHeader("Incoming contact requests"));
            int roomInvites=MonogramController.get(currentAccount).pendingRoomInvitations().size();
            if(roomInvites>0) items.add(UItem.asButton(11,"Room invitations ("+roomInvites+")"));
            if(requests.isEmpty()) items.add(UItem.asSettingsCell(0,"Pending requests","None").setEnabled(false));
            for(int i=0;i<requests.size();i++) items.add(UItem.asButton(100+i,requests.get(i)));
        } else {
            boolean incoming=requests.contains(address) || state!=null && state.incomingPending;
            items.add(UItem.asShadow(address+"\n\nControl access to your online status and profile data shared with contacts. Message encryption and fingerprint trust are managed separately."));
            items.add(UItem.asHeader("Their access to you"));
            if(state==null) items.add(UItem.asSettingsCell(0,"Access", "Unavailable").setEnabled(false));
            else {
                items.add(UItem.asSettingsCell(0,"Your access list",state.sharesOwnPresence ? "Allowed" : state.preApproved ? "Pre-approved" : state.allowRequests ? "Waiting for request" : "Not allowed").setEnabled(false));
                if(state.sharesOwnPresence || state.preApproved || state.allowRequests) items.add(UItem.asButton(5,"Revoke access").red().setEnabled(!busy));
                else items.add(UItem.asButton(6,"Allow access").accent().setEnabled(!busy));
                if(incoming && !state.sharesOwnPresence && (state.preApproved || state.allowRequests)) items.add(UItem.asButton(6,"Allow access").accent().setEnabled(!busy));
                if(state.allowRequests) items.add(UItem.asShadow("Monogram will approve this contact’s requests while signed in. Once approved, your server stores their access. Revoke access removes this permission and the server subscription."));
                else if(state.preApproved && !state.sharesOwnPresence) items.add(UItem.asShadow("Your server reports this contact as pre-approved. Access becomes active when their client requests it."));
            }
            items.add(UItem.asHeader("Your access to them"));
            if(state!=null) {
                items.add(UItem.asSettingsCell(0,"Their presence",state.seesTheirPresence ? "Shared with you" : state.outgoingPending ? "Request pending" : "Not shared").setEnabled(false));
                if(state.outgoingPending) items.add(UItem.asButton(4,"Cancel contact request").setEnabled(!busy));
                else if(!state.seesTheirPresence) items.add(UItem.asButton(1,"Contact request").accent().setEnabled(!busy));
            } else items.add(UItem.asShadow("Reconnect to view or change access. Saved incoming requests remain available."));
            if(incoming) {
                items.add(UItem.asHeader("Incoming request"));
                items.add(UItem.asButton(2,"Accept and request back").accent().setEnabled(!busy && state!=null));
                items.add(UItem.asButton(3,"Decline request").setEnabled(!busy && state!=null));
                items.add(UItem.asShadow("Allow access approves only their request. Accept and request back also asks them to share their presence with you."));
            }
            if(busy) items.add(UItem.asShadow("Updating contact access…"));
            if(status!=null) items.add(UItem.asShadow(status));
        }
        if(address==null) items.add(UItem.asShadow(null));
        items.add(UItem.asButton(10,"XMPP connection"));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==11 && address==null) { presentFragment(new MonogramRoomActivity(currentAccount,0)); return; }
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
            case 6: act(XmppContacts.Action.ALLOW);break;
            case 5: showDialog(new AlertDialog.Builder(getContext()).setTitle("Revoke access?")
                .setMessage("Remove this contact’s access to your online status and contact-only profile data, and stop automatically approving their requests?")
                .setPositiveButton("Revoke access",(dialog,which) -> act(XmppContacts.Action.REVOKE)).setNegativeButton("Cancel",null).create()); break;
        }
    }
    private void act(XmppContacts.Action action) {
        busy=true; status=null; reload(); final int loading=generation;
        MonogramController.get(currentAccount).contactAuthorization(address,action,error -> {
            if(loading!=generation) return;
            busy=false; status=error!=null ? "Could not complete the request: "+error : action==XmppContacts.Action.ALLOW
                    ? "Access preference saved. Monogram will approve this contact’s requests while signed in."
                    : "Request sent to the XMPP server. Access status updates when the server or contact replies.";
            reload();
        });
    }
    @Override public void didReceivedNotification(int id,int account,Object... args) { reload(); }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
