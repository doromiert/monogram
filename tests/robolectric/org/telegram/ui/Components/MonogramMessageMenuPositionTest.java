package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={org.telegram.ui.MonogramSettingsColorsTest.Device.class,org.telegram.ui.MonogramSettingsColorsTest.Preferences.class})
public class MonogramMessageMenuPositionTest {
    @org.junit.Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        AndroidUtilities.density=1;AndroidUtilities.displaySize=new android.graphics.Point(400,800);
    }
    @Test public void tallHiddenSubmenuDoesNotRaiseTheInitialVisibleMenu() {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;AndroidUtilities.density=1;
        ChatScrimPopupContainerLayout container=new ChatScrimPopupContainerLayout(context);
        ActionBarPopupWindow.ActionBarPopupWindowLayout menu=new ActionBarPopupWindow.ActionBarPopupWindowLayout(context,0,null,ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_USE_SWIPEBACK);
        menu.setPadding(8,8,8,8);
        TextView root=new TextView(context);root.setText("Reply\nCopy");root.setMinimumHeight(100);root.setMinimumWidth(200);menu.addView(root);
        TextView details=new TextView(context);details.setMinimumHeight(440);details.setMinimumWidth(280);menu.addViewToSwipeBack(details);
        container.addView(menu,LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,LayoutHelper.WRAP_CONTENT));container.setPopupWindowLayout(menu);
        int spec=View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.AT_MOST);container.measure(spec,spec);
        int visible=container.getInitialVisibleHeight();
        assertTrue("The hidden page still reserves space for opening details",container.getMeasuredHeight()>=440);
        assertTrue("Initial placement must use root height plus padding, not hidden details",visible>=100 && visible<150);
        // With the same touch point, hidden details must not move the popup upward by hundreds of pixels.
        assertTrue(container.getMeasuredHeight()-visible>=290);
        container.setMaxHeight(180);container.measure(spec,spec);
        assertTrue(container.getInitialVisibleHeight()<=180);
    }
    @Test public void menusWithoutSubmenusKeepTheirMeasuredHeight() {
        Context context=RuntimeEnvironment.getApplication();ChatScrimPopupContainerLayout container=new ChatScrimPopupContainerLayout(context);
        View content=new View(context);container.addView(content,LayoutHelper.createLinear(200,120));
        int spec=View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.AT_MOST);container.measure(spec,spec);
        assertEquals(120,container.getInitialVisibleHeight());
    }
}
