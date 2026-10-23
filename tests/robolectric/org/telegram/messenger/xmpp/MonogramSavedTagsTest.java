package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.XmppMessage;
import java.util.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramSavedTagsTest {
    private TLRPC.Reaction emoji(String value) { TLRPC.TL_reactionEmoji r=new TLRPC.TL_reactionEmoji();r.emoticon=value;return r; }
    private int note(MonogramStore store,String to,String body) throws Exception {
        return store.save(XmppMessage.sent(JidCreate.entityFullFrom("alice@example.org/phone"),to,UUID.randomUUID().toString(),body,false));
    }
    private Context context(String database) {
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance=new org.telegram.messenger.ApplicationLoader();
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase(database);return context;
    }
    @Test public void editingEmojiMovesExistingMembershipsAndRetainsStyleAcrossReopen() throws Exception {
        String db="saved-tag-edit.db";Context context=context(db);long self;int first,second;
        TLRPC.Reaction old=emoji("⭐"),replacement=emoji("📚");
        try {
            try(MonogramStore store=new MonogramStore(context,db)) {
                self=store.peer("alice@example.org");first=note(store,"alice@example.org","one");second=note(store,"alice@example.org","two");
                store.savedTags.set(first,self,Collections.singletonList(old));store.savedTags.set(second,self,Collections.singletonList(old));
                assertEquals(2,store.savedTags.edit(self,old,replacement," Reading ",0xff42a5f5).size());
                assertTrue(store.getMessage(first,self).reactions.reactions_as_tags);
                assertEquals("📚",((TLRPC.TL_reactionEmoji)store.getMessage(first,self).reactions.results.get(0).reaction).emoticon);
                assertEquals(2,store.savedTags.list(self).tags.get(0).count);
                store.savedTags.rename(replacement,"Books");assertEquals(Integer.valueOf(0xff42a5f5),store.savedTags.colors().get("e:📚"));
            }
            try(MonogramStore store=new MonogramStore(context,db)) {
                assertEquals("Books",store.savedTags.list(self).tags.get(0).title);
                assertEquals(Integer.valueOf(0xff42a5f5),store.savedTags.colors().get("e:📚"));
                assertEquals(2,store.search(self,self,"",0,0,100,0,0,0,null,false,false,Collections.singletonList(replacement)).messages.size());
                assertTrue(store.search(self,self,"",0,0,100,0,0,0,null,false,false,Collections.singletonList(old)).messages.isEmpty());
                store.deleteMessages(Collections.singleton(first));assertEquals(1,store.savedTags.list(self).tags.get(0).count);
            }
        } finally {context.deleteDatabase(db);}
    }
    @Test public void collisionsAndInvalidInputCannotLoseLabelsOrEscapeTheSelfChat() throws Exception {
        String db="saved-tag-errors.db";Context context=context(db);
        try(MonogramStore store=new MonogramStore(context,db)) {
            long self=store.peer("alice@example.org");int first=note(store,"alice@example.org","one"),second=note(store,"alice@example.org","two");
            TLRPC.Reaction a=emoji("⭐"),b=emoji("📚");
            store.savedTags.set(first,self,Collections.singletonList(a));store.savedTags.set(second,self,Collections.singletonList(b));
            assertThrows(IllegalArgumentException.class,()->store.savedTags.edit(self,a,b,"Don't merge",0));
            assertThrows(IllegalArgumentException.class,()->store.savedTags.edit(self,a,a,"Bad alpha",0x11445566));
            assertThrows(IllegalArgumentException.class,()->store.savedTags.set(first,self,Collections.singletonList(emoji("arbitrary text"))));
            int dm=note(store,"bob@example.org","private dm");
            assertThrows(IllegalArgumentException.class,()->store.savedTags.set(dm,self,Collections.singletonList(a)));
            assertEquals(2,store.savedTags.list(self).tags.size());assertEquals(1,store.getMessage(first,self).reactions.results.size());
        } finally {context.deleteDatabase(db);}
    }
    @Test public void removingOneMembershipPreservesOtherBookmarksAndClearingNamePreservesColor() throws Exception {
        String db="saved-tag-remove.db";Context context=context(db);
        try(MonogramStore store=new MonogramStore(context,db)) {
            long self=store.peer("alice@example.org");int first=note(store,"alice@example.org","one"),second=note(store,"alice@example.org","two");
            TLRPC.Reaction tag=emoji("⭐");store.savedTags.set(first,self,Collections.singletonList(tag));store.savedTags.set(second,self,Collections.singletonList(tag));
            store.savedTags.edit(self,tag,tag,"Starred",0xff66bb6a);store.savedTags.rename(tag,"");
            assertEquals(Integer.valueOf(0xff66bb6a),store.savedTags.colors().get("e:⭐"));
            store.savedTags.set(first,self,Collections.emptyList());assertTrue(store.getMessage(first,self).reactions.results.isEmpty());
            assertEquals(1,store.getMessage(second,self).reactions.results.size());assertEquals(1,store.savedTags.list(self).tags.get(0).count);
        } finally {context.deleteDatabase(db);}
    }
    @Test public void version30MigrationPreservesExistingAssignmentsAndNames() throws Exception {
        String db="saved-tag-migration.db";Context context=context(db);long self;int message;
        try {
            try(MonogramStore store=new MonogramStore(context,db)) {
                self=store.peer("alice@example.org");message=note(store,"alice@example.org","old note");
                store.savedTags.set(message,self,Collections.singletonList(emoji("⭐")));store.savedTags.rename(emoji("⭐"),"Old label");
                android.database.sqlite.SQLiteDatabase sql=store.getWritableDatabase();
                sql.execSQL("ALTER TABLE saved_tag_names RENAME TO old_tag_names");
                sql.execSQL("CREATE TABLE saved_tag_names(tag TEXT PRIMARY KEY,title TEXT NOT NULL)");
                sql.execSQL("INSERT INTO saved_tag_names(tag,title) SELECT tag,title FROM old_tag_names");
                sql.execSQL("DROP TABLE old_tag_names");sql.setVersion(30);
            }
            try(MonogramStore upgraded=new MonogramStore(context,db)) {
                assertEquals(33,upgraded.getReadableDatabase().getVersion());assertEquals("Old label",upgraded.savedTags.list(self).tags.get(0).title);
                assertEquals(1,upgraded.getMessage(message,self).reactions.results.size());assertEquals(Integer.valueOf(0),upgraded.savedTags.colors().get("e:⭐"));
            }
        } finally {context.deleteDatabase(db);}
    }
}
