// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import java.util.*;import java.util.concurrent.*;import org.json.*;
import org.telegram.messenger.*;import org.telegram.utils.settings.MonogramAppearance;
/** Local dialog IDs never cross the wire; explicit members/pins use canonical JIDs. */
final class MonogramSyncFolders {
 private static final String[] TYPES={"contacts","non_contacts","groups","channels","bots"};
 private static final class Row{int id,flags;String name,icon;ArrayList<Long> include,exclude,pinned;}
 private static <T>T ui(Callable<T> task)throws Exception {CompletableFuture<T> done=new CompletableFuture<>();AndroidUtilities.runOnUIThread(()->{try{done.complete(task.call());}catch(Exception e){done.completeExceptionally(e);}});try{return done.get(3,TimeUnit.SECONDS);}catch(ExecutionException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
 static JSONObject export(int account)throws Exception {
  ArrayList<Row> rows=ui(()->{MessagesController mc=MessagesController.getInstance(account);if(!mc.dialogFiltersLoaded&&!UserConfig.getInstance(account).filtersLoaded)return null;ArrayList<Row> result=new ArrayList<>();
   for(MessagesController.DialogFilter f:mc.dialogFilters){Row r=new Row();r.id=f.id;r.flags=f.flags;r.name=f.isDefault()?"All":f.name;r.icon=MonogramFolderIcons.syncName(account,f.id);r.include=new ArrayList<>(f.alwaysShow);r.exclude=new ArrayList<>(f.neverShow);r.pinned=new ArrayList<>();ArrayList<Long> pinned=new ArrayList<>();for(int i=0;i<f.pinnedDialogs.size();i++)pinned.add(f.pinnedDialogs.keyAt(i));pinned.sort(Comparator.comparingInt(f.pinnedDialogs::get));r.pinned.addAll(pinned);result.add(r);}return result;});
  if(rows==null)return null;JSONObject rules=new JSONObject();JSONArray order=new JSONArray(),rooms=new JSONArray();Set<String> roomIds=new TreeSet<>();MonogramController controller=MonogramController.get(account);
  for(Row r:rows){order.put(r.name);if(r.id==0)continue;if(rules.has(r.name)||r.name.equals("All"))throw new IllegalStateException("Give each folder a unique name before syncing.");JSONArray types=new JSONArray();for(int i=0;i<TYPES.length;i++)if((r.flags&(1<<i))!=0)types.put(TYPES[i]);
   JSONObject rule=new JSONObject().put("types",types).put("exclude_muted",(r.flags&32)!=0).put("exclude_read",(r.flags&64)!=0).put("exclude_archived",(r.flags&128)!=0).put("icon",r.icon).put("pinned",jids(controller,r.pinned,roomIds)).put("include",jids(controller,r.include,roomIds)).put("exclude",jids(controller,r.exclude,roomIds));rules.put(r.name,rule);
  }
  boolean all=false;for(int i=0;i<order.length();i++)if(order.getString(i).equals("All"))all=true;if(!all)order.put("All");for(String room:roomIds)rooms.put(room);
  return new JSONObject().put("rules",rules).put("order",order).put("all_hidden",MonogramAppearance.hideAllChats.get()).put("rooms",rooms);
 }
 private static JSONArray jids(MonogramController controller,List<Long> members,Set<String> rooms){JSONArray result=new JSONArray();for(long member:members){String jid=controller.jid(member);if(jid==null||jid.isEmpty())continue;result.put(jid);if(member<0)rooms.add(jid);}return result;}
 static void apply(int account,JSONObject value)throws Exception {
  JSONObject rules=value.getJSONObject("rules");JSONArray order=value.getJSONArray("order");MonogramController controller=MonogramController.get(account);String owner=controller.accountJid();Set<String> rooms=new HashSet<>();JSONArray roomList=value.optJSONArray("rooms");if(roomList!=null)for(int i=0;i<roomList.length();i++)rooms.add(roomList.getString(i));
  ArrayList<MessagesController.DialogFilter> next=new ArrayList<>();Map<String,String> icons=new HashMap<>();
  Map<String,Integer> previous=ui(()->{Map<String,Integer> ids=new HashMap<>();for(MessagesController.DialogFilter f:MessagesController.getInstance(account).dialogFilters)if(!f.isDefault())ids.put(f.name,f.id);return ids;});Set<Integer> used=new HashSet<>(previous.values());int free=2;
  for(int i=0;i<order.length();i++){String name=order.getString(i);MessagesController.DialogFilter f=new MessagesController.DialogFilter();f.name=name;f.order=i;f.id=0;
   if(!name.equals("All")){Integer id=previous.get(name);while(used.contains(free))free++;f.id=id==null?free++:id;used.add(f.id);JSONObject rule=rules.getJSONObject(name);JSONArray types=rule.getJSONArray("types");for(int t=0;t<types.length();t++)for(int n=0;n<TYPES.length;n++)if(TYPES[n].equals(types.getString(t)))f.flags|=1<<n;
    if(rule.getBoolean("exclude_muted"))f.flags|=32;if(rule.getBoolean("exclude_read"))f.flags|=64;if(rule.getBoolean("exclude_archived"))f.flags|=128;
    members(controller,rule.getJSONArray("include"),rooms,f.alwaysShow);members(controller,rule.getJSONArray("exclude"),rooms,f.neverShow);JSONArray pins=rule.optJSONArray("pinned");if(pins!=null)for(int p=0;p<pins.length();p++){String jid=pins.getString(p);f.pinnedDialogs.put(controller.syncFolderPeer(jid,rooms.contains(jid)),p);}
    icons.put(name,rule.optString("icon",""));
   }next.add(f);
  }
  ui(()->{if(!owner.equals(controller.accountJid()))throw new IllegalStateException("Account changed.");MessagesController mc=MessagesController.getInstance(account);MessagesStorage storage=MessagesStorage.getInstance(account);
   for(MessagesController.DialogFilter old:new ArrayList<>(mc.dialogFilters))if(!old.isDefault()&&next.stream().noneMatch(f->f.id==old.id))storage.deleteDialogFilter(old);
   mc.dialogFilters.clear();mc.dialogFiltersById.clear();mc.selectDialogFilter(null,0);mc.selectDialogFilter(null,1);
   for(MessagesController.DialogFilter f:next){mc.dialogFilters.add(f);mc.dialogFiltersById.put(f.id,f);storage.saveDialogFilter(f,false,true);if(f.id!=0)MonogramFolderIcons.importSyncName(account,f.id,icons.get(f.name));}
   MonogramAppearance.hideAllChats.set(value.getBoolean("all_hidden"));storage.saveDialogFiltersOrder();mc.dialogFiltersLoaded=true;mc.sortDialogs(null);NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);return true;});
  // Wait for SQLite's queued writes before marking the portable record applied.
  CompletableFuture<Boolean> saved=new CompletableFuture<>();MessagesStorage.getInstance(account).getStorageQueue().postRunnable(()->saved.complete(true));saved.get(3,TimeUnit.SECONDS);
 }
 private static void members(MonogramController controller,JSONArray list,Set<String> rooms,List<Long> result)throws Exception {for(int i=0;i<list.length();i++){String jid=list.getString(i);result.add(controller.syncFolderPeer(jid,rooms.contains(jid)));}}
}
