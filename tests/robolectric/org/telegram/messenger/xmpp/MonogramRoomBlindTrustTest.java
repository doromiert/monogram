package org.telegram.messenger.xmpp;

import android.content.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramRoomBlindTrustTest.Controller.class)
public class MonogramRoomBlindTrustTest {
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller {@Implementation public void __constructor__(int account){}}
    Context context;MonogramStore store;MonogramController controller;SharedPreferences prefs;long room;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;context.deleteDatabase("room-trust.db");store=new MonogramStore(context,"room-trust.db");
        prefs=context.getSharedPreferences("room-trust",0);prefs.edit().clear().putString("jid","alice@example.org").commit();
        controller=Shadow.newInstanceOf(MonogramController.class);ReflectionHelpers.setField(controller,"verifiedRoomTrustMembers",new java.util.concurrent.ConcurrentHashMap<>());ReflectionHelpers.setField(controller,"store",store);ReflectionHelpers.setField(controller,"preferences",prefs);ReflectionHelpers.setField(controller,"encryptionPolicy",new MonogramEncryptionPolicy(prefs));
        room=store.room("friends@conference.example.org","Alice");store.roomRoster(room,Collections.singletonMap("bob@example.org","member"));
    }
    @After public void cleanup(){store.close();context.deleteDatabase("room-trust.db");}
    @Test public void roomOptInFollowsVerifiedMembershipAndNeverApprovesSelfOrOtherAccounts()throws Exception {
        MonogramEncryptionPolicy policy=new MonogramEncryptionPolicy(prefs);policy.selected("alice@example.org",store.jid(room),room,com.negzero.monogram.xmpp.XmppMessage.Encryption.PLAIN,10000);
        assertFalse(controller.blindlyTrustAddress("bob@example.org"));controller.setBlindlyTrustRoom(room,true);
        assertNull(policy.cached("alice@example.org",store.jid(room),10001));
        assertTrue(controller.blindlyTrustAddress("bob@example.org"));assertFalse(controller.blindlyTrustAddress("stranger@example.org"));
        java.util.concurrent.ConcurrentHashMap<Long,Set<String>> verified=ReflectionHelpers.getField(controller,"verifiedRoomTrustMembers");
        verified.put(room,Collections.singleton("fresh@example.org"));assertTrue(controller.blindlyTrustAddress("fresh@example.org"));assertFalse(controller.blindlyTrustAddress("bob@example.org"));verified.clear();
        assertFalse(controller.blindlyTrustAddress("alice@example.org"));
        Map<String,String> members=new LinkedHashMap<>();members.put("bob@example.org","member");members.put("new@example.org","member");store.roomRoster(room,members);
        assertTrue(controller.blindlyTrustAddress("new@example.org"));
        store.roomRoster(room,Collections.singletonMap("new@example.org","member"));assertFalse(controller.blindlyTrustAddress("bob@example.org"));
        prefs.edit().putString("jid","other@example.org").commit();assertFalse(controller.blindlyTrustAddress("new@example.org"));
        prefs.edit().putString("jid","alice@example.org").commit();controller.setBlindlyTrustRoom(room,false);assertFalse(controller.blindlyTrustAddress("new@example.org"));
    }
}
