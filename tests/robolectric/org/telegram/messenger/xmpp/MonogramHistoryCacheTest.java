package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.MessageFixture;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramHistoryCacheTest {
    @Test public void clearingHistoryKeepsContactsRoomsAndNewArrivalsWithoutReusingIds() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String file="history-cache-test.db";context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org"),room=store.room("room@conference.example.org","Alice");
            int old=store.save(MessageFixture.replyMessage("old","old",null,"Cached plaintext",true,false,100000,"phone",false,null));
            store.getWritableDatabase().execSQL("INSERT OR REPLACE INTO local_contact_names(peer,name) VALUES(?,?)",new Object[]{peer,"Local name"});
            int through=store.historyWatermark();assertTrue(through>=old);
            int fresh=store.save(MessageFixture.replyMessage("new","new",null,"Arrived during clearing",true,false,200000,"phone",false,null));
            assertTrue(fresh>through);store.clearHistory(through);
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,body FROM messages ORDER BY id",null)) {
                assertTrue(c.moveToFirst());assertEquals(fresh,c.getInt(0));assertEquals("Arrived during clearing",c.getString(1));assertFalse(c.moveToNext());
            }
            assertEquals("alice@example.org",store.jid(self));assertEquals("bob@example.org",store.jid(peer));assertEquals("room@conference.example.org",store.jid(room));
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT name FROM local_contact_names WHERE peer=?",new String[]{Long.toString(peer)})) {assertTrue(c.moveToFirst());assertEquals("Local name",c.getString(0));}
            store.clearHistory(fresh);
            int later=store.save(MessageFixture.replyMessage("later","later",null,"Later",true,false,300000,"phone",false,null));
            assertTrue(later>fresh);
        } finally {context.deleteDatabase(file);}
    }
}
