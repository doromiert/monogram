package org.telegram.ui;

import android.widget.FrameLayout;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.telegram.messenger.LocaleController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={
    MonogramConnectionNoticeTest.Factory.class,MonogramConnectionNoticeTest.Notice.class,
    MonogramConnectionNoticeTest.Labels.class})
public class MonogramConnectionNoticeTest {
    @Implements(value=LocaleController.class,isInAndroidSdk=false)
    public static class Labels {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static String getString(int id){return "label";}
    }
    @Implements(value=BulletinFactory.class,isInAndroidSdk=false)
    public static class Factory {
        static int created,duration;
        static Runnable action;
        static Bulletin last;
        @Implementation public static BulletinFactory of(FrameLayout view,Theme.ResourcesProvider colors){return Shadow.newInstanceOf(BulletinFactory.class);}
        @Implementation public Bulletin createSimpleBulletin(int icon,CharSequence text,CharSequence button,int timeout,Runnable click){
            created++;duration=timeout;action=click;return last=Shadow.newInstanceOf(Bulletin.class);
        }
    }
    @Implements(value=Bulletin.class,isInAndroidSdk=false)
    public static class Notice {
        @org.robolectric.annotation.RealObject Bulletin real;
        boolean showing;
        @Implementation public static void __staticInitializer__(){}
        @Implementation public Bulletin hideAfterBottomSheet(boolean hide){assertFalse(hide);return real;}
        @Implementation public Bulletin show(boolean top){assertTrue(top);showing=true;return real;}
        @Implementation public boolean isShowing(){return showing;}
        @Implementation public void hide(){showing=false;}
    }
    private MonogramConnectionNotice notice;
    private final List<Integer> attempts=new ArrayList<>();
    @Before public void setup(){Factory.created=0;notice=new MonogramConnectionNotice(new FrameLayout(RuntimeEnvironment.getApplication()),attempts::add);}
    @Test public void onlyIdleDisconnectedForegroundAccountGetsPersistentAction(){
        notice.update(0,true,ConnectionsManager.ConnectionStateConnecting);
        notice.update(0,true,ConnectionsManager.ConnectionStateConnected);
        notice.update(0,false,ConnectionsManager.ConnectionStateWaitingForNetwork);
        assertEquals(0,Factory.created);
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);
        assertEquals(-1,Factory.duration);assertTrue(Factory.last.isShowing());
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);assertEquals(1,Factory.created);
        Factory.action.run();assertEquals(Collections.singletonList(0),attempts);assertFalse(Factory.last.isShowing());
    }
    @Test public void connectionAttemptsAndLeavingForegroundRemoveNotice(){
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);Bulletin first=Factory.last;
        notice.update(0,true,ConnectionsManager.ConnectionStateConnecting);assertFalse(first.isShowing());
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);
        notice.update(0,false,ConnectionsManager.ConnectionStateWaitingForNetwork);assertFalse(Factory.last.isShowing());
    }
    @Test public void accountSwitchCannotReconnectPreviousAccount(){
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);Runnable old=Factory.action;Bulletin first=Factory.last;
        notice.update(3,true,ConnectionsManager.ConnectionStateWaitingForNetwork);assertFalse(first.isShowing());
        old.run();assertTrue(attempts.isEmpty());
        Factory.action.run();assertEquals(Collections.singletonList(3),attempts);
    }
}
