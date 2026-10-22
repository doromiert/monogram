// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import com.negzero.monogram.xmpp.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_stories;
import org.jivesoftware.smack.roster.Roster;

/** Native Telegram story presentation over account-owned XEP-0501 PEP data. */
public final class MonogramStories {
    interface Session { XmppSession get() throws Exception; }
    private final int account;
    private final MonogramStore store;
    private final Session source;
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicBoolean refreshing=new java.util.concurrent.atomic.AtomicBoolean();
    private long refreshed;
    MonogramStories(int account,MonogramStore store,Session source) { this.account=account;this.store=store;this.source=source; }
    private String owner() { return MonogramController.get(account).accountJid(); }
    private long self() { return UserConfig.getInstance(account).getClientUserId(); }
    private SharedPreferences prefs() { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-stories-"+account,Context.MODE_PRIVATE); }
    private String key(String owner) { return owner+"\nitems"; }
    private synchronized JSONArray read(String owner) {
        try { return new JSONArray(prefs().getString(key(owner),"[]")); } catch(Exception corrupt) { return new JSONArray(); }
    }
    private synchronized int put(String owner,XmppStories.Story story) throws Exception {
        if(!owner.equals(owner())) throw new IllegalStateException("Account changed");
        JSONArray data=read(owner),next=new JSONArray();int id=0;
        for(int i=0;i<data.length();i++) {
            JSONObject row=data.getJSONObject(i);
            if(story.owner.equals(row.optString("owner")) && story.id.equals(row.optString("wire"))) id=row.getInt("id");
            else if(row.optLong("time")+XmppStories.LIFETIME>System.currentTimeMillis()) next.put(row);
        }
        if(id==0) id=prefs().getInt(owner+"\nnext",1);
        JSONObject row=new JSONObject().put("id",id).put("owner",story.owner).put("wire",story.id).put("url",story.url)
                .put("mime",story.mime).put("title",story.title).put("time",story.published);
        next.put(row);
        if(!prefs().edit().putString(key(owner),next.toString()).putInt(owner+"\nnext",Math.max(id+1,prefs().getInt(owner+"\nnext",1))).commit()) throw new java.io.IOException("Could not save story");
        return id;
    }
    private synchronized void replace(String owner,String peer,List<XmppStories.Story> stories) throws Exception {
        if(!owner.equals(owner())) return;
        Set<String> keep=new HashSet<>();for(XmppStories.Story story:stories) keep.add(story.id);
        JSONArray data=read(owner),next=new JSONArray();
        for(int i=0;i<data.length();i++) {
            JSONObject row=data.getJSONObject(i);
            if((!peer.equals(row.optString("owner")) || keep.contains(row.optString("wire"))) && row.optLong("time")+XmppStories.LIFETIME>System.currentTimeMillis()) next.put(row);
        }
        if(!prefs().edit().putString(key(owner),next.toString()).commit()) throw new java.io.IOException("Could not refresh stories");
        for(XmppStories.Story story:stories) put(owner,story);
    }
    private final Set<String> fetching=ConcurrentHashMap.newKeySet();
    private final Map<String,Long> peerRefresh=new ConcurrentHashMap<>();
    void changed(String peer) {
        String owner=owner();
        long now=android.os.SystemClock.elapsedRealtime();String stamp=owner+"\n"+peer;
        if(now-peerRefresh.getOrDefault(stamp,Long.MIN_VALUE/2)<5000) return;
        peerRefresh.put(stamp,now);
        if(owner.isEmpty() || store.knownPeer(peer)<=0 || !fetching.add(owner+"\n"+peer)) return;
        worker.execute(()->{
            try {
                XmppSession session=source.get();replace(owner,peer,XmppStories.fetch(session.connection(),peer));
                AndroidUtilities.runOnUIThread(()->{
                    if(owner.equals(owner())) MessagesController.getInstance(account).getStoriesController().loadStories();
                });
            } catch(Exception unavailable) {} finally { fetching.remove(owner+"\n"+peer); }
        });
    }
    private void refresh() {
        if(System.currentTimeMillis()-refreshed<30000 || !refreshing.compareAndSet(false,true)) return;
        final String owner=owner();if(owner.isEmpty()) { refreshing.set(false);return; }
        refreshed=System.currentTimeMillis();
        worker.execute(()->{
            try {
                XmppSession session=source.get();LinkedHashSet<String> peers=new LinkedHashSet<>();peers.add(owner);
                Roster.getInstanceFor(session.connection()).getEntries().forEach(entry->peers.add(entry.getJid().toString()));
                int budget=50;
                for(String peer:peers) {
                    if(budget--<=0) break;
                    if(!owner.equals(owner()) || !session.connection().isAuthenticated()) break;
                    try {
                        replace(owner,peer,XmppStories.fetch(session.connection(),peer));
                        AndroidUtilities.runOnUIThread(()->{
                            if(owner.equals(owner())) MessagesController.getInstance(account).getStoriesController().loadStories();
                        });
                    } catch(Exception unavailable) { /* Keep cached items on transient/unsupported-provider failure. */ }
                }
            } catch(Exception offline) { /* Cached stories remain usable while reconnecting. */ }
            finally { refreshing.set(false); }
        });
    }
    public static boolean remote(TL_stories.StoryItem story) {
        return story!=null && story.attachPath!=null && story.attachPath.startsWith("https://")
                && story.media!=null && (story.media.photo!=null && story.media.photo.dc_id==Integer.MIN_VALUE
                || story.media.document!=null && story.media.document.dc_id==Integer.MIN_VALUE);
    }
    private TL_stories.StoryItem project(JSONObject row) throws Exception {
        TL_stories.TL_storyItem story=new TL_stories.TL_storyItem();story.id=row.getInt("id");
        story.date=(int)(row.getLong("time")/1000);story.expire_date=story.date+86400;story.caption=row.optString("title");
        story.contacts=true;story.out=owner().equals(row.getString("owner"));story.flags|=1;
        story.privacy.add(new TLRPC.TL_privacyValueAllowContacts());story.flags|=4;
        story.attachPath=row.getString("url");
        story.media=MonogramAttachments.remoteMedia(story.id,row.getString("mime").startsWith("video/")?"story.mp4":"story.jpg",row.getString("mime"),0);
        if(story.media.photo!=null) story.media.photo.dc_id=Integer.MIN_VALUE;
        return story;
    }
    void restore(long peer,TL_stories.StoryItem target) {
        if(target==null) return;
        try {
            JSONArray rows=read(owner());
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(row.getInt("id")==target.id && store.jid(peer).equals(row.getString("owner"))) { target.attachPath=row.getString("url");return; }
            }
        } catch(Exception ignored) {}
    }
    String replyQuote(int id,long recipient) {
        try {
            JSONArray rows=read(owner());String jid=store.jid(recipient);
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(row.getInt("id")==id && jid.equals(row.getString("owner"))) {
                    String title=row.optString("title");if(title.isEmpty()) title="Story";
                    title=title.replace('\n',' ').replace('\r',' ');
                    return "> "+title.substring(0,Math.min(200,title.length()))+"\n\n";
                }
            }
        } catch(Exception ignored) {}
        return "> Story\n\n";
    }
    private TL_stories.TL_peerStories peerStories(String owner,long peer) throws Exception {
        TL_stories.TL_peerStories result=new TL_stories.TL_peerStories();result.peer=new TLRPC.TL_peerUser();result.peer.user_id=peer;
        String jid=store.jid(peer);JSONArray rows=read(owner);
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            if(jid.equals(row.optString("owner")) && row.optLong("time")+XmppStories.LIFETIME>System.currentTimeMillis()) result.stories.add(project(row));
        }
        result.stories.sort(Comparator.comparingInt(story->story.date));
        result.max_read_id=prefs().getInt(owner+"\nread\n"+jid,0);result.flags|=1;return result;
    }
    private long peer(TLRPC.InputPeer peer) { return peer instanceof TLRPC.TL_inputPeerSelf ? self() : peer==null ? 0 : peer.user_id; }
    private final Map<Integer,FileTransfer.Job> uploads=new ConcurrentHashMap<>();
    private final ThreadLocal<FileTransfer.Job> currentJob=new ThreadLocal<>();
    private final ThreadLocal<Long> requestGeneration=new ThreadLocal<>();
    void cancel(int token) { FileTransfer.Job job=uploads.get(token);if(job!=null) job.cancel(); }
    private void checkRequestOwner() throws Exception {
        FileTransfer.Job job=currentJob.get();if(job!=null) job.check();
        Long generation=requestGeneration.get();
        if(generation!=null && generation!=MonogramController.get(account).accountGeneration()) throw new java.io.InterruptedIOException("Account changed");
    }
    void request(int token,TLObject request,RequestDelegate callback) {
        final long generation=MonogramController.get(account).accountGeneration();
        FileTransfer.Job job=new FileTransfer.Job();uploads.put(token,job);
        worker.execute(()->{
            currentJob.set(job);requestGeneration.set(generation);
            try {
                checkRequestOwner();TLObject result=handle(request);
                if(!job.cancelled()) Utilities.stageQueue.postRunnable(()->callback.run(result,null));
            } catch(Exception failed) {
                TLRPC.TL_error error=new TLRPC.TL_error();error.code=400;
                error.text=failed instanceof IllegalArgumentException || failed instanceof UnsupportedOperationException
                        ? failed.getMessage() : "MONOGRAM_STORY_FAILED: "+failed.getClass().getSimpleName();
                if(!job.cancelled()) Utilities.stageQueue.postRunnable(()->callback.run(null,error));
            } finally { uploads.remove(token,job);currentJob.remove();requestGeneration.remove(); }
        });
    }
    TLObject handle(TLObject request) throws Exception {
        if(!(request instanceof TL_stories.TL_stories_getAllStories || request instanceof TL_stories.TL_stories_getPeerStories
                || request instanceof TL_stories.TL_stories_getPinnedStories
                || request instanceof TL_stories.TL_stories_getStoriesByID || request instanceof TL_stories.TL_stories_sendStory
                || request instanceof TL_stories.TL_stories_deleteStories || request instanceof TL_stories.TL_stories_readStories
                || request instanceof TL_stories.TL_stories_canSendStory || request instanceof TL_stories.TL_stories_editStory)) return null;
        String owner=owner();if(owner.isEmpty()) throw new IllegalStateException("XMPP account required");
        if(request instanceof TL_stories.TL_stories_getAllStories) {
            TL_stories.TL_stories_allStories result=new TL_stories.TL_stories_allStories();result.state="local";result.stealth_mode=new TL_stories.TL_storiesStealthMode();
            if(!((TL_stories.TL_stories_getAllStories)request).include_hidden) {
                LinkedHashSet<String> peers=new LinkedHashSet<>();JSONArray rows=read(owner);
                for(int i=0;i<rows.length();i++) peers.add(rows.getJSONObject(i).getString("owner"));
                for(String jid:peers) {
                    long peer=store.knownPeer(jid);if(peer<=0) continue;
                    TL_stories.TL_peerStories stories=peerStories(owner,peer);
                    if(!stories.stories.isEmpty()) { result.peer_stories.add(stories);result.users.add(store.user(peer,self())); }
                }
                result.count=result.peer_stories.size();refresh();
            }
            return result;
        }
        if(request instanceof TL_stories.TL_stories_getPinnedStories) {
            // The native profile uses the pinned-stories request for its Posts grid.
            // XMPP stories remain ephemeral: expose active items here without making them permanent.
            TL_stories.TL_stories_getPinnedStories query=(TL_stories.TL_stories_getPinnedStories)request;
            long peer=peer(query.peer);
            ArrayList<TL_stories.StoryItem> active=peerStories(owner,peer).stories;
            active.sort((a,b)->Integer.compare(b.id,a.id));
            TL_stories.TL_stories_stories result=new TL_stories.TL_stories_stories();result.count=active.size();
            int limit=Math.max(1,Math.min(query.limit,100));
            for(TL_stories.StoryItem story:active) if((query.offset_id==0 || story.id<query.offset_id) && result.stories.size()<limit) result.stories.add(story);
            result.users.add(store.user(peer,self()));changed(store.jid(peer));return result;
        }
        if(request instanceof TL_stories.TL_stories_getPeerStories) {
            long peer=peer(((TL_stories.TL_stories_getPeerStories)request).peer);
            TL_stories.TL_stories_peerStories result=new TL_stories.TL_stories_peerStories();result.stories=peerStories(owner,peer);result.users.add(store.user(peer,self()));changed(store.jid(peer));return result;
        }
        if(request instanceof TL_stories.TL_stories_getStoriesByID) {
            TL_stories.TL_stories_getStoriesByID query=(TL_stories.TL_stories_getStoriesByID)request;
            TL_stories.TL_stories_stories result=new TL_stories.TL_stories_stories();
            for(TL_stories.StoryItem story:peerStories(owner,peer(query.peer)).stories) if(query.id.contains(story.id)) result.stories.add(story);
            result.users.add(store.user(peer(query.peer),self()));return result;
        }
        if(request instanceof TL_stories.TL_stories_canSendStory) {
            if(peer(((TL_stories.TL_stories_canSendStory)request).peer)!=self()) throw new UnsupportedOperationException("Publish stories on your own account");
            TL_stories.canSendStoryCount result=new TL_stories.canSendStoryCount();result.count_remains=Integer.MAX_VALUE;return result;
        }
        if(request instanceof TL_stories.TL_stories_readStories) {
            TL_stories.TL_stories_readStories read=(TL_stories.TL_stories_readStories)request;
            String key=owner+"\nread\n"+store.jid(peer(read.peer));prefs().edit().putInt(key,Math.max(read.max_id,prefs().getInt(key,0))).apply();
            return new org.telegram.tgnet.Vector<org.telegram.tgnet.Vector.Int>(org.telegram.tgnet.Vector.Int::TLDeserialize);
        }
        if(request instanceof TL_stories.TL_stories_deleteStories) {
            TL_stories.TL_stories_deleteStories query=(TL_stories.TL_stories_deleteStories)request;
            if(peer(query.peer)!=self()) throw new IllegalArgumentException("Only your own stories can be deleted");
            org.telegram.tgnet.Vector<org.telegram.tgnet.Vector.Int> result=new org.telegram.tgnet.Vector<>(org.telegram.tgnet.Vector.Int::TLDeserialize);
            JSONArray data=read(owner),next=new JSONArray();
            for(int i=0;i<data.length();i++) {
                JSONObject row=data.getJSONObject(i);
                if(query.id.contains(row.getInt("id")) && owner.equals(row.getString("owner"))) {
                    checkRequestOwner();XmppStories.retract(source.get().connection(),row.getString("wire"));result.objects.add(new org.telegram.tgnet.Vector.Int(row.getInt("id")));
                } else next.put(row);
            }
            if(!prefs().edit().putString(key(owner),next.toString()).commit()) throw new java.io.IOException("Could not persist story deletion");return result;
        }
        if(request instanceof TL_stories.TL_stories_editStory) {
            TL_stories.TL_stories_editStory edit=(TL_stories.TL_stories_editStory)request;
            if(peer(edit.peer)!=self() || edit.media!=null || !edit.media_areas.isEmpty()) throw new UnsupportedOperationException("Only captions of your active XMPP stories can be edited");
            if((edit.flags&4)!=0 && (edit.privacy_rules.size()!=1 || !(edit.privacy_rules.get(0) instanceof TLRPC.TL_inputPrivacyValueAllowContacts)))
                throw new UnsupportedOperationException("XMPP stories use subscribed-contact visibility");
            JSONArray rows=read(owner);
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(row.getInt("id")==edit.id && owner.equals(row.getString("owner"))) {
                    XmppStories.Story changed=new XmppStories.Story(row.getString("wire"),owner,row.getString("url"),row.getString("mime"),
                            (edit.flags&2)!=0?edit.caption:row.getString("title"),row.getLong("time"));
                    checkRequestOwner();XmppStories.republish(source.get().connection(),changed);put(owner,changed);
                    row.put("title",changed.title);TL_stories.TL_updateStory update=new TL_stories.TL_updateStory();
                    update.peer=new TLRPC.TL_peerUser();update.peer.user_id=self();update.story=project(row);update.story.edited=true;
                    MonogramController.Updates result=new MonogramController.Updates();result.updates.add(update);return result;
                }
            }
            throw new IllegalArgumentException("Story is no longer available");
        }
        TL_stories.TL_stories_sendStory send=(TL_stories.TL_stories_sendStory)request;
        if(peer(send.peer)!=self()) throw new IllegalArgumentException("Publish stories on your own account");
        if(send.privacy_rules.size()!=1 || !(send.privacy_rules.get(0) instanceof TLRPC.TL_inputPrivacyValueAllowContacts)) throw new IllegalArgumentException("XMPP stories are shared with subscribed contacts. Choose Contacts without exclusions.");
        if(send.pinned || send.period!=0 && send.period!=86400 || send.fwd_from_id!=null || send.music!=null || !send.media_areas.isEmpty()) throw new UnsupportedOperationException("XMPP stories support a photo or video, caption and 24-hour expiry");
        if(send.media==null || !(send.media.file instanceof MonogramAvatarFile)) throw new IllegalArgumentException("Story media is not ready");
        File file=((MonogramAvatarFile)send.media.file).source;String mime=send.media instanceof TLRPC.TL_inputMediaUploadedDocument?"video/mp4":"image/jpeg";
        FileTransfer.Job job=currentJob.get();if(job==null) job=new FileTransfer.Job();XmppSession session=source.get();
        checkRequestOwner();
        File snapshot=FileTransfer.snapshot(file,ApplicationLoader.applicationContext.getCacheDir(),job);
        FileLink link;
        try { link=FileTransfer.upload(session.connection(),snapshot,UUID.randomUUID()+(mime.startsWith("video/")?".mp4":".jpg"),mime,false,job,(bytes,total)->AndroidUtilities.runOnUIThread(()->{
            if(owner.equals(owner())) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileUploadProgressChanged,file.getAbsolutePath(),bytes,total);
        })); }
        finally { snapshot.delete(); }
        String id=UUID.nameUUIDFromBytes((owner+"\n"+send.random_id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        checkRequestOwner();
        XmppStories.Story story=XmppStories.publish(session.connection(),id,link.wireUrl,mime,send.caption,false);
        if(job.cancelled()) { XmppStories.retract(session.connection(),id);job.check(); }
        checkRequestOwner();int local=put(owner,story);
        TL_stories.TL_updateStory update=new TL_stories.TL_updateStory();update.peer=new TLRPC.TL_peerUser();update.peer.user_id=self();
        JSONArray rows=read(owner);for(int i=0;i<rows.length();i++) if(rows.getJSONObject(i).getInt("id")==local) update.story=project(rows.getJSONObject(i));
        MonogramController.Updates result=new MonogramController.Updates();result.date=(int)(System.currentTimeMillis()/1000);result.updates.add(update);return result;
    }
}
