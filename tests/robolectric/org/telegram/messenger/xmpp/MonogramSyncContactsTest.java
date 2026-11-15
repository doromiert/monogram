package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramSyncContactsTest {
    Context context;MonogramStore store;String owner="alice@example.invalid",peer="bob@example.invalid";long local;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;context.deleteDatabase("sync-contacts.db");store=new MonogramStore(context,"sync-contacts.db");MonogramContactDetails.prefs(0).edit().clear().commit();local=store.peer(peer);}
    @After public void cleanup(){store.close();context.deleteDatabase("sync-contacts.db");MonogramContactDetails.prefs(0).edit().clear().commit();}
    @Test public void localEditsExportPortableValuesAndImportsDoNotMintNewRevisions()throws Exception {
        store.localName(local,"Local nickname");MonogramContactDetails.note(0,owner,peer,"A private note");
        JSONObject exported=MonogramSyncContacts.snapshot(0,owner,store).getJSONObject(0);JSONObject value=exported.getJSONObject("value");long revision=exported.getLong("revision");
        assertEquals("Local nickname",value.getString("nickname"));assertEquals("A private note",value.getString("note"));assertTrue(value.isNull("avatar"));assertEquals(MonogramSyncContacts.id(peer),exported.getString("id"));assertTrue(revision>0);
        value.put("nickname","Remote nickname");assertFalse(MonogramSyncContacts.apply(0,owner,store,value,revision-1));assertEquals("Local nickname",store.localName(local));
        assertTrue(MonogramSyncContacts.apply(0,owner,store,value,revision+10));assertEquals(revision+10,MonogramContactDetails.revision(0,owner,peer));assertEquals("Remote nickname",store.localName(local));
        assertEquals(revision+10,MonogramSyncContacts.snapshot(0,owner,store).getJSONObject(0).getLong("revision"));
        MonogramContactDetails.note(0,owner,peer,"");assertTrue(MonogramContactDetails.revision(0,owner,peer)>revision+10);
        assertEquals("",MonogramContactDetails.note(0,"other@example.invalid",peer));
    }
    @Test public void clearingAnAvatarKeepsAnExplicitVersionedTombstone()throws Exception {
        MonogramContactDetails.avatar(0,owner,peer,null);JSONObject exported=MonogramSyncContacts.snapshot(0,owner,store).getJSONObject(0);assertTrue(exported.getJSONObject("value").isNull("avatar"));assertTrue(exported.getLong("revision")>0);
        JSONObject invalid=new JSONObject().put("peer",owner).put("nickname","").put("note","").put("avatar",JSONObject.NULL);
        try{MonogramSyncContacts.apply(0,owner,store,invalid,1);fail("own account must not be overwritten");}catch(IllegalArgumentException expected){}
    }
}
