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
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramSettingsColorsTest.Preferences.class)
public class MonogramSettingsColorsTest {
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
