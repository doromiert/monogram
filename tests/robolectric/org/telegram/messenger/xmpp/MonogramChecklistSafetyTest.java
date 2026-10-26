package org.telegram.messenger.xmpp;

import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.ChecklistContent;
import com.negzero.monogram.xmpp.MessageFixture;
import java.util.Arrays;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

/** Tests the currently unexposed checklist draft; these do not certify native integration. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramChecklistSafetyTest {
    private static final String FILE="checklist-safety.db", OWNER="bob@example.org";
    private Context context;
    private MonogramStore store;
    private MonogramChecklistStore checklists;
    private long peer;

    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        context.deleteDatabase(FILE);open();
    }
    private void open() {
        store=new MonogramStore(context,FILE);peer=store.peer(OWNER);
        MonogramChecklistStore.create(store.getWritableDatabase());checklists=new MonogramChecklistStore(store);
    }
    @After public void cleanup() { if(store!=null)store.close();context.deleteDatabase(FILE); }
    private MonogramChecklists.Checklist document() {
        return new MonogramChecklists.Checklist(OWNER,1,true,true,"Groceries",
                Collections.singletonList(new MonogramChecklists.Item(1,"Milk",false,null,0)));
    }
    private int message(String id,boolean outgoing,String body) throws Exception {
        return store.save(MessageFixture.message(id,id,null,body,true,outgoing,100000));
    }
    private int document(String id) throws Exception {
        MonogramChecklists.Checklist doc=document();int message=message(id,false,MonogramChecklists.markdown(doc));
        assertEquals(Collections.singleton(message),checklists.acceptDocument(message,peer,
                MonogramChecklists.markdown(doc),ChecklistContent.document(MonogramChecklists.encode(doc)),OWNER,false,false));
        return message;
    }
    private ChecklistContent update(String target,MonogramChecklists.Checklist doc) {
        return ChecklistContent.update(target,MonogramChecklists.encode(MonogramChecklists.toggle(target,doc,1,true)));
    }

    @Test public void pendingUpdateSurvivesRestartAndReplaysOnceWhenDocumentArrives() throws Exception {
        ChecklistContent update=update("later",document());
        assertTrue(checklists.acceptUpdate(0,peer,update,"alice@example.org",false,false).isEmpty());
        store.close();open();int id=document("later");
        assertEquals(2,checklists.checklist(id).revision);
        assertEquals("alice@example.org",checklists.checklist(id).items.get(0).completedBy);
        assertTrue(checklists.acceptUpdate(0,peer,update,"alice@example.org",false,false).isEmpty());
        assertEquals(2,checklists.checklist(id).revision);
    }

    @Test public void duplicateAliasOnOrdinaryMessageMakesChecklistTargetAmbiguous() throws Exception {
        int id=document("collision");
        message("collision",true,"An unrelated outgoing message");
        assertTrue(checklists.acceptUpdate(0,peer,update("collision",document()),OWNER,false,false).isEmpty());
        assertEquals(1,checklists.checklist(id).revision);
        try {checklists.target(id,peer);fail("Ambiguous alias must not become a mutation target");}
        catch(IllegalArgumentException expected) {}
    }

    @Test public void deletedChecklistCannotBeProjectedOrMutatedByDelayedUpdate() throws Exception {
        int id=document("deleted");store.deleteMessages(Collections.singleton(id));
        assertTrue(checklists.acceptUpdate(0,peer,update("deleted",document()),OWNER,false,false).isEmpty());
        assertNull(checklists.checklist(id));assertNull(checklists.snapshot(id,peer));
        assertFalse(checklists.project(new TLRPC.TL_message(),id,store.peer("alice@example.org")));
        try(Cursor row=store.getReadableDatabase().rawQuery("SELECT revision FROM checklist_documents WHERE message=?",new String[]{Integer.toString(id)})) {
            assertTrue(row.moveToFirst());assertEquals(1,row.getLong(0));
        }
        try(Cursor row=store.getReadableDatabase().rawQuery("SELECT rejected FROM checklist_updates",null)) {
            assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));
        }
    }

    @Test public void actorPermissionsAndRoomIdentityAreEnforcedBeforeMutation() throws Exception {
        MonogramChecklists.Checklist privateDoc=new MonogramChecklists.Checklist(OWNER,1,false,false,"Private",document().items);
        int id=message("private",false,MonogramChecklists.markdown(privateDoc));
        checklists.acceptDocument(id,peer,MonogramChecklists.markdown(privateDoc),
                ChecklistContent.document(MonogramChecklists.encode(privateDoc)),OWNER,false,false);
        assertTrue(checklists.acceptUpdate(0,peer,update("private",privateDoc),"alice@example.org",false,false).isEmpty());
        assertTrue(checklists.acceptUpdate(0,peer,update("private",privateDoc),OWNER,true,false).isEmpty());
        assertEquals(1,checklists.checklist(id).revision);
        assertEquals(Collections.singleton(id),checklists.acceptUpdate(0,peer,update("private",privateDoc),OWNER,true,true));
        assertEquals(2,checklists.checklist(id).revision);
    }

    @Test public void actorJidsCanonicalizeAndMutationReferencesMatchWireConstraints() {
        assertEquals(OWNER,new MonogramChecklists.Checklist("Bob@EXAMPLE.ORG/phone",1,false,false,"",Collections.emptyList()).owner);
        for(String invalid:Arrays.asList("example.org","bob@example.org\nforged","bob@")) {
            try {new MonogramChecklists.Checklist(invalid,1,false,false,"",Collections.emptyList());fail(invalid);}
            catch(MonogramChecklists.Invalid expected) {}
        }
        for(String invalid:Arrays.asList("has space","bad&ref","non-ascii-ą","'quoted'")) {
            try {MonogramChecklists.toggle(invalid,document(),1,true);fail(invalid);}
            catch(MonogramChecklists.Invalid expected) {}
        }
    }

    @Test public void terminalRevisionRejectsStateChangeWithoutOverflowException() {
        MonogramChecklists.Checklist terminal=new MonogramChecklists.Checklist(OWNER,Long.MAX_VALUE,true,true,"Last",document().items);
        assertNull(MonogramChecklists.apply(terminal,MonogramChecklists.toggle("last",terminal,1,true),OWNER,100));
        assertSame(terminal,MonogramChecklists.apply(terminal,MonogramChecklists.toggle("last",terminal,1,false),OWNER,100));
    }

    @Test public void checklistSizeIncludesBinaryOverheadAndCrossDialogControlCannotHideMessages() throws Exception {
        java.util.ArrayList<MonogramChecklists.Item> items=new java.util.ArrayList<>();
        String title=String.join("",Collections.nCopies(58,"a"));
        for(int i=1;i<=512;i++)items.add(new MonogramChecklists.Item(i,title,false,null,0));
        try {new MonogramChecklists.Checklist(OWNER,1,true,true,"",items);fail("Binary overhead exceeds 32768 bytes");}
        catch(MonogramChecklists.Invalid expected) {}
        int control=message("not-a-control",false,"Ordinary message");
        long other=store.peer("carol@example.org");
        assertTrue(checklists.acceptUpdate(control,other,update("unknown",document()),OWNER,false,false).isEmpty());
        assertFalse(checklists.hidden(control));
    }
}
