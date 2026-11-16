package org.telegram.messenger;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import android.content.pm.ApplicationInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=36, application=Application.class)
public class MonogramDebugToastReceiverTest {
    @Test public void boundedPromptsPreserveUnicodeAndIgnoreBlankInput() {
        assertEquals("", MonogramDebugToastReceiver.message(new Intent()));
        assertEquals("", MonogramDebugToastReceiver.message(new Intent().putExtra("message", " \n ")));
        String text = MonogramDebugToastReceiver.message(new Intent().putExtra("message", new String(new char[600]).replace("\0", "😀")));
        assertEquals(512, text.codePointCount(0, text.length()));
        assertFalse(Character.isHighSurrogate(text.charAt(text.length()-1)));
    }
    @Test public void backgroundPromptIsVisibleAsNotificationAndNonDebugBuildIgnoresIt() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        context.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        ApplicationLoader.mainInterfacePaused = true;
        MonogramDebugToastReceiver receiver=new MonogramDebugToastReceiver();
        String action="fixture.MONOGRAM_PROMPT";
        context.registerReceiver(receiver,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        context.sendOrderedBroadcast(new Intent(action).putExtra("message", "Please upload a voice message; I am listening."),null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Notification posted = Shadows.shadowOf(manager).getNotification(MonogramDebugToastReceiver.NOTIFICATION);
        assertNotNull(posted);
        assertEquals("Monogram testing", posted.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("voice message"));
        manager.cancelAll();
        context.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        context.sendOrderedBroadcast(new Intent(action).putExtra("message", "Ignored"),null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        context.unregisterReceiver(receiver);
        assertEquals(0, Shadows.shadowOf(manager).getAllNotifications().size());
    }
}
