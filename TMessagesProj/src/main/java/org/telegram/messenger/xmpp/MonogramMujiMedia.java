// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import java.util.*;
import java.util.concurrent.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** One capture device/factory shared by a bounded MUJI mesh; every remote has its own DTLS peer. */
public final class MonogramMujiMedia implements AutoCloseable {
    public interface Listener {
        void ready();void description(String peer,boolean offer,String sdp);void candidate(String peer,String mid,int line,String value);
        void remoteApplied(String peer);void connected(String peer);void failed(String peer,String reason);void video(String peer,boolean available);
    }
    private final Context context;private final Listener listener;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"Monogram MUJI media"));
    private final Map<String,Peer> peers=new LinkedHashMap<>();
    private final CompletableFuture<Void> disposed=new CompletableFuture<>();
    public CompletableFuture<Void> disposed(){return disposed;}
    private volatile boolean closed;private volatile EglBase.Context eglContext;
    private EglBase egl;private PeerConnectionFactory factory;private JavaAudioDeviceModule audioDevice;
    private AudioSource audioSource;private AudioTrack audioTrack;private VideoSource videoSource;private VideoTrack videoTrack;
    private CameraVideoCapturer camera;private SurfaceTextureHelper texture;
    private List<PeerConnection.IceServer> servers;private boolean relayOnly,muted,cameraRunning;
    private final Map<String,Boolean> direct=new HashMap<>();
    private final class Peer {
        final String address;PeerConnection connection;VideoTrack remote;VideoSink sink;
        final List<IceCandidate> remoteCandidates=new ArrayList<>(),localCandidates=new ArrayList<>();
        boolean remoteSet,published;ScheduledFuture<?> disconnected;
        Peer(String address){this.address=address;}
    }
    public MonogramMujiMedia(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    public EglBase.Context egl(){return eglContext;}
    private void execute(String peer,Runnable work){if(closed)return;try{worker.execute(()->{if(!closed)try{work.run();}catch(RuntimeException failed){listener.failed(peer,failed instanceof SecurityException?"Media permission required":"Group-call media failed");}});}catch(RejectedExecutionException ignored){}}
    public void prepare(boolean video,List<PeerConnection.IceServer> servers,boolean relayOnly){execute(null,()->{
        if(factory!=null)throw new IllegalStateException("Already prepared");permission(Manifest.permission.RECORD_AUDIO);if(video)permission(Manifest.permission.CAMERA);
        if(relayOnly && !MonogramCallMedia.hasTurn(servers))throw new IllegalStateException("Relay required but TURN unavailable");
        this.servers=new ArrayList<>(servers);this.relayOnly=relayOnly;
        MonogramWebRtcBootstrap.initialize(context);if(closed)return;egl=EglBase.create();eglContext=egl.getEglBaseContext();
        audioDevice=JavaAudioDeviceModule.builder(context)
            .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback(){
                public void onWebRtcAudioRecordInitError(String error){captureFailed("Microphone initialization failed");}
                public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code,String error){captureFailed("Microphone start failed");}
                public void onWebRtcAudioRecordError(String error){captureFailed("Microphone failed");}
            }).setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){
                public void onWebRtcAudioTrackInitError(String error){captureFailed("Audio playback initialization failed");}
                public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code,String error){captureFailed("Audio playback start failed");}
                public void onWebRtcAudioTrackError(String error){captureFailed("Audio playback failed");}
            }).createAudioDeviceModule();
        factory=PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglContext,true,true)).setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglContext)).createPeerConnectionFactory();
        if(closed)return;
        audioSource=factory.createAudioSource(new MediaConstraints());audioTrack=factory.createAudioTrack("muji-audio",audioSource);audioTrack.setEnabled(!muted);
        if(closed)return;
        if(video) {
            CameraEnumerator enumerator=Camera2Enumerator.isSupported(context)?new Camera2Enumerator(context):new Camera1Enumerator(true);
            for(String name:enumerator.getDeviceNames())if(enumerator.isFrontFacing(name)){camera=enumerator.createCapturer(name,cameraEvents());if(camera!=null)break;}
            if(camera==null)for(String name:enumerator.getDeviceNames()){camera=enumerator.createCapturer(name,cameraEvents());if(camera!=null)break;}
            if(camera==null)throw new IllegalStateException("Camera unavailable");
            texture=SurfaceTextureHelper.create("muji-camera",eglContext);videoSource=factory.createVideoSource(false);camera.initialize(texture,context,videoSource.getCapturerObserver());
            videoTrack=factory.createVideoTrack("muji-video",videoSource);if(closed)return;camera.startCapture(640,360,24);cameraRunning=true;
        }
        if(!closed)listener.ready();
    });}
    private void captureFailed(String reason){execute(null,()->listener.failed(null,reason));}
    private void permission(String permission){if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Permission required");}
    private CameraVideoCapturer.CameraEventsHandler cameraEvents(){return new CameraVideoCapturer.CameraEventsHandler(){
        public void onCameraError(String error){execute(null,()->listener.failed(null,"Camera failed"));}public void onCameraDisconnected(){onCameraError("");}public void onCameraFreezed(String error){onCameraError(error);}public void onCameraOpening(String name){}public void onFirstFrameAvailable(){}public void onCameraClosed(){}
    };}
    private Peer peer(String address,boolean video){
        Peer found=peers.get(address);if(found!=null)return found;
        if(factory==null || peers.size()>=7)throw new IllegalStateException("Conference unavailable");
        Peer p=new Peer(address);PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(servers);
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;config.bundlePolicy=PeerConnection.BundlePolicy.MAXBUNDLE;config.rtcpMuxPolicy=PeerConnection.RtcpMuxPolicy.REQUIRE;
        boolean relay=relayOnly || !Boolean.TRUE.equals(direct.get(address));
        if(relay && !MonogramCallMedia.hasTurn(servers))throw new IllegalStateException("TURN relay required");
        config.iceTransportsType=relay?PeerConnection.IceTransportsType.RELAY:PeerConnection.IceTransportsType.ALL;
        p.connection=factory.createPeerConnection(config,observer(p));if(p.connection==null)throw new IllegalStateException("Peer unavailable");
        peers.put(address,p);if(closed)return p;
        if(p.connection.addTrack(audioTrack,Collections.singletonList("muji"))==null)throw new IllegalStateException("Audio sender unavailable");
        if(video && videoTrack!=null && p.connection.addTrack(videoTrack,Collections.singletonList("muji"))==null)throw new IllegalStateException("Video sender unavailable");
        p.connection.setAudioPlayout(true);p.connection.setAudioRecording(true);peers.put(address,p);return p;
    }
    public void network(String address,boolean allowed){execute(address,()->direct.put(address,allowed));}
    public void offer(String address,boolean video){execute(address,()->create(peer(address,video),true));}
    public void answer(String address,boolean video,String sdp){execute(address,()->{Peer p=peer(address,video);remote(p,SessionDescription.Type.OFFER,sdp,()->create(p,false));});}
    public void answerReceived(String address,String sdp){execute(address,()->{Peer p=peers.get(address);if(p==null)throw new IllegalStateException("Unknown participant");remote(p,SessionDescription.Type.ANSWER,sdp,()->{});});}
    private void create(Peer p,boolean offer){if(closed)return;SdpObserver observer=new Sdp(p){public void onCreateSuccess(SessionDescription raw){execute(p.address,()->{
        SessionDescription description=new SessionDescription(raw.type,offer?namedContents(raw.description):raw.description);
        p.connection.setLocalDescription(new Sdp(p){public void onSetSuccess(){execute(p.address,()->{p.published=true;listener.description(p.address,offer,description.description);for(IceCandidate c:p.localCandidates)listener.candidate(p.address,c.sdpMid,c.sdpMLineIndex,c.sdp);p.localCandidates.clear();});}},description);
    });}};if(offer)p.connection.createOffer(observer,new MediaConstraints());else p.connection.createAnswer(observer,new MediaConstraints());}
    static String namedContents(String sdp){
        String[] lines=sdp.split("\\r?\\n");Map<String,String> names=new LinkedHashMap<>();String media=null;
        for(String line:lines){if(line.startsWith("m="))media=line.substring(2).split(" ",2)[0];if(line.startsWith("a=mid:") && ("audio".equals(media)||"video".equals(media)))names.put(line.substring(6),"audio".equals(media)?"voice":"webcam");}
        StringBuilder result=new StringBuilder();for(String line:lines){if(line.startsWith("a=mid:"))line="a=mid:"+names.getOrDefault(line.substring(6),line.substring(6));else if(line.startsWith("a=group:BUNDLE ")){StringBuilder group=new StringBuilder("a=group:BUNDLE");for(String name:line.substring(15).split(" +"))group.append(' ').append(names.getOrDefault(name,name));line=group.toString();}result.append(line).append("\r\n");}return result.toString();
    }
    private void remote(Peer p,SessionDescription.Type type,String sdp,Runnable ready){if(closed)return;if(sdp==null || sdp.length()>262144)throw new IllegalArgumentException("Invalid SDP");p.connection.setRemoteDescription(new Sdp(p){public void onSetSuccess(){execute(p.address,()->{p.remoteSet=true;listener.remoteApplied(p.address);for(IceCandidate c:p.remoteCandidates)if(!p.connection.addIceCandidate(c))throw new IllegalStateException("ICE candidate rejected");p.remoteCandidates.clear();ready.run();});}},new SessionDescription(type,sdp));}
    public void candidate(String address,String mid,int line,String value){execute(address,()->{Peer p=peers.get(address);if(p==null)throw new IllegalStateException("Unknown participant");IceCandidate candidate=new IceCandidate(mid,line,value);if(p.remoteSet){if(!p.connection.addIceCandidate(candidate))throw new IllegalStateException("ICE candidate rejected");}else{if(p.remoteCandidates.size()>=128)throw new IllegalStateException("Candidate queue full");p.remoteCandidates.add(candidate);}});}
    public void muted(boolean value){execute(null,()->{muted=value;if(audioTrack!=null)audioTrack.setEnabled(!value);if(audioDevice!=null)audioDevice.setMicrophoneMute(value);});}
    public void videoEnabled(boolean value){execute(null,()->{if(videoTrack!=null){permission(Manifest.permission.CAMERA);videoTrack.setEnabled(value);if(!value && cameraRunning){try{camera.stopCapture();}catch(InterruptedException e){Thread.currentThread().interrupt();}cameraRunning=false;}else if(value && !cameraRunning){camera.startCapture(640,360,24);cameraRunning=true;}}});}
    public void sink(String address,VideoSink sink){execute(address,()->{Peer p=peers.get(address);if(p==null)return;if(p.remote!=null && p.sink!=null)p.remote.removeSink(p.sink);p.sink=sink;if(p.remote!=null && sink!=null)p.remote.addSink(sink);});}
    public void remove(String address){execute(address,()->dispose(peers.remove(address)));}
    private static void release(Runnable work){try{work.run();}catch(RuntimeException ignored){}}
    private void dispose(Peer p){if(p==null)return;if(p.disconnected!=null)p.disconnected.cancel(false);if(p.remote!=null && p.sink!=null)p.remote.removeSink(p.sink);p.connection.close();p.connection.dispose();}
    private PeerConnection.Observer observer(Peer p){return new PeerConnection.Observer(){
        public void onSignalingChange(PeerConnection.SignalingState state){}public void onIceConnectionReceivingChange(boolean receiving){}public void onIceGatheringChange(PeerConnection.IceGatheringState state){}public void onIceCandidatesRemoved(IceCandidate[] removed){}public void onAddStream(MediaStream stream){}public void onRemoveStream(MediaStream stream){}public void onRenegotiationNeeded(){}
        public void onDataChannel(DataChannel data){execute(p.address,data::close);}
        public void onIceConnectionChange(PeerConnection.IceConnectionState state){if(state==PeerConnection.IceConnectionState.FAILED)execute(p.address,()->listener.failed(p.address,"ICE connection failed"));}
        public void onConnectionChange(PeerConnection.PeerConnectionState state){execute(p.address,()->{
            if(state==PeerConnection.PeerConnectionState.CONNECTED){if(p.disconnected!=null)p.disconnected.cancel(false);p.disconnected=null;listener.connected(p.address);}
            else if(state==PeerConnection.PeerConnectionState.DISCONNECTED && p.disconnected==null)p.disconnected=worker.schedule(()->{if(!closed && peers.get(p.address)==p)listener.failed(p.address,"Media connection lost");},20,TimeUnit.SECONDS);
            else if(state==PeerConnection.PeerConnectionState.FAILED)listener.failed(p.address,"Media connection failed");
        });}
        public void onIceCandidate(IceCandidate candidate){execute(p.address,()->{if(p.published)listener.candidate(p.address,candidate.sdpMid,candidate.sdpMLineIndex,candidate.sdp);else{if(p.localCandidates.size()>=128)throw new IllegalStateException("Candidate queue full");p.localCandidates.add(candidate);}});}
        public void onTrack(RtpTransceiver transceiver){execute(p.address,()->{MediaStreamTrack track=transceiver.getReceiver().track();if(track instanceof VideoTrack){p.remote=(VideoTrack)track;if(p.sink!=null)p.remote.addSink(p.sink);listener.video(p.address,true);}});}
        public void onRemoveTrack(RtpReceiver receiver){execute(p.address,()->{if(p.remote!=null && receiver.track()==p.remote){if(p.sink!=null)p.remote.removeSink(p.sink);p.remote=null;listener.video(p.address,false);}});}
    };}
    private class Sdp implements SdpObserver {
        final Peer peer;Sdp(Peer peer){this.peer=peer;}public void onCreateSuccess(SessionDescription description){}public void onSetSuccess(){}
        public void onCreateFailure(String error){execute(peer.address,()->listener.failed(peer.address,"Media description creation failed"));}public void onSetFailure(String error){execute(peer.address,()->listener.failed(peer.address,"Media description rejected"));}
    }
    @Override public void close(){
        if(closed)return;closed=true;
        try{worker.execute(()->{try{
            for(Peer p:peers.values())release(()->dispose(p));peers.clear();
            if(camera!=null){if(cameraRunning)try{camera.stopCapture();}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RuntimeException ignored){}release(camera::dispose);}
            if(videoTrack!=null)release(videoTrack::dispose);if(videoSource!=null)release(videoSource::dispose);if(texture!=null)release(texture::dispose);
            if(audioTrack!=null)release(audioTrack::dispose);if(audioSource!=null)release(audioSource::dispose);if(factory!=null)release(factory::dispose);if(audioDevice!=null)release(audioDevice::release);if(egl!=null)release(egl::release);eglContext=null;
        }finally{disposed.complete(null);worker.shutdown();}});}catch(RejectedExecutionException ignored){disposed.complete(null);}
    }
}
