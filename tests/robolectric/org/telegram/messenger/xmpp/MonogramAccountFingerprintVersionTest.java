package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows=MonogramAccountFingerprintVersionTest.Account.class)
public class MonogramAccountFingerprintVersionTest {
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Account {
        @Implementation public void __constructor__(int account) {}
    }
    private SharedPreferences preferences;
    private MonogramController reopen() {
        MonogramController controller=ReflectionHelpers.callConstructor(MonogramController.class,
                ReflectionHelpers.ClassParameter.from(int.class,0));
        ReflectionHelpers.setField(controller,"preferences",preferences);
        return controller;
    }
    @Before public void setup() {
        preferences=RuntimeEnvironment.getApplication().getSharedPreferences("account-fingerprint-version-test",0);
        preferences.edit().clear().putString("jid","alice@example.org")
                .putBoolean("omemo-42",true).putInt("omemo-version-42",2).commit();
    }
    @Test public void selectionSurvivesReopenWithoutChangingChatEncryption() {
        MonogramController controller=reopen();
        assertTrue(controller.accountFingerprintModern());
        controller.setAccountFingerprintModern(false);
        controller=reopen();
        assertFalse(controller.accountFingerprintModern());
        assertTrue(controller.modernEncryption(42));
        assertTrue(preferences.getBoolean("omemo-42",false));
        controller.setAccountFingerprintModern(true);
        assertTrue(reopen().accountFingerprintModern());
    }
    @Test public void reusedAccountSlotDoesNotInheritAnotherAccountsSelection() {
        reopen().setAccountFingerprintModern(false);
        preferences.edit().putString("jid","bob@example.org").commit();
        assertTrue(reopen().accountFingerprintModern());
        preferences.edit().putString("jid","alice@example.org").commit();
        assertFalse(reopen().accountFingerprintModern());
    }
}
