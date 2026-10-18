package org.telegram.ui.ActionBar;

import android.graphics.Color;
import androidx.core.graphics.ColorUtils;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.xmpp.MonogramThemes;
import org.telegram.tgnet.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramThemesTest.Preferences.class,MonogramThemesTest.ThemeStartup.class})
public class MonogramThemesTest {
    @Implements(value=org.telegram.messenger.MessagesController.class,isInAndroidSdk=false)
    public static class Preferences {
        @Implementation public static android.content.SharedPreferences getGlobalMainSettings() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("theme-fixture",0);
        }
    }
    @Implements(value=Theme.class,isInAndroidSdk=false)
    public static class ThemeStartup { @Implementation public static void __staticInitializer__() {} }
    @org.junit.Before public void setup() { ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication(); }
    @Test public void nightVariantsKeepTheirOwnColorInsteadOfInheritingTheHomeGradient() throws Exception {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        Theme.ThemeInfo info=new Theme.ThemeInfo();info.name="Night";
        Method options=Theme.ThemeInfo.class.getDeclaredMethod("setAccentColorOptions",int[].class);options.setAccessible(true);
        options.invoke(info,(Object)new int[]{0xff5b9eff,0xffdb638b});
        assertEquals(0xff258de5,info.themeAccentsMap.get(0).myMessagesAccentColor);
        assertEquals(0,info.themeAccentsMap.get(1).myMessagesAccentColor);
        assertEquals(0,info.themeAccentsMap.get(1).myMessagesGradientAccentColor1);
        assertEquals(0xffdb638b,info.themeAccentsMap.get(1).accentColor);
    }
    @Test public void everyShippedMonetTokenResolvesAndModifiersMatchExteragram() throws Exception {
        int source=0xff526476;
        assertEquals(Integer.valueOf(ColorUtils.blendARGB(Color.BLACK,source,.7f)),MonogramMonet.resolve("n1_800(l=70)",name->source));
        assertEquals(204,Color.alpha(MonogramMonet.resolve("mWhite (a=80)",name->source)));
        assertNull(MonogramMonet.resolve("n3_500",name->source));assertNull(MonogramMonet.resolve("n1_900(a=101)",name->source));
        Path assets=Paths.get("src/main/assets");if(!Files.isDirectory(assets)) assets=Paths.get("TMessagesProj/src/main/assets");
        for(String style:new String[]{"light","dark","black"}) for(String line:Files.readAllLines(assets.resolve("monet_"+style+".attheme"))) {
            if(!line.contains("=")) continue;String value=line.substring(line.indexOf('=')+1).trim();
            if(value.matches("-?\\d+h?")) continue;
            assertNotNull(style+": "+line,MonogramMonet.resolve(value,name->source));
        }
    }
    @Test public void localThemesHaveStableIdentitiesAndCompleteLightDarkSettingsWithoutRemoteDocuments() {
        Set<Long> ids=new HashSet<>();Set<String> emojis=new HashSet<>();
        for(TLRPC.TL_theme theme:MonogramThemes.catalog()) {
            assertTrue(ids.add(theme.id));assertTrue(emojis.add(theme.emoticon));assertNull(theme.document);assertEquals(4,theme.settings.size());
            assertTrue(theme.settings.get(2).base_theme instanceof TLRPC.TL_baseThemeNight);
            SerializedData output=new SerializedData();theme.serializeToStream(output);
            SerializedData input=new SerializedData(output.toByteArray());TLRPC.TL_theme restored=TLRPC.Theme.TLdeserialize(input,input.readInt32(true),true);
            assertEquals(theme.id,restored.id);assertEquals(theme.emoticon,restored.emoticon);assertEquals(4,restored.settings.size());
            assertNotNull(restored.settings.get(0).wallpaper.settings);output.cleanup();input.cleanup();
        }
        for(EmojiThemes theme:MonogramThemes.chats(0)) {
            assertTrue(theme.getTlTheme(0).settings.get(0).base_theme instanceof TLRPC.TL_baseThemeClassic);
            assertTrue(theme.getTlTheme(1).settings.get(1).base_theme instanceof TLRPC.TL_baseThemeNight);
        }
    }
}
