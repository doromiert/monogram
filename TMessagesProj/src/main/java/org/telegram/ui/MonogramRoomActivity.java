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
public final class MonogramRoomActivity extends UniversalFragment {
    private final long dialog;
    private EditTextCell address,nickname,password;
    private boolean busy;
    private String status;
    public MonogramRoomActivity(int account,long dialog) { currentAccount=account; this.dialog=dialog; }
    @Override protected CharSequence getTitle() { return dialog==0 ? "Join a room" : "Room settings"; }
    @Override public View createView(Context context) {
        MonogramController controller=MonogramController.get(currentAccount);
        address=new EditTextCell(context,"Room address",false);
        address.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        nickname=new EditTextCell(context,"Your room nickname",false);
        nickname.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        String own=controller.accountJid(); int at=own.indexOf('@');
        nickname.editText.setText(dialog==0 ? (at>0 ? own.substring(0,at) : "Monogram") : controller.roomNickname(dialog));
        password=new EditTextCell(context,"Room password (if required)",false);
        password.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(dialog!=0) address.editText.setText(controller.jid(dialog));
        return super.createView(context);
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        if(dialog==0) {
            items.add(UItem.asHeader("Room")); items.add(UItem.asCustom(address));
            items.add(UItem.asButton(2,R.drawable.msg_search,"Browse public rooms").setEnabled(!busy));
            items.add(UItem.asShadow("Enter a full room address such as friends@conference.example.org."));
        } else { items.add(UItem.asHeader(MonogramController.get(currentAccount).jid(dialog))); }
        items.add(UItem.asCustom(nickname));
        items.add(UItem.asCustom(password));
        if(dialog!=0) items.add(UItem.asShadow("Leave the password blank to reuse the saved room password."));
        items.add(UItem.asButton(1,busy ? "Connecting…" : dialog==0 ? "Join room" : "Rejoin room").accent().setEnabled(!busy));
        items.add(UItem.asShadow("OMEMO is enabled by default. Encrypted sending requires a members-only room that reveals member addresses, and trusted devices for every member. It never falls back to plain messages."));
        if(dialog!=0) {
            items.add(UItem.asButton(3,"Change nickname").setEnabled(!busy));
            items.add(UItem.asButton(4,R.drawable.msg_secret,"Conversation security"));
            items.add(UItem.asButton(5,"Leave room").setEnabled(!busy));
        }
        if(status!=null) items.add(UItem.asShadow(status));
    }
    private void refresh() { if(listView!=null) listView.adapter.update(true); }
    private void join() {
        if(busy) return;
        String room=address.getText().toString().trim(),nick=nickname.getText().toString().trim();
        if(room.isEmpty() || nick.isEmpty()) { status="Enter the room address and a nickname."; refresh(); return; }
        busy=true; status=null; refresh();
        MonogramController controller=MonogramController.get(currentAccount);
        controller.openRoom(room,nick,dialog!=0 && password.getText().toString().isEmpty() ? null : password.getText().toString(),(value,error) -> {
            busy=false; if(getParentActivity()==null) return;
            if(error!=null) { status="Could not join the room. Check its address, password, membership and nickname, then retry."; refresh(); return; }
            if(dialog!=0) { status="Joined the room."; refresh(); return; }
            TLRPC.Chat chat=(TLRPC.Chat)value;
            Bundle args=new Bundle(); args.putLong("chat_id",chat.id);
            ChatActivity activity=new ChatActivity(args); activity.setCurrentAccount(currentAccount); presentFragment(activity,true);
        });
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
        if(item.id==1 && !busy) join();
        else if(item.id==2 && !busy) browse();
        else if(item.id==4) presentFragment(new MonogramSecurityActivity(currentAccount,dialog));
        else if(item.id==3 && !busy) {
            String nick=nickname.getText().toString().trim(); if(nick.isEmpty()) return;
            busy=true; refresh(); controller.changeRoomNickname(dialog,nick,(value,error) -> { busy=false; status=error==null ? "Nickname changed." : "Could not change nickname. Check that it is available and that the room is joined."; refresh(); });
        } else if(item.id==5 && !busy) showDialog(new AlertDialog.Builder(getContext()).setTitle("Leave room?")
                .setMessage("Cached messages stay on this device. Monogram will stop automatically rejoining this room.")
                .setPositiveButton("Leave",(d,which) -> { busy=true; refresh(); controller.leaveRoom(dialog,(value,error) -> { busy=false; status=error==null ? "Left the room." : "Could not leave the room. Check your connection and retry."; refresh(); }); })
                .setNegativeButton("Cancel",null).create());
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
