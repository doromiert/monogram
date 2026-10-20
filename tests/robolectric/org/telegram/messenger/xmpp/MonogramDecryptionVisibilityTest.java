package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.*;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MonogramDecryptionVisibilityTest {
    private MonogramController controller;
    private MonogramStore store;
    private long self,peer;
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        ApplicationLoader.applicationContext.deleteDatabase("monogram-0.db");
        ApplicationLoader.applicationContext.getSharedPreferences("monogram-0",0).edit().clear().putString("jid","alice@example.org").commit();
        controller=ReflectionHelpers.callConstructor(MonogramController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        store=ReflectionHelpers.getField(controller,"store"); self=store.peer("alice@example.org"); peer=store.peer("bob@example.org");
        ReflectionHelpers.setField(controller,"self",self);
    }
    @After public void cleanup() {
        for(String name:new String[]{"worker","sendWorker","bookmarkWorker","readSyncWorker","roomWorker","cacheWorker","archiveWorker","fileWorker","profileWorker","fingerprintWorker","autoDownloadWorker"})
            ((ExecutorService)ReflectionHelpers.getField(controller,name)).shutdownNow();
        store.close(); ((MonogramOmemo2Store)ReflectionHelpers.getField(controller,"modernStore")).close();
        ApplicationLoader.applicationContext.deleteDatabase("monogram-0.db");
        ApplicationLoader.applicationContext.getSharedPreferences("monogram-0",0).edit().clear().commit();
    }
    private XmppMessage message(String wire,boolean readable) throws Exception {
        return MessageFixture.replyMessage(wire,null,null,readable ? "Clear text" : null,readable,false,200000,"phone",true,null);
    }
    @Test public void provisionalCiphertextIsDurableButHiddenAndPlaintextGetsOneNewInsertion() throws Exception {
        int old=store.save(MessageFixture.replyMessage("old",null,null,"Cached text",true,false,100000,"phone",true,null));
        int pending=store.savePending(message("new",false));
        assertTrue(store.pendingDecryption(pending));
        assertEquals(1,store.history(peer,self,0,50).size()); assertEquals(old,store.history(peer,self,0,50).get(0).id);
        assertEquals(0,store.newerMessages(peer,old));
        store.save(MessageFixture.replyMessage("new",null,"archive-copy",null,false,false,200000,"phone",true,null));
        assertTrue(store.pendingDecryption(pending)); assertEquals(1,store.history(peer,self,0,50).size());
        try(MonogramStore reopened=new MonogramStore(0)) { assertEquals(pending,reopened.getMessage(pending,self).id); }
        HashSet<Integer> replaced=new HashSet<>();
        assertEquals(pending,store.save(message("new",true),replaced));
        assertTrue(replaced.isEmpty()); assertFalse(store.pendingDecryption(pending));
        assertEquals("Clear text",store.history(peer,self,0,50).get(0).message);
        assertEquals(0,store.savePending(message("new",false))); assertFalse(store.pendingDecryption(pending));
    }
    @Test public void definiteFailureRevealsImmediatelyAndLatePlaintextReplacesRatherThanDuplicates() throws Exception {
        int id=store.savePending(message("failed",false)); HashSet<Integer> replaced=new HashSet<>();
        assertEquals(id,store.save(message("failed",false),replaced)); assertTrue(replaced.isEmpty());
        assertFalse(store.pendingDecryption(id)); assertEquals(1,store.history(peer,self,0,50).size());
        assertEquals(0,store.save(message("failed",false)));
        assertEquals(id,store.save(message("failed",true),replaced)); assertTrue(replaced.contains(id));
        assertEquals(1,store.history(peer,self,0,50).size());
    }
    @Test public void anUndecryptedEditCannotReplaceKnownCachedText() throws Exception {
        int original=store.save(message("original",true));
        XmppMessage encryptedEdit=MessageFixture.correction("edit",null,null,null,false,false,300000,"phone",true,null,"original");
        int edit=store.savePending(encryptedEdit);
        store.reconcileCorrections(peer,new HashSet<>()); assertEquals("Clear text",store.getMessage(original,self).message);
        store.save(encryptedEdit); store.reconcileCorrections(peer,new HashSet<>());
        assertEquals("Clear text",store.getMessage(original,self).message);
        store.save(MessageFixture.correction("edit",null,null,"Edited clear text",true,false,300000,"phone",true,null,"original"));
        store.reconcileCorrections(peer,java.util.Collections.singleton(edit));
        assertEquals("Edited clear text",store.getMessage(original,self).message);
    }
    @Test public void roomCiphertextAndMessagesWithoutWireIdsUseTheSameVisibilityRules() throws Exception {
        String room="friends@conference.example.org"; long dialog=store.room(room,"Alice");
        XmppRoomMessage pending=MessageFixture.roomArchive(room,"Bob","room-wire",null,null,null,200000,true);
        int id=store.savePending(pending,self); assertTrue(store.history(dialog,self,0,50).isEmpty());
        HashSet<Integer> replaced=new HashSet<>();
        assertEquals(id,store.saveRoom(MessageFixture.roomArchive(room,"Bob","room-wire",null,null,"Room clear",200000,true),self,replaced));
        assertTrue(replaced.isEmpty()); assertEquals("Room clear",store.history(dialog,self,0,50).get(0).message);
        int idless=store.savePending(MessageFixture.idlessEncrypted(false,"bob@example.org/phone"));
        assertEquals(idless,store.save(MessageFixture.idlessEncrypted(true,"bob@example.org/phone")));
        assertNotEquals(MessageFixture.idlessEncrypted(false,"bob@example.org/phone").ids,
                MessageFixture.idlessEncrypted(false,"eve@example.org/phone").ids);
    }
    private void schedule(int id,Runnable reveal) {
        ReflectionHelpers.callInstanceMethod(controller,"decryptionTimeout",ReflectionHelpers.ClassParameter.from(long.class,0L),
                ReflectionHelpers.ClassParameter.from(String.class,"alice@example.org"),ReflectionHelpers.ClassParameter.from(int.class,id),
                ReflectionHelpers.ClassParameter.from(Runnable.class,reveal));
    }
    private void advance(long millis) throws Exception {
        Shadows.shadowOf(Utilities.stageQueue.getHandler().getLooper()).idleFor(millis,TimeUnit.MILLISECONDS);
        controller.worker.submit(() -> {}).get(5,TimeUnit.SECONDS);
    }
    @Test public void timeoutWaitsFiveSecondsAndSkipsResolvedOrLoggedOutMessages() throws Exception {
        XmppMessage opaque=message("slow",false); int id=store.savePending(opaque); AtomicInteger reveals=new AtomicInteger();
        schedule(id,() -> { reveals.incrementAndGet(); store.save(opaque); });
        advance(4999); assertEquals(0,reveals.get()); assertTrue(store.history(peer,self,0,50).isEmpty());
        advance(1); assertEquals(1,reveals.get()); assertEquals(1,store.history(peer,self,0,50).size());
        XmppMessage failed=message("early-failure",false); int failure=store.savePending(failed);
        schedule(failure,reveals::incrementAndGet); store.save(failed); advance(5000); assertEquals(1,reveals.get());
        int loggedOut=store.savePending(message("logout",false)); schedule(loggedOut,reveals::incrementAndGet);
        ReflectionHelpers.setField(controller,"accountEpoch",1L); store.clear(); advance(5000); assertEquals(1,reveals.get());
    }
}
