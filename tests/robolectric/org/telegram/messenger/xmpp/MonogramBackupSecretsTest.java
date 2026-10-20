package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramBackupSecretsTest {
    private static String unseal(String value) {
        if (!value.startsWith("old-phone:")) throw new IllegalArgumentException("Wrong Keystore fixture");
        return value.substring(10);
    }
    @Test public void credentialsNestedInvitationsAndBookmarksRewrapForAnotherDeviceWithoutChangingInputs() throws Exception {
        Map<String,Object> saved=new LinkedHashMap<>();saved.put("jid","alice@example.org");saved.put("device",1234);
        saved.put("credential","old-phone:login secret");saved.put("room-password-room@example.org","old-phone:room secret");
        saved.put("room-invitations-alice@example.org",new JSONObject().put("room@example.org",new JSONObject().put("password","old-phone:invite secret")).toString());
        saved.put("room-password-public@example.org","");saved.put("blocked-jids-alice@example.org",new HashSet<>(Arrays.asList("blocked@example.org")));
        Map<String,Object> portable=MonogramBackupSecrets.preferences(MonogramBackupSecrets.Namespace.XMPP,saved,MonogramBackupSecretsTest::unseal);
        assertEquals("login secret",portable.get("credential"));assertEquals("old-phone:login secret",saved.get("credential"));
        Map<String,Object> restored=MonogramBackupSecrets.preferences(MonogramBackupSecrets.Namespace.XMPP,portable,value->"new-phone:"+value);
        assertEquals("new-phone:login secret",restored.get("credential"));assertEquals(1234,restored.get("device"));
        assertEquals("new-phone:invite secret",new JSONObject((String)restored.get("room-invitations-alice@example.org")).getJSONObject("room@example.org").getString("password"));
        assertEquals("",restored.get("room-password-public@example.org"));assertEquals(saved.get("blocked-jids-alice@example.org"),restored.get("blocked-jids-alice@example.org"));
        String bookmark=MonogramBackupSecrets.bookmarkPayload("old-phone:bookmark xml",MonogramBackupSecretsTest::unseal);
        assertEquals("new-phone:bookmark xml",MonogramBackupSecrets.bookmarkPayload(bookmark,value->"new-phone:"+value));
        assertNull(MonogramBackupSecrets.bookmarkPayload(null,value->{throw new AssertionError();}));
    }
    @Test public void providerTokensArePortableAndUnreadableSecretsAbortInsteadOfCopyingCiphertext() throws Exception {
        for(MonogramBackupSecrets.Namespace namespace:new MonogramBackupSecrets.Namespace[]{MonogramBackupSecrets.Namespace.PACKS,MonogramBackupSecrets.Namespace.GIPHY,MonogramBackupSecrets.Namespace.MAIN}) {
            String key=namespace==MonogramBackupSecrets.Namespace.PACKS ? "token" : namespace==MonogramBackupSecrets.Namespace.GIPHY ? "key" : "monogram_translation_api_key";
            assertEquals("api secret",MonogramBackupSecrets.preferences(namespace,Collections.singletonMap(key,"old-phone:api secret"),MonogramBackupSecretsTest::unseal).get(key));
            assertThrows(IllegalArgumentException.class,()->MonogramBackupSecrets.preferences(namespace,Collections.singletonMap(key,"unreadable hardware ciphertext"),MonogramBackupSecretsTest::unseal));
        }
    }
}
