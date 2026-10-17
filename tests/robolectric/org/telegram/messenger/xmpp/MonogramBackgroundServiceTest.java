package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.telegram.messenger.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=36, application=Application.class,
        shadows={MonogramBackgroundServiceTest.Loader.class, MonogramBackgroundServiceTest.Settings.class,
                MonogramBackgroundServiceTest.Strings.class, MonogramBackgroundServiceTest.Accounts.class})
public class MonogramBackgroundServiceTest {
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class Loader { @Implementation public static void postInitApplication() {} }
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static android.content.SharedPreferences getNotificationsSettings(int account) {
            return RuntimeEnvironment.getApplication().getSharedPreferences("Notifications"+account,0);
        }
    }
    @Implements(value=LocaleController.class,isInAndroidSdk=false)
    public static class Strings { @Implementation public static String getString(int id) { return "Monogram connection"; } }
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Accounts {
        static int networkEvents;
        @Implementation public static MonogramController get(int account) { return Shadow.newInstanceOf(MonogramController.class); }
        @Implementation public void restore() {}
        @Implementation public void networkChanged() { networkEvents++; }
    }
    @Test public void activeAccountGetsForegroundServiceAndNetworkListener() {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("monogram-0",0).edit().putString("jid","alice@example.org").commit();
        assertTrue(NotificationsService.enabled(context));
        org.robolectric.android.controller.ServiceController<NotificationsService> service=Robolectric.buildService(NotificationsService.class).create();
        try {
            assertEquals(android.app.Service.START_STICKY, service.get().onStartCommand(null,0,1));
            assertEquals(1028, org.robolectric.Shadows.shadowOf(service.get()).getLastForegroundNotificationId());
            assertTrue((org.robolectric.Shadows.shadowOf(service.get()).getLastForegroundNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT)!=0);
            android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Accounts.networkEvents = 0;
            for (android.net.ConnectivityManager.NetworkCallback callback : org.robolectric.Shadows.shadowOf(connectivity).getNetworkCallbacks()) {
                callback.onAvailable(Shadow.newInstanceOf(android.net.Network.class));
                callback.onLost(Shadow.newInstanceOf(android.net.Network.class));
            }
            assertEquals(2 * UserConfig.MAX_ACCOUNT_COUNT, Accounts.networkEvents);
        } finally { service.destroy(); }
    }
    @Test public void existingSettingsAndLogoutControlServiceAcrossAccounts() {
        Context context=RuntimeEnvironment.getApplication();
        assertFalse(NotificationsService.enabled(context));
        context.getSharedPreferences("monogram-1",0).edit().putString("jid","alice@example.org").commit();
        assertTrue(NotificationsService.enabled(context));
        Settings.getNotificationsSettings(1).edit().putBoolean("pushService",false).commit();
        assertFalse(NotificationsService.enabled(context));
        Settings.getNotificationsSettings(1).edit().putBoolean("pushService",true).putBoolean("pushConnection",false).commit();
        assertFalse(NotificationsService.enabled(context));
        Settings.getNotificationsSettings(1).edit().putBoolean("pushConnection",true).commit();
        assertTrue(NotificationsService.enabled(context));
        context.getSharedPreferences("monogram-1",0).edit().remove("jid").commit();
        assertFalse(NotificationsService.enabled(context));
    }
}
