package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.MessageFixture;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMessageRegressionTest.Device.class,MonogramMediaRefreshTest.Decoder.class,MonogramMediaRefreshTest.ImageBounds.class})
public class MonogramMediaRefreshTest {
    @Implements(android.graphics.BitmapFactory.class)
    public static class ImageBounds {
        @Implementation public static android.graphics.Bitmap decodeFile(String path,android.graphics.BitmapFactory.Options options) {
            options.outWidth=-1;options.outHeight=-1;return null;
        }
    }
    @Implements(android.media.MediaMetadataRetriever.class)
    public static class Decoder {
        static AtomicInteger attempts=new AtomicInteger();
        @Implementation public void setDataSource(String path) { attempts.incrementAndGet(); throw new IllegalArgumentException("Temporary metadata failure"); }
    }
    @Test public void failedAndMissingCachedMediaCanBeScannedAgain() throws Exception {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        MonogramController controller=ReflectionHelpers.callConstructor(MonogramController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        MonogramStore store=ReflectionHelpers.getField(controller,"store");
        ExecutorService serial=Executors.newSingleThreadExecutor();
        ((ExecutorService)ReflectionHelpers.getField(controller,"fileWorker")).shutdownNow();
        ReflectionHelpers.setField(controller,"fileWorker",serial);
        File file=File.createTempFile("metadata-retry-",".mp4",ApplicationLoader.applicationContext.getCacheDir());
        try {
            java.nio.file.Files.write(file.toPath(),new byte[]{0});
            long self=store.peer("alice@example.org");
            int id=store.save(MessageFixture.replyMessage("video",null,null,"video",true,false,100000,"phone",false,null));
            TLRPC.Message message=store.getMessage(id,self);
            MonogramAttachments.apply(message,com.negzero.monogram.xmpp.FileLink.parse("https://files.example.org/video.mp4"),file,
                    MonogramAttachments.remoteMedia(id,"video.mp4","video/mp4",1),"","video.mp4");
            Method refresh=MonogramController.class.getDeclaredMethod("refreshCachedMedia",TLRPC.Message.class,File.class);refresh.setAccessible(true);
            Set<Integer> scanning=ReflectionHelpers.getField(controller,"mediaInfoScanned");
            Decoder.attempts.set(0);
            for(int attempt=1;attempt<=2;attempt++) {
                int previous=Decoder.attempts.get();
                refresh.invoke(controller,message,file);serial.submit(() -> {}).get(5,TimeUnit.SECONDS);
                assertTrue(Decoder.attempts.get()>previous);assertFalse(scanning.contains(id));
                assertEquals("video",store.getMessage(id,self).message);
            }
            int beforeMissing=Decoder.attempts.get();
            assertTrue(file.delete());
            refresh.invoke(controller,message,file);serial.submit(() -> {}).get(5,TimeUnit.SECONDS);
            assertFalse(scanning.contains(id));assertEquals(beforeMissing,Decoder.attempts.get());
        } finally {
            for(String name:new String[]{"worker","sendWorker","bookmarkWorker","readSyncWorker","roomWorker","cacheWorker","archiveWorker","fileWorker","profileWorker","fingerprintWorker","autoDownloadWorker"})
                ((ExecutorService)ReflectionHelpers.getField(controller,name)).shutdownNow();
            store.close();((MonogramOmemo2Store)ReflectionHelpers.getField(controller,"modernStore")).close();
            ApplicationLoader.applicationContext.deleteDatabase("monogram-0.db");file.delete();
        }
    }
}
