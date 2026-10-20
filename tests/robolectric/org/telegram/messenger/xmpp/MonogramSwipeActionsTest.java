package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import android.view.View;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.Components.MonogramSwipeActionsHelper;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramSwipeActionsTest.Settings.class,MonogramSwipeActionsTest.Density.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class MonogramSwipeActionsTest {
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static SharedPreferences getMainSettings(int account) { return RuntimeEnvironment.getApplication().getSharedPreferences("swipe-actions-"+account,0); }
    }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Density {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static int dp(float value) { return (int)Math.ceil(value); }
    }
    @Before public void clear() { Settings.getMainSettings(0).edit().clear().commit();Settings.getMainSettings(1).edit().clear().commit(); }
    @Test public void orderPersistsPerAccountAndEmptyDoesNotReenableReply() {
        assertEquals(Collections.singletonList(MonogramSwipeAction.REPLY),MonogramSwipeAction.enabled(0));
        assertFalse(MonogramChatBehavior.swipeLoop(0));assertFalse(MonogramChatBehavior.swipeReversed(0));
        MonogramSwipeAction.setEnabled(0,Arrays.asList(MonogramSwipeAction.DELETE,MonogramSwipeAction.COPY,MonogramSwipeAction.DELETE));
        assertEquals(Arrays.asList(MonogramSwipeAction.DELETE,MonogramSwipeAction.COPY),MonogramSwipeAction.enabled(0));
        assertEquals(Collections.singletonList(MonogramSwipeAction.REPLY),MonogramSwipeAction.enabled(1));
        MonogramSwipeAction.REPLY.setEnabled(0,true);
        assertEquals(Arrays.asList(MonogramSwipeAction.REPLY,MonogramSwipeAction.DELETE,MonogramSwipeAction.COPY),MonogramSwipeAction.enabled(0));
        MonogramSwipeAction.REPLY.setEnabled(0,false);
        assertEquals(Arrays.asList(MonogramSwipeAction.DELETE,MonogramSwipeAction.COPY),MonogramSwipeAction.enabled(0));
        MonogramSwipeAction.setEnabled(0,Collections.emptyList());assertTrue(MonogramSwipeAction.enabled(0).isEmpty());assertEquals(9,MonogramSwipeAction.disabled(0).size());
        assertEquals(Arrays.asList(MonogramSwipeAction.COPY,MonogramSwipeAction.REPLY),MonogramSwipeAction.parse("3,invalid,2,3,900"));
    }
    private MonogramSwipeActionsHelper helper(MonogramSwipeAction... actions) {
        MonogramSwipeActionsHelper helper=new MonogramSwipeActionsHelper(new View(RuntimeEnvironment.getApplication()),0,null);helper.start(Arrays.asList(actions));return helper;
    }
    @Test public void verticalSelectionRequiresArmingThenFirst44AndSubsequent36Dp() {
        MonogramSwipeActionsHelper helper=helper(MonogramSwipeAction.REPLY,MonogramSwipeAction.COPY,MonogramSwipeAction.DELETE);
        helper.update(-49,0,100);helper.update(-49,0,200);assertEquals(MonogramSwipeAction.REPLY,helper.getSelected());
        helper.update(-60,0,100);helper.update(-60,0,110);helper.update(-60,0,153);assertEquals(MonogramSwipeAction.REPLY,helper.getSelected());
        helper.update(-60,0,154);assertEquals(MonogramSwipeAction.COPY,helper.getSelected());
        helper.update(-60,0,189);assertEquals(MonogramSwipeAction.COPY,helper.getSelected());
        helper.update(-60,0,190);assertEquals(MonogramSwipeAction.DELETE,helper.getSelected());
        helper.update(-60,0,226);assertEquals(MonogramSwipeAction.DELETE,helper.getSelected());
        helper.update(-60,0,190);assertEquals(MonogramSwipeAction.COPY,helper.getSelected());helper.detach();
    }
    @Test public void reverseAndLoopUseCurrentSettingsAndNewGestureResetsSelection() {
        MonogramChatBehavior.swipeLoop(0,true);MonogramChatBehavior.swipeReversed(0,true);
        assertFalse(MonogramChatBehavior.swipeLoop(1));assertFalse(MonogramChatBehavior.swipeReversed(1));
        MonogramSwipeActionsHelper helper=helper(MonogramSwipeAction.REPLY,MonogramSwipeAction.COPY);
        helper.update(-60,0,100);helper.update(-60,0,90);helper.update(-60,0,46);assertEquals(MonogramSwipeAction.COPY,helper.getSelected());
        helper.update(-60,0,10);assertEquals(MonogramSwipeAction.REPLY,helper.getSelected());
        helper.select(1);assertEquals(MonogramSwipeAction.COPY,helper.getSelected());
        helper.start(Collections.singletonList(MonogramSwipeAction.DELETE));assertEquals(MonogramSwipeAction.DELETE,helper.getSelected());
        helper.start(Collections.emptyList());assertNull(helper.getSelected());assertEquals(0,helper.size());helper.detach();
    }
}
