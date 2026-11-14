// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.view.View;import java.util.*;
import org.telegram.messenger.*;import org.telegram.messenger.xmpp.MonogramPacks;
import org.telegram.tgnet.TLRPC;import org.telegram.ui.ActionBar.AlertDialog;import org.telegram.ui.Cells.EditTextCell;import org.telegram.ui.Components.*;
/** Names and fallback emoji are independent fields for every custom emoji. */
public final class MonogramEmojiPackActivity extends MonogramSettingsActivity {
 private final long packId;private boolean saving,destroyed;private final ArrayList<MonogramPacks.Item> entries=new ArrayList<>();
 public MonogramEmojiPackActivity(int account,long id){currentAccount=account;packId=id;}
 private MonogramPacks.Pack pack(){for(MonogramPacks.Pack p:MonogramPacks.packs(currentAccount))if(p.id==packId)return p;return null;}
 @Override protected CharSequence getTitle(){MonogramPacks.Pack p=pack();return p==null?"Custom emoji":p.name;}
 @Override protected void fillItems(ArrayList<UItem> rows,UniversalAdapter adapter){
  MonogramPacks.Pack p=pack();entries.clear();if(p==null){rows.add(UItem.asShadow("This pack is no longer available."));return;}
  rows.add(UItem.asButton(1,"Preview and manage artwork"));rows.add(UItem.asShadow("Tap an emoji to give it a name. Names are searchable; the associated emoji is a separate fallback for other clients."));
  rows.add(UItem.asHeader("Custom emoji"));
  for(MonogramPacks.Item item:p.items){entries.add(item);UItem row=UItem.asButton(100+entries.size()-1,item.name,item.emoji);row.setEnabled(!saving);rows.add(row);}
 }
 @Override protected void onClick(UItem row,View view,int position,float x,float y){
  if(saving)return;if(row.id==1){TLRPC.TL_inputStickerSetID id=new TLRPC.TL_inputStickerSetID();id.id=packId;showDialog(new StickersAlert(getContext(),this,id,MonogramPacks.find(currentAccount,id),null,false));return;}
  int index=row.id-100;if(index<0||index>=entries.size())return;MonogramPacks.Item item=entries.get(index);
  showDialog(new AlertDialog.Builder(getContext()).setTitle(item.name).setItems(new CharSequence[]{"Edit emoji name","Change associated emoji"},(d,which)->edit(item,which==1)).setNegativeButton("Cancel",null).create());
 }
 private void edit(MonogramPacks.Item item,boolean fallback){
  EditTextCell input=new EditTextCell(getContext(),fallback?"Associated emoji":"Emoji name",false);input.editText.setSingleLine(true);input.editText.setText(fallback?item.emoji:item.name);input.editText.selectAll();
  showDialog(new AlertDialog.Builder(getContext()).setTitle(fallback?"Associated emoji":"Emoji name").setView(input).setPositiveButton("Save",(d,w)->{
   String value=input.getText().toString(),owner=MonogramPacks.owner(currentAccount);saving=true;listView.adapter.update(true);
   Utilities.globalQueue.postRunnable(()->{String failure=null;try{if(!owner.equals(MonogramPacks.owner(currentAccount)))throw new IllegalStateException("Account changed. Reopen this pack.");MonogramPacks.rename(currentAccount,packId,item.id,value,fallback);}catch(Exception e){failure=e.getMessage();}String error=failure;
    AndroidUtilities.runOnUIThread(()->{if(destroyed)return;saving=false;listView.adapter.update(true);if(error!=null)BulletinFactory.of(this).showForError(error);});});
  }).setNegativeButton("Cancel",null).create());
 }
 @Override public void onResume(){super.onResume();if(listView!=null)listView.adapter.update(false);}
 @Override public void onFragmentDestroy(){destroyed=true;super.onFragmentDestroy();}
 @Override protected boolean onLongClick(UItem row,View view,int p,float x,float y){return false;}
}
