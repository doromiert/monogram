package org.telegram.messenger;

import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.*;
import android.text.style.StyleSpan;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramSystemEmojiTest.Device.class,MonogramSystemEmojiTest.Config.class})
public class MonogramSystemEmojiTest {
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Device {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static int dp(float value){return Math.round(value);}
        @Implementation public static boolean isTablet(){return false;}
    }
    @Implements(value=SharedConfig.class,isInAndroidSdk=false)
    public static class Config {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static int getDevicePerformanceClass(){return 2;}
    }
    @Before public void setup(){ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();SharedConfig.useSystemEmoji=false;AndroidUtilities.density=1;}
    @After public void cleanup(){SharedConfig.useSystemEmoji=false;}
    @Test public void pickerUsesDeviceFontForSimpleAndCompoundEmoji(){
        SharedConfig.useSystemEmoji=true;
        for(String code:new String[]{"😀","❤️","👍🏽","🫱🏻‍🫲🏽"}){
            Emoji.EmojiDrawable small=Emoji.getEmojiDrawable(code);assertTrue(code,small instanceof Emoji.SystemEmojiDrawable);assertTrue(small.isLoaded());
            Drawable big=Emoji.getEmojiBigDrawable(code);assertTrue(big instanceof Emoji.SystemEmojiDrawable);assertTrue(big.getBounds().width()>small.getBounds().width());
        }
    }
    @Test public void switchingToSystemRemovesCachedTelegramSpansButKeepsFormattingAndCustomEmoji(){
        SpannableString text=new SpannableString("😀 custom");
        Emoji.EmojiSpan ordinary=new Emoji.EmojiSpan(Emoji.getEmojiDrawable("😀"),0,new Paint().getFontMetricsInt());text.setSpan(ordinary,0,2,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        StyleSpan bold=new StyleSpan(android.graphics.Typeface.BOLD);text.setSpan(bold,0,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        AnimatedEmojiSpan custom=new AnimatedEmojiSpan(123L,new Paint().getFontMetricsInt());text.setSpan(custom,3,9,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        SharedConfig.useSystemEmoji=true;CharSequence result=Emoji.replaceEmoji(text,new Paint().getFontMetricsInt(),true,null);
        assertEquals(text.toString(),result.toString());assertEquals(0,((Spanned)result).getSpans(0,result.length(),Emoji.EmojiSpan.class).length);
        assertEquals(1,((Spanned)result).getSpans(0,result.length(),StyleSpan.class).length);assertEquals(1,((Spanned)result).getSpans(0,result.length(),AnimatedEmojiSpan.class).length);
        assertEquals("Copy mode must not mutate the original",1,text.getSpans(0,text.length(),Emoji.EmojiSpan.class).length);
    }
    @Test public void automaticTelegramAnimationsAreRemovedButExplicitCustomEmojiSurvive(){
        SpannableString text=new SpannableString("😀 😀");AnimatedEmojiSpan automatic=new AnimatedEmojiSpan(123L,new Paint().getFontMetricsInt());automatic.standard=true;
        AnimatedEmojiSpan custom=new AnimatedEmojiSpan(123L,new Paint().getFontMetricsInt());text.setSpan(automatic,0,2,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text.setSpan(custom,3,5,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        SharedConfig.useSystemEmoji=true;Emoji.replaceEmoji(text,new Paint().getFontMetricsInt(),false,null);assertArrayEquals(new AnimatedEmojiSpan[]{custom},text.getSpans(0,text.length(),AnimatedEmojiSpan.class));
    }
    @Test public void turningSystemModeOffRestoresBundledEmoji(){
        SharedConfig.useSystemEmoji=true;assertTrue(Emoji.getEmojiDrawable("😀") instanceof Emoji.SystemEmojiDrawable);
        SharedConfig.useSystemEmoji=false;assertTrue(Emoji.getEmojiDrawable("😀") instanceof Emoji.SimpleEmojiDrawable);
    }
}
