package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.telegram.messenger.ApplicationLoader;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramUiPreferencesTest.Account.class,MonogramUiPreferencesTest.App.class})
public class MonogramUiPreferencesTest {
    private static MonogramController controller;
    private static String owner;
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Account {
        @Implementation public static MonogramController get(int account) { return controller; }
        @Implementation public String accountJid() { return owner; }
    }
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @Implementation public static void __staticInitializer__() {} }
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();controller=Shadow.newInstanceOf(MonogramController.class);
        owner=java.util.UUID.randomUUID()+"@example.org";
    }
    @Test public void folderArtworkAndModesRemainOwnerScoped() {
        int choice=MonogramFolderIcons.choices()[0];
        MonogramFolderIcons.set(0,42,choice);MonogramFolderIcons.setDisplayMode(0,MonogramFolderIcons.ICONS_ONLY);
        String saved=owner;owner="another@example.org";assertEquals(0,MonogramFolderIcons.get(0,42));
        assertEquals(MonogramFolderIcons.ICONS_AND_NAMES,MonogramFolderIcons.displayMode(0));
        owner=saved;assertEquals(choice,MonogramFolderIcons.get(0,42));assertEquals(MonogramFolderIcons.ICONS_ONLY,MonogramFolderIcons.displayMode(0));
        MonogramFolderIcons.remove(0,42);assertEquals(0,MonogramFolderIcons.get(0,42));
    }
    @Test public void recorderAndAvatarChoicesDoNotLeakIntoReusedAccountSlot() {
        MonogramUiPreferences.setRoundVideoCamera(0,MonogramUiPreferences.ROUND_ASK);MonogramUiPreferences.setAvatarCorners(0,0);
        assertEquals(MonogramUiPreferences.ROUND_ASK,MonogramUiPreferences.roundVideoCamera(0));assertEquals(0,MonogramUiPreferences.avatarCorners(0));
        owner="replacement@example.org";assertEquals(MonogramUiPreferences.ROUND_FRONT,MonogramUiPreferences.roundVideoCamera(0));assertEquals(28,MonogramUiPreferences.avatarCorners(0));
        assertThrows(IllegalArgumentException.class,()->MonogramUiPreferences.setRoundVideoCamera(0,99));
    }
    @Test public void messagePinsPersistAcrossReadsAndStayPrivateToOwnerAndPeer() {
        String saved=owner;
        MonogramMessagePins.save(0,owner,"chat@example.org",42,true,false);
        MonogramMessagePins.save(0,owner,"chat@example.org",43,true,false);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("42","43")),MonogramMessagePins.ids(0,owner,"chat@example.org"));
        assertTrue(MonogramMessagePins.ids(0,owner,"other@example.org").isEmpty());
        owner="replacement@example.org";assertTrue(MonogramMessagePins.ids(0,owner,"chat@example.org").isEmpty());
        assertThrows(IllegalStateException.class,()->MonogramMessagePins.save(0,saved,"chat@example.org",42,false,false));
        owner=saved;MonogramMessagePins.save(0,owner,"chat@example.org",42,false,false);
        assertEquals(java.util.Collections.singleton("43"),MonogramMessagePins.ids(0,owner,"chat@example.org"));
        MonogramMessagePins.save(0,owner,"chat@example.org",0,false,true);
        assertTrue(MonogramMessagePins.ids(0,owner,"chat@example.org").isEmpty());
    }
}
