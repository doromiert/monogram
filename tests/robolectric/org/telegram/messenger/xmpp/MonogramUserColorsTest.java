package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import java.util.UUID;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramUserColorsTest.Account.class,MonogramUserColorsTest.App.class,MonogramUserColorsTest.Packs.class})
public class MonogramUserColorsTest {
    private static MonogramController controller;private static String jid;
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Account {
        @Implementation public static MonogramController get(int account) { return controller; }
        @Implementation public String jid(long id) { return jid; }
        @Implementation public long accountGeneration() { return 1; }
    }
    private static final long NAME_EMOJI=0x4d50000000000001L,PROFILE_EMOJI=0x4d50000000000002L;
    private static final java.util.Map<Long,TLRPC.Document> documents=new java.util.HashMap<>();
    @Implements(value=MonogramPacks.class,isInAndroidSdk=false)
    public static class Packs {
        @Implementation public static TLRPC.Document document(int account,long id) { return account==0 ? documents.get(id) : null; }
    }
    private TLRPC.Document emoji(long id,boolean custom) throws Exception {
        java.io.File file=java.io.File.createTempFile("profile-emoji-",".png",RuntimeEnvironment.getApplication().getCacheDir());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)) { out.write(new byte[]{1,2,3}); }
        TLRPC.TL_document doc=new TLRPC.TL_document();doc.id=id;doc.dc_id=Integer.MIN_VALUE;doc.localPath=file.getAbsolutePath();
        doc.attributes.add(custom ? new TLRPC.TL_documentAttributeCustomEmoji() : new TLRPC.TL_documentAttributeSticker());documents.put(id,doc);return doc;
    }
    private TLRPC.User user(boolean self) { TLRPC.TL_user user=new TLRPC.TL_user();user.id=42;user.self=self;return user; }
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();documents.clear();
        controller=Shadow.newInstanceOf(MonogramController.class);jid=UUID.randomUUID()+"@example.org";
        RuntimeEnvironment.getApplication().getSharedPreferences("monogram-0",Context.MODE_PRIVATE).edit().putString("jid",jid).commit();
    }
    @Test public void nativeFlagsAndColorsReapplyToFreshFacadeUser() {
        assertTrue(MonogramUserColors.save(0,jid,user(true),5,7));
        TLRPC.User fresh=user(true);MonogramUserColors.project("monogram-0.db",jid,jid,fresh);
        assertEquals(5,fresh.color.color);assertEquals(7,fresh.profile_color.color);
        assertEquals(256|512,fresh.flags2 & (256|512));assertEquals(1,fresh.color.flags);assertEquals(1,fresh.profile_color.flags);
        assertEquals(0,fresh.color.background_emoji_id);assertEquals(0,fresh.profile_color.background_emoji_id);
    }
    @Test public void resetRetainsNameAndRemovesProfileColorFlag() {
        assertTrue(MonogramUserColors.save(0,jid,user(true),2,3));
        assertTrue(MonogramUserColors.save(0,jid,user(true),2,-1));
        TLRPC.User fresh=user(true);MonogramUserColors.project("monogram-0.db",jid,jid,fresh);
        assertEquals(2,fresh.color.color);assertEquals(0,fresh.profile_color.flags & 1);
    }
    @Test public void accountSlotOwnerPeerAndNonSelfProjectionAreIsolated() {
        assertTrue(MonogramUserColors.save(0,jid,user(true),1,4));
        TLRPC.User fresh=user(true);MonogramUserColors.project("monogram-1.db",jid,jid,fresh);assertNull(fresh.color);
        MonogramUserColors.project("monogram-0.db","other@example.org",jid,fresh);assertNull(fresh.color);
        MonogramUserColors.project("monogram-0.db",jid,"other@example.org",fresh);assertNull(fresh.color);
        TLRPC.User contact=user(false);MonogramUserColors.project("monogram-0.db",jid,jid,contact);assertNull(contact.color);
    }
    @Test public void staleOwnerAndUnsupportedSelectionsCannotReplaceSavedColors() {
        assertTrue(MonogramUserColors.save(0,jid,user(true),3,6));
        assertFalse(MonogramUserColors.save(0,"old@example.org",user(true),1,2));
        assertFalse(MonogramUserColors.save(0,jid,user(false),1,2));
        assertFalse(MonogramUserColors.save(0,jid,user(true),7,2));
        assertFalse(MonogramUserColors.save(0,jid,user(true),1,8));
        TLRPC.User fresh=user(true);MonogramUserColors.project("monogram-0.db",jid,jid,fresh);
        assertEquals(3,fresh.color.color);assertEquals(6,fresh.profile_color.color);
    }
    @Test public void bothLocalBackgroundsProjectAndClearAtomicallyWithoutLosingColors() throws Exception {
        emoji(NAME_EMOJI,true);emoji(PROFILE_EMOJI,true);
        assertTrue(MonogramUserColors.save(0,jid,user(true),4,2,NAME_EMOJI,PROFILE_EMOJI));
        TLRPC.User fresh=user(true);MonogramUserColors.project("monogram-0.db",jid,jid,fresh);
        assertEquals(3,fresh.color.flags);assertEquals(NAME_EMOJI,fresh.color.background_emoji_id);
        assertEquals(3,fresh.profile_color.flags);assertEquals(PROFILE_EMOJI,fresh.profile_color.background_emoji_id);
        assertTrue(MonogramUserColors.save(0,jid,fresh,6,3));
        assertEquals(NAME_EMOJI,fresh.color.background_emoji_id);assertEquals(PROFILE_EMOJI,fresh.profile_color.background_emoji_id);
        assertTrue(MonogramUserColors.save(0,jid,fresh,6,3,0,0));
        TLRPC.User reset=user(true);MonogramUserColors.project("monogram-0.db",jid,jid,reset);
        assertEquals(6,reset.color.color);assertEquals(3,reset.profile_color.color);
        assertEquals(1,reset.color.flags);assertEquals(1,reset.profile_color.flags);
        assertEquals(0,reset.color.background_emoji_id);assertEquals(0,reset.profile_color.background_emoji_id);
    }
    @Test public void foreignMissingAndOrdinaryStickerBackgroundsDoNotReplaceSavedState() throws Exception {
        TLRPC.Document name=emoji(NAME_EMOJI,true);emoji(PROFILE_EMOJI,false);
        assertTrue(MonogramUserColors.save(0,jid,user(true),2,4,NAME_EMOJI,0));
        assertFalse(MonogramUserColors.validEmoji(1,NAME_EMOJI));
        assertFalse(MonogramUserColors.save(0,jid,user(true),3,5,NAME_EMOJI,PROFILE_EMOJI));
        assertFalse(MonogramUserColors.save(0,jid,user(true),3,5,1234,0));
        assertTrue(new java.io.File(name.localPath).delete());
        assertFalse(MonogramUserColors.save(0,jid,user(true),3,5,NAME_EMOJI,0));
        TLRPC.User fresh=user(true);MonogramUserColors.project("monogram-0.db",jid,jid,fresh);
        assertEquals(2,fresh.color.color);assertEquals(4,fresh.profile_color.color);assertEquals(NAME_EMOJI,fresh.color.background_emoji_id);
        assertTrue(MonogramUserColors.save(0,jid,fresh,2,4,0,0));
    }

}
