// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

/** Local attachment compositions; native picker entries and the native durable outbox remain authoritative. */
public final class MonogramMediaDrafts {
    private MonogramMediaDrafts() {}
    public static final String BATCH="monogram-draft-batch",ITEM="monogram-draft-item",OWNER="monogram-draft-owner";
    private static final ExecutorService WRITER=Executors.newSingleThreadExecutor(),READER=Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String,JSONObject> latest=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,AtomicLong> revisions=new ConcurrentHashMap<>();
    private static final Set<String> loggedOut=ConcurrentHashMap.newKeySet();
    private static final Set<String> cleared=ConcurrentHashMap.newKeySet(),protectedPaths=ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String,String> runtimeEpochs=new ConcurrentHashMap<>();
    private static final Set<Integer> observedAccounts=ConcurrentHashMap.newKeySet();
    private static final NotificationCenter.NotificationCenterDelegate completionObserver=(id,account,args) -> collectCompleted(account);
    private static final AtomicLong gcFence=new AtomicLong();
    private static volatile Session active;
    public static final class Owner {
        final int account;final long dialog;final String jid,peer,key,epoch;
        Owner(int account,long dialog,String jid,String peer) throws Exception {
            if(jid.isEmpty() || peer.isEmpty()) throw new IllegalArgumentException("Missing draft owner");
            this.account=account;this.dialog=dialog;this.jid=jid;this.peer=peer;this.epoch=ownershipEpoch(account,jid);this.key=hash(account+"\n"+jid+"\n"+peer);
        }
        public static Owner current(int account,long dialog) {
            try { MonogramController controller=MonogramController.get(account);return new Owner(account,dialog,controller.accountJid(),controller.jid(dialog)); }
            catch(Exception unavailable) { return null; }
        }
        boolean current() { if(loggedOut.contains(account+"\n"+jid)) return false;Owner current=current(account,dialog);return current!=null && key.equals(current.key) && epoch.equals(current.epoch); }
        File directory() { return new File(scopeDirectory(account,jid),key); }
        AtomicFile manifest() { return new AtomicFile(new File(directory(),"draft.json")); }
    }
    public static final class Restored {
        public final ArrayList<MediaController.PhotoEntry> photos=new ArrayList<>();
        public String caption="",kind="photo";public final ArrayList<String> files=new ArrayList<>();public final ArrayList<MediaController.AudioEntry> audios=new ArrayList<>();
        public final ArrayList<Object> contacts=new ArrayList<>();public final ArrayList<MessageObject> forwards=new ArrayList<>();
        public ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();public boolean above;public int missing;
    }
    public static final class Session {
        public final Owner owner;
        private final Consumer<String> errors;
        private volatile boolean closed,sending,pickerOpen=true;private boolean handoffCaptured,handoffOpen;
        private String batch,kind="photo";private final String capture=UUID.randomUUID().toString();
        private JSONArray missingEntries=new JSONArray();
        private final Map<Integer,String> itemTokens=new HashMap<>();private final Set<String> referenceClaims=new HashSet<>();
        private Session(Owner owner,Consumer<String> errors) { this.owner=owner;this.errors=errors; }
        public boolean active() { return !closed && active==this && owner.current(); }
        public void close() { closed=true;pickerOpen=false;gcFence.incrementAndGet(); }
        public void pickerClosed() { pickerOpen=false;gcFence.incrementAndGet();collectCompleted(owner.account); }
        public void restore(Consumer<Restored> result) {
            READER.execute(() -> {
                Restored restored=new Restored();JSONArray unavailable=new JSONArray();
                try {
                    JSONObject state=load(owner);if(state==null) return;
                    checkOwner(owner,state);restored.kind=state.optString("kind","photo");restored.caption=state.optString("caption");restored.above=state.optBoolean("above");restored.entities=readEntities(state.optString("entities"));
                    Set<String> accepted=strings(state.optJSONArray("accepted"));JSONArray entries=state.getJSONArray("photos");
                    for(int i=0;i<entries.length();i++) { JSONObject entry=entries.getJSONObject(i);
                        if(entry.optString("selection").equals("caption")) { if(accepted.contains(entry.optString("item"))) restored.caption="";continue; }
                        if(accepted.contains(entry.optString("item"))) continue;
                        try {
                            if(entry.has("contact")) { restored.contacts.add(MonogramSelectionDrafts.contact(entry.getJSONObject("contact")));continue; }
                            if(entry.has("forward")) { restored.forwards.add(MonogramSelectionDrafts.message(owner.account,entry.getJSONObject("forward")));continue; }
                            MediaController.PhotoEntry photo=decode(entry,owner);
                            if(restored.kind.equals("document")) restored.files.add(photo.path);
                            else if(restored.kind.equals("audio")) restored.audios.add(decodeAudio(entry,photo.path));
                            else restored.photos.add(photo);
                        } catch(Exception missing) { restored.missing++;unavailable.put(entry); }
                    }
                    if(restored.photos.isEmpty() && restored.files.isEmpty() && restored.audios.isEmpty() && restored.contacts.isEmpty() && restored.forwards.isEmpty() && entries.length()>0 && !state.optBoolean("separateCaption")) restored.caption="";
                } catch(Exception invalid) { restored.missing++; }
                finally { AndroidUtilities.runOnUIThread(() -> { if(active()) { missingEntries=unavailable;kind=restored.kind;result.accept(restored); } }); }
            });
        }
        public void save(Map<Object,Object> photos,List<Object> order,CharSequence caption,boolean above,boolean beginSend) {
            synchronized(gcFence) { saveLocked(photos,order,caption,above,beginSend); }
        }
        private void saveLocked(Map<Object,Object> photos,List<Object> order,CharSequence caption,boolean above,boolean beginSend) {
            if(!active() || !pickerOpen || sending && !beginSend) return;
            if(!kind.equals("photo") && order.isEmpty()) return;if(!kind.equals("photo")) missingEntries=new JSONArray();kind="photo";
            try {
                if(beginSend) { batch=UUID.randomUUID().toString();itemTokens.clear(); }
                JSONObject state=new JSONObject().put("kind",kind).put("version",1).put("account",owner.account).put("jid",owner.jid).put("peer",owner.peer).put("epoch",owner.epoch).put("above",above);
                CharSequence[] text={caption==null ? "" : new android.text.SpannableStringBuilder(caption)};ArrayList<TLRPC.MessageEntity> entities=MediaDataController.getInstance(owner.account).getEntities(text,false);
                state.put("caption",text[0].toString()).put("entities",entities(entities));JSONArray entries=new JSONArray();
                for(Object key:order) {
                    Object value=photos.get(key);if(!(value instanceof MediaController.PhotoEntry)) throw new IOException("This selection cannot be saved as a media draft");
                    MediaController.PhotoEntry photo=(MediaController.PhotoEntry)value;JSONObject entry=encode(photo,owner).put("capture",capture);
                    if(beginSend) { String token=UUID.randomUUID().toString();itemTokens.put(photo.imageId,token);entry.put("item",token); }
                    entries.put(entry);protect(entry);
                }
                JSONObject previous=load(owner);if(previous!=null && !beginSend && previous.optString("kind","photo").equals("photo")) {
                    JSONArray old=previous.optJSONArray("photos");Set<String> selected=new HashSet<>();for(int i=0;i<entries.length();i++) selected.add(entries.getJSONObject(i).optString("draftId"));
                    if(old!=null) for(int i=0;i<old.length();i++) { JSONObject pending=old.getJSONObject(i);if(pending.optBoolean("editorOnly") && !selected.contains(pending.optString("draftId"))) entries.put(pending); }
                }
                for(int i=0;i<missingEntries.length();i++) entries.put(missingEntries.getJSONObject(i));
                if(entries.length()==0 && text[0].length()==0) { discard();return; }
                state.put("photos",entries);if(beginSend) { sending=true;state.put("batch",batch); }
                publish(owner,state,errors);
            } catch(Exception failed) { report(errors,"Could not save the media draft. Keep the picker open and try again."); }
        }
        public void saveFiles(String type,List<String> paths,List<MediaController.AudioEntry> audios,CharSequence caption,boolean above,boolean beginSend) {
            saveSelections(type,paths,audios,null,null,caption,above,beginSend);
        }
        public void saveSelections(String type,List<String> paths,List<MediaController.AudioEntry> audios,List<Object> contacts,List<MessageObject> forwards,CharSequence caption,boolean above,boolean beginSend) {
            synchronized(gcFence) { saveSelectionsLocked(type,paths,audios,contacts,forwards,caption,above,beginSend); }
        }
        private void saveSelectionsLocked(String type,List<String> paths,List<MediaController.AudioEntry> audios,List<Object> contacts,List<MessageObject> forwards,CharSequence caption,boolean above,boolean beginSend) {
            if(!active() || !pickerOpen || sending && !beginSend || !kind.equals(type) && paths.isEmpty() && (contacts==null || contacts.isEmpty()) && (forwards==null || forwards.isEmpty())) return;
            try {
                if(!kind.equals(type)) missingEntries=new JSONArray();kind=type;
                if(beginSend) { batch=UUID.randomUUID().toString();handoffCaptured=false;handoffOpen=true;referenceClaims.clear(); }
                CharSequence[] text={caption==null ? "" : new android.text.SpannableStringBuilder(caption)};
                JSONObject state=new JSONObject().put("kind",kind).put("version",1).put("account",owner.account).put("jid",owner.jid).put("peer",owner.peer).put("epoch",owner.epoch).put("above",above)
                        .put("entities",entities(MediaDataController.getInstance(owner.account).getEntities(text,false))).put("caption",text[0].toString());
                JSONArray entries=new JSONArray();int index=0;
                for(String path:paths) {
                    MediaController.PhotoEntry nativeEntry=new MediaController.PhotoEntry(0,index++,0,path,0,0,false,0,0,new File(path).length());
                    JSONObject entry=encode(nativeEntry,owner).put("capture",capture).put("originalSource",path).put("account",owner.account);
                    if(audios!=null) { if(audios.get(index-1).messageObject.currentAccount!=owner.account) throw new IOException("Music selection belongs to another account");entry.put("audio",encodeAudio(audios.get(index-1))); }
                    if(beginSend) entry.put("item",UUID.randomUUID().toString());
                    entries.put(entry);protect(entry);
                }
                if(contacts!=null) for(int i=0;i<contacts.size();i++) {
                    JSONObject entry=new JSONObject().put("contact",MonogramSelectionDrafts.contact(contacts.get(i))).put("selection","contact:"+i);
                    if(beginSend) entry.put("item",UUID.randomUUID().toString());entries.put(entry);
                }
                if(forwards!=null) for(MessageObject forward:forwards) {
                    if(forward.currentAccount!=owner.account) throw new IOException("Forward selection belongs to another account");
                    JSONObject entry=new JSONObject().put("forward",MonogramSelectionDrafts.message(forward)).put("selection","forward:"+MonogramSelectionDrafts.messageKey(forward));
                    if(beginSend) entry.put("item",UUID.randomUUID().toString());entries.put(entry);
                }
                if(text[0].length()>0 && (contacts!=null || forwards!=null && !forwards.isEmpty())) {
                    JSONObject entry=new JSONObject().put("selection","caption");if(beginSend) entry.put("item",UUID.randomUUID().toString());entries.put(entry);state.put("separateCaption",true);
                }
                for(int i=0;i<missingEntries.length();i++) entries.put(missingEntries.getJSONObject(i));
                if(entries.length()==0 && text[0].length()==0) { discard();return; }
                state.put("photos",entries);if(beginSend) { sending=true;state.put("batch",batch); }
                publish(owner,state,errors);
            } catch(Exception failed) { report(errors,"Could not save the attachment draft. Keep the picker open and try again."); }
        }
        public void tag(SendMessagesHelper.SendingMediaInfo info,MediaController.PhotoEntry photo) {
            if(!owner.current() || batch==null || !itemTokens.containsKey(photo.imageId)) return;
            if(info.params==null) info.params=new HashMap<>();info.params.put(BATCH,batch);info.params.put(ITEM,itemTokens.get(photo.imageId));info.params.put(OWNER,owner.key);
        }
        public void discard() { if(!active()) return;sending=false;batch=null;itemTokens.clear();clear(owner); }
    }
    public static Session bind(int account,long dialog,Consumer<String> errors) {
        synchronized(gcFence) { return bindLocked(account,dialog,errors); }
    }
    private static Session bindLocked(int account,long dialog,Consumer<String> errors) {
        Owner owner=Owner.current(account,dialog);if(owner==null) return null;loggedOut.remove(account+"\n"+owner.jid);gcFence.incrementAndGet();
        if(observedAccounts.add(account)) {
            NotificationCenter.getInstance(account).addObserver(completionObserver,NotificationCenter.messageReceivedByServer);
            NotificationCenter.getInstance(account).addObserver(completionObserver,NotificationCenter.messagesDeleted);
        }
        collectCompleted(account);
        Session previous=active;if(previous!=null) previous.close();Session session=new Session(owner,errors);active=session;return session;
    }
    /** Captured on the native preparation call before its worker starts; never tags unrelated later sends. */
    public static Map<String,HashMap<String,String>> captureFileHandoff(int account,long dialog,List<String> paths) {
        Map<String,HashMap<String,String>> result=new HashMap<>();Session session=active;
        if(session==null || session.owner.account!=account || session.owner.dialog!=dialog || !session.owner.current() || !session.sending || session.kind.equals("photo") || session.handoffCaptured || paths==null) return result;
        try {
            JSONObject state=load(session.owner);if(state==null || !session.batch.equals(state.optString("batch"))) return result;
            JSONArray entries=state.getJSONArray("photos");
            for(String path:paths) for(int i=0;i<entries.length();i++) {
                JSONObject entry=entries.getJSONObject(i);if(!path.equals(entry.optString("originalSource")) && !path.equals(entry.optString("path"))) continue;
                HashMap<String,String> params=new HashMap<>();params.put(BATCH,session.batch);params.put(ITEM,entry.getString("item"));params.put(OWNER,session.owner.key);result.put(path,params);break;
            }
        } catch(Exception unavailable) { /* Leave the composition recoverable if native preparation cannot be matched. */ }
        if(!result.isEmpty()) { session.handoffCaptured=true;gcFence.incrementAndGet(); }
        return result;
    }
    public static HashMap<String,String> captureReference(int account,long dialog,String key) {
        Session session=active;if(session==null || session.owner.account!=account || session.owner.dialog!=dialog || !session.owner.current() || !session.sending || !session.handoffOpen) return null;
        try {
            JSONObject state=load(session.owner);if(state==null || !session.batch.equals(state.optString("batch"))) return null;JSONArray entries=state.getJSONArray("photos");
            for(int i=0;i<entries.length();i++) { JSONObject entry=entries.getJSONObject(i);if(!key.equals(entry.optString("selection"))) continue;String item=entry.getString("item");if(!session.referenceClaims.add(item)) return null;gcFence.incrementAndGet();
                HashMap<String,String> params=new HashMap<>();params.put(BATCH,session.batch);params.put(ITEM,item);params.put(OWNER,session.owner.key);return params;
            }
        } catch(Exception unavailable) { /* Retain draft on failed handoff matching. */ }return null;
    }
    public static HashMap<String,String> captureForward(int account,long dialog,MessageObject source) { return active!=null && active.kind.equals("document") ? captureReference(account,dialog,"forward:"+MonogramSelectionDrafts.messageKey(source)) : null; }
    public static HashMap<String,String> captureContact(int account,long dialog,int index) { return active!=null && active.kind.equals("contacts") ? captureReference(account,dialog,"contact:"+index) : null; }
    public static HashMap<String,String> captureCaption(int account,long dialog,String kind) { return active!=null && active.kind.equals(kind) ? captureReference(account,dialog,"caption") : null; }
    public static void finishReferenceHandoff() { Session session=active;if(session!=null) session.handoffOpen=false; }
    /** External results become a native composition, never an automatic send or another queue. */
    public static boolean recordExternal(Owner owner,List<MediaController.PhotoEntry> inputs,String kind,Consumer<String> errors,Runnable ready) {
        synchronized(gcFence) { try {
            if(owner==null || !owner.current()) return false;
            JSONArray entries=new JSONArray();String capture=UUID.randomUUID().toString();JSONObject previous=load(owner);
            if(previous!=null && (previous.has("batch") || !kind.equals(previous.optString("kind","photo")))) {
                report(errors,"Finish sending or discard the existing attachment draft, then reopen the attachment picker to import this selection. The returned selection is retained for recovery.");return false;
            }
            Session picker=active;if(picker!=null && picker.owner.key.equals(owner.key)) picker.close();
            if(previous!=null && !previous.has("batch") && kind.equals(previous.optString("kind","photo"))) { JSONArray old=previous.getJSONArray("photos");for(int i=0;i<old.length();i++) entries.put(old.getJSONObject(i)); }
            for(MediaController.PhotoEntry photo:inputs) { JSONObject entry=encode(photo,owner).put("capture",capture).put("originalUri",photo.path);if(kind.equals("photo")) entry.put("resolveMedia",true);entries.put(entry);protect(entry); }
            if(entries.length()==0) return false;
            JSONObject state=new JSONObject().put("kind",kind).put("version",1).put("account",owner.account).put("jid",owner.jid).put("peer",owner.peer).put("epoch",owner.epoch).put("photos",entries).put("caption","").put("entities",entities(null));
            if(previous!=null && !previous.has("batch") && kind.equals(previous.optString("kind","photo"))) state.put("caption",previous.optString("caption")).put("entities",previous.optString("entities",entities(null))).put("above",previous.optBoolean("above"));
            return publish(owner,state,errors,ready);
        } catch(Exception failed) { report(errors,"Could not preserve the external selection. Select the files again.");return false; } }
    }
    static boolean hasComposition(Owner owner) {
        try { return load(owner)!=null; } catch(Exception uncertain) { return true; }
    }
    public static void deactivate() { Session session=active;if(session!=null) session.close();active=null; }
    public static boolean protectedFile(String path) { return path!=null && protectedPaths.contains(path); }
    public static void mergeParams(HashMap<String,String> destination,HashMap<String,String> source) {
        if(source==null) return;for(String key:Arrays.asList(BATCH,ITEM,OWNER)) if(source.containsKey(key)) destination.put(key,source.get(key));
    }
    /** reserveFile follows native putMessages; the callback below runs behind that transaction on its storage queue. */
    public static void accepted(int account,TLRPC.Message message) {
        if(message.params==null || !message.params.containsKey(BATCH) || !message.params.containsKey(ITEM)) return;
        Owner owner=Owner.current(account,message.dialog_id);if(owner==null || !owner.key.equals(message.params.get(OWNER))) return;
        final String batch=message.params.get(BATCH),item=message.params.get(ITEM);final int id=message.id;
        MessagesStorage.getInstance(account).getMonogramOutbox(message.dialog_id,rows -> {
            boolean durable=false;
            for(TLRPC.Message row:rows) if(row.id==id && row.params!=null && batch.equals(row.params.get(BATCH)) && item.equals(row.params.get(ITEM))) { durable=true;break; }
            if(!durable || !owner.current()) return;
            WRITER.execute(() -> {
                synchronized(revision(owner)) { try {
                    JSONObject state=load(owner);if(state==null || !batch.equals(state.optString("batch"))) return;
                    Set<String> accepted=strings(state.optJSONArray("accepted"));accepted.add(item);JSONArray acknowledgements=new JSONArray();for(String value:accepted) acknowledgements.put(value);
                    state=new JSONObject(state.toString()).put("accepted",acknowledgements);JSONArray photos=state.getJSONArray("photos");boolean complete=true;
                    for(int i=0;i<photos.length();i++) if(!accepted.contains(photos.getJSONObject(i).optString("item"))) complete=false;
                    File retained=new File(owner.directory(),"outbox-owned");try(FileOutputStream marker=new FileOutputStream(retained)) { marker.write(1);marker.getFD().sync(); }
                    if(complete) {
                        latest.remove(owner.key);cleared.add(owner.key);revision(owner).incrementAndGet();owner.manifest().delete();gcFence.incrementAndGet();collectCompleted(account);
                    } else { latest.put(owner.key,state);write(owner,state); }
                    // Files can still be read by pending native outbox entries; successful handoff never deletes them.
                } catch(Exception unavailable) { /* Retain the draft rather than falsely acknowledging a file. */ } }
            });
        });
    }
    /** Native completion/deletion signals only trigger collection; the storage queue remains authoritative. */
    public static void collectCompleted(int account) {
        String jid=MonogramController.get(account).accountJid();if(jid==null || jid.isEmpty()) return;
        Session picker=active;if(picker!=null && picker.pickerOpen && !picker.closed) return;
        final long fence=gcFence.get();
        MessagesStorage.getInstance(account).getMonogramDraftReferences(references -> {
            if(references==null || !jid.equals(MonogramController.get(account).accountJid())) return;
            WRITER.execute(() -> { synchronized(gcFence) {
                if(loggedOut.contains(account+"\n"+jid) || !jid.equals(MonogramController.get(account).accountJid())) return;
                if(fence!=gcFence.get()) return;
                Session live=active;if(live!=null && live.pickerOpen && !live.closed) return;
                File[] directories=scopeDirectory(account,jid).listFiles();if(directories==null) return;
                // Any composition may refer to another owner's snapshot via editor/forward state.
                // Keep the entire account scope until all such compositions finish instead of guessing at binary paths.
                for(File directory:directories) if(latest.containsKey(directory.getName()) || new File(directory,"draft.json").exists() || new File(directory,"draft.json.bak").exists()) return;
                for(File directory:directories) synchronized(revisions.computeIfAbsent(directory.getName(),key -> new AtomicLong())) {
                    // Pending captures and active/recoverable compositions always own their snapshots.
                    if(latest.containsKey(directory.getName()) || new File(directory,"draft.json").exists() || new File(directory,"draft.json.bak").exists()) continue;
                    String prefix=directory.getAbsolutePath()+File.separator;boolean retained=false;
                    for(String reference:references) if(reference!=null && (reference.equals(directory.getName()) || reference.contains(prefix))) { retained=true;break; }
                    if(!retained && fence==gcFence.get()) deleteTree(directory);
                }
            } });
        });
    }
    private static File scopeDirectory(int account,String jid) {
        try { return new File(ApplicationLoader.applicationContext.getFilesDir(),"monogram-media-drafts/"+account+"-"+hash(jid)); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static synchronized String ownershipEpoch(int account,String jid) {
        android.content.SharedPreferences preferences=ApplicationLoader.applicationContext.getSharedPreferences("monogram-media-draft-owners",Context.MODE_PRIVATE);
        String key=account+"\n"+jid,value=runtimeEpochs.get(key);if(value!=null) return value;value=preferences.getString(key,null);
        if(value==null) { value=UUID.randomUUID().toString();if(!preferences.edit().putString(key,value).commit()) throw new IllegalStateException("Cannot save media draft owner"); }
        return value;
    }
    public static void logout(int account,String jid) {
        gcFence.incrementAndGet();
        MonogramExternalSelections.logout(account,jid);
        final String nextEpoch=UUID.randomUUID().toString();
        loggedOut.add(account+"\n"+jid);runtimeEpochs.put(account+"\n"+jid,nextEpoch);
        boolean persisted=false;
        try { persisted=ApplicationLoader.applicationContext.getSharedPreferences("monogram-media-draft-owners",Context.MODE_PRIVATE).edit().putString(account+"\n"+jid,nextEpoch).commit(); }
        catch(Exception failed) { FileLog.e("Media draft logout generation could not be saved"); }
        if(!persisted) {
            // Fence this process immediately, then best-effort remove old metadata before credentials are cleared.
            // Never remove possibly queued assets when disk/permissions are unreliable.
            FileLog.e("Media draft logout metadata persistence failed");
            try { File[] directories=scopeDirectory(account,jid).listFiles();if(directories!=null) for(File directory:directories) new AtomicFile(new File(directory,"draft.json")).delete(); }
            catch(Exception ignored) { FileLog.e("Media draft logout metadata cleanup failed"); }
        }
        Session session=active;
        if(session!=null && session.owner.account==account && session.owner.jid.equals(jid)) { session.close();active=null; }
        for(Map.Entry<String,JSONObject> entry:latest.entrySet()) if(entry.getValue().optInt("account",-1)==account && jid.equals(entry.getValue().optString("jid"))) {
            revisions.computeIfAbsent(entry.getKey(),key -> new AtomicLong()).incrementAndGet();latest.remove(entry.getKey());cleared.add(entry.getKey());
        }
        WRITER.execute(() -> {
            File[] directories=scopeDirectory(account,jid).listFiles();if(directories==null) return;
            for(File directory:directories) {
                if(!nextEpoch.equals(ownershipEpoch(account,jid))) return;
                boolean retained=new File(directory,"outbox-owned").isFile();AtomicFile manifest=new AtomicFile(new File(directory,"draft.json"));
                try(InputStream input=manifest.openRead()) { ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))>=0) { if(bytes.size()+count>16*1024*1024) throw new IOException();bytes.write(buffer,0,count); }JSONObject state=new JSONObject(bytes.toString("UTF-8"));if(nextEpoch.equals(state.optString("epoch"))) continue;retained|=state.has("batch"); }
                catch(FileNotFoundException absent) {} catch(Exception uncertain) { retained=true; }
                manifest.delete();if(!retained) deleteTree(directory);
            }
        });
    }
    // Editor checkpoints mutate only unapplied fields on the latest selection, never applied edits.
    static boolean ensureEditorSelection(Owner owner,MediaController.PhotoEntry photo) {
        synchronized(gcFence) { synchronized(revision(owner)) {
            try {
                if(!owner.current()) return false;
                JSONObject state=loadLocked(owner);
                if(state!=null && (!state.optString("kind","photo").equals("photo") || state.has("batch"))) { editorError(owner,"Finish or discard the existing attachment draft before editing another attachment.");return false; }
                if(state==null) state=new JSONObject().put("kind","photo").put("version",1).put("account",owner.account).put("jid",owner.jid).put("peer",owner.peer).put("epoch",owner.epoch).put("photos",new JSONArray());
                JSONArray entries=state.getJSONArray("photos");String id=MonogramEditorDrafts.identity(photo);
                for(int i=0;i<entries.length();i++) if(id.equals(entries.getJSONObject(i).optString("draftId"))) return true;
                JSONObject entry=encode(photo,owner).put("editorOnly",true);MonogramEditorDrafts.previewOnly(photo,true);entries.put(entry);protect(entry);return publish(owner,state,null,null);
            } catch(Exception failed) { FileLog.e("Could not retain editor selection");return false; }
        } }
    }
    static void editorError(Owner owner,String message) { Session session=active;if(session!=null && session.owner.key.equals(owner.key) && owner.current()) report(session.errors,message); }
    static boolean persistEditor(MonogramEditorDrafts.Token token,long version) {
        synchronized(gcFence) { synchronized(revision(token.owner)) {
            try {
                if(!token.current(version)) return false;JSONObject state=loadLocked(token.owner);if(state==null || state.has("batch")) return false;
                JSONArray entries=state.getJSONArray("photos");for(int i=0;i<entries.length();i++) { JSONObject entry=entries.getJSONObject(i);
                    if(!token.id.equals(entry.optString("draftId"))) continue;
                    JSONObject editor=MonogramEditorDrafts.encoded(token.photo(),token.owner);if(editor==null) return false;entry.put("editor",editor);return publish(token.owner,state,null,null);
                }
            } catch(Exception failed) { FileLog.e("Could not retain editor composition"); }
        } }
        return false;
    }
    private static final Map<String,Set<String>> retiredEditors=new ConcurrentHashMap<>();
    static void retireEditor(Owner owner,String path) { retireEditor(owner,path,true); }
    static void retireEditor(Owner owner,String path,boolean committed) {
        if(path==null || path.isEmpty()) return;
        try {
            File file=new File(path),directory=new File(owner.directory(),"editor-assets");
            if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) || !file.getName().matches("[0-9a-fA-F-]{36}\\.png")) return;
            retiredEditors.computeIfAbsent(owner.key,key -> ConcurrentHashMap.newKeySet()).add(file.getAbsolutePath());if(committed) collectRetiredEditors(owner);
        } catch(Exception uncertain) { /* Only helper-created PNGs are eligible. */ }
    }
    static void collectRetiredEditors(Owner owner) {
        final long fence=gcFence.get();
        MessagesStorage.getInstance(owner.account).getMonogramDraftReferences(references -> {
            if(references==null) return;
            WRITER.execute(() -> { synchronized(gcFence) {
                if(fence!=gcFence.get() || !owner.current()) return;
                Set<String> candidates=retiredEditors.get(owner.key);if(candidates==null || candidates.isEmpty()) return;
                try {
                    ArrayList<JSONObject> manifests=new ArrayList<>();File[] owners=scopeDirectory(owner.account,owner.jid).listFiles();if(owners==null) return;
                    for(File directory:owners) {
                        synchronized(revisions.computeIfAbsent(directory.getName(),key -> new AtomicLong())) {
                            JSONObject memory=latest.get(directory.getName());if(memory!=null) manifests.add(new JSONObject(memory.toString()));
                            for(String name:Arrays.asList("draft.json","draft.json.bak")) { File file=new File(directory,name);if(!file.exists()) continue;
                                try(InputStream input=new FileInputStream(file)) { ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                                    while((count=input.read(buffer))>=0) { if(bytes.size()+count>16*1024*1024) return;bytes.write(buffer,0,count); }
                                    manifests.add(new JSONObject(bytes.toString("UTF-8")));
                                }
                            }
                        }
                    }
                    for(String path:new ArrayList<>(candidates)) {
                        boolean used=MonogramEditorDrafts.references(path);
                        for(String reference:references) if(reference!=null && (reference.equals(owner.key) || reference.contains(path))) used=true;
                        for(JSONObject state:manifests) if(editorReferences(state,path)) used=true;
                        if(!used && fence==gcFence.get() && owner.current()) { File file=new File(path);if(!file.exists() || file.delete()) candidates.remove(path); }
                    }
                } catch(Exception uncertain) { /* Corrupt/unreadable metadata cannot authorize deleting an editor asset. */ }
            } });
        });
    }
    static boolean editorReferences(Object value,String path) throws Exception {
        if(value instanceof JSONObject) { JSONObject object=(JSONObject)value;
            if(object.has("native") && object.has("fullEntities")) { VideoEditedInfo edit=readEdit(object);if(path.equals(edit.originalPath) || path.equals(edit.paintPath) || path.equals(edit.blurPath)) return true; }
            Iterator<String> keys=object.keys();while(keys.hasNext()) if(editorReferences(object.get(keys.next()),path)) return true;
        } else if(value instanceof JSONArray) { JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++) if(editorReferences(array.get(i),path)) return true;
        } else if(value instanceof String) { String text=(String)value;if(text.contains(path)) return true;
            try { if(new String(android.util.Base64.decode(text,android.util.Base64.DEFAULT),StandardCharsets.UTF_8).contains(path)) return true; } catch(IllegalArgumentException notBinary) {}
        }
        return false;
    }
    static void clearEditor(Owner owner,MediaController.PhotoEntry photo) {
        synchronized(gcFence) { synchronized(revision(owner)) {
            try {
                JSONObject state=loadLocked(owner);if(state==null || state.has("batch")) return;JSONArray old=state.getJSONArray("photos"),kept=new JSONArray();String id=MonogramEditorDrafts.identity(photo);ArrayList<String> retired=new ArrayList<>();
                for(int i=0;i<old.length();i++) { JSONObject entry=old.getJSONObject(i);if(id.equals(entry.optString("draftId"))) {
                    JSONObject pending=entry.optJSONObject("editor");if(pending!=null && pending.has("paint")) retired.add(pending.getString("paint"));
                    entry.remove("editor");if(entry.optBoolean("editorOnly")) continue;
                }kept.put(entry); }
                state.put("photos",kept);boolean committed;
                if(kept.length()==0 && state.optString("caption").isEmpty()) { clear(owner);committed=true; }else committed=publish(owner,state,null,null);
                for(String path:retired) retireEditor(owner,path,committed);
            } catch(Exception failed) { FileLog.e("Could not clear editor checkpoint"); }
        } }
    }
    private static void deleteTree(File file) { File[] children=file.listFiles();if(children!=null) for(File child:children) deleteTree(child);file.delete(); }
    private static AtomicLong revision(Owner owner) { return revisions.computeIfAbsent(owner.key,key -> new AtomicLong()); }
    private static void publish(Owner owner,JSONObject state,Consumer<String> errors) { publish(owner,state,errors,null); }
    private static boolean publish(Owner owner,JSONObject state,Consumer<String> errors,Runnable ready) {
        gcFence.incrementAndGet();
        boolean durable=true;
        final long version;
        synchronized(revision(owner)) {
            version=revision(owner).incrementAndGet();
            try { state.put("revision",version);latest.put(owner.key,state);cleared.remove(owner.key);write(owner,state); }
            catch(Exception failed) { durable=false;report(errors,"Could not save the media draft. Keep the picker open and try again."); }
        }
        WRITER.execute(() -> {
            if(version!=revision(owner).get() || !owner.current()) return;
            try {
                JSONObject saved=new JSONObject(state.toString());JSONArray photos=saved.getJSONArray("photos");
                boolean incomplete=false;for(int i=0;i<photos.length();i++) try { snapshotFiles(owner,photos.getJSONObject(i)); } catch(Exception missing) { incomplete=true; }
                synchronized(revision(owner)) {
                    if(version!=revision(owner).get() || !owner.current()) return;
                    JSONObject current=latest.get(owner.key);if(current!=null && current.has("accepted")) saved.put("accepted",current.getJSONArray("accepted"));
                    write(owner,saved);latest.put(owner.key,saved);
                }
                if(incomplete) report(errors,"Some draft media could not be preserved. Keep the original files available before leaving the picker.");
            } catch(Exception failed) { report(errors,"Some draft media could not be preserved. Keep the original files available before leaving the picker."); }
            if(ready!=null && owner.current() && version==revision(owner).get()) AndroidUtilities.runOnUIThread(ready);
        });
        return durable;
    }
    private static void clear(Owner owner) {
        gcFence.incrementAndGet();
        synchronized(revision(owner)) { revision(owner).incrementAndGet();latest.remove(owner.key);cleared.add(owner.key);owner.manifest().delete(); }
    }
    private static JSONObject load(Owner owner) throws Exception {
        synchronized(revision(owner)) { return loadLocked(owner); }
    }
    private static JSONObject loadLocked(Owner owner) throws Exception {
        JSONObject memory=latest.get(owner.key);if(memory!=null) return new JSONObject(memory.toString());if(cleared.contains(owner.key)) return null;
        try(InputStream input=owner.manifest().openRead()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))>=0) { if(bytes.size()+count>16*1024*1024) throw new IOException("Media draft metadata is too large");bytes.write(buffer,0,count); }
            JSONObject state=new JSONObject(bytes.toString("UTF-8"));if(!owner.epoch.equals(state.optString("epoch"))) return null;checkOwner(owner,state);revision(owner).accumulateAndGet(state.optLong("revision"),Math::max);return state;
        } catch(FileNotFoundException absent) { return null; }
    }
    private static void checkOwner(Owner owner,JSONObject state) throws Exception {
        if(state.getInt("version")!=1 || state.getInt("account")!=owner.account || !owner.jid.equals(state.getString("jid")) || !owner.peer.equals(state.getString("peer")) || !owner.epoch.equals(state.getString("epoch"))) throw new IOException("Draft owner changed");
    }
    private static void write(Owner owner,JSONObject state) throws Exception {
        synchronized(revision(owner)) {
            if(state.optLong("revision")!=revision(owner).get()) return;
            if(!owner.directory().isDirectory() && !owner.directory().mkdirs()) throw new IOException("Draft storage unavailable");
            AtomicFile file=owner.manifest();FileOutputStream output=file.startWrite();
            try { output.write(state.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output); }
            catch(Exception failed) { file.failWrite(output);throw failed; }
        }
    }
    private static void report(Consumer<String> errors,String text) { if(errors!=null) AndroidUtilities.runOnUIThread(() -> errors.accept(text)); }
    private static Set<String> strings(JSONArray array) { Set<String> values=new HashSet<>();if(array!=null) for(int i=0;i<array.length();i++) values.add(array.optString(i));return values; }
    private static String hash(String value) throws Exception { byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:hash) result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString(); }

    private static JSONObject encodeAudio(MediaController.AudioEntry audio) throws Exception {
        return new JSONObject().put("id",audio.id).put("author",audio.author).put("title",audio.title).put("genre",audio.genre).put("duration",audio.duration).put("mime",audio.mimeType).put("name",audio.displayName).put("size",audio.size)
                .put("message",binary(data -> audio.messageObject.messageOwner.serializeToStream(data)));
    }
    private static MediaController.AudioEntry decodeAudio(JSONObject entry,String path) throws Exception {
        JSONObject in=entry.getJSONObject("audio");MediaController.AudioEntry audio=new MediaController.AudioEntry();audio.id=in.optLong("id");audio.author=in.optString("author");audio.title=in.optString("title");audio.genre=in.optString("genre");audio.duration=in.optInt("duration");audio.mimeType=in.optString("mime");audio.displayName=in.optString("name");audio.size=in.optLong("size");audio.path=path;
        SerializedData data=read(in.getString("message"));try { TLRPC.Message message=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);message.attachPath=path;audio.messageObject=new MessageObject(entry.optInt("account"),message,false,false); } finally { data.cleanup(); }return audio;
    }
    private static final String[] PATHS={"path","thumbPath","imagePath","filterPath","paintPath","croppedPaintPath","fullPaintPath","coverPath"};
    private static JSONObject encode(MediaController.PhotoEntry photo,Owner owner) throws Exception {
        JSONObject out=new JSONObject().put("bucket",photo.bucketId).put("id",photo.imageId).put("date",photo.dateTaken).put("duration",photo.duration)
                .put("width",photo.width).put("height",photo.height).put("size",photo.size).put("orientation",photo.orientation).put("invert",photo.invert).put("video",photo.isVideo)
                .put("muted",photo.isMuted).put("delete",photo.canDeleteAfter).put("spoiler",photo.hasSpoiler).put("videoOrientation",photo.videoOrientation)
                .put("liveOffset",photo.livePhotoVideoOffset).put("liveTime",photo.livePhotoTimestampUs).put("averageDuration",photo.averageDuration)
                .put("filtered",photo.isFiltered).put("painted",photo.isPainted).put("cropped",photo.isCropped).put("ttl",photo.ttl).put("effect",photo.effectId).put("coverPosition",photo.coverSavedPosition)
                .put("quality",photo.highQuality==null ? JSONObject.NULL : photo.highQuality).put("discardLive",photo.discardLivePhoto==null ? JSONObject.NULL : photo.discardLivePhoto);
        String[] paths={photo.path,photo.thumbPath,photo.imagePath,photo.filterPath,photo.paintPath,photo.croppedPaintPath,photo.fullPaintPath,photo.coverPath};
        for(int i=0;i<PATHS.length;i++) if(paths[i]!=null && !paths[i].isEmpty()) out.put(PATHS[i],paths[i]);
        CharSequence[] text={photo.caption==null ? "" : new android.text.SpannableStringBuilder(photo.caption)};ArrayList<TLRPC.MessageEntity> entities=photo.entities;
        if(photo.caption instanceof android.text.Spanned) entities=MediaDataController.getInstance(owner.account).getEntities(text,false);
        out.put("caption",text[0].toString()).put("entities",entities(entities));
        if(photo.editedInfo!=null) out.put("edit",edit(photo.editedInfo));
        if(photo.savedFilterState!=null) out.put("filter",binary(data -> photo.savedFilterState.serializeToStream(data)));
        if(photo.cropState!=null) out.put("crop",binary(data -> photo.cropState.serializeToStream(data)));
        if(photo.coverPhoto!=null) out.put("cover",binary(data -> photo.coverPhoto.serializeToStream(data)));
        out.put("mediaEntities",mediaEntities(photo.mediaEntities)).put("croppedEntities",mediaEntities(photo.croppedMediaEntities));
        if(photo.stickers!=null) out.put("stickers",binary(data -> { data.writeInt32(photo.stickers.size());for(TLRPC.InputDocument document:photo.stickers) document.serializeToStream(data); }));
        File source=photo.path==null ? null : new File(photo.path);if(source!=null && source.isFile()) out.put("sourceSize",source.length()).put("sourceModified",source.lastModified());
        out.put("draftId",MonogramEditorDrafts.identity(photo));JSONObject pending=MonogramEditorDrafts.encoded(photo,owner);if(pending!=null) out.put("editor",pending);
        return out;
    }
    private static MediaController.PhotoEntry decode(JSONObject in,Owner owner) throws Exception {
        String path=in.getString("path");File source=new File(path);if(!source.isFile() || !source.canRead()) throw new IOException("Draft source unavailable");
        if(in.has("sourceSize") && source.length()!=in.getLong("sourceSize") || in.has("sourceModified") && source.lastModified()!=in.getLong("sourceModified")) throw new IOException("Draft source changed");
        MediaController.PhotoEntry photo=new MediaController.PhotoEntry(in.getInt("bucket"),in.getInt("id"),in.getLong("date"),path,in.getInt("orientation"),in.getInt("duration"),in.getBoolean("video"),in.getInt("width"),in.getInt("height"),in.getLong("size"));
        photo.invert=in.optInt("invert");photo.isMuted=in.optBoolean("muted");photo.canDeleteAfter=in.optBoolean("delete");photo.hasSpoiler=in.optBoolean("spoiler");photo.videoOrientation=in.optInt("videoOrientation",-1);
        photo.livePhotoVideoOffset=in.optLong("liveOffset");photo.livePhotoTimestampUs=in.optLong("liveTime");photo.averageDuration=in.optLong("averageDuration");
        photo.isFiltered=in.optBoolean("filtered");photo.isPainted=in.optBoolean("painted");photo.isCropped=in.optBoolean("cropped");photo.ttl=in.optInt("ttl");photo.effectId=in.optLong("effect");photo.coverSavedPosition=in.optLong("coverPosition");
        photo.highQuality=in.isNull("quality") ? null : in.getBoolean("quality");photo.discardLivePhoto=in.isNull("discardLive") ? null : in.getBoolean("discardLive");
        photo.caption=in.optString("caption");photo.entities=readEntities(in.optString("entities"));
        photo.thumbPath=existing(in,"thumbPath",false);photo.imagePath=existing(in,"imagePath",true);photo.filterPath=existing(in,"filterPath",true);photo.paintPath=existing(in,"paintPath",true);
        photo.croppedPaintPath=existing(in,"croppedPaintPath",true);photo.fullPaintPath=existing(in,"fullPaintPath",true);photo.coverPath=existing(in,"coverPath",true);
        if(in.has("edit")) {
            photo.editedInfo=readEdit(in.getJSONObject("edit"));
            requireAsset(photo.editedInfo.paintPath);requireAsset(photo.editedInfo.blurPath);validateEntities(photo.editedInfo.mediaEntities);
        }
        if(in.has("filter")) { SerializedData data=read(in.getString("filter"));try { photo.savedFilterState=new MediaController.SavedFilterState();photo.savedFilterState.readParams(data,true); } finally { data.cleanup(); } }
        if(in.has("crop")) { SerializedData data=read(in.getString("crop"));try { if(data.readInt32(true)!=MediaController.CropState.constructor) throw new IOException("Invalid crop");photo.cropState=new MediaController.CropState();photo.cropState.readParams(data,true); } finally { data.cleanup(); } }
        if(in.has("cover")) { SerializedData data=read(in.getString("cover"));try { photo.coverPhoto=TLRPC.Photo.TLdeserialize(data,data.readInt32(true),true); } finally { data.cleanup(); } }
        photo.mediaEntities=readMediaEntities(in.optString("mediaEntities"));photo.croppedMediaEntities=readMediaEntities(in.optString("croppedEntities"));
        validateEntities(photo.mediaEntities);validateEntities(photo.croppedMediaEntities);
        if(in.has("stickers")) { SerializedData data=read(in.getString("stickers"));try { int count=data.readInt32(true);if(count<0 || count>4096) throw new IOException("Invalid masks");photo.stickers=new ArrayList<>();for(int i=0;i<count;i++) photo.stickers.add(TLRPC.InputDocument.TLdeserialize(data,data.readInt32(true),true)); } finally { data.cleanup(); } }
        MonogramEditorDrafts.identity(photo,in.optString("draftId"));MonogramEditorDrafts.previewOnly(photo,in.optBoolean("editorOnly"));if(in.has("editor")) MonogramEditorDrafts.restore(photo,owner,in.getJSONObject("editor"));
        return photo;
    }
    private static void requireAsset(String path) throws IOException {
        if(path!=null && !path.isEmpty() && (!new File(path).isFile() || !new File(path).canRead())) throw new IOException("An edited draft asset is missing");
    }
    static void validateEntities(ArrayList<VideoEditedInfo.MediaEntity> entities) throws IOException {
        if(entities==null) return;
        for(VideoEditedInfo.MediaEntity entity:entities) {
            if(entity.type!=VideoEditedInfo.MediaEntity.TYPE_TEXT && entity.text!=null && (entity.text.startsWith("/") || entity.text.startsWith("content:"))) requireAsset(entity.text);
            requireAsset(entity.segmentedPath);
            if(entity.document!=null) requireAsset(entity.document.localPath);
            for(VideoEditedInfo.EmojiEntity emoji:entity.entities) requireAsset(emoji.documentAbsolutePath);
        }
    }
    private static String existing(JSONObject in,String key,boolean required) throws Exception { String path=in.optString(key,"");if(path.isEmpty()) return null;if(new File(path).isFile()) return path;if(required) throw new IOException("An edited draft asset is missing");return null; }
    private static JSONObject edit(VideoEditedInfo info) throws Exception { return new JSONObject().put("native",info.getString()).put("muted",info.muted).put("round",info.roundVideo).put("photo",info.isPhoto).put("size",info.estimatedSize).put("duration",info.estimatedDuration).put("quality",info.compressQuality).put("start",info.start).put("end",info.end).put("volume",info.volume).put("fullEntities",mediaEntities(info.mediaEntities)); }
    private static VideoEditedInfo readEdit(JSONObject in) throws Exception { VideoEditedInfo info=new VideoEditedInfo();if(!info.parseString(in.getString("native"))) throw new IOException("Invalid video edit");info.muted=in.optBoolean("muted");info.roundVideo=in.optBoolean("round");info.isPhoto=in.optBoolean("photo");info.estimatedSize=in.optLong("size");info.estimatedDuration=in.optLong("duration");info.compressQuality=in.optInt("quality");info.start=(float)in.optDouble("start");info.end=(float)in.optDouble("end");info.volume=(float)in.optDouble("volume",1);info.mediaEntities=readMediaEntities(in.optString("fullEntities"));return info; }
    private interface Encoder { void write(SerializedData data); }
    private static String binary(Encoder encoder) { SerializedData data=new SerializedData();try { encoder.write(data);return android.util.Base64.encodeToString(data.toByteArray(),android.util.Base64.NO_WRAP); } finally { data.cleanup(); } }
    private static SerializedData read(String value) { return new SerializedData(android.util.Base64.decode(value,android.util.Base64.DEFAULT)); }
    private static String entities(ArrayList<TLRPC.MessageEntity> entities) { return binary(data -> { data.writeInt32(entities==null ? 0 : entities.size());if(entities!=null) for(TLRPC.MessageEntity entity:entities) entity.serializeToStream(data); }); }
    private static ArrayList<TLRPC.MessageEntity> readEntities(String value) throws Exception { ArrayList<TLRPC.MessageEntity> result=new ArrayList<>();if(value.isEmpty()) return result;SerializedData data=read(value);try { int count=data.readInt32(true);if(count<0 || count>4096) throw new IOException("Invalid caption entities");for(int i=0;i<count;i++) result.add(TLRPC.MessageEntity.TLdeserialize(data,data.readInt32(true),true));return result; } finally { data.cleanup(); } }
    static String mediaEntities(ArrayList<VideoEditedInfo.MediaEntity> entities) { if(entities==null) return "";return binary(data -> { data.writeInt32(entities.size());for(VideoEditedInfo.MediaEntity entity:entities) { entity.serializeTo(data,true);data.writeString(entity.document==null || entity.document.localPath==null ? "" : entity.document.localPath);data.writeInt32(entity.entities.size());for(VideoEditedInfo.EmojiEntity emoji:entity.entities) data.writeString(emoji.documentAbsolutePath==null ? "" : emoji.documentAbsolutePath); } }); }
    static ArrayList<VideoEditedInfo.MediaEntity> readMediaEntities(String value) throws Exception { if(value.isEmpty()) return null;SerializedData data=read(value);try { int count=data.readInt32(true);if(count<0 || count>4096) throw new IOException("Invalid paint entities");ArrayList<VideoEditedInfo.MediaEntity> result=new ArrayList<>();for(int i=0;i<count;i++) { VideoEditedInfo.MediaEntity entity=new VideoEditedInfo.MediaEntity(data,true,true);String local=data.readString(true);if(entity.document!=null && !local.isEmpty()) entity.document.localPath=local;int emojis=data.readInt32(true);if(emojis!=entity.entities.size()) throw new IOException("Invalid paint emoji paths");for(VideoEditedInfo.EmojiEntity emoji:entity.entities) { String path=data.readString(true);if(!path.isEmpty()) emoji.documentAbsolutePath=path; } result.add(entity); }return result; } finally { data.cleanup(); } }
    private static void protect(JSONObject entry) { for(String key:PATHS) { String path=entry.optString(key);if(!path.isEmpty()) { protectedPaths.add(path);if(path.startsWith("content:")) try { ApplicationLoader.applicationContext.getContentResolver().takePersistableUriPermission(Uri.parse(path),Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(SecurityException ignored) {} } } }
    private static void snapshotFiles(Owner owner,JSONObject entry) throws Exception {
        for(String key:PATHS) {
            String source=entry.optString(key);if(source.isEmpty()) continue;
            boolean copy=!key.equals("path") || entry.optBoolean("delete") || source.startsWith("content:") || source.startsWith(ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath()+File.separator);
            if(key.equals("thumbPath") && !source.startsWith("content:") && !new File(source).isFile()) { entry.remove(key);continue; }
            String path=copy ? copy(owner,source,entry.optString("capture",UUID.randomUUID().toString())) : source;entry.put(key,path);
            if(key.equals("path")) { File file=new File(path);entry.put("sourceSize",file.length()).put("sourceModified",file.lastModified()); }
        }
        if(entry.optBoolean("resolveMedia")) {
            String path=entry.getString("path"),source=entry.optString("originalUri",path),mime=null;
            if(source.startsWith("content:")) try { mime=ApplicationLoader.applicationContext.getContentResolver().getType(Uri.parse(source)); } catch(Exception ignored) {}
            String lower=path.toLowerCase(Locale.ROOT);
            boolean video=entry.optBoolean("video") || mime!=null && mime.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".mov");entry.put("video",video);
            if(video) {
                android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();try {
                    retriever.setDataSource(path);String width=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),height=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),duration=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if(width!=null) entry.put("width",Integer.parseInt(width));if(height!=null) entry.put("height",Integer.parseInt(height));if(duration!=null) entry.put("duration",(int)Math.ceil(Long.parseLong(duration)/1000d));
                    String rotation=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if(rotation!=null) entry.put("videoOrientation",Integer.parseInt(rotation));
                } finally { retriever.release(); }
            } else {
                android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(path,bounds);if(bounds.outWidth>0) entry.put("width",bounds.outWidth).put("height",bounds.outHeight);
                android.util.Pair<Integer,Integer> orientation=AndroidUtilities.getImageOrientation(path);entry.put("orientation",orientation.first).put("invert",orientation.second);
            }
        }
        if(entry.has("edit")) {
            VideoEditedInfo info=readEdit(entry.getJSONObject("edit"));info.originalPath=entry.getString("path");
            if(info.paintPath!=null) info.paintPath=copy(owner,info.paintPath);if(info.blurPath!=null) info.blurPath=copy(owner,info.blurPath);
            snapshotEntities(owner,info.mediaEntities);entry.put("edit",edit(info));
        }
        if(entry.has("editor")) MonogramEditorDrafts.snapshot(owner,entry.getJSONObject("editor"));
        for(String key:Arrays.asList("mediaEntities","croppedEntities")) { ArrayList<VideoEditedInfo.MediaEntity> entities=readMediaEntities(entry.optString(key));snapshotEntities(owner,entities);entry.put(key,mediaEntities(entities)); }
    }
    static void snapshotEntities(Owner owner,ArrayList<VideoEditedInfo.MediaEntity> entities) throws Exception {
        if(entities==null) return;
        for(VideoEditedInfo.MediaEntity entity:entities) {
            if(entity.type!=VideoEditedInfo.MediaEntity.TYPE_TEXT && entity.text!=null && (entity.text.startsWith("/") || entity.text.startsWith("content:"))) entity.text=copy(owner,entity.text);
            if(entity.segmentedPath!=null && !entity.segmentedPath.isEmpty()) entity.segmentedPath=copy(owner,entity.segmentedPath);
            if(entity.document!=null && entity.document.localPath!=null && !entity.document.localPath.isEmpty()) entity.document.localPath=copy(owner,entity.document.localPath);
            for(VideoEditedInfo.EmojiEntity emoji:entity.entities) if(emoji.documentAbsolutePath!=null && !emoji.documentAbsolutePath.isEmpty()) emoji.documentAbsolutePath=copy(owner,emoji.documentAbsolutePath);
        }
    }
    private static String providerName(Uri uri) {
        String name=null;try(android.database.Cursor cursor=ApplicationLoader.applicationContext.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(cursor!=null && cursor.moveToFirst()) { int index=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(index>=0) name=cursor.getString(index); }
        } catch(Exception ignored) {}
        if(name==null || name.isEmpty()) name=uri.getLastPathSegment();
        if(name!=null && name.indexOf('.')<0) try { String mime=ApplicationLoader.applicationContext.getContentResolver().getType(uri),extension=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);if(extension!=null) name+="."+extension; } catch(Exception ignored) {}
        return name;
    }
    static String copy(Owner owner,String source) throws Exception { return copy(owner,source,UUID.randomUUID().toString()); }
    private static String copy(Owner owner,String source,String capture) throws Exception {
        File original=new File(source),assets=new File(owner.directory(),"assets");if(source.startsWith(assets.getAbsolutePath()+File.separator) && original.isFile()) return source;
        if(!assets.isDirectory() && !assets.mkdirs()) throw new IOException("Draft assets unavailable");
        String suffix="";int dot=source.lastIndexOf('.');if(dot>=0 && source.length()-dot<=10) suffix=source.substring(dot).replaceAll("[^a-zA-Z0-9.]","");
        Uri uri=Uri.parse(source);boolean content="content".equals(uri.getScheme());
        File destination;
        if(content) {
            String name=FileLoader.fixFileName(providerName(uri));if(name==null || name.isEmpty() || name.equals(".") || name.equals("..")) name="attachment";if(name.length()>180) name=name.substring(name.length()-180);
            File captureDirectory=new File(assets,hash(source+"\n"+capture));if(!captureDirectory.isDirectory() && !captureDirectory.mkdirs()) throw new IOException("Draft asset directory unavailable");destination=new File(captureDirectory,name);
        } else destination=new File(assets,hash(source+"\n"+original.length()+"\n"+original.lastModified())+suffix);
        if(destination.isFile()) return destination.getAbsolutePath();
        if(content) try { ApplicationLoader.applicationContext.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(SecurityException unsupported) { /* A private snapshot outlives temporary grants. */ }
        File temporary=File.createTempFile("asset-",".tmp",assets);
        try(InputStream input=content ? ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri) : new FileInputStream(original);FileOutputStream output=new FileOutputStream(temporary)) {
            if(input==null) throw new FileNotFoundException();byte[] buffer=new byte[65536];int count;long copied=0;while((count=input.read(buffer))>=0) { if(loggedOut.contains(owner.account+"\n"+owner.jid) || !owner.current()) throw new InterruptedIOException("Draft owner changed");if(count>com.negzero.monogram.xmpp.FileTransfer.MAX_BYTES-copied) throw new IOException("Draft source exceeds the attachment size limit");output.write(buffer,0,count);copied+=count; }output.getFD().sync();
            if(!temporary.renameTo(destination)) throw new IOException("Could not save draft asset");return destination.getAbsolutePath();
        } finally { temporary.delete(); }
    }
}
