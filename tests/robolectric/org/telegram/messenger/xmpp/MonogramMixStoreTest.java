package org.telegram.messenger.xmpp;

import android.content.Context;
import java.util.*;
import com.negzero.monogram.xmpp.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramMixStoreTest {
    Context context;final String file="mix-store-test.db",jid="general@mix.example.org";
    @Before public void setup(){context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();context.deleteDatabase(file);}
    @After public void cleanup(){context.deleteDatabase(file);}
    @Test public void upgradeFrom34PreservesMucAndMessagesWithoutReclassifyingThem()throws Exception{
        long room,self;int message;
        try(MonogramStore store=new MonogramStore(context,file)){
            self=store.peer("alice@example.org");room=store.room("old@conference.example.org","Alice");
            message=store.saveRoom(MessageFixture.mixMessage("old@conference.example.org","Bob","bob@example.org","keep-archive","keep-origin",false),self);
            store.getWritableDatabase().execSQL("DROP TABLE mix_channels");store.getWritableDatabase().setVersion(34);
        }
        try(MonogramStore store=new MonogramStore(context,file)){
            assertEquals(35,store.getReadableDatabase().getVersion());assertFalse(store.isMix(room));assertEquals("A MIX message",store.getMessage(message,self).message);assertTrue(store.room(room).autojoin);
        }
    }
    @Test public void mixMembershipAndParticipantIdSurviveRestartAndExplicitLeave()throws Exception{
        long room;
        try(MonogramStore store=new MonogramStore(context,file)){
            room=store.room(jid,"Alice");store.mixChannel(room,"stable-self",true);long self=store.peer("alice@example.org"),bob=store.peer("bob@example.org");
            store.rosterContacts(Arrays.asList("alice@example.org","bob@example.org"));assertTrue(store.isMix(room));
            store.roomState(MessageFixture.mixState(jid,"stable-self",true),self);assertEquals("General",store.roomChat(room).title);assertEquals(2,store.roomChat(room).participants_count);assertTrue(store.roomMember(-room,bob));
            assertEquals(bob,store.knownOccupant(jid+"/bob-id"));assertNull(store.roomChat(room).admin_rights);assertFalse(store.roomChat(room).creator);
        }
        try(MonogramStore store=new MonogramStore(context,file)){
            assertTrue(store.isMix(room));assertEquals("stable-self",store.mixParticipantId(room));assertTrue(store.room(room).autojoin);
            store.mixChannel(room,"stable-self",false);assertFalse(store.room(room).autojoin);assertEquals("stable-self",store.mixParticipantId(room));
            store.roomState(MessageFixture.mixState(jid,"stable-self",false),store.peer("alice@example.org"));assertTrue(store.activeRoomUsers(room).isEmpty());
        }
    }
    @Test public void liveAndBareSenderArchiveDeduplicateByMixServerAssignedId()throws Exception{
        try(MonogramStore store=new MonogramStore(context,file)){
            long self=store.peer("alice@example.org"),room=store.room(jid,"Alice"),bob=store.peer("bob@example.org");store.mixChannel(room,"stable-self",true);
            int live=store.saveRoom(MessageFixture.mixMessage(jid,"bob-id","bob@example.org","server-1","origin-1",false),self);
            int archive=store.saveRoom(MessageFixture.mixMessage(jid,"jid-historical","bob@example.org","server-1","origin-1",true),self);
            assertEquals(live,archive);TLRPC.Message message=store.getMessage(live,self);assertEquals(room,message.dialog_id);assertEquals(bob,message.from_id.user_id);assertFalse(message.out);assertEquals("A MIX message",message.message);
        }
    }
    @Test public void clearingAnAccountCannotLeaveTransportFlagsForReusedRoomIds()throws Exception{
        try(MonogramStore store=new MonogramStore(context,file)){
            long room=store.room(jid,"Alice");store.mixChannel(room,"stable-self",true);store.clear();
            assertTrue(store.rooms().isEmpty());try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT count(*) FROM mix_channels",null)){row.moveToFirst();assertEquals(0,row.getInt(0));}
        }
    }
}
