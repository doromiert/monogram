// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import java.util.*;
import com.negzero.monogram.xmpp.XmppSpaces;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.*;

/** XEP-0503 directory: spaces may contain both MIX/MUC conversations and nested spaces. */
public final class MonogramSpacesActivity extends MonogramSettingsActivity implements NotificationCenter.NotificationCenterDelegate {
    private XmppSpaces.Reference space;
    private final Set<String> ancestors;
    private XmppSpaces.Contents contents;
    private EditTextCell service,node,name;
    private List<XmppSpaces.Reference> saved=Collections.emptyList();
    private List<TLRPC.Chat> channels=Collections.emptyList();
    private boolean busy,destroyed,publicSpace;
    private String status;
    public MonogramSpacesActivity(int account){this(account,null,Collections.emptySet());}
    private MonogramSpacesActivity(int account,XmppSpaces.Reference space,Set<String> ancestors){currentAccount=account;this.space=space;this.ancestors=new HashSet<>(ancestors);if(space!=null)this.ancestors.add(space.key());}
    @Override protected CharSequence getTitle(){return space==null?"Spaces and channels":space.name;}
    @Override public boolean onFragmentCreate(){super.onFragmentCreate();NotificationCenter.getInstance(currentAccount).addObserver(this,NotificationCenter.updateInterfaces);return true;}
    @Override public View createView(Context context){
        service=new EditTextCell(context,"Pubsub service",false);node=new EditTextCell(context,"Space node",false);name=new EditTextCell(context,"Name for a new space",false);
        for(EditTextCell input:new EditTextCell[]{service,node,name}){input.editText.setSingleLine(true);input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1024)});}
        service.editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        return super.createView(context);
    }
    @Override public void onResume(){super.onResume();update();if(space!=null && contents==null && !busy)load();}
    private void update(){if(listView!=null)listView.adapter.update(true);}
    @Override public void didReceivedNotification(int id,int account,Object...args){if(destroyed)return;if(space==null)update();else if(!busy)load();}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        MonogramController controller=MonogramController.get(currentAccount);
        if(space==null){
            items.add(UItem.asButton(1,R.drawable.msg_groups,"Join or create a MIX channel").setEnabled(!busy));
            items.add(UItem.asButton(2,R.drawable.msg_search,"Find MIX services").setEnabled(!busy));
            items.add(UItem.asShadow("Channels are conversations. A space groups related channels and can contain other spaces."));
            saved=controller.savedSpaces();channels=controller.mixChannels();
            if(!saved.isEmpty())items.add(UItem.asHeader("Your spaces"));
            for(int i=0;i<saved.size();i++) {
                XmppSpaces.Reference reference=saved.get(i);UItem row=UItem.asButton(100+i,R.drawable.msg_folders,reference.name,reference.service+" · "+reference.node).setEnabled(!busy);
                row.object2=reference;items.add(row);
            }
            if(!channels.isEmpty())items.add(UItem.asHeader("Your MIX channels"));
            for(int i=0;i<channels.size();i++) {
                TLRPC.Chat chat=channels.get(i);UItem row=UItem.asButton(100+saved.size()+i,R.drawable.msg_groups,chat.title,controller.jid(-chat.id)).setEnabled(!busy);
                row.object2=chat;items.add(row);
            }
            items.add(UItem.asHeader("Open a space"));items.add(UItem.asCustom(service));items.add(UItem.asCustom(node));
            items.add(UItem.asButton(3,"Open space").accent().setEnabled(!busy));
            items.add(UItem.asButton(4,R.drawable.msg_search,"Browse spaces on this service").setEnabled(!busy));
            items.add(UItem.asHeader("Create a space"));items.add(UItem.asCustom(name));
            items.add(UItem.asCheck(8,"Public space").setChecked(publicSpace).setEnabled(!busy));
            items.add(UItem.asShadow(publicSpace?"The space directory will be readable by anyone. Each channel keeps its own access rules.":"The space directory will use a private whitelist. Its owner can grant access; joining the space does not join its channels."));
            items.add(UItem.asButton(5,"Create space on this service").setEnabled(!busy));
        }else{
            items.add(UItem.asHeader(space.name));items.add(UItem.asShadow(space.service+" · "+space.node));
            boolean followed=controller.savedSpaces().stream().anyMatch(s->s.key().equals(space.key()));
            items.add(UItem.asButton(10,R.drawable.msg_retry,busy?"Loading…":"Refresh").setEnabled(!busy));
            items.add(UItem.asButton(11,followed?"Unfollow space":"Follow space").setEnabled(!busy));
            if(contents!=null){
                if(contents.entries.isEmpty())items.add(UItem.asShadow("This space has no supported channels or child spaces yet."));
                for(int i=0;i<contents.entries.size();i++){
                    XmppSpaces.Entry entry=contents.entries.get(i);
                    items.add(UItem.asButton(1000+i,entry.space==null?R.drawable.msg_groups:R.drawable.msg_folders,entry.name,entry.space==null?entry.channel:entry.space.service+" · "+entry.space.node).setEnabled(!busy));
                }
            }
            items.add(UItem.asShadow("Tap a channel to join or open it. Following this directory does not subscribe you to its channels. Long-press an entry to remove it from the directory if you have permission."));
            items.add(UItem.asHeader("Manage space"));
            items.add(UItem.asButton(12,"Add an existing channel").setEnabled(!busy));
            items.add(UItem.asButton(15,"Create a MIX channel in this space").setEnabled(!busy));
            items.add(UItem.asButton(13,"Add a nested space").setEnabled(!busy));
            items.add(UItem.asButton(14,"Manage access to this space").setEnabled(!busy));
            items.add(UItem.asShadow("These actions require permission on the pubsub service. Granting access to the directory does not grant access to its channels."));
        }
        if(busy)items.add(UItem.asShadow("Connecting…"));if(status!=null)items.add(UItem.asShadow(status));
    }
    private void open(XmppSpaces.Reference ref){
        if(ancestors.contains(ref.key()) || ancestors.size()>=16){status="This space links back to an open space, or the nesting is too deep.";update();return;}
        presentFragment(new MonogramSpacesActivity(currentAccount,ref,ancestors));
    }
    private void load(){
        if(busy || destroyed)return;busy=true;status=null;update();
        MonogramController.get(currentAccount).spacesRequest(spaces->spaces.load(space),(value,error)->{if(destroyed)return;busy=false;status=error;if(value!=null){contents=value;space=value.space;actionBar.setTitle(space.name);}update();});
    }
    private void showChat(TLRPC.Chat chat){Bundle args=new Bundle();args.putLong("chat_id",chat.id);ChatActivity activity=new ChatActivity(args);activity.setCurrentAccount(currentAccount);presentFragment(activity);}
    private String nick(){String own=MonogramController.get(currentAccount).accountJid();return own.substring(0,own.indexOf('@'));}
    private void openChannel(String jid){
        MonogramController controller=MonogramController.get(currentAccount);for(TLRPC.Chat chat:controller.mixChannels())if(jid.equals(controller.jid(-chat.id))){showChat(chat);return;}
        busy=true;status=null;update();controller.mixRequest(mix->mix.isChannel(jid),(mixed,error)->{
            if(destroyed)return;busy=false;if(error!=null){status=error;update();return;}
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Join channel?").setMessage(jid+"\nJoining follows this channel's own membership and encryption rules.").setPositiveButton("Join",(d,w)->{
                busy=true;update();org.telegram.tgnet.RequestDelegate done=(value,failure)->{if(destroyed)return;busy=false;status=failure==null?null:failure.text;update();if(failure==null)showChat((TLRPC.Chat)value);};
                if(mixed)controller.openMixChannel(jid,nick(),false,done);else controller.openRoom(jid,nick(),null,done);
            }).setNegativeButton("Cancel",null).create());update();
        });
    }
    private void fields(String title,String first,String second,String third,java.util.function.Consumer<String[]> submit){
        LinearLayout layout=new LinearLayout(getContext());layout.setOrientation(LinearLayout.VERTICAL);List<EditTextCell> inputs=new ArrayList<>();
        for(String hint:new String[]{first,second,third})if(hint!=null){EditTextCell input=new EditTextCell(getContext(),hint,false);input.editText.setSingleLine(true);input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3071)});layout.addView(input);inputs.add(input);}
        showDialog(new AlertDialog.Builder(getContext()).setTitle(title).setView(layout).setPositiveButton("Continue",(d,w)->{String[] values=new String[inputs.size()];for(int i=0;i<values.length;i++)values[i]=inputs.get(i).getText().toString().trim();submit.accept(values);}).setNegativeButton("Cancel",null).create());
    }
    private void modify(MonogramController.SpacesTask<Boolean> task){busy=true;status=null;update();MonogramController.get(currentAccount).spacesRequest(task,(value,error)->{if(destroyed)return;busy=false;status=error;update();if(error==null)load();});}
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        if(busy)return;MonogramController controller=MonogramController.get(currentAccount);
        if(space==null){
            if(item.object2 instanceof XmppSpaces.Reference){open((XmppSpaces.Reference)item.object2);return;}
            if(item.object2 instanceof TLRPC.Chat){showChat((TLRPC.Chat)item.object2);return;}
            if(item.id==1)presentFragment(new MonogramMixChannelActivity(currentAccount,0,null));
            else if(item.id==2){busy=true;update();controller.mixRequest(mix->mix.services(),(found,error)->{if(destroyed)return;busy=false;status=error;update();if(error!=null)return;if(found.isEmpty()){status="Your server advertises no MIX services. You can enter a service in Browse MIX channels.";update();return;}CharSequence[] labels=new CharSequence[found.size()];for(int i=0;i<labels.length;i++)labels[i]=found.get(i).getEntityID().toString();showDialog(new AlertDialog.Builder(getContext()).setTitle("MIX services").setItems(labels,(d,w)->presentFragment(new MonogramMixChannelActivity(currentAccount,0,"@"+found.get(w).getEntityID()))).setNegativeButton("Cancel",null).create());});}
            else if(item.id==3){try{open(new XmppSpaces.Reference(service.getText().toString().trim(),node.getText().toString().trim(),null));}catch(Exception invalid){status=invalid.getMessage();update();}}
            else if(item.id==4){busy=true;update();controller.spacesRequest(spaces->spaces.discover(service.getText().toString().trim()),(found,error)->{if(destroyed)return;busy=false;status=error;update();if(error!=null)return;if(found.isEmpty()){status="No accessible spaces were listed by this service.";update();return;}CharSequence[] labels=new CharSequence[found.size()];for(int i=0;i<labels.length;i++)labels[i]=found.get(i).name;showDialog(new AlertDialog.Builder(getContext()).setTitle("Spaces").setItems(labels,(d,w)->open(found.get(w))).setNegativeButton("Cancel",null).create());});}
            else if(item.id==5){try{XmppSpaces.Reference ref=new XmppSpaces.Reference(service.getText().toString().trim(),node.getText().toString().trim(),name.getText().toString().trim());busy=true;update();controller.spacesRequest(spaces->spaces.create(ref,publicSpace),(created,error)->{if(destroyed)return;busy=false;status=error;update();if(error==null){controller.saveSpace(created,true);open(created);}});}catch(Exception invalid){status=invalid.getMessage();update();}}
            else if(item.id==8){publicSpace=!publicSpace;update();}
            return;
        }
        if(item.id==10)load();
        else if(item.id==11){boolean following=controller.savedSpaces().stream().anyMatch(s->s.key().equals(space.key()));busy=true;update();controller.spacesRequest(spaces->{if(following){spaces.unsubscribe(space);return "none";}return spaces.subscribe(space);},(state,error)->{if(destroyed)return;busy=false;status=error;if(error==null){controller.saveSpace(space,!following);if("pending".equals(state))status="The space owner must approve your subscription.";}update();});}
        else if(item.id==12)fields("Add channel","Channel address","Display name",null,values->modify(spaces->{spaces.addChannel(space,values[0],values[1]);return true;}));
        else if(item.id==13)fields("Add nested space","Pubsub service","Space node",null,values->modify(spaces->{spaces.addSpace(space,new XmppSpaces.Reference(values[0],values[1],null));return true;}));
        else if(item.id==14)fields("Directory access","Contact's XMPP address",null,null,values->showDialog(new AlertDialog.Builder(getContext())
                .setTitle(values[0]).setItems(new CharSequence[]{"Grant access","Revoke access"},(d,w)->modify(spaces->{spaces.access(space,values[0],w==0);return true;}))
                .setNegativeButton("Cancel",null).create()));
        else if(item.id==15)fields("Create MIX channel","New channel address","Display name",null,values->{busy=true;update();controller.openMixChannel(values[0],nick(),true,(result,error)->{if(destroyed)return;if(error!=null){busy=false;status=error.text;update();return;}TLRPC.Chat chat=(TLRPC.Chat)result;controller.spacesRequest(spaces->{spaces.addChannel(space,values[0],values[1]);return true;},(value,failure)->{if(destroyed)return;busy=false;status=failure==null?null:"The channel was created, but could not be added to this space: "+failure;update();if(failure==null)load();});});});
        else if(item.id>=1000 && contents!=null && item.id-1000<contents.entries.size()){XmppSpaces.Entry entry=contents.entries.get(item.id-1000);if(entry.space!=null)open(entry.space);else openChannel(entry.channel);}
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){
        if(space==null || contents==null || busy || item.id<1000 || item.id-1000>=contents.entries.size())return false;
        XmppSpaces.Entry entry=contents.entries.get(item.id-1000);showDialog(new AlertDialog.Builder(getContext()).setTitle("Remove from space?").setMessage("This removes the directory entry. The channel or nested space and its members are kept.").setPositiveButton("Remove",(d,w)->modify(spaces->{spaces.remove(space,entry.id);return true;})).setNegativeButton("Cancel",null).create());return true;
    }
    @Override public void onFragmentDestroy(){destroyed=true;NotificationCenter.getInstance(currentAccount).removeObserver(this,NotificationCenter.updateInterfaces);super.onFragmentDestroy();}
}
