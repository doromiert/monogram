// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** One accepted call. Commands and listener events run on a private serial media queue. */
public final class MonogramCallMedia implements AutoCloseable {
    public interface Listener {
        void localDescription(boolean offer, String sdp);
        void candidate(String mid, int mLine, String sdp);
        void remoteDescriptionApplied();
        void connected();
        void failed(String reason);
        void remoteVideo(boolean available);
        default void cameraSwitched(boolean front) {}
        default void cameraFirstFrameAvailable() {}
        default void cameraError(String reason) {}
    }

    private static final int MAX_CANDIDATES = 256;
    private final Context context;
    private final Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "xmpp-call-media"));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object closeLock = new Object();
    private final List<Runnable> closeCallbacks = new ArrayList<>();
    private boolean disposed;
    private final List<IceCandidate> candidates = new ArrayList<>();
    private final List<IceCandidate> localCandidates = new ArrayList<>();
    private volatile EglBase.Context eglContext;
    private volatile boolean videoEnabled, frontFacing = true, micMuted;
    private EglBase egl;
    private JavaAudioDeviceModule audioDevice;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack, remoteTrack;
    private SurfaceTextureHelper texture;
    private CameraVideoCapturer camera;
    private VideoSink localSink, remoteSink;
    private boolean cameraRunning, prepared, remoteSet, negotiating, connected, localPublished, videoRequested = true;
    private ScheduledFuture<?> disconnectedTimeout;

    public MonogramCallMedia(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.listener = Objects.requireNonNull(listener);
    }

    /** Null until preparation succeeds; renderers must be released before this call is closed. */
    public EglBase.Context getEglContext() { return eglContext; }
    public boolean isVideoEnabled() { return videoEnabled; }
    public boolean isFrontFacing() { return frontFacing; }
    public boolean isMicMuted() { return micMuted; }

    /** Explicit permission/user-acceptance boundary: neither constructor nor signaling starts capture. */
    public void prepare(boolean video, List<PeerConnection.IceServer> ice, boolean relayOnly) {
        List<PeerConnection.IceServer> servers = new ArrayList<>(Objects.requireNonNull(ice));
        execute(() -> {
            if (prepared || factory != null) throw new IllegalStateException("Media already prepared");
            requirePermission(Manifest.permission.RECORD_AUDIO);
            if (video && videoRequested) requirePermission(Manifest.permission.CAMERA);
            if (relayOnly && !hasTurn(servers)) throw new IllegalStateException("Relay required but TURN unavailable");
            MonogramWebRtcBootstrap.initialize(context);
            egl = EglBase.create();
            eglContext = egl.getEglBaseContext();
            audioDevice = JavaAudioDeviceModule.builder(context)
                    .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                        public void onWebRtcAudioRecordInitError(String error) { failure("Microphone initialization failed"); }
                        public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code, String error) { failure("Microphone start failed"); }
                        public void onWebRtcAudioRecordError(String error) { failure("Microphone failed"); }
                    })
                    .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback() {
                        public void onWebRtcAudioTrackInitError(String error) { failure("Audio playback initialization failed"); }
                        public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code, String error) { failure("Audio playback start failed"); }
                        public void onWebRtcAudioTrackError(String error) { failure("Audio playback failed"); }
                    }).createAudioDeviceModule();
            audioDevice.setMicrophoneMute(micMuted);
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice)
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglContext, true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglContext)).createPeerConnectionFactory();
            if (closed.get()) return;
            PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(servers);
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
            configuration.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
            configuration.iceTransportsType = relayOnly ? PeerConnection.IceTransportsType.RELAY : PeerConnection.IceTransportsType.ALL;
            peer = factory.createPeerConnection(configuration, observer());
            if (peer == null) throw new IllegalStateException("Peer connection unavailable");
            if (closed.get()) return;
            audioSource = factory.createAudioSource(new MediaConstraints());
            audioTrack = factory.createAudioTrack("monogram-audio", audioSource);
            audioTrack.setEnabled(!micMuted);
            if (peer.addTrack(audioTrack, Collections.singletonList("monogram")) == null)
                throw new IllegalStateException("Audio sender unavailable");
            if (video) {
                createCamera();
                if (peer.addTrack(videoTrack, Collections.singletonList("monogram")) == null)
                    throw new IllegalStateException("Video sender unavailable");
                if (videoRequested) startCamera();
            }
            if (closed.get()) return;
            peer.setAudioPlayout(true);
            peer.setAudioRecording(true);
            prepared = true;
        });
    }

    private void requirePermission(String permission) {
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Media permission required");
    }

    static boolean hasTurn(List<PeerConnection.IceServer> servers) {
        for (PeerConnection.IceServer server : servers) for (String url : server.urls)
            if (url.startsWith("turn:") || url.startsWith("turns:")) return true;
        return false;
    }

    public void offer() { execute(() -> { beginNegotiation(); createDescription(true); }); }

    public void answer(String remoteOffer) {
        execute(() -> {
            beginNegotiation();
            setRemote(SessionDescription.Type.OFFER, remoteOffer, () -> createDescription(false));
        });
    }

    public void remoteAnswer(String remoteAnswer) {
        execute(() -> {
            requirePrepared();
            if (!negotiating || peer.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER)
                throw new IllegalStateException("Unexpected remote answer");
            setRemote(SessionDescription.Type.ANSWER, remoteAnswer, () -> negotiating = false);
        });
    }

    private void beginNegotiation() {
        requirePrepared();
        if (negotiating) throw new IllegalStateException("Description already pending");
        negotiating = true;
        localPublished = false;
    }

    private void createDescription(boolean offer) {
        SdpObserver callback = new DescriptionObserver() {
            public void onCreateSuccess(SessionDescription description) {
                execute(() -> peer.setLocalDescription(new DescriptionObserver() {
                    public void onSetSuccess() {
                        execute(() -> {
                            if (!offer) negotiating = false;
                            listener.localDescription(offer, description.description);
                            localPublished = true;
                            for (IceCandidate candidate : localCandidates) emitCandidate(candidate);
                            localCandidates.clear();
                        });
                    }
                }, description));
            }
        };
        if (offer) peer.createOffer(callback, new MediaConstraints());
        else peer.createAnswer(callback, new MediaConstraints());
    }

    private void setRemote(SessionDescription.Type type, String sdp, Runnable success) {
        if (sdp == null || sdp.length() == 0 || sdp.length() > 1024 * 1024)
            throw new IllegalArgumentException("Invalid remote description");
        peer.setRemoteDescription(new DescriptionObserver() {
            public void onSetSuccess() {
                execute(() -> {
                    remoteSet = true;
                    listener.remoteDescriptionApplied();
                    if (closed.get()) return;
                    for (IceCandidate candidate : candidates) installCandidate(candidate);
                    candidates.clear();
                    success.run();
                });
            }
        }, new SessionDescription(type, sdp));
    }

    public void addIceCandidate(String mid, int mLine, String sdp) {
        execute(() -> {
            requirePrepared();
            if (mid == null || mid.length() > 256 || mLine < 0 || mLine > 32 || sdp == null || sdp.length() > 8192 || !sdp.startsWith("candidate:"))
                throw new IllegalArgumentException("Invalid ICE candidate");
            IceCandidate candidate = new IceCandidate(mid, mLine, sdp);
            if (remoteSet) installCandidate(candidate);
            else {
                if (candidates.size() >= MAX_CANDIDATES) throw new IllegalStateException("Too many pending ICE candidates");
                candidates.add(candidate);
            }
        });
    }

    private void installCandidate(IceCandidate candidate) {
        if (!peer.addIceCandidate(candidate)) throw new IllegalStateException("Remote ICE candidate rejected");
    }

    public void setMicMuted(boolean muted) {
        execute(() -> {
            micMuted = muted;
            if (audioTrack != null) audioTrack.setEnabled(!muted);
            if (audioDevice != null) audioDevice.setMicrophoneMute(muted);
        });
    }

    /** Controls the already negotiated video track; adding video requires a new negotiated call. */
    public void setVideoEnabled(boolean enabled) {
        execute(() -> {
            videoRequested = enabled;
            if (!prepared) return;
            if (camera == null) {
                if (enabled) throw new IllegalStateException("Video was not negotiated");
                return;
            }
            if (enabled) { requirePermission(Manifest.permission.CAMERA);startCamera(); }
            else {
                videoTrack.setEnabled(false);
                if (cameraRunning) stopCamera();
                cameraRunning = false;videoEnabled = false;
            }
        });
    }

    public void switchCamera() {
        execute(() -> {
            if (camera == null || !cameraRunning) { listener.cameraSwitched(frontFacing);return; }
            try { camera.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                public void onCameraSwitchDone(boolean front) {
                    execute(() -> { frontFacing = front;listener.cameraSwitched(front); });
                }
                public void onCameraSwitchError(String error) {
                    execute(() -> { listener.cameraSwitched(frontFacing);listener.cameraError("Camera switch failed"); });
                }
            }); } catch (RuntimeException unavailable) {
                listener.cameraSwitched(frontFacing);listener.cameraError("Camera switch failed");
            }
        });
    }

    public void setSinks(VideoSink local, VideoSink remote) {
        execute(() -> {
            if (videoTrack != null && localSink != null) videoTrack.removeSink(localSink);
            if (remoteTrack != null && remoteSink != null) remoteTrack.removeSink(remoteSink);
            localSink = local;remoteSink = remote;
            if (videoTrack != null && localSink != null) videoTrack.addSink(localSink);
            if (remoteTrack != null && remoteSink != null) remoteTrack.addSink(remoteSink);
        });
    }

    private void createCamera() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context)
                ? new Camera2Enumerator(context) : new Camera1Enumerator(true);
        CameraVideoCapturer.CameraEventsHandler events = new CameraVideoCapturer.CameraEventsHandler() {
            public void onCameraError(String error) { failure("Camera failed"); }
            public void onCameraDisconnected() { failure("Camera disconnected"); }
            public void onCameraFreezed(String error) { failure("Camera stopped producing frames"); }
            public void onCameraOpening(String name) {}
            public void onFirstFrameAvailable() { execute(() -> { if (cameraRunning && videoEnabled) listener.cameraFirstFrameAvailable(); }); }
            public void onCameraClosed() {}
        };
        String[] names = enumerator.getDeviceNames();
        for (String name : names) if (enumerator.isFrontFacing(name)) {
            camera = enumerator.createCapturer(name, events);
            if (camera != null) { frontFacing = true;break; }
        }
        if (camera == null) for (String name : names) {
            camera = enumerator.createCapturer(name, events);
            if (camera != null) { frontFacing = enumerator.isFrontFacing(name);break; }
        }
        if (camera == null) throw new IllegalStateException("Camera unavailable");
        texture = SurfaceTextureHelper.create("xmpp-call-camera", eglContext);
        if (texture == null) throw new IllegalStateException("Camera texture unavailable");
        videoSource = factory.createVideoSource(false);
        camera.initialize(texture, context, videoSource.getCapturerObserver());
        videoTrack = factory.createVideoTrack("monogram-video", videoSource);
        videoTrack.setEnabled(false);
        if (localSink != null) videoTrack.addSink(localSink);
    }

    private void startCamera() {
        if (closed.get()) return;
        if (!cameraRunning) {
            camera.startCapture(1280, 720, 30);
            cameraRunning = true;
        }
        videoTrack.setEnabled(true);videoEnabled = true;
    }

    private void stopCamera() {
        try { camera.stopCapture(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Camera stop interrupted", interrupted);
        }
    }

    private PeerConnection.Observer observer() {
        return new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState state) {}
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.FAILED) failure("ICE connection failed");
            }
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                execute(() -> {
                    if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                        if (disconnectedTimeout != null) disconnectedTimeout.cancel(false);
                        disconnectedTimeout = null;
                        if (!connected) { connected = true;listener.connected(); }
                    }
                    else if (state == PeerConnection.PeerConnectionState.DISCONNECTED && disconnectedTimeout == null)
                        disconnectedTimeout = worker.schedule(() -> fail("Media connection lost"), 20, TimeUnit.SECONDS);
                    else if (state == PeerConnection.PeerConnectionState.FAILED) fail("Media connection failed");
                });
            }
            public void onIceConnectionReceivingChange(boolean receiving) {}
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
            public void onIceCandidate(IceCandidate candidate) {
                execute(() -> {
                    if (localPublished) emitCandidate(candidate);
                    else {
                        if (localCandidates.size() >= MAX_CANDIDATES) throw new IllegalStateException("Too many local ICE candidates");
                        localCandidates.add(candidate);
                    }
                });
            }
            public void onIceCandidatesRemoved(IceCandidate[] removed) {}
            public void onAddStream(MediaStream stream) {}
            public void onRemoveStream(MediaStream stream) {}
            public void onDataChannel(DataChannel channel) { execute(channel::close); }
            public void onRenegotiationNeeded() {}
            public void onTrack(RtpTransceiver transceiver) {
                execute(() -> {
                    MediaStreamTrack track = transceiver.getReceiver().track();
                    if (!(track instanceof VideoTrack)) return;
                    if (remoteTrack != null && remoteSink != null) remoteTrack.removeSink(remoteSink);
                    remoteTrack = (VideoTrack) track;
                    if (remoteSink != null) remoteTrack.addSink(remoteSink);
                    listener.remoteVideo(true);
                });
            }
            public void onRemoveTrack(RtpReceiver receiver) {
                execute(() -> {
                    if (remoteTrack == null || receiver.track() != remoteTrack) return;
                    if (remoteSink != null) remoteTrack.removeSink(remoteSink);
                    remoteTrack = null;listener.remoteVideo(false);
                });
            }
        };
    }

    private class DescriptionObserver implements SdpObserver {
        public void onCreateSuccess(SessionDescription description) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String error) { failure("Media description creation failed"); }
        public void onSetFailure(String error) { failure("Media description rejected"); }
    }

    private void emitCandidate(IceCandidate candidate) {
        if (!closed.get()) listener.candidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
    }

    private void requirePrepared() {
        if (!prepared || peer == null) throw new IllegalStateException("Media not prepared");
    }

    private void failure(String reason) { execute(() -> fail(reason)); }

    private void execute(Runnable action) {
        if (closed.get()) return;
        try {
            worker.execute(() -> {
                if (closed.get()) return;
                try { action.run(); }
                catch (RuntimeException | LinkageError failure) { fail("Media operation failed"); }
            });
        } catch (RejectedExecutionException stopped) { /* Closing fences native callbacks and queued commands. */ }
    }

    private void fail(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        try { listener.failed(reason); }
        finally { finishRelease(); }
    }

    @Override public void close() { close(null); }

    /** Completion follows disposal; an already disposed call completes immediately on the caller. */
    public void close(Runnable complete) {
        boolean alreadyDisposed, begin;
        synchronized (closeLock) {
            alreadyDisposed = disposed;
            if (!alreadyDisposed && complete != null) closeCallbacks.add(complete);
            begin = !alreadyDisposed && closed.compareAndSet(false, true);
        }
        if (alreadyDisposed) completion(complete);
        else if (begin) worker.execute(this::finishRelease);
    }

    private void finishRelease() {
        List<Runnable> callbacks;
        try { release(); }
        finally {
            synchronized (closeLock) {
                disposed = true;
                callbacks = new ArrayList<>(closeCallbacks);closeCallbacks.clear();
            }
            worker.shutdown();
            for (Runnable callback : callbacks) completion(callback);
        }
    }

    private static void completion(Runnable callback) {
        if (callback == null) return;
        try { callback.run(); }
        catch (RuntimeException ignored) { /* One caller must not prevent the other disposal callbacks. */ }
    }

    private void release() {
        if (disconnectedTimeout != null) disconnectedTimeout.cancel(false);
        disconnectedTimeout = null;
        candidates.clear();localCandidates.clear();prepared = false;videoEnabled = false;eglContext = null;
        // Keep disposing independent resources even if a platform camera has already disappeared.
        dispose(() -> { if (audioTrack != null) audioTrack.setEnabled(false); });
        dispose(() -> { if (videoTrack != null) videoTrack.setEnabled(false); });
        dispose(() -> { if (videoTrack != null && localSink != null) videoTrack.removeSink(localSink); });
        dispose(() -> { if (remoteTrack != null && remoteSink != null) remoteTrack.removeSink(remoteSink); });
        dispose(() -> { if (camera != null) camera.stopCapture(); });
        dispose(() -> { if (camera != null) camera.dispose(); });camera = null;cameraRunning = false;
        dispose(() -> { if (peer != null) peer.dispose(); });peer = null;remoteTrack = null;
        dispose(() -> { if (audioTrack != null) audioTrack.dispose(); });audioTrack = null;
        dispose(() -> { if (videoTrack != null) videoTrack.dispose(); });videoTrack = null;
        dispose(() -> { if (audioSource != null) audioSource.dispose(); });audioSource = null;
        dispose(() -> { if (videoSource != null) videoSource.dispose(); });videoSource = null;
        dispose(() -> { if (texture != null) texture.dispose(); });texture = null;
        dispose(() -> { if (factory != null) factory.dispose(); });factory = null;
        dispose(() -> { if (audioDevice != null) audioDevice.release(); });audioDevice = null;
        dispose(() -> { if (egl != null) egl.release(); });egl = null;
        localSink = null;remoteSink = null;
    }

    private interface Disposal { void run() throws InterruptedException; }
    private static void dispose(Disposal action) {
        try { action.run(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (RuntimeException ignored) { /* Continue releasing the other native owners. */ }
    }
}
