// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import java.util.ArrayList;
import org.telegram.messenger.voip.VoIPCallController;

/** Notification actions enter a visible Activity before starting microphone/camera foreground work. */
public final class MonogramCallPermissionActivity extends Activity {
    private static final int REQUEST=9102;
    private boolean pending;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(Build.VERSION.SDK_INT>=27) { setShowWhenLocked(true);setTurnScreenOn(true); }
        if(MonogramCallService.matching(getIntent())==null) { finish();return; }
        pending=saved!=null && saved.getBoolean("permission-pending");
        if(!pending) proceed();
    }
    private void proceed() {
        MonogramCallService.Controller c=MonogramCallService.matching(getIntent());
        if(c==null) { finish();return; }
        String action=getIntent().getStringExtra(MonogramCallService.ACTION);
        if(MonogramCallService.OPEN.equals(action)) { MonogramCallService.open(c);finish();return; }
        ArrayList<String> missing=new ArrayList<>();
        if(MonogramCallService.BLUETOOTH.equals(action)) {
            if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if(Build.VERSION.SDK_INT>=23) {
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
            if(c.call.video && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA);
        }
        if(!missing.isEmpty()) { pending=true;requestPermissions(missing.toArray(new String[0]),REQUEST);return; }
        if(MonogramCallService.BLUETOOTH.equals(action)) c.setAudioOutput(VoIPCallController.AUDIO_OUTPUT_BLUETOOTH);
        else c.launchService(this);
        finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state);state.putBoolean("permission-pending",pending); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=REQUEST) return;pending=false;
        MonogramCallService.Controller c=MonogramCallService.matching(getIntent());
        if(c==null) { finish();return; }
        boolean granted=results.length==permissions.length && results.length>0;
        for(int result:results) granted&=result==PackageManager.PERMISSION_GRANTED;
        if(granted) proceed();
        else {
            if(!MonogramCallService.BLUETOOTH.equals(getIntent().getStringExtra(MonogramCallService.ACTION))) {
                if(c.call.outgoing) c.hangUp();else c.declineIncomingCall();
            }
            finish();
        }
    }
}
