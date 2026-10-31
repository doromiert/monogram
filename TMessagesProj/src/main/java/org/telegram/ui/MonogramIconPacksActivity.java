// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import java.util.*;
import androidx.core.content.FileProvider;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;

public final class MonogramIconPacksActivity extends MonogramSettingsActivity {
    private static final int PICK_PACK=7250,PICK_ICON=7251;
    private final String packId;
    private MonogramIconPacks.Pack pack;
    private List<MonogramIconPacks.Pack> packs=Collections.emptyList();
    private final ArrayList<String> resources=new ArrayList<>();
    private String pendingResource;
    private boolean busy;
    private int orderSection=-1;
    public MonogramIconPacksActivity(int account){this(account,null);}
    private MonogramIconPacksActivity(int account,String id){currentAccount=account;packId=id;}
    @Override protected CharSequence getTitle(){return packId==null?"Icon packs":pack==null?"Edit icon pack":pack.name;}
    @Override public View createView(Context context) {
        updateData();View view=super.createView(context);listView.allowReorder(true);
        listView.listenReorder((section,rows)->{
            if(section!=orderSection)return;ArrayList<String> ids=new ArrayList<>();for(UItem row:rows)if(row.id>=100&&row.id<100+packs.size())ids.add(packs.get(row.id-100).id);MonogramIconPacks.order(ids);
        });
        MonogramIconPacks.reload(()->{if(getParentActivity()!=null)refresh();});return view;
    }
    private void updateData() {packs=MonogramIconPacks.list();pack=null;for(MonogramIconPacks.Pack p:packs)if(p.id.equals(packId))pack=p;}
    private void refresh(){updateData();if(actionBar!=null)actionBar.setTitle(getTitle());if(listView!=null){UniversalAdapter adapter=listView.adapter;listView.setAdapter(null);listView.getRecycledViewPool().clear();listView.setAdapter(adapter);adapter.update(false);}if(parentLayout!=null)parentLayout.rebuildFragments(0);}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        resources.clear();updateData();
        if(busy){items.add(UItem.asShadow("Updating icon pack…"));return;}
        if(packId==null){
            items.add(UItem.asButton(1,"Create icon pack"));items.add(UItem.asButton(2,"Import icon pack"));
            items.add(UItem.asShadow("Import ZIP packs for static, animated and code-drawn UI icons. Tap a pack to enable it; hold it to edit or share. Earlier packs override later ones."));
            items.add(UItem.asHeader("Installed packs"));orderSection=adapter.reorderSectionStart();
            for(int i=0;i<packs.size();i++)items.add(UItem.asCheck(100+i,packs.get(i).name).setChecked(MonogramIconPacks.enabled(packs.get(i))).setReordering(true));
            adapter.reorderSectionEnd();
            if(packs.isEmpty())items.add(UItem.asShadow("No custom icon packs. Built-in Monogram icons are active."));
        }else if(pack!=null){
            items.add(UItem.asButton(3,"Edit name"));items.add(UItem.asButton(4,"Add or replace icon"));items.add(UItem.asButton(5,"Share pack"));
            items.add(UItem.asShadow("Tap an icon to replace its image. Hold an icon to remove its override."));
            items.add(UItem.asHeader("Icons"));Iterator<String> it=pack.mappings().keys();while(it.hasNext())resources.add(it.next());Collections.sort(resources);
            for(int i=0;i<resources.size();i++) {
                int id=resources.get(i).startsWith("raw.")?0:MonogramIconPacks.resourceId(getContext().getResources(),resources.get(i));
                items.add(UItem.asButton(100+i,id,resources.get(i)));
            }
            if(resources.isEmpty())items.add(UItem.asShadow("No icons yet. Add an icon to override its built-in image."));
        }
    }
    private void result(String error){busy=false;if(getParentActivity()==null)return;if(error!=null)showDialog(new AlertDialog.Builder(getContext()).setTitle("Icon pack").setMessage(error).setPositiveButton("OK",null).create());refresh();}
    private void input(String title,String value,java.util.function.Consumer<String> save) {
        EditText edit=new EditText(getContext());edit.setSingleLine(true);edit.setText(value);edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));edit.setPadding(AndroidUtilities.dp(24),AndroidUtilities.dp(12),AndroidUtilities.dp(24),AndroidUtilities.dp(12));
        showDialog(new AlertDialog.Builder(getContext()).setTitle(title).setView(edit).setPositiveButton("Save",(d,w)->save.accept(edit.getText().toString())).setNegativeButton("Cancel",null).create());
    }
    private void pick(int request,String mime){try{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);getParentActivity().startActivityForResult(intent,request);}catch(Exception e){result("Could not open the file picker");}}
    private void pickIcon(String name){pendingResource=name;pick(PICK_ICON,"image/*");}
    @Override protected void onClick(UItem item,View v,int p,float x,float y) {
        if(busy)return;
        if(item.id>=100){int i=item.id-100;if(packId==null&&i<packs.size()){MonogramIconPacks.Pack selected=packs.get(i);busy=true;MonogramIconPacks.enable(selected,!MonogramIconPacks.enabled(selected),this::result);}else if(i<resources.size())pickIcon(resources.get(i));return;}
        switch(item.id){
            case 1:input("New icon pack","",name->{busy=true;MonogramIconPacks.create(name,this::result);});break;
            case 2:pick(PICK_PACK,"*/*");break;
            case 3:input("Edit name",pack.name,name->{busy=true;MonogramIconPacks.rename(pack,name,this::result);});break;
            case 4:presentFragment(new MonogramIconResourceActivity(currentAccount,this::pickIcon));break;
            case 5:share(pack);break;
        }
    }
    private void share(MonogramIconPacks.Pack selected){busy=true;MonogramIconPacks.export(selected,file->{busy=false;try{Uri uri=FileProvider.getUriForFile(getContext(),ApplicationLoader.getApplicationId()+".provider",file);Intent intent=new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);getParentActivity().startActivity(Intent.createChooser(intent,"Share icon pack"));}catch(Exception e){result("Could not share icon pack");}},this::result);}
    @Override protected boolean onLongClick(UItem item,View v,int p,float x,float y) {
        if(item.id<100||busy)return false;int i=item.id-100;
        if(packId==null&&i<packs.size()){
            MonogramIconPacks.Pack selected=packs.get(i);ItemOptions menu=ItemOptions.makeOptions(this,v);
            menu.add(R.drawable.msg_edit,"Edit",()->presentFragment(new MonogramIconPacksActivity(currentAccount,selected.id)));
            menu.add(R.drawable.msg_share,"Share",()->share(selected));
            menu.add(R.drawable.msg_delete,"Delete",()->showDialog(new AlertDialog.Builder(getContext()).setTitle("Delete icon pack?").setMessage(selected.name).setPositiveButton("Delete",(d,w)->{busy=true;MonogramIconPacks.remove(selected,this::result);}).setNegativeButton("Cancel",null).create()));menu.show();return true;
        }
        if(pack!=null&&i<resources.size()) {String resource=resources.get(i);showDialog(new AlertDialog.Builder(getContext()).setTitle("Remove icon override?").setMessage(resource).setPositiveButton("Remove",(d,w)->{busy=true;MonogramIconPacks.removeIcon(pack,resource,this::result);}).setNegativeButton("Cancel",null).create());return true;}
        return false;
    }
    @Override public void onActivityResultFragment(int request,int result,Intent data) {
        super.onActivityResultFragment(request,result,data);if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;
        if(request==PICK_PACK){busy=true;MonogramIconPacks.importPack(data.getData(),this::result);}
        else if(request==PICK_ICON&&pack!=null&&pendingResource!=null){busy=true;MonogramIconPacks.addIcon(pack,pendingResource,data.getData(),this::result);pendingResource=null;}
    }
}
