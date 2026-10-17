package org.telegram.messenger;

import static org.junit.Assert.*;
import androidx.collection.LongSparseArray;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={BlockedPeersStateTest.Controller.class,BlockedPeersStateTest.Base.class,
                BlockedPeersStateTest.Network.class,BlockedPeersStateTest.Ui.class,
                BlockedPeersStateTest.Config.class,BlockedPeersStateTest.Notifications.class,
                BlockedPeersStateTest.Storage.class,BlockedPeersStateTest.Media.class,
                BlockedPeersStateTest.Objects.class,BlockedPeersStateTest.Alerts.class,BlockedPeersStateTest.Locale.class})
public class BlockedPeersStateTest {
    static final ArrayList<RequestDelegate> replies=new ArrayList<>();
    static final ArrayList<TLObject> requests=new ArrayList<>();
    static TLRPC.User user;
    static int removed,notifications;
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Controller extends Base {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void __constructor__(int account) {}
        @Implementation public TLRPC.User getUser(Long id) { return id==user.id?user:null; }
        @Implementation public static TLRPC.InputPeer getInputPeer(TLRPC.User value) {
            TLRPC.TL_inputPeerUser peer=new TLRPC.TL_inputPeerUser();peer.user_id=value.id;return peer;
        }
        @Implementation public void putUsers(ArrayList<TLRPC.User> users,boolean cached) {}
        @Implementation public void putChats(ArrayList<TLRPC.Chat> chats,boolean cached) {}
    }
    @Implements(value=BaseController.class,isInAndroidSdk=false)
    public static class Base {
        @Implementation protected ConnectionsManager getConnectionsManager() { return Shadow.newInstanceOf(ConnectionsManager.class); }
        @Implementation protected UserConfig getUserConfig() { return Shadow.newInstanceOf(UserConfig.class); }
        @Implementation protected NotificationCenter getNotificationCenter() { return Shadow.newInstanceOf(NotificationCenter.class); }
        @Implementation protected MessagesStorage getMessagesStorage() { return Shadow.newInstanceOf(MessagesStorage.class); }
        @Implementation protected MediaDataController getMediaDataController() { return Shadow.newInstanceOf(MediaDataController.class); }
    }
    @Implements(value=ConnectionsManager.class,isInAndroidSdk=false)
    public static class Network {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public int sendRequest(TLObject request,RequestDelegate reply) {
            requests.add(request);replies.add(reply);return requests.size();
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
        @Implementation public boolean isClientActivated() { return true; }
    }
    @Implements(value=NotificationCenter.class,isInAndroidSdk=false)
    public static class Notifications {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void postNotificationName(int event,Object... args) { notifications++; }
    }
    @Implements(value=MessagesStorage.class,isInAndroidSdk=false)
    public static class Storage {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void putUsersAndChats(List<TLRPC.User> users,List<TLRPC.Chat> chats,boolean transaction,boolean queue) {}
    }
    @Implements(value=MediaDataController.class,isInAndroidSdk=false)
    public static class Media {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public void removePeer(long id) { removed++; }
        @Implementation public void removeInline(long id) { removed++; }
    }
    @Implements(value=MessageObject.class,isInAndroidSdk=false)
    public static class Objects { @Implementation public static void __staticInitializer__() {} }
    static String lastAlert;
    @Implements(value=org.telegram.ui.Components.AlertsCreator.class,isInAndroidSdk=false)
    public static class Alerts {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static android.app.Dialog showSimpleAlert(org.telegram.ui.ActionBar.BaseFragment fragment,String title,String text) {
            lastAlert=text;return null;
        }
    }
    @Implements(value=LocaleController.class,isInAndroidSdk=false)
    public static class Locale {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static String getString(int id) { return "Monogram"; }
    }
    private MessagesController controller;
    private LongSparseArray<TLRPC.UserFull> full;
    @Before public void setup() {
        replies.clear();requests.clear();removed=notifications=0;lastAlert=null;
        user=new TLRPC.TL_user();user.id=12;
        controller=Shadow.newInstanceOf(MessagesController.class);
        controller.blockePeers=new LongSparseIntArray();controller.totalBlockedCount=0;
        ReflectionHelpers.setField(controller,"blockedPeerRequests",new LongSparseArray<>());
        full=new LongSparseArray<>();full.put(12,new TLRPC.TL_userFull());
        ReflectionHelpers.setField(controller,"fullUsers",full);
        ReflectionHelpers.setField(controller,"fullChats",new LongSparseArray<>());
    }
    private void success(int request) { replies.get(request).run(new TLRPC.TL_boolTrue(),null); }
    private void fail(int request) { TLRPC.TL_error error=new TLRPC.TL_error();error.text="forbidden";replies.get(request).run(null,error); }
    private TLRPC.TL_contacts_blocked list(long... ids) {
        TLRPC.TL_contacts_blocked result=new TLRPC.TL_contacts_blocked();result.count=ids.length;
        for(long id:ids) {
            TLRPC.TL_peerBlocked blocked=new TLRPC.TL_peerBlocked();
            blocked.peer_id=new TLRPC.TL_peerUser();blocked.peer_id.user_id=id;result.blocked.add(blocked);
        }
        return result;
    }
    @Test public void mutationsWaitForSuccessDeduplicateAndKeepUnknownCounts() {
        controller.blockPeer(12);controller.blockPeer(12);
        assertEquals(1,requests.size());assertEquals(0,controller.blockePeers.size());assertEquals(0,removed);
        fail(0);assertEquals("The XMPP server couldn't block this contact. Try again.",lastAlert);assertEquals(0,controller.totalBlockedCount);assertFalse(full.get(12).blocked);
        controller.blockPeer(12);success(1);
        assertEquals(1,controller.blockePeers.size());assertEquals(1,controller.totalBlockedCount);
        assertTrue(full.get(12).blocked);assertEquals(1,removed);
        int[] callbacks={0};controller.unblockPeer(12,()->callbacks[0]++);
        controller.unblockPeer(12,()->callbacks[0]++);
        assertEquals(3,requests.size());fail(2);
        assertEquals(0,callbacks[0]);assertEquals(1,controller.blockePeers.size());
        controller.totalBlockedCount=-1;controller.unblockPeer(12,()->callbacks[0]++);success(3);
        assertEquals(1,callbacks[0]);assertEquals(0,controller.blockePeers.size());
        assertEquals(-1,controller.totalBlockedCount);assertFalse(full.get(12).blocked);
    }
    @Test public void resetFailureMutationAndSnapshotFenceStaleLists() {
        controller.getBlockedPeers(true);fail(0);assertFalse(controller.loadingBlockedPeers);
        controller.getBlockedPeers(true);controller.getBlockedPeers(true);
        replies.get(1).run(list(99),null);assertEquals(0,controller.blockePeers.size());assertTrue(controller.loadingBlockedPeers);
        replies.get(2).run(list(),null);assertFalse(controller.loadingBlockedPeers);
        controller.getBlockedPeers(true);controller.blockPeer(12);success(4);
        replies.get(3).run(list(),null);assertEquals(1,controller.blockePeers.size());
        controller.getBlockedPeers(false);
        assertEquals(0,((TLRPC.TL_contacts_getBlocked)requests.get(5)).offset);
        controller.applyMonogramBlockedPeers(new ArrayList<>(),Collections.singleton(12L));
        replies.get(5).run(list(),null);assertEquals(1,controller.totalBlockedCount);assertTrue(full.get(12).blocked);
        controller.unblockPeer(12);controller.applyMonogramBlockedPeers(new ArrayList<>(),Collections.emptySet());success(6);
        assertEquals(0,controller.totalBlockedCount);assertFalse(full.get(12).blocked);
    }
    @Test public void effectiveDomainBlockSurvivesExactJidUnblockAcknowledgement() {
        controller.applyMonogramBlockedPeers(new ArrayList<>(),Collections.singleton(12L),id->id==12);
        controller.unblockPeer(12);
        controller.applyMonogramBlockedPeers(new ArrayList<>(),Collections.emptySet(),id->id==12);
        success(0);
        assertEquals(0,controller.blockePeers.size());assertEquals(0,controller.totalBlockedCount);
        assertTrue(full.get(12).blocked);
    }
    @Test public void cleanupFenceRejectsOldMutationsAndCallbacks() {
        controller.blockPeer(12);ReflectionHelpers.callInstanceMethod(controller,"resetBlockedPeersState");success(0);
        assertEquals(0,controller.blockePeers.size());assertEquals(0,removed);assertEquals(-1,controller.totalBlockedCount);
        controller.blockePeers.put(12,1);controller.totalBlockedCount=1;
        int[] callbacks={0};controller.unblockPeer(12,()->callbacks[0]++);
        ReflectionHelpers.callInstanceMethod(controller,"resetBlockedPeersState");success(1);
        assertEquals(0,callbacks[0]);assertEquals(-1,controller.totalBlockedCount);
        controller.getBlockedPeers(true);ReflectionHelpers.callInstanceMethod(controller,"resetBlockedPeersState");
        replies.get(2).run(list(12),null);assertEquals(0,controller.blockePeers.size());assertFalse(controller.loadingBlockedPeers);
    }
}
