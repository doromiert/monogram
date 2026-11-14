// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.app.*;import android.content.*;import android.graphics.Bitmap;import android.view.View;import android.widget.*;
import org.json.*;import java.util.*;
import org.telegram.messenger.*;import org.telegram.messenger.xmpp.MonogramLanSync;
import org.telegram.ui.ActionBar.AlertDialog;import org.telegram.ui.Cells.EditTextCell;import org.telegram.ui.Components.*;
/** Uses the existing settings renderer, system authentication and QR camera/gallery scanner. */
public final class MonogramDeviceSyncActivity extends MonogramSettingsActivity {
 private boolean destroyed,busy;private String pendingAction,pendingValue;private int generation;private String error;private final ArrayList<String> peers=new ArrayList<>();
 private final Runnable refresh=new Runnable(){public void run(){if(destroyed)return;if(listView!=null)listView.adapter.update(false);AndroidUtilities.runOnUIThread(this,1000);}};
 public MonogramDeviceSyncActivity(int account){currentAccount=account;}
 @Override protected CharSequence getTitle(){return "Device sync";}
 @Override public boolean onFragmentCreate(){MonogramLanSync.install();return super.onFragmentCreate();}
 @Override public void onResume(){super.onResume();if(MonogramLanSync.enabled())MonogramLanSync.resume();AndroidUtilities.cancelRunOnUIThread(refresh);refresh.run();}
 @Override public void onPause(){super.onPause();AndroidUtilities.cancelRunOnUIThread(refresh);}
 @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
  items.add(UItem.asCheck(1,"Encrypted LAN sync").setChecked(MonogramLanSync.enabled()));
  items.add(UItem.asShadow(error!=null?error:MonogramLanSync.status()));
  if(MonogramLanSync.enabled()){
   if(MonogramLanSync.needsUnlock())items.add(UItem.asButton(5,"Unlock to resume").accent());
   items.add(UItem.asHeader("Pair another device"));
   items.add(UItem.asButton(2,busy?"Preparing…":"Show pairing QR code").setEnabled(!busy));
   items.add(UItem.asButton(3,"Scan pairing QR code").setEnabled(!busy));
   items.add(UItem.asButton(6,"Show rotating pairing code").setEnabled(!busy));
   items.add(UItem.asButton(7,"Pair with an eight-digit code").setEnabled(!busy));
   items.add(UItem.asButton(4,"Paste pairing code").setEnabled(!busy));
  }
  items.add(UItem.asShadow("Connect both devices to the same local network. Pairing shares accounts and passwords, sticker and emoji packs, wallet recovery data, folders, public OMEMO fingerprints, and API keys. Only scan a code displayed on a device you own. Sync pauses when this phone locks; a device screen lock is required."));

