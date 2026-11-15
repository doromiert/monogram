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
    @Test public void avatarBytesTravelWithoutDevicePathsAndDecodeOnTheOtherDevice()throws Exception {
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888);java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,encoded));image.recycle();
        java.io.File source=java.io.File.createTempFile("contact-avatar-",".png",context.getCacheDir());try(java.io.FileOutputStream out=new java.io.FileOutputStream(source)){out.write(encoded.toByteArray());}
        MonogramContactDetails.avatar(0,owner,peer,source);JSONObject exported=MonogramSyncContacts.snapshot(0,owner,store).getJSONObject(0);JSONObject value=exported.getJSONObject("value");assertFalse(value.isNull("avatar"));assertFalse(value.toString().contains(source.getAbsolutePath()));
        context.deleteDatabase("sync-contact-other.db");MonogramContactDetails.prefs(1).edit().clear().commit();
        try(MonogramStore target=new MonogramStore(context,"sync-contact-other.db")) {
            assertTrue(MonogramSyncContacts.apply(1,owner,target,value,exported.getLong("revision")));java.io.File received=MonogramContactDetails.avatar(1,owner,peer);assertNotNull(received);assertTrue(received.isFile());assertNotEquals(source.getAbsolutePath(),received.getAbsolutePath());
            android.graphics.Bitmap decoded=android.graphics.BitmapFactory.decodeFile(received.getAbsolutePath());assertNotNull(decoded);assertEquals(8,decoded.getWidth());assertEquals(8,decoded.getHeight());decoded.recycle();assertEquals(exported.getLong("revision"),MonogramSyncContacts.snapshot(1,owner,target).getJSONObject(0).getLong("revision"));
        }finally{context.deleteDatabase("sync-contact-other.db");MonogramContactDetails.prefs(1).edit().clear().commit();source.delete();}
    }
}
