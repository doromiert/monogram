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
    @org.robolectric.annotation.Implements(value=MonogramStore.class,isInAndroidSdk=false)
    public static class NoFullMessages {
        @org.robolectric.annotation.Implementation protected TLRPC.Message getMessage(int id,long self) {
            throw new AssertionError("Media counts must not reconstruct messages or their replies");
        }
    }
    @org.robolectric.annotation.Implements(value=MonogramFormatting.class,isInAndroidSdk=false)
    public static class PausedClassification {
        static MonogramStore store;
        static java.util.concurrent.CountDownLatch entered,release;
        @org.robolectric.annotation.Implementation protected static String displayText(String text,String markup) throws Exception {
            assertFalse("Classification must not hold the UI store monitor",Thread.holdsLock(store));
            entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return text;
        }
    }
    @Test @Config(shadows={NoFullMessages.class,PausedClassification.class,MonogramMessageRegressionTest.Device.class})
    public void largeChatCountersUseMetadataBatchesWithoutBlockingChatOpening() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        String db="media-count-batches.db";context.deleteDatabase(db);
        java.util.concurrent.ExecutorService counter=java.util.concurrent.Executors.newSingleThreadExecutor(),ui=java.util.concurrent.Executors.newSingleThreadExecutor();
        PausedClassification.entered=new java.util.concurrent.CountDownLatch(1);PausedClassification.release=new java.util.concurrent.CountDownLatch(1);
        try(MonogramStore store=new MonogramStore(context,db)) {
            PausedClassification.store=store;
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            for(int i=0;i<250;i++)store.save(XmppMessage.sent(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","text-"+i,"plain text",false));
            store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","photo",FileLink.parse("https://files.example.org/photo.png"),false));
            long start=System.nanoTime();
            java.util.concurrent.Future<org.telegram.tgnet.Vector<TLRPC.TL_messages_searchCounter>> work=counter.submit(() -> store.mediaCounters(self,peer,Arrays.asList(new TLRPC.TL_inputMessagesFilterPhotoVideo(),new TLRPC.TL_inputMessagesFilterEmpty(),new TLRPC.TL_inputMessagesFilterUrl())));
            assertTrue(PausedClassification.entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            long lookup=System.nanoTime();
            try{assertEquals("bob@example.org",ui.submit(() -> store.jid(peer)).get(500,java.util.concurrent.TimeUnit.MILLISECONDS));}
            finally{System.out.println("Chat lookup during paused media classification: "+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-lookup)+"ms");PausedClassification.release.countDown();}
            org.telegram.tgnet.Vector<TLRPC.TL_messages_searchCounter> counts=work.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,counts.objects.get(0).count);assertEquals(251,counts.objects.get(1).count);assertEquals(0,counts.objects.get(2).count);
            System.out.println("251-row metadata count: "+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+"ms");
        }finally{PausedClassification.release.countDown();counter.shutdownNow();ui.shutdownNow();PausedClassification.store=null;context.deleteDatabase(db);}
    }

    @Test public void editedMediaUsesOriginalCachedTypeAndDeletedMessagesAreExcluded() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        String db="media-count-edits.db";context.deleteDatabase(db);
        java.io.File source=java.io.File.createTempFile("voice", ".ogg",context.getCacheDir());java.nio.file.Files.write(source.toPath(),new byte[]{1,2,3});
        try(MonogramStore store=new MonogramStore(context,db)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            FileShare sharing=FileShare.describe(FileLink.parse("https://files.example.org/voice.ogg"),source,"voice.ogg","audio/ogg");
            int original=store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","voice",sharing,"old caption",null));
            TLRPC.TL_messageMediaDocument media=new TLRPC.TL_messageMediaDocument();media.flags=1;
            TLRPC.TL_document document=new TLRPC.TL_document();document.id=original;document.mime_type="audio/ogg";document.file_reference=new byte[0];
            TLRPC.TL_documentAttributeAudio audio=new TLRPC.TL_documentAttributeAudio();audio.voice=true;audio.duration=2;document.attributes.add(audio);media.document=document;
            SerializedData encoded=new SerializedData();media.serializeToStream(encoded);
            store.getWritableDatabase().execSQL("UPDATE attachments SET media=?,path=? WHERE message=?",new Object[]{encoded.toByteArray(),store.attachmentFile(sharing).getAbsolutePath(),original});encoded.cleanup();
            int correction=store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","caption-edit",sharing,"new caption",null,new MessageCorrection("voice")));
            store.reconcileCorrections(peer,Collections.singleton(correction));
            List<TLRPC.MessagesFilter> filters=Arrays.asList(new TLRPC.TL_inputMessagesFilterRoundVoice(),new TLRPC.TL_inputMessagesFilterMusic(),new TLRPC.TL_inputMessagesFilterEmpty());
            org.telegram.tgnet.Vector<TLRPC.TL_messages_searchCounter> counts=store.mediaCounters(self,peer,filters);
            assertEquals(1,counts.objects.get(0).count);assertEquals(0,counts.objects.get(1).count);assertEquals(1,counts.objects.get(2).count);
            store.deleteMessages(Collections.singleton(original));
            counts=store.mediaCounters(self,peer,filters);assertEquals(0,counts.objects.get(0).count);assertEquals(0,counts.objects.get(2).count);
        }finally{source.delete();context.deleteDatabase(db);}
    }

}