  peers.clear();JSONArray paired=MonogramLanSync.snapshot().optJSONArray("peers");if(paired!=null&&paired.length()>0){items.add(UItem.asHeader("Paired devices"));for(int i=0;i<paired.length();i++){JSONObject p=paired.optJSONObject(i);if(p==null)continue;peers.add(p.optString("id"));items.add(UItem.asButton(100+peers.size()-1,p.optString("name","Device"),"Unpair"));}}
  List<JSONObject> fingerprints=MonogramLanSync.fingerprints();if(!fingerprints.isEmpty()){items.add(UItem.asHeader("Fingerprints from your devices"));for(JSONObject fp:fingerprints)items.add(UItem.asShadow(fp.optString("address")+" · "+fp.optString("mode")+" · "+fp.optString("device")+"\n"+fp.optString("fingerprint")));items.add(UItem.asShadow("Synced fingerprints are references for verification. Each device keeps its own OMEMO identity and active sessions."));}
 }
 private void update(){if(!destroyed&&listView!=null)listView.adapter.update(true);}
 @Override protected void onClick(UItem item,View view,int position,float x,float y){
  if(item.id==1){MonogramLanSync.enable(!MonogramLanSync.enabled());update();}
  else if(item.id==2)request("invite","");
  else if(item.id==3){CameraScanActivity.showAsSheet(this,true,CameraScanActivity.TYPE_QR,new MonogramPairingScan(code->{if(!destroyed)request("join",code);},failure->{if(!destroyed){error=failure;update();BulletinFactory.of(this).showForError(failure);}}));}
  else if(item.id==4){EditTextCell input=new EditTextCell(getContext(),"monogram-sync:…",false);input.editText.setSingleLine(false);input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8192)});if(android.os.Build.VERSION.SDK_INT>=26)input.editText.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);showDialog(new AlertDialog.Builder(getContext()).setTitle("Pair device").setView(input).setPositiveButton("Pair",(d,w)->request("join",input.getText().toString().trim())).setNegativeButton("Cancel",null).create());}
  else if(item.id==6)request("numeric","");
  else if(item.id==7)enterCode();
  else if(item.id==5)unlock();
  else if(item.id>=100&&item.id-100<peers.size()){String id=peers.get(item.id-100);showDialog(new AlertDialog.Builder(getContext()).setTitle("Unpair this device?").setMessage("Transfers between these two devices stop. Other paired connections stay active. Data already received stays on each device.").setPositiveButton("Unpair",(d,w)->request("forget",id)).setNegativeButton("Cancel",null).create());}
 }
 private void unlock(){KeyguardManager guard=(KeyguardManager)getContext().getSystemService(Context.KEYGUARD_SERVICE);Intent i=guard==null?null:guard.createConfirmDeviceCredentialIntent("Unlock Monogram sync","Authorize encrypted account and wallet sync");if(i==null){error="Set a device screen lock in Android settings first.";update();return;}startActivityForResult(i,2937);}
 @Override public void onActivityResultFragment(int request,int result,Intent data){super.onActivityResultFragment(request,result,data);if(request==2937&&result==Activity.RESULT_OK){MonogramLanSync.resume();String action=pendingAction,value=pendingValue;pendingAction=pendingValue=null;if(action!=null)request(action,value);else update();}}
 private void request(String action,String value){if(busy||destroyed)return;if(!MonogramLanSync.enabled())MonogramLanSync.enable(true);busy=true;error=null;update();int request=++generation;MonogramLanSync.action(action,value,(result,failure)->{if(destroyed||generation!=request){if(action.equals("invite"))MonogramLanSync.cancelInvite();return;}busy=false;error=failure;update();if(failure!=null){if(MonogramLanSync.needsUnlock()){pendingAction=action;pendingValue=value;unlock();}else BulletinFactory.of(this).showForError(failure);}if(failure==null&&action.equals("invite"))qr(result,request);if(failure==null&&action.equals("numeric"))numeric();});}
 private void numeric(){
  TextView code=new TextView(getContext());code.setGravity(android.view.Gravity.CENTER);code.setTextSize(34);code.setPadding(24,24,24,24);code.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText));
  AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle("Rotating pairing code").setMessage("Type this code on your other device. New code every 30 seconds; pairing closes after two minutes.").setView(code).setPositiveButton("Done",null).create();dialog.setOnDismissListener(d->MonogramLanSync.cancelInvite());showDialog(dialog);
  Runnable tick=new Runnable(){public void run(){if(destroyed||!dialog.isShowing())return;JSONObject value=MonogramLanSync.numericStatus();String text=value.optString("code","");code.setText(text.isEmpty()?"Pairing ended":text+"\n"+value.optInt("remaining")+"s");AndroidUtilities.runOnUIThread(this,1000);}};tick.run();
 }
 private void enterCode(){
  JSONArray nearby=MonogramLanSync.nearby();if(nearby.length()==0){error="Open Show rotating pairing code on your other device. Looking for nearby devices…";update();BulletinFactory.of(this).showForError(error);return;}
  CharSequence[] names=new CharSequence[nearby.length()];for(int i=0;i<names.length;i++)names[i]=nearby.optJSONObject(i).optString("name","Device");
  showDialog(new AlertDialog.Builder(getContext()).setTitle("Choose the device showing a code").setItems(names,(d,which)->{
   JSONObject peer=nearby.optJSONObject(which);if(peer==null)return;EditTextCell input=new EditTextCell(getContext(),"Eight-digit code",false);input.editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
   showDialog(new AlertDialog.Builder(getContext()).setTitle("Enter pairing code").setView(input).setPositiveButton("Pair",(dialog,w)->{try{request("joinNumeric",new JSONObject().put("id",peer.getString("id")).put("code",input.getText().toString().trim()).toString());}catch(Exception e){error="Invalid device code.";update();}}).setNegativeButton("Cancel",null).create());
  }).setNegativeButton("Cancel",null).create());
 }
 private void qr(String code,int request){
  ImageView image=new ImageView(getContext());image.setAdjustViewBounds(true);image.setPadding(AndroidUtilities.dp(16),AndroidUtilities.dp(16),AndroidUtilities.dp(16),AndroidUtilities.dp(16));image.setBackgroundColor(0xffffffff);image.setContentDescription("Device pairing QR code");
  AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle("Scan on your other device").setMessage("Valid for two minutes. This grants access to your synchronized credentials and wallet recovery data.").setView(image).setPositiveButton("Done",null).setNeutralButton("Copy pairing code",(d,w)->AndroidUtilities.addToClipboard(code)).create();
  dialog.setOnDismissListener(d->MonogramLanSync.cancelInvite());showDialog(dialog);
  Utilities.globalQueue.postRunnable(()->{Bitmap bitmap=null;try{com.google.zxing.qrcode.encoder.ByteMatrix matrix=com.google.zxing.qrcode.encoder.Encoder.encode(code,com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M).getMatrix();int modules=matrix.getWidth()+8,scale=Math.max(2,768/modules),size=modules*scale;int[] pixels=new int[size*size];Arrays.fill(pixels,0xffffffff);for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)if(matrix.get(x,y)==1)for(int dy=0;dy<scale;dy++)Arrays.fill(pixels,((y+4)*scale+dy)*size+(x+4)*scale,((y+4)*scale+dy)*size+(x+5)*scale,0xff000000);bitmap=Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888);}catch(Exception ignored){}final Bitmap qr=bitmap;AndroidUtilities.runOnUIThread(()->{if(!destroyed&&generation==request&&dialog.isShowing()){if(qr!=null)image.setImageBitmap(qr);else{dialog.dismiss();error="Could not generate QR code. Try again.";update();}}else if(qr!=null)qr.recycle();});});
  AndroidUtilities.runOnUIThread(()->{if(dialog.isShowing())dialog.dismiss();},120000);
 }
 @Override public void onFragmentDestroy(){destroyed=true;generation++;MonogramLanSync.cancelInvite();AndroidUtilities.cancelRunOnUIThread(refresh);super.onFragmentDestroy();}
 @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
