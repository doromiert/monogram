// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.os.Looper;
import java.util.*;import java.util.concurrent.*;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
/** Portable library preferences map shared UUIDs to the current account's local documents. */
public final class MonogramSyncLibrary {
 private MonogramSyncLibrary(){}
 private static <T>T ui(Callable<T> action)throws Exception {if(Looper.myLooper()==Looper.getMainLooper())return action.call();FutureTask<T> task=new FutureTask<>(action);AndroidUtilities.runOnUIThread(task);return task.get(5,TimeUnit.SECONDS);}
 private static LinkedHashMap<Long,MonogramPacks.Item> items(int account){LinkedHashMap<Long,MonogramPacks.Item> map=new LinkedHashMap<>();for(MonogramPacks.Pack pack:MonogramPacks.packs(account))if(!(pack.source!=null&&pack.source.startsWith("local:replaced:")))for(MonogramPacks.Item item:pack.items)map.put(item.id,item);return map;}
 private static void add(JSONArray array,Set<String> seen,String id){if(id!=null&&!id.isEmpty()&&seen.add(id))array.put(id);}
 private static Object canonical(Object value)throws JSONException {
  if(value instanceof JSONObject){JSONObject old=(JSONObject)value,out=new JSONObject();ArrayList<String> keys=new ArrayList<>();old.keys().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys)out.put(key,canonical(old.get(key)));return out;}
  if(value instanceof JSONArray){JSONArray old=(JSONArray)value,out=new JSONArray();for(int i=0;i<old.length();i++)out.put(canonical(old.get(i)));return out;}return value;
 }
 private static String unicode(String code){return code.replace("\ufe0f", "").replace("\ufe0e", "");}
 private static JSONArray mixed(List<String> codes,Map<Long,MonogramPacks.Item> items){JSONArray values=new JSONArray();Set<String> seen=new HashSet<>();for(String code:codes){if(code.startsWith("animated_")){try{MonogramPacks.Item item=items.get(Long.parseLong(code.substring(9)));if(item!=null)add(values,seen,"item:"+item.syncId);}catch(NumberFormatException ignored){}}else add(values,seen,"unicode:"+unicode(code));}return values;}
 private static void mixedOrder(ArrayList<String> codes,JSONArray order,Map<String,Long> local)throws JSONException {
  if(order==null)return;ArrayList<String> result=new ArrayList<>();for(int i=0;i<order.length();i++){String key=order.getString(i),code=null;if(key.startsWith("unicode:"))code=key.substring(8);else if(key.startsWith("item:")&&local.containsKey(key.substring(5)))code="animated_"+local.get(key.substring(5));if(code!=null)for(String existing:codes)if(unicode(existing).equals(unicode(code))&&!result.contains(existing)){result.add(existing);break;}}for(String code:codes)if(!result.contains(code))result.add(code);codes.clear();codes.addAll(result);
 }
 public static final class Snapshot {public final JSONObject value;public final long revision;Snapshot(JSONObject value,long revision){this.value=value;this.revision=revision;}}
 public static JSONObject export(int account)throws Exception {Snapshot state=snapshot(account);return state==null?null:state.value;}
 public static Snapshot snapshot(int account)throws Exception {
  long before=MonogramPickerVersion.revision(account);
  JSONObject order=MonogramPacks.syncOrder(account);LinkedHashMap<Long,MonogramPacks.Item> items=items(account);
  Snapshot snapshot=ui(()->{
   JSONArray favorites=new JSONArray(),recent=new JSONArray(),emojiFavorites=new JSONArray(),emojiRecent=new JSONArray();Set<String> f=new HashSet<>(),r=new HashSet<>();
   MediaDataController media=MediaDataController.getInstance(account);Emoji.loadRecentEmoji();if(!media.localRecentStickersReady())return null;
   for(TLRPC.Document document:media.getRecentStickers(MediaDataController.TYPE_FAVE)){MonogramPacks.Item item=items.get(document.id);if(item!=null)add(favorites,f,item.syncId);}
   for(TLRPC.Document document:media.getRecentStickers(MediaDataController.TYPE_IMAGE)){MonogramPacks.Item item=items.get(document.id);if(item!=null)add(recent,r,item.syncId);}
   for(String code:Emoji.getFavoriteEmoji()){if(code.startsWith("animated_")){try{MonogramPacks.Item item=items.get(Long.parseLong(code.substring(9)));if(item!=null)add(favorites,f,item.syncId);}catch(NumberFormatException ignored){}}else emojiFavorites.put(unicode(code));}
   Set<String> standardRecent=new HashSet<>();for(String code:Emoji.recentEmoji){if(code.startsWith("animated_")){try{MonogramPacks.Item item=items.get(Long.parseLong(code.substring(9)));if(item!=null)add(recent,r,item.syncId);}catch(NumberFormatException ignored){}}else if(standardRecent.add(unicode(code)))emojiRecent.put(unicode(code));}
   JSONObject state=new JSONObject().put("emojis",order.getJSONArray("emojis")).put("stickers",order.getJSONArray("stickers"));
   if(!items.isEmpty())state.put("favorites",favorites).put("recent",recent);
   if(MessagesController.getGlobalEmojiSettings().contains("monogram_favorite_emoji"))state.put("emoji_favorites",emojiFavorites).put("emoji_favorite_order",mixed(Emoji.getFavoriteEmoji(),items));
   if(Emoji.hasUsedRecentEmoji())state.put("emoji_recent",emojiRecent).put("emoji_recent_order",mixed(Emoji.recentEmoji,items));String pinned=MessagesController.getGlobalEmojiSettings().getString("monogram_synced_pinned_emoji",null);if(pinned!=null)state.put("emoji_pinned",new JSONArray(pinned));return new Snapshot((JSONObject)canonical(state),before);
  });return MonogramPickerVersion.revision(account)==before?snapshot:null;
 }
 public static boolean apply(int account,JSONObject state)throws Exception {return apply(account,state,0);}
 public static boolean apply(int account,JSONObject state,long incomingRevision)throws Exception {
  LinkedHashMap<Long,MonogramPacks.Item> items=items(account);Map<String,Long> local=new HashMap<>();Map<Long,Boolean> emojis=new HashMap<>();
  for(MonogramPacks.Pack pack:MonogramPacks.packs(account))for(MonogramPacks.Item item:pack.items){local.put(item.syncId,item.id);emojis.put(item.id,pack.emojis);}
  for(String field:new String[]{"favorites","recent"}){JSONArray ids=state.optJSONArray(field);if(ids!=null)for(int i=0;i<ids.length();i++)if(!local.containsKey(ids.getString(i)))return false;}
  for(String field:new String[]{"emoji_favorite_order","emoji_recent_order"}){JSONArray keys=state.optJSONArray(field);if(keys!=null)for(int i=0;i<keys.length();i++){String key=keys.getString(i);if(key.startsWith("item:")&&!local.containsKey(key.substring(5)))return false;}}
  if(!MonogramPacks.applySyncOrder(account,state,incomingRevision))return false;
  return ui(()->{
   if(incomingRevision>0&&MonogramPickerVersion.revision(account)>incomingRevision)return false;
   MediaDataController media=MediaDataController.getInstance(account);ArrayList<String> favoriteCodes=new ArrayList<>(),recentCodes=new ArrayList<>();
   for(String field:new String[]{"favorites","recent"}){
    JSONArray ids=state.optJSONArray(field);if(ids==null)continue;ArrayList<TLRPC.Document> documents=new ArrayList<>();ArrayList<String> codes=field.equals("favorites")?favoriteCodes:recentCodes;
    for(int i=0;i<ids.length();i++){long id=local.get(ids.getString(i));if(Boolean.TRUE.equals(emojis.get(id)))codes.add("animated_"+id);else {TLRPC.Document document=MonogramPacks.document(account,id);if(document!=null)documents.add(document);}}
    media.applySyncedLocalStickers(field.equals("favorites")?MediaDataController.TYPE_FAVE:MediaDataController.TYPE_IMAGE,documents);
   }
   for(String field:new String[]{"emoji_favorites","emoji_recent"}){JSONArray values=state.optJSONArray(field);ArrayList<String> codes=field.equals("emoji_favorites")?favoriteCodes:recentCodes;if(values!=null)for(int i=0;i<values.length();i++)codes.add(values.getString(i));}
   for(String code:Emoji.getFavoriteEmoji()){
    if(code.startsWith("animated_")){try{long id=Long.parseLong(code.substring(9));if(!state.has("favorites")||!items.containsKey(id))favoriteCodes.add(code);}catch(NumberFormatException ignored){}}
    else if(!state.has("emoji_favorites"))favoriteCodes.add(code);
   }
   for(String code:Emoji.recentEmoji){
    if(code.startsWith("animated_")){try{long id=Long.parseLong(code.substring(9));if(!state.has("recent")||!items.containsKey(id))recentCodes.add(code);}catch(NumberFormatException ignored){}}
    else if(!state.has("emoji_recent"))recentCodes.add(code);
   }
   mixedOrder(favoriteCodes,state.optJSONArray("emoji_favorite_order"),local);mixedOrder(recentCodes,state.optJSONArray("emoji_recent_order"),local);
   if(state.has("emoji_favorites")||state.has("emoji_recent")||state.has("favorites")||state.has("recent")||state.has("emoji_favorite_order")||state.has("emoji_recent_order"))Emoji.applySyncedLibrary(favoriteCodes,recentCodes);
   if(state.has("emoji_pinned"))MessagesController.getGlobalEmojiSettings().edit().putString("monogram_synced_pinned_emoji",state.getJSONArray("emoji_pinned").toString()).apply();
   MonogramPacks.refresh(account);
   NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.emojiLoaded,true);return true;
  });
 }
}
