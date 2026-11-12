package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramBatteryPromptTest {
    private Activity activity;
    private SharedPreferences preferences;
    private MonogramBatteryPrompt prompt;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        preferences = activity.getSharedPreferences("battery-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        shadowOf((PowerManager) activity.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(activity.getPackageName(), false);
        prompt = new MonogramBatteryPrompt();
    }

    @Test public void interruptedPromptRemainsPendingAndDoesNotStack() {
        prompt.showIfNeeded(activity, preferences, false);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        prompt.showIfNeeded(activity, preferences, true);
        AlertDialog first = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(first.isShowing());
        prompt.showIfNeeded(activity, preferences, true);
        assertSame(first, ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(preferences.getBoolean(MonogramBatteryPrompt.ACKNOWLEDGED, false));
        prompt.pause();
        assertFalse(first.isShowing());
        assertFalse(preferences.getBoolean(MonogramBatteryPrompt.ACKNOWLEDGED, false));
        new MonogramBatteryPrompt().showIfNeeded(activity, preferences, true);
        assertNotSame(first, ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }

    @Test public void explicitDismissalAcknowledgesAcrossRecreation() {
        prompt.showIfNeeded(activity, preferences, true);
        AlertDialog shown = ShadowAlertDialog.getLatestAlertDialog();
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(preferences.getBoolean(MonogramBatteryPrompt.ACKNOWLEDGED, false));
        new MonogramBatteryPrompt().showIfNeeded(activity, preferences, true);
        assertSame(shown, ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void enableOpensPackageScopedSystemRequest() {
        prompt.showIfNeeded(activity, preferences, true);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        android.content.Intent intent = shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.getAction());
        assertEquals("package:" + activity.getPackageName(), intent.getDataString());
        assertTrue(preferences.getBoolean(MonogramBatteryPrompt.ACKNOWLEDGED, false));
    }

    @Test public void unrestrictedAppsAreSkippedWithoutConsumingReminder() {
        shadowOf((PowerManager) activity.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(activity.getPackageName(), true);
        prompt.showIfNeeded(activity, preferences, true);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(preferences.getBoolean(MonogramBatteryPrompt.ACKNOWLEDGED, false));
    }

    @Test public void backDismissalAlsoCountsAsAcknowledgement() {
        prompt.showIfNeeded(activity, preferences, true);
        ShadowAlertDialog.getLatestAlertDialog().cancel();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(preferences.getBoolean(MonogramBatteryPrompt.ACKNOWLEDGED, false));
    }
}
