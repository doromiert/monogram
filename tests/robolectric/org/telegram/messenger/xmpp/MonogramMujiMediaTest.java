package org.telegram.messenger.xmpp;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.webrtc.MonogramWebRtcBootstrap;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,application=Application.class,sdk=28,shadows=MonogramMujiMediaTest.Bootstrap.class)
public class MonogramMujiMediaTest {
    @Implements(value=MonogramWebRtcBootstrap.class,isInAndroidSdk=false)
    public static class Bootstrap {
        static AtomicInteger starts=new AtomicInteger();
        @Implementation public static void initialize(Context context){starts.incrementAndGet();throw new IllegalStateException("fixture native boundary");}
    }
    static class Events implements MonogramMujiMedia.Listener {
        final CountDownLatch failed=new CountDownLatch(1);final AtomicInteger callbacks=new AtomicInteger();
        public void ready(){callbacks.incrementAndGet();}public void description(String peer,boolean offer,String sdp){callbacks.incrementAndGet();}public void candidate(String peer,String mid,int line,String value){callbacks.incrementAndGet();}public void remoteApplied(String peer){callbacks.incrementAndGet();}public void connected(String peer){callbacks.incrementAndGet();}public void failed(String peer,String reason){failed.countDown();}public void video(String peer,boolean available){callbacks.incrementAndGet();}
    }
    @Test public void mediaRequiresExplicitPermissionsAndCloseNeverStartsQueuedCapture()throws Exception {
        Bootstrap.starts.set(0);Events events=new Events();MonogramMujiMedia media=new MonogramMujiMedia(RuntimeEnvironment.getApplication(),events);
        media.prepare(false,Collections.emptyList(),false);assertTrue(events.failed.await(2,TimeUnit.SECONDS));assertEquals(0,Bootstrap.starts.get());assertEquals(0,events.callbacks.get());media.close();
        ScheduledExecutorService worker=ReflectionHelpers.getField(media,"worker");assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));
        Application app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA);
        Events queued=new Events();MonogramMujiMedia pending=new MonogramMujiMedia(app,queued);ScheduledExecutorService queue=ReflectionHelpers.getField(pending,"worker");CountDownLatch gate=new CountDownLatch(1);
        queue.execute(()->{try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        pending.prepare(true,Collections.emptyList(),false);pending.close();gate.countDown();assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS));assertEquals(0,Bootstrap.starts.get());assertEquals(0,queued.callbacks.get());
    }
    @Test public void contentNamesFollowTheAdvertisedMujiMappingWithoutCorruptingOtherSdpFields(){
        String source="v=0\r\na=group:BUNDLE 0 1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=msid:stream0 track1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:1\r\n";
        String named=MonogramMujiMedia.namedContents(source);assertTrue(named.contains("a=group:BUNDLE voice webcam\r\n"));assertTrue(named.contains("a=mid:voice\r\n"));assertTrue(named.contains("a=mid:webcam\r\n"));assertTrue(named.contains("a=msid:stream0 track1\r\n"));
    }
}
