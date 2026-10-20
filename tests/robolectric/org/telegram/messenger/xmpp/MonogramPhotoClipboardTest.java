// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonogramPhotoClipboardTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void pngIsCopiedByteForByteIntoIndependentPrivateSnapshot() throws Exception {
        // One RGBA pixel; IHDR/IDAT/IEND CRCs and the zlib scanline were checked offline.
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z/D/PwAG/gL+DHWJ3gAAAABJRU5ErkJggg==");
        // The source extension is deliberately unhelpful: decoded content determines the export MIME.
        File source=temporary.newFile("attachment.bin");Files.write(source.toPath(),png);
        File cache=temporary.newFolder("private-cache");
        File exported=MonogramPhotoClipboard.prepare(source,cache);

        assertEquals(new File(cache,"monogram-clipboard").getCanonicalFile(),exported.getParentFile().getCanonicalFile());
        assertNotEquals(source.getCanonicalFile(),exported.getCanonicalFile());
        assertTrue(exported.getName().endsWith(".png"));
        assertArrayEquals(png,Files.readAllBytes(exported.toPath()));
        Files.write(source.toPath(),new byte[]{1,2,3});Files.delete(source.toPath());
        assertArrayEquals("Clipboard consumers retain the snapshot after the original changes or disappears",png,Files.readAllBytes(exported.toPath()));
    }

    @Test public void invalidImageWithPngExtensionIsRejectedWithoutExportResidue() throws Exception {
        File source=temporary.newFile("not-an-image.png");
        byte[] invalid="This is not a PNG image".getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(),invalid);
        File cache=temporary.newFolder("private-cache");

        assertThrows(IOException.class,() -> MonogramPhotoClipboard.prepare(source,cache));

        File directory=new File(cache,"monogram-clipboard");
        if(directory.exists()) {
            File[] leftovers=directory.listFiles();assertNotNull(leftovers);assertEquals(0,leftovers.length);
        }
        assertArrayEquals("Rejected inputs must remain untouched",invalid,Files.readAllBytes(source.toPath()));
    }
}
