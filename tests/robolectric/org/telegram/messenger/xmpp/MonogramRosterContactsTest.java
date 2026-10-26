package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.MessageFixture;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramRosterContactsTest {
    Context context;MonogramStore store;long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        context.deleteDatabase("roster-contacts.db");store=new MonogramStore(context,"roster-contacts.db");
        self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
    }
    @After public void cleanup() {store.close();context.deleteDatabase("roster-contacts.db");}
    @Test public void membershipSurvivesRestartAndRemovalKeepsMessagesAndAlias() throws Exception {
        assertFalse(store.user(peer,self).contact);store.rosterContact(peer,true);store.localName(peer,"My Bob");
        int id=store.save(MessageFixture.message("kept","kept",null,"History stays",true,false,100000));
        assertTrue(store.user(peer,self).contact);assertFalse(store.user(self,self).contact);
        store.close();store=new MonogramStore(context,"roster-contacts.db");assertTrue(store.user(peer,self).contact);
        store.rosterContact(peer,false);assertFalse(store.user(peer,self).contact);
        assertEquals("My Bob",store.user(peer,self).first_name);assertEquals("History stays",store.getMessage(id,self).message);
        store.rosterContact(peer,true);assertTrue(store.user(peer,self).contact);
    }
    @Test public void completeRosterReplacesRemovedContactsWithoutDeletingKnownPeers() {
        store.rosterContact(peer,true);long other=store.peer("carol@example.org");
        store.rosterContacts(Collections.singleton("carol@example.org"));
        assertFalse(store.user(peer,self).contact);assertTrue(store.user(other,self).contact);
        assertEquals(peer,store.knownPeer("bob@example.org"));
    }
    @Test public void logoutClearsMembershipBeforePeerIdsAreReused() {
        store.rosterContact(peer,true);store.clear();long replacement=store.peer("carol@example.org");
        assertFalse(store.user(replacement,0).contact);
        try(android.database.Cursor rows=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM roster_contacts",null)) {
            assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0));
        }
    }
    @Test public void previousSchemaMigratesAndHydratesRosterWithoutDroppingHistory() throws Exception {
        int id=store.save(MessageFixture.message("kept","kept",null,"Before migration",true,false,100000));
        store.getWritableDatabase().execSQL("DROP TABLE roster_contacts");store.getWritableDatabase().setVersion(33);store.close();
        store=new MonogramStore(context,"roster-contacts.db");assertEquals(34,store.getReadableDatabase().getVersion());
        assertEquals("Before migration",store.getMessage(id,self).message);assertFalse(store.user(peer,self).contact);
        store.rosterContacts(Collections.singleton("bob@example.org"));assertTrue(store.user(peer,self).contact);
    }
}
