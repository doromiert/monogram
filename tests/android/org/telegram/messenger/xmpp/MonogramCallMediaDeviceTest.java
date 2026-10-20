package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import com.negzero.monogram.xmpp.calls.JingleCodec;
import com.negzero.monogram.xmpp.calls.JingleSdp;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.telegram.messenger.NativeLoader;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** Disposable shell process: no app Application, accounts, capture, local SDP installation or signaling. */
public final class MonogramCallMediaDeviceTest {
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception {
        Field value = owner.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(owner);
    }
    static void unopened(JavaAudioDeviceModule audio) throws Exception {
        check(field(field(audio,"audioInput"),"audioRecord") == null,"Smoke opened AudioRecord");
        check(field(field(audio,"audioOutput"),"audioTrack") == null,"Smoke opened AudioTrack");
    }

    public static void main(String[] args) {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(45000); } catch (InterruptedException finished) { return; }
            System.err.println("FAIL: native media smoke timed out");System.exit(1);
        },"media-smoke-watchdog");
        watchdog.setDaemon(true);watchdog.start();
        try { run(args);watchdog.interrupt();System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(System.err);System.exit(1); }
    }

    static Context bootstrap(File directory) throws Exception {
        File root = directory.getCanonicalFile();
        check(root.getPath().matches("/data/local/tmp/monogram-call-(media-smoke|interop)\\.[A-Za-z0-9]+"),"Unexpected smoke directory");
        File library = new File(root,"libtmessages.49.so");
        check(library.isFile(),"New APK library missing");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context framework = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = new ContextWrapper(framework) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
            @Override public File getCacheDir() { return root; }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { throw new AssertionError("Smoke must not access preferences"); }
            @Override public File getDatabasePath(String name) { throw new AssertionError("Smoke must not access databases"); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) { throw new AssertionError("Smoke must not open databases"); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) { throw new AssertionError("Smoke must not open databases"); }
        };
        // Bypass NativeLoader's installed-library discovery ONLY in this isolated process, after
        // loading the exact supplied APK's library. No installed package or account paths are used.
        System.load(library.getCanonicalPath());
        Field loaded = NativeLoader.class.getDeclaredField("nativeLoaded");loaded.setAccessible(true);loaded.setBoolean(null,true);
        MonogramWebRtcBootstrap.initialize(context);MonogramWebRtcBootstrap.initialize(context);
        boolean mapped = false;
        for (String line : Files.readAllLines(new File("/proc/self/maps").toPath())) if (line.contains("libtmessages.")) {
            check(line.endsWith(library.getCanonicalPath()),"Unexpected media library mapped");mapped = true;
        }
        check(mapped,"Supplied media library was not mapped");
        System.out.println("PASS: supplied APK native bootstrap is repeatable");
        return context;
    }

    private static void run(String[] args) throws Exception {
        check(args.length == 1,"Disposable directory required");
        Context context = bootstrap(new File(args[0]));

        EglBase egl = null;JavaAudioDeviceModule audio = null;PeerConnectionFactory factory = null;
        PeerConnection peer = null;AudioSource audioSource = null;AudioTrack audioTrack = null;
        VideoSource videoSource = null;VideoTrack videoTrack = null;
        AtomicInteger captureStarts = new AtomicInteger(), iceCandidates = new AtomicInteger();
        try {
            egl = EglBase.create();
            DefaultVideoEncoderFactory encoders = new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true);
            DefaultVideoDecoderFactory decoders = new DefaultVideoDecoderFactory(egl.getEglBaseContext());
            check(encoders.getSupportedCodecs().length > 0 && decoders.getSupportedCodecs().length > 0,"Native video codecs unavailable");
            // Construction queries capabilities; AudioRecord/AudioTrack open only on native
            // InitRecording/InitPlayout. No descriptions are installed and no channels activated.
            audio = JavaAudioDeviceModule.builder(context)
                    .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback() {
                        public void onWebRtcAudioRecordStart() { captureStarts.incrementAndGet(); }
                        public void onWebRtcAudioRecordStop() {}
                    }).createAudioDeviceModule();
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.disableNetworkMonitor = true;options.networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_ANY;
            factory = PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(audio)
                    .setVideoEncoderFactory(encoders).setVideoDecoderFactory(decoders).createPeerConnectionFactory();
            unopened(audio);
            audioSource = factory.createAudioSource(new MediaConstraints());audioTrack = factory.createAudioTrack("smoke-audio",audioSource);audioTrack.setEnabled(false);
            videoSource = factory.createVideoSource(false);videoTrack = factory.createVideoTrack("smoke-video",videoSource);videoTrack.setEnabled(false);
            PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(Collections.emptyList());
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
            configuration.iceTransportsType = PeerConnection.IceTransportsType.NONE;
            peer = factory.createPeerConnection(configuration,new NoNetworkObserver(iceCandidates));
            check(peer != null,"Native PeerConnection unavailable");
            peer.setAudioRecording(false);peer.setAudioPlayout(false);
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
            CountDownLatch done = new CountDownLatch(1);AtomicReference<SessionDescription> offer = new AtomicReference<>();AtomicReference<String> error = new AtomicReference<>();
            peer.createOffer(new SdpObserver() {
                public void onCreateSuccess(SessionDescription description) { offer.set(description);done.countDown(); }
                public void onCreateFailure(String reason) { error.set("Native offer creation failed");done.countDown(); }
                public void onSetSuccess() { error.set("Unexpected installed description");done.countDown(); }
                public void onSetFailure(String reason) { error.set("Unexpected installed description");done.countDown(); }
            },new MediaConstraints());
            check(done.await(15,TimeUnit.SECONDS) && error.get() == null && offer.get() != null,"Recv-only offer failed");
            JingleCodec.CallDescription parsed = JingleSdp.parse(offer.get().description);
            check(parsed.video() && parsed.contents.size() == 2,"Native offer must contain audio and video");
            JingleCodec.CallDescription sdpRoundtrip = JingleSdp.parse(JingleSdp.serialize(parsed));
            JingleCodec.initialize();JingleCodec.Packet packet = new JingleCodec.Packet(JingleCodec.description(sdpRoundtrip,true));
            packet.setType(IQ.Type.set);packet.setStanzaId("isolated-native-smoke");
            JingleCodec.Packet decoded = PacketParserUtils.parseStanza(packet.toXML().toString());
            JingleCodec.CallDescription xmlRoundtrip = JingleSdp.fromJingle(decoded.element,true);
            check(xmlRoundtrip.video() && xmlRoundtrip.bundle.equals(parsed.bundle),"Native SDP/Jingle roundtrip lost media grouping");
            check(JingleSdp.parse(JingleSdp.serialize(xmlRoundtrip)).contents.size() == 2,"Jingle serialization lost media");
            check(peer.getLocalDescription() == null && peer.getRemoteDescription() == null,"Smoke must never install descriptions");
            unopened(audio);
            check(captureStarts.get() == 0 && iceCandidates.get() == 0,"Smoke unexpectedly activated media/network");
            System.out.println("PASS: native audio/video factory, uncaptured tracks, recv-only offer, SDP/Jingle XML roundtrip; no installed descriptions, capture or ICE");
        } finally {
            if (peer != null) peer.dispose();
            if (audioTrack != null) audioTrack.dispose();if (videoTrack != null) videoTrack.dispose();
            if (audioSource != null) audioSource.dispose();if (videoSource != null) videoSource.dispose();
            if (factory != null) factory.dispose();if (audio != null) audio.release();if (egl != null) egl.release();
        }
        check(captureStarts.get() == 0 && iceCandidates.get() == 0,"Smoke activated media during disposal");
        System.out.println("PASS: native media smoke disposal");
    }

    private static final class NoNetworkObserver implements PeerConnection.Observer {
        private final AtomicInteger candidates;
        NoNetworkObserver(AtomicInteger candidates) { this.candidates = candidates; }
        public void onSignalingChange(PeerConnection.SignalingState state) {}
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
        public void onIceConnectionReceivingChange(boolean receiving) {}
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        public void onIceCandidate(IceCandidate candidate) { candidates.incrementAndGet(); }
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        public void onAddStream(MediaStream stream) {}
        public void onRemoveStream(MediaStream stream) {}
        public void onDataChannel(DataChannel channel) { throw new AssertionError("Smoke must not open data channels"); }
        public void onRenegotiationNeeded() {}
    }
}
