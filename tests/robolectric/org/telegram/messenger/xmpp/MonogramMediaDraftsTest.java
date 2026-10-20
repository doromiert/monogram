package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMediaDraftsTest.Account.class,MonogramMediaDraftsTest.App.class,MonogramMediaDraftsTest.Ui.class,MonogramMediaDraftsTest.Caption.class,MonogramMediaDraftsTest.Storage.class})
public class MonogramMediaDraftsTest {
    private static MonogramController controller;private static String jid;
    private static ArrayList<TLRPC.Message> outbox;
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Account {
        @Implementation public static MonogramController get(int account) { return controller; }
        @Implementation public String accountJid() { return jid; }
        @Implementation public String jid(long dialog) { return dialog<0 ? "friends@conference.example.org" : "bob"+dialog+"@example.org"; }
    }
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable action) { action.run(); }
    }
    @Implements(value=MediaDataController.class,isInAndroidSdk=false)
    public static class Caption {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static MediaDataController getInstance(int account) { return Shadow.newInstanceOf(MediaDataController.class); }
        @Implementation public ArrayList<TLRPC.MessageEntity> getEntities(CharSequence[] text,boolean allow) { return new ArrayList<>(); }
    }
    @Implements(value=MessagesStorage.class,isInAndroidSdk=false)
    public static class Storage {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static MessagesStorage getInstance(int account) { return Shadow.newInstanceOf(MessagesStorage.class); }
        @Implementation public void getMonogramOutbox(long dialog,Consumer<ArrayList<TLRPC.Message>> callback) { callback.accept(new ArrayList<>(outbox)); }
    }
    private Context context;private final ArrayList<String> errors=new ArrayList<>();
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        controller=Shadow.newInstanceOf(MonogramController.class);jid=UUID.randomUUID()+"@example.org";outbox=new ArrayList<>();
        MonogramMediaDrafts.deactivate();
    }
    @After public void close() throws Exception { MonogramMediaDrafts.deactivate();awaitWrites(); }
    private void awaitWrites() throws Exception { ((ExecutorService)ReflectionHelpers.getStaticField(MonogramMediaDrafts.class,"WRITER")).submit(()->{}).get(10,TimeUnit.SECONDS); }
    private MediaController.PhotoEntry photo(int id,boolean temporary) throws Exception {
        File directory=temporary ? context.getCacheDir() : new File(context.getFilesDir(),"fixture-gallery");directory.mkdirs();
        File source=File.createTempFile("selected-",".jpg",directory);Files.write(source.toPath(),new byte[]{1,2,3,4});
        MediaController.PhotoEntry entry=new MediaController.PhotoEntry(9,id,1234,source.getAbsolutePath(),90,0,false,640,480,4);entry.canDeleteAfter=temporary;
        entry.caption="😀 caption";entry.entities=new ArrayList<>();TLRPC.TL_messageEntityBold bold=new TLRPC.TL_messageEntityBold();bold.offset=3;bold.length=7;entry.entities.add(bold);return entry;
    }
    private MonogramMediaDrafts.Session save(long peer,List<MediaController.PhotoEntry> photos,boolean send) {
        MonogramMediaDrafts.Session session=MonogramMediaDrafts.bind(0,peer,errors::add);Map<Object,Object> selected=new HashMap<>();ArrayList<Object> order=new ArrayList<>();
        for(MediaController.PhotoEntry photo:photos) { selected.put(photo.imageId,photo);order.add(photo.imageId); }
        session.save(selected,order,"Global caption",true,send);return session;
    }
    private MonogramMediaDrafts.Restored restore(MonogramMediaDrafts.Session session) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<MonogramMediaDrafts.Restored> value=new AtomicReference<>();
        session.restore(result->{value.set(result);done.countDown();});assertTrue(done.await(10,TimeUnit.SECONDS));return value.get();
    }
    @Test public void nativeSelectionOrderCaptionEntitiesAndVideoTrimSurviveDiskReopen() throws Exception {
        MediaController.PhotoEntry first=photo(1,false),second=photo(2,false);second.isVideo=true;second.duration=9;second.editedInfo=new VideoEditedInfo();
        second.editedInfo.originalPath=second.path;second.editedInfo.startTime=1000;second.editedInfo.endTime=7000;second.editedInfo.muted=true;second.editedInfo.volume=.5f;
        MonogramMediaDrafts.Session session=save(42,Arrays.asList(second,first),false);assertTrue(session.owner.manifest().getBaseFile().isFile());awaitWrites();
        ((Map<?,?>)ReflectionHelpers.getStaticField(MonogramMediaDrafts.class,"latest")).clear();session=MonogramMediaDrafts.bind(0,42,errors::add);
        MonogramMediaDrafts.Restored restored=restore(session);assertEquals(2,restored.photos.size());assertEquals(2,restored.photos.get(0).imageId);assertEquals("Global caption",restored.caption);assertTrue(restored.above);
        assertEquals("😀 caption",restored.photos.get(1).caption);assertEquals(3,restored.photos.get(1).entities.get(0).offset);assertTrue(restored.photos.get(1).entities.get(0) instanceof TLRPC.TL_messageEntityBold);
        assertEquals(1000,restored.photos.get(0).editedInfo.startTime);assertEquals(7000,restored.photos.get(0).editedInfo.endTime);assertTrue(restored.photos.get(0).editedInfo.muted);assertEquals(.5f,restored.photos.get(0).editedInfo.volume,0);
    }
    @Test public void temporaryCameraAndEditedAssetsAreProtectedThenCopiedToPrivateDurableStorage() throws Exception {
        MediaController.PhotoEntry photo=photo(7,true);File edit=File.createTempFile("edited-",".jpg",context.getCacheDir());Files.write(edit.toPath(),new byte[]{8,9});photo.imagePath=edit.getAbsolutePath();
        MonogramMediaDrafts.Session session=save(-4,Collections.singletonList(photo),false);assertTrue(MonogramMediaDrafts.protectedFile(photo.path));awaitWrites();
        assertTrue(new File(photo.path).delete());assertTrue(edit.delete());
        MonogramMediaDrafts.Restored restored=restore(session);assertEquals(1,restored.photos.size());assertEquals(0,restored.missing);
        assertTrue(restored.photos.get(0).path.startsWith(context.getFilesDir().getAbsolutePath()+File.separator));assertArrayEquals(new byte[]{8,9},Files.readAllBytes(new File(restored.photos.get(0).imagePath).toPath()));
    }
    @Test public void changedOriginalsAndMissingAssetsRemainRecoverableWithoutRestoringWrongMedia() throws Exception {
        MediaController.PhotoEntry photo=photo(3,false);MonogramMediaDrafts.Session session=save(42,Collections.singletonList(photo),false);awaitWrites();
        Files.write(new File(photo.path).toPath(),new byte[]{5,6,7,8,9});MonogramMediaDrafts.Restored restored=restore(session);assertEquals(0,restored.photos.size());assertEquals(1,restored.missing);
        session.save(Collections.emptyMap(),Collections.emptyList(),"",false,false);awaitWrites();assertTrue(session.owner.manifest().getBaseFile().isFile());
        session.discard();assertFalse(session.owner.manifest().getBaseFile().isFile());
    }
    @Test public void accountAndConversationOwnershipPreventStaleSessionMutation() throws Exception {
        MediaController.PhotoEntry photo=photo(4,false);MonogramMediaDrafts.Session alice=save(42,Collections.singletonList(photo),false);awaitWrites();
        assertEquals(0,restore(MonogramMediaDrafts.bind(0,43,errors::add)).photos.size());assertFalse(alice.active());
        alice.discard();assertTrue(alice.owner.manifest().getBaseFile().isFile());
        String old=jid;jid="other-"+old;assertEquals(0,restore(MonogramMediaDrafts.bind(0,42,errors::add)).photos.size());jid=old;
        assertEquals(1,restore(MonogramMediaDrafts.bind(0,42,errors::add)).photos.size());
    }
    private TLRPC.Message queued(MonogramMediaDrafts.Session session,MediaController.PhotoEntry photo,int id) {
        SendMessagesHelper.SendingMediaInfo info=new SendMessagesHelper.SendingMediaInfo();session.tag(info,photo);
        TLRPC.TL_message message=new TLRPC.TL_message();message.id=id;message.out=true;message.dialog_id=session.owner.dialog;message.params=info.params;return message;
    }
    @Test public void allItemsMustBeDurableBeforeBatchClearsAndPartialAcceptanceIsNotSentAgain() throws Exception {
        MediaController.PhotoEntry first=photo(5,true),second=photo(6,true);MonogramMediaDrafts.Session session=save(42,Arrays.asList(first,second),true);awaitWrites();
        TLRPC.Message a=queued(session,first,-1),b=queued(session,second,-2);outbox.add(a);MonogramMediaDrafts.accepted(0,a);awaitWrites();assertTrue(session.owner.manifest().getBaseFile().isFile());
        MonogramMediaDrafts.Restored partial=restore(session);assertEquals(1,partial.photos.size());assertEquals(second.imageId,partial.photos.get(0).imageId);
        MonogramMediaDrafts.accepted(0,b);awaitWrites();assertTrue(session.owner.manifest().getBaseFile().isFile());
        outbox.add(b);MonogramMediaDrafts.accepted(0,b);awaitWrites();assertFalse(session.owner.manifest().getBaseFile().isFile());
        assertTrue(new File(session.owner.directory(),"assets").isDirectory());
    }
    @Test public void paramsCannotReplaceNativeFieldsAndLogoutRetainsDeclaredOutboxAssets() throws Exception {
        MediaController.PhotoEntry photo=photo(8,true);MonogramMediaDrafts.Session session=save(42,Collections.singletonList(photo),true);awaitWrites();
        HashMap<String,String> destination=new HashMap<>(),source=new HashMap<>();destination.put("originalPath","native-path");source.put("originalPath","wrong");source.put(MonogramMediaDrafts.ITEM,"selected");
        MonogramMediaDrafts.mergeParams(destination,source);assertEquals("native-path",destination.get("originalPath"));assertEquals("selected",destination.get(MonogramMediaDrafts.ITEM));
        MonogramMediaDrafts.logout(0,jid);awaitWrites();assertFalse(session.owner.manifest().getBaseFile().isFile());assertTrue(new File(session.owner.directory(),"assets").isDirectory());
        assertEquals(0,restore(MonogramMediaDrafts.bind(0,42,errors::add)).photos.size());
    }
}
