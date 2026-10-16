package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramFavoriteEmojiTest.Settings.class,MonogramFavoriteEmojiTest.Display.class})
public class MonogramFavoriteEmojiTest {
    @Implements(value=org.telegram.messenger.AndroidUtilities.class,isInAndroidSdk=false)
    public static class Display {
        @Implementation protected static void __staticInitializer__() {}
        @Implementation public static boolean isTablet() { return false; }
        @Implementation public static int dp(float value) { return (int)Math.ceil(value); }
    }
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static SharedPreferences getGlobalEmojiSettings() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("emoji",Context.MODE_PRIVATE);
        }
    }
    @Test public void favoritesPersistIndependentlyOfRecentsAndAppearFirstWithoutDuplicates() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        SharedPreferences settings=Settings.getGlobalEmojiSettings(); settings.edit().clear().commit();
        Emoji.recentEmoji.clear(); Emoji.emojiUseHistory.clear();
        Emoji.recentEmoji.addAll(Arrays.asList("👍","🐢","animated_123"));
        Emoji.setFavoriteEmoji("🐢",true); Emoji.setFavoriteEmoji("🔥",true); Emoji.setFavoriteEmoji("🐢",true);
        Emoji.setFavoriteEmoji("not an emoji",true);
        Emoji.setFavoriteEmoji("❤",true);
        assertTrue(Emoji.isFavoriteEmoji("❤️"));
        Emoji.setFavoriteEmoji("❤️",false);
        assertEquals(Arrays.asList("🐢","🔥"),Emoji.getFavoriteEmoji());
        assertEquals(Arrays.asList("🐢","🔥","👍"),Emoji.getRecentEmojiWithFavorites(false));
        assertEquals(Arrays.asList("🐢","🔥","👍","animated_123"),Emoji.getRecentEmojiWithFavorites(true));
        assertEquals("🐢\n🔥",settings.getString("monogram_favorite_emoji",null));
        Emoji.clearRecentEmoji();
        assertEquals(Arrays.asList("🐢","🔥"),Emoji.getRecentEmojiWithFavorites(false));
        Emoji.setFavoriteEmoji("🐢",false);
        assertEquals(Arrays.asList("🔥"),Emoji.getFavoriteEmoji());
        assertTrue(Emoji.recentEmoji.isEmpty());
    }
}
