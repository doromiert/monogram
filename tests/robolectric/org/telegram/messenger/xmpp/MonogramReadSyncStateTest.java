package org.telegram.messenger.xmpp;

import android.content.*;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramReadSyncStateTest {
    final String own="alice@example.org",contact="bob@example.org";
    Context context;SharedPreferences preferences;MonogramStore store;MonogramReadSyncState sync;long peer,self;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("private-read.db");preferences=context.getSharedPreferences("private-read-test",Context.MODE_PRIVATE);preferences.edit().clear().commit();
        store=new MonogramStore(context,"private-read.db");self=store.peer(own);peer=store.peer(contact);sync=new MonogramReadSyncState(store,preferences);
    }
    @After public void close() { store.close();context.deleteDatabase("private-read.db");preferences.edit().clear().commit(); }
    int save(String wire,long time,boolean outgoing) throws Exception { return store.save(MessageFixture.serverAssigned("wire-"+wire,wire,"mam-"+wire,time,outgoing)); }
    MonogramReadSyncState.Candidate read(int id) throws Exception {
        MonogramReads.ReadResult result=store.reads.throughWithChanges(peer,Collections.singleton(id),false);
        return sync.candidate(own,peer,result.newlyRead);
    }
    @Test public void privateQueueSurvivesRestartWithoutPublicConsentAndDoesNotLoseNewerWorkOnOldAcknowledgement() throws Exception {
        int newer=save("newer",300000,false),older=save("older",100000,false);
        MonogramReadSyncState.Candidate first=read(older),latest=read(newer);
        assertNotNull(first);assertTrue(sync.queue(own,first));assertTrue(sync.queue(own,latest));assertFalse(sync.queue(own,first));
        assertTrue(store.reads.pendingDisplayed().isEmpty()); // Neither markable requests nor public consent are required.
        sync.acknowledge(own,first);assertEquals(newer,sync.pending(own).get(0).id);
        store.close();store=new MonogramStore(context,"private-read.db");sync=new MonogramReadSyncState(store,preferences);
        assertEquals(newer,sync.pending(own).get(0).id);assertTrue(sync.pending("other@example.org").isEmpty());
        sync.acknowledge(own,latest);assertTrue(sync.pending(own).isEmpty());assertFalse(sync.queue(own,first));
        assertNull(sync.candidate(own,peer,Collections.singleton(older)));
    }
    @Test public void onlyKnownCorrectlyScopedServerReferencesCanAdvanceAndRemoteOrOutgoingEventsDoNotQueueReceipts() throws Exception {
        assertNull(sync.observe(own,XmppReadSync.Entry.create(contact,own,"not-yet-loaded")));
        assertEquals(0,store.knownPeer("unknown@example.org"));
        int incoming=save("incoming",100000,false),outgoing=save("outgoing",200000,true);
        assertNull(sync.observe(own,XmppReadSync.Entry.create(contact,"mallory@example.org","incoming")));
        assertNull(sync.observe(own,XmppReadSync.Entry.create("unknown@example.org",own,"incoming")));
        assertNull(sync.observe(own,XmppReadSync.Entry.create(contact+"/occupant",own,"incoming")));
        assertEquals(outgoing,sync.observe(own,XmppReadSync.Entry.create(contact,own,"outgoing")).id);
        store.reads.throughWithChanges(peer,Collections.singleton(outgoing),false);
        assertTrue(store.reads.isRead(incoming));assertTrue(sync.pending(own).isEmpty());assertTrue(store.reads.pendingDisplayed().isEmpty());
        assertNull(sync.observe(own,XmppReadSync.Entry.create(contact,own,"incoming"))); // Never regress to an earlier device snapshot.
        assertEquals(outgoing,sync.observe(own,XmppReadSync.Entry.create(contact,own,"outgoing")).id); // Idempotent retry after a native persistence failure.
    }
    @Test public void disabledStateClearsOnlyPrivatePendingReadsAndCannotInventHistoricalOrOutgoingReads() throws Exception {
        int original=save("original",100000,false);MonogramReadSyncState.Candidate candidate=read(original);assertTrue(sync.queue(own,candidate));
        assertTrue(sync.setEnabled(own,false));assertFalse(preferences.getBoolean("private-read-enabled-"+own,true));assertTrue(sync.pending(own).isEmpty());
        assertTrue(sync.setEnabled(own,true));assertNull(read(original));
        int sent=save("own-sent",300000,true);assertNull(sync.candidate(own,peer,Collections.singleton(sent)));
        int withoutSid=store.save(MessageFixture.serverAssigned("no-sid",null,"mam-only",400000,false));assertNull(read(withoutSid));
        assertTrue(sync.pending(own).isEmpty());
    }
    @Test public void deletedOrAmbiguousReferencesArePrunedAndRoomIdsNeverBecomeDmAnchors() throws Exception {
        int original=save("doomed",100000,false);MonogramReadSyncState.Candidate candidate=read(original);assertTrue(sync.queue(own,candidate));
        store.deleteMessages(Collections.singleton(original));assertTrue(sync.pending(own).isEmpty());assertNull(sync.observe(own,candidate.entry));
        String address="room@conference.example.org";long room=store.room(address,"Alice");
        int id=store.saveRoom(MessageFixture.roomMarkable(address,"Bob","room-id",200000,false),self);
        MonogramReads.ReadResult read=store.reads.throughWithChanges(room,Collections.singleton(id),false);
        MonogramReadSyncState.Candidate group=sync.candidate(own,room,read.newlyRead);assertEquals(address,group.entry.by);
        assertNull(sync.observe(own,XmppReadSync.Entry.create(contact,own,"room-id")));
        assertNull(sync.observe(own,XmppReadSync.Entry.create(address,own,"room-id")));
        assertEquals(id,sync.observe(own,group.entry).id);
        int first=save("reused",300000,false),second=save("other",400000,true);
        store.getWritableDatabase().execSQL("INSERT INTO message_ids(peer,outgoing,kind,value,message) VALUES(?,1,?,'reused',?)",new Object[]{peer,XmppReadSync.LOCAL_ID,second});
        assertNull(sync.observe(own,XmppReadSync.Entry.create(contact,own,"reused")));
        assertNull(sync.candidate(own,peer,Collections.singleton(first)));
    }
}
