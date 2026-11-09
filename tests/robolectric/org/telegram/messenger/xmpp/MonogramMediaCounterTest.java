package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramMediaCounterTest {
    @Test public void viewerCanDiscoverPhotoVideoNeighboursWithoutNetworkOrOtherChats() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        String db="media-counters.db";context.deleteDatabase(db);
        try(MonogramStore store=new MonogramStore(context,db)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            java.io.File source=java.io.File.createTempFile("media", ".bin",context.getCacheDir());java.nio.file.Files.write(source.toPath(),new byte[]{1,2,3});
            int[] ids=new int[3];String[] types={"image/png","video/mp4","image/png"};
            for(int i=0;i<3;i++) {
                FileShare sharing=FileShare.describe(FileLink.parse("https://files.example.org/"+i),source,i==1?"video.mp4":"photo.png",types[i]);
                ids[i]=store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","media"+i,sharing,"",null));
            }
            store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"carol@example.org","other",FileLink.parse("https://files.example.org/other.png"),false));
            org.telegram.tgnet.Vector<TLRPC.TL_messages_searchCounter> counters=store.mediaCounters(self,peer,Arrays.asList(new TLRPC.TL_inputMessagesFilterPhotoVideo(),new TLRPC.TL_inputMessagesFilterPhotos(),new TLRPC.TL_inputMessagesFilterVideo()));
            assertEquals(3,counters.objects.get(0).count);assertEquals(2,counters.objects.get(1).count);assertEquals(1,counters.objects.get(2).count);
            TLRPC.TL_messages_messagesSlice media=store.search(self,peer,"",0,0,20,0,0,0,new TLRPC.TL_inputMessagesFilterPhotoVideo(),false,false);
            assertEquals(3,media.messages.size());assertEquals(ids[2],media.messages.get(0).id);assertEquals(ids[0],media.messages.get(2).id);
            assertEquals(0,store.mediaCounters(self,store.peer("empty@example.org"),Collections.singletonList(new TLRPC.TL_inputMessagesFilterPhotoVideo())).objects.get(0).count);
            source.delete();
        }finally{context.deleteDatabase(db);}
    }
}
