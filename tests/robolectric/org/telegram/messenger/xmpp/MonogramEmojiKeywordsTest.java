package org.telegram.messenger.xmpp;

import android.content.res.AssetManager;
import java.io.File;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramEmojiKeywordsTest {
    @Test public void nativeCacheReceivesOfflineKeywordsAndUnchangedVersionsStayEmpty() throws Exception {
        AssetManager assets=RuntimeEnvironment.getApplication().getAssets();
        File archive=File.createTempFile("emoji-keywords-",".apk"); archive.deleteOnExit();
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            for(String language:new String[]{"en","pl","ro"}) {
                String name="emoji-keywords-"+language+".tsv";
                zip.putNextEntry(new ZipEntry("assets/"+name)); Files.copy(new File("src/main/assets",name).toPath(),zip); zip.closeEntry();
            }
        }
        int cookie=ReflectionHelpers.callInstanceMethod(assets,"addAssetPath",ReflectionHelpers.ClassParameter.from(String.class,archive.getAbsolutePath()));
        assertTrue(cookie>0);
        TLRPC.TL_emojiKeywordsDifference english=MonogramEmojiKeywords.load(assets,"en-US",-1);
        assertEquals("en",english.lang_code); assertTrue(english.keywords.size()>1000);
        assertTrue(english.keywords.stream().anyMatch(k -> ((TLRPC.TL_emojiKeyword)k).keyword.equals("heart") && ((TLRPC.TL_emojiKeyword)k).emoticons.contains("❤")));
        assertEquals("pl",MonogramEmojiKeywords.load(assets,"pl_PL",-1).lang_code);
        assertEquals("ro",MonogramEmojiKeywords.load(assets,"ro",-1).lang_code);
        assertEquals("en",MonogramEmojiKeywords.load(assets,"unsupported",-1).lang_code);
        assertTrue(MonogramEmojiKeywords.load(assets,"en",48).keywords.isEmpty());
    }
}
