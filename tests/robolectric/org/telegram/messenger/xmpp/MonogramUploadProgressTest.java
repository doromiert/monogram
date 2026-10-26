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
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramUploadProgressTest.Loader.class,MonogramMessageRegressionTest.Device.class,MonogramUploadProgressTest.Encoding.class,MonogramUploadProgressTest.Controller.class,MonogramEditingTest.PendingSession.class})
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
        for(String field:new String[]{"fileJobs","fileNames","automaticJobs","pendingSends","progressUpdates","translationRequests","queuedXmrPolicies"})
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
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(int.class,long.class,org.telegram.tgnet.TLObject.class,org.telegram.tgnet.RequestDelegate.class,String.class,int.class);constructor.setAccessible(true);
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
    @Test public void lateDownloadCompletionPreservesRetryAndMetadataAliasProgress() {
        MonogramController controller=controller();
        com.negzero.monogram.xmpp.FileTransfer.Job old=new com.negzero.monogram.xmpp.FileTransfer.Job();
        com.negzero.monogram.xmpp.FileTransfer.Job retry=new com.negzero.monogram.xmpp.FileTransfer.Job();
        ConcurrentHashMap<Integer,com.negzero.monogram.xmpp.FileTransfer.Job> jobs=ReflectionHelpers.getField(controller,"fileJobs");
        ConcurrentHashMap<String,com.negzero.monogram.xmpp.FileTransfer.Job> names=ReflectionHelpers.getField(controller,"fileNames");
        ConcurrentHashMap<String,Long> timestamps=ReflectionHelpers.getField(controller,"progressUpdates");
        String name="document-original.bin",metadataAlias="photo-upgraded.jpg",oldAlias="document-retired.bin";
        old.cancel();
        jobs.put(42,retry);names.put(name,retry);names.put(metadataAlias,retry);names.put(oldAlias,old);
        ImageLoader.getInstance().setFileProgress(name,3,10);
        ImageLoader.getInstance().setFileProgress(metadataAlias,3,10);
        ImageLoader.getInstance().setFileProgress(oldAlias,1,10);
        timestamps.put(name,9L);timestamps.put(metadataAlias,9L);timestamps.put(oldAlias,8L);
        ReflectionHelpers.callInstanceMethod(controller,"finishDownload",
                ReflectionHelpers.ClassParameter.from(int.class,42),
                ReflectionHelpers.ClassParameter.from(String.class,name),
                ReflectionHelpers.ClassParameter.from(com.negzero.monogram.xmpp.FileTransfer.Job.class,old));
        assertSame(retry,jobs.get(42));assertSame(retry,names.get(name));assertSame(retry,names.get(metadataAlias));
        assertTrue(controller.isFileLoading(name));assertTrue(controller.isFileLoading(metadataAlias));
        assertArrayEquals(new long[]{3,10},ImageLoader.getInstance().getFileProgressSizes(name));
        assertArrayEquals(new long[]{3,10},ImageLoader.getInstance().getFileProgressSizes(metadataAlias));
        assertEquals(Long.valueOf(9L),timestamps.get(name));assertEquals(Long.valueOf(9L),timestamps.get(metadataAlias));
        assertFalse(names.containsKey(oldAlias));assertNull(ImageLoader.getInstance().getFileProgressSizes(oldAlias));
        assertFalse(timestamps.containsKey(oldAlias));

        ReflectionHelpers.callInstanceMethod(controller,"finishDownload",
                ReflectionHelpers.ClassParameter.from(int.class,42),
                ReflectionHelpers.ClassParameter.from(String.class,name),
                ReflectionHelpers.ClassParameter.from(com.negzero.monogram.xmpp.FileTransfer.Job.class,retry));
        assertTrue(jobs.isEmpty());assertTrue(names.isEmpty());
        assertNull(ImageLoader.getInstance().getFileProgressSizes(name));
        assertNull(ImageLoader.getInstance().getFileProgressSizes(metadataAlias));
        assertTrue(timestamps.isEmpty());
    }
    @Test public void cancellationByPathRemovesQueuedRequestAndLogoutCleanupFinishesEveryPath() throws Exception {
        MonogramController controller=controller();MonogramFileRequest request=request();register(controller,request);
        Class<?> type=Class.forName(MonogramController.class.getName()+"$PendingSend");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(int.class,long.class,org.telegram.tgnet.TLObject.class,org.telegram.tgnet.RequestDelegate.class,String.class,int.class);constructor.setAccessible(true);
        Object pending=constructor.newInstance(7,0L,request,null,"PLAIN",request.localId);
        ConcurrentHashMap<Integer,Object> sends=ReflectionHelpers.getField(controller,"pendingSends");sends.put(7,pending);
        controller.cancelFile(request.message.attachPath);
        assertTrue(sends.isEmpty());assertTrue(request.job.cancelled());
        assertNull(ImageLoader.getInstance().getFileProgressSizes(request.message.attachPath));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,Encoding.finished);

        MonogramFileRequest retry=request();register(controller,retry);
        com.negzero.monogram.xmpp.FileTransfer.Job orphan=new com.negzero.monogram.xmpp.FileTransfer.Job();
        ConcurrentHashMap<String,com.negzero.monogram.xmpp.FileTransfer.Job> names=ReflectionHelpers.getField(controller,"fileNames");
        names.put("/private/another-upload.mp4",orphan);
        ImageLoader.getInstance().setFileProgress("/private/another-upload.mp4",4,10);
        ReflectionHelpers.callInstanceMethod(controller,"cancelFileTransfers");
        assertTrue(retry.job.cancelled());assertTrue(orphan.cancelled());assertTrue(names.isEmpty());
        assertTrue(((ConcurrentHashMap<?,?>)ReflectionHelpers.getField(controller,"fileJobs")).isEmpty());
        assertNull(ImageLoader.getInstance().getFileProgressSizes(retry.message.attachPath));
        assertNull(ImageLoader.getInstance().getFileProgressSizes("/private/another-upload.mp4"));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(3,Encoding.finished);
    }
    @Test public void queuedModeChangeFinishesPreparationBeforeReportingFailure() throws Exception {
        MonogramController controller=controller();MonogramFileRequest request=request();register(controller,request);
        android.content.SharedPreferences preferences=RuntimeEnvironment.getApplication().getSharedPreferences("upload-mode-test",0);
        preferences.edit().clear().putString("jid","alice@example.org").commit();
        ReflectionHelpers.setField(controller,"preferences",preferences);
        MonogramStore store=new MonogramStore(RuntimeEnvironment.getApplication(),"upload-mode-test.db");
        ReflectionHelpers.setField(controller,"store",store);
        ReflectionHelpers.setField(controller,"encryptionPolicy",new MonogramEncryptionPolicy(preferences));
        request.message.dialog_id=store.peer("bob@example.org");
        controller.setEncryption(request.message.dialog_id,com.negzero.monogram.xmpp.XmppMessage.Encryption.PLAIN);
        org.jivesoftware.smack.tcp.XMPPTCPConnection connection=new org.jivesoftware.smack.tcp.XMPPTCPConnection(
                org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());
        ReflectionHelpers.setField(connection,"authenticated",true);
        com.negzero.monogram.xmpp.XmppSession session=Shadow.newInstanceOf(com.negzero.monogram.xmpp.XmppSession.class);
        MonogramEditingTest.PendingSession network=Shadow.extract(session);network.connection=connection;
        ReflectionHelpers.setField(controller,"session",session);
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        ReflectionHelpers.setField(controller,"sendWorker",executor);
        java.util.concurrent.CountDownLatch completed=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
        org.telegram.tgnet.RequestDelegate callback=(response,failure) -> { error.set(failure.text);completed.countDown(); };
        Class<?> type=Class.forName(MonogramController.class.getName()+"$PendingSend");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(int.class,long.class,org.telegram.tgnet.TLObject.class,org.telegram.tgnet.RequestDelegate.class,String.class,int.class);constructor.setAccessible(true);
        Object pending=constructor.newInstance(7,0L,request,callback,"LEGACY_OMEMO",request.localId);
        ConcurrentHashMap<Integer,Object> sends=ReflectionHelpers.getField(controller,"pendingSends");sends.put(7,pending);
        try {
            ReflectionHelpers.callInstanceMethod(controller,"scheduleSend",ReflectionHelpers.ClassParameter.from((Class)type,pending));
            assertTrue(completed.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("MONOGRAM_SENDING_MODE_CHANGED",error.get());assertTrue(sends.isEmpty());
            assertNull(ImageLoader.getInstance().getFileProgressSizes(request.message.attachPath));
            assertTrue(((ConcurrentHashMap<?,?>)ReflectionHelpers.getField(controller,"fileJobs")).isEmpty());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,Encoding.finished);
        } finally { executor.shutdownNow();store.close();RuntimeEnvironment.getApplication().deleteDatabase("upload-mode-test.db"); }
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
