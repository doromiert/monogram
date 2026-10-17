package org.telegram.messenger.xmpp;

import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramNotificationReadTest.Notifications.class,MonogramNotificationReadTest.ObjectShadow.class,MonogramMessageRegressionTest.Device.class})
public class MonogramNotificationReadTest {
    @org.junit.Before public void setup() {
        ApplicationLoader.applicationContext=org.robolectric.RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
    }
    @Implements(value=NotificationsController.class,isInAndroidSdk=false)
    public static class Notifications { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=MessageObject.class,isInAndroidSdk=false)
    public static class ObjectShadow {
        @RealObject MessageObject real;
        @Implementation public void __constructor__(int account,TLRPC.Message message,boolean layout,boolean media) { real.messageOwner=message; }
    }
    MessageObject push(long peer,int id) {
        TLRPC.Message message=new TLRPC.TL_message();message.dialog_id=peer;message.id=id;
        return new MessageObject(0,message,false,false);
    }
    int count(int reported,List<MessageObject> messages) {
        return ReflectionHelpers.callStaticMethod(NotificationsController.class,"monogramUnreadFloor",
                ReflectionHelpers.ClassParameter.from(long.class,7L),ReflectionHelpers.ClassParameter.from(int.class,reported),
                ReflectionHelpers.ClassParameter.from(Iterable.class,messages));
    }
    @Test public void lateUnmatchedPushesSurviveAStaleZeroWhileOtherPeersAndScheduledPushesDoNotInflateIt() {
        MessageObject first=push(7,1),second=push(7,9),other=push(8,2),scheduled=push(7,3),story=push(7,4);
        scheduled.messageOwner.from_scheduled=true;story.isStoryReactionPush=true;
        List<MessageObject> pushes=Arrays.asList(first,second,other,scheduled,story);
        assertEquals(2,count(0,pushes));assertEquals(5,count(5,pushes));assertEquals(0,count(0,Collections.emptyList()));
    }
}
