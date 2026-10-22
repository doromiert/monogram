package org.telegram.ui;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.graphics.Point;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramDrawerTest.App.class,MonogramDrawerTest.Device.class})
public class MonogramDrawerTest {
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Device { @Implementation public static void __staticInitializer__() {} }
    private DrawerLayoutContainer root;
    private MonogramDrawer drawer;
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        AndroidUtilities.density=1;AndroidUtilities.displaySize=new Point(400,800);
        LocaleController.isRTL=false;
        root=new DrawerLayoutContainer(RuntimeEnvironment.getApplication());
        drawer=new MonogramDrawer(root);
        ReflectionHelpers.setField(root,"navigationDrawer",drawer);
        root.addView(drawer);
        drawer.setVisibility(View.VISIBLE);
        ReflectionHelpers.setField(drawer,"width",300);
        ReflectionHelpers.callInstanceMethod(drawer,"setProgress",ReflectionHelpers.ClassParameter.from(float.class,1f));
        root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,400,800);
    }
    private MotionEvent event(int action,float x,float y,long time) { return MotionEvent.obtain(0,time,action,x,y,0); }
    private float progress() { return ReflectionHelpers.getField(drawer,"progress"); }
    @Test public void horizontalCloseIsInteractiveAndSettlesAfterRelease() {
        assertFalse(root.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN,250,300,0)));
        root.requestDisallowInterceptTouchEvent(true);
        MotionEvent move=event(MotionEvent.ACTION_MOVE,100,301,100);
        assertTrue(root.onInterceptTouchEvent(move));assertTrue(root.onTouchEvent(move));
        assertEquals(.5f,progress(),.001f);
        root.onTouchEvent(event(MotionEvent.ACTION_UP,80,301,120));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(0f,progress(),.001f);assertEquals(View.GONE,drawer.getVisibility());
    }
    @Test public void verticalScrollingDoesNotCloseDrawerAndCanceledDragRestoresIt() {
        root.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN,250,300,0));
        assertFalse(root.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE,247,380,100)));
        assertFalse(drawer.observingEdge());assertEquals(1f,progress(),0);
        root.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN,250,300,200));
        MotionEvent move=event(MotionEvent.ACTION_MOVE,100,301,300);
        assertTrue(root.onInterceptTouchEvent(move));root.onTouchEvent(move);
        root.onTouchEvent(event(MotionEvent.ACTION_CANCEL,100,301,310));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(1f,progress(),.001f);assertEquals(View.VISIBLE,drawer.getVisibility());
    }
    @Test public void rtlDrawerClosesInOppositeDirection() {
        LocaleController.isRTL=true;
        root.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN,100,300,0));
        MotionEvent move=event(MotionEvent.ACTION_MOVE,250,301,100);
        assertTrue(root.onInterceptTouchEvent(move));root.onTouchEvent(move);
        assertEquals(.5f,progress(),.001f);
        root.onTouchEvent(event(MotionEvent.ACTION_UP,280,301,120));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(View.GONE,drawer.getVisibility());
    }
    @After public void cleanup() { LocaleController.isRTL=false;drawer.close(false); }
}
