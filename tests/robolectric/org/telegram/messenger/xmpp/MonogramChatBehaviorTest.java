package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.telegram.messenger.MessagesController;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramChatBehaviorTest.Settings.class)
public class MonogramChatBehaviorTest {
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static SharedPreferences getMainSettings(int account) { return RuntimeEnvironment.getApplication().getSharedPreferences("chat-behavior-"+account,0); }
    }
    @Test public void apkDefaultsBotExceptionsAndIndependentAccountChoicesPersist() {
        Settings.getMainSettings(0).edit().clear().commit();Settings.getMainSettings(1).edit().clear().commit();
        assertTrue(MonogramChatBehavior.hideKeyboardOnScroll(0));assertEquals(", ",MonogramChatBehavior.mentionSuffix(0,false));assertEquals(" ",MonogramChatBehavior.mentionSuffix(0,true));
        assertFalse(MonogramChatBehavior.showSaveButton(0));assertFalse(MonogramChatBehavior.showRepeatButton(0));
        assertTrue(MonogramChatBehavior.showCopyPhotoButton(0));assertTrue(MonogramChatBehavior.showDetailsButton(0));
        assertTrue(MonogramChatBehavior.groupMessageMenu(0));MonogramChatBehavior.groupMessageMenu(0,false);assertFalse(MonogramChatBehavior.groupMessageMenu(0));assertTrue(MonogramChatBehavior.groupMessageMenu(1));
        MonogramChatBehavior.showCopyPhotoButton(0,false);MonogramChatBehavior.showDetailsButton(0,false);
        MonogramChatBehavior.hideKeyboardOnScroll(0,false);MonogramChatBehavior.commaAfterMention(0,false);MonogramChatBehavior.showSaveButton(0,true);MonogramChatBehavior.showRepeatButton(0,true);
        assertFalse(MonogramChatBehavior.hideKeyboardOnScroll(0));assertEquals(" ",MonogramChatBehavior.mentionSuffix(0,false));
        assertTrue(MonogramChatBehavior.showSaveButton(0));assertTrue(MonogramChatBehavior.showRepeatButton(0));
        assertFalse(MonogramChatBehavior.showCopyPhotoButton(0));assertFalse(MonogramChatBehavior.showDetailsButton(0));
        assertTrue(MonogramChatBehavior.showCopyPhotoButton(1));assertTrue(MonogramChatBehavior.showDetailsButton(1));
        assertTrue(MonogramChatBehavior.hideKeyboardOnScroll(1));assertEquals(", ",MonogramChatBehavior.mentionSuffix(1,false));assertFalse(MonogramChatBehavior.showSaveButton(1));
    }
}
