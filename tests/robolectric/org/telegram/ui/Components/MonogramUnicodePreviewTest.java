package org.telegram.ui.Components;
import static org.junit.Assert.*;
import android.graphics.drawable.*;
import org.junit.*;import org.junit.runner.RunWith;
import org.robolectric.*;import org.robolectric.annotation.*;
import org.telegram.messenger.*;import org.telegram.ui.ActionBar.Theme;import org.telegram.ui.ContentPreviewViewer;import org.robolectric.util.ReflectionHelpers;import org.telegram.tgnet.TLRPC;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramUnicodePreviewTest.Ui.class,MonogramUnicodePreviewTest.Colors.class})
public class MonogramUnicodePreviewTest {
 @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)public static class Ui {
  @Implementation protected static void __staticInitializer__(){}
  @Implementation public static int dp(float n){return (int)Math.ceil(n);}@Implementation public static boolean isTablet(){return false;}
 }
 @Implements(value=Theme.class,isInAndroidSdk=false)public static class Colors {
  @Implementation protected static void __staticInitializer__(){}
  @Implementation public static int getColor(int key){return 0xff333333;}
  @Implementation public static Drawable createRadSelectorDrawable(int color,int top,int bottom){return new ColorDrawable(color);}
 }
 @Test public void unicodePreviewUsesDisplayedSkinToneAndNeverTreatsCustomOrHeadersAsUnicode(){
  EmojiView.ImageViewEmoji cell=new EmojiView.ImageViewEmoji(RuntimeEnvironment.getApplication());
  cell.setTag("👍");Emoji.emojiColor.put("👍","🏽");assertEquals("👍🏽",cell.getUnicodeEmoji());
  cell.setImageDrawable(new ColorDrawable(0),true);assertEquals("👍",cell.getUnicodeEmoji());assertTrue(cell.isRecentEmoji());
  cell.setSpan(new AnimatedEmojiSpan(42,null));assertNull(cell.getUnicodeEmoji());
  cell.setSpan(null);cell.setTag("Favorite emoji");assertNull(cell.getUnicodeEmoji());cell.setTag(null);assertNull(cell.getUnicodeEmoji());Emoji.emojiColor.clear();
 }
 public static class RecordingViewer extends ContentPreviewViewer {
  String unicode;int content;boolean recent;int opens;
  @Override public void open(TLRPC.Document document,SendMessagesHelper.ImportingSticker sticker,String emoji,String query,TLRPC.BotInlineResult inline,int type,boolean isRecent,Object parent,Theme.ResourcesProvider resources){
   assertNull(document);assertNull(sticker);unicode=emoji;content=type;recent=isRecent;opens++;ReflectionHelpers.setField(this,"isVisible",true);
  }
 }
 @Test public void existingPreviewOpensAndBrowsesUnicodeCellsWithoutDocuments(){
  RecordingViewer viewer=new RecordingViewer();EmojiView.ImageViewEmoji cell=new EmojiView.ImageViewEmoji(RuntimeEnvironment.getApplication());cell.setTag("🔥");
  assertTrue(ReflectionHelpers.callInstanceMethod(viewer,"openEmojiCell",ReflectionHelpers.ClassParameter.from(EmojiView.ImageViewEmoji.class,cell),ReflectionHelpers.ClassParameter.from(Theme.ResourcesProvider.class,null)));
  assertEquals("🔥",viewer.unicode);assertEquals(ContentPreviewViewer.CONTENT_TYPE_EMOJI,viewer.content);
  cell.setTag("☠️");cell.setImageDrawable(new ColorDrawable(0),true);
  assertTrue(ReflectionHelpers.callInstanceMethod(viewer,"openEmojiCell",ReflectionHelpers.ClassParameter.from(EmojiView.ImageViewEmoji.class,cell),ReflectionHelpers.ClassParameter.from(Theme.ResourcesProvider.class,null)));
  assertEquals("☠️",viewer.unicode);assertEquals(2,viewer.opens);assertTrue(viewer.recent);
 }

}
