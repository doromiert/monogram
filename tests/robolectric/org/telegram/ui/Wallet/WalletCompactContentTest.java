package org.telegram.ui.Wallet;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.telegram.messenger.AndroidUtilities;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={WalletCompactContentTest.Dimensions.class})
public class WalletCompactContentTest {
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Dimensions {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static int dp(float value){return (int)Math.ceil(value);}
    }
    @Test public void compactBalanceGlyphsKeepUniformScaleAndFillNarrowAndWideViewports()throws Exception {
        Class<?> type=Class.forName("org.telegram.ui.Wallet.WalletCardView$ProjectedFaceLayout");
        Constructor<?> constructor=type.getDeclaredConstructor(Context.class);constructor.setAccessible(true);
        FrameLayout face=(FrameLayout)constructor.newInstance(RuntimeEnvironment.getApplication());
        Method compact=type.getDeclaredMethod("setCompactFace");compact.setAccessible(true);compact.invoke(face);
        Field contentField=type.getDeclaredField("content");contentField.setAccessible(true);View content=(View)contentField.get(face);
        for(int height:new int[]{40,48,64})for(int width:new int[]{240,336,600,900}) {
            face.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            face.layout(0,0,width,height);
            assertEquals("Balance glyphs must not stretch horizontally",content.getScaleY(),content.getScaleX(),.00001f);
            assertEquals("The uniformly scaled content must still fill the card width",width,content.getMeasuredWidth()*content.getScaleX(),1f);
            assertEquals(height,content.getMeasuredHeight()*content.getScaleY(),1f);
        }
    }
}
