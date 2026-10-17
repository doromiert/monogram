package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import android.content.res.Resources;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramTranslationLanguagesTest.Settings.class,MonogramMessageRegressionTest.Device.class})
public class MonogramTranslationLanguagesTest {
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static SharedPreferences getGlobalMainSettings() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("translation-languages",0);
        }
        @Implementation public static MessagesController getInstance(int account) { return null; }
    }
    @Test public void migrationReplacesAutomaticExclusionsWithOnlySystemLanguage() {
        SharedPreferences prefs=Settings.getGlobalMainSettings();
        prefs.edit().clear().putInt("translate_button_restricted_languages_version",2)
                .putStringSet("translate_button_restricted_languages",new HashSet<>(Arrays.asList("pl","en","fr"))).commit();
        RestrictedLanguagesSelectActivity.invalidateRestrictedLanguages();
        RestrictedLanguagesSelectActivity.checkRestrictedLanguages(false);
        HashSet<String> expected=new HashSet<>(Arrays.asList(Resources.getSystem().getConfiguration().locale.getLanguage()));
        assertEquals(expected,RestrictedLanguagesSelectActivity.getRestrictedLanguages());
        RestrictedLanguagesSelectActivity.getExtendedDoNotTranslate(languages -> assertEquals(expected,languages));
        assertFalse(prefs.contains("translate_button_restricted_languages"));
    }
    @Test public void migrationAndAccountChangesPreserveExplicitChoicesIncludingEmptySet() {
        for(HashSet<String> selected:Arrays.asList(new HashSet<>(Arrays.asList("fr","de")),new HashSet<String>())) {
            Settings.getGlobalMainSettings().edit().clear().putInt("translate_button_restricted_languages_version",2).commit();
            RestrictedLanguagesSelectActivity.updateRestrictedLanguages(selected,true);
            RestrictedLanguagesSelectActivity.checkRestrictedLanguages(true);
            assertEquals(selected,RestrictedLanguagesSelectActivity.getRestrictedLanguages());
            RestrictedLanguagesSelectActivity.invalidateRestrictedLanguages();
            assertEquals(selected,RestrictedLanguagesSelectActivity.getRestrictedLanguages());
        }
    }
}
