package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.*;
import com.negzero.monogram.xmpp.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramMediaRestoreTest {
    @Test public void incomingCachedPhotosAndVideosRecoverLocalPathsAndTransportMetadata() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        String db="viewer-media-restore.db";context.deleteDatabase(db);
        File source=File.createTempFile("media-restore-", ".bin",context.getCacheDir());Files.write(source.toPath(),new byte[]{1,2,3});
        try(MonogramStore store=new MonogramStore(context,db)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org"),other=store.peer("other@example.org");
            for(String mime:new String[]{"image/png","video/mp4"}) {
                Files.write(source.toPath(),new byte[]{1,2,3});
                String name=mime.startsWith("image") ? "photo.png" : "video.mp4";
                FileShare sharing=FileShare.describe(FileLink.parse("https://files.example.org/"+name),source,name,mime);
                int id=store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org",name,sharing,"caption",null));
                store.getWritableDatabase().execSQL("UPDATE messages SET outgoing=0 WHERE id=?",new Object[]{id});
                TLRPC.Message original=store.getMessage(id,self);
                SerializedData out=new SerializedData();original.serializeToStream(out);SerializedData in=new SerializedData(out.toByteArray());
                TLRPC.Message cached=TLRPC.Message.TLdeserialize(in,in.readInt32(true),true);cached.readAttachPath(in,self);in.cleanup();out.cleanup();
                assertFalse(MonogramAttachments.has(cached));assertTrue(cached.attachPath==null || cached.attachPath.isEmpty());
                File local=store.attachmentFile(sharing);local.getParentFile().mkdirs();Files.copy(source.toPath(),local.toPath(),StandardCopyOption.REPLACE_EXISTING);
                try {
                    assertFalse(store.restoreMediaAttachment(cached,other));
                    assertTrue(store.restoreMediaAttachment(cached,peer));assertTrue(MonogramAttachments.has(cached));assertEquals(local.getAbsolutePath(),cached.attachPath);
                    if(cached.media.document!=null)assertEquals(local.getAbsolutePath(),cached.media.document.localPath);
                    Files.write(source.toPath(),new byte[]{4,5,6});
                    FileShare changed=FileShare.describe(sharing.link,source,name,mime);
                    store.getWritableDatabase().execSQL("UPDATE attachments SET sharing=?,path=? WHERE message=?",new Object[]{changed.xml(),local.getAbsolutePath(),id});
                    assertTrue(store.restoreMediaAttachment(cached,peer));assertEquals(store.attachmentFile(changed).getAbsolutePath(),cached.attachPath);
                    assertNotEquals(local.getAbsolutePath(),cached.attachPath);
                    store.getWritableDatabase().execSQL("UPDATE attachments SET sharing='<broken/>' WHERE message=?",new Object[]{id});
                    assertFalse(store.restoreMediaAttachment(cached,peer));assertEquals("",cached.attachPath);assertFalse(MonogramAttachments.has(cached));
                    if(cached.media.document!=null)assertNull(cached.media.document.localPath);
                }finally{local.delete();}
            }
        }finally{source.delete();context.deleteDatabase(db);}
    }
}
