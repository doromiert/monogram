package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.XmppMessage.Encryption;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramEncryptionPolicyTest {
    private SharedPreferences prefs;
    @Before public void setup(){prefs=RuntimeEnvironment.getApplication().getSharedPreferences("daily-policy-test",0);prefs.edit().clear().commit();}
    @Test public void dailyResultsSurviveRestartAndStayScopedToAccountContactAndRoom() {
        MonogramEncryptionPolicy policy=new MonogramEncryptionPolicy(prefs);long now=1000000;
        assertTrue(policy.automatic("alice","bob"));assertNull(policy.cached("alice","bob",now));
        policy.selected("alice","bob",4,Encryption.OMEMO2,now);
        policy=new MonogramEncryptionPolicy(prefs);
        assertEquals(Encryption.OMEMO2,policy.cached("alice","bob",now+MonogramEncryptionPolicy.DAY-1));
        assertNull(policy.cached("alice","bob",now+MonogramEncryptionPolicy.DAY));assertNull(policy.cached("alice","bob",now-1));
        assertNull(policy.cached("other","bob",now));assertNull(policy.cached("alice","room@example.org",now));
        assertTrue(prefs.getBoolean("omemo-4",false));assertEquals(2,prefs.getInt("omemo-version-4",0));
    }
    @Test public void manualOverridesPersistAndReenablingForcesFreshSelection() {
        MonogramEncryptionPolicy policy=new MonogramEncryptionPolicy(prefs);
        policy.selected("alice","room",-7,Encryption.LEGACY_OMEMO,1000000);
        policy.automatic("alice","room",false);assertFalse(new MonogramEncryptionPolicy(prefs).automatic("alice","room"));
        assertTrue(policy.automatic("alice","bob"));policy.automatic("alice","room",true);
        assertNull(policy.cached("alice","room",1000001));
    }
    @Test public void plaintextUpdatesActualSendingModeAndWarningIsPerConversationPerDay() {
        MonogramEncryptionPolicy policy=new MonogramEncryptionPolicy(prefs);long now=1000000;
        policy.selected("alice","bob",4,Encryption.PLAIN,now);assertFalse(prefs.getBoolean("omemo-4",true));
        assertTrue(policy.needsWarning("alice","bob",now));policy.warned("alice","bob",now);
        assertFalse(new MonogramEncryptionPolicy(prefs).needsWarning("alice","bob",now+1));
        assertTrue(policy.needsWarning("alice","room",now+1));assertTrue(policy.needsWarning("alice","bob",now+MonogramEncryptionPolicy.DAY));
    }
}
