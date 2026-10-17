package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.calls.JingleCodec;
import com.negzero.monogram.xmpp.calls.JingleSdp;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** Synthetic media only: independent Firefox engine, no phone capture/playback, app or XMPP account. */
public final class MonogramCallInteropDeviceTest {
    private static final int MAX_MESSAGE = 256 * 1024, MAX_CANDIDATES = 128;
    private final BlockingQueue<Runnable> events = new ArrayBlockingQueue<>(256);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicInteger frames = new AtomicInteger(), audioStarts = new AtomicInteger();
    private final AtomicBoolean connected = new AtomicBoolean(), dtls = new AtomicBoolean();
    private final AtomicLong audioPackets = new AtomicLong();
    private final List<JSONObject> earlyLocal = new ArrayList<>(), earlyRemote = new ArrayList<>();
    private final List<VideoTrack> remoteTracks = new ArrayList<>();
    private String base, mode;
    private EglBase egl;
    private JavaAudioDeviceModule audio;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoSource source;
    private VideoTrack video;
    private VideoSink sink;
    private ScheduledExecutorService generator;
    private boolean remoteSet, localSent, ready, descriptionReceived, audioExpected, reported, completed;
    private int localCount, remoteCount;

    public static void main(String[] args) {
        MonogramCallInteropDeviceTest test = new MonogramCallInteropDeviceTest();
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(135000); } catch (InterruptedException done) { return; }
            System.err.println("FAIL: interop WATCHDOG");System.exit(1);
        },"interop-watchdog");
        watchdog.setDaemon(true);watchdog.start();
        int status = 1;
        try { test.run(args);status = 0; }
        catch (Throwable error) {
            String reason = test.failure.get();if (reason == null) reason = error instanceof Failure ? error.getMessage() : "NATIVE_FAILURE";
            try { if (test.base != null) test.request("result/native",new JSONObject().put("pass",false).put("reason",reason)); }
            catch (Exception ignored) {}
            System.err.println("FAIL: interop " + reason);
        } finally { test.release();watchdog.interrupt(); }
        System.exit(status);
    }

    private static final class Failure extends RuntimeException { Failure(String reason) { super(reason); } }
    private static void require(boolean condition,String reason) { if (!condition) throw new Failure(reason); }

    private void run(String[] args) throws Exception {
        require(args.length == 3,"ARGUMENTS");base = args[1];mode = args[2];
        require(base.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}/[0-9a-f]{32}/"),"ARGUMENTS");
        require(mode.equals("native-offer") || mode.equals("browser-offer"),"ARGUMENTS");
        Context context = MonogramCallMediaDeviceTest.bootstrap(new File(args[0]));
        JingleCodec.initialize();
        egl = EglBase.create();
        audio = JavaAudioDeviceModule.builder(context)
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback() {
                    public void onWebRtcAudioRecordStart() { audioStarts.incrementAndGet();failure.compareAndSet(null,"CAPTURE_STARTED"); }
                    public void onWebRtcAudioRecordStop() {}
                }).setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback() {
                    public void onWebRtcAudioTrackStart() { audioStarts.incrementAndGet();failure.compareAndSet(null,"PLAYBACK_STARTED"); }
                    public void onWebRtcAudioTrackStop() {}
                }).createAudioDeviceModule();
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();options.disableNetworkMonitor = true;
        factory = PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(audio)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext())).createPeerConnectionFactory();
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(Collections.emptyList());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        configuration.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        peer = factory.createPeerConnection(configuration,observer());require(peer != null,"PEER_UNAVAILABLE");
        peer.setAudioRecording(false);peer.setAudioPlayout(false);
        peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        source = factory.createVideoSource(false);video = factory.createVideoTrack("synthetic-video",source);
        peer.addTrack(video,Collections.singletonList("synthetic-stream"));
        sink = frame -> { if (frame.getBuffer().getWidth() > 0 && frame.getBuffer().getHeight() > 0) frames.incrementAndGet(); };
        source.getCapturerObserver().onCapturerStarted(true);
        generator = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"synthetic-i420"));
        AtomicInteger sequence = new AtomicInteger();
        generator.scheduleAtFixedRate(() -> {
            JavaI420Buffer buffer = null;VideoFrame frame = null;
            try {
                buffer = JavaI420Buffer.allocate(320,240);int n = sequence.incrementAndGet();
                fill(buffer.getDataY(),(byte)(32 + n % 180));fill(buffer.getDataU(),(byte)90);fill(buffer.getDataV(),(byte)180);
                frame = new VideoFrame(buffer,0,System.nanoTime());buffer = null;
                source.getCapturerObserver().onFrameCaptured(frame);
            } catch (Throwable error) { failure.compareAndSet(null,"SYNTHETIC_FRAME"); }
            finally { if (frame != null) frame.release();else if (buffer != null) buffer.release(); }
        },0,100,TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120), nextStats = 0;
        while (System.nanoTime() < deadline) {
            drain();checkCapture();
            if (failure.get() != null) throw new Failure(failure.get());
            long now = System.nanoTime();
            if (now >= nextStats) { stats();nextStats = now + TimeUnit.SECONDS.toNanos(1); }
            if (!reported && connected.get() && dtls.get() && frames.get() >= 10 && (!audioExpected || audioPackets.get() > 0)) {
                JSONObject result = new JSONObject().put("pass",true).put("frames",frames.get()).put("audioPackets",audioPackets.get())
                        .put("audioExpected",audioExpected).put("dtls",true).put("mode",mode).put("capture",false);
                request("result/native",result);
                reported = true;
                System.out.println("PASS: independent media connected, decoded video frames="+frames.get()+", inbound audio packets="+audioPackets.get()+", capture=false");
            }
            incoming(request("native",null));
            if (completed) return;
        }
        throw new Failure(reported ? "PEER_COMPLETION_TIMEOUT" : !ready ? "BROWSER_NOT_READY" : !connected.get() ? "ICE_TIMEOUT" : !dtls.get() ? "DTLS_TIMEOUT" : frames.get() < 10 ? "VIDEO_TIMEOUT" : "AUDIO_TIMEOUT");
    }

    private static void fill(ByteBuffer buffer,byte value) { while (buffer.hasRemaining()) buffer.put(value); }
    private void checkCapture() throws Exception {
        try { MonogramCallMediaDeviceTest.unopened(audio); }
        catch (AssertionError opened) { throw new Failure("CAPTURE_OPENED"); }
        require(audioStarts.get() == 0,"CAPTURE_STARTED");
    }
    private void enqueue(Runnable event) { if (!events.offer(event)) failure.compareAndSet(null,"EVENT_LIMIT"); }
    private void drain() { Runnable event;while ((event = events.poll()) != null) event.run(); }

    private void incoming(JSONObject message) throws Exception {
        String type = message.getString("type");
        if (type.equals("wait")) return;
        if (type.equals("error")) throw new Failure("BROWSER_FAILED");
        if (type.equals("done")) { require(reported,"EARLY_COMPLETION");completed = true;return; }
        if (type.equals("ready")) {
            require(!ready,"DUPLICATE_READY");ready = true;audioExpected = message.optBoolean("audio",false);
            if (mode.equals("native-offer")) create(true);return;
        }
        require(ready,"EARLY_MESSAGE");
        if (type.equals("candidate")) {
            require(++remoteCount <= MAX_CANDIDATES,"CANDIDATE_LIMIT");
            if (remoteSet) candidate(message);else earlyRemote.add(message);return;
        }
        require(!descriptionReceived && ((mode.equals("browser-offer") && type.equals("offer")) || (mode.equals("native-offer") && type.equals("answer"))),"UNEXPECTED_DESCRIPTION");
        descriptionReceived = true;boolean offer = type.equals("offer");
        String sdp;
        try { sdp = roundtrip(message.getString("sdp"),offer); }
        catch (IllegalArgumentException invalid) {
            throw new Failure("Rejected media cannot be negotiated".equals(invalid.getMessage()) ? "REMOTE_REJECTED_MEDIA" : "REMOTE_JINGLE_MAPPING");
        }
        catch (Failure invalid) { throw invalid; }
        catch (Exception invalid) { throw new Failure("REMOTE_JINGLE_MAPPING"); }
        peer.setRemoteDescription(new DescriptionObserver() {
            @Override public void onSetSuccess() { enqueue(() -> {
                remoteSet = true;
                try { for (JSONObject pending : earlyRemote) candidate(pending);earlyRemote.clear(); }
                catch (Failure invalid) { throw invalid; }
                catch (Exception error) { throw new Failure("CANDIDATE_JSON"); }
                if (offer) create(false);
            }); }
        },new SessionDescription(offer ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER,sdp));
    }

    private void candidate(JSONObject message) throws Exception {
        Object rawMid = message.opt("mid"),rawValue = message.opt("candidate"),rawIndex = message.opt("index");
        require(rawMid instanceof String,"CANDIDATE_MID_TYPE");String mid = (String) rawMid;
        require(!mid.isEmpty() && mid.length() <= 256,"CANDIDATE_MID_LENGTH");
        require(rawIndex instanceof Number,"CANDIDATE_INDEX_TYPE");Number number = (Number) rawIndex;int index = number.intValue();
        require(number.doubleValue() == index && index >= 0 && index < 2,"CANDIDATE_INDEX_RANGE");
        require(rawValue instanceof String,"CANDIDATE_VALUE_TYPE");String value = (String) rawValue;
        require(!value.isEmpty(),"CANDIDATE_EMPTY");
        require(value.length() <= 8192 && value.startsWith("candidate:"),"CANDIDATE_VALUE_FORMAT");
        boolean accepted;
        try { accepted = peer.addIceCandidate(new IceCandidate(mid,index,value)); }
        catch (RuntimeException invalid) { throw new Failure("CANDIDATE_NATIVE_ERROR"); }
        require(accepted,"CANDIDATE_REJECTED");
    }

    private void create(boolean offer) {
        SdpObserver callback = new DescriptionObserver() {
            @Override public void onCreateSuccess(SessionDescription description) { enqueue(() -> {
                String wire;
                try { wire = roundtrip(description.description,offer); }
                catch (Exception error) { throw new Failure("LOCAL_JINGLE_MAPPING"); }
                peer.setLocalDescription(new DescriptionObserver() {
                    @Override public void onSetSuccess() { enqueue(() -> {
                        try {
                            request("browser",new JSONObject().put("type",offer ? "offer" : "answer").put("sdp",wire));localSent = true;
                            for (JSONObject pending : earlyLocal) request("browser",pending);earlyLocal.clear();
                        } catch (Exception error) { throw new Failure("SIGNALING_HTTP"); }
                    }); }
                },description);
            }); }
        };
        if (offer) peer.createOffer(callback,new MediaConstraints());else peer.createAnswer(callback,new MediaConstraints());
    }

    private static String roundtrip(String sdp,boolean offer) throws Exception {
        require(sdp.length() <= MAX_MESSAGE,"DESCRIPTION_LIMIT");
        JingleCodec.CallDescription parsed = JingleSdp.parse(sdp);
        JingleCodec.Packet packet = new JingleCodec.Packet(JingleCodec.description(parsed,offer));packet.setType(IQ.Type.set);packet.setStanzaId("local-media-only");
        JingleCodec.Packet decoded = PacketParserUtils.parseStanza(packet.toXML().toString());
        JingleCodec.CallDescription result = JingleSdp.fromJingle(decoded.element,offer);
        require(result.video() && result.contents.size() == 2,"MISSING_MEDIA");
        return JingleSdp.serialize(result);
    }

    private class DescriptionObserver implements SdpObserver {
        public void onCreateSuccess(SessionDescription description) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String reason) { failure.compareAndSet(null,"DESCRIPTION_CREATE"); }
        public void onSetFailure(String reason) { failure.compareAndSet(null,"DESCRIPTION_APPLY"); }
    }

    private PeerConnection.Observer observer() {
        return new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState state) {}
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.FAILED) failure.compareAndSet(null,"ICE_FAILED");
            }
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                connected.set(state == PeerConnection.PeerConnectionState.CONNECTED);
                if (state == PeerConnection.PeerConnectionState.FAILED) failure.compareAndSet(null,"CONNECTION_FAILED");
            }
            public void onIceConnectionReceivingChange(boolean receiving) {}
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
            public void onIceCandidate(IceCandidate candidate) { enqueue(() -> {
                require(++localCount <= MAX_CANDIDATES,"CANDIDATE_LIMIT");
                try {
                    JSONObject message = new JSONObject().put("type","candidate").put("mid",candidate.sdpMid).put("index",candidate.sdpMLineIndex).put("candidate",candidate.sdp);
                    if (localSent) request("browser",message);else earlyLocal.add(message);
                } catch (Exception error) { throw new Failure("SIGNALING_HTTP"); }
            }); }
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            public void onAddStream(MediaStream stream) {}
            public void onRemoveStream(MediaStream stream) {}
            public void onDataChannel(DataChannel channel) { failure.compareAndSet(null,"UNEXPECTED_DATA_CHANNEL"); }
            public void onRenegotiationNeeded() {}
            public void onTrack(RtpTransceiver transceiver) { enqueue(() -> {
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track instanceof VideoTrack) { VideoTrack remote = (VideoTrack) track;remoteTracks.add(remote);remote.addSink(sink); }
            }); }
        };
    }

    private void stats() {
        peer.getStats(report -> {
            long packets = 0;boolean secure = false;
            for (RTCStats entry : report.getStatsMap().values()) {
                Map<String,Object> values = entry.getMembers();
                if (entry.getType().equals("transport") && "connected".equals(values.get("dtlsState"))) secure = true;
                if (entry.getType().equals("inbound-rtp") && ("audio".equals(values.get("kind")) || "audio".equals(values.get("mediaType")))) {
                    Object received = values.get("packetsReceived");if (received instanceof Number) packets += ((Number) received).longValue();
                }
            }
            dtls.set(secure);audioPackets.set(packets);
        });
    }

    private JSONObject request(String path,JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base+path).openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(4000);connection.setInstanceFollowRedirects(false);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);require(bytes.length <= MAX_MESSAGE,"MESSAGE_LIMIT");
                connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            require(connection.getResponseCode() == 200,"SIGNALING_HTTP");
            try (InputStream in = connection.getInputStream();ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];for (int n;(n=in.read(buffer)) != -1;) { require(result.size()+n <= MAX_MESSAGE,"MESSAGE_LIMIT");result.write(buffer,0,n); }
                return result.size() == 0 ? new JSONObject() : new JSONObject(new String(result.toByteArray(),StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private void release() {
        if (generator != null) {
            generator.shutdownNow();try { generator.awaitTermination(3,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (source != null) source.getCapturerObserver().onCapturerStopped();
        for (VideoTrack remote : remoteTracks) remote.removeSink(sink);remoteTracks.clear();
        if (peer != null) peer.dispose();
        if (video != null) video.dispose();if (source != null) source.dispose();
        if (factory != null) factory.dispose();if (audio != null) audio.release();if (egl != null) egl.release();
    }
}
