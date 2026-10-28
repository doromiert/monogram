// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.FrameLayout;
import java.util.*;
import com.negzero.monogram.xmpp.calls.XmppMuji;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramMujiService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;
import org.webrtc.SurfaceViewRenderer;

/** Native room call controls; opening this screen never starts capture. */
public final class MonogramGroupCallActivity extends MonogramSettingsActivity implements NotificationCenter.NotificationCenterDelegate {
    private final long peer;private final String title;private boolean pendingVideo;private String error;
    private final Map<String,SurfaceViewRenderer> renderers=new HashMap<>();
    public MonogramGroupCallActivity(int account,long peer,String title){this.currentAccount=account;this.peer=peer;this.title=title==null?"Group call":title;}
    @Override protected CharSequence getTitle(){return title;}
    @Override public boolean onFragmentCreate(){super.onFragmentCreate();NotificationCenter.getInstance(currentAccount).addObserver(this,NotificationCenter.monogramGroupCallChanged);return true;}
    private MonogramMujiService call(){MonogramMujiService c=MonogramMujiService.current;return c!=null && c.account==currentAccount && c.peer==peer?c:null;}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        MonogramMujiService call=call();items.add(UItem.asHeader("Group call"));
        if(call==null){
            String stopped=MonogramMujiService.lastError(currentAccount,peer);if(stopped!=null)error=stopped;
            releaseVideos();items.add(UItem.asButton(1,R.drawable.msg_callback,"Join voice call").accent());
            items.add(UItem.asButton(2,R.drawable.msg_videocall,"Join video call").accent());
            if(error!=null)items.add(UItem.asShadow(error));
            items.add(UItem.asShadow("Join or start a call in this room. Your microphone starts only after you join. Up to eight participants can join a private, non-anonymous room."));
            return;
        }
        items.add(UItem.asSettingsCell(0,"Status",call.status).setEnabled(false));
        items.add(UItem.asCheck(3,"Mute microphone").setChecked(call.muted));
        items.add(UItem.asCheck(6,"Speaker").setChecked(call.speaker()));
        if(call.video)items.add(UItem.asCheck(4,"Camera").setChecked(call.cameraEnabled));
        items.add(UItem.asButton(5,R.drawable.msg_cancel,"Leave call").accent());
        items.add(UItem.asShadow("Audio and video use encrypted DTLS-SRTP connections. Participant identities have not been cryptographically verified. Your existing peer-to-peer call privacy settings apply."));
        items.add(UItem.asHeader("Participants · "+(call.participants.size()+1)));
        items.add(UItem.asSettingsCell(-1,"You",call.muted?"Microphone muted":"Microphone on").setEnabled(false));
        Set<String> visible=new HashSet<>();
        for(XmppMuji.Participant participant:call.participants){
            String nick=participant.occupant.substring(participant.occupant.indexOf('/')+1);
            String state=call.peerErrors.get(participant.realJid);if(state==null)state=participant.preparing?"Preparing…":call.connected.contains(participant.realJid)?"Connected":"Connecting…";
            items.add(UItem.asSettingsCell(-2,nick,state).setEnabled(false));
            items.add(UItem.asShadow(participant.realJid));
            if(call.remoteVideo.contains(participant.realJid) && call.egl()!=null){
                visible.add(participant.realJid);SurfaceViewRenderer renderer=renderers.get(participant.realJid);
                if(renderer==null){renderer=new SurfaceViewRenderer(getContext());renderer.init(call.egl(),null);renderer.setEnableHardwareScaler(true);renderer.setLayoutParams(new FrameLayout.LayoutParams(-1,AndroidUtilities.dp(190)));renderers.put(participant.realJid,renderer);call.sink(participant.realJid,renderer);}
                items.add(UItem.asCustom(renderer));
            }
        }
        for(String address:new ArrayList<>(renderers.keySet()))if(!visible.contains(address)){call.sink(address,null);renderers.remove(address).release();}
    }
    private void releaseVideos(){MonogramMujiService call=call();for(Map.Entry<String,SurfaceViewRenderer> entry:renderers.entrySet()){if(call!=null)call.sink(entry.getKey(),null);entry.getValue().release();}renderers.clear();}
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        MonogramMujiService call=call();
        if(item.id==1 || item.id==2){pendingVideo=item.id==2;join();}
        else if(call!=null && item.id==3)call.setMuted(!call.muted);
        else if(call!=null && item.id==4)call.setCamera(!call.cameraEnabled);
        else if(call!=null && item.id==5)call.stopSelf();
        else if(call!=null && item.id==6)call.speaker(!call.speaker());
    }
    private void join(){
        if(getParentActivity()==null)return;
        ArrayList<String> missing=new ArrayList<>();
        if(getParentActivity().checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.RECORD_AUDIO);
        if(pendingVideo && getParentActivity().checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.CAMERA);
        if(!missing.isEmpty()){getParentActivity().requestPermissions(missing.toArray(new String[0]),2911);return;}
        try{MonogramMujiService.start(getParentActivity(),currentAccount,peer,title,pendingVideo);}
        catch(Exception failed){showDialog(new AlertDialog.Builder(getContext()).setTitle("Could not join call").setMessage(failed.getMessage()).setPositiveButton("OK",null).create());}
    }
    @Override public void onRequestPermissionsResultFragment(int requestCode,String[] permissions,int[] grants){super.onRequestPermissionsResultFragment(requestCode,permissions,grants);if(requestCode==2911){for(int grant:grants)if(grant!=PackageManager.PERMISSION_GRANTED)return;if(grants.length>0)join();}}
    @Override public void onResume(){super.onResume();if(listView!=null)listView.adapter.update(true);}
    @Override public void onFragmentDestroy(){releaseVideos();NotificationCenter.getInstance(currentAccount).removeObserver(this,NotificationCenter.monogramGroupCallChanged);super.onFragmentDestroy();}
    @Override public void didReceivedNotification(int id,int account,Object... args){MonogramMujiService call=call();if(call!=null && call.error!=null)error=call.error;if(listView!=null)listView.adapter.update(true);}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
