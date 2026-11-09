package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import com.negzero.monogram.xmpp.XmppProfile;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramProfileContentionTest.SlowImage.class)
public class MonogramProfileContentionTest {
    @Implements(value=MonogramProfiles.class,isInAndroidSdk=false)
    public static class SlowImage {
        static CountDownLatch entered,release;
        @Implementation protected static byte[] png(byte[] bytes) throws Exception {
            entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test preparation timed out");return bytes;
        }
    }
    @Test public void avatarReadsDoNotWaitForProfileImagePreparation() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();String file="profile-contention.db";context.deleteDatabase(file);
        SlowImage.entered=new CountDownLatch(1);SlowImage.release=new CountDownLatch(1);
        ExecutorService writer=Executors.newFixedThreadPool(2),reader=Executors.newSingleThreadExecutor();
        try(MonogramStore store=new MonogramStore(context,file)) {
            long peer=store.peer("contact@example.org");store.getWritableDatabase();
            XmppProfile update=new XmppProfile("<vCard xmlns='vcard-temp'><NICKNAME>Updated</NICKNAME></vCard>",null,new byte[0]);
            Future<?> preparing=writer.submit(() -> {try{store.profile(peer,update);}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(SlowImage.entered.await(5,TimeUnit.SECONDS));
            CountDownLatch secondStarted=new CountDownLatch(1);
            XmppProfile latest=new XmppProfile("<vCard xmlns='vcard-temp'><NICKNAME>Latest</NICKNAME></vCard>",null,new byte[0]);
            Future<?> second=writer.submit(() -> {secondStarted.countDown();try{store.profile(peer,latest);}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(secondStarted.await(5,TimeUnit.SECONDS));
            long start=System.nanoTime();
            try {assertNull(reader.submit(() -> store.avatar(peer)).get(500,TimeUnit.MILLISECONDS));}
            finally {System.out.println("Avatar lookup while profile preparation is paused: "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+"ms");SlowImage.release.countDown();}
            preparing.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);assertEquals("Latest",store.profile(peer).nickname);
        }finally{SlowImage.release.countDown();writer.shutdownNow();reader.shutdownNow();context.deleteDatabase(file);}
    }
}
