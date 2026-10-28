package org.telegram.messenger.xmpp;

import android.app.Application;
import android.content.Context;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.messenger.voip.*;
import org.telegram.tgnet.*;
import org.webrtc.*;
import com.negzero.monogram.xmpp.calls.XmppMuji;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=Application.class,shadows={
    MonogramMujiNativeUiTest.Messages.class,MonogramNotificationSettingsTest.Clock.class,
    MonogramNotificationSettingsTest.Base.class,MonogramMujiNativeUiTest.Objects.class,
    MonogramMujiNativeUiTest.Media.class})
public class MonogramMujiNativeUiTest {
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Messages extends MonogramNotificationSettingsTest.Base {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void __constructor__(int account) {}
        @Implementation public static MessagesController getInstance(int account){return ReflectionHelpers.callConstructor(MessagesController.class,ReflectionHelpers.ClassParameter.from(int.class,account));}
        @Implementation public TLRPC.Chat getChat(Long id){TLRPC.TL_chat chat=new TLRPC.TL_chat();chat.id=id;chat.title="Room";return chat;}
        @Implementation public TLRPC.User getUser(Long id){return user(id);}
    }
    @Implements(value=MessageObject.class,isInAndroidSdk=false)
    public static class Objects { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=MonogramMujiMedia.class,isInAndroidSdk=false)
    public static class Media {
        static VideoSink local;static final Map<String,VideoSink> sinks=new HashMap<>();
        static final Map<String,Double> volumes=new HashMap<>();
        @Implementation public void __constructor__(Context context,MonogramMujiMedia.Listener listener) {}
        @Implementation public void localSink(VideoSink sink){local=sink;}
        @Implementation public void sink(String address,VideoSink sink){sinks.put(address,sink);}
        @Implementation public void volume(String address,double volume){volumes.put(address,volume);}
        @Implementation public void muted(boolean value){}
        @Implementation public void videoEnabled(boolean value){}
        @Implementation public EglBase.Context egl(){return null;}
        @Implementation public void close(){}
        @Implementation public CompletableFuture<Void> disposed(){return CompletableFuture.completedFuture(null);}
    }
    private ServiceController<MonogramMujiService> lifecycle;
    private MonogramMujiService service;
    private MonogramMujiCall model;
    @Before public void setup(){
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        lifecycle=Robolectric.buildService(MonogramMujiService.class).create();service=lifecycle.get();
        service.account=0;service.peer=-7;service.title="Room";service.video=true;service.cameraEnabled=true;
        model=new MonogramMujiCall(0,-7,10,"Room",true);service.groupCall=model;
        ReflectionHelpers.setField(service,"media",new MonogramMujiMedia(RuntimeEnvironment.getApplication(),null));
        Media.local=null;Media.sinks.clear();Media.volumes.clear();
    }
    @After public void cleanup(){if(lifecycle!=null)lifecycle.destroy();assertNull(MonogramMujiService.current);}
    private static TLRPC.User user(long id){TLRPC.TL_user user=new TLRPC.TL_user();user.id=id;user.first_name="User "+id;return user;}
    private static XmppMuji.Participant participant(String address)throws Exception{
        Constructor<XmppMuji.Participant> c=XmppMuji.Participant.class.getDeclaredConstructor(String.class,String.class,boolean.class,boolean.class);c.setAccessible(true);
        return c.newInstance("room@example.org/"+address,address,false,true);
    }
    @SuppressWarnings("unchecked") private void remote(String address,long user)throws Exception{
        ((Map<String,TLRPC.User>)ReflectionHelpers.getField(service,"users")).put(address,user(user));
    }
    private void project(){ReflectionHelpers.callInstanceMethod(service,"projectParticipants");}
    @Test public void nativeGroupUiFindsMujiServiceWithoutRegisteringAPrivateOrTelegramCall(){
        assertSame(service,VoIPService.getSharedInstance());assertSame(service,VoIPService.getSharedState());
        assertTrue(service.isMonogramGroupCall());assertNull(VoIPCallControllers.get());
        assertEquals(0,service.getAccount());assertEquals(-7,service.getPeerId());assertEquals(10,service.getSelfId());
        assertNull(ReflectionHelpers.getField(service,"cpuWakelock"));
        assertEquals(VoIPCallController.SECURITY_DTLS_UNVERIFIED,service.getSecurityPresentation());
    }
    @Test public void nativeModelGroupsResourcesPreservesRowsAndRemovesDepartedPeople()throws Exception{
        String phone="bob@example.org/phone",desktop="bob@example.org/desktop";
        service.participants=Arrays.asList(participant(phone),participant(desktop));remote(phone,20);remote(desktop,20);
        service.remoteVideo.add(desktop);project();
        assertEquals(2,model.participants.size());assertEquals(2,model.call.participants_count);
        TLRPC.GroupCallParticipant bob=model.participants.get(20);assertEquals(desktop,bob.videoEndpoint);
        assertTrue(bob.can_self_unmute);assertEquals(10000,bob.volume);
        project();assertSame(bob,model.participants.get(20));
        service.participants=Collections.emptyList();((Map<?,?>)ReflectionHelpers.getField(service,"users")).clear();project();
        assertEquals(1,model.participants.size());assertNull(model.participants.get(20));assertTrue(model.participants.get(10).self);
    }
    @Test public void nativeMicCameraAndPerPersonVolumeControlsUseMujiOnly()throws Exception{
        String peer="bob@example.org/phone";service.participants=Collections.singletonList(participant(peer));remote(peer,20);project();
        Constructor<com.negzero.monogram.xmpp.calls.XmppCalls.Call> c=com.negzero.monogram.xmpp.calls.XmppCalls.Call.class.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class);c.setAccessible(true);
        com.negzero.monogram.xmpp.calls.XmppCalls.Call leg=c.newInstance("sid", "bob@example.org",peer,true,true);
        ((Map<String,com.negzero.monogram.xmpp.calls.XmppCalls.Call>)ReflectionHelpers.getField(service,"mediaCalls")).put(peer+"\nsid",leg);
        service.setMicMute(true,false,true);assertTrue(service.isMicMute());assertTrue(model.participants.get(10).muted);
        service.setVideoState(false,Instance.VIDEO_STATE_INACTIVE);assertEquals(Instance.VIDEO_STATE_INACTIVE,service.getVideoState(false));
        service.setVideoState(true,Instance.VIDEO_STATE_ACTIVE);assertEquals(Instance.VIDEO_STATE_INACTIVE,service.getVideoState(true));
        service.editCallMember(user(20),true,null,null,null,null);assertTrue(model.participants.get(20).muted_by_you);assertEquals(0.0,Media.volumes.get(peer+"\nsid"),0.0);
        service.editCallMember(user(20),false,null,7500,null,null);assertFalse(model.participants.get(20).muted_by_you);assertEquals(7500,model.participants.get(20).volume);assertEquals(0.75,Media.volumes.get(peer+"\nsid"),0.0);
        assertNull(model.getInputGroupCall());assertNull(model.getInputGroupCall(false));model.loadMembers(true);model.reloadGroupCall();
    }
    @Test public void leavingReleasesTheSelectedCommunicationDeviceAndRestoresAudioMode(){
        android.media.AudioManager audio=(android.media.AudioManager)RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
        android.media.AudioDeviceInfo speaker=org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder().setType(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build();
        Shadows.shadowOf(audio).setAvailableCommunicationDevices(Collections.singletonList(speaker));
        ReflectionHelpers.setField(service,"audio",audio);
        ReflectionHelpers.setField(service,"oldMode",android.media.AudioManager.MODE_NORMAL);
        ReflectionHelpers.setField(service,"oldSpeaker",false);
        audio.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
        service.setAudioOutput(VoIPCallController.AUDIO_OUTPUT_SPEAKER);assertSame(speaker,audio.getCommunicationDevice());
        lifecycle.destroy();lifecycle=null;
        assertNull(audio.getCommunicationDevice());assertEquals(android.media.AudioManager.MODE_NORMAL,audio.getMode());assertFalse(audio.isSpeakerphoneOn());
    }
    @Test public void rendererTargetsDetachSynchronouslyBeforeMediaDisposal(){
        AtomicInteger frames=new AtomicInteger();service.setLocalSink(frame->frames.incrementAndGet(),false);
        VideoSink captured=Media.local;assertNotNull(captured);captured.onFrame(null);assertEquals(1,frames.get());
        service.setLocalSink(null,false);captured.onFrame(null);assertEquals(1,frames.get());
        TLRPC.GroupCallParticipant p=model.participant(20,false);MonogramMujiCall.video(p,"bob@example.org/phone",true);
        VoIPService.ProxyVideoSink sink=service.addRemoteSink(p,false,frame->frames.incrementAndGet(),null);
        sink.onFrame(null);assertEquals(2,frames.get());service.clearRemoteSinks();sink.onFrame(null);assertEquals(2,frames.get());
    }
}
