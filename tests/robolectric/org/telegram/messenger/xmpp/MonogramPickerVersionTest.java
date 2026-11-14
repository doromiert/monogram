package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;import java.util.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import org.telegram.messenger.*;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramFavoriteEmojiTest.Display.class,MonogramFavoriteEmojiTest.Settings.class,MonogramPickerVersionTest.Mesh.class})
public class MonogramPickerVersionTest {
 @Implements(value=com.negzero.monogram.sync.NativeMesh.class,isInAndroidSdk=false)public static class Mesh {
  static boolean existing;static int puts;static long stamp;static Map<String,String> signatures=new HashMap<>();
  @Implementation protected static void __staticInitializer__(){}
  @Implementation public static String signature(long h,String key,String value){if(!value.isEmpty())signatures.put(key,value);return signatures.getOrDefault(key,"");}
  @Implementation public static String inspect(long h,String query){return existing?"[{\"kind\":\"pack-order\",\"owner\":\"version@example.invalid\"}]":"[]";}
  @Implementation public static String putJsonAt(long h,String kind,String owner,String id,String value,long revision){puts++;stamp=revision;existing=true;return "{}";}
 }

 @Before public void setup(){ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();UserConfig.selectedAccount=0;ApplicationLoader.applicationContext.getSharedPreferences("monogram-0",0).edit().putString("jid","version@example.invalid").commit();ApplicationLoader.applicationContext.getSharedPreferences("monogram-picker-revisions",0).edit().clear().commit();MonogramFavoriteEmojiTest.Settings.getGlobalEmojiSettings().edit().clear().commit();}
 @Test public void baselineAndImportsNeverMintNewUserVersions(){
  assertEquals(1,MonogramPickerVersion.publication(0,0,false));assertEquals(0,MonogramPickerVersion.publication(0,0,true));
  MonogramPickerVersion.observe(0,1000);Emoji.applySyncedLibrary(Arrays.asList("🔥"),Arrays.asList("🐢"));assertEquals(1000,MonogramPickerVersion.revision(0));
  assertEquals(0,MonogramPickerVersion.publication(MonogramPickerVersion.revision(0),1000,true));MonogramPickerVersion.observe(0,500);assertEquals(1000,MonogramPickerVersion.revision(0));
 }
 @Test public void onlyRealEmojiMutationsAdvanceStoredAccountRevision(){
  Emoji.setFavoriteEmoji("🔥",true);long first=MonogramPickerVersion.revision(0);assertTrue(first>1000);Emoji.setFavoriteEmoji("🔥",true);assertEquals(first,MonogramPickerVersion.revision(0));
  Emoji.setFavoriteEmoji("🐢",true);long second=MonogramPickerVersion.revision(0);assertTrue(second>first);Emoji.reorderFavoriteEmoji(Arrays.asList("🐢","🔥"));long third=MonogramPickerVersion.revision(0);assertTrue(third>second);
  Emoji.applySyncedLibrary(Arrays.asList("🔥"),Arrays.asList("🐢"));assertEquals(third,MonogramPickerVersion.revision(0));Emoji.addRecentEmoji("😂");assertTrue(MonogramPickerVersion.revision(0)>third);
  assertEquals(0,MonogramPickerVersion.publication(100,200,true));assertEquals(201,MonogramPickerVersion.publication(201,200,true));
 }
 @Test public void adapterPublishesActualEditOnceAndIgnoresImportOrSerializationDrift()throws Exception {
  ApplicationLoader.applicationContext.getSharedPreferences("monogram-device-sync",0).edit().putBoolean("enabled",true).commit();
  org.robolectric.util.ReflectionHelpers.setStaticField(MonogramLanSync.class,"handle",1L);org.robolectric.util.ReflectionHelpers.setStaticField(MonogramLanSync.class,"screenLocked",false);
  Mesh.existing=false;Mesh.puts=0;Mesh.signatures.clear();org.json.JSONObject state=new org.json.JSONObject().put("stickers",new org.json.JSONArray()).put("emojis",new org.json.JSONArray());
  publish(new MonogramSyncLibrary.Snapshot(state,0));assertEquals(1,Mesh.puts);assertEquals(1,Mesh.stamp);
  state.put("emoji_favorites",new org.json.JSONArray().put("🔥"));publish(new MonogramSyncLibrary.Snapshot(state,0));assertEquals(1,Mesh.puts);
  Mesh.signatures.put("picker-user-revision:version@example.invalid","200");publish(new MonogramSyncLibrary.Snapshot(state,200));assertEquals(1,Mesh.puts);
  publish(new MonogramSyncLibrary.Snapshot(state,201));assertEquals(2,Mesh.puts);assertEquals(201,Mesh.stamp);publish(new MonogramSyncLibrary.Snapshot(state,201));assertEquals(2,Mesh.puts);
  Mesh.puts=0;Mesh.signatures.clear();Mesh.existing=true;publish(new MonogramSyncLibrary.Snapshot(state,0));assertEquals(0,Mesh.puts);
 }
 private void publish(MonogramSyncLibrary.Snapshot snapshot){org.robolectric.util.ReflectionHelpers.callStaticMethod(MonogramLanSync.class,"publishLibrary",org.robolectric.util.ReflectionHelpers.ClassParameter.from(long.class,1L),org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,0),org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,"version@example.invalid"),org.robolectric.util.ReflectionHelpers.ClassParameter.from(MonogramSyncLibrary.Snapshot.class,snapshot));}

}
