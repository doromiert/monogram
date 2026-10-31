// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import java.util.*;
import com.negzero.monogram.xmpp.XmppMix;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.*;

/** MIX membership controls; messages use the same native ChatActivity as other conversations. */
public final class MonogramMixChannelActivity extends MonogramSettingsActivity {
    private final long dialog;
    private final String suggested;
    private EditTextCell address,nickname;
    private boolean create,busy,destroyed;
    private String status;
    private XmppMix.Channel channel;
    public MonogramMixChannelActivity(int account,long dialog,String suggested){currentAccount=account;this.dialog=dialog;this.suggested=suggested;}
    @Override protected CharSequence getTitle(){return dialog==0?"MIX channel":"Channel settings";}
    @Override public View createView(Context context){
        MonogramController controller=MonogramController.get(currentAccount);
        address=new EditTextCell(context,"Channel address",false);address.editText.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        address.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3071)});
        address.editText.setText(dialog==0?suggested:controller.jid(dialog));
        nickname=new EditTextCell(context,"Your nickname",false);nickname.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1024)});
        String own=controller.accountJid();nickname.editText.setText(dialog==0?own.substring(0,own.indexOf('@')):controller.roomNickname(dialog));
        return super.createView(context);
    }
    @Override public void onResume(){super.onResume();if(dialog!=0 && channel==null && !busy)refreshChannel();}
    private void update(){if(listView!=null)listView.adapter.update(true);}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        if(dialog==0){
            items.add(UItem.asHeader("Channel"));items.add(UItem.asCustom(address));items.add(UItem.asCustom(nickname));
            items.add(UItem.asCheck(1,"Create a new channel").setChecked(create).setEnabled(!busy));
            items.add(UItem.asButton(2,R.drawable.msg_search,"Browse MIX channels").setEnabled(!busy));
            items.add(UItem.asButton(3,busy?"Connecting…":create?"Create and join":"Join channel").accent().setEnabled(!busy));
            items.add(UItem.asShadow("Use a full address, such as general@mix.example.org. Your account server must support MIX-PAM. Joining applies to all your clients and persists while you are offline."));
            items.add(UItem.asShadow("Monogram keeps your group encryption setting. It checks the complete participant list before encrypted sending; hidden participant addresses cannot be used for OMEMO."));
        }else{
            items.add(UItem.asHeader(channel!=null && channel.title!=null?channel.title:MonogramController.get(currentAccount).jid(dialog)));
            if(channel!=null && channel.description!=null)items.add(UItem.asShadow(channel.description));
            items.add(UItem.asButton(4,R.drawable.msg_retry,busy?"Loading…":"Refresh channel").setEnabled(!busy));
            items.add(UItem.asCustom(nickname));items.add(UItem.asButton(5,"Change nickname").setEnabled(!busy));
            items.add(UItem.asButton(6,R.drawable.msg_secret,"Conversation security"));
            items.add(UItem.asButton(7,"Leave channel").red().setEnabled(!busy));
            items.add(UItem.asHeader("Participants"));
            if(channel==null)items.add(UItem.asShadow("Refresh to load participants."));
            else for(int i=0;i<channel.participants.size();i++){
                XmppMix.Participant participant=channel.participants.get(i);
                items.add(UItem.asButton(100+i,participant.nickname,participant.jid==null?"Address hidden by channel":participant.jid));
            }
        }
        if(status!=null)items.add(UItem.asShadow(status));
    }
    private void join(){
        busy=true;status=null;update();MonogramController.get(currentAccount).openMixChannel(address.getText().toString().trim(),nickname.getText().toString().trim(),create,(value,error)->{
            if(destroyed)return;busy=false;if(error!=null){status=error.text;update();return;}
            TLRPC.Chat chat=(TLRPC.Chat)value;Bundle args=new Bundle();args.putLong("chat_id",chat.id);ChatActivity activity=new ChatActivity(args);activity.setCurrentAccount(currentAccount);presentFragment(activity,true);
        });
    }
    private void refreshChannel(){busy=true;status=null;update();MonogramController.get(currentAccount).mixRequest(mix->mix.refresh(MonogramController.get(currentAccount).jid(dialog)),(result,error)->{if(destroyed)return;busy=false;channel=result;status=error;update();});}
    private void browse(){
        EditTextCell service=new EditTextCell(getContext(),"MIX service, such as mix.example.org",false);
        showDialog(new AlertDialog.Builder(getContext()).setTitle("Browse channels").setView(service).setPositiveButton("Browse",(d,w)->{
            busy=true;status=null;update();MonogramController.get(currentAccount).mixRequest(mix->mix.discover(service.getText().toString().trim()),(found,error)->{
                if(destroyed)return;busy=false;status=error;update();if(error!=null)return;
                if(found.isEmpty()){status="This service lists no channels available to your account.";update();return;}
                CharSequence[] labels=new CharSequence[found.size()];for(int i=0;i<labels.length;i++)labels[i]=(found.get(i).getName()==null?"":found.get(i).getName()+"\n")+found.get(i).getEntityID();
                showDialog(new AlertDialog.Builder(getContext()).setTitle("MIX channels").setItems(labels,(p,index)->address.editText.setText(found.get(index).getEntityID().toString())).setNegativeButton("Cancel",null).create());
            });
        }).setNegativeButton("Cancel",null).create());
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        if(busy && item.id!=6)return;MonogramController controller=MonogramController.get(currentAccount);
        if(item.id==1){create=!create;update();}else if(item.id==2)browse();else if(item.id==3)join();else if(item.id==4)refreshChannel();
        else if(item.id==5){busy=true;update();controller.mixRequest(mix->{mix.nickname(controller.jid(dialog),nickname.getText().toString().trim());return true;},(value,error)->{if(destroyed)return;busy=false;status=error;update();if(error==null)refreshChannel();});}
        else if(item.id==6)presentFragment(new MonogramSecurityActivity(currentAccount,dialog));
        else if(item.id==7)showDialog(new AlertDialog.Builder(getContext()).setTitle("Leave MIX channel?").setMessage("This leaves the channel on all your devices. Cached messages stay on this device.").setPositiveButton("Leave",(d,w)->{
            busy=true;update();controller.leaveRoom(dialog,(value,error)->{if(destroyed)return;busy=false;if(error==null)finishFragment();else{status=error.text;update();}});
        }).setNegativeButton("Cancel",null).create());
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
    @Override public void onFragmentDestroy(){destroyed=true;super.onFragmentDestroy();}
}
