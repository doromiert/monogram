package org.telegram.ui;

import android.content.Context;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AnimatedTextView;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramSettingsColorsTest.Preferences.class,MonogramSettingsColorsTest.Device.class})
public class MonogramSettingsColorsTest {
    // This manifest-free fixture has no APK bool resources for device bootstrap.
    @Implements(value=org.telegram.messenger.AndroidUtilities.class,isInAndroidSdk=false)
    public static class Device {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static boolean isTablet() { return false; }
        @Implementation public static boolean isTabletForce() { return false; }
    }
    @org.junit.Before public void setupDevice() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        org.telegram.messenger.AndroidUtilities.density=1;
        org.telegram.messenger.AndroidUtilities.displaySize=new android.graphics.Point(400,800);
    }
    @Implements(value=org.telegram.messenger.MessagesController.class,isInAndroidSdk=false)
    public static class Preferences {
        @Implementation public static android.content.SharedPreferences getGlobalMainSettings() {
            return RuntimeEnvironment.getApplication().getSharedPreferences("settings-colors",0);
        }
    }
    public static class ValueCell extends FrameLayout {
        public final AnimatedTextView valueTextView;
        public ValueCell(Context context) {
            super(context);
            addView(valueTextView=new AnimatedTextView(context));
        }
    }

    @Test public void directAnimatedLabelFollowsSuccessiveThemeColors() {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        AnimatedTextView label=new AnimatedTextView(context);
        ThemeDescription binding=new ThemeDescription(label,ThemeDescription.FLAG_TEXTCOLOR,null,null,null,null,Theme.key_windowBackgroundWhiteValueText);
        for(int color:new int[]{0xff268acc,0xff64b5f6,0xff268acc}) {
            binding.setColor(color,false,false);
            assertEquals(color,label.getDrawable().getTextColor());
        }
    }

    @Test public void reflectedAnimatedValueFollowsThemeAndRespectsColorTags() {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        FrameLayout list=new FrameLayout(context);ValueCell cell=new ValueCell(context);list.addView(cell);
        int key=Theme.key_windowBackgroundWhiteValueText;cell.setTag(key);
        ThemeDescription binding=new ThemeDescription(list,ThemeDescription.FLAG_CHECKTAG,new Class[]{ValueCell.class},new String[]{"valueTextView"},null,null,null,key);
        binding.setColor(0xff64b5f6,false,false);assertEquals(0xff64b5f6,cell.valueTextView.getDrawable().getTextColor());
        cell.setTag(Theme.key_text_RedRegular);
        binding.setColor(0xff268acc,false,false);assertEquals(0xff64b5f6,cell.valueTextView.getDrawable().getTextColor());
        cell.setTag(key);
        binding.setColor(0xff268acc,false,false);assertEquals(0xff268acc,cell.valueTextView.getDrawable().getTextColor());
    }
    public static class InputCell extends FrameLayout {
        public final org.telegram.ui.Components.EditTextBoldCursor input;
        public final android.widget.EditText plainInput;
        public InputCell(Context context) {
            super(context);
            addView(input=new org.telegram.ui.Components.EditTextBoldCursor(context));
            addView(plainInput=new android.widget.EditText(context));
        }
    }

    @Test public void reflectedHintsKeepEnteredTextAndForegroundAcrossThemes() {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        FrameLayout list=new FrameLayout(context);InputCell cell=new InputCell(context);list.addView(cell);
        cell.input.setText("My saved reply");cell.plainInput.setText("Another reply");
        int foreground=0xffeeeeee;
        cell.input.setTextColor(foreground);cell.plainInput.setTextColor(foreground);
        ThemeDescription nativeHint=new ThemeDescription(list,ThemeDescription.FLAG_HINTTEXTCOLOR,new Class[]{InputCell.class},new String[]{"input"},null,null,null,Theme.key_windowBackgroundWhiteHintText);
        ThemeDescription plainHint=new ThemeDescription(list,ThemeDescription.FLAG_HINTTEXTCOLOR,new Class[]{InputCell.class},new String[]{"plainInput"},null,null,null,Theme.key_windowBackgroundWhiteHintText);
        for(int color:new int[]{0xff999999,0xff657585,0xff999999}) {
            nativeHint.setColor(color,false,false);plainHint.setColor(color,false,false);
            assertEquals(color,(int)org.robolectric.util.ReflectionHelpers.getField(cell.input,"hintColor"));
            assertEquals(color,cell.plainInput.getCurrentHintTextColor());
            assertEquals(foreground,cell.input.getCurrentTextColor());
            assertEquals(foreground,cell.plainInput.getCurrentTextColor());
            assertEquals("My saved reply",cell.input.getText().toString());
            assertEquals("Another reply",cell.plainInput.getText().toString());
        }
    }

    private static class ScrollFixture extends androidx.recyclerview.widget.RecyclerView {
        boolean scrolled;
        ScrollFixture(Context context) { super(context); }
        @Override public boolean canScrollVertically(int direction) { return direction < 0 && scrolled; }
        void notifyScroll() {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(this,"dispatchOnScrolled",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,0),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,1));
        }
    }

    @Test public void adaptiveHeaderFollowsOnlyTheCurrentlyBoundPage() {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        org.telegram.ui.ActionBar.ActionBar bar=new org.telegram.ui.ActionBar.ActionBar(context);
        ScrollFixture first=new ScrollFixture(context), second=new ScrollFixture(context);
        second.scrolled=true;
        bar.setAdaptiveBackground(first);
        bar.setAdaptiveBackground(second);
        // A retained offscreen page must no longer drive the active page's header.
        first.notifyScroll();
        assertEquals(false,org.robolectric.util.ReflectionHelpers.getField(bar,"onTop"));
        second.scrolled=false;second.notifyScroll();
        assertEquals(true,org.robolectric.util.ReflectionHelpers.getField(bar,"onTop"));
        bar.setAdaptiveBackground(first);
        second.scrolled=true;second.notifyScroll();
        assertEquals(true,org.robolectric.util.ReflectionHelpers.getField(bar,"onTop"));
        first.scrolled=true;first.notifyScroll();
        assertEquals(false,org.robolectric.util.ReflectionHelpers.getField(bar,"onTop"));
    }

}
