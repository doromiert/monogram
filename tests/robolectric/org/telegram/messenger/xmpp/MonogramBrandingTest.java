package org.telegram.messenger.xmpp;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonogramBrandingTest {
    @Test public void freshMediaFoldersUseMonogramAndExistingCachesRemainReadable() throws Exception {
        File root=Files.createTempDirectory("monogram-branding-").toFile();
        File legacy=new File(root,"Telegram Images"),current=new File(root,"Monogram Images");
        try {
            assertEquals(current,MonogramBranding.mediaDirectory(root,"Telegram Images"));
            assertTrue(legacy.mkdir());
            assertEquals(legacy,MonogramBranding.mediaDirectory(root,"Telegram Images"));
            assertTrue(current.mkdir());
            assertEquals(legacy,MonogramBranding.mediaDirectory(root,"Telegram Images"));
        } finally {legacy.delete();current.delete();root.delete();}
    }
}
