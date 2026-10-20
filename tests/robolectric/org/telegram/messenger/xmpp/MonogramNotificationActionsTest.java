package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramNotificationActionsTest {
    private Context context;
    private SharedPreferences prefs;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        prefs=context.getSharedPreferences("monogram-notification-actions-0",0);prefs.edit().clear().commit();
    }
    private Intent action(long peer,int anchor) {
        return new Intent().setClassName("com.negzero.monogram.beta","org.telegram.messenger.WearReplyReceiver")
                .putExtra("dialog_id",peer).putExtra("max_id",anchor);
    }
    @Test public void logoutAndSlotReuseInvalidateBothOwnerAndPendingIntentIdentity() {
        Intent old=action(-42,7);MonogramNotificationActions.bind(old,0,prefs,"alice@example.org");
        assertTrue(MonogramNotificationActions.valid(old,prefs,"alice@example.org"));
        assertFalse(MonogramNotificationActions.valid(old,prefs,"bob@example.org"));
        MonogramNotificationActions.invalidate(0);
        assertFalse(MonogramNotificationActions.valid(old,prefs,"alice@example.org"));
        Intent replacement=action(-42,7);MonogramNotificationActions.bind(replacement,0,prefs,"alice@example.org");
        assertTrue(MonogramNotificationActions.valid(replacement,prefs,"alice@example.org"));
        assertFalse(old.filterEquals(replacement));
        assertFalse(MonogramNotificationActions.valid(old,prefs,"alice@example.org"));
    }
    @Test public void actionTargetsCannotBeRetargetedOrAdvancedByUpdatedExtras() {
        Intent captured=action(42,7);MonogramNotificationActions.bind(captured,0,prefs,"alice@example.org");
        Intent same=action(42,7);MonogramNotificationActions.bind(same,0,prefs,"alice@example.org");
        assertTrue(captured.filterEquals(same));
        Intent newer=action(42,8);MonogramNotificationActions.bind(newer,0,prefs,"alice@example.org");
        assertFalse(captured.filterEquals(newer));
        assertFalse(MonogramNotificationActions.valid(new Intent(captured).putExtra("max_id",8),prefs,"alice@example.org"));
        assertFalse(MonogramNotificationActions.valid(new Intent(captured).putExtra("dialog_id",43L),prefs,"alice@example.org"));
        assertFalse(MonogramNotificationActions.valid(new Intent(captured).putExtra("currentAccount",1),prefs,"alice@example.org"));
        assertFalse(MonogramNotificationActions.valid(new Intent(captured).putExtra("topic_id",1L),prefs,"alice@example.org"));
        assertFalse(MonogramNotificationActions.valid(action(42,7),prefs,"alice@example.org"));
        assertFalse(MonogramNotificationActions.valid(captured,prefs,""));
    }
}
