package org.telegram.messenger.xmpp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.jivesoftware.smack.proxy.ProxyInfo;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMujiNetworkTest.Proxy.class)
public class MonogramMujiNetworkTest {
    @Implements(value=MonogramProxy.class,isInAndroidSdk=false)
    public static class Proxy {
        static boolean enabled;
        @Implementation public static ProxyInfo current(){return enabled?ProxyInfo.forSocks5Proxy("127.0.0.1",9050,null,null):null;}
    }
    @Test public void groupMediaCannotBypassAnEnabledXmppProxy(){
        Proxy.enabled=false;MonogramMujiService.requireDirectNetwork();
        Proxy.enabled=true;assertThrows(IllegalStateException.class,MonogramMujiService::requireDirectNetwork);
        Proxy.enabled=false;
    }
}
