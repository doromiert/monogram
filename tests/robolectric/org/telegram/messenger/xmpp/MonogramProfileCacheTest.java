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
            store.getWritableDatabase().execSQL("DROP TABLE profile_avatar_sources");store.getWritableDatabase().execSQL("DROP TABLE IF EXISTS call_events");store.getWritableDatabase().setVersion(19);
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals(28,store.getReadableDatabase().getVersion());assertEquals("Keep this",store.getMessage(message,self).message);
            assertEquals(image,store.avatar(peer));assertEquals("Bobby",store.profile(peer).nickname);
            assertArrayEquals(Files.readAllBytes(image.toPath()),store.profileForRefresh(peer).avatar);
            store.profile(peer,new XmppProfile(store.profile(peer).xml(),"New nickname",source));
        }
        try(MonogramStore store=new MonogramStore(context,file)) { assertEquals("New nickname",store.profile(peer).nickname);assertArrayEquals(source,store.profileForRefresh(peer).avatar); }
    }
    @Test public void localNamesSurviveRemoteUpdatesAndReopenAndCanBeReset() throws Exception {
        long peer,self;
        try(MonogramStore store=new MonogramStore(context,file)) {
            self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
            store.rosterName(peer,"Roster name");store.localName(peer,"  My friend  ");
            store.profile(peer,new XmppProfile("<vCard xmlns='vcard-temp'><FN>Remote name</FN></vCard>","Remote nickname",new byte[0]));
            store.rosterName(peer,"Changed roster name");
            assertEquals("My friend",store.user(peer,self).first_name);
            assertEquals("My friend",store.fullUser(peer,self).user.first_name);
            store.localName(self,"Not my public name");assertNotEquals("Not my public name",store.user(self,self).first_name);
            for(String invalid:new String[]{"First\nSecond","Name\u2028Other","x".repeat(129)}) {
                assertThrows(IllegalArgumentException.class,() -> store.localName(peer,invalid));
                assertEquals("My friend",store.localName(peer));
            }
            assertThrows(IllegalArgumentException.class,() -> store.localName(Long.MAX_VALUE,"Unknown"));
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals("My friend",store.user(peer,self).first_name);
            store.localName(peer,"");assertEquals("",store.localName(peer));
            assertEquals("Changed roster name",store.user(peer,self).first_name);
            store.localName(peer,"Again");store.clear();assertEquals("",store.localName(peer));
        }
    }
    @Test public void schemaTwentyFiveUpgradeAddsLocalNamesWithoutReplacingMessagesOrProfiles() throws Exception {
        long peer,self;int message;
        try(MonogramStore store=new MonogramStore(context,file)) {
            self=store.peer("alice@example.org");peer=store.peer("bob@example.org");store.rosterName(peer,"Bob");
            message=store.save(MessageFixture.replyMessage("alias-migration",null,null,"Keep this",true,false,100000,"phone",false,null));
            store.getWritableDatabase().execSQL("DROP TABLE local_contact_names");store.getWritableDatabase().setVersion(25);
        }
        try(MonogramStore store=new MonogramStore(context,file)) {
            assertEquals(28,store.getReadableDatabase().getVersion());
            assertEquals("Keep this",store.getMessage(message,self).message);assertEquals("Bob",store.user(peer,self).first_name);
            store.localName(peer,"Local Bob");assertEquals("Local Bob",store.user(peer,self).first_name);
        }
    }
}
