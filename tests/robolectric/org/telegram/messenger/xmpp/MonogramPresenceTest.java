package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.XmppContacts.PresenceState;
import org.jivesoftware.smack.packet.Presence.Mode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramPresenceTest.Text.class,MonogramMessageRegressionTest.Device.class})
public class MonogramPresenceTest {
    @Implements(value=LocaleController.class,isInAndroidSdk=false)
    public static class Text {
        @Implementation public static String getString(int id) { return Integer.toString(id); }
        @Implementation public static String formatDateOnline(long date,boolean[] shorter) { return "seen:"+date; }
    }
    private PresenceState available(Mode mode,long lastSeen) throws Exception {
        java.lang.reflect.Constructor<PresenceState> constructor=PresenceState.class.getDeclaredConstructor(boolean.class,boolean.class,boolean.class,Mode.class,long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(true,false,true,mode,lastSeen);
    }
    @Test public void nativeStatusTracksAuthorizedPresenceAndCannotPersistFalseOnline() {
        TLRPC.User user=new TLRPC.TL_user();user.id=7;user.first_name="Alice";
        MonogramPresence.apply(user,new PresenceState(true,true,0),1000);
        assertTrue(user.status instanceof TLRPC.TL_userStatusOnline);assertEquals(Integer.MAX_VALUE,user.status.expires);
        MonogramPresence.apply(user,new PresenceState(true,false,900),1000);
        assertTrue(user.status instanceof TLRPC.TL_userStatusOffline);assertEquals(900,user.status.expires);
        MonogramPresence.apply(user,PresenceState.UNKNOWN,1000);
        assertTrue(user.status instanceof TLRPC.TL_userStatusEmpty);assertEquals(0,user.status.expires);
        MonogramPresence.apply(user,new PresenceState(true,false,1001),1000);
        assertEquals(0,user.status.expires);assertEquals("Alice",user.first_name);
        assertEquals(64,user.flags&64);
        MonogramPresence.apply(user,MonogramController.cachedPresence(0,7),1000);
        assertEquals(0,user.status.expires);
    }
    @Test public void awayBusyAndIdleRemainOfflineAcrossNativeFormatting() throws Exception {
        TLRPC.User user=new TLRPC.TL_user();user.id=7;
        boolean[] online={true};
        for(Mode mode:new Mode[]{Mode.away,Mode.xa,Mode.dnd}) {
            PresenceState value=available(mode,0);
            MonogramPresence.apply(user,value,1000);assertEquals(0,user.status.expires);
            assertEquals(Integer.toString(mode==Mode.dnd ? R.string.MonogramPresenceBusy : R.string.MonogramPresenceAway),MonogramPresence.format(value,online,null));
            assertFalse(online[0]);
        }
        assertEquals("seen:900",MonogramPresence.format(available(Mode.available,900),online,null));assertFalse(online[0]);
        assertEquals(Integer.toString(R.string.MonogramPresenceUnavailable),MonogramPresence.format(PresenceState.UNKNOWN,online,null));
        assertEquals(Integer.toString(R.string.VoipOfflineTitle),MonogramPresence.format(new PresenceState(true,false,0),online,null));
    }
    @Test public void ordinaryProfileFormattingIgnoresStaleTelegramStatusAndSpecialUsersAreRetained() {
        TLRPC.User user=new TLRPC.TL_user();user.id=7;user.status=new TLRPC.TL_userStatusOnline();user.status.expires=Integer.MAX_VALUE;
        boolean[] online={true};
        assertEquals(Integer.toString(R.string.MonogramPresenceUnavailable),LocaleController.formatUserStatus(0,user,online,null));
        assertFalse(online[0]);
        user.self=true;MonogramPresence.apply(user,PresenceState.UNKNOWN,1000);assertTrue(user.status instanceof TLRPC.TL_userStatusOnline);
        user.self=false;user.bot=true;assertFalse(MonogramPresence.applies(user));
        user.bot=false;user.deleted=true;assertFalse(MonogramPresence.applies(user));
    }
}
