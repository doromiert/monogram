package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.MessageFixture;
import com.negzero.monogram.xmpp.XmppRoomMessage;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramLegacyRoomAliasTest {
    private static final String FILE="legacy-room-alias.db",ROOM="friends@conference.example.org";
    private Context context;
    private MonogramStore store;
    private long self,room;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(FILE);store=new MonogramStore(context,FILE);
        self=store.peer("alice@example.org");room=store.room(ROOM,"Alice");
    }
    @After public void cleanup() { store.close();context.deleteDatabase(FILE); }
    private XmppRoomMessage message(String nick,String cipher,String archive,String mam,String body) throws Exception {
        XmppRoomMessage message=MessageFixture.roomArchive(ROOM,nick,null,null,null,body,100000,true);
        Map<String,String> aliases=new LinkedHashMap<>();
        if(cipher!=null) aliases.put("legacy-cipher",cipher);
        if(archive!=null) aliases.put("archive",archive);
        if(mam!=null) aliases.put("mam",mam);
        return MessageFixture.withRoomAliases(message,aliases);
    }
    private int rowCount() {
        try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM messages WHERE peer=?",new String[]{Long.toString(room)})) {
            assertTrue(rows.moveToFirst());return rows.getInt(0);
        }
    }
    @Test public void idlessCipherReplayAfterNicknameChangeKeepsOneReadableCachedRowDespiteNewArchiveAlias() throws Exception {
        int id=store.saveRoom(message("OldBob","cipher-A","old-archive",null,"Keep authenticated plaintext"),self);
        assertTrue(id>0);assertEquals(1,rowCount());
        store.saveRoom(message("RenamedBob","cipher-A","new-archive","new-mam",null),self);
        assertEquals(1,rowCount());assertEquals(1,store.history(room,self,0,50).size());
        assertEquals(id,store.history(room,self,0,50).get(0).id);
        assertEquals("Keep authenticated plaintext",store.getMessage(id,self).message);
        try(Cursor aliases=store.getReadableDatabase().rawQuery("SELECT value,message FROM message_ids WHERE peer=? AND kind='legacy-cipher'",new String[]{Long.toString(room)})) {
            assertEquals(1,aliases.getCount());assertTrue(aliases.moveToFirst());assertEquals("cipher-A",aliases.getString(0));assertEquals(id,aliases.getInt(1));
        }
        store.close();store=new MonogramStore(context,FILE);
        store.saveRoom(message("ThirdBob","cipher-A",null,"third-mam","Keep authenticated plaintext"),self);
        assertEquals(1,rowCount());assertEquals(id,store.history(room,self,0,50).get(0).id);
    }
    @Test public void deletedPrivateCipherAliasSurvivesChangedNickAndAddsNewArchiveTombstones() throws Exception {
        int id=store.saveRoom(message("OldBob","cipher-deleted",null,null,"Delete this"),self);
        store.deleteMessages(Collections.singleton(id));
        assertEquals(0,store.saveRoom(message("RenamedBob","cipher-deleted","new-archive","new-mam","Replayed body"),self));
        assertEquals(1,rowCount());assertTrue(store.history(room,self,0,50).isEmpty());assertTrue(store.hiddenMessage(id));
        assertEquals("",store.getMessage(id,self).message);
        store.close();store=new MonogramStore(context,FILE);
        // The newly observed MAM alias must itself remain a tombstone after the private alias is absent.
        assertEquals(0,store.saveRoom(message("ThirdBob",null,null,"new-mam","Late replay"),self));
        assertEquals(1,rowCount());assertTrue(store.history(room,self,0,50).isEmpty());assertTrue(store.hiddenMessage(id));
    }
    @Test public void distinctPrivateCipherHashesNeverCollapseEvenForTheSameNicknameAndTimestamp() throws Exception {
        int first=store.saveRoom(message("Bob","cipher-first",null,null,"First"),self);
        int second=store.saveRoom(message("Bob","cipher-second",null,null,"Second"),self);
        assertTrue(first>0);assertTrue(second>0);assertNotEquals(first,second);assertEquals(2,rowCount());
        assertEquals("First",store.getMessage(first,self).message);assertEquals("Second",store.getMessage(second,self).message);
        store.saveRoom(message("RenamedBob","cipher-second",null,"second-mam",null),self);
        assertEquals(2,rowCount());assertEquals("Second",store.getMessage(second,self).message);
    }
}
