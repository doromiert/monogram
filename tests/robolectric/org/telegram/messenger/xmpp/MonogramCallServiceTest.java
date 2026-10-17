package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import com.negzero.monogram.xmpp.XmppSession;
import com.negzero.monogram.xmpp.calls.XmppCalls;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smack.XMPPConnection;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.voip.*;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=Application.class,
        shadows={MonogramScreenWakeTest.Session.class,MonogramCallServiceTest.Signalling.class})
public class MonogramCallServiceTest {
    @Implements(value=XmppCalls.class,isInAndroidSdk=false)
    public static class Signalling {
        static int declines,ends;
        @Implementation public void __constructor__(XMPPConnection connection) {}
        @Implementation public void reject(String sid) { declines++; }
        @Implementation public void end(String sid) { ends++; }
    }
    Application application;
    Map<Integer,Object> bindings;
    Class<?> bindingClass;
    @Before public void setup() throws Exception {
        application=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=application;
        bindings=ReflectionHelpers.getStaticField(MonogramCallService.class,"bindings");bindings.clear();
        VoIPCallController old=VoIPCallControllers.get();if(old!=null) VoIPCallControllers.unregister(old);
        bindingClass=Class.forName(MonogramCallService.class.getName()+"$Binding");
        Signalling.declines=0;Signalling.ends=0;
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA);
    }
    @After public void cleanup() {
        VoIPCallController current=VoIPCallControllers.get();if(current!=null) VoIPCallControllers.unregister(current);bindings.clear();
    }
    private MonogramCallService.Controller controller(int account,boolean outgoing,boolean video,BooleanSupplier current,XmppSession session) throws Exception {
        Constructor<?> bindingConstructor=bindingClass.getDeclaredConstructor(int.class,XmppSession.class,BooleanSupplier.class);bindingConstructor.setAccessible(true);
        Object binding=bindingConstructor.newInstance(account,session,current);bindings.put(account,binding);
        Constructor<XmppCalls.Call> callConstructor=XmppCalls.Call.class.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class);callConstructor.setAccessible(true);
        XmppCalls.Call call=callConstructor.newInstance(UUID.randomUUID().toString(),"bob@example.org","bob@example.org/phone",outgoing,video);
        TLRPC.TL_user user=new TLRPC.TL_user();user.id=20;user.first_name="Bob";
        Constructor<MonogramCallService.Controller> constructor=MonogramCallService.Controller.class.getDeclaredConstructor(bindingClass,XmppCalls.Call.class,TLRPC.User.class);constructor.setAccessible(true);
        return constructor.newInstance(binding,call,user);
    }
    @Test public void notificationActionsAreFencedByAccountUniqueTokenAndCurrentBindingAcrossReplacement() throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);
        MonogramCallService.Controller first=controller(0,false,false,current::get,null);assertTrue(VoIPCallControllers.register(first));
        PendingIntent oldAction=first.action(MonogramCallService.END,false);
        Intent old=Shadows.shadowOf(oldAction).getSavedIntent();assertSame(first,MonogramCallService.matching(old));
        assertNull(MonogramCallService.matching(new Intent(old).putExtra(MonogramCallService.ACCOUNT,1)));
        assertNull(MonogramCallService.matching(new Intent(old).putExtra(MonogramCallService.TOKEN,"wrong-token")));
        current.set(false);assertNull(MonogramCallService.matching(old));current.set(true);
        VoIPCallControllers.unregister(first);
        MonogramCallService.Controller second=controller(0,false,false,() -> true,null);assertTrue(VoIPCallControllers.register(second));
        PendingIntent newAction=second.action(MonogramCallService.END,false);Intent replacement=Shadows.shadowOf(newAction).getSavedIntent();
        assertNotEquals(first.token,second.token);assertNotEquals(oldAction,newAction);
        assertNull(MonogramCallService.matching(old));assertSame(second,MonogramCallService.matching(replacement));
        new MonogramCallActionReceiver().onReceive(application,old);
        assertFalse(second.ended);assertSame(second,VoIPCallControllers.get());
        // Replacing the account binding invalidates actions even while the old controller is still registered.
        controller(0,false,false,() -> true,null);assertNull(MonogramCallService.matching(replacement));
    }
    @Test public void finishNotifiesAndUnregistersOnceWithoutRemovingAReplacementCall() throws Exception {
        MonogramCallService.Controller first=controller(0,false,false,() -> true,null);assertTrue(VoIPCallControllers.register(first));
        List<Integer> states=new ArrayList<>();first.addListener(new VoIPCallController.Listener() {
            @Override public void onStateChanged(int state) { states.add(state); }
        });
        first.finish("decline",false);assertTrue(first.ended);assertNull(VoIPCallControllers.get());
        assertEquals(Arrays.asList(VoIPService.STATE_REQUESTING,VoIPService.STATE_ENDED),states);
        MonogramCallService.Controller replacement=controller(0,true,false,() -> true,null);assertTrue(VoIPCallControllers.register(replacement));
        first.finish("late failure",true);
        assertSame(replacement,VoIPCallControllers.get());assertEquals(2,states.size());
        assertEquals(VoIPService.STATE_ENDED,first.state);assertEquals("decline",first.error);
    }
    @Test public void pendingIncomingMediaDescriptionCannotBeginCaptureBeforeExplicitAcceptance() throws Exception {
        MonogramCallService.Controller incoming=controller(0,false,true,() -> true,null);assertTrue(VoIPCallControllers.register(incoming));
        incoming.service=new MonogramCallService();incoming.remoteOffer=true;incoming.remoteSdp="pending remote offer";
        incoming.prepareMedia();incoming.prepareMedia();
        assertFalse(incoming.accepted);assertFalse(incoming.preparing);assertFalse(incoming.mediaReady);assertNull(incoming.media);
        assertEquals("pending remote offer",incoming.remoteSdp);assertNull(Shadows.shadowOf(application).getNextStartedService());
    }
    @Test public void terminalSummarySeparatesMissedRejectedCompletedAndDisconnectedCalls() {
        assertEquals("missed",MonogramCallService.callSummary("timeout",false,false,false,false));
        assertEquals("hangup",MonogramCallService.callSummary("decline",false,false,false,false));
        assertEquals("busy",MonogramCallService.callSummary("decline",false,true,true,false));
        assertEquals("hangup",MonogramCallService.callSummary("success",false,true,true,true));
        assertEquals("disconnect",MonogramCallService.callSummary("disconnected",true,false,true,true));
        assertEquals("missed",MonogramCallService.callSummary("success",false,true,true,false));
    }
    @Test public void videoMuteBeforeTrackArrivalStaysMutedUntilExplicitUnmute() throws Exception {
        MonogramCallService.Controller c=controller(0,false,true,() -> true,null);
        c.remoteVideoMuted=true;c.updateRemoteVideo();
        assertEquals(org.telegram.messenger.voip.Instance.VIDEO_STATE_INACTIVE,c.getRemoteVideoState());
        c.remoteVideoAvailable=true;c.updateRemoteVideo();
        assertEquals(org.telegram.messenger.voip.Instance.VIDEO_STATE_INACTIVE,c.getRemoteVideoState());
        c.remoteVideoMuted=false;c.updateRemoteVideo();
        assertEquals(org.telegram.messenger.voip.Instance.VIDEO_STATE_ACTIVE,c.getRemoteVideoState());
        c.remoteVideoAvailable=false;c.updateRemoteVideo();
        assertEquals(org.telegram.messenger.voip.Instance.VIDEO_STATE_INACTIVE,c.getRemoteVideoState());
    }
    @Test public void staleServiceStartCannotStopAServiceOwnedByTheReplacementCall() throws Exception {
        MonogramCallService.Controller old=controller(0,true,false,() -> true,null);
        Intent stale=MonogramCallService.intent(application,MonogramCallService.class,old,MonogramCallService.ACCEPT);
        MonogramCallService.Controller replacement=controller(0,true,false,() -> true,null);assertTrue(VoIPCallControllers.register(replacement));
        org.robolectric.android.controller.ServiceController<MonogramCallService> lifecycle=Robolectric.buildService(MonogramCallService.class).create();
        MonogramCallService service=lifecycle.get();
        ReflectionHelpers.setField(service,"controller",replacement);replacement.service=service;
        try {
            assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(stale,0,2));
            assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(null,0,3));
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf());assertFalse(replacement.ended);
            assertSame(replacement,VoIPCallControllers.get());assertSame(service,replacement.service);
            assertSame(replacement,ReflectionHelpers.getField(service,"controller"));
        } finally { replacement.finish("success",false);lifecycle.destroy(); }
    }
    @Test public void microphoneOrCameraPermissionDenialEndsCallWithoutStartingForegroundMedia() throws Exception {
        for(boolean outgoing:new boolean[]{false,true}) {
            String missing=outgoing ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
            Shadows.shadowOf(application).grantPermissions(outgoing ? Manifest.permission.RECORD_AUDIO : Manifest.permission.CAMERA);
            Shadows.shadowOf(application).denyPermissions(missing);
            XmppSession session=MonogramScreenWakeTest.session();ReflectionHelpers.setField(session,"calls",new XmppCalls(null));
            MonogramCallService.Controller c=controller(0,outgoing,true,() -> true,session);assertTrue(VoIPCallControllers.register(c));
            Intent intent=MonogramCallService.intent(application,MonogramCallPermissionActivity.class,c,MonogramCallService.ACCEPT);
            org.robolectric.android.controller.ActivityController<MonogramCallPermissionActivity> lifecycle=Robolectric.buildActivity(MonogramCallPermissionActivity.class,intent).create();
            MonogramCallPermissionActivity activity=lifecycle.get();
            assertFalse(c.accepted);assertNull(c.media);assertNull(Shadows.shadowOf(application).getNextStartedService());
            int request=ReflectionHelpers.getStaticField(MonogramCallPermissionActivity.class,"REQUEST");
            activity.onRequestPermissionsResult(request,new String[]{missing},new int[]{PackageManager.PERMISSION_DENIED});
            assertTrue(activity.isFinishing());assertTrue(c.ended);assertFalse(c.accepted);assertFalse(c.preparing);assertNull(c.media);
            assertNull(VoIPCallControllers.get());assertNull(Shadows.shadowOf(application).getNextStartedService());
            lifecycle.destroy();
        }
        assertEquals(1,Signalling.declines);assertEquals(1,Signalling.ends);
    }
}
