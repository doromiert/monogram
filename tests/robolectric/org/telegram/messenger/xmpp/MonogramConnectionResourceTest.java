package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import android.content.SharedPreferences;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramConnectionResourceTest {
    @Test public void distinctInstallationsDoNotKickEachOtherOffAndReconnectKeepsItsResource() {
        SharedPreferences phone=RuntimeEnvironment.getApplication().getSharedPreferences("resource-phone",0);
        SharedPreferences tablet=RuntimeEnvironment.getApplication().getSharedPreferences("resource-tablet",0);
        phone.edit().clear().commit();tablet.edit().clear().commit();
        String first=MonogramController.connectionResource(phone),second=MonogramController.connectionResource(tablet);
        assertTrue(first.startsWith("Monogram-"));assertNotEquals(first,second);
        assertEquals(first,MonogramController.connectionResource(RuntimeEnvironment.getApplication().getSharedPreferences("resource-phone",0)));
        assertEquals(second,MonogramController.connectionResource(tablet));
    }
}
