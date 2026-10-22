// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import com.negzero.monogram.xmpp.XmppRooms;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.*;

/** Current room occupants and server-authorized XEP-0045 actions. */
public final class MonogramRoomMembersActivity extends MonogramSettingsActivity implements NotificationCenter.NotificationCenterDelegate {
    private final long room;
    private XmppRooms.State state;
    private boolean busy, destroyed;
    private String status;
    public MonogramRoomMembersActivity(int account,long room) { currentAccount=account; this.room=room; }
    @Override protected CharSequence getTitle() { return "Room participants"; }
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this,NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).addObserver(this,NotificationCenter.didUpdateConnectionState);
        reload(); return true;
    }
    @Override public void onFragmentDestroy() {
        destroyed=true;
        NotificationCenter.getInstance(currentAccount).removeObserver(this,NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).removeObserver(this,NotificationCenter.didUpdateConnectionState);
        super.onFragmentDestroy();
    }
    private void reload() {
        state=MonogramController.get(currentAccount).roomState(room);
        if(listView!=null) listView.adapter.update(true);
    }
    private boolean moderator() { return state!=null && ("moderator".equals(state.role) || administrator()); }
    private boolean administrator() { return state!=null && ("owner".equals(state.affiliation) || "admin".equals(state.affiliation)); }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader(MonogramController.get(currentAccount).jid(room)));
        if(state==null || !state.joined) {
            items.add(UItem.asSettingsCell(0,"Participants","Unavailable").setEnabled(false));
            items.add(UItem.asShadow("Join the room to see its current participants and change room settings."));
        } else {
            items.add(UItem.asButton(1,"Invite someone").setEnabled(!busy));
            items.add(UItem.asButton(2,"Change room topic").setEnabled(!busy));
            if(administrator()) {
                items.add(UItem.asButton(3,"Manage memberships and bans").setEnabled(!busy));
                items.add(UItem.asButton(4,"Add member by XMPP address").setEnabled(!busy));
            }
            if(!state.subject.isEmpty()) items.add(UItem.asShadow(state.subject));
            items.add(UItem.asHeader("Participants ("+state.occupants.size()+")"));
            for(int i=0;i<state.occupants.size();i++) {
                XmppRooms.Occupant occupant=state.occupants.get(i);
                TLRPC.User user=MonogramController.get(currentAccount).roomOccupant(occupant.address);
                UItem item=user==null ? UItem.asButton(100+i,occupant.nickname) : UItem.asProfileCell(user).setId(100+i);
                item.subtext=occupant.nickname+" · "+occupant.role+("none".equals(occupant.affiliation) || occupant.affiliation.isEmpty() ? "" : " · "+occupant.affiliation);
                items.add(item.setEnabled(!busy));
            }
            items.add(UItem.asShadow("Tap a participant for their profile and available room actions. Addresses are shown only when disclosed by the room. Every change is checked by the server."));
        }
        if(busy) items.add(UItem.asShadow("Updating room…"));
        if(status!=null) items.add(UItem.asShadow(status));
    }
    private void completed(String error) {
        if(destroyed) return;
        busy=false; status=error==null ? "Request sent to the room server." : "Could not complete the request: "+error; reload();
    }
    private void text(String title,String hint,String initial,boolean address,Consumer<String> action) {
        EditTextCell input=new EditTextCell(getContext(),hint,false);
        input.editText.setInputType(InputType.TYPE_CLASS_TEXT | (address ? InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.editText.setText(initial);
        input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(address ? 3071 : 4096)});
        showDialog(new AlertDialog.Builder(getContext()).setTitle(title).setView(input)
                .setPositiveButton("Send",(d,which) -> {
                    if(destroyed || busy) return;
                    String value=input.getText().toString().trim();
                    if(address && value.isEmpty()) { status="Enter a XMPP address."; reload(); return; }
                    busy=true; status=null; reload(); action.accept(value);
                }).setNegativeButton("Cancel",null).create());
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(busy || state==null || !state.joined) return;
        MonogramController controller=MonogramController.get(currentAccount);
        if(item.id==1) text("Invite to room","XMPP address","",true,value -> controller.inviteToRoom(room,value,this::completed));
        else if(item.id==2) text("Room topic","Topic",state.subject,false,value -> controller.changeRoomSubject(room,value,this::completed));
        else if(item.id==3 && administrator()) memberships();
        else if(item.id==4 && administrator()) text("Add member","XMPP address","",true,value -> membershipChange(value,"member"));
        else if(item.id>=100 && item.id-100<state.occupants.size()) participant(state.occupants.get(item.id-100));
    }
    private void memberships() {
        busy=true; status=null; reload();
        MonogramController.get(currentAccount).roomAffiliations(room,(members,error) -> {
            if(destroyed) return;
            busy=false; status=error; reload();
            if(error!=null || members==null) return;
            ArrayList<String> addresses=new ArrayList<>(members.keySet());
            CharSequence[] labels=new CharSequence[addresses.size()];
            for(int i=0;i<addresses.size();i++) labels[i]=addresses.get(i)+"\n"+members.get(addresses.get(i));
            if(labels.length==0) { status="No memberships were returned by the server."; reload(); return; }
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Memberships and bans").setItems(labels,(d,which) -> {
                String address=addresses.get(which),affiliation=members.get(address);
                if("owner".equals(affiliation) || address.equals(MonogramController.get(currentAccount).accountJid())) return;
                ArrayList<String> choices=new ArrayList<>(),values=new ArrayList<>();
                if(!"member".equals(affiliation)) { choices.add("Make member"); values.add("member"); }
                choices.add("outcast".equals(affiliation) ? "Remove ban" : "Remove membership"); values.add("none");
                if(!"outcast".equals(affiliation)) { choices.add("Ban from room"); values.add("outcast"); }
                if(state!=null && "owner".equals(state.affiliation) && !"admin".equals(affiliation)) { choices.add("Make administrator"); values.add("admin"); }
                showDialog(new AlertDialog.Builder(getContext()).setTitle(address).setItems(choices.toArray(new CharSequence[0]),(choose,index) -> {
                    showDialog(new AlertDialog.Builder(getContext()).setTitle(choices.get(index)+"?").setMessage(address)
                            .setPositiveButton("Confirm",(confirm,button) -> {
                                if(destroyed || busy) return;
                                busy=true; status=null; reload(); membershipChange(address,values.get(index));
                            }).setNegativeButton("Cancel",null).create());
                }).setNegativeButton("Cancel",null).create());
            }).setNegativeButton("Close",null).create());
        });
    }
    private void membershipChange(String address,String affiliation) {
        MonogramController.get(currentAccount).roomAffiliation(room,address,affiliation,this::completed);
    }
    private void participant(XmppRooms.Occupant occupant) {
        ArrayList<String> labels=new ArrayList<>(); ArrayList<XmppRooms.MemberAction> actions=new ArrayList<>();
        TLRPC.User user=MonogramController.get(currentAccount).roomOccupant(occupant.address);
        if(user!=null) { labels.add("View profile"); actions.add(null); }
        boolean own=occupant.nickname.equals(state.nickname);
        if(!own && moderator()) {
            labels.add("Remove from room"); actions.add(XmppRooms.MemberAction.KICK);
            labels.add("visitor".equals(occupant.role) ? "Allow speaking" : "Remove speaking permission");
            actions.add("visitor".equals(occupant.role) ? XmppRooms.MemberAction.GRANT_VOICE : XmppRooms.MemberAction.REVOKE_VOICE);
        }
        if(!own && administrator()) {
            labels.add("moderator".equals(occupant.role) ? "Remove moderator role" : "Make moderator");
            actions.add("moderator".equals(occupant.role) ? XmppRooms.MemberAction.REVOKE_MODERATOR : XmppRooms.MemberAction.GRANT_MODERATOR);
            if(occupant.realJid!=null && !"owner".equals(occupant.affiliation) && !"admin".equals(occupant.affiliation)) {
                labels.add("member".equals(occupant.affiliation) ? "Remove membership" : "Add as member");
                actions.add("member".equals(occupant.affiliation) ? XmppRooms.MemberAction.REVOKE_MEMBERSHIP : XmppRooms.MemberAction.GRANT_MEMBERSHIP);
            }
        }
        if(labels.isEmpty()) return;
        showDialog(new AlertDialog.Builder(getContext()).setTitle(occupant.nickname).setItems(labels.toArray(new CharSequence[0]),(d,which) -> {
            XmppRooms.MemberAction action=actions.get(which);
            if(action==null) {
                Bundle args=new Bundle(); args.putLong("user_id",user.id);
                ProfileActivity profile=new ProfileActivity(args); profile.setCurrentAccount(currentAccount); presentFragment(profile); return;
            }
            showDialog(new AlertDialog.Builder(getContext()).setTitle(labels.get(which)+"?")
                    .setMessage("Participant: "+occupant.nickname+"\nRoom: "+MonogramController.get(currentAccount).jid(room))
                    .setPositiveButton("Confirm",(confirm,index) -> {
                        if(destroyed || busy) return;
                        busy=true; status=null; reload();
                        MonogramController.get(currentAccount).roomMemberAction(room,occupant,action,this::completed);
                    }).setNegativeButton("Cancel",null).create());
        }).setNegativeButton("Cancel",null).create());
    }
    @Override public void didReceivedNotification(int id,int account,Object... args) {
        if(id==NotificationCenter.didUpdateConnectionState || args.length>0 && args[0] instanceof Integer && (((Integer)args[0]) & (MessagesController.UPDATE_MASK_CHAT_MEMBERS | MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_NAME))!=0) reload();
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
