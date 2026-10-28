package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import com.negzero.monogram.xmpp.XmppSession;
import java.util.HashMap;
import java.util.Map;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=Application.class,
        shadows={MonogramScreenWakeTest.Accounts.class,MonogramScreenWakeTest.Session.class,
                MonogramScreenWakeTest.Connections.class,MonogramScreenWakeTest.Events.class,MonogramScreenWakeTest.Log.class})
public class MonogramScreenWakeTest {
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Accounts {
        static final Map<Integer,MonogramController> instances=new HashMap<>();
        int restores;
        @Implementation public void __constructor__(int account) {}
        @Implementation public static MonogramController get(int account) {
            assertTrue("Unconfigured account was instantiated",instances.containsKey(account));
            return instances.get(account);
        }
        @Implementation public void restore() { restores++; }
        // Exercise the real networkChanged(): authenticated sessions probe, disconnected ones restore.
    }
    @Implements(value=XmppSession.class,isInAndroidSdk=false)
    public static class Session {
        @Implementation public void __constructor__(com.negzero.monogram.xmpp.Account account,String password,int device,java.io.File trust,com.negzero.monogram.xmpp.Omemo2Device modern,com.negzero.monogram.xmpp.XmppRecovery recovery,com.negzero.monogram.xmpp.XmppSession.Listener listener){}

        XMPPTCPConnection connection;
        int probes;
        @Implementation public void __constructor__(com.negzero.monogram.xmpp.Account account,String password,int device,java.io.File trust,com.negzero.monogram.xmpp.Omemo2Device modern,XmppSession.Listener listener) {}
        @Implementation public XMPPTCPConnection connection() { return connection; }
        @Implementation public void checkConnection() { probes++; }
    }
    @Implements(value=ConnectionsManager.class,isInAndroidSdk=false)
    public static class Connections {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void __constructor__(int account) {}
        @Implementation public static ConnectionsManager getInstance(int account) {
            return instance(ConnectionsManager.class,account);
        }
    }
    @Implements(value=NotificationCenter.class,isInAndroidSdk=false)
    public static class Events {
        @Implementation public void __constructor__(int account) {}
        @Implementation public static NotificationCenter getGlobalInstance() { return instance(NotificationCenter.class,-1); }
        @Implementation public void postNotificationName(int id,Object... args) {}
    }
    @Implements(value=FileLog.class,isInAndroidSdk=false)
    public static class Log {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void d(String message) {}
    }
    static <T> T instance(Class<T> type,int account) {
        return ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(int.class,account));
    }
    static XmppSession session() {
        return ReflectionHelpers.callConstructor(XmppSession.class,
                ReflectionHelpers.ClassParameter.from(com.negzero.monogram.xmpp.Account.class,null),
                ReflectionHelpers.ClassParameter.from(String.class,null),ReflectionHelpers.ClassParameter.from(int.class,1),
                ReflectionHelpers.ClassParameter.from(java.io.File.class,null),
                ReflectionHelpers.ClassParameter.from(com.negzero.monogram.xmpp.Omemo2Device.class,null),
                ReflectionHelpers.ClassParameter.from(XmppSession.Listener.class,null));
    }
    Context context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        Accounts.instances.clear();UserConfig.selectedAccount=0;
        for(int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++)
            context.getSharedPreferences("monogram-"+account,0).edit().clear().commit();
    }
    private Session configure(int account,boolean authenticated) throws Exception {
        context.getSharedPreferences("monogram-"+account,0).edit().putString("jid","user"+account+"@example.org").commit();
        MonogramController controller=instance(MonogramController.class,account);
        Accounts.instances.put(account,controller);
        XmppSession session=session();
        Session network=Shadow.extract(session);
        network.connection=new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());
        ReflectionHelpers.setField(network.connection,"authenticated",authenticated);
        ReflectionHelpers.setField(controller,"session",session);
        return network;
    }
    @Test public void backgroundScreenWakeProbesConnectedAndRestoresDisconnectedConfiguredAccountsOnly() throws Exception {
        Session connected=configure(0,true),disconnected=configure(2,false);
        new ScreenReceiver().onReceive(context,new Intent(Intent.ACTION_SCREEN_ON));
        assertTrue(ApplicationLoader.isScreenOn);
        assertEquals(1,connected.probes);assertEquals(0,disconnected.probes);
        Accounts first=Shadow.extract(Accounts.instances.get(0)),second=Shadow.extract(Accounts.instances.get(2));
        assertEquals(0,first.restores);assertEquals(1,second.restores);
    }
    @Test public void screenOffDoesNotProbeOrReconnect() throws Exception {
        Session connected=configure(0,true),disconnected=configure(2,false);
        new ScreenReceiver().onReceive(context,new Intent(Intent.ACTION_SCREEN_OFF));
        assertFalse(ApplicationLoader.isScreenOn);
        assertEquals(0,connected.probes);assertEquals(0,disconnected.probes);
        Accounts second=Shadow.extract(Accounts.instances.get(2));assertEquals(0,second.restores);
    }
}
