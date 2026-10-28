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
import org.telegram.ui.LaunchActivity;
import org.webrtc.*;

/** The user explicitly joins before foreground microphone/camera capture starts. */
public final class MonogramMujiService extends Service implements NotificationCenter.NotificationCenterDelegate {
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
            session=MonogramController.get(account).session();configureAudio();changed();
            session.muji.setListener(new XmppMuji.Listener(){
                public void conference(String room,List<XmppMuji.Participant> list,String failure){ui(()->{
                    participants=list;
                    if(failure!=null){fail(failure);return;}
                    if(room==null && (joined || !session.connection().isAuthenticated())){stopSelf();return;}
                    if(room!=null){joined=true;status=connected.isEmpty()?"Waiting for participants…":"In call";}
                    changed();
                });}
                public void localDescriptionRequested(XmppCalls.Call call,boolean offer){if(offer)withNetwork(call,()->media.offer(mediaKey(call),call.video));}
                public void remoteDescription(XmppCalls.Call call,boolean offer,String sdp){if(offer)withNetwork(call,()->media.answer(mediaKey(call),call.video,sdp));else ui(()->media.answerReceived(mediaKey(call),sdp));}
                public void remoteCandidate(XmppCalls.Call call,String mid,int line,String candidate){ui(()->media.candidate(mediaKey(call),mid,line,candidate));}
                public void state(XmppCalls.Call call,XmppCalls.State state,String reason){ui(()->{
                    if(state==XmppCalls.State.ENDED){connected.remove(call.fullPeer);remoteVideo.remove(call.fullPeer);media.remove(mediaKey(call));mediaCalls.remove(mediaKey(call));if(reason!=null && !Arrays.asList("success","remote-ended","closed").contains(reason))peerErrors.put(call.fullPeer,reason);}
                    sendMute();changed();
                });}
            });
            media=new MonogramMujiMedia(this,new MonogramMujiMedia.Listener(){
                public void ready(){setup.execute(()->{try{session.blocking.list();session.muji.join(MonogramController.get(account).jid(peer),video).get(20,TimeUnit.SECONDS);}catch(Exception failed){ui(()->fail("Could not join group call: "+category(failed)));}});}
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
            MonogramController.get(account).allowDirectCall(user.id).whenComplete((direct,failure)->ui(()->{
                XmppCalls active=leg(call.fullPeer);if(active==null || active.current()==null || !active.current().sid.equals(call.sid))return;
                if(failure!=null){peerErrors.put(call.fullPeer,"Call privacy settings unavailable");changed();return;}
                mediaCalls.put(mediaKey(call),call);media.network(mediaKey(call),Boolean.TRUE.equals(direct));work.run();
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
        Intent open=new Intent(this,LaunchActivity.class).setAction("com.tmessages.openchat_muji").putExtra("currentAccount",account).putExtra("chatId",-peer);
        PendingIntent content=PendingIntent.getActivity(this,NOTICE,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.notification).setContentTitle(title).setContentText("Group call · "+status).setContentIntent(content).setOngoing(true).setCategory(NotificationCompat.CATEGORY_CALL).addAction(R.drawable.msg_cancel,"Leave",hangup).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|(video?ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA:0));else startForeground(NOTICE,notification);
    }
    public EglBase.Context egl(){return media==null?null:media.egl();}
    public void sink(String peer,VideoSink sink){XmppCalls leg=leg(peer);XmppCalls.Call call=leg==null?null:leg.current();if(media!=null && call!=null)media.sink(mediaKey(call),sink);}
    public void setMuted(boolean value){muted=value;if(media!=null)media.muted(value);sendMute();changed();}
    public boolean speaker(){return audio!=null && audio.isSpeakerphoneOn();}
    public void speaker(boolean value){if(audio!=null)audio.setSpeakerphoneOn(value);changed();}
    public void setCamera(boolean value){cameraEnabled=value;if(media!=null)media.videoEnabled(value);sendMute();changed();}
    private void sendMute(){if(session!=null)for(XmppMuji.Participant p:participants){XmppCalls e=leg(p.realJid);if(e!=null && e.current()!=null)e.mediaMuted(e.current().sid,muted,!cameraEnabled);}}
    private void changed(){if(account<0 || account>=UserConfig.MAX_ACCOUNT_COUNT)return;NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.monogramGroupCallChanged);}
    private void fail(String reason){error=reason;status=reason;changed();stopSelf();}
    @Override public void onDestroy(){
        NotificationCenter.getGlobalInstance().removeObserver(this,NotificationCenter.proxySettingsChanged);
        destroyed=true;endedAccount=account;endedPeer=peer;endedError=error;current=null;
        if(session!=null){session.muji.setListener(null);session.muji.leave();}
        changed();if(media!=null){closing=true;media.close();media.disposed().whenComplete((done,failure)->closing=false);}setup.shutdownNow();
        if(wake!=null && wake.isHeld())wake.release();
        if(audio!=null){if(Build.VERSION.SDK_INT>=26 && focus!=null)audio.abandonAudioFocusRequest(focus);else if(legacyFocus!=null)audio.abandonAudioFocus(legacyFocus);audio.setSpeakerphoneOn(oldSpeaker);audio.setMode(oldMode);}
        stopForeground(true);super.onDestroy();
    }
}
