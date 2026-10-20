package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

/** Synthetic owners only. These tests never log in, fetch wallpaper or contact a server. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramChatAppearanceTest.Account.class,MonogramChatAppearanceTest.App.class,MonogramChatAppearanceTest.Ui.class})
public class MonogramChatAppearanceTest {
    private static MonogramController controller;
    private static String peer;
    private Context context;
    @Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Account {
        @Implementation public static MonogramController get(int account) { return controller; }
        @Implementation public String accountJid() { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-0",Context.MODE_PRIVATE).getString("jid",""); }
        @Implementation public String jid(long dialog) { return peer; }
    }
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable runnable) { runnable.run(); }
    }
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        controller=Shadow.newInstanceOf(MonogramController.class);peer="friend@example.org";
        context.getSharedPreferences("monogram-0",Context.MODE_PRIVATE).edit().putString("jid",UUID.randomUUID()+"@example.org").commit();
        MonogramChatAppearance.invalidate(0);
    }
    private ExecutorService writer() { return ReflectionHelpers.getStaticField(MonogramChatAppearance.class,"WRITER"); }
    private void idle() throws Exception { writer().submit(()->{}).get(10,TimeUnit.SECONDS); }
    private TLRPC.WallPaper save(String source) throws Exception {
        AtomicReference<TLRPC.WallPaper> result=new AtomicReference<>();
        TLRPC.TL_wallPaperSettings settings=new TLRPC.TL_wallPaperSettings();settings.flags=1;settings.background_color=0x446688;
        MonogramChatAppearance.wallpaper(0,42,settings,null,source,result::set,()->{});idle();return result.get();
    }
    @Test public void ownerKeySurvivesReopenButNotReusedPeerId() {
        assertTrue(MonogramChatAppearance.theme(0,42,"emoticon_test"));
        MonogramChatAppearance.invalidate(0);assertEquals("emoticon_test",MonogramChatAppearance.theme(0,42));
        peer="other@example.org";MonogramChatAppearance.invalidate(0);assertNull(MonogramChatAppearance.theme(0,42));
        peer="friend@example.org";MonogramChatAppearance.invalidate(0);assertEquals("emoticon_test",MonogramChatAppearance.theme(0,42));
        context.getSharedPreferences("monogram-0",Context.MODE_PRIVATE).edit().putString("jid","different@example.org").commit();
        assertNull(MonogramChatAppearance.theme(0,42));
    }
    @Test public void nativeSolidSettingsRoundTripAndAtomicThemeReset() throws Exception {
        assertNotNull(save(null));MonogramChatAppearance.invalidate(0);
        assertEquals(0x446688,MonogramChatAppearance.wallpaper(0,42).settings.background_color);
        assertTrue(MonogramChatAppearance.theme(0,42,"emoticon_color",true));
        assertNull(MonogramChatAppearance.wallpaper(0,42));assertEquals("emoticon_color",MonogramChatAppearance.theme(0,42));
    }
    @Test public void replacementAndResetRetireOnlyOwnedSnapshotAndFailureKeepsPrevious() throws Exception {
        File source=File.createTempFile("fixture-wallpaper",".jpg",context.getCacheDir());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(source)) { out.write(new byte[]{1,2,3}); }
        TLRPC.WallPaper first=save(source.getAbsolutePath());assertNotNull(first);File snapshot=new File(first.uploadingImage);assertTrue(snapshot.isFile());
        assertNull(save(source.getAbsolutePath()+"-missing"));assertTrue(snapshot.isFile());
        assertEquals(first.id,MonogramChatAppearance.wallpaper(0,42).id);
        TLRPC.WallPaper next=save(source.getAbsolutePath());assertNotNull(next);assertFalse(snapshot.exists());assertTrue(source.isFile());
        assertTrue(MonogramChatAppearance.clearWallpaper(0,42));assertFalse(new File(next.uploadingImage).exists());assertTrue(source.isFile());
    }
    @Test public void sameJidReloginFencesPendingWriteAndCallback() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        writer().submit(()->{entered.countDown();try { release.await(10,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }});
        assertTrue(entered.await(10,TimeUnit.SECONDS));AtomicReference<TLRPC.WallPaper> result=new AtomicReference<>();
        try {
            MonogramChatAppearance.wallpaper(0,42,new TLRPC.TL_wallPaperSettings(),null,null,result::set,()->{});
            MonogramChatAppearance.invalidate(0);
        } finally { release.countDown(); }
        idle();assertNull(result.get());assertNull(MonogramChatAppearance.wallpaper(0,42));
    }
}
