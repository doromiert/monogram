package org.telegram.messenger.xmpp;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import com.negzero.monogram.xmpp.XmppProfile;
import com.negzero.monogram.xmpp.MessageFixture;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramProfileCacheTest {
    Context context;final String file="profile-cache-test.db";
    @Before public void setup() { context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();context.deleteDatabase(file); }
    @After public void cleanup() { context.deleteDatabase(file); }
    byte[] avatar() {
        Bitmap image=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,bytes));image.recycle();return bytes.toByteArray();
    }
    @Test public void retainsExactSourceAcrossReopenWithoutLoadingBytesForOrdinaryProfileReads() throws Exception {
        byte[] source=avatar();long peer;
        try(MonogramStore store=new MonogramStore(context,file)) {
            peer=store.peer("bob@example.org");store.profile(peer,new XmppProfile("<vCard xmlns='vcard-temp'><FN>Bob</FN></vCard>","Bobby",source));
            assertEquals(0,store.profile(peer).avatar.length);assertArrayEquals(source,store.profileForRefresh(peer).avatar);
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals(0,store.profile(peer).avatar.length);assertEquals("Bobby",store.profileForRefresh(peer).nickname);
            assertArrayEquals(source,store.profileForRefresh(peer).avatar);assertTrue(store.avatar(peer).isFile());
            store.profile(peer,new XmppProfile(store.profile(peer).xml(),"Bobby",new byte[0]));
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals(0,store.profileForRefresh(peer).avatar.length);assertNull(store.avatar(peer));
            store.clear();try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM profile_avatar_sources",null)) { assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0)); }
        }
    }
    @Test public void schemaNineteenUpgradePreservesMessagesAndUsesTheExistingImageUntilARefreshSucceeds() throws Exception {
        byte[] source=avatar();long peer,self;int message;File image;
        try(MonogramStore store=new MonogramStore(context,file)) {
            self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
            message=store.save(MessageFixture.replyMessage("keep-message",null,null,"Keep this",true,false,100000,"phone",false,null));
            store.profile(peer,new XmppProfile("<vCard xmlns='vcard-temp'><FN>Bob</FN></vCard>","Bobby",source));image=store.avatar(peer);
            store.getWritableDatabase().execSQL("DROP TABLE profile_avatar_sources");store.getWritableDatabase().setVersion(19);
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals(22,store.getReadableDatabase().getVersion());assertEquals("Keep this",store.getMessage(message,self).message);
            assertEquals(image,store.avatar(peer));assertEquals("Bobby",store.profile(peer).nickname);
            assertArrayEquals(Files.readAllBytes(image.toPath()),store.profileForRefresh(peer).avatar);
            store.profile(peer,new XmppProfile(store.profile(peer).xml(),"New nickname",source));
        }
        try(MonogramStore store=new MonogramStore(context,file)) { assertEquals("New nickname",store.profile(peer).nickname);assertArrayEquals(source,store.profileForRefresh(peer).avatar); }
    }
}
