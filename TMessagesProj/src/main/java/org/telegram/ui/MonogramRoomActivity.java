// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.*;

/** Native room entry and room-specific controls; conversations use ChatActivity. */
public final class MonogramRoomActivity extends MonogramSettingsActivity implements org.telegram.messenger.NotificationCenter.NotificationCenterDelegate {
    private final long dialog;
    private EditTextCell address,nickname,password,title;
    private final boolean creating;
    private final String invitedRoom;
    private java.util.List<String> invitations=new ArrayList<>();
    private boolean busy;
    private String status;
    public MonogramRoomActivity(int account,long dialog) { this(account,dialog,false,null); }
    private MonogramRoomActivity(int account,long dialog,boolean creating,String invitedRoom) { currentAccount=account; this.dialog=dialog; this.creating=creating; this.invitedRoom=invitedRoom; }
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        org.telegram.messenger.NotificationCenter.getInstance(currentAccount).addObserver(this,org.telegram.messenger.NotificationCenter.updateInterfaces);
        return true;
    }
    @Override public void onFragmentDestroy() {
        org.telegram.messenger.NotificationCenter.getInstance(currentAccount).removeObserver(this,org.telegram.messenger.NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }
    @Override public void didReceivedNotification(int id,int account,Object... args) {
        if(args.length>0 && args[0] instanceof Integer && (((Integer)args[0]) & org.telegram.messenger.MessagesController.UPDATE_MASK_CHAT_MEMBERS)!=0) refresh();
    }
    @Override protected CharSequence getTitle() { return creating ? "Create a private room" : dialog==0 ? "Join a room" : "Room settings"; }
    @Override public View createView(Context context) {
        MonogramController controller=MonogramController.get(currentAccount);
        address=new EditTextCell(context,"Room address",false);
        title=new EditTextCell(context,"Room name",false);
        title.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1024)});
        address.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3071)});
        address.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        nickname=new EditTextCell(context,"Your room nickname",false);
        nickname.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        String own=controller.accountJid(); int at=own.indexOf('@');
        nickname.editText.setText(dialog==0 ? (at>0 ? own.substring(0,at) : "Monogram") : controller.roomNickname(dialog));
        password=new EditTextCell(context,"Room password",false);
        password.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(dialog!=0) address.editText.setText(controller.jid(dialog));
        else if(invitedRoom!=null) {
            address.editText.setText(invitedRoom); password.editText.setText(controller.roomInvitationPassword(invitedRoom));
            status=controller.roomInvitationDescription(invitedRoom);
        }
        return super.createView(context);
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        if(dialog==0) {
            items.add(UItem.asHeader("Room")); items.add(UItem.asCustom(address));
            if(creating) {
                items.add(UItem.asCustom(title));
                items.add(UItem.asShadow("Creates a members-only room with member addresses visible to other members, as required for OMEMO. It is hidden from the public directory when supported. The address must be unused."));
            } else {
                items.add(UItem.asButton(2,R.drawable.msg_search,"Browse public rooms").setEnabled(!busy));
                items.add(UItem.asButton(7,"Create a private room"));
            }
            items.add(UItem.asShadow("Enter a full room address such as friends@conference.example.org."));
        } else { items.add(UItem.asHeader(MonogramController.get(currentAccount).jid(dialog))); }
        if(dialog==0) items.add(UItem.asHeader("Your room identity"));
        items.add(UItem.asCustom(nickname));
        if(!creating) items.add(UItem.asCustom(password));
        if(!creating) items.add(UItem.asShadow("Passwords entered here stay on this device. Saved room addresses, nicknames and autojoin choices sync privately with supported servers."));
        if(dialog!=0) items.add(UItem.asShadow("Leave the password blank to reuse the saved room password."));
        items.add(UItem.asButton(1,busy ? "Connecting…" : creating ? "Create room" : dialog==0 ? "Join room" : "Rejoin room").accent().setEnabled(!busy));
        items.add(UItem.asShadow("OMEMO is enabled by default. Encrypted sending requires a members-only room that reveals member addresses, and trusted devices for every member. It never falls back to plain messages."));
        if(dialog!=0) {
            String inviteError=MonogramController.get(currentAccount).lastRoomInviteError(dialog);
            if(inviteError!=null) items.add(UItem.asShadow(inviteError));
            items.add(UItem.asButton(8,"Participants and room actions"));
            items.add(UItem.asButton(3,"Change nickname").setEnabled(!busy));
            items.add(UItem.asButton(4,R.drawable.msg_secret,"Conversation security"));
            items.add(UItem.asButton(5,"Leave room").setEnabled(!busy));

        }
        items.add(UItem.asButton(6,R.drawable.msg_retry,"Sync room bookmarks").setEnabled(!busy));
        items.add(UItem.asShadow(MonogramController.get(currentAccount).bookmarkStatus()));
        if(dialog==0 && !creating && invitedRoom==null) {
            invitations=MonogramController.get(currentAccount).pendingRoomInvitations();
            if(!invitations.isEmpty()) items.add(UItem.asHeader("Room invitations"));
            for(int i=0;i<invitations.size();i++) items.add(UItem.asButton(100+i,invitations.get(i)));
            if(!invitations.isEmpty()) items.add(UItem.asShadow("Tap to review an invitation. Long-press to dismiss it. Joining never grants fingerprint trust."));
        }
        if(status!=null) items.add(UItem.asShadow(status));
    }
    @Override public void onResume() { super.onResume();refresh(); }
    private void refresh() { if(listView!=null) listView.adapter.update(true); }
    private void join() {
        if(busy) return;
        String room=address.getText().toString().trim(),nick=nickname.getText().toString().trim();
        if(room.isEmpty() || nick.isEmpty()) { status="Enter the room address and a nickname."; refresh(); return; }
        busy=true; status=null; refresh();
        MonogramController controller=MonogramController.get(currentAccount);
        org.telegram.tgnet.RequestDelegate completed=(value,error) -> {
            busy=false; if(getParentActivity()==null) return;
            if(error!=null) { status=(creating ? "Could not finish creating the room: " : "Could not join the room: ")+error.text; refresh(); return; }
            if(dialog!=0) { status="Joined the room."; refresh(); return; }
            TLRPC.Chat chat=(TLRPC.Chat)value;
            Bundle args=new Bundle(); args.putLong("chat_id",chat.id);
            ChatActivity activity=new ChatActivity(args); activity.setCurrentAccount(currentAccount); presentFragment(activity,true);
        };
        if(creating) controller.createRoom(room,nick,title.getText().toString().trim(),completed);
        else controller.openRoom(room,nick,dialog!=0 && password.getText().toString().isEmpty() ? null : password.getText().toString(),completed);
    }
    private void browse() {
        busy=true; status="Finding public rooms…"; refresh();
        MonogramController.get(currentAccount).discoverRooms((rooms,error) -> {
            busy=false; if(getParentActivity()==null) return;
            if(error!=null || rooms.isEmpty()) { status=error==null ? "No public rooms were listed. You can still enter a room address." : "Could not load the public room directory. You can enter a room address directly."; refresh(); return; }
            status=null; refresh();
            CharSequence[] labels=new CharSequence[rooms.size()];
            for(int i=0;i<rooms.size();i++) labels[i]=rooms.get(i).getName()+"\n"+rooms.get(i).getJid();
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Public rooms").setItems(labels,(d,which) -> address.editText.setText(rooms.get(which).getJid().toString())).setNegativeButton("Cancel",null).create());
        });
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        MonogramController controller=MonogramController.get(currentAccount);
        if(item.id>=100 && item.id-100<invitations.size()) { presentFragment(new MonogramRoomActivity(currentAccount,0,false,invitations.get(item.id-100))); return; }
        if(item.id==7) { presentFragment(new MonogramRoomActivity(currentAccount,0,true,null)); return; }
        if(item.id==8 && dialog!=0) { presentFragment(new MonogramRoomMembersActivity(currentAccount,dialog)); return; }
        if(item.id==1 && !busy) join();
        else if(item.id==2 && !busy) browse();
        else if(item.id==6 && !busy) {
            busy=true; refresh(); controller.syncRoomBookmarks((value,error) -> { busy=false; status=controller.bookmarkStatus(); refresh(); });
        }
        else if(item.id==4) presentFragment(new MonogramSecurityActivity(currentAccount,dialog));
        else if(item.id==3 && !busy) {
            String nick=nickname.getText().toString().trim(); if(nick.isEmpty()) return;
            busy=true; refresh(); controller.changeRoomNickname(dialog,nick,(value,error) -> { busy=false; status=error==null ? "Nickname changed." : "Could not change nickname. Check that it is available and that the room is joined."; refresh(); });
        } else if(item.id==5 && !busy) showDialog(new AlertDialog.Builder(getContext()).setTitle("Leave room?")
                .setMessage("Cached messages stay on this device. Monogram will stop automatically rejoining this room. On supported servers, this choice also syncs to your other clients.")
                .setPositiveButton("Leave",(d,which) -> { busy=true; refresh(); controller.leaveRoom(dialog,(value,error) -> { busy=false; status=error==null ? "Left the room." : "Could not leave the room. Check your connection and retry."; refresh(); }); })
                .setNegativeButton("Cancel",null).create());
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) {
        if(item.id<100 || item.id-100>=invitations.size()) return false;
        String room=invitations.get(item.id-100);
        showDialog(new AlertDialog.Builder(getContext()).setTitle("Dismiss invitation?").setMessage(room)
                .setPositiveButton("Dismiss",(d,which) -> MonogramController.get(currentAccount).dismissRoomInvitation(room)).setNegativeButton("Cancel",null).create());
        return true;
    }
}
