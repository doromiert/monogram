// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import java.util.*;
import com.negzero.monogram.xmpp.XmppDeveloperTools;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.*;

/** Native XEP-0030 node browser, also used for the XEP-0050 command list. */
public final class MonogramServiceDiscoveryActivity extends MonogramSettingsActivity {
    private String address,node,error;
    private final boolean commands;
    private boolean busy,destroyed,loaded;
    private XmppDeveloperTools.Discovery result;
    private final ArrayList<DiscoverItems.Item> children=new ArrayList<>();
    public MonogramServiceDiscoveryActivity(int account,String address,String node,boolean commands) {
        currentAccount=account;this.commands=commands;
        String own=MonogramController.get(account).accountJid();
        this.address=address==null ? own.substring(own.indexOf('@')+1) : address;
        this.node=commands ? XmppDeveloperTools.COMMANDS : node;
    }
    @Override protected CharSequence getTitle() { return commands ? "Ad-hoc commands" : "Service discovery"; }
    @Override public void onResume() { super.onResume();if(!loaded) {loaded=true;load();} }
    private void load() {
        if(busy || destroyed) return;busy=true;error=null;update();
        final String target=address,selected=node;
        MonogramController.get(currentAccount).developerRequest(tools -> tools.discover(target,selected),(value,failure) -> {
            if(destroyed) return;busy=false;result=value;error=failure;update();
        });
    }
    private void update() {if(listView!=null) listView.adapter.update(true);}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asButton(1,"XMPP address",address).setEnabled(!busy));
        if(!commands) items.add(UItem.asButton(2,"Node",node==null || node.isEmpty() ? "Root" : node).setEnabled(!busy));
        items.add(UItem.asButton(3,R.drawable.msg_retry,busy ? "Loading…" : "Refresh").setEnabled(!busy));
        items.add(UItem.asButton(4,"My account").setEnabled(!busy));
        if(!commands) items.add(UItem.asButton(5,"Ad-hoc commands on this service").setEnabled(!busy));
        items.add(UItem.asShadow(null));
        if(error!=null) items.add(UItem.asShadow(error));
        children.clear();
        if(result==null) return;
        if(!commands) {
            items.add(UItem.asHeader("Identities"));
            if(result.infoError!=null) items.add(UItem.asShadow(result.infoError));
            else {
                for(org.jivesoftware.smackx.disco.packet.DiscoverInfo.Identity identity:result.info.getIdentities())
                    items.add(UItem.asShadow((identity.getName()==null ? "" : identity.getName()+"\n")+identity.getCategory()+" / "+identity.getType()));
                items.add(UItem.asHeader("Features"));
                for(org.jivesoftware.smackx.disco.packet.DiscoverInfo.Feature feature:result.info.getFeatures())
                    items.add(UItem.asShadow(feature.getVar()));
                items.add(UItem.asButton(6,R.drawable.msg_copy,"View discovery XML"));
            }
        }
        items.add(UItem.asHeader(commands ? "Available commands" : "Items"));
        if(result.itemsError!=null) items.add(UItem.asShadow(result.itemsError));
        else {
            children.addAll(result.items.getItems());
            if(children.isEmpty()) items.add(UItem.asShadow(commands ? "This service offers no commands to this account." : "No child items."));
            for(int i=0;i<children.size();i++) {
                DiscoverItems.Item child=children.get(i);String label=child.getName();
                if(label==null || label.isEmpty()) label=child.getNode()==null ? child.getEntityID().toString() : child.getNode();
                items.add(UItem.asButton(100+i,label,child.getEntityID().toString()).setEnabled(!busy));
            }
        }
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(busy) return;
        if(item.id==1 || item.id==2) {
            boolean jid=item.id==1;EditTextCell input=new EditTextCell(getContext(),jid ? "XMPP address or server" : "Node (empty for root)",false);
            input.editText.setText(jid ? address : node);input.editText.setSingleLine(true);
            input.editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)});
            showDialog(new AlertDialog.Builder(getContext()).setTitle(jid ? "XMPP address" : "Discovery node").setView(input)
                .setPositiveButton("Browse",(dialog,which) -> {
                    String value=input.getText().toString().trim();
                    if(jid) {try {XmppDeveloperTools.target(value);} catch(Exception invalid) {error="Enter a valid XMPP address or server domain.";update();return;} address=value;}
                    else node=value.isEmpty() ? null : value;
                    result=null;AndroidUtilities.hideKeyboard(input.editText);load();
                }).setNegativeButton("Cancel",null).create());
        } else if(item.id==3) load();
        else if(item.id==4) {address=MonogramController.get(currentAccount).accountJid();result=null;load();}
        else if(item.id==5) presentFragment(new MonogramServiceDiscoveryActivity(currentAccount,address,null,true));
        else if(item.id==6 && result!=null && result.info!=null) MonogramDebugActivity.showText(this,"Discovery XML",result.info.toXML().toString()+(result.items==null ? "" : "\n\n"+result.items.toXML()));
        else if(item.id>=100 && item.id-100<children.size()) {
            DiscoverItems.Item child=children.get(item.id-100);
            if(commands) presentFragment(new MonogramAdHocCommandActivity(currentAccount,child.getEntityID().toString(),child.getNode(),child.getName()));
            else presentFragment(new MonogramServiceDiscoveryActivity(currentAccount,child.getEntityID().toString(),child.getNode(),false));
        }
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
    @Override public void onFragmentDestroy() { destroyed=true;super.onFragmentDestroy(); }
}
