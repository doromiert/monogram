package org.telegram.messenger;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.FileTransfer;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramDownloadDetachTest.Transfers.class,MonogramDownloadDetachTest.Files.class,MonogramDownloadDetachTest.Queues.class})
public class MonogramDownloadDetachTest {
    private static final String NAME="incoming.webp";
    private static final Map<Integer,FileTransfer.Job> jobs=new HashMap<>();
    private static MonogramController controller;
    private static FileLoader files;
    private static int cancellations;
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Transfers {
        @Implementation public void __constructor__(int account) {}
        @Implementation public static MonogramController get(int account) { return controller; }
        @Implementation public boolean isFileLoading(String name) { return NAME.equals(name) && jobs.containsKey(0); }
        @Implementation public void cancelFile(String name) {
            cancellations++;FileTransfer.Job job=jobs.remove(0);if(job!=null) job.cancel();
        }
    }
    @Implements(value=FileLoader.class,isInAndroidSdk=false)
    public static class Files {
        @Implementation public static FileLoader getInstance(int account) { return files; }
        @Implementation public void __constructor__(int account) {}
    }
    @Implements(value=DispatchQueue.class,isInAndroidSdk=false)
    public static class Queues {
        @Implementation public void __constructor__(String name) {}
        @Implementation public boolean postRunnable(Runnable runnable) { runnable.run();return true; }
    }
    private static <T> T shell(Class<T> type) throws Exception {
        Class<?> allocator=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field field=allocator.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(allocator.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    @Before public void setup() throws Exception {
        jobs.clear();cancellations=0;controller=ReflectionHelpers.callConstructor(MonogramController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        files=ReflectionHelpers.callConstructor(FileLoader.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        ReflectionHelpers.setField(files,"loadOperationPathsUI",new ConcurrentHashMap<>());
        ReflectionHelpers.setField(files,"loadOperationPaths",new ConcurrentHashMap<>());
    }
    private ImageLoader loader() throws Exception {
        ImageLoader loader=shell(ImageLoader.class);
        ReflectionHelpers.setField(loader,"forceLoadingImages",new HashMap<>());
        ReflectionHelpers.setField(loader,"imageLoadingByUrl",new HashMap<>());
        ReflectionHelpers.setField(loader,"imageLoadingByUrlPframe",new HashMap<>());
        ReflectionHelpers.setField(loader,"imageLoadingByKeys",new ConcurrentHashMap<>());
        ReflectionHelpers.setField(loader,"imageLoadingKeys",new HashSet<>());
        ReflectionHelpers.setField(loader,"fileProgresses",new ConcurrentHashMap<>());
        return loader;
    }
    private void detach(ImageLoader loader,String name) throws Exception {
        Class<?> cacheType=Class.forName("org.telegram.messenger.ImageLoader$CacheImage");
        Constructor<?> constructor=cacheType.getDeclaredConstructor(ImageLoader.class);constructor.setAccessible(true);
        Object cache=constructor.newInstance(loader);
        ImageLocation location=new ImageLocation();location.document=new TLRPC.TL_document();
        ReflectionHelpers.setField(cache,"imageLocation",location);
        ReflectionHelpers.setField(cache,"url",name);ReflectionHelpers.setField(cache,"key",name+"@100_100");
        ReflectionHelpers.callInstanceMethod(cache,"removeImageReceiver",ReflectionHelpers.ClassParameter.from(ImageReceiver.class,null));
    }
    @Test public void closingAndReopeningMediaDoesNotCancelAccountDownloadOrClearProgress() throws Exception {
        ImageLoader loader=loader();FileTransfer.Job job=new FileTransfer.Job();jobs.put(0,job);
        loader.setFileProgress(NAME,32768,65536);
        detach(loader,NAME);detach(loader,NAME);
        assertEquals(0,cancellations);assertSame(job,jobs.get(0));assertFalse(job.cancelled());
        assertArrayEquals(new long[]{32768,65536},loader.getFileProgressSizes(NAME));
    }
    @Test public void unrelatedImageLoadsStillCancelWhenTheirLastReceiverDisappears() throws Exception {
        detach(loader(),"other.webp");assertEquals(1,cancellations);
    }
    @Test public void explicitFileCancellationRemainsAvailableAfterDetaching() throws Exception {
        FileTransfer.Job job=new FileTransfer.Job();jobs.put(0,job);detach(loader(),NAME);
        FileLoader.getInstance(0).cancelLoadFile(NAME);
        assertEquals(1,cancellations);assertTrue(job.cancelled());assertFalse(jobs.containsKey(0));
    }
}
