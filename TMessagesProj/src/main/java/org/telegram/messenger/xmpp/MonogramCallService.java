// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import com.negzero.monogram.xmpp.XmppSession;
import com.negzero.monogram.xmpp.calls.XmppCalls;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.telegram.messenger.*;
import org.telegram.messenger.voip.Instance;
import org.telegram.messenger.voip.VoIPCallController;
import org.telegram.messenger.voip.VoIPCallControllers;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;
import org.webrtc.*;

/** The stable controller also represents ringing; capture starts only after a visible user action. */
public final class MonogramCallService extends Service {
    static final String TOKEN="monogram-call-token",ACCOUNT="monogram-call-account",ACTION="monogram-call-action";
    static final String ACCEPT="accept",OPEN="open",END="end",BLUETOOTH="bluetooth";
    private static final int NOTIFICATION=1032;
    private static final String INCOMING_CHANNEL="monogram_incoming_calls",ACTIVE_CHANNEL="monogram_active_calls";
    private static final Map<Integer,Binding> bindings=new ConcurrentHashMap<>();
    private Controller controller;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private AudioDeviceCallback devices;
    private PowerManager.WakeLock cpu,proximity;
    private int previousAudioMode;
    private boolean previousSpeaker,audioConfigured,audioFocusHeld;
    private long audioGeneration;

