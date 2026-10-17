package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramNotificationSettingsTest.Settings.class,MonogramNotificationSettingsTest.Clock.class,
                MonogramNotificationSettingsTest.Base.class,MonogramNotificationSettingsTest.Notifications.class,
                MonogramNotificationSettingsTest.Objects.class})
public class MonogramNotificationSettingsTest {
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static SharedPreferences getNotificationsSettings(int account) {
            return RuntimeEnvironment.getApplication().getSharedPreferences("notification-restart-"+account,Context.MODE_PRIVATE);
        }
    }
    @Implements(value=ConnectionsManager.class,isInAndroidSdk=false)
    public static class Clock extends Base {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void __constructor__(int account) {}
        @Implementation public static ConnectionsManager getInstance(int account) {
            return ReflectionHelpers.callConstructor(ConnectionsManager.class,ReflectionHelpers.ClassParameter.from(int.class,account));
        }
        @Implementation public int getCurrentTime() { return 1_800_000_000; }
    }
    @Implements(value=BaseController.class,isInAndroidSdk=false)
    public static class Base { @Implementation public void __constructor__(int account) {} }
    @Implements(value=NotificationsController.class,isInAndroidSdk=false)
    public static class Notifications { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=MessageObject.class,isInAndroidSdk=false)
    public static class Objects { @Implementation public static void __staticInitializer__() {} }
    @Before public void setup() {
        Settings.getNotificationsSettings(0).edit().clear().commit();
        Settings.getNotificationsSettings(1).edit().clear().commit();
    }
    private TLRPC.Dialog refresh(int account,long peer) {
        TLRPC.Dialog dialog=new TLRPC.TL_dialog();dialog.id=peer;
        dialog.peer=new TLRPC.TL_peerChat();dialog.peer.chat_id=-peer;
        dialog.notify_settings=new TLRPC.TL_peerNotifySettings();
        SharedPreferences.Editor editor=Settings.getNotificationsSettings(account).edit();
        new NotificationsSettingsFacade(account).setSettingsForDialog(editor,dialog,dialog.notify_settings);
        editor.commit();return dialog;
    }
    private void fullSettings(NotificationsSettingsFacade facade,long peer,long topic,TLRPC.PeerNotifySettings settings) {
        facade.restoreLocalNotificationSettings(peer,topic,settings);
    }
    @Test public void recreatedFacadeAndRepeatedDialogRefreshPreserveForeverWithoutMutingOtherRoomsOrAccounts() {
        SharedPreferences prefs=Settings.getNotificationsSettings(0);
        prefs.edit().putInt("notify2_-7",2).putBoolean("silent_-7",true).commit();
        for(int restart=0;restart<3;restart++) {
            TLRPC.Dialog muted=refresh(0,-7);
            assertEquals(Integer.MAX_VALUE,muted.notify_settings.mute_until);
            assertEquals(4,muted.notify_settings.flags&4);assertTrue(muted.notify_settings.silent);
            assertEquals(2,prefs.getInt("notify2_-7",-1));
            assertEquals(0,refresh(0,-8).notify_settings.mute_until);
            assertFalse(prefs.contains("notify2_-8"));
            assertEquals(0,refresh(1,-7).notify_settings.mute_until);
            assertFalse(Settings.getNotificationsSettings(1).contains("notify2_-7"));
        }
    }
    @Test public void temporaryMuteExplicitUnmuteAndDefaultSurviveRefreshWithOriginalExpiry() {
        SharedPreferences prefs=Settings.getNotificationsSettings(0);
        int until=1_800_003_600;
        prefs.edit().putInt("notify2_-7",3).putInt("notifyuntil_-7",until).commit();
        assertEquals(until,refresh(0,-7).notify_settings.mute_until);
        assertEquals(3,prefs.getInt("notify2_-7",-1));assertEquals(until,prefs.getInt("notifyuntil_-7",0));
        prefs.edit().putInt("notify2_-7",0).commit();
        assertEquals(0,refresh(0,-7).notify_settings.mute_until);assertEquals(0,prefs.getInt("notify2_-7",-1));
        prefs.edit().remove("notify2_-7").commit();
        assertEquals(0,refresh(0,-7).notify_settings.flags&4);assertFalse(prefs.contains("notify2_-7"));
    }
    @Test public void fullChatSettingsUseLatestLocalChoiceAndKeepTopicAndStoryOverridesScoped() {
        SharedPreferences prefs=Settings.getNotificationsSettings(0);
        prefs.edit().putInt("notify2_-7",2).putInt("notify2_-7_9",0).putBoolean("stories_-7",false).commit();
        NotificationsSettingsFacade facade=new NotificationsSettingsFacade(0);
        TLRPC.PeerNotifySettings response=new TLRPC.TL_peerNotifySettings();
        fullSettings(facade,-7,0,response);
        assertEquals(Integer.MAX_VALUE,response.mute_until);assertTrue(response.stories_muted);
        TLRPC.PeerNotifySettings topic=new TLRPC.TL_peerNotifySettings();fullSettings(facade,-7,9,topic);
        assertEquals(0,topic.mute_until);assertEquals(0,topic.flags&64);
        assertEquals(0,prefs.getInt("notify2_-7_9",-1));
        // A delayed full-chat response must observe an unmute made after the response was built.
        prefs.edit().putInt("notify2_-7",0).commit();fullSettings(facade,-7,0,response);
        assertEquals(0,response.mute_until);assertEquals(4,response.flags&4);
        assertEquals(0,prefs.getInt("notify2_-7",-1));
    }
}
