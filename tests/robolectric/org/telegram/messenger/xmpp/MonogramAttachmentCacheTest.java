package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramAttachmentCacheTest {
    @Test public void onlyCommittedAccountFilesAreReusableAcrossReopening() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance = new org.telegram.messenger.ApplicationLoader();
        String database = "gif-cache.db", otherDatabase = "other-gif-cache.db";
        context.deleteDatabase(database); context.deleteDatabase(otherDatabase);
        File source = File.createTempFile("gif-cache", ".gif", context.getCacheDir()), cached = null;
        FileLink link = FileLink.parse("https://files.example.org/animation.gif");
        try {
            Files.write(source.toPath(), new byte[]{'G','I','F','8','9','a'});
            FileShare sharing = FileShare.describe(link, source, "animation.gif", "image/gif");
            try (MonogramStore store = new MonogramStore(context, database)) {
                int id = store.save(XmppMessage.sentFile(org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/phone"),
                        "bob@example.org", "gif", sharing, "", null));
                cached = store.attachmentFile(sharing);
                cached.getParentFile().mkdirs(); Files.copy(source.toPath(), cached.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                assertFalse(store.hasCachedAttachment(link.wireUrl, cached));
                store.attach(id, link.wireUrl, cached, MonogramAttachments.remoteMedia(id, sharing.name, sharing.mime, sharing.size));
                assertTrue(store.hasCachedAttachment(link.wireUrl, cached));
                sharing.verify(cached, new FileTransfer.Job());
                assertFalse(store.hasCachedAttachment("https://files.example.org/different.gif", cached));
                Files.write(source.toPath(), new byte[]{'G','I','F','8','7','a'});
                FileShare changed = FileShare.describe(link, source, "animation.gif", "image/gif");
                assertNotEquals(cached, store.attachmentFile(changed));
                assertFalse(store.hasCachedAttachment(link.wireUrl, store.attachmentFile(changed)));
            }
            try (MonogramStore reopened = new MonogramStore(context, database);
                 MonogramStore other = new MonogramStore(context, otherDatabase)) {
                assertTrue(reopened.hasCachedAttachment(link.wireUrl, cached));
                assertFalse(other.hasCachedAttachment(link.wireUrl, cached));
                Files.write(cached.toPath(), Files.readAllBytes(source.toPath()));
                try { sharing.verify(cached, new FileTransfer.Job()); fail("Corrupt cached GIF accepted"); }
                catch (FileTransfer.Failure invalid) { assertEquals("INTEGRITY", invalid.reason); }
                Files.write(cached.toPath(), new byte[0]);
                assertFalse(reopened.hasCachedAttachment(link.wireUrl, cached));
                cached.delete(); assertFalse(reopened.hasCachedAttachment(link.wireUrl, cached));
            }
        } finally {
            source.delete(); if (cached != null) cached.delete();
            context.deleteDatabase(database); context.deleteDatabase(otherDatabase);
        }
    }
}
