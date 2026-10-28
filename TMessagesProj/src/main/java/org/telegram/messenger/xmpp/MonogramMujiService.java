// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.negzero.monogram.xmpp.XmppSession;
import com.negzero.monogram.xmpp.calls.*;
import java.util.*;
import java.util.concurrent.*;
import org.telegram.messenger.*;
import org.telegram.messenger.voip.VoIPCallControllers;
import org.telegram.messenger.voip.VoIPCallController;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.messenger.voip.Instance;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.LaunchActivity;
import org.webrtc.*;

/** The user explicitly joins before foreground microphone/camera capture starts. */
public final class MonogramMujiService extends VoIPService {
    private static final String CHANNEL="monogram_group_calls",END="end";private static final int NOTICE=1044;
    public static volatile MonogramMujiService current;private static volatile boolean closing;
    private static int endedAccount=-1;private static long endedPeer;private static String endedError;
    public static String lastError(int account,long peer){return endedAccount==account && endedPeer==peer?endedError:null;}
    public int account;public long peer;public String title,status="Connecting…",error;public boolean video,muted,cameraEnabled;
    public List<XmppMuji.Participant> participants=Collections.emptyList();
    public final Set<String> connected=new HashSet<>(),remoteVideo=new HashSet<>();
    public final Map<String,String> peerErrors=new HashMap<>();
    private final String token=UUID.randomUUID().toString();
    private final Map<String,XmppCalls.Call> mediaCalls=new ConcurrentHashMap<>();
    private final Map<String,TLRPC.User> users=new HashMap<>();
    private final Set<String> resolvingUsers=new HashSet<>();
    private final Set<String> remoteMuted=new HashSet<>(),remoteCameraMuted=new HashSet<>();
    private final ArrayList<VoIPCallController.Listener> uiListeners=new ArrayList<>();
    private final Map<String,ProxyVideoSink> videoSinks=new HashMap<>();
    private final ProxyVideoSink localVideoSink=new ProxyVideoSink();
    private boolean frontCamera=true,switchingCamera;
    private long startedAt;private boolean foregroundStarted;
    private static String mediaKey(XmppCalls.Call call){return call.fullPeer+"\n"+call.sid;}
    private XmppSession session;private MonogramMujiMedia media;private boolean joined,destroyed,initialized;
    private AudioManager.OnAudioFocusChangeListener legacyFocus;
    private AudioManager audio;private int oldMode;private boolean oldSpeaker;private AudioFocusRequest focus;private PowerManager.WakeLock wake;
    private final ExecutorService setup=Executors.newSingleThreadExecutor();
    public static boolean active(){return closing || current!=null && !current.destroyed;}
    static void requireDirectNetwork(){if(MonogramProxy.current()!=null)throw new IllegalStateException("Group calls over the configured proxy are not supported.");}
    public static void start(Context context,int account,long peer,String title,boolean video){
        requireDirectNetwork();
        if(active() || VoIPCallControllers.get()!=null)throw new IllegalStateException("Another call is active");
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED || video && context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Media permission required");
        Intent intent=new Intent(context,MonogramMujiService.class).putExtra("account",account).putExtra("peer",peer).putExtra("title",title).putExtra("video",video);
        ContextCompat.startForegroundService(context,intent);
    }
    @Override public void onCreate(){super.onCreate();current=this;NotificationCenter.getGlobalInstance().addObserver(this,NotificationCenter.proxySettingsChanged);}
    @Override public void didReceivedNotification(int id,int account,Object... args){try{requireDirectNetwork();}catch(IllegalStateException changed){fail("Group call ended because proxy settings changed.");}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(END.equals(intent.getAction())){if(token.equals(intent.getStringExtra("token")))stopSelf();else if(!initialized)stopSelf();return START_NOT_STICKY;}
        if(initialized)return START_NOT_STICKY;initialized=true;
        account=intent.getIntExtra("account",-1);peer=intent.getLongExtra("peer",0);title=intent.getStringExtra("title");video=intent.getBooleanExtra("video",false);cameraEnabled=video;
        try {
            requireDirectNetwork();
            if(account<0 || account>=UserConfig.MAX_ACCOUNT_COUNT || peer>=0 || VoIPCallControllers.get()!=null)throw new IllegalStateException("Call unavailable");
            if(title==null)title="Group call";
            notifyForeground();
            session=MonogramController.get(account).session();configureAudio();
            groupCall=new MonogramMujiCall(account,peer,MonogramController.get(account).ownUser().id,title,video);
            startedAt=SystemClock.elapsedRealtime();changed();
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voipServiceCreated);
            session.muji.setListener(new XmppMuji.Listener(){
                public void conference(String room,List<XmppMuji.Participant> list,String failure){ui(()->{
                    participants=list;
                    if(failure!=null){fail(failure);return;}
                    if(room==null && (joined || !session.connection().isAuthenticated())){stopSelf();return;}
                    if(room!=null){joined=true;status=connected.isEmpty()?"Waiting for participants…":"In call";}
                    resolveParticipants();changed();
                });}
                public void remoteMediaState(XmppCalls.Call call,boolean audioMuted,boolean videoMuted){ui(()->{
                    XmppCalls active=leg(call.fullPeer);if(active==null || active.current()==null || !active.current().sid.equals(call.sid))return;
                    if(audioMuted)remoteMuted.add(call.fullPeer);else remoteMuted.remove(call.fullPeer);
                    if(videoMuted)remoteCameraMuted.add(call.fullPeer);else remoteCameraMuted.remove(call.fullPeer);changed();
                });}
                public void localDescriptionRequested(XmppCalls.Call call,boolean offer){if(offer)withNetwork(call,()->media.offer(mediaKey(call),call.video));}
                public void remoteDescription(XmppCalls.Call call,boolean offer,String sdp){if(offer)withNetwork(call,()->media.answer(mediaKey(call),call.video,sdp));else ui(()->media.answerReceived(mediaKey(call),sdp));}
                public void remoteCandidate(XmppCalls.Call call,String mid,int line,String candidate){ui(()->media.candidate(mediaKey(call),mid,line,candidate));}
                public void state(XmppCalls.Call call,XmppCalls.State state,String reason){ui(()->{
                    if(state==XmppCalls.State.ENDED){connected.remove(call.fullPeer);remoteVideo.remove(call.fullPeer);remoteMuted.remove(call.fullPeer);remoteCameraMuted.remove(call.fullPeer);media.remove(mediaKey(call));mediaCalls.remove(mediaKey(call));if(reason!=null && !Arrays.asList("success","remote-ended","closed").contains(reason))peerErrors.put(call.fullPeer,reason);}
                    sendMute();changed();
                });}
            });
            media=new MonogramMujiMedia(this,new MonogramMujiMedia.Listener(){
                public void ready(){ui(MonogramMujiService.this::changed);setup.execute(()->{try{session.blocking.list();session.muji.join(MonogramController.get(account).jid(peer),video).get(20,TimeUnit.SECONDS);}catch(Exception failed){ui(()->fail("Could not join group call: "+category(failed)));}});}
                public void description(String address,boolean offer,String sdp){XmppCalls engine=mediaLeg(address);if(engine!=null){XmppCalls.Call call=engine.current();if(call!=null)engine.localDescription(call.sid,offer,sdp);}}
                public void candidate(String address,String mid,int line,String value){XmppCalls engine=mediaLeg(address);if(engine!=null){XmppCalls.Call call=engine.current();if(call!=null)engine.candidate(call.sid,mid,line,value);}}
                public void remoteApplied(String address){XmppCalls engine=mediaLeg(address);if(engine!=null){XmppCalls.Call call=engine.current();if(call!=null)engine.remoteDescriptionApplied(call.sid);}}
                public void connected(String address){XmppCalls engine=mediaLeg(address);if(engine!=null){XmppCalls.Call call=engine.current();if(call!=null)engine.mediaConnected(call.sid);}ui(()->{XmppCalls.Call call=mediaCalls.get(address);if(call==null)return;connected.add(call.fullPeer);peerErrors.remove(call.fullPeer);status="In call";changed();});}
                public void failed(String address,String reason){ui(()->{if(address==null){fail(reason);return;}XmppCalls.Call source=mediaCalls.get(address);if(source==null)return;peerErrors.put(source.fullPeer,reason);XmppCalls engine=mediaLeg(address);if(engine!=null){XmppCalls.Call call=engine.current();if(call!=null)engine.mediaFailed(call.sid,reason);}media.remove(address);changed();});}
                public void video(String address,boolean available){ui(()->{XmppCalls.Call source=mediaCalls.get(address);if(source==null)return;if(available)remoteVideo.add(source.fullPeer);else remoteVideo.remove(source.fullPeer);changed();});}
            });
            session.calls.iceServers().whenComplete((servers,failure)->ui(()->{
                if(failure!=null){fail("Could not load call network settings");return;}
                ArrayList<PeerConnection.IceServer> ice=new ArrayList<>();for(XmppIceServers.IceServer server:servers){PeerConnection.IceServer.Builder builder=PeerConnection.IceServer.builder(server.urls);if(server.username!=null)builder.setUsername(server.username);if(server.password!=null)builder.setPassword(server.password);ice.add(builder.createIceServer());}
                media.prepare(video,ice,false);
            }));
        }catch(Exception failure){fail("Could not start group call: "+category(failure));}
        return START_NOT_STICKY;
    }
    private static String category(Throwable failure){while(failure.getCause()!=null)failure=failure.getCause();return failure instanceof IllegalStateException?failure.getMessage():failure.getClass().getSimpleName();}
    private XmppCalls mediaLeg(String key){XmppCalls.Call expected=mediaCalls.get(key);if(expected==null)return null;XmppCalls engine=leg(expected.fullPeer);XmppCalls.Call current=engine==null?null:engine.current();return current!=null && current.sid.equals(expected.sid)?engine:null;}
    private XmppCalls leg(String address){return destroyed || session==null?null:session.muji.leg(address);}
    private void ui(Runnable work){AndroidUtilities.runOnUIThread(()->{if(!destroyed && current==this)work.run();});}
    private void withNetwork(XmppCalls.Call call,Runnable work){ui(()->{
        String bare;try{bare=org.jxmpp.jid.impl.JidCreate.from(call.fullPeer).asBareJid().toString();}catch(Exception invalid){return;}
        MonogramController.get(account).resolveCallUser(bare,false,session,user->{
            if(destroyed || current!=this)return;
            if(user==null){peerErrors.put(call.fullPeer,"Participant unavailable");changed();return;}
            users.put(call.fullPeer,user);changed();
            MonogramController.get(account).allowDirectCall(user.id).whenComplete((direct,failure)->ui(()->{
                XmppCalls active=leg(call.fullPeer);if(active==null || active.current()==null || !active.current().sid.equals(call.sid))return;
                if(failure!=null){peerErrors.put(call.fullPeer,"Call privacy settings unavailable");changed();return;}
                mediaCalls.put(mediaKey(call),call);media.network(mediaKey(call),Boolean.TRUE.equals(direct));TLRPC.GroupCallParticipant p=groupCall.participants.get(user.id);if(p!=null)media.volume(mediaKey(call),p.muted_by_you?0:p.volume/10000.0);work.run();
            }));
        });
    });}
    private void configureAudio(){
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);oldMode=audio.getMode();oldSpeaker=audio.isSpeakerphoneOn();
        AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        if(Build.VERSION.SDK_INT>=26){focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS)stopSelf();else if(media!=null)media.muted(muted || change<0);}).build();if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("Audio focus unavailable");}
        else {legacyFocus=change->{if(change==AudioManager.AUDIOFOCUS_LOSS)stopSelf();else if(media!=null)media.muted(muted || change<0);};if(audio.requestAudioFocus(legacyFocus,AudioManager.STREAM_VOICE_CALL,AudioManager.AUDIOFOCUS_GAIN)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("Audio focus unavailable");}
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean headset=false;for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(device.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET || device.getType()==AudioDeviceInfo.TYPE_WIRED_HEADPHONES || device.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.getType()==AudioDeviceInfo.TYPE_USB_HEADSET)headset=true;
        if(!headset)audio.setSpeakerphoneOn(true);
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"monogram:muji");wake.acquire();
    }
    private void notifyForeground(){
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Group calls",NotificationManager.IMPORTANCE_LOW));
        Intent end=new Intent(this,MonogramMujiService.class).setAction(END).putExtra("token",token);
        PendingIntent hangup=PendingIntent.getService(this,NOTICE,end,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent open=new Intent(this,LaunchActivity.class).setAction("voip_chat").putExtra("currentAccount",account).putExtra("chatId",-peer);
        PendingIntent content=PendingIntent.getActivity(this,NOTICE,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.notification).setContentTitle(title).setContentText("Group call · "+status).setContentIntent(content).setOngoing(true).setCategory(NotificationCompat.CATEGORY_CALL).addAction(R.drawable.msg_cancel,"Leave",hangup).build();
        if(foregroundStarted)manager.notify(NOTICE,notification);
        else {if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|(video?ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA:0));else startForeground(NOTICE,notification);foregroundStarted=true;}
    }
    public EglBase.Context egl(){return media==null?null:media.egl();}
    public void sink(String peer,VideoSink sink){XmppCalls leg=leg(peer);XmppCalls.Call call=leg==null?null:leg.current();if(media!=null && call!=null)media.sink(mediaKey(call),sink);}
    public void setMuted(boolean value){muted=value;if(media!=null)media.muted(value);sendMute();changed();}
    public boolean speaker(){return audio!=null && audio.isSpeakerphoneOn();}
    public void speaker(boolean value){if(audio!=null)audio.setSpeakerphoneOn(value);changed();}
    public void setCamera(boolean value){cameraEnabled=value;if(media!=null)media.videoEnabled(value);sendMute();changed();}
    private void sendMute(){if(session!=null)for(XmppMuji.Participant p:participants){XmppCalls e=leg(p.realJid);if(e!=null && e.current()!=null)e.mediaMuted(e.current().sid,muted,!cameraEnabled);}}
    @Override public boolean isMonogramGroupCall(){return true;}
    @Override public int getAccount(){return account;}
    @Override public long getPeerId(){return peer;}
    @Override public TLRPC.User getUser(){return null;}
    @Override public TLRPC.Chat getChat(){return MessagesController.getInstance(account).getChat(-peer);}
    @Override public long getSelfId(){return groupCall==null?0:groupCall.selfPeer.user_id;}
    @Override public String getTitle(){return title;}
    @Override public TLRPC.InputPeer getGroupCallPeer(){TLRPC.TL_inputPeerUser p=new TLRPC.TL_inputPeerUser();p.user_id=getSelfId();return p;}
    @Override public void setGroupCallPeer(TLRPC.InputPeer peer){}
    @Override public boolean isJoined(){return joined && !destroyed;}
    @Override public boolean isHangingUp(){return destroyed || error!=null;}
    @Override public int getCallState(){return destroyed?STATE_ENDED:error!=null?STATE_FAILED:joined?STATE_ESTABLISHED:STATE_CREATING;}
    @Override public long getCallDuration(){return Math.max(0,SystemClock.elapsedRealtime()-startedAt);}
    @Override public String getLastError(){return error;}
    @Override public boolean isOutgoing(){return true;}
    @Override public int getCapabilities(){return CAP_PIP|(video?CAP_VIDEO:0);}
    @Override public int getSecurityPresentation(){return SECURITY_DTLS_UNVERIFIED;}
    @Override public boolean isCallingVideo(){return video;}
    @Override public boolean isVideoAvailable(){return video;}
    @Override public boolean hasVideoCapturer(){return video && media!=null;}
    @Override public boolean isMicMute(){return muted;}
    @Override public void setMicMute(boolean value){setMuted(value);}
    @Override public void setMicMute(boolean value,boolean hold,boolean send){setMuted(value);}
    @Override public void addListener(VoIPCallController.Listener listener){if(!uiListeners.contains(listener))uiListeners.add(listener);listener.onStateChanged(getCallState());}
    @Override public void removeListener(VoIPCallController.Listener listener){uiListeners.remove(listener);}
    @Override public void hangUp(){stopSelf();}
    @Override public void hangUp(int discard){stopSelf();}
    @Override public void hangUp(int discard,Runnable done){hangUp(done);}
    @Override public void hangUp(Runnable done){stopSelf();if(done!=null)done.run();}
    @Override public EglBase.Context getEglContext(){return egl();}
    @Override public int getVideoState(boolean screen){return !screen && video && cameraEnabled?Instance.VIDEO_STATE_ACTIVE:Instance.VIDEO_STATE_INACTIVE;}
    @Override public void setVideoState(boolean screen,int state){if(!screen && video)setCamera(state==Instance.VIDEO_STATE_ACTIVE);}
    @Override public void createCaptureDevice(boolean screen){}
    @Override public void setupCaptureDevice(boolean screen,boolean mic){if(!screen && video){setMuted(!mic);setCamera(true);}}
    @Override public void clearCamera(){setCamera(false);}
    @Override public boolean isFrontFaceCamera(){return frontCamera;}
    @Override public boolean isSwitchingCamera(){return switchingCamera;}
    @Override public void switchCamera(){if(media==null || !video || switchingCamera)return;switchingCamera=true;media.switchCamera(new CameraVideoCapturer.CameraSwitchHandler(){
        public void onCameraSwitchDone(boolean front){ui(()->{switchingCamera=false;frontCamera=front;for(VoIPCallController.Listener l:new ArrayList<>(uiListeners))l.onCameraSwitch(front);});}
        public void onCameraSwitchError(String reason){ui(()->{switchingCamera=false;});}
    });}
    @Override public void setLocalSink(VideoSink local,boolean screen){if(screen)return;localVideoSink.setTarget(local);if(media!=null)media.localSink(localVideoSink);}
    @Override public void setSinks(VideoSink local,VideoSink remote){setLocalSink(local,false);}
    @Override public void setSinks(VideoSink local,boolean screen,VideoSink remote){setLocalSink(local,screen);}
    @Override public ProxyVideoSink addRemoteSink(TLRPC.GroupCallParticipant p,boolean screen,VideoSink target,VideoSink background){
        if(screen || p.videoEndpoint==null || media==null)return null;
        ProxyVideoSink sink=videoSinks.get(p.videoEndpoint);
        if(sink==null){sink=new ProxyVideoSink();videoSinks.put(p.videoEndpoint,sink);}
        if(target!=null)sink.setTarget(target);if(background!=null)sink.setBackground(background);
        sink(p.videoEndpoint,sink);return sink;
    }
    @Override public void removeRemoteSink(TLRPC.GroupCallParticipant p,boolean screen){if(screen)return;ProxyVideoSink sink=videoSinks.remove(p.videoEndpoint);if(sink!=null){sink.setTarget(null);sink.setBackground(null);sink(p.videoEndpoint,null);}}
    @Override public void clearRemoteSinks(){for(Map.Entry<String,ProxyVideoSink> entry:videoSinks.entrySet()){entry.getValue().setTarget(null);entry.getValue().setBackground(null);sink(entry.getKey(),null);}videoSinks.clear();}
    @Override public void checkVideoFrame(TLRPC.GroupCallParticipant participant,boolean screen){}
    @Override public void requestFullScreen(TLRPC.GroupCallParticipant p,boolean full,boolean screen){}
    @Override public boolean isFullscreen(TLRPC.GroupCallParticipant p,boolean screen){return false;}
    @Override public void setParticipantVolume(TLRPC.GroupCallParticipant p,int volume){
        p.volume=Math.max(0,Math.min(20000,volume));
        long id=MessageObject.getPeerId(p.peer);
        for(Map.Entry<String,XmppCalls.Call> entry:mediaCalls.entrySet()){
            TLRPC.User user=users.get(entry.getValue().fullPeer);
            if(user!=null && user.id==id && media!=null)media.volume(entry.getKey(),p.muted_by_you?0:p.volume/10000.0);
        }
    }
    @Override public void editCallMember(TLObject object,Boolean mute,Boolean video,Integer volume,Boolean hand,Runnable done){
        if(object instanceof TLRPC.User && groupCall!=null){TLRPC.GroupCallParticipant p=groupCall.participants.get(((TLRPC.User)object).id);
            if(p!=null && !p.self){if(mute!=null)p.muted_by_you=mute;if(volume!=null)p.volume=volume;setParticipantVolume(p,p.volume);changed();}
        }
        if(done!=null)done.run();
    }
    @Override public void playConnectedSound(){}
    @Override public void playAllowTalkSound(){}
    @Override public void playStartRecordSound(){}
    @Override public void setNoiseSupressionEnabled(boolean enabled){}
    @Override public boolean isSpeakerphoneOn(){return speaker();}
    @Override public boolean isBluetoothOn(){return audio!=null && audio.isBluetoothScoOn();}
    @Override public boolean isBluetoothWillOn(){return false;}
    private boolean outputDevice(int type){if(audio!=null)for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getType()==type)return true;return false;}
    @Override public boolean isBluetoothHeadsetConnected(){return outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);}
    @Override public boolean isHeadsetPlugged(){return outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET)||outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)||outputDevice(AudioDeviceInfo.TYPE_USB_HEADSET);}
    @Override public boolean hasEarpiece(){return outputDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);}
    @Override public int getCurrentAudioRoute(){return isBluetoothOn()?AUDIO_ROUTE_BLUETOOTH:speaker()?AUDIO_ROUTE_SPEAKER:AUDIO_ROUTE_EARPIECE;}
    @Override public int getAudioOutput(){return isBluetoothOn()?AUDIO_OUTPUT_BLUETOOTH:speaker()?AUDIO_OUTPUT_SPEAKER:AUDIO_OUTPUT_EARPIECE;}
    @Override public void switchToSpeaker(){setAudioOutput(AUDIO_OUTPUT_SPEAKER);}
    @Override public void setAudioOutput(int output){
        if(audio==null)return;
        try {
            if(Build.VERSION.SDK_INT>=31){int type=output==AUDIO_OUTPUT_SPEAKER?AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:output==AUDIO_OUTPUT_BLUETOOTH?AudioDeviceInfo.TYPE_BLUETOOTH_SCO:AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
                for(AudioDeviceInfo d:audio.getAvailableCommunicationDevices())if(d.getType()==type || output==AUDIO_OUTPUT_EARPIECE && (d.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET || d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET)){audio.setCommunicationDevice(d);changed();return;}
            }
            if(output==AUDIO_OUTPUT_BLUETOOTH){audio.startBluetoothSco();audio.setBluetoothScoOn(true);audio.setSpeakerphoneOn(false);}
            else {if(audio.isBluetoothScoOn()){audio.stopBluetoothSco();audio.setBluetoothScoOn(false);}audio.setSpeakerphoneOn(output==AUDIO_OUTPUT_SPEAKER);}
        }catch(SecurityException denied){/* The system retains the available route. */}
        changed();
    }
    @Override public void toggleSpeakerphoneOrShowRouteSheet(Context context,boolean overlay){toggleSpeakerphoneOrShowRouteSheet(context,overlay,null);}
    @Override public void toggleSpeakerphoneOrShowRouteSheet(Context context,boolean overlay,Integer selected){
        ArrayList<CharSequence> labels=new ArrayList<>();ArrayList<Integer> routes=new ArrayList<>();
        labels.add(LocaleController.getString(R.string.VoipAudioRoutingSpeaker));routes.add(AUDIO_OUTPUT_SPEAKER);
        if(hasEarpiece() || isHeadsetPlugged()){labels.add(LocaleController.getString(isHeadsetPlugged()?R.string.VoipAudioRoutingHeadset:R.string.VoipAudioRoutingEarpiece));routes.add(AUDIO_OUTPUT_EARPIECE);}
        if(isBluetoothHeadsetConnected()){labels.add(LocaleController.getString(R.string.VoipAudioRoutingBluetooth));routes.add(AUDIO_OUTPUT_BLUETOOTH);}
        new org.telegram.ui.ActionBar.BottomSheet.Builder(context).setTitle(LocaleController.getString(R.string.VoipOutputDevices),true)
            .setItems(labels.toArray(new CharSequence[0]),(dialog,which)->setAudioOutput(routes.get(which))).show();
    }
    private boolean groupCallUiOpen(){return org.telegram.ui.GroupCallActivity.groupCallInstance!=null && org.telegram.ui.GroupCallActivity.groupCallInstance.call==groupCall;}
    private void resolveParticipants(){
        Set<String> present=new HashSet<>();for(XmppMuji.Participant p:participants)present.add(p.realJid);
        users.keySet().retainAll(present);
        for(String address:present)if(!users.containsKey(address) && resolvingUsers.add(address)){
            String bare=address.substring(0,address.indexOf('/'));
            MonogramController.get(account).resolveCallUser(bare,false,session,user->{
                resolvingUsers.remove(address);if(destroyed || current!=this || user==null)return;
                for(XmppMuji.Participant p:participants)if(p.realJid.equals(address)){users.put(address,user);changed();break;}
            });
        }
    }
    private void projectParticipants(){
        if(!(groupCall instanceof MonogramMujiCall))return;
        MonogramMujiCall model=(MonogramMujiCall)groupCall;
        Set<Long> present=new HashSet<>();present.add(getSelfId());
        TLRPC.GroupCallParticipant self=model.participant(getSelfId(),true);self.muted=muted;
        MonogramMujiCall.video(self,"muji-self",cameraEnabled && video && egl()!=null);
        // Telegram represents a person once; multiple XMPP resources share that person's row.
        Map<Long,String> endpoints=new LinkedHashMap<>();
        for(XmppMuji.Participant p:participants){TLRPC.User user=users.get(p.realJid);if(user==null)continue;
            present.add(user.id);String prior=endpoints.get(user.id);
            if(prior==null || !remoteVideo.contains(prior) && remoteVideo.contains(p.realJid))endpoints.put(user.id,p.realJid);
        }
        for(Map.Entry<Long,String> endpoint:endpoints.entrySet()){
            TLRPC.GroupCallParticipant p=model.participant(endpoint.getKey(),false);
            p.about=peerErrors.get(endpoint.getValue());p.muted=true;
            for(Map.Entry<String,TLRPC.User> resource:users.entrySet())if(resource.getValue().id==endpoint.getKey() && !remoteMuted.contains(resource.getKey()))p.muted=false;
            MonogramMujiCall.video(p,endpoint.getValue(),remoteVideo.contains(endpoint.getValue()) && !remoteCameraMuted.contains(endpoint.getValue()));
        }
        for(int i=model.participants.size()-1;i>=0;i--)if(!present.contains(model.participants.keyAt(i)))model.participants.removeAt(i);
        model.sortedParticipants.clear();for(int i=0;i<model.participants.size();i++)model.sortedParticipants.add(model.participants.valueAt(i));
        model.call.participants_count=model.participants.size();model.canStreamVideo=video && egl()!=null;model.sortParticipants();
        Set<String> visible=new HashSet<>();for(TLRPC.GroupCallParticipant p:model.sortedParticipants)if(!p.self && p.videoEndpoint!=null)visible.add(p.videoEndpoint);
        for(String endpoint:new ArrayList<>(videoSinks.keySet()))if(!visible.contains(endpoint)){
            ProxyVideoSink sink=videoSinks.remove(endpoint);sink.setTarget(null);sink.setBackground(null);sink(endpoint,null);
        }
    }
    private void changed(){
        if(account<0 || account>=UserConfig.MAX_ACCOUNT_COUNT)return;
        projectParticipants();
        if(foregroundStarted && !destroyed)notifyForeground();
        for(VoIPCallController.Listener listener:new ArrayList<>(uiListeners)){
            listener.onStateChanged(getCallState());listener.onAudioSettingsChanged();
        }
        NotificationCenter center=NotificationCenter.getInstance(account);
        if(groupCall!=null)center.postNotificationName(NotificationCenter.groupCallUpdated,-peer,groupCall.call.id,false);
        center.postNotificationName(NotificationCenter.monogramGroupCallChanged);
    }
    private void fail(String reason){error=reason;status=reason;changed();stopSelf();}
    @Override public void onDestroy(){
        NotificationCenter.getGlobalInstance().removeObserver(this,NotificationCenter.proxySettingsChanged);
        if(media!=null)closing=true;
        destroyed=true;endedAccount=account;endedPeer=peer;endedError=error;
        if(session!=null){session.muji.setListener(null);session.muji.leave();}
        if(groupCallUiOpen())org.telegram.ui.GroupCallActivity.groupCallInstance.dismiss();
        clearRemoteSinks();localVideoSink.setTarget(null);if(media!=null)media.localSink(null);
        changed();current=null;NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didEndCall);
        uiListeners.clear();if(media!=null){media.close();media.disposed().whenComplete((done,failure)->closing=false);}setup.shutdownNow();
        if(wake!=null && wake.isHeld())wake.release();
        if(audio!=null){if(Build.VERSION.SDK_INT>=26 && focus!=null)audio.abandonAudioFocusRequest(focus);else if(legacyFocus!=null)audio.abandonAudioFocus(legacyFocus);audio.setSpeakerphoneOn(oldSpeaker);audio.setMode(oldMode);}
        stopForeground(true);super.onDestroy();
    }
}
