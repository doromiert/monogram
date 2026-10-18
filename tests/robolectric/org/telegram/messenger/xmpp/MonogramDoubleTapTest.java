package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramDoubleTapTest {
    @Test public void directionsPersistIndependentlyAndInvalidIncomingEditCannotBeEnabled() {
        Context app=RuntimeEnvironment.getApplication();
        SharedPreferences first=app.getSharedPreferences("double-tap-test-account-0",0);
        SharedPreferences other=app.getSharedPreferences("double-tap-test-account-1",0);
        first.edit().clear().commit();other.edit().clear().commit();
        assertEquals(MonogramDoubleTap.REACTION,MonogramDoubleTap.action(first,false));
        MonogramDoubleTap.save(first,false,MonogramDoubleTap.REPLY);
        MonogramDoubleTap.save(first,true,MonogramDoubleTap.EDIT);
        SharedPreferences reloaded=app.getSharedPreferences("double-tap-test-account-0",0);
        assertEquals(MonogramDoubleTap.REPLY,MonogramDoubleTap.action(reloaded,false));
        assertEquals(MonogramDoubleTap.EDIT,MonogramDoubleTap.action(reloaded,true));
        assertEquals(MonogramDoubleTap.REACTION,MonogramDoubleTap.action(other,true));
        assertThrows(IllegalArgumentException.class,() -> MonogramDoubleTap.save(first,false,MonogramDoubleTap.EDIT));
        assertEquals(MonogramDoubleTap.REPLY,MonogramDoubleTap.action(first,false));
        first.edit().putInt("monogram-double-tap-incoming",MonogramDoubleTap.EDIT).putInt("monogram-double-tap-own",999).commit();
        assertEquals(MonogramDoubleTap.REACTION,MonogramDoubleTap.action(first,false));
        assertEquals(MonogramDoubleTap.REACTION,MonogramDoubleTap.action(first,true));
        MonogramDoubleTap.save(first,false,MonogramDoubleTap.DISABLED);
        assertEquals(MonogramDoubleTap.DISABLED,MonogramDoubleTap.action(first,false));
    }
}
