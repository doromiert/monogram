package org.telegram.messenger.xmpp;

import android.content.Context;
import android.database.DatabaseUtils;
import com.negzero.monogram.xmpp.XmppRooms;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramRoomPresenceCostTest {
    Context context;final String file="presence-cost.db",address="busy@conference.example.org";
    @Before public void setup() {context=RuntimeEnvironment.getApplication();org.telegram.messenger.ApplicationLoader.applicationContext=context;context.deleteDatabase(file);}
    @After public void cleanup() {context.deleteDatabase(file);}
    // State normally snapshots a live Smack MUC. Populate its immutable transport fields
    // directly here so the database workload is reproducible without a network or real account.
    static <T> T blank(Class<T> type) throws Exception {
        Class<?> c=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field f=c.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(c.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
    XmppRooms.Occupant occupant(String nick,String jid) throws Exception {
        XmppRooms.Occupant o=blank(XmppRooms.Occupant.class);
        ReflectionHelpers.setField(o,"address",address+"/"+nick);ReflectionHelpers.setField(o,"nickname",nick);ReflectionHelpers.setField(o,"realJid",jid);
        ReflectionHelpers.setField(o,"role","participant");ReflectionHelpers.setField(o,"affiliation","member");return o;
    }
    XmppRooms.State state(List<XmppRooms.Occupant> occupants,boolean joined,String role) throws Exception {
        XmppRooms.State s=blank(XmppRooms.State.class);ReflectionHelpers.setField(s,"room",JidCreate.entityBareFrom(address));
        ReflectionHelpers.setField(s,"nickname","Alice");ReflectionHelpers.setField(s,"subject","Topic");ReflectionHelpers.setField(s,"role",role);
        ReflectionHelpers.setField(s,"affiliation","member");ReflectionHelpers.setField(s,"joined",joined);ReflectionHelpers.setField(s,"occupants",Collections.unmodifiableList(new ArrayList<>(occupants)));return s;
    }
    @Test public void repeatedUnchangedPresenceDoesNotRewriteTheWholeRoster() throws Exception {
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),room=store.room(address,"Alice");List<XmppRooms.Occupant> people=new ArrayList<>();
            for(int i=0;i<200;i++)people.add(occupant("Member"+i,null));
            XmppRooms.State snapshot=state(people,true,"participant");store.roomState(snapshot,self);
            for(int trial=0;trial<3;trial++) {
                store.getWritableDatabase().beginTransaction();
                try {
                    long before=DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT total_changes()",null),start=System.nanoTime();
                    for(int i=0;i<20;i++)store.roomState(state(people,true,"participant"),self);
                    long writes=DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT total_changes()",null)-before;
                    System.out.println("PRESENCE_COST trial="+trial+" occupants=200 updates=20 writes="+writes+" elapsed_ms="+(System.nanoTime()-start)/1000000);
                    assertEquals("Unchanged presence must not write any database rows",0,writes);
                    assertEquals(200,store.activeRoomUsers(room).size());
                } finally {store.getWritableDatabase().endTransaction();}
            }
        }
    }
    @Test public void departuresRejoinsIdentityChangesAndPermissionsStillUpdate() throws Exception {
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),room=store.room(address,"Alice");
            XmppRooms.Occupant bob=occupant("Bob",null),carol=occupant("Carol","carol@example.org");
            store.roomState(state(Arrays.asList(bob,carol),true,"participant"),self);
            long anonymous=store.knownOccupant(bob.address);assertTrue(anonymous>0);assertEquals(2,store.activeRoomUsers(room).size());
            store.roomState(state(Collections.singletonList(carol),true,"participant"),self);assertEquals(0,store.knownOccupant(bob.address));assertEquals(1,store.activeRoomUsers(room).size());
            store.roomState(state(Arrays.asList(bob,carol),true,"participant"),self);assertEquals(anonymous,store.knownOccupant(bob.address));
            XmppRooms.Occupant knownBob=occupant("Bob","bob@example.org");store.roomState(state(Arrays.asList(knownBob,carol),true,"moderator"),self);
            assertEquals(store.knownPeer("bob@example.org"),store.knownOccupant(bob.address));
            try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT role FROM room_permissions WHERE room=?",new String[]{Long.toString(-room)})) {assertTrue(row.moveToFirst());assertEquals("moderator",row.getString(0));}
            assertFalse(store.roomState(state(Arrays.asList(knownBob,carol),true,"moderator"),self));
            store.roomState(state(Collections.emptyList(),false,""),self);assertTrue(store.activeRoomUsers(room).isEmpty());
            store.roomState(state(Collections.singletonList(knownBob),true,"participant"),self);assertEquals(1,store.activeRoomUsers(room).size());
            assertEquals("Topic",store.room(room).subject);
            try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT role FROM room_permissions WHERE room=?",new String[]{Long.toString(-room)})) {assertTrue(row.moveToFirst());assertEquals("participant",row.getString(0));}
        }
    }
}
