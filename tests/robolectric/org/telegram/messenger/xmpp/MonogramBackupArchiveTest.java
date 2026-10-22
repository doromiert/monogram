package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramBackupArchiveTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final char[] password="portable account backup phrase".toCharArray();
    private File snapshot() throws Exception {
        File root=temporary.newFolder("snapshot");
        for(String path:new String[]{"data/history.db","data/outbox.db","data/outbox-meta.json","data/omemo2.db","data/legacy.db","trust/legacy.properties",
                "preferences/xmpp.json","preferences/native.json","preferences/packs.json","preferences/giphy.json","paths.json","packs/item.webp"}) {
            File file=new File(root,path);file.getParentFile().mkdirs();Files.write(file.toPath(),("container fixture "+path).getBytes("UTF-8"));
        }
        return root;
    }
    @Test public void portableContainerAuthenticatesOwnerAndPreservesFilesInPrivateStaging() throws Exception {
        File root=snapshot(),work=temporary.newFolder("private-no-backup-work");ByteArrayOutputStream encrypted=new ByteArrayOutputStream();
        MonogramBackupArchive.write(root,work,"alice@example.org",encrypted,password);
        File staged;
        try(MonogramBackupArchive.Staged opened=MonogramBackupArchive.stage(new ByteArrayInputStream(encrypted.toByteArray()),work,"alice@example.org",password)) {
            staged=opened.directory;assertEquals("alice@example.org",opened.owner);assertTrue(opened.created>0);
            assertArrayEquals(Files.readAllBytes(new File(root,"packs/item.webp").toPath()),Files.readAllBytes(new File(staged,"packs/item.webp").toPath()));
        }
        assertFalse(staged.exists());assertEquals(0,work.listFiles().length);
        IOException wrong=assertThrows(IOException.class,()->MonogramBackupArchive.stage(new ByteArrayInputStream(encrypted.toByteArray()),work,"bob@example.org",password));
        assertTrue(wrong.getMessage().contains("different XMPP account"));assertEquals(0,work.listFiles().length);
    }
    @Test public void authenticatedZipTraversalAndIncompleteSnapshotsAreRejectedWithoutApplyingAnything() throws Exception {
        File work=temporary.newFolder("private-no-backup-work"),empty=temporary.newFolder("incomplete");
        assertThrows(IOException.class,()->MonogramBackupArchive.write(empty,work,"alice@example.org",new ByteArrayOutputStream(),password));
        ByteArrayOutputStream zip=new ByteArrayOutputStream();
        try(ZipOutputStream output=new ZipOutputStream(zip)) { output.putNextEntry(new ZipEntry("../escaped.txt"));output.write(7);output.closeEntry(); }
        ByteArrayOutputStream encrypted=new ByteArrayOutputStream();
        MonogramBackupEnvelope.encrypt(new ByteArrayInputStream(zip.toByteArray()),encrypted,password);
        IOException traversal=assertThrows(IOException.class,()->MonogramBackupArchive.stage(new ByteArrayInputStream(encrypted.toByteArray()),work,"alice@example.org",password));
        assertTrue(traversal.getMessage().contains("Invalid or duplicate backup path"));assertFalse(new File(work,"escaped.txt").exists());assertEquals(0,work.listFiles().length);
    }
}
