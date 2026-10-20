package org.telegram.messenger.xmpp;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import java.util.Collections;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.webrtc.MonogramWebRtcBootstrap;
import org.webrtc.PeerConnection;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, application=Application.class, sdk=28, shadows=MonogramCallMediaTest.Bootstrap.class)
public class MonogramCallMediaTest {
    @Implements(MonogramWebRtcBootstrap.class)
    public static class Bootstrap {
        static final AtomicInteger starts = new AtomicInteger();
        static boolean stop;
        @Implementation public static void initialize(Context context) {
            starts.incrementAndGet();
            if (stop) throw new IllegalStateException("fixture bootstrap boundary");
        }
    }

    static final class Events implements MonogramCallMedia.Listener {
        final AtomicInteger events = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        public void localDescription(boolean offer,String sdp) { events.incrementAndGet(); }
        public void candidate(String mid,int line,String sdp) { events.incrementAndGet(); }
        public void remoteDescriptionApplied() { events.incrementAndGet(); }
        public void connected() { events.incrementAndGet(); }
        public void remoteVideo(boolean available) { events.incrementAndGet(); }
        public void failed(String reason) { failures.incrementAndGet(); }
    }

    @Test public void closeFencesQueuedPreparationCommandsAndLateCallbacksWithoutStartingCapture() throws Exception {
        Bootstrap.starts.set(0);Bootstrap.stop = false;
        Events events = new Events();
        MonogramCallMedia media = new MonogramCallMedia(RuntimeEnvironment.getApplication(),events);
        ScheduledExecutorService queue = ReflectionHelpers.getField(media,"worker");
        AtomicInteger cameraDisposed = new AtomicInteger(), cameraStopped = new AtomicInteger(), completions = new AtomicInteger();
        org.webrtc.CameraVideoCapturer camera = (org.webrtc.CameraVideoCapturer) Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class[]{org.webrtc.CameraVideoCapturer.class},(proxy,method,args) -> {
                    if (method.getName().equals("stopCapture")) cameraStopped.incrementAndGet();
                    if (method.getName().equals("dispose")) cameraDisposed.incrementAndGet();
                    return null;
                });
        ReflectionHelpers.setField(media,"camera",camera);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        queue.execute(() -> { entered.countDown();try { release.await(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        try {
            media.setSinks(null,null);media.prepare(true,Collections.emptyList(),false);media.offer();
            assertNull(media.getEglContext());assertEquals(0,Bootstrap.starts.get());
            media.close(() -> { assertEquals(1,cameraDisposed.get());assertEquals(1,cameraStopped.get());completions.incrementAndGet(); });
            media.close(completions::incrementAndGet);media.close();
            assertEquals(0,completions.get());
            ReflectionHelpers.callInstanceMethod(media,"failure",ReflectionHelpers.ClassParameter.from(String.class,"late camera callback"));
        } finally { release.countDown(); }
        assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS));
        assertEquals(0,Bootstrap.starts.get());assertEquals(0,events.events.get());assertEquals(0,events.failures.get());
        assertFalse(media.isVideoEnabled());assertNull(media.getEglContext());
        assertEquals(2,completions.get());media.close(completions::incrementAndGet);assertEquals(3,completions.get());
    }

    @Test public void relayOnlyFailsClosedBeforeEngineOrCaptureWithoutTurn() throws Exception {
        Bootstrap.starts.set(0);Bootstrap.stop = false;
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        Events events = new Events();MonogramCallMedia media = new MonogramCallMedia(app,events);
        ScheduledExecutorService queue = ReflectionHelpers.getField(media,"worker");
        media.prepare(false,Collections.singletonList(PeerConnection.IceServer.builder("stun:example.org").createIceServer()),true);
        assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS));
        assertEquals(1,events.failures.get());assertEquals(0,events.events.get());assertEquals(0,Bootstrap.starts.get());
        assertNull(media.getEglContext());media.close();
        assertTrue(MonogramCallMedia.hasTurn(Collections.singletonList(PeerConnection.IceServer.builder("turns:example.org:5349").createIceServer())));
    }

    @Test public void cameraOffBeforePreparationIsRetainedAndDoesNotRequireCameraPermission() throws Exception {
        Bootstrap.starts.set(0);Bootstrap.stop = true;
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        Events events = new Events();MonogramCallMedia media = new MonogramCallMedia(app,events);
        ScheduledExecutorService queue = ReflectionHelpers.getField(media,"worker");
        try {
            media.setVideoEnabled(false);media.prepare(true,Collections.emptyList(),false);
            assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS));
            assertEquals("Camera-off request must reach engine setup without asking to capture",1,Bootstrap.starts.get());
            assertFalse((Boolean)ReflectionHelpers.getField(media,"videoRequested"));
            assertFalse(media.isVideoEnabled());assertEquals(1,events.failures.get());
        } finally { Bootstrap.stop = false;media.close(); }
    }
}
