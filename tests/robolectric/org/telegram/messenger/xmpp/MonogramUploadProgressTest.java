package org.telegram.messenger.xmpp;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ImageLoader;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramUploadProgressTest.Loader.class,MonogramMessageRegressionTest.Device.class,MonogramUploadProgressTest.Encoding.class,MonogramUploadProgressTest.Controller.class})
public class MonogramUploadProgressTest {
    @Implements(value=ImageLoader.class,isInAndroidSdk=false)
    public static class Loader {
        static final ImageLoader instance=Shadow.newInstanceOf(ImageLoader.class);
        static { ReflectionHelpers.setField(instance,"fileProgresses",new ConcurrentHashMap<String,long[]>()); }
        @Implementation public void __constructor__() {}
        @Implementation public static ImageLoader getInstance() { return instance; }
    }
    @Implements(value=org.telegram.messenger.VideoEncodingService.class,isInAndroidSdk=false)
    public static class Encoding {
        static int finished;
        @Implementation public static void uploadFinished(int account,String path) { finished++; }
    }
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller {
        @Implementation public void __constructor__(int account) {}
    }
    private MonogramController controller() {
        org.telegram.messenger.ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        MonogramController controller=ReflectionHelpers.callConstructor(MonogramController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        for(String field:new String[]{"fileJobs","fileNames","pendingSends","progressUpdates"})
            ReflectionHelpers.setField(controller,field,new ConcurrentHashMap<>());
        org.telegram.messenger.ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        Encoding.finished=0;
        return controller;
    }
    private void register(MonogramController controller,MonogramFileRequest request) {
        ConcurrentHashMap<Integer,com.negzero.monogram.xmpp.FileTransfer.Job> jobs=ReflectionHelpers.getField(controller,"fileJobs");
        ConcurrentHashMap<String,com.negzero.monogram.xmpp.FileTransfer.Job> names=ReflectionHelpers.getField(controller,"fileNames");
        jobs.put(request.localId,request.job);names.put(request.message.attachPath,request.job);
        MonogramFileRequest.prepareProgress(request.message,request.file);
    }
    private MonogramFileRequest request() {
        TLRPC.Message message=new TLRPC.TL_message();message.id=-42;message.attachPath="/private/upload.mp4";
        return new MonogramFileRequest(message,new File(message.attachPath),"video/mp4","");
    }
    @Test public void queuedRequestCancellationClearsProgressAndFinishesNotification() throws Exception {
        MonogramController controller=controller();MonogramFileRequest request=request();register(controller,request);
        Class<?> type=Class.forName(MonogramController.class.getName()+"$PendingSend");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object pending=constructor.newInstance(7,0L,request,null,"PLAIN",request.localId);
        ConcurrentHashMap<Integer,Object> sends=ReflectionHelpers.getField(controller,"pendingSends");sends.put(7,pending);
        controller.cancelRequest(7);
        assertTrue(request.job.cancelled());assertTrue(sends.isEmpty());
        assertNull(ImageLoader.getInstance().getFileProgressSizes(request.message.attachPath));
        assertTrue(((ConcurrentHashMap<?,?>)ReflectionHelpers.getField(controller,"fileJobs")).isEmpty());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,Encoding.finished);
    }
    @Test public void lateCompletionCannotClearRetryProgressOrStopItsNotification() {
        MonogramController controller=controller();MonogramFileRequest old=request();register(controller,old);
        controller.cancelFile(old.localId);
        assertTrue(old.job.cancelled());assertNull(ImageLoader.getInstance().getFileProgressSizes(old.message.attachPath));
        MonogramFileRequest retry=request();register(controller,retry);
        ImageLoader.getInstance().setFileProgress(retry.message.attachPath,3,10);
        ReflectionHelpers.callInstanceMethod(controller,"finishUpload",
                ReflectionHelpers.ClassParameter.from(int.class,old.localId),
                ReflectionHelpers.ClassParameter.from(String.class,old.message.attachPath),
                ReflectionHelpers.ClassParameter.from(com.negzero.monogram.xmpp.FileTransfer.Job.class,old.job));
        assertArrayEquals(new long[]{3,10},ImageLoader.getInstance().getFileProgressSizes(retry.message.attachPath));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(0,Encoding.finished);
        controller.cancelFile(retry.message.attachPath);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,Encoding.finished);
        assertTrue(retry.job.cancelled());assertNull(ImageLoader.getInstance().getFileProgressSizes(retry.message.attachPath));
    }
    @Test public void queuedAndPreparedFilesNeverInheritMissingOrCompletedUploadProgress() throws Exception {
        File file=File.createTempFile("upload-progress",".bin",RuntimeEnvironment.getApplication().getCacheDir());
        try {
            Files.write(file.toPath(),new byte[]{1,2,3});
            TLRPC.Message message=new TLRPC.TL_message();message.attachPath=file.getAbsolutePath();
            assertNull(ImageLoader.getInstance().getFileProgressSizes(message.attachPath));
            assertEquals(3,MonogramFileRequest.prepareProgress(message,file));
            assertEquals(0f,ImageLoader.getInstance().getFileProgress(message.attachPath),0f);
            ImageLoader.getInstance().setFileProgress(message.attachPath,3,3);
            MonogramFileRequest.prepareProgress(message,file);
            assertArrayEquals(new long[]{0,3},ImageLoader.getInstance().getFileProgressSizes(message.attachPath));
            message.media=new TLRPC.TL_messageMediaDocument();message.media.document=new TLRPC.TL_document();message.media.document.size=123;
            message.attachPath=file.getAbsolutePath()+"-converted.mp4";
            assertEquals(123,MonogramFileRequest.prepareProgress(message,new File(message.attachPath)));
            assertArrayEquals(new long[]{0,123},ImageLoader.getInstance().getFileProgressSizes(message.attachPath));
        } finally { file.delete(); }
    }
}
