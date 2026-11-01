package org.telegram.ui;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.View;
import android.widget.FrameLayout;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={
    MonogramNavigationTest.App.class,MonogramConnectionNoticeRealTest.Device.class,
    MonogramConnectionNoticeRealTest.Colors.class,MonogramConnectionNoticeRealTest.Settings.class,
    MonogramConnectionNoticeTest.Labels.class,MonogramSystemEmojiTest.Config.class,
    MonogramComposeNavigationTest.Lite.class})
public class MonogramConnectionNoticeRealTest {
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Device extends MonogramSystemEmojiTest.Device {
        @Implementation public static android.graphics.Typeface bold(){return android.graphics.Typeface.DEFAULT_BOLD;}
        @Implementation public static void makeAccessibilityAnnouncement(CharSequence text){}
    }
    @Implements(value=Theme.class,isInAndroidSdk=false)
    public static class Colors {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static int getColor(int key){return 0xff444444;}
        @Implementation public static int getColor(int key,Theme.ResourcesProvider provider){return 0xff444444;}
        @Implementation public static Drawable createRoundRectDrawable(float radius,int color){return new ColorDrawable(color);}
        @Implementation public static Drawable createSelectorDrawable(int color,int mask){return new ColorDrawable(color);}
    }
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static android.content.SharedPreferences getGlobalMainSettings(){return RuntimeEnvironment.getApplication().getSharedPreferences("notice-real",0);}
    }
    private Activity activity;
    private FrameLayout root;
    private MonogramConnectionNotice notice;
    private final List<Integer> reconnects=new ArrayList<>();
    @Before public void setup(){
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();ApplicationLoader.applicationHandler=new Handler(Looper.getMainLooper());
        AndroidUtilities.density=1;AndroidUtilities.displaySize=new Point(390,844);
        activity=Robolectric.buildActivity(Activity.class).setup().get();root=new FrameLayout(activity);activity.setContentView(root);
        notice=new MonogramConnectionNotice(root,reconnects::add);
    }
    private void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));root.layout(0,0,390,844);}
    @After public void cleanup(){notice.hide();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));activity.finish();}
    @Test public void realBulletinCanDisappearDuringEntranceAndBeShownAgain(){
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);layout();assertTrue(Bulletin.getVisibleBulletin().isShowing());
        notice.update(0,true,ConnectionsManager.ConnectionStateConnecting);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertNull(Bulletin.getVisibleBulletin());
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);layout();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        Bulletin visible=Bulletin.getVisibleBulletin();assertNotNull(visible);
        ((Bulletin.ButtonLayout)visible.getLayout()).getButton().performClick();
        assertEquals(Collections.singletonList(0),reconnects);assertNull(Bulletin.getVisibleBulletin());
    }
    @Test public void realBulletinSurvivesConnectionChangesBeforeLayoutAndBackgrounding(){
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);
        notice.update(0,true,ConnectionsManager.ConnectionStateConnecting);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertNull(Bulletin.getVisibleBulletin());
        notice.update(0,true,ConnectionsManager.ConnectionStateWaitingForNetwork);layout();
        notice.update(3,true,ConnectionsManager.ConnectionStateWaitingForNetwork);layout();
        notice.update(3,false,ConnectionsManager.ConnectionStateWaitingForNetwork);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertNull(Bulletin.getVisibleBulletin());
        assertTrue(reconnects.isEmpty());
    }

}
