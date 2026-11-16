package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import android.content.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramPacksTest.Account.class,MonogramPacksTest.App.class,MonogramSyncLibraryTest.Ui.class,MonogramPacksTest.PackUi.class,MonogramPacksTest.EmojiUi.class,MonogramPacksTest.Vault.class,MonogramPacksTest.Logging.class,MonogramFavoriteEmojiTest.Settings.class,MonogramSyncLibraryTest.Media.class,MonogramSyncLibraryTest.Notices.class})
public class MonogramSyncLibraryTest {
 @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)public static class Ui extends MonogramPacksTest.Ui {
  @Implementation public static int dp(float n){return (int)Math.ceil(n);}@Implementation public static boolean isTablet(){return false;}
 }
 @Implements(value=MediaDataController.class,isInAndroidSdk=false)public static class Media {
  static final Map<Integer,ArrayList<TLRPC.Document>> documents=new HashMap<>();
  @Implementation public static MediaDataController getInstance(int account){return ReflectionHelpers.callConstructor(MediaDataController.class,ReflectionHelpers.ClassParameter.from(int.class,account));}
  @Implementation protected void __constructor__(int account){}
  @Implementation public boolean localRecentStickersReady(){return true;}
  @Implementation public ArrayList<TLRPC.Document> getRecentStickers(int type){return new ArrayList<>(documents.getOrDefault(type,new ArrayList<>()));}
  @Implementation public void applySyncedLocalStickers(int type,ArrayList<TLRPC.Document> values){documents.put(type,new ArrayList<>(values));}
 }
 @Implements(value=NotificationCenter.class,isInAndroidSdk=false)public static class Notices {
  @Implementation public static NotificationCenter getGlobalInstance(){return Shadow.newInstanceOf(NotificationCenter.class);}
  @Implementation public void postNotificationName(int id,Object...args){}
 }
 @Test public void olderRemotePickerCannotOverwriteAnUnexportedLocalUserEdit()throws Exception {
  new MonogramPacksTest().setup();MonogramPacksTest.jid="guard@example.org";ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
  ApplicationLoader.applicationContext.getSharedPreferences("monogram-0",0).edit().putString("jid",MonogramPacksTest.jid).commit();
  Emoji.applySyncedLibrary(Arrays.asList("🔥"),Collections.emptyList());MonogramPickerVersion.observe(0,1000);Emoji.setFavoriteEmoji("🐢",true);long local=MonogramPickerVersion.revision(0);
  JSONObject remote=new JSONObject().put("stickers",new JSONArray()).put("emojis",new JSONArray()).put("emoji_favorites",new JSONArray().put("☠"));
  assertFalse(MonogramSyncLibrary.apply(0,remote,1000));assertEquals(Arrays.asList("🔥","🐢"),Emoji.getFavoriteEmoji());assertEquals(local,MonogramPickerVersion.revision(0));
  assertTrue(MonogramSyncLibrary.apply(0,remote,local+1));assertTrue(Emoji.isFavoriteEmoji("☠️"));assertEquals(local,MonogramPickerVersion.revision(0));
 }
 @Test public void freshDefaultsDoNotReplaceRemotePreferences()throws Exception {
  new MonogramPacksTest().setup();MonogramPacksTest.jid="empty-library-sync@example.org";Media.documents.clear();
  MonogramFavoriteEmojiTest.Settings.getGlobalEmojiSettings().edit().clear().commit();ReflectionHelpers.setStaticField(Emoji.class,"recentEmojiLoaded",false);
  JSONObject state=MonogramSyncLibrary.export(0);assertFalse(state.has("favorites"));assertFalse(state.has("recent"));assertFalse(state.has("emoji_favorites"));assertFalse(state.has("emoji_recent"));
  assertTrue(state.has("stickers"));assertTrue(state.has("emojis"));assertFalse(Emoji.recentEmoji.isEmpty());
 }
 @Test public void favoritesAndRecentsMapSharedIdsAndPreserveMixedOrder()throws Exception {
  new MonogramPacksTest().setup();MonogramPacksTest.jid="library-sync@example.org";Media.documents.clear();
  MonogramFavoriteEmojiTest.Settings.getGlobalEmojiSettings().edit().clear().commit();Emoji.applySyncedLibrary(Collections.emptyList(),Collections.emptyList());
  String scope=ReflectionHelpers.callStaticMethod(MonogramPacks.class,"scope",ReflectionHelpers.ClassParameter.from(int.class,0));ArrayList<MonogramPacks.Pack> packs=new ArrayList<>();
  for(int i=0;i<2;i++){MonogramPacks.Pack pack=new MonogramPacks.Pack();pack.id=0x4d50001000000001L+i;pack.syncId=UUID.randomUUID().toString();pack.name="Pack "+i;pack.source="local:fixture";pack.emojis=i==1;MonogramPacks.Item item=new MonogramPacks.Item();item.id=0x4d50002000000001L+i;item.syncId=UUID.randomUUID().toString();item.name="Wave";item.emoji="👋";item.mime="image/png";item.file="art"+i+".png";item.width=item.height=1;pack.items.add(item);packs.add(pack);}
  save(scope,packs);long sticker=packs.get(0).items.get(0).id,custom=packs.get(1).items.get(0).id;
  Media.documents.put(MediaDataController.TYPE_FAVE,new ArrayList<>(Arrays.asList(MonogramPacks.document(0,sticker))));Media.documents.put(MediaDataController.TYPE_IMAGE,new ArrayList<>(Arrays.asList(MonogramPacks.document(0,sticker))));
  Emoji.applySyncedLibrary(Arrays.asList("🔥","animated_"+custom,"🐢"),Arrays.asList("animated_"+custom,"😂"));JSONObject state=MonogramSyncLibrary.export(0);
  Emoji.setFavoriteEmoji("☠️",true);JSONObject symbols=MonogramSyncLibrary.export(0);assertEquals("☠",symbols.getJSONArray("emoji_favorites").getString(symbols.getJSONArray("emoji_favorites").length()-1));
  assertEquals("unicode:🔥",state.getJSONArray("emoji_favorite_order").getString(0));assertEquals("item:"+packs.get(1).items.get(0).syncId,state.getJSONArray("emoji_favorite_order").getString(1));
  for(MonogramPacks.Pack pack:packs){pack.id+=100;pack.items.get(0).id+=100;}save(scope,packs);Emoji.applySyncedLibrary(Collections.emptyList(),Collections.emptyList());Media.documents.clear();
  int refreshed=MonogramPacksTest.PackUi.refreshes;assertTrue(MonogramSyncLibrary.apply(0,state));assertEquals(refreshed+1,MonogramPacksTest.PackUi.refreshes);assertEquals(sticker+100,Media.documents.get(MediaDataController.TYPE_FAVE).get(0).id);assertEquals(Arrays.asList("🔥","animated_"+(custom+100),"🐢"),Emoji.getFavoriteEmoji());assertEquals(Arrays.asList("animated_"+(custom+100),"😂"),Emoji.recentEmoji);
  state.getJSONArray("favorites").put(UUID.randomUUID().toString());assertFalse(MonogramSyncLibrary.apply(0,state));assertEquals(sticker+100,Media.documents.get(MediaDataController.TYPE_FAVE).get(0).id);
 }
 private void save(String scope,ArrayList<MonogramPacks.Pack> packs){ReflectionHelpers.callStaticMethod(MonogramPacks.class,"write",ReflectionHelpers.ClassParameter.from(String.class,scope),ReflectionHelpers.ClassParameter.from(ArrayList.class,packs));}
}
