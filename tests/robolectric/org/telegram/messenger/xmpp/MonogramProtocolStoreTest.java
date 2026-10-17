package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramProtocolStoreTest {
    private int protocol(MonogramStore store,int id) {
        try (Cursor c=store.getReadableDatabase().rawQuery("SELECT encryption FROM messages WHERE id=?",new String[]{Integer.toString(id)})) {
            assertTrue(c.moveToFirst()); return c.getInt(0);
        }
    }
    @Test public void schemaEightKeepsKnownTextAndModernReplayCannotPoisonItsArchivePage() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="protocol-migration.db"; context.deleteDatabase(file);
        int old; long self,peer;
        try {
            try (MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer("alice@example.org"); peer=store.peer("bob@example.org");
                old=store.save(MessageFixture.replyMessage("same-id","same-origin","same-archive","Keep existing plaintext",true,false,100000,"phone",false,null));
                MonogramStoreDeviceTest.legacyProtocols(store.getWritableDatabase()); store.getWritableDatabase().setVersion(8);
            }
            try (MonogramStore store=new MonogramStore(context,file)) {
                assertEquals(15,store.getReadableDatabase().getVersion()); assertEquals(-1,protocol(store,old));
                XmppMessage missing=MessageFixture.modern("same-id","same-archive",null,false,100000,null,null,null);
                assertTrue(store.saveArchive(peer,MessageFixture.page(missing),true,null));
                assertEquals("same-archive",store.archiveCursor(peer,false));
                assertEquals("Keep existing plaintext",store.getMessage(old,self).message);
                assertEquals(2,store.history(peer,self,0,100).size());
                int recovered=store.save(MessageFixture.modern("same-id","same-archive","Authenticated recovered text",false,100000,null,null,null));
                assertTrue(recovered>0 && recovered!=old); assertEquals(2,protocol(store,recovered));
                assertEquals(0,store.save(missing)); assertEquals("Authenticated recovered text",store.getMessage(recovered,self).message);
                assertEquals("Keep existing plaintext",store.getMessage(old,self).message);
            }
        } finally { context.deleteDatabase(file); }
    }
    @Test public void modernEditsKeepProtocolAndRejectLegacyDowngrades() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="protocol-edits.db"; context.deleteDatabase(file);
        try (MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            int original=store.save(MessageFixture.modern("original",null,"Before",true,100000,null,null,null));
            int edit=store.save(MessageFixture.modern("edit",null,"Modern edit",true,200000,null,"original",null));
            store.reconcileCorrections(peer);
            assertEquals("Modern edit",store.getMessage(original,self).message); assertTrue(store.hiddenCorrection(edit));
            assertEquals(XmppMessage.Encryption.OMEMO2,store.editTarget(original,peer,self).encryption);
            int downgrade=store.save(MessageFixture.correction("legacy-edit",null,null,"Forged legacy target",true,true,300000,"phone",true,null,"original"));
            store.reconcileCorrections(peer);
            assertFalse(store.hiddenCorrection(downgrade)); assertEquals("Modern edit",store.getMessage(original,self).message);
        } finally { context.deleteDatabase(file); }
    }
    @Test public void modernCaptionAndIntegrityMetadataSurviveUnreadableReplayAndReopen() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String file="protocol-caption.db"; context.deleteDatabase(file);
        String url="aesgcm://example.org/private.png#"+new String(new char[88]).replace('\0','a');
        FileShare sharing=new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>Friends.png</name><media-type>image/png</media-type></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+url+"'/></sources></file-sharing>");
        long self; int id;
        try {
            try (MonogramStore store=new MonogramStore(context,file)) {
                self=store.peer("alice@example.org"); store.peer("bob@example.org");
                id=store.save(MessageFixture.modern("photo","archive",sharing.body("Private caption 😀"),false,100000,null,null,sharing));
                assertEquals(2,protocol(store,id)); assertEquals("Private caption 😀",store.getMessage(id,self).message);
                assertEquals(0,store.save(MessageFixture.modern("photo","archive",null,false,100000,null,null,null)));
            }
            try (MonogramStore store=new MonogramStore(context,file)) {
                assertEquals("Private caption 😀",store.getMessage(id,self).message); assertEquals(url,store.sharing(id).link.wireUrl);
                assertEquals(2,protocol(store,id));
            }
        } finally { context.deleteDatabase(file); }
    }
}
