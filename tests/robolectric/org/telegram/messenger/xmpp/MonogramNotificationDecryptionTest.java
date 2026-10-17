package org.telegram.messenger.xmpp;

import android.util.SparseArray;
import androidx.collection.LongSparseArray;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramNotificationDecryptionTest.Notifications.class,MonogramNotificationDecryptionTest.Base.class,MonogramNotificationDecryptionTest.Queue.class,
                MonogramNotificationDecryptionTest.Ui.class,MonogramNotificationDecryptionTest.Events.class,
                MonogramNotificationReadTest.ObjectShadow.class})
public class MonogramNotificationDecryptionTest {
    @Implements(value=NotificationsController.class,isInAndroidSdk=false)
    public static class Notifications extends Base {
        static final List<Boolean> renders=new ArrayList<>();
        @Implementation public static void checkOtherNotificationsChannel() {}
        @Implementation public void __constructor__(int account) {}
        @Implementation protected void showOrUpdateNotification(boolean alert) { renders.add(alert); }
    }
    @Implements(value=BaseController.class,isInAndroidSdk=false)
    public static class Base {
        @Implementation public void __constructor__(int account) {}
    }
    @Implements(value=DispatchQueue.class,isInAndroidSdk=false)
    public static class Queue {
        @Implementation public void __constructor__(String name) {}
        @Implementation public void __constructor__(String name,boolean start) {}
        @Implementation public boolean postRunnable(Runnable runnable) { runnable.run();return true; }
    }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        static final List<Runnable> pending=new ArrayList<>();
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable runnable) { pending.add(runnable); }
    }
    @Implements(value=NotificationCenter.class,isInAndroidSdk=false)
    public static class Events {
        @Implementation public static NotificationCenter getGlobalInstance() { return Shadow.newInstanceOf(NotificationCenter.class); }
        @Implementation public void postNotificationName(int id,Object... args) {}
    }
    NotificationsController controller;
    ArrayList<MessageObject> pushes,delayed;
    SparseArray<MessageObject> indexed;
    @Before public void setup() {
        ApplicationLoader.applicationContext=org.robolectric.RuntimeEnvironment.getApplication();
        Notifications.renders.clear();Ui.pending.clear();
        controller=ReflectionHelpers.callConstructor(NotificationsController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        pushes=new ArrayList<>();delayed=new ArrayList<>();indexed=new SparseArray<>();
        LongSparseArray<SparseArray<MessageObject>> dictionary=new LongSparseArray<>();dictionary.put(0,indexed);
        ReflectionHelpers.setField(controller,"pushMessages",pushes);
        ReflectionHelpers.setField(controller,"delayedPushMessages",delayed);
        ReflectionHelpers.setField(controller,"pushMessagesDict",dictionary);
        ReflectionHelpers.setField(controller,"popupMessages",new ArrayList<MessageObject>());
        controller.popupReplyMessages=new ArrayList<>();
    }
    MessageObject message(int id,String body) {
        TLRPC.Message message=new TLRPC.TL_message();message.id=id;message.dialog_id=7;message.message=body;
        message.peer_id=new TLRPC.TL_peerUser();message.peer_id.user_id=7;
        MessageObject object=Shadow.newInstanceOf(MessageObject.class);object.messageOwner=message;return object;
    }
    void remember(MessageObject message) {
        indexed.put(message.getId(),message);pushes.add(message);delayed.add(message);
        controller.popupMessages.add(message);controller.popupReplyMessages.add(message);
    }
    void recover(MessageObject... messages) {
        LongSparseArray<ArrayList<MessageObject>> edits=new LongSparseArray<>();
        edits.put(7,new ArrayList<>(Arrays.asList(messages)));controller.processEditedMessages(edits);
    }
    void runUi() { for(Runnable runnable:new ArrayList<>(Ui.pending)) runnable.run();Ui.pending.clear(); }

    @Test public void recoveredPlaintextReplacesEveryExistingNotificationWithoutAlertingOrAddingUnread() {
        MessageObject encrypted=message(1,"Encrypted message"),plain=message(1,"Recovered plaintext");remember(encrypted);
        MessageObject unrelated=message(2,"Other unread message");remember(unrelated);
        recover(plain,message(3,"Archived message without a notification"));runUi();
        assertEquals(Collections.singletonList(false),Notifications.renders);
        assertEquals(2,pushes.size());assertEquals(2,indexed.size());assertEquals(2,delayed.size());
        assertSame(plain,indexed.get(1));assertSame(plain,pushes.get(0));assertSame(plain,delayed.get(0));
        assertSame(plain,controller.popupMessages.get(0));assertSame(plain,controller.popupReplyMessages.get(0));
        assertSame(unrelated,controller.popupMessages.get(1));
        // Read removal uses the current tray object's identity.
        assertTrue(controller.popupMessages.remove(pushes.get(0)));
    }
    @Test public void popupDismissedBeforeQueuedRefreshStaysDismissed() {
        remember(message(1,"Encrypted message"));recover(message(1,"Recovered plaintext"));
        controller.popupMessages.clear();controller.popupReplyMessages.clear();runUi();
        assertTrue(controller.popupMessages.isEmpty());assertTrue(controller.popupReplyMessages.isEmpty());
    }
    @Test public void archivedReadMessageCannotRecreateAnyNotification() {
        recover(message(1,"Recovered plaintext"));runUi();
        assertTrue(pushes.isEmpty());assertTrue(delayed.isEmpty());assertTrue(Notifications.renders.isEmpty());
        assertTrue(controller.popupMessages.isEmpty());assertTrue(controller.popupReplyMessages.isEmpty());
    }
}
