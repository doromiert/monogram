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
    @Test public void pendingDirectRetractionIsAuthorScopedAndSurvivesRestart() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-direct.db"; context.deleteDatabase(file);
        long self,peer; int original;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer("alice@example.org"); peer=store.peer("bob@example.org");
                assertEquals(0,store.save(MessageFixture.retraction("wire",false,true,"other")));
                original=store.save(MessageFixture.messageFrom("wire","origin","archive","Keep me",true,false,100000,"phone"));
                assertTrue(store.reconcileRetractions(peer).isEmpty()); assertEquals("Keep me",store.getMessage(original,self).message);
                assertEquals(0,store.save(MessageFixture.retraction("wire",false,false,"other")));
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(Collections.singleton(original),store.reconcileRetractions(peer));
                assertTrue(store.history(peer,self,0,50).isEmpty()); assertEquals("",store.getMessage(original,self).message);
                assertEquals(0,store.save(MessageFixture.messageFrom("wire","origin","archive","Replay",true,false,100000,"phone")));
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void ambiguousResourceIdsAndWrongReferenceKindsCannotDeleteMessages() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="retract-ambiguous.db"; context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            int one=store.save(MessageFixture.messageFrom("same","origin-one","archive-one","One",true,false,100000,"phone"));
            int two=store.save(MessageFixture.messageFrom("same","origin-two","archive-two","Two",true,false,101000,"desktop"));
            store.save(MessageFixture.retraction("same",false,false,"third"));
            store.save(MessageFixture.retraction("origin-one",false,false,"third"));
            assertTrue(store.reconcileRetractions(peer).isEmpty()); assertEquals(2,store.history(peer,self,0,50).size());
            store.save(MessageFixture.retraction("origin-one",true,false,"third"));
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
}
