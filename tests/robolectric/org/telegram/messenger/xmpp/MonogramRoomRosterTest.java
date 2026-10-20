package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import com.negzero.monogram.xmpp.MessageFixture;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramRoomRosterTest {
    Context context;String file="room-roster-test.db";
    @Before public void setup() { context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();context.deleteDatabase(file); }
    @After public void cleanup() { context.deleteDatabase(file); }
    private Map<String,String> members() { Map<String,String> map=new LinkedHashMap<>();map.put("alice@example.org","owner");map.put("bob@example.org","member");map.put("carol@example.org","admin");return map; }
    @Test public void offlineMembersShareDmIdsAndRolesWithoutBecomingActiveOccupants() throws Exception {
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),bob=store.peer("bob@example.org"),room=store.room("room@conference.example.org","Alice");
            store.roomUser(room,"room@conference.example.org/Alice","alice@example.org",self);
            store.getWritableDatabase().execSQL("UPDATE room_participants SET active=1 WHERE room=?",new Object[]{-room});
            store.roomRoster(room,members());
            TLRPC.TL_messages_chatFull full=store.roomFull(room,self);
            assertEquals(3,full.chats.get(0).participants_count);assertEquals(3,full.full_chat.participants.participants.size());
            assertEquals(Collections.singleton(self),store.activeRoomUsers(room));
            assertTrue(full.full_chat.participants.participants.stream().anyMatch(p -> p.user_id==bob));
            assertTrue(full.full_chat.participants.participants.stream().anyMatch(p -> p instanceof TLRPC.TL_chatParticipantCreator && p.user_id==self));
            assertTrue(full.full_chat.participants.participants.stream().anyMatch(p -> p instanceof TLRPC.TL_chatParticipantAdmin));
            assertTrue(full.users.stream().filter(u -> u.id==bob).findFirst().get().status instanceof TLRPC.TL_userStatusEmpty);
            store.roomMetadata(room,"Renamed remotely","Remote description");
            assertEquals("Renamed remotely",store.roomChat(room).title);
            assertEquals("Remote description",store.roomFull(room,self).full_chat.about);
            assertEquals(Collections.singleton(self),store.activeRoomUsers(room));
            assertEquals(3,store.roomChat(room).participants_count);
            assertTrue(store.roomFull(room,self).full_chat.participants.participants.stream().anyMatch(p -> p instanceof TLRPC.TL_chatParticipantAdmin));
            store.roomMetadata(room,null,null);assertEquals("Renamed remotely",store.roomChat(room).title);
            Map<String,String> invalid=new LinkedHashMap<>();invalid.put("bob@example.org","outcast");
            try { store.roomRoster(room,invalid);fail("An invalid snapshot must not erase the known roster"); } catch(IllegalArgumentException expected) {}
            assertEquals(3,store.roomFull(room,self).full_chat.participants.participants.size());
            store.roomRoster(room,Collections.singletonMap("alice@example.org","owner"));
            assertEquals(1,store.roomChat(room).participants_count);assertEquals(bob,store.knownPeer("bob@example.org"));
        }
    }
    @Test public void schemaEighteenUpgradePreservesMessagesRoomDescriptionAndPersistentMembership() throws Exception {
        int message;long self,room;
        try(MonogramStore store=new MonogramStore(context,file)) {
            self=store.peer("alice@example.org");room=store.room("room@conference.example.org","Alice");
            message=store.save(MessageFixture.replyMessage("old-message",null,null,"Keep this",true,false,100000,"phone",false,null));
            store.getWritableDatabase().execSQL("UPDATE rooms SET description='Keep description' WHERE id=?",new Object[]{-room});
            store.getWritableDatabase().execSQL("DROP TABLE room_affiliations");store.getWritableDatabase().execSQL("DROP TABLE IF EXISTS call_events");store.getWritableDatabase().setVersion(18);
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals(28,store.getReadableDatabase().getVersion());assertEquals("Keep this",store.getMessage(message,self).message);
            assertEquals("Keep description",store.roomFull(room,self).full_chat.about);store.roomRoster(room,members());
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals(3,store.roomChat(room).participants_count);assertEquals(3,store.roomFull(room,self).full_chat.participants.participants.size());
            assertTrue(store.activeRoomUsers(room).isEmpty());
        }
    }
}
