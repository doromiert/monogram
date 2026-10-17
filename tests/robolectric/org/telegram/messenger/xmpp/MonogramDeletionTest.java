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
public class MonogramDeletionTest {
    @Test public void directDeletionSurvivesReplayAndReopening() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="deleted-direct.db";
        context.deleteDatabase(file); long self,peer; int id;
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer("alice@example.org"); peer=store.peer("bob@example.org");
                id=store.save(MessageFixture.replyMessage("wire","origin",null,"Delete me",true,false,100000,"phone",false,null));
                store.deleteMessages(Collections.singleton(id));
                assertEquals(0,store.save(MessageFixture.replyMessage("wire","origin","archive","Delete me",true,false,100000,"phone",false,null)));
                assertTrue(store.history(peer,self,0,50).isEmpty());
                assertEquals("",store.getMessage(id,self).message); assertTrue(store.hiddenCorrection(id));
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertTrue(store.history(peer,self,0,50).isEmpty());
                assertEquals(0,store.save(MessageFixture.replyMessage("wire","origin","archive",null,false,false,100000,"phone",false,null)));
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void roomDeletionSurvivesLateDecryptionAndMigrationKeepsOtherMessages() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="deleted-room.db";
        context.deleteDatabase(file); long self,dialog; int kept;
        String room="room@conference.example.org";
        try {
            try(MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer("alice@example.org"); dialog=store.room(room,"Alice");
                int id=store.saveRoom(MessageFixture.roomText(room,"Bob","wire","archive",null,"bob@example.org","bob",100000,false,null,null),self);
                kept=store.saveRoom(MessageFixture.roomText(room,"Bob","keep","archive-keep","Keep me","bob@example.org","bob",101000,false,null,null),self);
                store.deleteMessages(Collections.singleton(id));
                assertEquals(0,store.saveRoom(MessageFixture.roomText(room,"Bob","wire","archive","Late plaintext","bob@example.org","bob",100000,false,null,null),self));
                assertEquals(1,store.history(dialog,self,0,50).size());
                // Recreate the actual pre-deletion schema, then exercise the migration on reopening.
                store.getWritableDatabase().execSQL("ALTER TABLE messages DROP COLUMN deleted"); store.getWritableDatabase().setVersion(14);
            }
            try(MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(15,store.getReadableDatabase().getVersion());
                assertEquals("Keep me",store.getMessage(kept,self).message);
            }
        } finally { context.deleteDatabase(file); }
    }
}
