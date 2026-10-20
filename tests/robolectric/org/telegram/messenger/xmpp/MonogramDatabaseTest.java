package org.telegram.messenger.xmpp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The same isolated database assertions can run when the phone is away. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonogramDatabaseTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void storage() throws Exception {
        MonogramStoreDeviceTest.main(new String[]{temporary.newFolder("store").toString()});
    }
    @Test public void replies() throws Exception {
        MonogramReplyStoreDeviceTest.main(new String[]{temporary.newFolder("replies").toString()});
    }
    @Test public void corrections() throws Exception {
        MonogramCorrectionStoreDeviceTest.main(new String[]{temporary.newFolder("corrections").toString()});
    }
    @Test public void captions() throws Exception {
        MonogramCaptionStoreDeviceTest.main(new String[]{temporary.newFolder("captions").toString()});
    }
    @Test public void captionsNeverOpenEncryptedFileKeysInBrowser() {
        String key = new String(new char[88]).replace('\0', 'a');
        String text = "Private AESGCM://example.org/private.png#" + key + " and https://example.org/public";
        android.text.SpannableString caption = new android.text.SpannableString(text);
        android.text.util.Linkify.addLinks(caption, android.text.util.Linkify.WEB_URLS);
        MonogramAttachments.suppressEncryptedLinks(caption);
        android.text.style.URLSpan[] links = caption.getSpans(0, caption.length(), android.text.style.URLSpan.class);
        org.junit.Assert.assertEquals(1, links.length);
        org.junit.Assert.assertEquals("https://example.org/public", links[0].getURL());
        org.junit.Assert.assertEquals(text, caption.toString());
    }
}
