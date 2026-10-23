package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramAttachmentCommitTest {
    private FileLink link() throws Exception {
        return FileLink.parse("aesgcm://files.example.org/file.bin#"+new String(new char[88]).replace('\0','a'));
    }
    @Test public void integrityEnrichmentRejectsCompletionVerifiedAgainstTheOldDescription() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="attachment-enrichment.db";context.deleteDatabase(database);
        File bytes=File.createTempFile("attachment-commit",".bin",context.getCacheDir());
        try(MonogramStore store=new MonogramStore(context,database)) {
            long self=store.peer("alice@example.org");store.peer("bob@example.org");
            FileLink link=link();
            int original=store.save(MessageFixture.modern("file",null,link.wireUrl,true,100000,null,null,null));
            assertNull(store.sharing(original));
            File oldPath=store.attachmentPath(original);oldPath.getParentFile().mkdirs();
            Files.write(oldPath.toPath(),new byte[]{9,9,9});
            TLRPC.MessageMedia oldMedia=MonogramAttachments.remoteMedia(original,"file.bin","application/octet-stream",3);
            Files.write(bytes.toPath(),new byte[]{1,2,3});
            FileShare stronger=FileShare.describe(link,bytes,"file.bin","application/octet-stream");
            assertEquals(original,store.save(MessageFixture.modern("file",null,stronger.body(""),true,100000,null,null,stronger)));
            File current=store.attachmentPath(original);assertNotEquals(oldPath,current);
            assertFalse(store.attachRemoteMetadata(original,link.wireUrl,current,oldMedia));
            assertFalse(store.attachDownloaded(original,link.wireUrl,null,oldPath,oldMedia));
            assertFalse(store.hasCachedAttachment(link.wireUrl,oldPath));
            assertEquals(current.getAbsolutePath(),store.getMessage(original,self).attachPath);
            current.getParentFile().mkdirs();Files.copy(bytes.toPath(),current.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            stronger.verify(current,new FileTransfer.Job());
            assertTrue(store.attachDownloaded(original,link.wireUrl,stronger,current,oldMedia));
            assertTrue(store.hasCachedAttachment(link.wireUrl,current));
        } finally { bytes.delete();context.deleteDatabase(database); }
    }
    @Test public void verifiedForwardSourceCacheSurvivesReopenAndDeletedOrWrongReferencesCannotCommit() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="forward-source-cache.db";context.deleteDatabase(database);
        File bytes=File.createTempFile("forward-source",".bin",context.getCacheDir());
        try {
            Files.write(bytes.toPath(),new byte[]{1,2,3});FileShare sharing=FileShare.describe(link(),bytes,"file.bin","application/octet-stream");
            int id;File cached;TLRPC.MessageMedia media;
            try(MonogramStore store=new MonogramStore(context,database)) {
                store.peer("alice@example.org");store.peer("bob@example.org");
                id=store.save(MessageFixture.modern("source",null,sharing.body(""),true,100000,null,null,sharing));
                cached=store.attachmentPath(id);cached.getParentFile().mkdirs();
                Files.copy(bytes.toPath(),cached.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                media=MonogramAttachments.remoteMedia(id,sharing.name,sharing.mime,sharing.size);
                assertFalse(store.hasCachedAttachment(sharing.link.wireUrl,cached));
                assertFalse(store.attachDownloaded(id,"https://files.example.org/wrong.bin",sharing,cached,media));
                assertFalse(store.attachDownloaded(999,sharing.link.wireUrl,sharing,cached,media));
                assertTrue(store.attachDownloaded(id,sharing.link.wireUrl,sharing,cached,media));
            }
            try(MonogramStore reopened=new MonogramStore(context,database)) {
                assertTrue(reopened.hasCachedAttachment(sharing.link.wireUrl,cached));
                sharing.verify(cached,new FileTransfer.Job());
                reopened.deleteMessages(Collections.singleton(id));
                assertFalse(reopened.attachDownloaded(id,sharing.link.wireUrl,sharing,cached,media));
            }
        } finally { bytes.delete();context.deleteDatabase(database); }
    }
    @Test public void headerPlaceholdersSurviveReopenWithoutClaimingCachedBytesOrReplacingDecodedMedia() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        String database="attachment-headers.db";context.deleteDatabase(database);
        int id;long self;File path;
        try {
            try(MonogramStore store=new MonogramStore(context,database)) {
                self=store.peer("alice@example.org");store.peer("bob@example.org");
                id=store.save(MessageFixture.modern("headers",null,link().wireUrl,true,100000,null,null,null));
                path=store.attachmentPath(id);path.delete();
                TLRPC.MessageMedia photo=MonogramAttachments.remoteMedia(id,"photo.jpg","image/jpeg",321);
                assertFalse(store.attachRemoteMetadata(id,"https://files.example.org/wrong",path,photo));
                assertTrue(store.attachRemoteMetadata(id,link().wireUrl,path,photo));
                assertFalse(store.hasCachedAttachment(link().wireUrl,path));
                assertTrue(store.getMessage(id,self).media instanceof TLRPC.TL_messageMediaPhoto);
            }
            try(MonogramStore reopened=new MonogramStore(context,database)) {
                TLRPC.Message projected=reopened.getMessage(id,self);
                assertEquals(321,projected.media.photo.sizes.get(0).size);
                assertFalse(reopened.hasCachedAttachment(link().wireUrl,path));
                path.getParentFile().mkdirs();Files.write(path.toPath(),new byte[]{1,2,3});
                TLRPC.MessageMedia decoded=MonogramAttachments.remoteMedia(id,"photo.jpg","image/jpeg",3);
                assertTrue(reopened.attachDownloaded(id,link().wireUrl,null,path,decoded));
                assertFalse(reopened.attachRemoteMetadata(id,link().wireUrl,path,MonogramAttachments.remoteMedia(id,"file","video/mp4",999)));
                assertEquals(3,reopened.getMessage(id,self).media.photo.sizes.get(0).size);
                assertTrue(reopened.hasCachedAttachment(link().wireUrl,path));
                path.delete();reopened.deleteMessages(Collections.singleton(id));
                assertFalse(reopened.attachRemoteMetadata(id,link().wireUrl,path,decoded));
            }
        } finally { context.deleteDatabase(database); }
    }
}