    private static final class Network {
        final List<com.negzero.monogram.xmpp.calls.XmppIceServers.IceServer> servers;final boolean direct;
        Network(List<com.negzero.monogram.xmpp.calls.XmppIceServers.IceServer> servers,boolean direct) { this.servers=servers;this.direct=direct; }
    }
    private static final class Binding {
        final int account;final XmppSession session;final BooleanSupplier current;final Long owner;final String ownerJid;
        Binding(int account,XmppSession session,BooleanSupplier current) { this(account,session,current,null,null); }
        Binding(int account,XmppSession session,BooleanSupplier current,Long owner,String ownerJid) { this.account=account;this.session=session;this.current=current;this.owner=owner;this.ownerJid=ownerJid; }
        boolean valid() { return bindings.get(account)==this && current.getAsBoolean(); }
    }
    public static void bind(int account,XmppSession session,BooleanSupplier current) {
        Binding binding=new Binding(account,session,current,MonogramController.get(account).callOwner(),MonogramController.get(account).accountJid());Binding old=bindings.put(account,binding);
        if(old!=null) AndroidUtilities.runOnUIThread(() -> { Controller c=active();if(c!=null && c.binding==old) c.finish("disconnected",true); });
        session.calls.setListener(new XmppCalls.Listener() {
            private void dispatch(XmppCalls.Call call,java.util.function.Consumer<Controller> action) {
                AndroidUtilities.runOnUIThread(() -> { Controller c=active();if(binding.valid() && c!=null && c.binding==binding && c.call.sid.equals(call.sid)) { c.call=call;action.accept(c); } });
            }
            @Override public void incoming(XmppCalls.Call call) {
                if(!binding.valid()) return;
                MonogramController.get(account).resolveCallUser(call.peer,true,binding.session,user -> {
                    if(!binding.valid()) return;
                    XmppCalls.Call current=binding.session.calls.current();
                    if(current==null || !current.sid.equals(call.sid)) return;
                    if(user==null || VoIPCallControllers.get()!=null || MonogramMujiService.active()) { session.calls.reject(call.sid);return; }
                    Controller c=new Controller(binding,call,user);
                    if(!VoIPCallControllers.register(c)) { session.calls.reject(call.sid);return; }
                    c.state=VoIPService.STATE_WAITING_INCOMING;
                    c.showNotification(true);
                    if(!ApplicationLoader.mainInterfacePaused) open(c);
                });
            }
            @Override public void state(XmppCalls.Call call,XmppCalls.State state,String reason) {
                dispatch(call,c -> {
                    if(state==XmppCalls.State.ENDED) { c.finish(reason,reason!=null && !Arrays.asList("success","cancel","decline","remote-ended","answered-elsewhere","alternative-session","busy").contains(reason));return; }
                    if(state==XmppCalls.State.ACTIVE) { c.connected();return; }
                    c.changeState(state==XmppCalls.State.PROPOSING ? VoIPService.STATE_REQUESTING
                            : state==XmppCalls.State.RINGING ? (call.outgoing ? VoIPService.STATE_RINGING : VoIPService.STATE_WAITING_INCOMING)
                            : VoIPService.STATE_EXCHANGING_KEYS);
                });
            }
            @Override public void localDescriptionRequested(XmppCalls.Call call,boolean offer) {
                dispatch(call,c -> { if(offer) { c.offerRequested=true;c.prepareMedia(); } });
            }
            @Override public void remoteDescription(XmppCalls.Call call,boolean offer,String sdp) {
                dispatch(call,c -> { c.remoteSdp=sdp;c.remoteOffer=offer;c.prepareMedia(); });
            }
            @Override public void remoteCandidate(XmppCalls.Call call,String mid,int line,String candidate) {
                dispatch(call,c -> { if(c.media!=null) c.media.addIceCandidate(mid,line,candidate);else c.fail("Media was not initialized"); });
            }
            @Override public void remoteMediaState(XmppCalls.Call call,boolean audioMuted,boolean videoMuted) {
                dispatch(call,c -> { c.remoteAudio=audioMuted ? Instance.AUDIO_STATE_MUTED : Instance.AUDIO_STATE_ACTIVE;
                    c.remoteVideoMuted=videoMuted;c.updateRemoteVideo();c.mediaChanged(); });
            }
            @Override public void capabilities(String peer,XmppCalls.Capabilities capabilities) {
                if(binding.valid()) MonogramController.get(account).callCapabilitiesChanged(peer);
            }
        });
    }
    public static void unbind(int account,XmppSession session) {
        Binding binding=bindings.get(account);
        if(binding!=null && binding.session==session && bindings.remove(account,binding))
            AndroidUtilities.runOnUIThread(() -> { Controller c=active();if(c!=null && c.binding==binding) c.finish("disconnected",true); });
    }
    static Controller active() {
        VoIPCallController c=VoIPCallControllers.get();return c instanceof Controller ? (Controller)c : null;
    }
    static Controller matching(Intent intent) {
        Controller c=active();return c!=null && c.binding.valid() && c.binding.account==intent.getIntExtra(ACCOUNT,-1)
                && c.token.equals(intent.getStringExtra(TOKEN)) ? c : null;
    }
    public static void start(Context context,int account,long peer,boolean video) {
        AndroidUtilities.runOnUIThread(() -> {
            Binding binding=bindings.get(account);
            if(binding==null || !binding.valid() || !binding.session.connection().isAuthenticated() || VoIPCallControllers.get()!=null || MonogramMujiService.active()) {
                Toast.makeText(context,LocaleController.getString(R.string.VoipFailed),Toast.LENGTH_SHORT).show();return;
            }
            MonogramController.get(account).resolveCallUser(MonogramController.get(account).jid(peer),false,binding.session,user -> {
                if(!binding.valid() || user==null || VoIPCallControllers.get()!=null) return;
                try {
                    XmppCalls.Call call=binding.session.calls.start(MonogramController.get(account).jid(peer),video);
                    Controller c=new Controller(binding,call,user);
                    if(!VoIPCallControllers.register(c)) { binding.session.calls.end(call.sid);return; }
                    c.beginPermissions(context);
                } catch(Exception failure) { Toast.makeText(context,LocaleController.getString(R.string.VoipFailed),Toast.LENGTH_SHORT).show(); }
            });
        });
    }
    static Intent intent(Context context,Class<?> target,Controller c,String action) {
        return new Intent(context,target).setAction(action).setData(Uri.parse("monogram-call:"+c.token+"/"+action))
                .putExtra(TOKEN,c.token).putExtra(ACCOUNT,c.binding.account).putExtra(ACTION,action);
    }
    private static boolean permission(Context context,String permission) {
        return Build.VERSION.SDK_INT<23 || context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }
    private static boolean incomingAlertsAllowed(Context context) {
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=((NotificationManager)context.getSystemService(NOTIFICATION_SERVICE)).getNotificationChannel(INCOMING_CHANNEL);
            if(channel!=null && channel.getImportance()==NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }
    static void open(Controller c) {
        if(!c.valid()) return;
        Context context=ApplicationLoader.applicationContext;
        context.startActivity(new Intent(context,LaunchActivity.class).setAction("voip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    @Override public void onCreate() { super.onCreate();audio=(AudioManager)getSystemService(AUDIO_SERVICE); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        Controller c=intent==null ? null : matching(intent);
        if(c==null || c.ended) { if(controller==null || controller.ended) stopSelf();return START_NOT_STICKY; }
        if(controller!=null && controller!=c) releaseAudio();
        controller=c;c.service=this;
        try {
            int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if(c.call.video) types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,c.notification(false),types);
            else startForeground(NOTIFICATION,c.notification(false));
            configureAudio(c);
            if(!c.accepted) { c.accepted=true;if(!c.call.outgoing) c.binding.session.calls.accept(c.call.sid); }
            c.prepareMedia();open(c);
        } catch(Exception failure) { c.fail("Cannot start call media"); }
        return START_NOT_STICKY;
    }
    private void configureAudio(Controller c) {
        if(devices!=null) return;
        previousAudioMode=audio.getMode();previousSpeaker=audio.isSpeakerphoneOn();audioConfigured=true;audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        long generation=++audioGeneration;
        focusListener=change -> AndroidUtilities.runOnUIThread(() -> audioFocusChanged(c,generation,change));
        int result;
        if(Build.VERSION.SDK_INT>=26) {
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focusListener).build();result=audio.requestAudioFocus(focus);
        } else result=audio.requestAudioFocus(focusListener,AudioManager.STREAM_VOICE_CALL,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        if(result!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw new IllegalStateException("Call audio focus unavailable");
        audioFocusHeld=true;c.applyMicMute();
        devices=new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { c.audioChanged(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { c.audioChanged(); }
        };
        audio.registerAudioDeviceCallback(devices,new Handler(Looper.getMainLooper()));
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        cpu=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"monogram:call");cpu.acquire();
        if(power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
            proximity=power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,"monogram:call-proximity");
        c.setAudioOutput(c.call.video || !c.hasEarpiece() ? VoIPCallController.AUDIO_OUTPUT_SPEAKER : VoIPCallController.AUDIO_OUTPUT_EARPIECE);
    }
    private void audioFocusChanged(Controller c,long generation,int change) {
        if(generation!=audioGeneration || !audioConfigured || controller!=c || c.service!=this || !c.valid()) return;
        audioFocusHeld=change>0;
        c.applyMicMute();
        if(change==AudioManager.AUDIOFOCUS_LOSS) c.hangUp();
    }
    private void updateProximity(Controller c) {
        if(proximity==null) return;
        boolean near=!c.videoEnabled && !c.isSpeakerphoneOn() && !c.isHeadsetPlugged() && !c.isBluetoothOn();
        if(near && !proximity.isHeld()) proximity.acquire();
        else if(!near && proximity.isHeld()) proximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
    }
    @Override public void onDestroy() {
        Controller c=controller;
        if(c!=null && c.service==this) { c.service=null;if(!c.ended) { c.binding.session.calls.mediaFailed(c.call.sid,"Call service stopped");c.finish("disconnected",true); } }
        releaseAudio();stopForeground(true);super.onDestroy();
    }
    private void releaseAudio() {
        audioGeneration++;audioFocusHeld=false;
        if(controller!=null && controller.service==this && controller.media!=null) controller.media.setMicMuted(true);
        if(audio!=null) {
            if(devices!=null) audio.unregisterAudioDeviceCallback(devices);
            if(Build.VERSION.SDK_INT>=26 && focus!=null) audio.abandonAudioFocusRequest(focus);
            else if(focusListener!=null) audio.abandonAudioFocus(focusListener);
            if(audioConfigured) {
                if(Build.VERSION.SDK_INT>=31) audio.clearCommunicationDevice();
                else { audio.stopBluetoothSco();audio.setBluetoothScoOn(false); }
                audio.setSpeakerphoneOn(previousSpeaker);audio.setMode(previousAudioMode);
            }
        }
        if(proximity!=null && proximity.isHeld()) proximity.release();
        if(cpu!=null && cpu.isHeld()) cpu.release();
        devices=null;focus=null;focusListener=null;cpu=null;proximity=null;audioConfigured=false;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    static String callSummary(String reason,boolean failed,boolean outgoing,boolean accepted,boolean connected) {
        if(connected) return failed ? "disconnect" : "hangup";
        if("busy".equals(reason) || outgoing && "decline".equals(reason)) return "busy";
        if("decline".equals(reason)) return "hangup";
        if(!accepted || outgoing && !failed) return "missed";
        return failed ? "disconnect" : "hangup";
    }

    static final class Controller implements VoIPCallController {
        final Binding binding;final TLRPC.User user;final String token=UUID.randomUUID().toString();
        final List<Listener> listeners=new CopyOnWriteArrayList<>();
        final List<Runnable> finishedCallbacks=new ArrayList<>();
        boolean cleanupComplete;
        volatile XmppCalls.Call call;
        MonogramCallService service;MonogramCallMedia media;
        int state=VoIPService.STATE_REQUESTING,remoteAudio=Instance.AUDIO_STATE_ACTIVE,remoteVideo=Instance.VIDEO_STATE_INACTIVE;
        volatile boolean ended;
        boolean accepted,preparing,mediaReady,offerRequested,descriptionSent,remoteOffer,micMuted,videoEnabled,switching,remoteVideoAvailable,remoteVideoMuted;
        String remoteSdp,error;long started;final long created=System.currentTimeMillis();
        VideoSink localSink,remoteSink;
        Controller(Binding binding,XmppCalls.Call call,TLRPC.User user) { this.binding=binding;this.call=call;this.user=user;this.videoEnabled=call.video; }
        boolean valid() { return !ended && binding.valid() && VoIPCallControllers.isActive(this); }
        void beginPermissions(Context context) {
            if(!valid()) return;
            if(!permission(context,Manifest.permission.RECORD_AUDIO) || call.video && !permission(context,Manifest.permission.CAMERA)) {
                context.startActivity(intent(context,MonogramCallPermissionActivity.class,this,ACCEPT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;
            }
            launchService(context);
        }
        void launchService(Context context) {
            if(!valid()) return;
            stopRinging();
            try { ContextCompat.startForegroundService(context,intent(context,MonogramCallService.class,this,ACCEPT)); }
            catch(RuntimeException failure) { fail("Call service could not start"); }
        }
        void prepareMedia() {
            if(!valid() || !accepted || service==null) return;
            if(mediaReady) { applyDescriptions();return; }
            if(preparing) return;preparing=true;
            binding.session.calls.iceServers().thenCombine(MonogramController.get(binding.account).allowDirectCall(user.id),Network::new).whenComplete((network,failure) -> AndroidUtilities.runOnUIThread(() -> {
                if(!valid() || service==null) return;
                if(failure!=null) { fail("Call relay discovery failed");return; }
                List<PeerConnection.IceServer> ice=new ArrayList<>();
                for(com.negzero.monogram.xmpp.calls.XmppIceServers.IceServer server:network.servers) {
                    PeerConnection.IceServer.Builder builder=PeerConnection.IceServer.builder(server.urls);
                    if(server.username!=null) builder.setUsername(server.username);
                    if(server.password!=null) builder.setPassword(server.password);
                    ice.add(builder.createIceServer());
                }
                media=new MonogramCallMedia(service,new MonogramCallMedia.Listener() {
                    private void ui(Runnable action) { AndroidUtilities.runOnUIThread(() -> { if(valid()) action.run(); }); }
                    public void localDescription(boolean offer,String sdp) { ui(() -> {
                        mediaReady=true;for(Listener l:listeners) l.onVideoAvailableChange(call.video);
                        binding.session.calls.localDescription(call.sid,offer,sdp);
                        applyMicMute();mediaChanged(); }); }
                    public void candidate(String mid,int line,String value) { if(valid()) binding.session.calls.candidate(call.sid,mid,line,value); }
                    public void remoteDescriptionApplied() { if(valid()) binding.session.calls.remoteDescriptionApplied(call.sid); }
                    public void connected() { ui(() -> { binding.session.calls.mediaConnected(call.sid);Controller.this.connected(); }); }
                    public void failed(String message) { ui(() -> fail(message)); }
                    public void remoteVideo(boolean available) { ui(() -> { remoteVideoAvailable=available;updateRemoteVideo();mediaChanged(); }); }
                    public void cameraSwitched(boolean front) { ui(() -> { switching=false;for(Listener l:listeners) l.onCameraSwitch(front); }); }
                    public void cameraFirstFrameAvailable() { ui(() -> { for(Listener l:listeners) l.onCameraFirstFrameAvailable(); }); }
                });
                media.setSinks(localSink,remoteSink);applyMicMute();
                media.setVideoEnabled(videoEnabled);media.prepare(call.video,ice,!network.direct);
                mediaReady=true;applyDescriptions();
            }));
        }
        void applyDescriptions() {
            if(media==null || !mediaReady) return;
            if(remoteSdp!=null) {
                String sdp=remoteSdp;boolean offer=remoteOffer;remoteSdp=null;
                if(offer) media.answer(sdp);else media.remoteAnswer(sdp);
            } else if(offerRequested && !descriptionSent) { descriptionSent=true;media.offer(); }
        }
        void changeState(int value) {
            if(ended || state==value) return;state=value;
            for(Listener l:listeners) l.onStateChanged(value);
            showNotification(value==VoIPService.STATE_WAITING_INCOMING && !accepted);
        }
        void connected() {
            if(!valid()) return;if(started==0) started=SystemClock.elapsedRealtime();
            changeState(VoIPService.STATE_ESTABLISHED);
        }
        void updateRemoteVideo() { remoteVideo=call.video && remoteVideoAvailable && !remoteVideoMuted ? Instance.VIDEO_STATE_ACTIVE : Instance.VIDEO_STATE_INACTIVE; }
        void mediaChanged() { for(Listener l:listeners) l.onMediaStateUpdated(remoteAudio,remoteVideo);if(service!=null) service.updateProximity(this); }
        void audioChanged() { for(Listener l:listeners) l.onAudioSettingsChanged();if(service!=null) service.updateProximity(this); }
        void fail(String reason) { if(!valid()) return;binding.session.calls.mediaFailed(call.sid,reason);finish(reason,true); }
        void finish(String reason,boolean failed) {
            if(ended) return;ended=true;error=reason;
            if(binding.owner!=null && !Arrays.asList("answered-elsewhere","alternative-session").contains(reason)) {
                String summary=callSummary(reason,failed,call.outgoing,accepted,started!=0);
                MonogramController.get(binding.account).callEnded(binding.owner,binding.ownerJid,user.id,call.sid,call.outgoing,call.video,created,
                        (int)Math.min(Integer.MAX_VALUE,getCallDuration()/1000),summary);
            }
            state="busy".equals(reason) ? VoIPService.STATE_BUSY : failed ? VoIPService.STATE_FAILED : VoIPService.STATE_ENDED;
            MonogramCallMedia closing=media;media=null;
            MonogramCallService owned=service;service=null;
            if(owned!=null) { owned.releaseAudio();owned.stopForeground(true);owned.stopSelf(); }
            ((NotificationManager)ApplicationLoader.applicationContext.getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION);
            for(Listener l:listeners) l.onStateChanged(state);
            Runnable complete=() -> {
                cleanupComplete=true;VoIPCallControllers.unregister(this);
                List<Runnable> callbacks=new ArrayList<>(finishedCallbacks);finishedCallbacks.clear();
                for(Runnable callback:callbacks) callback.run();
            };
            if(closing==null) complete.run();else closing.close(() -> AndroidUtilities.runOnUIThread(complete));
        }
        PendingIntent action(String action,boolean activity) {
            Context context=ApplicationLoader.applicationContext;
            Intent intent=intent(context,activity ? MonogramCallPermissionActivity.class : MonogramCallActionReceiver.class,this,action);
            return activity ? PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        }
        Notification notification(boolean incoming) {
            Context context=ApplicationLoader.applicationContext;
            NotificationManager manager=(NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);
            if(Build.VERSION.SDK_INT>=26) {
                NotificationChannel channel=new NotificationChannel(incoming ? INCOMING_CHANNEL : ACTIVE_CHANNEL,
                        LocaleController.getString(incoming ? R.string.IncomingCalls : R.string.Call),incoming ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW);
                if(incoming) channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
                else channel.setSound(null,null);
                manager.createNotificationChannel(channel);
            }
            Person person=new Person.Builder().setName(UserObject.getUserName(user)).setKey(Long.toString(user.id)).setImportant(true).build();
            PendingIntent open=action(OPEN,true),end=action(END,false);
            NotificationCompat.Builder builder=new NotificationCompat.Builder(context,incoming ? INCOMING_CHANNEL : ACTIVE_CHANNEL)
                    .setSmallIcon(R.drawable.notification).setContentTitle(UserObject.getUserName(user))
                    .setContentText(LocaleController.getString(call.video ? R.string.VideoCall : R.string.Call))
                    .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(incoming ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
                    .setStyle(incoming ? NotificationCompat.CallStyle.forIncomingCall(person,end,action(ACCEPT,true)).setIsVideo(call.video)
                            : NotificationCompat.CallStyle.forOngoingCall(person,end).setIsVideo(call.video));
            if(incoming) builder.setFullScreenIntent(open,true);
            else if(started!=0) builder.setWhen(System.currentTimeMillis()-getCallDuration()).setUsesChronometer(true).setShowWhen(true);
            Notification n=builder.build();if(incoming) n.flags|=Notification.FLAG_INSISTENT;return n;
        }
        void showNotification(boolean incoming) {
            if(!valid()) return;
            boolean background=incoming && ApplicationLoader.mainInterfacePaused;
            if(background && !incomingAlertsAllowed(ApplicationLoader.applicationContext)) {
                binding.session.calls.ignore(call.sid);finish("Notifications unavailable",true);return;
            }
            try { ((NotificationManager)ApplicationLoader.applicationContext.getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(incoming)); }
            catch(SecurityException denied) { if(background) { binding.session.calls.ignore(call.sid);finish("Notifications unavailable",true); } }
        }
        public int getAccount() { return binding.account; }
        public long getPeerId() { return user.id; }
        public TLRPC.User getUser() { return user; }
        public boolean isOutgoing() { return call.outgoing; }
        public int getCallState() { return state; }
        public long getCallDuration() { return started==0 ? 0 : Math.max(0,SystemClock.elapsedRealtime()-started); }
        public boolean isCallingVideo() { return call.video; }
        public int getCapabilities() { return CAP_RETRY|(call.video ? CAP_VIDEO|CAP_PIP : 0); }
        public int getSecurityPresentation() { return state==VoIPService.STATE_ESTABLISHED ? SECURITY_DTLS_UNVERIFIED : SECURITY_NONE; }
        public String getLastError() { return error; }
        public EglBase.Context getEglContext() { return media==null ? null : media.getEglContext(); }
        public void addListener(Listener listener) { listeners.add(listener);listener.onStateChanged(state); }
        public void removeListener(Listener listener) { listeners.remove(listener); }
        public void acceptIncomingCall() { beginPermissions(ApplicationLoader.applicationContext); }
        public void declineIncomingCall() { if(valid()) binding.session.calls.reject(call.sid);finish("decline",false); }
        public void stopRinging() { ((NotificationManager)ApplicationLoader.applicationContext.getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION); }
        public void hangUp() { hangUp(null); }
        public void hangUp(Runnable done) {
            if(done!=null) { if(cleanupComplete) done.run();else finishedCallbacks.add(done); }
            if(ended) return;
            if(valid()) binding.session.calls.end(call.sid);finish("success",false);
        }
        public void retry() { if(binding.valid()) hangUp(() -> start(ApplicationLoader.applicationContext,binding.account,user.id,call.video)); }
        public boolean isMicMute() { return micMuted; }
        boolean effectiveMicMute() { return micMuted || service==null || !service.audioFocusHeld; }
        void applyMicMute() {
            boolean mute=effectiveMicMute();if(media!=null) media.setMicMuted(mute);
            if(valid() && accepted) binding.session.calls.mediaMuted(call.sid,mute,!videoEnabled);
        }
        public void setMicMute(boolean mute) { micMuted=mute;applyMicMute();audioChanged(); }
        private AudioManager audio() { return service==null ? (AudioManager)ApplicationLoader.applicationContext.getSystemService(AUDIO_SERVICE) : service.audio; }
        public boolean isBluetoothOn() { AudioManager a=audio();return Build.VERSION.SDK_INT>=31 ? a.getCommunicationDevice()!=null && bluetooth(a.getCommunicationDevice().getType()) : a.isBluetoothScoOn(); }
        static boolean bluetooth(int type) { return type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || Build.VERSION.SDK_INT>=31 && type==AudioDeviceInfo.TYPE_BLE_HEADSET; }
        public boolean isBluetoothHeadsetConnected() {
            if(Build.VERSION.SDK_INT>=31 && !permission(ApplicationLoader.applicationContext,Manifest.permission.BLUETOOTH_CONNECT)) return false;
            for(AudioDeviceInfo d:audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if(bluetooth(d.getType())) return true;return false;
        }
        public boolean isHeadsetPlugged() { for(AudioDeviceInfo d:audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if(d.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET || d.getType()==AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET) return true;return false; }
        public boolean hasEarpiece() { for(AudioDeviceInfo d:audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return true;return false; }
        public boolean isSpeakerphoneOn() { return audio().isSpeakerphoneOn(); }
        public int getCurrentAudioRoute() { return isBluetoothOn() ? 2 : isSpeakerphoneOn() ? 1 : 0; }
        public int getAudioOutput() { return isBluetoothOn() ? AUDIO_OUTPUT_BLUETOOTH : isSpeakerphoneOn() ? AUDIO_OUTPUT_SPEAKER : AUDIO_OUTPUT_EARPIECE; }
        public void setAudioOutput(int route) {
            if(!valid() || service==null) return;AudioManager a=audio();
            if(route==AUDIO_OUTPUT_BLUETOOTH && Build.VERSION.SDK_INT>=31 && !permission(service,Manifest.permission.BLUETOOTH_CONNECT)) {
                service.startActivity(intent(service,MonogramCallPermissionActivity.class,this,BLUETOOTH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;
            }
            if(Build.VERSION.SDK_INT>=31) {
                AudioDeviceInfo selected=null;
                for(AudioDeviceInfo d:a.getAvailableCommunicationDevices()) {
                    int type=d.getType();boolean matches=route==AUDIO_OUTPUT_SPEAKER ? type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            : route==AUDIO_OUTPUT_BLUETOOTH ? bluetooth(type)
                            : type==AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || type==AudioDeviceInfo.TYPE_WIRED_HEADSET || type==AudioDeviceInfo.TYPE_USB_HEADSET;
                    if(matches && (selected==null || type!=AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) selected=d;
                }
                if(selected!=null) a.setCommunicationDevice(selected);
            } else {
                a.setSpeakerphoneOn(route==AUDIO_OUTPUT_SPEAKER);
                if(route==AUDIO_OUTPUT_BLUETOOTH) { a.startBluetoothSco();a.setBluetoothScoOn(true); }
                else { a.stopBluetoothSco();a.setBluetoothScoOn(false); }
            }
            audioChanged();
        }
        public void toggleSpeakerphoneOrShowRouteSheet(Context context,boolean overlay,Integer selected) { VoIPCallControllers.showAudioRouteSheet(context,this,overlay,selected); }
        public void switchToSpeaker() { setAudioOutput(AUDIO_OUTPUT_SPEAKER); }
        public boolean isVideoAvailable() { return call.video; }
        public int getVideoState() { return videoEnabled ? Instance.VIDEO_STATE_ACTIVE : Instance.VIDEO_STATE_INACTIVE; }
        public int getRemoteVideoState() { return remoteVideo; }
        public int getRemoteAudioState() { return remoteAudio; }
        public boolean isScreencast() { return false; }
        public boolean isFrontFaceCamera() { return media==null || media.isFrontFacing(); }
        public boolean isSwitchingCamera() { return switching; }
        public void createCaptureDevice() { setVideoState(Instance.VIDEO_STATE_ACTIVE); }
        public void clearCamera() { setVideoState(Instance.VIDEO_STATE_INACTIVE); }
        public void requestVideoCall() { if(call.video) createCaptureDevice(); }
        public void setVideoState(int value) { if(!call.video) return;videoEnabled=value==Instance.VIDEO_STATE_ACTIVE;
            if(media!=null) media.setVideoEnabled(videoEnabled);applyMicMute();mediaChanged(); }
        public void switchCamera() { if(media!=null && !switching) { switching=true;media.switchCamera(); } }
        public void setSinks(VideoSink local,VideoSink remote) { localSink=local;remoteSink=remote;if(media!=null) media.setSinks(local,remote); }
        public boolean isConverting() { return false; }
    }
}
