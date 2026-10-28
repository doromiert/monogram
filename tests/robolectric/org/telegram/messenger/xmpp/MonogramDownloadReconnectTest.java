package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramDownloadReconnectTest.Controller.class,MonogramDownloadReconnectTest.Transfer.class,MonogramDownloadReconnectTest.Object.class,MonogramDownloadReconnectTest.Images.class,MonogramDownloadReconnectTest.Storage.class,MonogramDownloadReconnectTest.Ui.class,MonogramDownloadReconnectTest.Attachments.class,MonogramDownloadReconnectTest.Utils.class})
public class MonogramDownloadReconnectTest {
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller {@Implementation public void __constructor__(int account){}}
    static Runnable duringTransfer;static int transfers;static Exception failure;
    @Implements(value=FileTransfer.class,isInAndroidSdk=false)
    public static class Transfer {
        @Implementation public static boolean download(FileLink link,File target,FileTransfer.Job job,FileTransfer.Progress progress,FileTransfer.DownloadPolicy policy,FileTransfer.Metadata metadata)throws Exception {
            transfers++;if(failure!=null)throw failure;metadata.received("image/jpeg",3);duringTransfer.run();
            target.getParentFile().mkdirs();Files.write(target.toPath(),new byte[]{1,2,3});return true;
        }
    }
    @Implements(value=MessageObject.class,isInAndroidSdk=false)
    public static class Object {
        @org.robolectric.annotation.RealObject MessageObject object;
        @Implementation public void __constructor__(int account,TLRPC.Message message,MessageObject reply,
                java.util.AbstractMap<Long,TLRPC.User> users,java.util.AbstractMap<Long,TLRPC.Chat> chats,
                androidx.collection.LongSparseArray<TLRPC.User> sparseUsers,androidx.collection.LongSparseArray<TLRPC.Chat> sparseChats,
                boolean layout,boolean media,long event,boolean repost,boolean repostVideo,boolean saved,int searchType){object.messageOwner=message;}
        @Implementation public String getFileName(){return "picture.jpg";}
        @Implementation public int getId(){return object.messageOwner.id;}
    }
    @Implements(value=ImageLoader.class,isInAndroidSdk=false)
    public static class Images {
        @Implementation public void __constructor__(){}
        @Implementation public static ImageLoader getInstance(){return Shadow.newInstanceOf(ImageLoader.class);}
        @Implementation public void setFileProgress(String name,long bytes,long total){}
    }
    @Implements(value=MessagesStorage.class,isInAndroidSdk=false)
    public static class Storage {
        @Implementation public void __constructor__(int account){}
        @Implementation public static MessagesStorage getInstance(int account){return ReflectionHelpers.callConstructor(MessagesStorage.class,ReflectionHelpers.ClassParameter.from(int.class,account));}
        @Implementation public void putMessages(ArrayList<TLRPC.Message> messages,boolean transaction,boolean queue,boolean date,int mask,int mode,long thread){}
    }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static void runOnUIThread(Runnable task){}
    }
    @Implements(value=MonogramAttachments.class,isInAndroidSdk=false)
    public static class Attachments {
        @Implementation protected static TLRPC.MessageMedia decodedMedia(int id,int date,File file,String name,String mime){return MonogramAttachments.remoteMedia(id,name,mime,file.length());}
    }
    @Implements(value=Utilities.class,isInAndroidSdk=false)
    public static class Utils {@Implementation public static void __staticInitializer__(){}}
    static class Queue extends AbstractExecutorService {
        final ArrayDeque<Runnable> pending=new ArrayDeque<>();
        public void execute(Runnable task){pending.add(task);}
        void drain(){while(!pending.isEmpty())pending.remove().run();}
        public void shutdown(){}public List<Runnable> shutdownNow(){return Collections.emptyList();}public boolean isShutdown(){return false;}public boolean isTerminated(){return false;}public boolean awaitTermination(long time,TimeUnit unit){return true;}
    }
    Context context;MonogramStore store;MonogramController controller;Queue io,worker;int id;long self;String address;File path;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("download-reconnect.db");store=new MonogramStore(context,"download-reconnect.db");self=store.peer("alice@example.org");store.peer("bob@example.org");
        address="aesgcm://files.example.org/picture.jpg#"+String.join("",Collections.nCopies(88,"a"));
        id=store.save(MessageFixture.modern("picture",null,address,true,100000,null,null,null));path=store.attachmentPath(id);path.delete();
        controller=Shadow.newInstanceOf(MonogramController.class);io=new Queue();worker=new Queue();
        ReflectionHelpers.setField(controller,"store",store);ReflectionHelpers.setField(controller,"self",self);ReflectionHelpers.setField(controller,"worker",worker);ReflectionHelpers.setField(controller,"fileWorker",io);
        for(String field:new String[]{"fileJobs","fileNames","automaticJobs","progressUpdates","downloadErrors"})ReflectionHelpers.setField(controller,field,new ConcurrentHashMap<>());
        for(String field:new String[]{"autoAttempted","mediaInfoScanned"})ReflectionHelpers.setField(controller,field,ConcurrentHashMap.newKeySet());
        failure=null;transfers=0;duringTransfer=()->{};
    }
    @After public void cleanup(){store.close();context.deleteDatabase("download-reconnect.db");path.delete();}
    private void download(){controller.download(new MessageObject(0,store.getMessage(id,self),true,true));io.drain();worker.drain();}
    @Test public void completedDownloadCommitsAcrossXmppReconnectAndIsReused()throws Exception {
        duringTransfer=()->ReflectionHelpers.setField(controller,"sessionEpoch",1L);
        download();assertTrue("An XMPP reconnect must not discard a verified HTTP download",store.hasCachedAttachment(address,path));
        download();assertEquals("Retry must reuse committed bytes",1,transfers);
    }
    @Test public void accountChangeStillCannotCommitOldDownload()throws Exception {
        duringTransfer=()->ReflectionHelpers.setField(controller,"accountEpoch",1L);
        download();assertFalse(store.hasCachedAttachment(address,path));
    }
    @Test public void failedDownloadReportsTransportCategoryWithoutLeakingUrlOrClaimingXmppIsRequired()throws Exception {
        failure=new java.net.SocketTimeoutException("https://files.example.org/private?token=secret#key");
        download();assertFalse(store.hasCachedAttachment(address,path));
        Map<Integer,String> errors=ReflectionHelpers.getField(controller,"downloadErrors");
        assertEquals("MONOGRAM_FILE_TRANSFER_FAILED|SocketTimeoutException",errors.get(id));
        assertFalse(controller.isFileLoading("picture.jpg"));
    }

}
