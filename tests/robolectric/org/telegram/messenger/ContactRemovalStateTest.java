package org.telegram.messenger;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.tgnet.*;

/** Native contact removal must not destroy UI state when an XMPP roster request fails. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
    shadows={ContactRemovalStateTest.Base.class,ContactRemovalStateTest.Contacts.class,
        ContactRemovalStateTest.Controller.class,ContactRemovalStateTest.Network.class,
        ContactRemovalStateTest.Ui.class,ContactRemovalStateTest.Config.class,
        ContactRemovalStateTest.Notifications.class,ContactRemovalStateTest.Locale.class})
public class ContactRemovalStateTest {
    static RequestDelegate reply;
    static TLRPC.TL_contacts_deleteContacts request;
    static long owner;
    static int reloads,bulletins;
    static TLRPC.User user;
    static MessagesController messages;
    @Implements(value=ContactsController.class,isInAndroidSdk=false)
    public static class Contacts extends Base {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void loadContacts(boolean cache,long hash) { reloads++; }
    }
    @Implements(value=BaseController.class,isInAndroidSdk=false)
    public static class Base {
        @Implementation protected ConnectionsManager getConnectionsManager() { return Shadow.newInstanceOf(ConnectionsManager.class); }
        @Implementation protected UserConfig getUserConfig() { return Shadow.newInstanceOf(UserConfig.class); }
        @Implementation protected MessagesController getMessagesController() { return messages; }
    }
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Controller {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public TLRPC.InputUser getInputUser(TLRPC.User value) {
            TLRPC.TL_inputUser input=new TLRPC.TL_inputUser();input.user_id=value.id;return input;
        }
    }
    @Implements(value=ConnectionsManager.class,isInAndroidSdk=false)
    public static class Network {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public int sendRequest(TLObject value,RequestDelegate callback) {
            request=(TLRPC.TL_contacts_deleteContacts)value;reply=callback;return 1;
        }
    }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable task) { task.run(); }
    }
    @Implements(value=UserConfig.class,isInAndroidSdk=false)
    public static class Config {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public long getClientUserId() { return owner; }
    }
    @Implements(value=NotificationCenter.class,isInAndroidSdk=false)
    public static class Notifications {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static NotificationCenter getGlobalInstance() { return Shadow.newInstanceOf(NotificationCenter.class); }
        @Implementation public void postNotificationName(int event,Object... args) { bulletins++; }
    }
    @Implements(value=LocaleController.class,isInAndroidSdk=false)
    public static class Locale {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static String getString(int id) { return "Contact operation failed"; }
    }
    private ContactsController contacts;
    @Before public void setup() {
        reply=null;request=null;owner=100;reloads=bulletins=0;
        messages=Shadow.newInstanceOf(MessagesController.class);
        contacts=Shadow.newInstanceOf(ContactsController.class);
        contacts.contacts=new ArrayList<>();contacts.contactsDict=new ConcurrentHashMap<>();
        user=new TLRPC.TL_user();user.id=12;user.first_name="Alice";user.contact=true;user.mutual_contact=true;
        TLRPC.TL_contact contact=new TLRPC.TL_contact();contact.user_id=user.id;
        contacts.contacts.add(contact);contacts.contactsDict.put(user.id,contact);
    }
    private ArrayList<TLRPC.User> users() { return new ArrayList<>(Collections.singleton(user)); }
    private void fail() { TLRPC.TL_error error=new TLRPC.TL_error();error.text="forbidden";reply.run(null,error); }
    @Test public void deletionWaitsForAcknowledgementAndFailureKeepsContact() {
        contacts.deleteContact(users(),true);
        assertEquals(12,request.id.get(0).user_id);
        assertTrue(user.contact);assertTrue(user.mutual_contact);assertEquals(1,contacts.contacts.size());
        fail();
        assertTrue(user.contact);assertTrue(user.mutual_contact);assertEquals(1,contacts.contactsDict.size());
        assertEquals(1,bulletins);assertEquals(1,reloads);
    }
    @Test public void undoableFailureRunsRollbackButReusedAccountDoesNot() {
        final int[] restored={0};
        ReflectionHelpers.callInstanceMethod(contacts,"deleteContact",
            ReflectionHelpers.ClassParameter.from(ArrayList.class,users()),
            ReflectionHelpers.ClassParameter.from(boolean.class,false),
            ReflectionHelpers.ClassParameter.from(Runnable.class,()->restored[0]++));
        fail();assertEquals(1,restored[0]);
        contacts.deleteContact(users(),false);owner=200;fail();
        assertEquals(1,restored[0]);assertEquals(1,reloads);assertEquals(1,bulletins);
    }
}
