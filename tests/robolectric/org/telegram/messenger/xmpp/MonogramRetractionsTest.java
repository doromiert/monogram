package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.MessageFixture;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramRetractionsTest {
    private static final String KEY="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private com.negzero.monogram.xmpp.XmppMessage retract(String reference,boolean legacy,boolean outgoing,String resource) throws Exception {
        return MessageFixture.encryptedRetraction(reference,legacy,outgoing,resource,KEY);
    }
    @Test public void pendingDirectRetractionIsAuthorScopedAndSurvivesRestart() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-direct.db"; context.deleteDatabase(file);
        long self,peer; int original;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer("alice@example.org"); peer=store.peer("bob@example.org");
                assertEquals(0,store.save(retract("wire",false,true,"other")));
                original=store.save(MessageFixture.messageFrom("wire","origin","archive","Keep me",true,false,100000,"phone").withSenderFingerprint(KEY));
                assertTrue(store.reconcileRetractions(peer).isEmpty()); assertEquals("Keep me",store.getMessage(original,self).message);
                assertEquals(0,store.save(retract("wire",false,false,"other")));
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(Collections.singleton(original),store.reconcileRetractions(peer));
                assertTrue(store.history(peer,self,0,50).isEmpty()); assertEquals("",store.getMessage(original,self).message);
                assertEquals(0,store.save(MessageFixture.messageFrom("wire","origin","archive","Replay",true,false,100000,"phone").withSenderFingerprint(KEY)));
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void ambiguousResourceIdsAndWrongReferenceKindsCannotDeleteMessages() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-ambiguous.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            int one=store.save(MessageFixture.messageFrom("same","origin-one","archive-one","One",true,false,100000,"phone").withSenderFingerprint(KEY));
            int two=store.save(MessageFixture.messageFrom("same","origin-two","archive-two","Two",true,false,101000,"desktop").withSenderFingerprint(KEY));
            store.save(retract("same",false,false,"third"));
            store.save(retract("origin-one",false,false,"third"));
            assertTrue(store.reconcileRetractions(peer).isEmpty()); assertEquals(2,store.history(peer,self,0,50).size());
            store.save(retract("origin-one",true,false,"third"));
            assertEquals(Collections.singleton(one),store.reconcileRetractions(peer)); assertEquals("Two",store.getMessage(two,self).message);
        } finally { context.deleteDatabase(file); }
    }
    @Test public void roomRetractionsRequireVerifiedOccupantContinuityAndRoomAssignedIds() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-room.db"; context.deleteDatabase(file);
        String room="room@conference.example.org";
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.room(room,"Alice");
            int original=store.saveRoom(MessageFixture.roomText(room,"Bob","wire","server-id","Keep me","bob@example.org","bob-id",100000,false,null,null),self);
            store.saveRoom(MessageFixture.roomRetraction(room,"Bob","eve@example.org","eve-id","server-id",false),self);
            store.saveRoom(MessageFixture.roomRetraction(room,"Other","bob@example.org",null,"server-id",false),self);
            store.saveRoom(MessageFixture.roomRetraction(room,"Bob","bob@example.org","bob-id","wire",false),self);
            assertTrue(store.reconcileRetractions(peer).isEmpty()); assertEquals("Keep me",store.getMessage(original,self).message);
            store.saveRoom(MessageFixture.roomRetraction(room,"RenamedBob","bob@example.org","bob-id","server-id",false),self);
            assertEquals(Collections.singleton(original),store.reconcileRetractions(peer)); assertTrue(store.history(peer,self,0,50).isEmpty());
        } finally { context.deleteDatabase(file); }
    }
    @Test public void plaintextRoomRetractionFromTheSameOccupantRemovesAnEncryptedOriginal() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();String file="retract-room-interop.db";context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            String room="room@conference.example.org";long self=store.peer("alice@example.org"),peer=store.room(room,"Alice");
            int id=store.saveRoom(MessageFixture.roomText(room,"Alice","wire","server-id","Secret","alice@example.org","own-stable",100000,true,null,null).withSenderFingerprint(KEY),self);
            store.saveRoom(MessageFixture.roomRetraction(room,"Mallory","mallory@example.org","wrong-stable","server-id",false),self);
            assertTrue(store.reconcileRetractions(peer).isEmpty());
            store.saveRoom(MessageFixture.roomRetraction(room,"Alice","alice@example.org","own-stable","server-id",false),self);
            assertEquals(Collections.singleton(id),store.reconcileRetractions(peer));
            assertTrue(store.history(peer,self,0,50).isEmpty());
        } finally {context.deleteDatabase(file);}
    }
    @Test public void encryptedOriginalRejectsPlaintextAndOtherDevicesButAcceptsItsAuthenticatedKey() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-fingerprint.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            int id=store.save(MessageFixture.messageFrom("wire","origin","archive","Secret",true,false,100000,"phone").withSenderFingerprint(KEY));
            store.save(MessageFixture.retraction("wire",false,false,"phone"));
            store.save(MessageFixture.encryptedRetraction("wire",false,false,"phone",OTHER));
            assertTrue(store.reconcileRetractions(peer).isEmpty()); assertEquals("Secret",store.getMessage(id,self).message);
            store.save(retract("wire",false,false,"phone"));
            assertEquals(Collections.singleton(id),store.reconcileRetractions(peer));
        } finally { context.deleteDatabase(file); }
    }
    @Test public void oldEncryptedOriginalWaitsForAuthenticatedFingerprintBackfill() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-old-key.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            com.negzero.monogram.xmpp.XmppMessage original=MessageFixture.messageFrom("wire","origin","archive","Secret",true,false,100000,"phone");
            int id=store.save(original); store.save(retract("wire",false,false,"phone"));
            assertTrue(store.reconcileRetractions(peer).isEmpty());
            store.save(original.withSenderFingerprint(KEY));
            assertEquals(Collections.singleton(id),store.reconcileRetractions(peer));
        } finally { context.deleteDatabase(file); }
    }
    @Test public void encryptedRoomControlStillNeedsOriginalFingerprintAsWellAsOccupantIdentity() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-room-key.db"; context.deleteDatabase(file); String room="room@conference.example.org";
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.room(room,"Alice");
            int id=store.saveRoom(MessageFixture.roomText(room,"Bob","wire","sid","Secret","bob@example.org","bob-id",100000,true,null,null).withSenderFingerprint(KEY),self);
            store.saveRoom(MessageFixture.roomRetraction(room,"Bob","bob@example.org","bob-id","sid",false,OTHER),self);
            assertTrue(store.reconcileRetractions(peer).isEmpty());
            store.saveRoom(MessageFixture.roomRetraction(room,"Bob","bob@example.org","bob-id","sid",false,KEY),self);
            assertEquals(Collections.singleton(id),store.reconcileRetractions(peer));
        } finally { context.deleteDatabase(file); }
    }
    @Test public void modernControlPlaceholderIsHiddenAndProtocolDowngradeIsRejected() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-modern.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            int original=store.save(MessageFixture.modern("original","archive","Secret",false,100000,null,null,null).withSenderFingerprint(KEY));
            store.save(retract("original",false,false,"phone"));
            assertTrue(store.reconcileRetractions(peer).isEmpty());
            int placeholder=store.save(MessageFixture.modern("event",null,null,false,200000,null,null,null));
            store.save(MessageFixture.modernRetraction("event","original",KEY));
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(original,placeholder)),store.reconcileRetractions(peer));
            assertTrue(store.history(peer,self,0,50).isEmpty());
        } finally { context.deleteDatabase(file); }
    }

}
