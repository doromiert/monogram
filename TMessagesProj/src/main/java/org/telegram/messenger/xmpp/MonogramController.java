// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.Account;
import com.negzero.monogram.xmpp.XmppSession;
import com.negzero.monogram.xmpp.XmppMessage;
import com.negzero.monogram.xmpp.ReplyReference;
import com.negzero.monogram.xmpp.FileLink;
import com.negzero.monogram.xmpp.FileTransfer;
import com.negzero.monogram.xmpp.MessageMarkup;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.jivesoftware.smack.android.AndroidSmackInitializer;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

public final class MonogramController {
    /** Native update payloads originating from XMPP have no Telegram PTS sequence. */
    public static final class Updates extends TLRPC.TL_updates {}
    private static final MonogramController[] instances = new MonogramController[UserConfig.MAX_ACCOUNT_COUNT];
    public static synchronized MonogramController get(int account) {
        if (instances[account] == null) instances[account] = new MonogramController(account);
        return instances[account];
    }
    public static synchronized com.negzero.monogram.xmpp.XmppContacts.PresenceState cachedPresence(int account,long peer) {
        MonogramController current=instances[account];
        return current==null ? com.negzero.monogram.xmpp.XmppContacts.PresenceState.UNKNOWN : current.presence(peer);
    }
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService sendWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService readSyncWorker=Executors.newSingleThreadExecutor();
    private final Object readSyncLock=new Object();
    private final java.util.concurrent.atomic.AtomicBoolean readSyncSending=new java.util.concurrent.atomic.AtomicBoolean(),readSyncReload=new java.util.concurrent.atomic.AtomicBoolean(),readSyncReconcile=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong readSyncRequests=new java.util.concurrent.atomic.AtomicLong();
    private volatile long readSyncPrivacyEpoch;
    private volatile com.negzero.monogram.xmpp.XmppReadSync.Snapshot readSyncSnapshot;
    private volatile XmppSession readSyncSession;
    private final MonogramReadSyncState privateReads;
    private final ExecutorService bookmarkWorker = Executors.newSingleThreadExecutor();
    private final java.util.Set<Long> joinedRooms = new java.util.HashSet<>();
    private final java.util.Set<String> pendingRecoveryRooms=new java.util.HashSet<>();
    private volatile BookmarkSync bookmarkSync;
    private volatile String bookmarkStatus="Room bookmarks have not synced yet.";
    private final ExecutorService roomWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService cacheWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService archiveWorker = Executors.newSingleThreadExecutor();
    private final int account;
    private final ExecutorService fileWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService profileWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService fingerprintWorker=Executors.newFixedThreadPool(2);
    private final ExecutorService autoDownloadWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentHashMap<Integer, FileTransfer.Job> fileJobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, FileTransfer.Job> fileNames = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, FileTransfer.Job> automaticJobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> autoAttempted = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());
    private final SharedPreferences preferences;
    private final Object displayedSettingsLock=new Object();
    private volatile long displayedPrivacyEpoch;
    private volatile boolean displayedReady;
    private final java.util.concurrent.atomic.AtomicBoolean displayedSending=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong displayedRequests=new java.util.concurrent.atomic.AtomicLong();
    private final MonogramStore store;
    public final MonogramLinkPreviews linkPreviews;
    private final MonogramEncryptionPolicy encryptionPolicy;
    private volatile long encryptionPolicyEpoch;
    private final MonogramOmemo2Store modernStore;
    private final MonogramLegacyOmemoStore legacyStore;
    private final AccountVault vault;
    private volatile XmppSession session;
    private volatile long self;
    private volatile long accountEpoch;
    private volatile int state = ConnectionsManager.ConnectionStateConnecting;
    private boolean restoring;
    private volatile long sessionEpoch;
    private volatile ArchiveSync archiveSync;
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> profileVersions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Long> roomMetadataDirty=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final MonogramProfileLoads profileLoads=new MonogramProfileLoads();
    private final java.util.Set<Long> typingPeers = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final java.util.concurrent.ConcurrentHashMap<Long, String> typingAddresses = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long,com.negzero.monogram.xmpp.XmppContacts.PresenceState> presences=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long,Long> lastPresenceQueries=new java.util.HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean presencePublishQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Set<Long> presenceUpdates=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean presenceReset=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long presencePeer;
    private final java.util.Map<String, long[]> roomTypingPeers = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long mediaHistoryPeer;
    private int retrySeconds = 3;
    private final Runnable retryConnection = this::restore;
    private final Runnable scheduledWake = this::drainScheduledMessages;
    private final java.util.concurrent.atomic.AtomicBoolean scheduledDrainQueued=new java.util.concurrent.atomic.AtomicBoolean();

    private MonogramController(int account) {
        this.account = account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("monogram-" + account, Context.MODE_PRIVATE);
        store = new MonogramStore(account); modernStore = new MonogramOmemo2Store(account);
        linkPreviews=new MonogramLinkPreviews(account,preferences,store);
        encryptionPolicy=new MonogramEncryptionPolicy(preferences);
        privateReads=new MonogramReadSyncState(store,preferences);
        legacyStore=MonogramLegacyOmemoStore.application();vault = new AccountVault(account);
        if (preferences.contains("jid")) self = store.knownPeer(accountJid());
        debugLog.enabled(preferences.getBoolean("debug-message-xml",false));
        displayedReady=readReceiptsEnabled();
        preferences.edit().remove("media-auto-enabled").remove("media-auto-limit").apply();
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).addObserver(this::historyMediaLoaded,NotificationCenter.messagesDidLoad);
            NotificationCenter.getInstance(account).addObserver(this::sentLinkPreview,NotificationCenter.messageReceivedByServer);
        });
    }
    public final com.negzero.monogram.xmpp.XmppDebugLog debugLog=new com.negzero.monogram.xmpp.XmppDebugLog();
    public void debugCapture(boolean enabled) {
        preferences.edit().putBoolean("debug-message-xml",enabled).apply();debugLog.enabled(enabled);
    }
    public void inspectMessage(MessageObject object,java.util.function.Consumer<String> callback) {
        final int id=object.getId(),rendered=object.type;final long owner=accountEpoch;
        worker.execute(() -> {
            String details;
            try {
                if(owner!=accountEpoch) return;
                details="Monogram "+org.telegram.messenger.BuildVars.BUILD_VERSION_STRING+" · Android API "+android.os.Build.VERSION.SDK_INT+"\nDisplayed media type: "+rendered+"\nDisplayed as sticker: "+object.isAnyKindOfSticker()+"\n\n"+store.debugMessage(id,self);
            } catch(Exception unavailable) { details="Message diagnostics unavailable: "+unavailable.getClass().getSimpleName(); }
            final String result=details;
            AndroidUtilities.runOnUIThread(() -> { if(owner==accountEpoch) callback.accept(result); });
        });
    }
    public void messageMetadata(int id,java.util.function.Consumer<java.util.LinkedHashMap<String,String>> callback) {
        final long owner=accountEpoch;
        cacheWorker.execute(() -> {
            java.util.LinkedHashMap<String,String> values;
            try { if(owner!=accountEpoch) return;values=store.messageMetadata(id,self); }
            catch(Exception unavailable) { values=new java.util.LinkedHashMap<>(); }
            final java.util.LinkedHashMap<String,String> result=values;
            AndroidUtilities.runOnUIThread(() -> { if(owner==accountEpoch) callback.accept(result); });
        });
    }
    public int state() { return state; }
    public String mentionName(long dialog,TLRPC.User user) {
        String fallback=UserObject.getFirstName(user,false);
        if(dialog>=0 || accountJid().isEmpty()) return fallback;
        try { String nickname=store.mentionNames(dialog,self).get(user.id);return nickname==null || nickname.isEmpty() ? store.roomMentionFallback(user.id) : nickname; }
        catch(IllegalArgumentException unknownRoom) { try { return store.roomMentionFallback(user.id); } catch(IllegalArgumentException unknownUser) { return "Member"; } }
    }
    public java.util.Map<Long,String> mentionNames(long dialog) {
        if(dialog>=0 || accountJid().isEmpty()) return java.util.Collections.emptyMap();
        try { return store.mentionNames(dialog,self); } catch(IllegalArgumentException unknownRoom) { return java.util.Collections.emptyMap(); }
    }
    public String lastDisconnect() { return preferences.getString("last-disconnect-"+accountJid(),null); }
    public String accountJid() { return preferences.getString("jid", ""); }
    /** Non-secret connection template for creating a separate account on the same service. */
    public Account registrationTemplate() throws Exception {
        return new Account(accountJid(),preferences.getString("host",""),preferences.getInt("port",5222),"Monogram");
    }

    public void markLocalRead(long peer,java.util.Collection<Integer> anchors,boolean all,java.util.function.BiConsumer<java.util.Set<Integer>,Integer> callback) {
        markLocalRead(peer,anchors,all,true,callback);
    }
    public void markLocalRead(long peer,java.util.Collection<Integer> anchors,boolean all,boolean publish,java.util.function.BiConsumer<java.util.Set<Integer>,Integer> callback) {
        final long owner=accountEpoch;
        final long consent=displayedPrivacyEpoch,privateConsent=readSyncPrivacyEpoch;
        final String own=accountJid();
        final boolean sync=publish && readSyncEnabled();
        final boolean share=publish && readReceiptsEnabled() && (peer>0 || groupReadReceiptsEnabled());
        ArrayList<Integer> requested=new ArrayList<>(anchors);
        worker.execute(() -> {
            java.util.Set<Integer> read;
            MonogramReads.DisplayedCandidate candidate;
            MonogramReadSyncState.Candidate privateCandidate;
            try {
                if(owner!=accountEpoch || accountJid().isEmpty()) { AndroidUtilities.runOnUIThread(() -> callback.accept(null,-1));return; }
                MonogramReads.ReadResult result=store.reads.throughWithChanges(peer,requested,all);
                if(result==null) { AndroidUtilities.runOnUIThread(() -> callback.accept(null,-1));return; }
                read=result.read;
                privateCandidate=sync && peer!=0 && peer!=self ? privateReadCandidate(own,peer,result.newlyRead) : null;
                candidate=share && peer!=0 && peer!=self ? store.reads.displayedCandidate(peer,result.newlyRead) : null;
            } catch(Exception failure) {
                if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram local read: "+failure.getClass().getSimpleName());
                AndroidUtilities.runOnUIThread(() -> callback.accept(null,-1));return;
            }
            MessagesStorage.getInstance(account).processMonogramRead(peer,read,() -> owner==accountEpoch,count ->
                    AndroidUtilities.runOnUIThread(() -> {
                        callback.accept(owner==accountEpoch && count>=0 ? read : null,count);
                        if(privateCandidate!=null && count>=0) worker.execute(() -> {
                            synchronized(readSyncLock) {
                                try {
                                    if(sameAccount(owner,own) && privateConsent==readSyncPrivacyEpoch && readSyncEnabled()
                                            && privateReads.queue(own,privateCandidate)) syncOwnReads(false);
                                } catch(Exception failure) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram private read queue: "+failure.getClass().getSimpleName()); }
                            }
                        });
                        if(candidate!=null && count>=0) worker.execute(() -> {
                            if(owner==accountEpoch && consent==displayedPrivacyEpoch && displayedReady && readReceiptsEnabled()
                                    && store.reads.queueDisplayed(candidate)) flushDisplayedMarkers();
                        });
                    }));
        });
    }
    /** Coalesce only explicitly consented local reads; the native message outbox remains separate. */
    private void flushDisplayedMarkers() {
        displayedRequests.incrementAndGet();
        if(!displayedReady || !readReceiptsEnabled() || !displayedSending.compareAndSet(false,true)) return;
        final long owner=accountEpoch,consent=displayedPrivacyEpoch,revision=displayedRequests.get();
        sendWorker.execute(() -> {
            try {
                XmppSession expected=session;
                if(expected==null || !expected.connection().isAuthenticated()) return;
                for(MonogramReads.DisplayedCandidate candidate:store.reads.pendingDisplayed()) {
                    if(owner!=accountEpoch || consent!=displayedPrivacyEpoch || session!=expected || !displayedReady || !readReceiptsEnabled()) break;
                    if(!store.reads.isCurrentDisplayed(candidate)) continue;
                    if(candidate.peer<0 && !groupReadReceiptsEnabled()) continue;
                    if(expected.sendDisplayed(store.jid(candidate.peer),candidate.wire)
                            && owner==accountEpoch && consent==displayedPrivacyEpoch && session==expected && readReceiptsEnabled())
                        store.reads.acknowledgeDisplayed(candidate);
                }
            } catch(Exception failure) {
                if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram displayed marker: "+failure.getClass().getSimpleName());
            } finally {
                displayedSending.set(false);
                if(displayedRequests.get()!=revision) flushDisplayedMarkers();
            }
        });
    }
    private MonogramReadSyncState.Candidate privateReadCandidate(String own,long peer,java.util.Collection<Integer> newlyRead) {
        try { return privateReads.candidate(own,peer,newlyRead); }
        catch(Exception failure) {
            if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram private read candidate: "+failure.getClass().getSimpleName());return null;
        }
    }
    public boolean readSyncEnabled() {
        String own=accountJid();return !own.isEmpty() && preferences.getBoolean("private-read-enabled-"+own,true);
    }
    public boolean setReadSyncEnabled(boolean enabled) {
        synchronized(readSyncLock) {
            String own=accountJid();if(own.isEmpty()) return false;
            if(enabled==readSyncEnabled()) return true;
            if(!privateReads.setEnabled(own,enabled)) return false;
            readSyncPrivacyEpoch++;
            XmppSession current=session;if(current!=null) current.readSync.setEnabled(enabled);
            readSyncSnapshot=null;readSyncSession=null;
            if(enabled) syncOwnReads(true);
            return true;
        }
    }
    /** Coalesce PEP events and reuse the snapshot for archive catch-up and local reads. */
    private void syncOwnReads(boolean reload) { syncOwnReads(reload,false); }
    private void syncOwnReads(boolean reload,boolean reconcile) {
        if(reload) readSyncReload.set(true);
        if(reconcile) readSyncReconcile.set(true);
        readSyncRequests.incrementAndGet();
        if(!readSyncEnabled() || !readSyncSending.compareAndSet(false,true)) return;
        final long owner=accountEpoch,consent=readSyncPrivacyEpoch,revision=readSyncRequests.get();final String own=accountJid();
        readSyncWorker.execute(() -> {
            XmppSession expected=session;
            try {
                if(expected==null || !expected.connection().isAuthenticated() || !sameAccount(owner,own)) return;
                boolean refresh=readSyncReload.getAndSet(false),apply=readSyncReconcile.getAndSet(false);
                com.negzero.monogram.xmpp.XmppReadSync.Snapshot snapshot=readSyncSnapshot;
                if(refresh || snapshot==null || readSyncSession!=expected) { snapshot=expected.readSync.load();apply=true; }
                synchronized(readSyncLock) {
                    if(!currentReadSync(expected,owner,own,consent)) return;
                    readSyncSnapshot=snapshot;readSyncSession=expected;
                }
                if(apply) for(com.negzero.monogram.xmpp.XmppReadSync.Entry entry:snapshot.entries) {
                    if(entry.by.equals(own) && !snapshot.directIds) continue;
                    final MonogramReadSyncState.Candidate read;
                    synchronized(readSyncLock) {
                        if(!currentReadSync(expected,owner,own,consent)) return;
                        read=privateReads.observe(own,entry);
                    }
                    if(read!=null) AndroidUtilities.runOnUIThread(() -> {
                        if(currentReadSync(expected,owner,own,consent)) MessagesController.getInstance(account).applyMonogramSyncedRead(read.peer,read.id);
                    });
                }
                if(!snapshot.writable) return;
                for(MonogramReadSyncState.Candidate candidate:privateReads.pending(own)) {
                    synchronized(readSyncLock) {
                        if(!currentReadSync(expected,owner,own,consent)) return;
                        if(candidate.peer>0 && !snapshot.directIds || !privateReads.current(own,candidate)) continue;
                    }
                    expected.readSync.publish(candidate.entry,snapshot);
                    synchronized(readSyncLock) {
                        if(!currentReadSync(expected,owner,own,consent)) return;
                        privateReads.acknowledge(own,candidate);
                    }
                }
            } catch(Exception failure) {
                if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram private read sync: "+failure.getClass().getSimpleName());
            } finally {
                readSyncSending.set(false);
                if(readSyncRequests.get()!=revision) syncOwnReads(false);
            }
        });
    }
    private boolean currentReadSync(XmppSession expected,long owner,String own,long consent) {
        return session==expected && sameAccount(owner,own) && consent==readSyncPrivacyEpoch && readSyncEnabled() && expected.connection().isAuthenticated();
    }
    public java.util.Set<String> pendingContactRequests() {
        return new java.util.TreeSet<>(preferences.getStringSet("contact-requests-"+accountJid(),java.util.Collections.emptySet()));
    }
    void recordContactRequest(String owner,String address,boolean pending) {
        if(owner.equals(address)) return;
        String key="contact-requests-"+owner;
        java.util.Set<String> requests=new java.util.HashSet<>(preferences.getStringSet(key,java.util.Collections.emptySet()));
        boolean changed=pending ? requests.add(address) : requests.remove(address);
        if(!changed) return;
        preferences.edit().putStringSet(key,requests).apply();
        if(owner.equals(accountJid())) contactAuthorizationsChanged();
    }
    private void contactAuthorizationsChanged() {
        flushDisplayedMarkers();
        AndroidUtilities.runOnUIThread(() -> {
            XmppSession current=session;
            if(UserConfig.getInstance(account).isClientActivated() && current!=null && current.connection().isAuthenticated())
                ContactsController.getInstance(account).loadContacts(false,0);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.contactsDidLoad);
        });
    }
    public com.negzero.monogram.xmpp.XmppContacts.State contactAuthorization(String address) throws Exception { return session().contacts.state(address); }
    public static final class AccountInvitation {
        private final XmppSession session;
        private final long epoch,owner;
        private final com.negzero.monogram.xmpp.XmppInvitations.Request request;
        public final com.negzero.monogram.xmpp.XmppInvitations.Invitation result;
        public final boolean usernameRequired,rosterSubscription;
        private AccountInvitation(XmppSession session,long epoch,long owner,com.negzero.monogram.xmpp.XmppInvitations.Request request,
                                  com.negzero.monogram.xmpp.XmppInvitations.Invitation result) {
            this.session=session;this.epoch=epoch;this.owner=owner;this.request=request;this.result=result;
            usernameRequired=result==null && request.usernameRequired;rosterSubscription=result==null && request.rosterSubscription;
        }
    }
    public void accountInvitation(AccountInvitation pending,String username,boolean subscribe,java.util.function.BiConsumer<AccountInvitation,String> callback) {
        XmppSession expected=pending==null ? session : pending.session;long epoch=pending==null ? sessionEpoch : pending.epoch;long owner=pending==null ? accountEpoch : pending.owner;
        profileWorker.execute(() -> {
            AccountInvitation value=null;String error=null;
            com.negzero.monogram.xmpp.XmppInvitations.Request request=pending==null ? null : pending.request;
            try {
                if(expected==null || state!=ConnectionsManager.ConnectionStateConnected) throw new com.negzero.monogram.xmpp.XmppInvitations.InvitationException("Connect to XMPP before creating an invitation.");
                requireRoomSession(expected,epoch);if(owner!=accountEpoch) throw new IllegalStateException("Account changed");
                com.negzero.monogram.xmpp.XmppInvitations.Invitation result;
                if(request==null) { request=expected.invitations.begin();result=request.invitation; }
                else result=expected.invitations.complete(request,username,subscribe);
                requireRoomSession(expected,epoch);if(owner!=accountEpoch) throw new IllegalStateException("Account changed");value=new AccountInvitation(expected,epoch,owner,request,result);
            } catch(Exception failed) {
                if(expected!=null) expected.invitations.cancel(request);
                error=failed instanceof com.negzero.monogram.xmpp.XmppInvitations.InvitationException ? failed.getMessage() : com.negzero.monogram.xmpp.XmppRequestException.describe(failed);
            }
            final AccountInvitation invitation=value;final String failure=error;
            AndroidUtilities.runOnUIThread(() -> {
                if(expected!=session || epoch!=sessionEpoch || owner!=accountEpoch) { cancelAccountInvitation(invitation);callback.accept(null,"Account connection changed. Please retry."); }
                else callback.accept(invitation,failure);
            });
        });
    }
    public void cancelAccountInvitation(AccountInvitation pending) {
        if(pending!=null && pending.result==null) profileWorker.execute(() -> pending.session.invitations.cancel(pending.request));
    }
    public void contactAuthorization(String address,com.negzero.monogram.xmpp.XmppContacts.Action action,Consumer<String> completed) {
        final long owner=accountEpoch; final String jid=accountJid();
        worker.execute(() -> {
            String error=null;
            try {
                if(owner!=accountEpoch || !jid.equals(accountJid())) throw new IllegalStateException("Account changed");
                XmppSession expected=session(); expected.contacts.act(address,action);
                if(expected!=session || owner!=accountEpoch) throw new IllegalStateException("Account changed");
                if(action==com.negzero.monogram.xmpp.XmppContacts.Action.ACCEPT || action==com.negzero.monogram.xmpp.XmppContacts.Action.DENY
                        || action==com.negzero.monogram.xmpp.XmppContacts.Action.REVOKE) recordContactRequest(jid,address,false);
                contactAuthorizationsChanged();
            } catch(Exception failed) { error=com.negzero.monogram.xmpp.XmppRequestException.describe(failed); }
            final String failure=error;
            AndroidUtilities.runOnUIThread(() -> completed.accept(owner==accountEpoch ? failure : "Account changed"));
        });
    }
    public boolean typingEnabled() { return preferences.getBoolean("typing-enabled", true); }
    public boolean readReceiptsEnabled() {
        String jid=accountJid();return !jid.isEmpty() && preferences.getBoolean("read-receipts-enabled-"+jid,false);
    }
    public boolean groupReadReceiptsEnabled() {
        String jid=accountJid();return !jid.isEmpty() && preferences.getBoolean("group-read-receipts-enabled-"+jid,false);
    }
    public boolean setReadReceiptsEnabled(boolean enabled) {
        return setReadReceiptsPreference(enabled,false);
    }
    public boolean setGroupReadReceiptsEnabled(boolean enabled) { return setReadReceiptsPreference(enabled,true); }
    private boolean setReadReceiptsPreference(boolean enabled,boolean group) {
        synchronized(displayedSettingsLock) {
            String jid=accountJid();if(jid.isEmpty()) return false;
            boolean ready=readReceiptsEnabled();
            if(enabled==(group ? groupReadReceiptsEnabled() : ready) && displayedReady==ready) return true;
            if(!preferences.edit().putBoolean((group ? "group-read-receipts-enabled-" : "read-receipts-enabled-")+jid,enabled).commit()) return false;
            final boolean desiredReady=group ? ready : enabled;
            final long owner=accountEpoch,consent=++displayedPrivacyEpoch;
            displayedReady=false;
            XmppSession current=session;if(current!=null) current.setDisplayedMarkersEnabled(false);
            worker.execute(() -> {
                if(owner!=accountEpoch) return;
                try {
                    if(group) store.reads.clearPendingGroupDisplayed();else store.reads.clearPendingDisplayed();
                    synchronized(displayedSettingsLock) {
                        if(owner==accountEpoch && consent==displayedPrivacyEpoch) {
                            displayedReady=desiredReady;
                            XmppSession active=session;if(active!=null) active.setDisplayedMarkersEnabled(desiredReady);
                        }
                    }
                } catch(Exception failure) {
                    if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram displayed preference: "+failure.getClass().getSimpleName());
                }
            });
            return true;
        }
    }
    public boolean setTypingEnabled(boolean enabled) {
        if (!preferences.edit().putBoolean("typing-enabled", enabled).commit()) return false;
        XmppSession current = session;
        if (current != null) current.chatStates.setEnabled(enabled);
        return true;
    }
    public com.negzero.monogram.xmpp.XmppContacts.PresenceState presence(long peer) {
        return state==ConnectionsManager.ConnectionStateConnected ? presences.getOrDefault(peer,com.negzero.monogram.xmpp.XmppContacts.PresenceState.UNKNOWN)
                : com.negzero.monogram.xmpp.XmppContacts.PresenceState.UNKNOWN;
    }
    private void presenceChanged(long peer,XmppSession expected,long epoch,boolean query) {
        if(peer<=0 || peer==self || session!=expected || sessionEpoch!=epoch) return;
        try {
            com.negzero.monogram.xmpp.XmppContacts.PresenceState value=expected.contacts.presence(store.jid(peer));
            com.negzero.monogram.xmpp.XmppContacts.PresenceState old=presences.get(peer);
            if(value.visible && !value.available && old!=null && old.visible && !old.available && old.lastSeen>0) value=old;
            presences.put(peer,value); publishPresence(peer);
            long now=android.os.SystemClock.elapsedRealtime();
            if(!query || !value.visible || value.available || value.lastSeen>0 || now-lastPresenceQueries.getOrDefault(peer,Long.MIN_VALUE/2)<60000) return;
            lastPresenceQueries.put(peer,now);
            String jid=store.jid(peer);
            final com.negzero.monogram.xmpp.XmppContacts.PresenceState requested=value;
            profileWorker.execute(() -> {
                try {
                    com.negzero.monogram.xmpp.XmppContacts.PresenceState result=expected.contacts.lastSeen(jid);
                    worker.execute(() -> {
                        if(session!=expected || sessionEpoch!=epoch || state!=ConnectionsManager.ConnectionStateConnected || presences.get(peer)!=requested) return;
                        try {
                            com.negzero.monogram.xmpp.XmppContacts.PresenceState current=expected.contacts.presence(jid);
                            presences.put(peer,current.visible && !current.available ? result : current); publishPresence(peer);
                        } catch(Exception unavailable) { }
                    });
                } catch(Exception unavailable) { /* Last activity is optional and can be refused. */ }
            });
        } catch(Exception unknown) { presences.remove(peer); publishPresence(peer); }
    }
    private void publishPresence(long peer) { presenceUpdates.add(peer);publishPresence(); }
    private void publishPresence() {
        if(!presencePublishQueued.compareAndSet(false,true)) return;
        AndroidUtilities.runOnUIThread(() -> {
            presencePublishQueued.set(false);
            java.util.ArrayList<Long> changed=new java.util.ArrayList<>(presenceUpdates);
            presenceUpdates.removeAll(changed);
            MessagesController nativeController=MessagesController.getInstance(account);
            long now=System.currentTimeMillis()/1000;
            if(presenceReset.getAndSet(false)) {
                nativeController.onlinePrivacy.clear();
                for(TLRPC.User user:nativeController.getUsers().values()) MonogramPresence.apply(user,presence(user.id),now);
            } else for(long peer:changed) {
                nativeController.onlinePrivacy.remove(peer);
                MonogramPresence.apply(nativeController.getUser(peer),presence(peer),now);
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_STATUS);
        },100);
    }
    private void refreshPresence() {
        XmppSession expected=session; long epoch=sessionEpoch;
        if(expected==null) return;
        for(long peer:store.peers()) if(peer!=self) presenceChanged(peer,expected,epoch,peer==presencePeer);
    }
    public void chatOpened(long peer) {
        presencePeer=peer; chatState(peer, 0);
        worker.execute(() -> { XmppSession expected=session; if(expected!=null) presenceChanged(peer,expected,sessionEpoch,true); warnIfPlain(peer); });
    }
    public void typingInput(long peer, boolean composing) { chatState(peer, composing ? 1 : 0); }
    public void chatInactive(long peer) { chatState(peer, 2); }
    public void chatGone(long peer) { if(presencePeer==peer) presencePeer=0; chatState(peer, 3); }
    private void chatState(long peer, int action) {
        XmppSession current = session;
        if (peer == 0 || peer == self || current == null || !current.connection().isAuthenticated() || !typingEnabled()) return;
        try {
            String jid = typingAddresses.computeIfAbsent(peer, store::jid);
            if (action == 1) current.chatStates.input(jid, true);
            else if (action == 2) current.chatStates.inactive(jid);
            else if (action == 3) current.chatStates.gone(jid);
            else current.chatStates.open(jid);
        } catch (IllegalArgumentException unknown) { /* A deleted/local-only conversation has no remote state. */ }
    }
    /** Live XEP-0085 state is authoritative for the header, independent of Telegram's update queues. */
    public CharSequence chatTypingSubtitle(long dialog) {
        if(state!=ConnectionsManager.ConnectionStateConnected) return null;
        if(dialog>0) return typingPeers.contains(dialog) ? LocaleController.getString(R.string.Typing) : null;
        java.util.TreeSet<Long> users=new java.util.TreeSet<>();
        for(long[] entry:roomTypingPeers.values()) if(entry[0]==dialog) users.add(entry[1]);
        if(users.isEmpty()) return null;
        StringBuilder names=new StringBuilder();int named=0;
        for(long id:users) {
            TLRPC.User user=MessagesController.getInstance(account).getUser(id);
            if(user==null) continue;
            if(names.length()>0) names.append(", ");
            names.append(org.telegram.messenger.UserObject.getFirstName(user));
            if(++named==2) break;
        }
        if(names.length()==0) return LocaleController.getString(R.string.Typing);
        if(users.size()>2) return String.format(LocaleController.getPluralString("AndMoreTypingGroup",users.size()-2),names.toString(),users.size()-2);
        return users.size()==1 ? LocaleController.formatString("IsTypingGroup",R.string.IsTypingGroup,names.toString())
                : LocaleController.formatString("AreTypingGroup",R.string.AreTypingGroup,names.toString());
    }
    private void publishTyping(long peer, boolean composing) { publishTyping(peer, peer, composing); }
    private void publishTyping(long dialog, long user, boolean composing) {
        TLRPC.SendMessageAction action = composing ? new TLRPC.TL_sendMessageTypingAction() : new TLRPC.TL_sendMessageCancelAction();
        TLRPC.Update update;
        if (dialog < 0) {
            TL_update.TL_updateChatUserTyping room = new TL_update.TL_updateChatUserTyping();
            room.chat_id = -dialog; room.from_id = new TLRPC.TL_peerUser(); room.from_id.user_id = user; room.action = action;
            update = room;
        } else {
            TL_update.TL_updateUserTyping direct = new TL_update.TL_updateUserTyping();
            direct.user_id = user; direct.action = action; update = direct;
        }
        ArrayList<TLRPC.Update> updates = new ArrayList<>(); updates.add(update);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        if (composing) users.add(store.user(user, self));
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        if (dialog < 0) chats.add(store.roomChat(dialog,self));
        // Publish on the main thread as well: a header must not wait for user-cache insertion / printing-string rebuilds.
        final long owner=accountEpoch;
        AndroidUtilities.runOnUIThread(() -> {
            if(owner!=accountEpoch) return;
            MessagesController.getInstance(account).putUsers(users,false);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_USER_PRINT);
        });
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).processUpdateArray(updates, users, chats, false, now()));
    }
    private void clearRoomTyping() {
        for (long[] entry : roomTypingPeers.values()) publishTyping(entry[0], entry[1], false);
        roomTypingPeers.clear();
    }
    private void state(int value) {
        state = value;
        if (value != ConnectionsManager.ConnectionStateConnected) {
            presences.clear(); clearRoomTyping(); rosterLoads.clear();rosterAttempts.clear();roomMetadataDirty.clear();
            for (long peer : new ArrayList<>(typingPeers)) if (typingPeers.remove(peer)) publishTyping(peer, false);
        }
        presenceReset.set(true);publishPresence();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didUpdateConnectionState));
    }
    public void restore() { restore(false); }
    public void networkChanged() {
        XmppSession current = session;
        if (current != null && current.connection().isAuthenticated()) current.checkConnection();
        else restore();
    }
    public void reconnect() { restore(true); }
    private synchronized void restore(boolean force) {
        if (restoring || !preferences.contains("jid")) return;
        if (!force && session != null && session.connection().isAuthenticated()) return;
        restoring = true;
        AndroidUtilities.cancelRunOnUIThread(retryConnection);
        state(ConnectionsManager.ConnectionStateConnecting);
        worker.execute(() -> {
            try {
                XmppSession existing = session;
                if (!force && existing != null) {
                    // Retain stream resumption and initialized keys on automatic reconnects.
                    if (!existing.connection().isConnected()) existing.connection().connect();
                    if (!existing.connection().isAuthenticated()) existing.connection().login();
                } else connect(new Account(preferences.getString("jid", ""), preferences.getString("host", ""), preferences.getInt("port", 5222), "Monogram"),
                        vault.decrypt(preferences.getString("credential", "")));
            }
            catch (Exception e) { state(ConnectionsManager.ConnectionStateWaitingForNetwork); retryConnection(); }
            finally { synchronized (this) { restoring = false; } }
        });
    }
    private void retryConnection() {
        if (!preferences.contains("jid")) return;
        AndroidUtilities.cancelRunOnUIThread(retryConnection);
        AndroidUtilities.runOnUIThread(retryConnection, retrySeconds * 1000L);
        retrySeconds = Math.min(60, retrySeconds * 2);
    }
    private void connect(Account details, String password) throws Exception {
        if (session != null) { MonogramCallService.unbind(account,session);session.close(); session = null; }
        joinedRooms.clear();pendingRecoveryRooms.clear();bookmarkSync=null;pendingProfileUsers.clear();visibleHistory.clear();calendarHistory.clear();calendarWindows.clear();
        final long epoch = ++sessionEpoch, owner=accountEpoch;
        autoAttempted.clear();
        state(ConnectionsManager.ConnectionStateConnecting);
        AndroidSmackInitializer.initialize(ApplicationLoader.applicationContext);
        XmppSession.initializeOmemo(new File(ApplicationLoader.getFilesDirFixed(), "monogram-omemo"),legacyStore);
        int device = preferences.getInt("device", 0);
        // Existing accounts have no manifest and follow the unchanged path. Restored state
        // must bind before any missing device ID can be allocated or connection constructed.
        final com.negzero.monogram.xmpp.XmppRecovery recovery=MonogramRecoveryStore.load(account,details.jid.toString(),device,modernStore,legacyStore);
        if (device == 0) {
            device = org.jivesoftware.smackx.omemo.OmemoManager.randomDeviceId();
            if (!preferences.edit().putInt("device", device).commit()) throw new java.io.IOException("Cannot save device identity");
        }
        self = store.peer(details.jid.toString());
        if (UserConfig.getInstance(account).isClientActivated() && UserConfig.getInstance(account).getClientUserId() == self
                && details.jid.toString().equals(accountJid())) publishProfile(self, epoch);
        final int modernDevice = modernStore.device(details.jid.toString());
        XmppSession connecting = new XmppSession(details, password, device,
                new File(ApplicationLoader.getFilesDirFixed(), "monogram-trust-" + account),
                new com.negzero.monogram.xmpp.Omemo2Device(details.jid.toString(),modernDevice,
                        () -> modernStore.begin(details.jid.toString(),modernDevice)),recovery,
                new XmppSession.Listener() {
                    @Override public void debugMessageXml(boolean outgoing,org.jivesoftware.smack.packet.Message stanza) {
                        if(owner==accountEpoch && debugLog.enabled()) debugLog.record(outgoing ? "Sent XML" : "Received XML",com.negzero.monogram.xmpp.XmppXml.string(stanza));
                    }
                    @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {}
                    @Override public void message(XmppMessage message) { received(owner,details.jid.toString(),message,null); }
                    @Override public void message(XmppMessage message,Runnable accepted) { received(owner,details.jid.toString(),message,accepted); }
                    @Override public void messagePending(XmppMessage message) {
                        worker.execute(() -> {
                            if (!sameAccount(owner,details.jid.toString())) return;
                            int id=store.savePending(message);
                            decryptionTimeout(owner,details.jid.toString(),id,() -> incoming(message));
                        });
                    }
                    @Override public void receipt(com.negzero.monogram.xmpp.MessageReceipt receipt) { receivedReceipt(owner,details.jid.toString(),receipt); }
                    @Override public void roomDisplayed(com.negzero.monogram.xmpp.RoomDisplayedMarker marker) {
                        receivedRoomDisplayed(owner,details.jid.toString(),marker);
                    }
                    @Override public void ownDisplayed(org.jxmpp.jid.EntityBareJid peer,String id) {
                        worker.execute(() -> {
                            if(sameAccount(owner,details.jid.toString())) applyOwnDisplayed(owner,details.jid.toString(),store.knownPeer(peer.toString()),id);
                        });
                    }
                    @Override public void roomMessage(com.negzero.monogram.xmpp.XmppRoomMessage message) {
                        receivedRoom(owner,details.jid.toString(),message,null);
                    }
                    @Override public void roomMessage(com.negzero.monogram.xmpp.XmppRoomMessage message,Runnable accepted) {
                        receivedRoom(owner,details.jid.toString(),message,accepted);
                    }
                    @Override public void roomMessagePending(com.negzero.monogram.xmpp.XmppRoomMessage message) {
                        worker.execute(() -> {
                            if (!sameAccount(owner,details.jid.toString())) return;
                            int id=store.savePending(message,self);
                            decryptionTimeout(owner,details.jid.toString(),id,() -> incomingRoom(message));
                        });
                    }
                    @Override public void contactRequest(String address,boolean pending) {
                        worker.execute(() -> { if(sessionEpoch==epoch) recordContactRequest(details.jid.toString(),address,pending); });
                    }
                    @Override public void roomChanged(com.negzero.monogram.xmpp.XmppRooms.State room) {
                        worker.execute(() -> { if (sessionEpoch==epoch && session!=null) {
                            store.roomState(room,self); if(room.joined) flushPendingSends(); long dialog=store.knownRoom(room.room.toString());
                            if(dialog!=0 && (room.title!=null || room.description!=null)) profileVersions.merge(dialog,1L,Long::sum);
                            if(dialog!=0) { publishRoom(dialog); if(room.joined && joinedRooms.add(dialog)) { refreshRoomRoster(dialog,true); queueRoomArchive(dialog,session); } else if(!room.joined) joinedRooms.remove(dialog); }
                            if(dialog!=0 && pendingRecoveryRooms.remove(room.room.toString())) session.recoverRoomIncoming(room.room.toString());
                        } });
                    }
                    @Override public void roomMetadataChanged(String room) {
                        worker.execute(() -> { if(sessionEpoch!=epoch || session==null) return;
                            long dialog=store.knownRoom(room);if(dialog!=0) { roomMetadataDirty.add(dialog);changedProfile(dialog); }
                        });
                    }
                    @Override public void roomInviteFailed(String room,String target,String condition) {
                        worker.execute(() -> {
                            if(sessionEpoch!=epoch || session==null) return;
                            long dialog=store.knownRoom(room);if(dialog==0) return;
                            String detail="Could not invite "+target+" to "+store.room(dialog).title+": server replied "+condition+".";
                            preferences.edit().putString("last-room-invite-error-"+dialog,detail).apply();
                            AndroidUtilities.runOnUIThread(() -> {
                                if(sessionEpoch!=epoch || !org.telegram.ui.LaunchActivity.isActive) return;
                                org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getSafeLastFragment();
                                if(fragment!=null && fragment.getCurrentAccount()==account && fragment.getParentActivity()!=null)
                                    org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip,detail).show();
                            });
                        });
                    }
                    @Override public void reactionRejected(String from,String id) {
                        worker.execute(() -> { if(sessionEpoch!=epoch || session==null) return;
                            try { java.util.Set<Integer> changed=store.reactions.reject(from,id,self);
                                if(!changed.isEmpty()) { long peer=store.getMessage(changed.iterator().next(),self).dialog_id; publishReactions(peer,changed);
                                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin,org.telegram.ui.Components.Bulletin.TYPE_ERROR,"The server or recipient rejected your reaction. The previous reactions were restored.")); }
                            } catch(Exception ignored) { /* An unrelated/invalid error cannot change reaction state. */ }
                        });
                    }
                    @Override public void blockedChanged(java.util.List<String> ignored) {
                        worker.execute(() -> {
                            XmppSession current=session;
                            if(current!=null && sessionEpoch==epoch && sameAccount(owner,details.jid.toString()))
                                rememberBlocked(current,epoch);
                        });
                    }
                    @Override public void readSyncChanged() {
                        worker.execute(() -> { if(sessionEpoch==epoch && session!=null && sameAccount(owner,details.jid.toString())) syncOwnReads(true); });
                    }
                    @Override public void bookmarksChanged() {
                        worker.execute(() -> { if(sessionEpoch==epoch && session!=null) syncBookmarks(session,epoch,null); });
                    }
                    @Override public void storiesChanged(String address) {
                        if(sessionEpoch==epoch) stories().changed(address);
                    }
                    @Override public void profileChanged(org.jxmpp.jid.Jid address) {
                        worker.execute(() -> { if(sessionEpoch!=epoch || session==null) return;
                            long peer=address.hasResource() ? store.knownOccupant(address.toString()) : 0;
                            if(peer==0) peer=store.knownPeer(address.toString());
                            if(peer==0 && address.hasResource() && store.knownRoom(address.asBareJid().toString())==0) peer=store.knownPeer(address.asBareJid().toString());
                            if(peer!=0) changedProfile(peer);
                            if(!address.hasResource()) { long room=store.knownRoom(address.toString()); if(room!=0) changedProfile(room); }
                        });
                    }
                    @Override public void typing(org.jxmpp.jid.EntityBareJid jid, boolean composing) {
                        worker.execute(() -> {
                            if (sessionEpoch != epoch || session == null || !preferences.contains("jid") || composing && state != ConnectionsManager.ConnectionStateConnected) return;
                            long peer = store.knownPeer(jid.toString());
                            if (peer == 0 || peer == self) return;
                            if (composing) typingPeers.add(peer); else typingPeers.remove(peer);
                            publishTyping(peer, composing);
                        });
                    }
                    @Override public void roomInvitation(String room,String inviter,String reason,String password) {
                        worker.execute(() -> {
                            if(sessionEpoch!=epoch || session==null || !preferences.contains("jid")) return;
                            try {
                                if(room.length()>3071 || inviter.length()>3071 || password!=null && password.length()>4096) return;
                                org.json.JSONObject invites=roomInvitationData();
                                if(!invites.has(room) && invites.length()>=64) return;
                                org.json.JSONObject invite=new org.json.JSONObject();
                                invite.put("inviter",inviter); invite.put("reason",reason==null ? "" : reason.substring(0,Math.min(512,reason.length())));
                                org.json.JSONObject previous=invites.optJSONObject(room);
                                invite.put("password",password==null || password.isEmpty() ? previous==null ? "" : previous.optString("password") : vault.encrypt(password));
                                invites.put(room,invite);
                                preferences.edit().putString(roomInvitationsKey(),invites.toString()).putBoolean("has-"+roomInvitationsKey(),invites.length()>0).commit();
                                AndroidUtilities.runOnUIThread(() -> {
                                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_CHAT_MEMBERS);
                                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.contactsDidLoad);
                                });
                            } catch(Exception failed) { FileLog.e(failed); }
                        });
                    }
                    @Override public void roomTyping(org.jxmpp.jid.EntityFullJid occupant, boolean composing) {
                        worker.execute(() -> {
                            if (sessionEpoch != epoch || session == null || !preferences.contains("jid") || composing && state != ConnectionsManager.ConnectionStateConnected) return;
                            String address = occupant.toString();
                            long[] previous = roomTypingPeers.get(address);
                            if (!composing) {
                                if (previous != null) { roomTypingPeers.remove(address); publishTyping(previous[0], previous[1], false); }
                                return;
                            }
                            long room = store.knownRoom(occupant.asBareJid().toString());
                            long user = store.knownOccupant(address);
                            if (room == 0 || user == 0 || user == self) return;
                            if (previous != null && previous[1] != user) publishTyping(previous[0], previous[1], false);
                            roomTypingPeers.put(address, new long[] {room, user}); publishTyping(room, user, true);
                        });
                    }
                    @Override public void disconnected(Exception error) {
                        worker.execute(() -> {
                            if (sessionEpoch != epoch || session == null) return;
                            preferences.edit().putString("last-disconnect-"+details.jid,new java.util.Date()+" · "+(error==null ? "Connection closed" : com.negzero.monogram.xmpp.XmppRequestException.describe(error))).apply();
                            cancelSearchLoads(archiveSync);archiveSync = null; joinedRooms.clear(); bookmarkSync=null;
                            AndroidUtilities.cancelRunOnUIThread(scheduledWake);
                            state(ConnectionsManager.ConnectionStateWaitingForNetwork); retryConnection();
                        });
                    }
                    @Override public void reconnected(boolean resumed) {
                        worker.execute(() -> {
                            if (sessionEpoch != epoch || session == null) return;
                            AndroidUtilities.cancelRunOnUIThread(retryConnection); retrySeconds = 3;
                            state(ConnectionsManager.ConnectionStateConnected); lastPresenceQueries.clear(); refreshPresence(); MonogramPacks.maybeAutoSync(account); recoverPendingSends(); drainScheduledMessages(); startArchiveSync(session); syncBookmarks(session,epoch,null); refreshBlocked(session,epoch); syncOwnReads(true);
                        });
                    }
                });
        // Android owns retries; do not race Smack's reconnect thread with replacement sessions.
        org.jivesoftware.smack.ReconnectionManager.getInstanceFor(connecting.connection()).disableAutomaticReconnection();
        Roster roster = Roster.getInstanceFor(connecting.connection());
        roster.addRosterListener(new org.jivesoftware.smack.roster.RosterListener() {
            private void changed(java.util.Collection<org.jxmpp.jid.Jid> addresses) {
                worker.execute(() -> {
                    if (sessionEpoch != epoch || session != connecting) return;
                    for (org.jxmpp.jid.Jid jid : addresses) {
                        RosterEntry entry = roster.getEntry(jid.asBareJid());
                        if(entry!=null && entry.canSeeMyPresence()) recordContactRequest(details.jid.toString(),jid.asBareJid().toString(),false);
                        long peer = store.knownPeer(jid.asBareJid().toString());
                        if (peer == 0 && entry != null) peer = store.peer(jid.asBareJid().toString());
                        if (peer != 0) { store.rosterName(peer, entry == null ? "" : entry.getName()); publishProfile(peer, epoch); refreshProfile(peer); MonogramController.this.presenceChanged(peer,connecting,epoch,peer==presencePeer); }
                    }
                    contactAuthorizationsChanged();
                });
            }
            @Override public void entriesAdded(java.util.Collection<org.jxmpp.jid.Jid> addresses) { changed(addresses); }
            @Override public void entriesUpdated(java.util.Collection<org.jxmpp.jid.Jid> addresses) { changed(addresses); }
            @Override public void entriesDeleted(java.util.Collection<org.jxmpp.jid.Jid> addresses) { changed(addresses); }
            @Override public void presenceChanged(org.jivesoftware.smack.packet.Presence presence) {
                if(presence.getFrom()==null) return;
                String jid=presence.getFrom().asBareJid().toString();
                worker.execute(() -> {
                    if(session!=connecting || sessionEpoch!=epoch) return;
                    long peer=store.knownPeer(jid);
                    if(peer>0) MonogramController.this.presenceChanged(peer,connecting,epoch,peer==presencePeer);
                });
            }
        });
        MonogramCallService.bind(account,connecting,() -> sessionEpoch==epoch && sameAccount(owner,details.jid.toString()));
        bindUnavailableRecipients(connecting,epoch,owner,details.jid.toString());
        for(long room:store.rooms()) connecting.rooms.remember(store.jid(room));
        connecting.chatStates.setEnabled(typingEnabled());
        connecting.recoverIncoming();
        connecting.readSync.setEnabled(preferences.getBoolean("private-read-enabled-"+details.jid,true));
        connecting.connect();
        synchronized(displayedSettingsLock) {
            session = connecting;
            connecting.setDisplayedMarkersEnabled(displayedReady && readReceiptsEnabled());
        }
        synchronized(readSyncLock) { connecting.readSync.setEnabled(preferences.getBoolean("private-read-enabled-"+details.jid,true)); }
        for (RosterEntry entry : Roster.getInstanceFor(connecting.connection()).getEntries()) {
            long peer = store.peer(entry.getJid().toString()); store.rosterName(peer, entry.getName());
        }
        refreshProfile(self);
        for (long peer : store.peers()) if (peer != self) refreshProfile(peer);
        state(ConnectionsManager.ConnectionStateConnected); lastPresenceQueries.clear(); refreshPresence(); MonogramPacks.maybeAutoSync(account);
        AndroidUtilities.cancelRunOnUIThread(retryConnection); retrySeconds = 3;
        recoverPendingSends(); drainScheduledMessages();
        startArchiveSync(connecting); syncBookmarks(connecting,epoch,null); refreshBlocked(connecting,epoch); syncOwnReads(true);
    }
    private void rejoinRooms(XmppSession expected,long epoch) {
        roomWorker.execute(() -> {
            for (long dialog:store.rooms()) {
                if (session!=expected || sessionEpoch!=epoch || !expected.connection().isAuthenticated()) return;
                try {
                    MonogramStore.Room room=store.room(dialog);
                    com.negzero.monogram.xmpp.XmppRooms.State current=expected.rooms.state(room.jid);
                    if(!room.autojoin) { if(current.joined) expected.rooms.leave(room.jid); continue; }
                    if(current.joined) { if(!room.nickname.equals(current.nickname)) expected.rooms.nickname(room.jid,room.nickname); continue; }
                    String encrypted=preferences.getString("room-password-"+dialog,"");
                    expected.rooms.join(room.jid,room.nickname,encrypted.isEmpty() ? null : vault.decrypt(encrypted));
                    flushPendingSends();
                } catch(Exception failed) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram room rejoin: "+failed.getClass().getSimpleName()); }
            }
        });
    }
    private static final class BookmarkSync {
        final XmppSession session; final long epoch,owner;
        final ArrayList<RequestDelegate> callbacks=new ArrayList<>(); boolean again; Exception cacheFailure;
        BookmarkSync(XmppSession session,long epoch,long owner) { this.session=session; this.epoch=epoch; this.owner=owner; }
    }
    private boolean currentBookmarks(BookmarkSync sync) { return bookmarkSync==sync && session==sync.session && sessionEpoch==sync.epoch && accountEpoch==sync.owner && sync.session.connection().isAuthenticated(); }
    public String bookmarkStatus() { return bookmarkStatus; }
    public void syncRoomBookmarks(RequestDelegate callback) { worker.execute(() -> {
        try { syncBookmarks(session(),sessionEpoch,callback); } catch(Exception failed) { bookmarkStatus="Reconnect your XMPP account before syncing rooms."; if(callback!=null) roomResult(callback,failed); }
    }); }
    private void syncBookmarks(XmppSession expected,long epoch,RequestDelegate callback) {
        if(session!=expected || sessionEpoch!=epoch) return;
        if(bookmarkSync!=null && currentBookmarks(bookmarkSync)) {
            bookmarkSync.again=true; if(callback!=null) bookmarkSync.callbacks.add(callback); return;
        }
        BookmarkSync sync=new BookmarkSync(expected,epoch,accountEpoch); bookmarkSync=sync;
        if(callback!=null) sync.callbacks.add(callback); loadBookmarks(sync);
    }
    private void loadBookmarks(BookmarkSync sync) {
        bookmarkStatus="Synchronizing room bookmarks…";
        bookmarkWorker.execute(() -> {
            try {
                com.negzero.monogram.xmpp.XmppBookmarks.Snapshot snapshot=sync.session.bookmarks.load();
                worker.execute(() -> {
                    if(!currentBookmarks(sync)) return;
                    if(sync.again) { sync.again=false; loadBookmarks(sync); return; }
                    try {
                        java.util.Set<String> present=new java.util.HashSet<>();
                        for(com.negzero.monogram.xmpp.XmppBookmarks.Bookmark bookmark:snapshot.bookmarks) {
                            present.add(bookmark.jid);
                            store.importBookmark(bookmark,vault.encrypt(bookmark.xml()),ownUser().first_name);
                            long dialog=store.knownRoom(bookmark.jid); sync.session.rooms.remember(bookmark.jid);
                            String passwordKey="room-password-"+dialog,localKey="room-password-local-"+dialog;
                            boolean local=preferences.getBoolean(localKey,!preferences.getString(passwordKey,"").isEmpty());
                            if(!local && !preferences.edit().putString(passwordKey,bookmark.password.isEmpty() ? "" : vault.encrypt(bookmark.password)).putBoolean(localKey,false).commit())
                                throw new java.io.IOException("Cannot save imported room password");
                            publishRoom(dialog);
                        }
                        store.missingBookmarks(present);
                        rejoinRooms(sync.session,sync.epoch);
                        TLRPC.TL_messages_dialogs cached=dialogs();
                        publishDialogs(cached,() -> accountEpoch==sync.owner && sessionEpoch==sync.epoch,null);
                        ArrayList<MonogramStore.PendingBookmark> pending=store.pendingBookmarks();
                        if(!snapshot.writable) { finishBookmarks(sync,"Using older server bookmarks; local changes stay on this device.",null); return; }
                        publishBookmarks(sync,pending);
                    } catch(Exception failed) { rejoinRooms(sync.session,sync.epoch); finishBookmarks(sync,"Could not sync bookmarks. Cached rooms remain available.",failed); }
                });
            } catch(Exception failed) { worker.execute(() -> {
                if(!currentBookmarks(sync)) return; rejoinRooms(sync.session,sync.epoch);
                finishBookmarks(sync,"Could not sync bookmarks. Cached rooms remain available.",failed);
            }); }
        });
    }
    private void publishBookmarks(BookmarkSync sync,ArrayList<MonogramStore.PendingBookmark> pending) {
        // Decode private cached XML on the account worker, then perform only network calls on the bookmark worker.
        ArrayList<com.negzero.monogram.xmpp.XmppBookmarks.Bookmark> values=new ArrayList<>();
        try {
            for(MonogramStore.PendingBookmark row:pending) {
                com.negzero.monogram.xmpp.XmppBookmarks.Bookmark bookmark=row.payload==null
                        ? com.negzero.monogram.xmpp.XmppBookmarks.Bookmark.create(row.jid,row.nickname,row.autojoin)
                        : com.negzero.monogram.xmpp.XmppBookmarks.Bookmark.parse(row.jid,vault.decrypt(row.payload)).edit(row.nickname,row.autojoin);
                values.add(bookmark);
            }
        } catch(Exception failed) { finishBookmarks(sync,"Could not read saved bookmarks. Local room choices are retained.",failed); return; }
        bookmarkWorker.execute(() -> {
            Exception error=null;
            for(int i=0;i<values.size();i++) {
                if(!currentBookmarks(sync)) return;
                MonogramStore.PendingBookmark row=pending.get(i); com.negzero.monogram.xmpp.XmppBookmarks.Bookmark value=values.get(i);
                try {
                    com.negzero.monogram.xmpp.XmppBookmarks.Bookmark published=sync.session.bookmarks.save(value);
                    worker.execute(() -> { if(currentBookmarks(sync)) try { store.bookmarkSaved(row.dialog,row.revision,vault.encrypt(published.xml())); }
                        catch(Exception failed) { sync.cacheFailure=failed; } });
                } catch(Exception failed) { error=failed; break; }
            }
            final Exception failed=error;
            worker.execute(() -> { if(currentBookmarks(sync)) {
                Exception problem=failed!=null ? failed : sync.cacheFailure;
                finishBookmarks(sync,problem==null ? "Room bookmarks are synchronized with your account." : "Could not finish syncing bookmarks. Local changes will retry on reconnect.",problem);
            } });
        });
    }
    private void finishBookmarks(BookmarkSync sync,String status,Exception error) {
        if(!currentBookmarks(sync)) return;
        if(error==null && sync.again) { sync.again=false; loadBookmarks(sync); return; }
        bookmarkStatus=status; bookmarkSync=null;
        for(RequestDelegate callback:sync.callbacks) {
            if(error==null) AndroidUtilities.runOnUIThread(() -> callback.run(new TLRPC.TL_boolTrue(),null)); else roomResult(callback,error);
        }
    }
    private void dirtyRoomBookmark(long dialog) {
        store.dirtyBookmark(dialog); XmppSession expected=session;
        if(expected!=null && expected.connection().isAuthenticated()) syncBookmarks(expected,sessionEpoch,null);
    }
    public boolean isRoom(long dialog) { return dialog<0 && store.rooms().contains(dialog); }
    public String roomNickname(long dialog) { return store.room(dialog).nickname; }
    public String lastRoomInviteError(long dialog) { return preferences.getString("last-room-invite-error-"+dialog,null); }
    private String roomInvitationsKey() { return "room-invitations-"+accountJid(); }
    private org.json.JSONObject roomInvitationData() {
        try { return new org.json.JSONObject(preferences.getString(roomInvitationsKey(),"{}")); }
        catch(org.json.JSONException invalid) { return new org.json.JSONObject(); }
    }
    public boolean hasRoomInvitations() { return preferences.getBoolean("has-"+roomInvitationsKey(),false); }
    public java.util.List<String> pendingRoomInvitations() {
        ArrayList<String> result=new ArrayList<>(); roomInvitationData().keys().forEachRemaining(result::add); return result;
    }
    public String roomInvitationDescription(String room) {
        org.json.JSONObject invite=roomInvitationData().optJSONObject(room);
        return invite==null ? "" : "Invited by "+invite.optString("inviter")+(invite.optString("reason").isEmpty() ? "" : "\n"+invite.optString("reason"));
    }
    public String roomInvitationPassword(String room) {
        org.json.JSONObject invite=roomInvitationData().optJSONObject(room);
        String encrypted=invite==null ? "" : invite.optString("password");
        try { return encrypted.isEmpty() ? "" : vault.decrypt(encrypted); } catch(Exception unavailable) { return ""; }
    }
    public void dismissRoomInvitation(String room) {
        final long owner=accountEpoch;
        worker.execute(() -> { if(accountEpoch!=owner) return; org.json.JSONObject invites=roomInvitationData(); invites.remove(room);
            preferences.edit().putString(roomInvitationsKey(),invites.toString()).putBoolean("has-"+roomInvitationsKey(),invites.length()>0).commit();
            AndroidUtilities.runOnUIThread(() -> {
                                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_CHAT_MEMBERS);
                                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.contactsDidLoad);
                                }); });
    }
    public com.negzero.monogram.xmpp.XmppRooms.State roomState(long room) {
        try { return session().rooms.state(store.jid(room)); } catch(Exception unavailable) { return null; }
    }
    public TLRPC.User roomOccupant(String address) {
        long peer=store.knownOccupant(address); return peer==0 ? null : store.user(peer,self);
    }
    public void createRoom(String address,String nickname,String title,RequestDelegate callback) {
        final long owner=accountEpoch,epoch=sessionEpoch;
        roomWorker.execute(() -> {
            try {
                XmppSession expected=session();
                if(accountEpoch!=owner || sessionEpoch!=epoch) throw new IllegalStateException("Account changed");
                if(!org.jxmpp.jid.impl.JidCreate.from(address).isEntityBareJid()) throw new IllegalArgumentException("Enter a bare room address.");
                expected.rooms.create(address,nickname,title);
                worker.execute(() -> { if(accountEpoch==owner && sessionEpoch==epoch && session==expected) openRoom(address,nickname,"",callback); });
            } catch(Exception failed) { roomResult(callback,failed); }
        });
    }
    private String roomError(Exception error) {
        if((error instanceof IllegalArgumentException || error instanceof IllegalStateException) && error.getMessage()!=null) return error.getMessage();
        return com.negzero.monogram.xmpp.XmppRequestException.describe(error);
    }
    private interface RoomOperation { void run(com.negzero.monogram.xmpp.XmppRooms rooms,String address) throws Exception; }
    private void roomOperation(long room,RoomOperation operation,Consumer<String> callback) {
        final long owner=accountEpoch,epoch=sessionEpoch;
        roomWorker.execute(() -> {
            String failure=null;
            try {
                if(accountEpoch!=owner || sessionEpoch!=epoch) throw new IllegalStateException("Account changed");
                XmppSession expected=session(); operation.run(expected.rooms,store.jid(room));
                if(accountEpoch!=owner || sessionEpoch!=epoch || session!=expected) throw new IllegalStateException("Account changed");
            } catch(Exception failed) { failure=roomError(failed); }
            final String error=failure;
            AndroidUtilities.runOnUIThread(() -> { if(accountEpoch==owner && sessionEpoch==epoch) callback.accept(error); });
        });
    }
    public void roomAffiliations(long room,java.util.function.BiConsumer<java.util.Map<String,String>,String> callback) {
        final java.util.concurrent.atomic.AtomicReference<java.util.Map<String,String>> found=new java.util.concurrent.atomic.AtomicReference<>();
        roomOperation(room,(rooms,jid) -> found.set(rooms.affiliations(jid)),error -> callback.accept(found.get(),error));
    }
    public void roomAffiliation(long room,String address,String affiliation,Consumer<String> callback) { roomOperation(room,(rooms,jid) -> rooms.affiliation(jid,address,affiliation),callback); }
    public void inviteToRoom(long room,String address,Consumer<String> callback) { roomOperation(room,(rooms,jid) -> rooms.invite(jid,address,"Join us in this room"),callback); }
    public void changeRoomSubject(long room,String subject,Consumer<String> callback) { roomOperation(room,(rooms,jid) -> rooms.subject(jid,subject),callback); }
    public void roomMemberAction(long room,com.negzero.monogram.xmpp.XmppRooms.Occupant occupant,com.negzero.monogram.xmpp.XmppRooms.MemberAction action,Consumer<String> callback) { roomOperation(room,(rooms,jid) -> rooms.memberAction(jid,occupant,action),callback); }
    public void openRoom(String address,String nickname,String password,RequestDelegate callback) {
        long epoch=sessionEpoch,owner=accountEpoch;
        worker.execute(() -> {
            try {
                XmppSession expected=session();
                if(sessionEpoch!=epoch || accountEpoch!=owner) throw new IllegalStateException("Account changed");
                String jid=org.jxmpp.jid.impl.JidCreate.entityBareFrom(address).toString();
                if(org.jxmpp.jid.impl.JidCreate.from(address).hasResource()) throw new IllegalArgumentException("Bare room address required");
                org.jxmpp.jid.parts.Resourcepart.from(nickname);
                long saved=store.room(jid,nickname);
                String roomPassword=password;
                if(roomPassword==null) { String encrypted=preferences.getString("room-password-"+saved,""); roomPassword=encrypted.isEmpty() ? "" : vault.decrypt(encrypted); }
                boolean localPassword=preferences.getBoolean("room-password-local-"+saved,!preferences.getString("room-password-"+saved,"").isEmpty());
                if(!preferences.edit().putString("room-password-"+saved,roomPassword.isEmpty() ? "" : vault.encrypt(roomPassword))
                        .putBoolean("room-password-local-"+saved,password==null ? localPassword : !password.isEmpty()).commit()) throw new java.io.IOException("Cannot save room password");
                final String secret=roomPassword;
                roomWorker.execute(() -> {
                    try {
                        if(session!=expected || sessionEpoch!=epoch || accountEpoch!=owner) throw new IllegalStateException("Account changed");
                        com.negzero.monogram.xmpp.XmppRooms.State room=expected.rooms.join(jid,nickname,secret.isEmpty() ? null : secret);
                        worker.execute(() -> {
                            if(session!=expected || sessionEpoch!=epoch || accountEpoch!=owner) return;
                            long dialog=store.room(jid,room.nickname); store.roomState(room,self); dirtyRoomBookmark(dialog); publishRoom(dialog); dismissRoomInvitation(jid);
                            TLRPC.Chat chat=store.roomChat(dialog,self); TLRPC.TL_messages_dialogs cached=dialogs();
                            publishDialogs(cached,() -> sessionEpoch==epoch && accountEpoch==owner,() -> callback.run(chat,null));
                        });
                    } catch(Exception failed) { roomResult(callback,failed); }
                });
            } catch(Exception failed) { roomResult(callback,failed); }
        });
    }
    private void roomResult(RequestDelegate callback,Exception failure) {
        TLRPC.TL_error error=new TLRPC.TL_error(); error.code=400; error.text=roomError(failure);
        AndroidUtilities.runOnUIThread(() -> callback.run(null,error));
    }
    public void changeRoomNickname(long dialog,String nickname,RequestDelegate callback) {
        long owner=accountEpoch,epoch=sessionEpoch;
        roomWorker.execute(() -> { try {
            if(accountEpoch!=owner || sessionEpoch!=epoch) throw new IllegalStateException("Account changed");
            session().rooms.nickname(store.jid(dialog),nickname);
            worker.execute(() -> { if(accountEpoch==owner && sessionEpoch==epoch) dirtyRoomBookmark(dialog); });
            AndroidUtilities.runOnUIThread(() -> { if(accountEpoch==owner && sessionEpoch==epoch) callback.run(new TLRPC.TL_boolTrue(),null); });
        } catch(Exception failed) { roomResult(callback,failed); } });
    }
    public void leaveRoom(long dialog,RequestDelegate callback) {
        long owner=accountEpoch;
        worker.execute(() -> { try {
            if(accountEpoch!=owner) throw new IllegalStateException("Account changed");
            String jid=store.jid(dialog); XmppSession expected=session;
            store.leaveRoom(dialog); dirtyRoomBookmark(dialog); publishRoom(dialog);
            roomWorker.execute(() -> { try {
                if(accountEpoch!=owner) throw new IllegalStateException("Account changed");
                if(expected!=null && expected.connection().isAuthenticated() && expected.rooms.state(jid).joined) expected.rooms.leave(jid);
                AndroidUtilities.runOnUIThread(() -> { if(accountEpoch==owner) callback.run(new TLRPC.TL_boolTrue(),null); });
            } catch(Exception failed) { roomResult(callback,failed); } });
        } catch(Exception failed) { roomResult(callback,failed); } });
    }
    public void discoverRooms(java.util.function.BiConsumer<java.util.List<org.jivesoftware.smackx.muc.HostedRoom>,Exception> callback) {
        roomWorker.execute(() -> {
            java.util.List<org.jivesoftware.smackx.muc.HostedRoom> found=new ArrayList<>(); Exception error=null;
            try { XmppSession expected=session(); for(org.jxmpp.jid.DomainBareJid service:expected.rooms.services()) found.addAll(expected.rooms.discover(service.toString())); }
            catch(Exception failed) { error=failed; }
            final Exception result=error; AndroidUtilities.runOnUIThread(() -> callback.accept(found,result));
        });
    }
    private void publishRoom(long dialog) {
        TLRPC.Chat chat=store.roomChat(dialog,self); ArrayList<TLRPC.Chat> chats=new ArrayList<>(); chats.add(chat);
        ArrayList<TLRPC.User> users=store.roomUsers(dialog,self);
        if(store.profile(dialog)==null) refreshProfile(dialog);
        java.util.Set<Long> active=store.activeRoomUsers(dialog);
        for(TLRPC.User user:users) if(user.id!=self && active.contains(user.id) && store.profile(user.id)==null) refreshProfile(user.id);
        MessagesStorage.getInstance(account).putUsersAndChats(users,chats,true,true);
        final long owner=accountEpoch;
        AndroidUtilities.runOnUIThread(() -> { if(accountEpoch!=owner) return; MessagesController.getInstance(account).putUsers(users,false); MessagesController.getInstance(account).putChat(chat,false);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_ALL); });
    }
    private void incomingRoom(com.negzero.monogram.xmpp.XmppRoomMessage message) {
        long dialog=store.knownRoom(message.room.toString()); if(dialog==0) return;
        if(message.reactions!=null) { publishReactions(dialog,store.reactions.save(message,self)); return; }
        java.util.Set<Integer> replaced=new java.util.HashSet<>();
        int id=store.saveRoom(message,self,replaced);
        applyRetractions(dialog);
        java.util.Set<Integer> updated=new java.util.HashSet<>(); if(id!=0) updated.add(id);
        MonogramStore.CorrectionChanges corrections=store.reconcileCorrections(dialog,updated);
        updated.addAll(corrections.edited); refreshReplies(dialog,updated);
        publishReactions(dialog,java.util.Collections.emptySet());
        if(id!=0 && !store.hiddenMessage(id)) { long sender=store.getMessage(id,self).from_id.user_id; if(sender!=self && store.profile(sender)==null) refreshProfile(sender); }
        if(!UserConfig.getInstance(account).isClientActivated()) return;
        ArrayList<TLRPC.Update> updates=correctionUpdates(corrections,id);
        if(id!=0 && !store.hiddenMessage(id)) {
            maybeAutoDownload(store.getMessage(id,self));
            if(replaced.contains(id)) {
                TL_update.TL_updateEditMessage update=new TL_update.TL_updateEditMessage(); update.message=store.getMessage(id,self); updates.add(update);
            } else {
                TL_update.TL_updateNewMessage update=new TL_update.TL_updateNewMessage(); update.message=store.getMessage(id,self); updates.add(update);
            }
        }
        if(updates.isEmpty()) return;
        ArrayList<TLRPC.User> users=roomUpdateUsers(updates); ArrayList<TLRPC.Chat> chats=new ArrayList<>(); chats.add(store.roomChat(dialog,self));
        final long archiveOwner = accountEpoch;
        final String archiveJid = accountJid();
        final boolean archiveIncoming = id > 0 && !store.hiddenMessage(id) && !store.getMessage(id, self).out
                && message.correction == null && message.retraction == null;
        long epoch=sessionEpoch;
        AndroidUtilities.runOnUIThread(() -> {
            if (!sameAccount(archiveOwner, archiveJid)) return;
            if (archiveIncoming) MonogramDialogState.incoming(account, archiveJid, dialog, id, false);
            Utilities.stageQueue.postRunnable(() -> {
                if (!sameAccount(archiveOwner, archiveJid)) return;
                MessagesController.getInstance(account).processUpdateArray(updates,users,chats,false,now());
                AndroidUtilities.runOnUIThread(() -> {
                    if(sessionEpoch!=epoch) return;
                    ArrayList<MessageObject> changed=new ArrayList<>();
                    if(id!=0 && !store.hiddenMessage(id)) changed.add(new MessageObject(account,store.getMessage(id,self),true,true));
                    for(int edited:corrections.edited) changed.add(new MessageObject(account,store.getMessage(edited,self),true,true));
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects,dialog,changed);
                });
            });
        });
    }
    private ArrayList<TLRPC.User> roomUpdateUsers(ArrayList<TLRPC.Update> updates) {
        ArrayList<Long> ids=new ArrayList<>(),chats=new ArrayList<>();ids.add(self);
        ArrayList<TLRPC.Message> messages=new ArrayList<>();
        for(TLRPC.Update update:updates) {
            if(update instanceof TL_update.TL_updateNewMessage) messages.add(((TL_update.TL_updateNewMessage)update).message);
            else if(update instanceof TL_update.TL_updateEditMessage) messages.add(((TL_update.TL_updateEditMessage)update).message);
        }
        java.util.Set<TLRPC.Message> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(int index=0;index<messages.size();index++) {
            TLRPC.Message message=messages.get(index);if(message==null || !seen.add(message)) continue;
            MessagesStorage.addUsersAndChatsFromMessage(message,ids,chats,null);
            if(message.reactions!=null) for(TLRPC.MessagePeerReaction reaction:message.reactions.recent_reactions)
                if(reaction!=null && reaction.peer_id!=null && reaction.peer_id.user_id>0) ids.add(reaction.peer_id.user_id);
            if(message.replyMessage!=null) messages.add(message.replyMessage);
        }
        ArrayList<TLRPC.User> users=new ArrayList<>();
        for(long id:new java.util.LinkedHashSet<>(ids)) if(id>0) {
            try { users.add(store.user(id,self)); }
            catch(IllegalArgumentException missing) { /* Missing references remain unresolved in the native cache. */ }
        }
        return users;
    }
    private static final class ArchiveTask {
        final long peer;
        boolean backwards=true,resetAttempted,complete,unsupported;
        int loaded,desired=3,capacityRetries;
        String before;
        Consumer<String> searchCompletion;
        ArchiveTask(long peer) { this.peer = peer; }
    }
    private static final class ArchiveSync {
        final XmppSession session;
        final long epoch;
        final String jid;
        final java.util.ArrayDeque<ArchiveTask> tasks = new java.util.ArrayDeque<>();
        final java.util.Set<Long> peers = new java.util.HashSet<>();
        final java.util.Set<Long> rescan = new java.util.HashSet<>();
        final java.util.Set<Long> pending = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
        boolean supported,directChecked,running;
        final java.util.ArrayDeque<Long> fingerprintsReady=new java.util.ArrayDeque<>();
        final java.util.Map<Long,Integer> requestedPages=new java.util.HashMap<>();
        final java.util.Map<Long,ArchiveTask> windows=new java.util.HashMap<>();
        final java.util.Set<Long> preparing=new java.util.HashSet<>();
        final java.util.Set<Long> supportedRooms=new java.util.HashSet<>();
        ArchiveSync(XmppSession session, long epoch,String jid) { this.session = session; this.epoch = epoch; this.jid=jid; }
        boolean add(long peer) {
            peers.add(peer);
            ArchiveTask task=windows.computeIfAbsent(peer,ArchiveTask::new);
            task.desired=Math.max(task.desired,requestedPages.getOrDefault(peer,3));
            if(task.complete || task.loaded>=task.desired || !pending.add(peer)) return false;
            tasks.addLast(task); return true;
        }
    }
    private void finishSearchLoad(ArchiveTask task,String error) {
        Consumer<String> completed=task.searchCompletion;task.searchCompletion=null;
        if(completed!=null) AndroidUtilities.runOnUIThread(() -> completed.accept(error));
    }
    private void cancelSearchLoads(ArchiveSync sync) {
        if(sync!=null) for(ArchiveTask task:sync.windows.values()) finishSearchLoad(task,"The connection changed. Reconnect and try loading older history again.");
    }
    /** Explicit single-page expansion of the ordinary recent-history cursor, never an unbounded search scan. */
    public void loadOlderForSearch(long peer,Consumer<String> completed) {
        final long owner=accountEpoch;final String own=accountJid();
        worker.execute(() -> {
            Consumer<String> result=error -> completed.accept(sameAccount(owner,own) ? error : "The account changed. Open search again.");
            ArchiveSync sync=archiveSync;
            String error=null;
            ArchiveTask task=sync==null ? null : sync.windows.get(peer);
            if(!sameAccount(owner,own) || sync==null || !currentArchive(sync)) error="Connect to XMPP before loading older history.";
            else if(peer==0) error="Older server history is unavailable for this conversation.";
            else if(task!=null && task.unsupported) error=peer<0 ? "This room does not provide server history." : "Your server does not provide message history.";
            else if(task==null || sync.pending.contains(peer)) error="This conversation’s history is still loading. Try again when it finishes.";
            else if(task.loaded==0) error="Recent history has not loaded. Reconnect and try again.";
            else if(task.complete) error="All available server history for this conversation is already loaded.";
            else if(task.searchCompletion!=null) error="An older history batch is already loading.";
            if(error!=null) { final String failure=error;AndroidUtilities.runOnUIThread(() -> result.accept(failure));return; }
            task.searchCompletion=result;task.desired=task.loaded+1;
            sync.requestedPages.put(peer,task.desired);
            sync.pending.add(peer);sync.tasks.addLast(task);nextArchivePage(sync);
        });
    }
    private boolean currentArchive(ArchiveSync sync) {
        return archiveSync == sync && accountEpoch == sync.epoch && session == sync.session && sync.session.connection().isAuthenticated();
    }
    private void startArchiveSync(XmppSession expected) {
        cancelSearchLoads(archiveSync);
        ArchiveSync sync = new ArchiveSync(expected, accountEpoch,store.jid(self)); archiveSync = sync;
        for (long peer : store.peers()) prepareArchivePeer(sync,peer);
        for(long room:store.rooms()) try { if(store.room(room).autojoin && expected.rooms.state(store.jid(room)).joined) { joinedRooms.add(room); prepareArchivePeer(sync,room); } } catch(Exception ignored) {}
        // Keep discovery for conversations that exist only in the server archive.
        prepareArchivePeer(sync,0);
        nextArchivePage(sync);
    }
    private void prepareArchivePeer(ArchiveSync sync,long peer) {
        if(!currentArchive(sync) || sync.windows.containsKey(peer) || !sync.preparing.add(peer)) return;
        sync.peers.add(peer);
        java.util.Set<Long> profiles=new java.util.LinkedHashSet<>(); if(peer!=0) profiles.add(peer); profiles.add(self);
        java.util.concurrent.atomic.AtomicInteger remaining=new java.util.concurrent.atomic.AtomicInteger(profiles.size());
        Runnable profileReady=() -> {
            if(remaining.decrementAndGet()!=0 || !currentArchive(sync)) return;
            sync.fingerprintsReady.addLast(peer); startFingerprintStage(sync);
        };
        for(long profile:profiles) {
            if(store.profile(profile)!=null) { refreshProfile(profile); profileReady.run(); }
            else refreshProfile(profile,profileReady);
        }
    }
    private void startFingerprintStage(ArchiveSync sync) {
        if(!currentArchive(sync)) return;
        Long next;
        while((next=sync.fingerprintsReady.pollFirst())!=null) {
            final long peer=next;
            Runnable ready=() -> {
                if(currentArchive(sync) && sync.preparing.remove(peer)) { sync.add(peer); nextArchivePage(sync); }
            };
            sync.session.fingerprints.afterTimeout(() -> worker.execute(ready));
            fingerprintWorker.execute(() -> {
                try {
                    java.util.Set<String> addresses=new java.util.LinkedHashSet<>();
                    if(peer>0) addresses.add(store.jid(peer));
                    else if(peer<0 && encrypted(peer)) try { for(org.jxmpp.jid.BareJid member:sync.session.rooms.members(store.jid(peer))) addresses.add(member.toString()); } catch(Exception unsupported) {}
                    addresses.add(store.jid(self));
                    if(peer<0) addresses.removeAll(excludedRoomRecipients(peer));
                    addresses.removeIf(address -> address.indexOf('/')>=0);
                    if(!currentArchive(sync)) return;
                    // The shared discovery pool has a deadline; an unavailable member cannot hold all history.
                    sync.session.fingerprints.load(addresses,modernEncryption(peer) && sync.session.modernAvailable(),false,snapshot -> {
                        if(snapshot.complete) worker.execute(ready);
                    });
                } catch(Exception failed) { worker.execute(ready); }
            });
        }
        nextArchivePage(sync);
    }
    private void queueRoomArchive(long room,XmppSession expected) {
        ArchiveSync sync=archiveSync;
        if(sync!=null && currentArchive(sync)) { prepareArchivePeer(sync,room); return; }
        cancelSearchLoads(archiveSync);
        sync=new ArchiveSync(expected,accountEpoch,store.jid(self)); archiveSync=sync; prepareArchivePeer(sync,room);
    }
    private void syncRoomArchive(ArchiveSync sync,ArchiveTask task) throws Exception {
        String jid=store.jid(task.peer);
        if(!sync.supportedRooms.contains(task.peer)) {
            if(!sync.session.rooms.hasArchive(jid)) { worker.execute(() -> { if(currentArchive(sync)) { task.unsupported=true;finishSearchLoad(task,"This room does not provide server history.");sync.pending.remove(task.peer); sync.running=false; nextArchivePage(sync); } }); return; }
            sync.supportedRooms.add(task.peer);
        }
        String cursor=task.before;
        com.negzero.monogram.xmpp.XmppRooms.ArchivePage page=sync.session.rooms.archive(jid,
                cursor==null ? "" : cursor,null,50);
        if(cursor!=null && cursor.equals(task.backwards ? page.firstId : page.lastId) && !page.complete) throw new java.io.IOException("Room archive cursor did not advance");
        boolean initial=task.loaded==0;
        worker.execute(() -> {
            try { roomArchivePage(sync,task,page,initial); }
            catch(Exception failed) { if(currentArchive(sync)) archiveFailed(sync,task,failed); else if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram late room archive: "+failed.getClass().getSimpleName()); }
        });
    }
    private void roomArchivePage(ArchiveSync sync,ArchiveTask task,com.negzero.monogram.xmpp.XmppRooms.ArchivePage page,boolean initial) {
        if(!sameAccount(sync.epoch,sync.jid)) return;
        java.util.Set<Integer> updated=new java.util.HashSet<>();
        boolean current=currentArchive(sync),changed=false;
        if(current) changed=store.saveRoomArchive(task.peer,page,task.backwards || initial,self,updated);
        // Retain already decrypted records after reconnect without advancing the obsolete sync's cursors.
        else for(com.negzero.monogram.xmpp.XmppRoomMessage message:page.messages) {
            if(!store.jid(task.peer).equals(message.room.toString())) throw new IllegalArgumentException("Room archive belongs to another room");
            int id=store.saveRoom(message,self); if(id!=0) { updated.add(id);changed=true; }
        }
        publishArchiveChanges(sync,java.util.Collections.singleton(task.peer),updated,changed);
        page.stored();
        if(current) finishArchivePage(sync,task,page.firstId,page.complete);
    }
    private void nextArchivePage(ArchiveSync sync) {
        if (!currentArchive(sync) || sync.running) return;
        for (long peer:sync.rescan) {
            ArchiveTask old=sync.windows.get(peer);if(old!=null) finishSearchLoad(old,"History is being refreshed after an encryption change. Try again when it finishes.");
            store.resetArchiveCursor(peer); sync.tasks.removeIf(task -> task.peer==peer);
            sync.windows.remove(peer); sync.pending.remove(peer); sync.add(peer);
        }
        sync.rescan.clear();
        ArchiveTask task;
        while ((task = sync.tasks.pollFirst()) != null) {
            if(task.complete || task.loaded>=task.desired) { sync.pending.remove(task.peer); continue; }
            final ArchiveTask next = task; sync.running=true;
            archiveWorker.execute(() -> syncArchive(sync, next));
            return;
        }
        syncOwnReads(false,true);
    }
    private void finishArchivePage(ArchiveSync sync,ArchiveTask task,String first,boolean complete) {
        task.loaded++; task.before=first; task.complete=complete || first==null; sync.running=false;
        finishSearchLoad(task,null);
        if(!task.complete && task.loaded<task.desired) sync.tasks.addLast(task); else sync.pending.remove(task.peer);
        nextArchivePage(sync);
    }
    private void syncArchive(ArchiveSync sync, ArchiveTask task) {
        if (!currentArchive(sync)) return;
        try {
            if(task.peer<0) { syncRoomArchive(sync,task); return; }
            if(!sync.directChecked) { sync.supported=sync.session.hasArchive(); sync.directChecked=true; }
            if(!sync.supported) { worker.execute(() -> { if(currentArchive(sync)) { task.unsupported=true;finishSearchLoad(task,"Your server does not provide message history.");sync.pending.remove(task.peer); sync.running=false; nextArchivePage(sync); } }); return; }
            String cursor = task.before;
            String jid = task.peer == 0 ? null : store.jid(task.peer);
            XmppSession.ArchivePage page = sync.session.archiveBefore(jid,cursor,50);
            if (cursor != null && cursor.equals(task.backwards ? page.firstId : page.lastId) && !page.complete) {
                throw new java.io.IOException("Archive cursor did not advance");
            }
            boolean initial = task.loaded==0;
            worker.execute(() -> {
                try { archivePage(sync, task, page, initial); }
                catch (Exception error) { if(currentArchive(sync)) archiveFailed(sync, task, error); else if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram late archive: "+error.getClass().getSimpleName()); }
            });
        } catch (Exception error) { worker.execute(() -> { if (currentArchive(sync)) archiveFailed(sync, task, error); }); }
    }
    private void archivePage(ArchiveSync sync, ArchiveTask task, XmppSession.ArchivePage page, boolean initial) {
        if(!sameAccount(sync.epoch,sync.jid)) return;
        java.util.Set<Integer> updated = new java.util.HashSet<>();
        boolean current=currentArchive(sync),changed=false;
        if(current) changed=store.saveArchive(task.peer,page,task.backwards || initial,updated);
        // Replaying the ciphertext may no longer work after its ratchet key has been consumed.
        else for(XmppMessage message:page.messages) {
            if(task.peer!=0 && !store.jid(task.peer).equals(message.peer.toString())) throw new IllegalArgumentException("Archive belongs to another chat");
            int id=store.save(message); if(id!=0) { updated.add(id);changed=true; }
        }
        page.stored();
        java.util.Set<Long> peers=new java.util.HashSet<>();
        for(XmppMessage message:page.messages) peers.add(store.peer(message.peer.toString()));
        publishArchiveChanges(sync,peers,updated,changed);
        if(current) finishArchivePage(sync,task,page.firstId,page.complete);
    }
    private void publishArchiveChanges(ArchiveSync sync,java.util.Set<Long> peers,java.util.Set<Integer> updated,boolean changed) {
        refreshNotifications(updated);
        for(long peer:peers) {
            applyRetractions(peer);
            MonogramStore.CorrectionChanges corrections=store.reconcileCorrections(peer,updated);
            updated.addAll(corrections.edited);
            publishCorrections(peer,correctionUpdates(corrections,0));
            refreshReplies(peer,updated);
            publishReactions(peer,null);
            ArchiveSync active=archiveSync;
            if(active!=null && currentArchive(active) && !active.peers.contains(peer)) prepareArchivePeer(active,peer);
        }
        if(changed && UserConfig.getInstance(account).isClientActivated()) {
            for(long peer:peers) if(peer<0) publishRoom(peer);
            publishArchiveMessages(sync,updated);
            publishDialogs(dialogs(),() -> sameAccount(sync.epoch,sync.jid),null);
        }
    }
    private void publishArchiveMessages(ArchiveSync sync, java.util.Set<Integer> updated) {
        if(updated.isEmpty() || !UserConfig.getInstance(account).isClientActivated()) return;
        ArrayList<TLRPC.Message> messages=new ArrayList<>();
        java.util.Map<Long,ArrayList<MessageObject>> byPeer=new java.util.HashMap<>();
        for(int id:updated) if(!store.hiddenMessage(id)) {
            TLRPC.Message message=store.getMessage(id,self); messages.add(message);
            if(message.dialog_id==mediaHistoryPeer) {
                maybeLinkPreview(message);
                if(MonogramAttachments.has(message)) {
                    File path=new File(message.attachPath);
                    if(path.isFile()) refreshCachedMedia(message,path); else maybeAutoDownload(message);
                }
            }
            byPeer.computeIfAbsent(message.dialog_id,ignored -> new ArrayList<>()).add(new MessageObject(account,message,true,true));
        }
        MessagesStorage.getInstance(account).putMessages(messages,false,true,false,0,0,0);
        AndroidUtilities.runOnUIThread(() -> {
            if(!sameAccount(sync.epoch,sync.jid)) return;
            for(java.util.Map.Entry<Long,ArrayList<MessageObject>> entry:byPeer.entrySet())
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects,entry.getKey(),entry.getValue(),true);
        });
    }
    private void archiveFailed(ArchiveSync sync, ArchiveTask task, Exception error) {
        sync.running=false;
        if((error instanceof com.negzero.monogram.xmpp.Omemo2IncomingJournal.FullException
                || error instanceof com.negzero.monogram.xmpp.LegacyIncomingJournal.FullException) && task.capacityRetries++==0) {
            sync.tasks.addLast(task);
        } else if (!task.resetAttempted && error instanceof org.jivesoftware.smack.XMPPException.XMPPErrorException
                && ((org.jivesoftware.smack.XMPPException.XMPPErrorException) error).getStanzaError().getCondition()
                == org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found) {
            if(task.searchCompletion!=null) {
                finishSearchLoad(task,"The server history cursor expired. Recent history will refresh; try again when it finishes.");task.desired=3;
            }
            task.resetAttempted=true; task.before=null; task.loaded=0;
            store.resetArchiveCursor(task.peer); sync.tasks.addLast(task);
        } else {
            sync.pending.remove(task.peer);
            if(task.searchCompletion!=null) { finishSearchLoad(task,"Could not load older history: "+com.negzero.monogram.xmpp.XmppRequestException.describe(error));task.desired=task.loaded; }
            // A failed chat must not prevent other chats from syncing or erase cached history.
            if (BuildVars.LOGS_ENABLED) FileLog.e("Monogram archive sync: " + error.getClass().getSimpleName());
        }
        nextArchivePage(sync);
    }
    private static long searchPeer(TLRPC.InputPeer peer) {
        return peer==null ? 0 : peer.chat_id!=0 ? -peer.chat_id : peer.channel_id!=0 ? -peer.channel_id : peer.user_id;
    }
    private void publishDialogs(TLRPC.TL_messages_dialogs dialogs,java.util.function.BooleanSupplier current,Runnable completed) {
        MessagesStorage.getInstance(account).restoreMonogramReadState(dialogs.messages,dialogs.dialogs,() -> AndroidUtilities.runOnUIThread(() -> {
            if(!current.getAsBoolean()) return;
            MessagesController.getInstance(account).processLoadedDialogs(dialogs,null,null,0,0,dialogs.dialogs.size(),3,false,false,false);
            if(completed!=null) completed.run();
        }));
    }
    private TLRPC.TL_messages_dialogs dialogs() {
        TLRPC.TL_messages_dialogs result = new TLRPC.TL_messages_dialogs();
        for (long peer : store.peers()) {
            ArrayList<TLRPC.Message> history = store.history(peer, self, 0, 1);
            if (history.isEmpty()) continue;
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog(); dialog.id = peer; dialog.peer = new TLRPC.TL_peerUser(); dialog.peer.user_id = peer;
            dialog.top_message = history.get(0).id; dialog.last_message_date = history.get(0).date;
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            MonogramDialogState.apply(account,accountJid(),dialog);result.dialogs.add(dialog); result.messages.addAll(history); result.users.add(store.user(peer, self));
        }
        for (long peer:store.rooms()) {
            ArrayList<TLRPC.Message> history=store.history(peer,self,0,1);
            TLRPC.TL_dialog dialog=new TLRPC.TL_dialog(); dialog.id=peer; dialog.peer=new TLRPC.TL_peerChat(); dialog.peer.chat_id=-peer;
            if(!history.isEmpty()) { dialog.top_message=history.get(0).id; dialog.last_message_date=history.get(0).date; }
            dialog.notify_settings=new TLRPC.TL_peerNotifySettings(); MonogramDialogState.apply(account,accountJid(),dialog);result.dialogs.add(dialog); result.messages.addAll(history);
            result.chats.add(store.roomChat(peer,self)); result.users.addAll(store.roomUsers(peer,self));
        }
        result.dialogs.sort((a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        result.users.add(ownUser());
        return result;
    }
    private final java.util.concurrent.ConcurrentHashMap<Long,Integer> visibleHistory=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Long> calendarHistory=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<Long,MonogramCalendarWindow> calendarWindows=new java.util.concurrent.ConcurrentHashMap<>();
    public boolean calendarWindowActive(long peer) { return calendarWindows.containsKey(peer); }
    public void historyVisible(long peer,int message) {
        // A date jump must not extend the unrelated newest-to-oldest sync window across a sparse cache.
        if(peer==0 || message<=0 || calendarHistory.contains(peer) || java.util.Objects.equals(visibleHistory.put(peer,message),message)) return;
        final long owner=accountEpoch;
        cacheWorker.execute(() -> {
            int desired=store.newerMessages(peer,message)/50+3;
            worker.execute(() -> {
                ArchiveSync sync=archiveSync;
                if(owner!=accountEpoch || sync==null || !currentArchive(sync)) return;
                sync.requestedPages.merge(peer,desired,Math::max);
                ArchiveTask task=sync.windows.get(peer); if(task==null || task.complete || desired<=task.desired) return;
                task.desired=desired; sync.add(peer); nextArchivePage(sync);
            });
        });
    }
    public TLRPC.Document customEmoji(long id) { return store.customEmoji(id); }
    private final MonogramOutbox outbox=new MonogramOutbox();
    public void loadHistory(long peer, int offset, int count, int loadType, java.util.function.BiConsumer<TLRPC.messages_Messages, Boolean> completed) {
        loadHistory(peer,offset,count,loadType,0,completed);
    }
    public void loadHistory(long peer, int offset, int count, int loadType, int date, java.util.function.BiConsumer<TLRPC.messages_Messages, Boolean> completed) {
        long epoch=accountEpoch;
        if(loadType==4 && date>0) { calendarHistory.add(peer);calendarWindows.remove(peer); }
        else if(offset<=0 && loadType!=4) { calendarHistory.remove(peer);calendarWindows.remove(peer);visibleHistory.remove(peer); }
        XmppSession expected=session;final long connectionEpoch=sessionEpoch;
        final String own=loadType==4 && date>0 || loadType==3 && offset>0 ? accountJid() : "";
        if(loadType==4 && date>0) {
            MonogramCalendarWindow window=new MonogramCalendarWindow();calendarWindows.put(peer,window);
            loadNavigationHistory(peer,offset,count,4,date,null,epoch,own,expected,connectionEpoch,window,completed);return;
        }
        MonogramCalendarWindow window=calendarWindows.get(peer);
        if(loadType==3 && offset>0) {
            if(window!=null && !window.cachedOnly() && window.ids().contains(offset)) {
                loadHistory(peer,offset,count,loadType,date,epoch,new ArrayList<>(),window,completed);return;
            }
            calendarHistory.add(peer);visibleHistory.remove(peer);
            final MonogramCalendarWindow target=new MonogramCalendarWindow();calendarWindows.put(peer,target);
            cacheWorker.execute(() -> {
                try {
                    if(!currentCalendar(peer,target,epoch,own,expected,connectionEpoch)) throw new IllegalStateException("History target changed");
                    TLRPC.Message anchor=store.historyAround(peer,self,offset,1).get(0);
                    String cursor=store.navigationArchiveId(peer,offset);
                    loadNavigationHistory(peer,offset,count,3,anchor.date,cursor,epoch,own,expected,connectionEpoch,target,completed);
                } catch(Exception unavailable) {
                    if(calendarWindows.remove(peer,target)) calendarHistory.remove(peer);
                    Utilities.stageQueue.postRunnable(() -> completed.accept(new TLRPC.TL_messages_messages(),true));
                }
            });return;
        }
        if(window!=null && offset>0 && (loadType==0 || loadType==1)) {
            loadCalendarContinuation(peer,offset,count,loadType,epoch,expected,sessionEpoch,window,completed);return;
        }
        if(loadType!=4 && (offset<=0 || loadType==2 || loadType==3)) {
            MessagesStorage.getInstance(account).getMonogramOutbox(peer,pending -> loadHistory(peer,offset,count,loadType,date,epoch,pending,completed));
        } else loadHistory(peer,offset,count,loadType,date,epoch,new ArrayList<>(),completed);
    }
    private boolean currentCalendar(long peer,MonogramCalendarWindow window,long owner,String own,XmppSession expected,long epoch) {
        return calendarWindows.get(peer)==window && session==expected && sessionEpoch==epoch && sameAccount(owner,own);
    }
    private void loadNavigationHistory(long peer,int offset,int count,int type,int date,String cursor,long owner,String own,XmppSession expected,long epoch,MonogramCalendarWindow window,
            java.util.function.BiConsumer<TLRPC.messages_Messages,Boolean> completed) {
        final String address=store.jid(peer);
        archiveWorker.execute(() -> {
            try {
                if(!currentCalendar(peer,window,owner,own,expected,epoch) || expected==null || !expected.connection().isAuthenticated()) throw new IllegalStateException("History connection unavailable");
                java.util.Date since=new java.util.Date((long)date*1000);
                MonogramCalendarWindow.Page newer,older;
                if(cursor!=null) {
                    if(peer>0) {
                        XmppSession.ArchivePage before=expected.archiveBefore(address,cursor,50);
                        older=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,before,true) : null).get();
                        if(!currentCalendar(peer,window,owner,own,expected,epoch)) throw new IllegalStateException("History changed");
                        XmppSession.ArchivePage after=expected.archiveAfter(address,cursor,50);
                        newer=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,after,false) : null).get();
                    } else {
                        com.negzero.monogram.xmpp.XmppRooms.ArchivePage before=expected.rooms.archive(address,cursor,null,50);
                        older=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,before,true) : null).get();
                        if(!currentCalendar(peer,window,owner,own,expected,epoch)) throw new IllegalStateException("History changed");
                        com.negzero.monogram.xmpp.XmppRooms.ArchivePage after=expected.rooms.archive(address,null,cursor,50);
                        newer=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,after,false) : null).get();
                    }
                } else if(peer>0) {
                    XmppSession.ArchivePage after=expected.archiveSince(address,since,50);
                    newer=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,after,false) : null).get();
                    if(!currentCalendar(peer,window,owner,own,expected,epoch)) throw new IllegalStateException("Calendar changed");
                    XmppSession.ArchivePage before=expected.archiveBefore(address,after.firstId,50);
                    older=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,before,true) : null).get();
                } else {
                    com.negzero.monogram.xmpp.XmppRooms.ArchivePage after=expected.rooms.archiveSince(address,since,50);
                    newer=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,after,false) : null).get();
                    if(!currentCalendar(peer,window,owner,own,expected,epoch)) throw new IllegalStateException("Calendar changed");
                    com.negzero.monogram.xmpp.XmppRooms.ArchivePage before=expected.rooms.archive(address,after.firstId==null ? "" : after.firstId,null,50);
                    older=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,before,true) : null).get();
                }
                if(newer==null || older==null || !currentCalendar(peer,window,owner,own,expected,epoch)) throw new IllegalStateException("Calendar changed");
                if(cursor!=null) window.startAround(older,new MonogramCalendarWindow.Page(cursor,cursor,java.util.Collections.singleton(offset),false),newer);
                else {
                    window.start(newer,older);
                    if(type==3 && !window.ids().contains(offset)) throw new IllegalStateException("Selected message was not returned by this archive window");
                }
            } catch(Exception unavailable) {
                if(!currentCalendar(peer,window,owner,own,expected,epoch)) {
                    Utilities.stageQueue.postRunnable(() -> completed.accept(new TLRPC.TL_messages_messages(),false));return;
                }
                try {
                    ArrayList<TLRPC.Message> cached=type==3 ? store.historyAround(peer,self,offset,count) : store.historyAroundDate(peer,self,date,count);
                    java.util.Set<Integer> ids=new java.util.LinkedHashSet<>();for(TLRPC.Message message:cached) ids.add(message.id);
                    window.cached(ids);
                } catch(Exception invalidTarget) { window.cached(java.util.Collections.emptySet()); }
            }
            loadHistory(peer,offset,count,type,date,owner,new ArrayList<>(),window,completed);
        });
    }
    private void loadCalendarContinuation(long peer,int offset,int count,int type,long owner,XmppSession expected,long epoch,MonogramCalendarWindow window,
            java.util.function.BiConsumer<TLRPC.messages_Messages,Boolean> completed) {
        final String own=accountJid(),address=store.jid(peer);final boolean forward=type==1;
        if(window.cachedOnly()) {
            cacheWorker.execute(() -> {
                if(!currentCalendar(peer,window,owner,own,expected,epoch)) { Utilities.stageQueue.postRunnable(() -> completed.accept(new TLRPC.TL_messages_messages(),false));return; }
                try {
                    int needed=Math.min(50,Math.max(1,count));
                    if(!window.end(forward) && store.history(peer,self,offset,needed,forward,window.ids()).size()<needed) {
                        ArrayList<TLRPC.Message> cached=store.history(peer,self,offset,needed,forward);
                        java.util.Set<Integer> ids=new java.util.LinkedHashSet<>();for(TLRPC.Message message:cached) ids.add(message.id);
                        window.addCached(ids,forward,cached.size()<needed);
                    }
                } catch(Exception unavailable) { try { window.addCached(java.util.Collections.emptySet(),forward,true); } catch(Exception ignored) {} }
                loadHistory(peer,offset,count,type,0,owner,new ArrayList<>(),window,completed);
            });return;
        }
        archiveWorker.execute(() -> {
            try {
                int needed=Math.min(100,Math.max(1,count));
                for(int pages=0;pages<3 && !window.end(forward)
                        && store.history(peer,self,offset,needed,forward,window.ids()).size()<needed;pages++) {
                    if(!currentCalendar(peer,window,owner,own,expected,epoch) || expected==null || !expected.connection().isAuthenticated()) break;
                    String cursor=window.cursor(forward);if(cursor==null) break;
                    MonogramCalendarWindow.Page page;
                    if(peer>0) {
                        XmppSession.ArchivePage result=forward ? expected.archiveAfter(address,cursor,50) : expected.archiveBefore(address,cursor,50);
                        page=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,result,!forward) : null).get();
                    } else {
                        com.negzero.monogram.xmpp.XmppRooms.ArchivePage result=expected.rooms.archive(address,forward ? null : cursor,forward ? cursor : null,50);
                        page=worker.submit(() -> currentCalendar(peer,window,owner,own,expected,epoch) ? saveCalendarPage(peer,owner,own,result,!forward) : null).get();
                    }
                    if(page==null || !currentCalendar(peer,window,owner,own,expected,epoch)) break;
                    window.add(page,forward);
                }
            } catch(Exception unavailable) { /* Keep the fetched window and retry its cursor on the next scroll. */ }
            loadHistory(peer,offset,count,type,0,owner,new ArrayList<>(),window,completed);
        });
    }
    private MonogramCalendarWindow.Page saveCalendarPage(long peer,long owner,String own,XmppSession.ArchivePage page,boolean backwards) {
        if(!sameAccount(owner,own)) return null;
        java.util.Set<Integer> updated=new java.util.HashSet<>();
        store.saveCalendar(peer,page,backwards,updated);
        page.stored();calendarPageSaved(peer,updated);
        java.util.List<String> ids=new ArrayList<>();for(com.negzero.monogram.xmpp.XmppMessage message:page.messages) ids.add(message.ids.get("archive"));
        return new MonogramCalendarWindow.Page(page.firstId,page.lastId,store.calendarIds(peer,ids),page.complete);
    }
    private MonogramCalendarWindow.Page saveCalendarPage(long peer,long owner,String own,com.negzero.monogram.xmpp.XmppRooms.ArchivePage page,boolean backwards) {
        if(!sameAccount(owner,own)) return null;
        java.util.Set<Integer> updated=new java.util.HashSet<>();
        store.saveRoomCalendar(peer,page,backwards,self,updated);
        page.stored();calendarPageSaved(peer,updated);
        java.util.List<String> ids=new ArrayList<>();for(com.negzero.monogram.xmpp.XmppRoomMessage message:page.messages) ids.add(message.ids.get("mam"));
        return new MonogramCalendarWindow.Page(page.firstId,page.lastId,store.calendarIds(peer,ids),page.complete);
    }
    private void calendarPageSaved(long peer,java.util.Set<Integer> updated) {
        applyRetractions(peer);MonogramStore.CorrectionChanges corrections=store.reconcileCorrections(peer,updated);
        updated.addAll(corrections.edited);publishCorrections(peer,correctionUpdates(corrections,0));
        refreshNotifications(updated);refreshReplies(peer,updated);publishReactions(peer,null);
    }
    private void loadHistory(long peer,int offset,int count,int loadType,int date,long epoch,ArrayList<TLRPC.Message> pending,java.util.function.BiConsumer<TLRPC.messages_Messages,Boolean> completed) {
        loadHistory(peer,offset,count,loadType,date,epoch,pending,null,completed);
    }
    private void loadHistory(long peer,int offset,int count,int loadType,int date,long epoch,ArrayList<TLRPC.Message> pending,MonogramCalendarWindow window,java.util.function.BiConsumer<TLRPC.messages_Messages,Boolean> completed) {
        cacheWorker.execute(() -> {
            if(epoch!=accountEpoch || window!=null && calendarWindows.get(peer)!=window) { Utilities.stageQueue.postRunnable(() -> completed.accept(new TLRPC.TL_messages_messages(),false));return; }
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            mediaHistoryPeer = peer;
            ArchiveSync sync = archiveSync;
            boolean end=window==null ? store.archiveComplete(peer) : window.end(loadType==1);
            try {
                java.util.Set<Integer> allowed=window==null ? null : window.ids();
                result.messages.addAll(loadType==4 ? store.historyAroundDate(peer,self,date,count,allowed)
                        : loadType==3 && offset>0 ? store.historyAround(peer,self,offset,count,allowed)
                        : store.history(peer,self,Math.max(0,offset),count,loadType==1,allowed));
                if (peer<0) { result.chats.add(store.roomChat(peer,self)); result.users.addAll(store.roomUsers(peer,self)); }
                else result.users.add(store.user(peer, self)); if (self > 0) result.users.add(ownUser());
            } catch (RuntimeException failed) {
                if (BuildVars.LOGS_ENABLED) FileLog.e("Monogram cached history: " + failed.getClass().getSimpleName());
                result.messages.clear(); result.users.clear(); end = true;
            }
            final boolean archiveEnd = end;
            MessagesStorage.getInstance(account).restoreMonogramReadState(result.messages,null,() -> Utilities.stageQueue.postRunnable(() -> {
                if(epoch!=accountEpoch || window!=null && calendarWindows.get(peer)!=window) { completed.accept(new TLRPC.TL_messages_messages(),false); return; }
                outbox.merge(peer,result.messages,pending);
                completed.accept(result,archiveEnd);
            }));
        });
    }
    public void login(String jid, String password, String host, int port, Consumer<Exception> result) {
        long owner=accountEpoch;
        worker.execute(() -> {
            Exception error = null;boolean signingIn=false;
            try {
                Account details = new Account(jid, host, port, "Monogram");
                if(owner!=accountEpoch || UserConfig.getInstance(account).isClientActivated()
                        || preferences.contains("jid") && !details.jid.toString().equals(accountJid()))
                    throw new com.negzero.monogram.xmpp.XmppInvitations.InvitationException("This account slot is already configured or changed. Open sign-in again.");
                signingIn=true;connect(details, password);
                if(owner!=accountEpoch) throw new com.negzero.monogram.xmpp.XmppInvitations.InvitationException("Account changed. Please sign in again.");
                if (!preferences.edit().putString("jid", details.jid.toString()).putString("host", host)
                        .putInt("port", port).putString("credential", vault.encrypt(password)).commit()) throw new java.io.IOException("Cannot save account");
                syncOwnReads(true);
            } catch (Exception e) {
                error = e;
                if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram sign-in: "+com.negzero.monogram.xmpp.XmppRequestException.describeLogin(e));
                if(signingIn) {
                    if (session != null) { MonogramCallService.unbind(account,session);session.close(); session = null; }
                    state(ConnectionsManager.ConnectionStateWaitingForNetwork);
                }
            }
            AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
            final Exception completed = error;
            AndroidUtilities.runOnUIThread(() -> result.accept(owner==accountEpoch ? completed : new com.negzero.monogram.xmpp.XmppInvitations.InvitationException("Account changed. Please sign in again.")));
        });
    }
    public String sealRegistrationInvite(String uri) throws Exception {
        com.negzero.monogram.xmpp.RegistrationInvitation.parse(uri);return vault.encrypt(uri);
    }
    public com.negzero.monogram.xmpp.RegistrationInvitation openRegistrationInvite(String sealed) throws Exception {
        return com.negzero.monogram.xmpp.RegistrationInvitation.parse(vault.decrypt(sealed));
    }
    private void requireRegistrationSlot(long owner) throws Exception {
        if(owner!=accountEpoch || UserConfig.getInstance(account).isClientActivated() || preferences.contains("jid"))
            throw new com.negzero.monogram.xmpp.XmppInvitations.InvitationException("This account slot changed. Open the invitation again.");
    }
    public void register(com.negzero.monogram.xmpp.RegistrationInvitation invitation,String jid,String password,String host,int port,
                         java.util.function.BiConsumer<Boolean,Exception> callback) {
        long owner=accountEpoch;
        worker.execute(() -> {
            boolean created=false,signingIn=false;Exception error=null;
            try {
                requireRegistrationSlot(owner);Account details=new Account(jid,host,port,"Monogram");invitation.validate(details);
                String sealed=vault.encrypt(password);
                AndroidSmackInitializer.initialize(ApplicationLoader.applicationContext);
                com.negzero.monogram.xmpp.XmppRegistration.create(invitation,details,password);created=true;
                requireRegistrationSlot(owner);signingIn=true;connect(details,password);requireRegistrationSlot(owner);
                if(!preferences.edit().putString("jid",details.jid.toString()).putString("host",host).putInt("port",port)
                        .putString("credential",sealed).commit()) throw new java.io.IOException("Cannot save account");
                syncOwnReads(true);
            } catch(Exception failed) {
                error=failed;
                if(signingIn) {
                    if(session!=null) { MonogramCallService.unbind(account,session);session.close();session=null; }
                    state(ConnectionsManager.ConnectionStateWaitingForNetwork);
                }
            }
            final boolean registered=created;final Exception failure=error;
            AndroidUtilities.runOnUIThread(() -> {
                ApplicationLoader.startPushService();
                callback.accept(registered,owner==accountEpoch ? failure : new com.negzero.monogram.xmpp.XmppInvitations.InvitationException("Account changed. Sign in to the new account separately."));
            });
        });
    }
    public void logout() {
        final String departing=accountJid();
        final long departingEpoch=accountEpoch;
        AndroidUtilities.cancelRunOnUIThread(scheduledWake);
        MonogramScheduledMessages.releaseGeneration(account,departing,departingEpoch);
        synchronized(readSyncLock) {
            readSyncPrivacyEpoch++;
            XmppSession current=session;if(current!=null) current.readSync.setEnabled(false);
            privateReads.clearPending(departing);readSyncSnapshot=null;readSyncSession=null;
        }
        MonogramNotificationActions.invalidate(account);
        MonogramChatAppearance.invalidate(account);
        MonogramMediaDrafts.logout(account,departing);
        debugCapture(false);
        linkPreviews.cancel();
        MonogramDialogState.clear(account,departing);
        MonogramPacks.logout(account);
        if (!preferences.edit().remove("jid").remove("credential").commit()) throw new IllegalStateException("Cannot remove saved credential");
        synchronized(displayedSettingsLock) {
            displayedPrivacyEpoch++;displayedReady=false;
            XmppSession current=session;if(current!=null) current.setDisplayedMarkersEnabled(false);
        }
        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
        synchronized(profileLoads) { accountEpoch++;profileLoads.clear(); } pendingSends.clear(); queuedEncryption.clear(); queuedLocalIds.clear(); outbox.clear();
        for(TranslationRequest translation:translationRequests.values()) translation.cancel();
        translationRequests.clear();
        try { MonogramTranslation.clearConfiguration(account); }
        catch(java.io.IOException unavailable) { throw new IllegalStateException("Cannot clear translation settings",unavailable); }
        SharedPreferences.Editor roomPasswords=preferences.edit();
        for(String key:preferences.getAll().keySet()) if(key.startsWith("private-read-") || key.startsWith("group-read-receipts-enabled-") || key.startsWith("read-receipts-enabled-") || key.startsWith("room-password-") || key.startsWith("plain-reactions-") || key.startsWith("last-send-error-") || key.startsWith("room-recipient-exclusions-") || key.startsWith("contact-requests-") || key.startsWith("automatic-room-recipients-") || key.startsWith("blocked-jids-") || key.startsWith("unavailable-room-recipients-") || key.startsWith("last-skipped-") || key.startsWith("last-room-invite-error-") || key.startsWith("last-disconnect-")) roomPasswords.remove(key);
        if(!roomPasswords.commit()) throw new IllegalStateException("Cannot remove room credentials");
        AndroidUtilities.cancelRunOnUIThread(retryConnection);
        worker.execute(() -> {
            for (long peer : new ArrayList<>(typingPeers)) if (typingPeers.remove(peer)) publishTyping(peer, false);
            typingAddresses.clear(); clearRoomTyping(); presences.clear(); lastPresenceQueries.clear(); presencePeer=0;
            if (session != null) { MonogramCallService.unbind(account,session);session.close(); session = null; }
            sessionEpoch++;cancelSearchLoads(archiveSync); archiveSync = null;pendingRecoveryRooms.clear();
            try { if(!departing.isEmpty()) { modernStore.clearIncoming(departing);legacyStore.clearIncoming(departing); } }
            catch(Exception failure) { throw new IllegalStateException("Cannot clear pending encrypted messages",failure); }
            cancelFileTransfers();
            automaticJobs.clear(); autoAttempted.clear(); mediaInfoScanned.clear();
            savedTagColors=java.util.Collections.emptyMap();linkPreviews.clear();store.clear(); self = 0;
            state(ConnectionsManager.ConnectionStateWaitingForNetwork);
        });
    }
    public java.io.File avatarPath(long peer) { return store.avatar(peer); }
    public java.util.Map<String, String> profileDetails(long peer) {
        try { return MonogramProfiles.details(store.profile(peer), store.jid(peer)); }
        catch (IllegalArgumentException unknown) { return java.util.Collections.emptyMap(); }
    }
    public com.negzero.monogram.xmpp.XmppProfile cachedProfile(long peer) { return store.profile(peer); }
    public String localName(long peer) { return store.localName(peer); }
    public void localName(long peer,String name,java.util.function.Consumer<String> completed) {
        final long owner=accountEpoch;final String jid=accountJid();
        worker.execute(() -> {
            String error=null;
            try {
                if(!sameAccount(owner,jid) || peer<=0 || peer==self) throw new IllegalArgumentException("Choose another contact to rename.");
                store.localName(peer,name);
            } catch(IllegalArgumentException invalid) { error=invalid.getMessage(); }
            catch(Exception failure) { error="Could not save the local name. Please try again."; }
            final String result=error;
            AndroidUtilities.runOnUIThread(() -> {
                if(!sameAccount(owner,jid)) return;
                if(result==null) publishProfile(peer,sessionEpoch);
                completed.accept(result);
            });
        });
    }
    public String contactNote(long peer) { return MonogramContactDetails.note(account,accountJid(),store.jid(peer)); }
    public void editLocalContact(long peer,String name,String note,java.util.function.Consumer<String> completed) {
        final long epoch=accountEpoch;final String owner=accountJid();
        cacheWorker.execute(()->{
            String error=null;
            try {
                if(!sameAccount(epoch,owner) || peer<=0 || peer==self) throw new IllegalArgumentException("Choose another contact");
                if(note.length()>8192) throw new IllegalArgumentException("Notes can contain up to 8192 characters");
                store.localName(peer,name);MonogramContactDetails.note(account,owner,store.jid(peer),note);
            } catch(Exception failed) { error=failed instanceof IllegalArgumentException ? failed.getMessage() : "Could not save contact details"; }
            final String result=error;AndroidUtilities.runOnUIThread(()->{
                if(!sameAccount(epoch,owner)) return;
                if(result==null) { publishLocalContact(peer);publishProfile(peer,sessionEpoch); }
                completed.accept(result);
            });
        });
    }
    private void publishLocalContact(long peer) {
        TLRPC.UserFull current=MessagesController.getInstance(account).getUserFull(peer);
        if(current!=null) {
            TLRPC.UserFull updated=store.fullUser(peer,self);current.personal_photo=updated.personal_photo;current.profile_photo=updated.profile_photo;current.note=updated.note;
            current.flags=(current.flags&~2097152)|(updated.flags&2097152);current.flags2=(current.flags2&~TLObject.FLAG_22)|(updated.flags2&TLObject.FLAG_22);
            MessagesStorage.getInstance(account).updateUserInfo(current,false);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad,peer,current);
        }
    }
    public void contactPhoto(long peer,File source,boolean suggest,java.util.function.Consumer<String> completed) {
        final long epoch=accountEpoch;final String owner=accountJid();
        cacheWorker.execute(()->{
            String error=null;File prepared=null;
            try {
                if(!sameAccount(epoch,owner) || peer<=0 || peer==self) throw new IllegalArgumentException("Choose another contact");
                if(source!=null) {
                    if(source.length()<=0 || source.length()>com.negzero.monogram.xmpp.XmppProfile.MAX_AVATAR_BYTES) throw new IllegalArgumentException("Invalid contact photo");
                    byte[] bytes=new byte[(int)source.length()];try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(source))) { input.readFully(bytes); }
                    prepared=MonogramProfiles.cache(ApplicationLoader.applicationContext,"local-contact-"+account+"-"+Integer.toHexString(owner.hashCode()),MonogramProfiles.png(bytes));
                }
                if(!sameAccount(epoch,owner)) throw new IllegalStateException("Account changed");
                if(!suggest) MonogramContactDetails.avatar(account,owner,store.jid(peer),prepared);
                else if(prepared==null) throw new IllegalArgumentException("Choose a photo to suggest");
            } catch(Exception failed) { error=failed instanceof IllegalArgumentException ? failed.getMessage() : "Could not prepare contact photo"; }
            final File image=prepared;final String result=error;AndroidUtilities.runOnUIThread(()->{
                if(!sameAccount(epoch,owner)) return;
                if(result==null) {
                    if(suggest) SendMessagesHelper.prepareSendingPhoto(org.telegram.messenger.AccountInstance.getInstance(account),image.getAbsolutePath(),null,peer,null,null,null,"Suggested profile photo",null,null,null,0,null,true,0,0,null);
                    else { publishLocalContact(peer);publishProfile(peer,sessionEpoch); }
                }
                completed.accept(result);
            });
        });
    }
    public void acceptPhotoSuggestion(File source,java.util.function.Consumer<String> completed) {
        final long epoch=accountEpoch;final String owner=accountJid();
        worker.execute(()->{
            String error=null;
            try {
                if(!sameAccount(epoch,owner) || source==null || !source.isFile() || source.length()<=0 || source.length()>com.negzero.monogram.xmpp.XmppProfile.MAX_AVATAR_BYTES) throw new IllegalArgumentException("Download a supported photo first");
                byte[] bytes=new byte[(int)source.length()];try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(source))) { input.readFully(bytes); }
                byte[] png=MonogramProfiles.png(bytes);
                com.negzero.monogram.xmpp.XmppProfile changed=session().profiles.avatar(png);
                if(!sameAccount(epoch,owner)) throw new IllegalStateException("Account changed");
                store.profile(self,changed);publishProfile(self,sessionEpoch);
            } catch(Exception failed) { error=failed instanceof IllegalArgumentException ? failed.getMessage() : "Could not update profile photo"; }
            final String result=error;AndroidUtilities.runOnUIThread(()->{ if(sameAccount(epoch,owner)) completed.accept(result); });
        });
    }
    public void refreshProfile(long peer) { refreshProfile(peer,null); }
    public void refreshVisibleRoomMember(long room,long peer) { if(peer>0 && isRoom(room) && store.profile(peer)==null) refreshProfile(peer); }
    private void changedProfile(long peer) {
        profileVersions.merge(peer,1L,Long::sum);refreshProfile(peer);
    }
    private void refreshProfile(long peer,Runnable completed) { refreshProfile(peer,completed,accountEpoch); }
    private void refreshProfile(long peer,Runnable completed,long owner) {
        if(owner!=accountEpoch) return;
        if(peer==0) { if(completed!=null) completed.run();return; }
        final XmppSession expected=session;final long epoch=sessionEpoch;final String jid;
        try { jid=store.jid(peer); }
        catch(IllegalArgumentException unknown) { if(completed!=null && owner==accountEpoch) completed.run();return; }
        final MonogramProfileLoads.Load load;
        synchronized(profileLoads) {
            if(owner!=accountEpoch) return;
            if(expected!=session || epoch!=sessionEpoch) { worker.execute(() -> refreshProfile(peer,completed,owner));return; }
            load=profileLoads.begin(peer,expected,owner,epoch,jid,completed);
        }
        if(load==null) return;
        profileWorker.execute(() -> {
            final long version=profileVersions.getOrDefault(peer,0L);
            if(!currentProfileLoad(peer,load)) { worker.execute(() -> finishProfile(peer,load,version));return; }
            final com.negzero.monogram.xmpp.XmppProfile cached;
            try { cached=store.profileForRefresh(peer); }
            catch(Exception failed) { worker.execute(() -> finishProfile(peer,load,version));return; }
            if(!currentProfileLoad(peer,load)) { worker.execute(() -> finishProfile(peer,load,version));return; }
            org.jivesoftware.smackx.muc.RoomInfo information=null;
            if(peer<0 && roomMetadataDirty.contains(peer)) try { information=expected.rooms.information(jid); }
            catch(Exception failed) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram room metadata: "+failed.getClass().getSimpleName()); }
            com.negzero.monogram.xmpp.XmppProfile fetched=null;
            try { fetched=peer<0 ? expected.profiles.loadRoom(jid,cached,information) : expected.profiles.load(jid,cached); }
            catch(Exception failed) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram profile: "+failed.getClass().getSimpleName()); }
            final org.jivesoftware.smackx.muc.RoomInfo info=information;
            final com.negzero.monogram.xmpp.XmppProfile profile=fetched;
            // Apply before taking another image so the worker queue cannot retain unbounded source bytes.
            try { worker.submit(() -> {
                try {
                    if(!currentProfileLoad(peer,load) || profileVersions.getOrDefault(peer,0L)!=version) return;
                    if(info!=null) { store.roomMetadata(peer,info.getName(),info.getDescription());if(profile!=null) roomMetadataDirty.remove(peer); }
                    if(profile!=null && profile!=cached) {
                        try { store.profile(peer,profile);if(peer==self) expected.profiles.useOwnProfile(profile); }
                        catch(Exception failed) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram profile cache: "+failed.getClass().getSimpleName()); }
                    }
                    if(peer<0 && (info!=null || profile!=null)) { publishRoom(peer);publishRoomInfo(peer,expected,epoch); }
                    else if(profile!=null) publishProfile(peer,epoch);
                } catch(Exception failed) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram profile publication: "+failed.getClass().getSimpleName()); }
                finally { finishProfile(peer,load,version); }
            }).get(); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(Exception failed) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram profile handoff: "+failed.getClass().getSimpleName()); }
        });
    }
    private boolean currentProfileLoad(long peer,MonogramProfileLoads.Load load) {
        return load.owner==accountEpoch && load.connection!=null && session==load.connection && sessionEpoch==load.epoch && profileLoads.current(peer,load);
    }
    private void finishProfile(long peer,MonogramProfileLoads.Load load,long version) {
        java.util.List<Runnable> callbacks=profileLoads.finish(peer,load);if(callbacks==null || load.owner!=accountEpoch) return;
        Runnable completed=callbacks.isEmpty() ? null : () -> {
            for(Runnable callback:callbacks) { if(load.owner!=accountEpoch) return;callback.run(); }
        };
        if(load.connection!=null && session==load.connection && sessionEpoch==load.epoch && profileVersions.getOrDefault(peer,0L)!=version)
            refreshProfile(peer,completed,load.owner);
        else if(completed!=null) completed.run();
    }
    private final java.util.concurrent.ConcurrentHashMap<Long,TLRPC.User> pendingProfileUsers=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean profilePublishQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private void publishProfile(long peer,long epoch) {
        if(!UserConfig.getInstance(account).isClientActivated() || sessionEpoch!=epoch) return;
        pendingProfileUsers.put(peer,store.user(peer,self)); queueProfilePublication(epoch);
    }
    private void queueProfilePublication(long epoch) {
        if(!profilePublishQueued.compareAndSet(false,true)) return;
        AndroidUtilities.runOnUIThread(() -> {
            profilePublishQueued.set(false);
            if(sessionEpoch!=epoch) { if(!pendingProfileUsers.isEmpty()) queueProfilePublication(sessionEpoch); return; }
            java.util.Map<Long,TLRPC.User> batch=new java.util.LinkedHashMap<>(pendingProfileUsers);
            for(java.util.Map.Entry<Long,TLRPC.User> entry:batch.entrySet()) pendingProfileUsers.remove(entry.getKey(),entry.getValue());
            ArrayList<TLRPC.User> users=new ArrayList<>(batch.values()); if(users.isEmpty()) return;
            MessagesStorage.getInstance(account).putUsersAndChats(users,null,false,true);
            MessagesController.getInstance(account).putUsers(users,false);
            for (Long peer : batch.keySet()) {
                TLRPC.UserFull full=MessagesController.getInstance(account).getUserFull(peer);
                if(full==null) continue;
                com.negzero.monogram.xmpp.XmppProfile profile=store.profile(peer);
                String about=profile==null ? "" : profile.field("DESC");
                if(!java.util.Objects.equals(full.about,about)) {
                    full.about=about;
                    if(about==null || about.isEmpty()) full.flags&=~2; else full.flags|=2;
                    MessagesStorage.getInstance(account).updateUserInfo(full,false);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad,peer,full);
                }
            }
            TLRPC.User own=batch.get(self);
            if(own!=null) { UserConfig.getInstance(account).setCurrentUser(own); UserConfig.getInstance(account).saveConfig(true);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged); }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_ALL);
            if(!pendingProfileUsers.isEmpty()) queueProfilePublication(epoch);
        },200);
    }
    private TLRPC.TL_users_userFull profileResult(long peer) {
        TLRPC.TL_users_userFull result = new TLRPC.TL_users_userFull();
        result.full_user = store.fullUser(peer, self);
        result.full_user.blocked=blockingCache().blocked(store.jid(peer));
        applyCallCapabilities(peer,result.full_user);result.users.add(result.full_user.user);return result;
    }
    public long accountGeneration() { return accountEpoch; }
    long callOwner() { return accountEpoch; }
    void callEnded(long owner,String ownerJid,long peer,String sid,boolean outgoing,boolean video,long timestamp,int duration,String reason) {
        worker.execute(() -> {
            if(!sameAccount(owner,ownerJid) || self<=0 || accountJid().isEmpty()) return;
            try {
                int id=store.saveCall(self,peer,sid,outgoing,video,timestamp,duration,reason);
                if(id==0 || !UserConfig.getInstance(account).isClientActivated()) return;
                TLRPC.Message message=store.getMessage(id,self);
                ArrayList<TLRPC.Update> updates=new ArrayList<>();
                TL_update.TL_updateNewMessage update=new TL_update.TL_updateNewMessage();update.message=message;updates.add(update);
                ArrayList<TLRPC.User> users=new ArrayList<>();users.add(store.user(peer,self));users.add(ownUser());
                Utilities.stageQueue.postRunnable(() -> {
                    if(sameAccount(owner,ownerJid)) MessagesController.getInstance(account).processUpdateArray(updates,users,new ArrayList<>(),false,now());
                });
            } catch(Exception failure) {
                if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram call history: "+failure.getClass().getSimpleName());
            }
        });
    }
    private MonogramCallPrivacy callPrivacy() throws Exception {
        return new MonogramCallPrivacy(account,accountJid(),id -> store.user(id,self),chat -> store.roomChat(-chat,self),
                (peer,chat) -> store.roomMember(chat,peer));
    }
    void resolveCallUser(String address,boolean incoming,XmppSession expected,Consumer<TLRPC.User> callback) {
        final long owner=accountEpoch;
        worker.execute(() -> {
            TLRPC.User result=null;
            try {
                if(owner==accountEpoch && session==expected && expected.connection().isAuthenticated() && !accountJid().equals(address)) {
                    org.jxmpp.jid.EntityBareJid jid=org.jxmpp.jid.impl.JidCreate.entityBareFrom(address);
                    long peer=store.peer(jid.toString());
                    boolean contact=Roster.getInstanceFor(expected.connection()).getEntry(jid)!=null;
                    if(!incoming || callPrivacy().allowIncoming(peer,contact)) result=store.user(peer,self);
                }
            } catch(Exception invalid) { /* Unavailable account/privacy data cannot authorize a call. */ }
            final TLRPC.User user=result;
            AndroidUtilities.runOnUIThread(() -> {
                if(owner!=accountEpoch || session!=expected) { callback.accept(null);return; }
                if(user!=null) MessagesController.getInstance(account).putUser(user,false);
                callback.accept(user);
            });
        });
    }
    java.util.concurrent.CompletableFuture<Boolean> allowDirectCall(long peer) {
        final long owner=accountEpoch;final XmppSession expected=session;
        java.util.concurrent.CompletableFuture<Boolean> result=new java.util.concurrent.CompletableFuture<>();
        worker.execute(() -> {
            boolean allowed=false;
            try {
                if(owner==accountEpoch && expected!=null && session==expected && expected.connection().isAuthenticated())
                    allowed=callPrivacy().allowDirect(peer,Roster.getInstanceFor(expected.connection()).getEntry(org.jxmpp.jid.impl.JidCreate.entityBareFrom(store.jid(peer)))!=null);
            } catch(Exception unavailable) { /* Relay-only when policy cannot be read. */ }
            result.complete(allowed);
        });
        return result;
    }
    private void applyCallCapabilities(long peer,TLRPC.UserFull full) {
        XmppSession expected=session;
        com.negzero.monogram.xmpp.calls.XmppCalls.Capabilities capabilities=expected==null ? null : expected.calls.cachedCapabilities(store.jid(peer));
        full.phone_calls_available=peer>0 && peer!=self && capabilities!=null && capabilities.audio;
        full.video_calls_available=full.phone_calls_available && capabilities.video;
    }
    void callCapabilitiesChanged(String address) {
        final long owner=accountEpoch;final XmppSession expected=session;
        worker.execute(() -> {
            if(owner!=accountEpoch || session!=expected) return;
            long peer=store.knownPeer(address);if(peer<=0 || peer==self) return;
            TLRPC.UserFull latest=store.fullUser(peer,self);applyCallCapabilities(peer,latest);
            AndroidUtilities.runOnUIThread(() -> {
                if(owner!=accountEpoch || session!=expected) return;
                TLRPC.UserFull cached=MessagesController.getInstance(account).getUserFull(peer);
                if(cached==null || cached.phone_calls_available==latest.phone_calls_available && cached.video_calls_available==latest.video_calls_available) return;
                cached.phone_calls_available=latest.phone_calls_available;cached.video_calls_available=latest.video_calls_available;
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad,peer,cached);
            });
        });
    }
    public TLRPC.User ownUser() { return store.user(self, self); }
    public String jid(long peer) { return store.jid(peer); }
    private String roomRecipientsKey(long peer) { return "room-recipient-exclusions-"+accountJid()+"\n"+store.jid(peer); }
    public java.util.Set<String> excludedRoomRecipients(long peer) {
        return new java.util.HashSet<>(preferences.getStringSet(roomRecipientsKey(peer),java.util.Collections.emptySet()));
    }
    public boolean setRoomRecipient(long peer,String address,boolean included) {
        if(!isRoom(peer) || address.equals(accountJid())) throw new IllegalArgumentException("Invalid room recipient");
        try { if(!org.jxmpp.jid.impl.JidCreate.entityBareFrom(address).toString().equals(address)) throw new IllegalArgumentException("Invalid recipient address"); }
        catch(org.jxmpp.stringprep.XmppStringprepException invalid) { throw new IllegalArgumentException(invalid); }
        String key=roomRecipientsKey(peer);
        java.util.Set<String> previous=excludedRoomRecipients(peer),excluded=new java.util.HashSet<>(previous);
        if(included) excluded.remove(address); else excluded.add(address);
        if(preferences.edit().putStringSet(key,excluded).commit()) return true;
        preferences.edit().putStringSet(key,previous).apply(); return false;
    }
    private String automaticRecipientsKey(long peer) { return "automatic-room-recipients-"+accountJid()+"\n"+store.jid(peer); }
    public boolean automaticRoomRecipients(long peer) { return peer<0 && preferences.getBoolean(automaticRecipientsKey(peer),true); }
    public void setAutomaticRoomRecipients(long peer,boolean enabled) {
        if(peer>=0) throw new IllegalArgumentException("Room required");
        if(!preferences.edit().putBoolean(automaticRecipientsKey(peer),enabled).commit()) throw new IllegalStateException("Could not save recipient policy");
    }
    public String skippedRoomRecipients(long peer) { return preferences.getString("last-skipped-"+accountJid()+"\n"+peer,null); }
    private static String unavailableRecipientsKey(String jid) { return "unavailable-room-recipients-"+jid; }
    private java.util.Map<String,String> unavailableRecipients(String jid) {
        java.util.Map<String,String> result=new java.util.LinkedHashMap<>();
        String raw=preferences.getString(unavailableRecipientsKey(jid),"");
        if(raw.length()>262144) return result;
        try {
            org.json.JSONObject data=new org.json.JSONObject(raw);
            java.util.Iterator<String> keys=data.keys();
            while(keys.hasNext() && result.size()<512) {
                String key=keys.next();Object reason=data.opt(key);
                if(key.length()<=3200 && reason instanceof String && ((String)reason).length()<=512) result.put(key,(String)reason);
            }
        } catch(org.json.JSONException invalid) { /* A disposable performance cache must not prevent sign-in. */ }
        return result;
    }
    private void saveUnavailableRecipients(String jid,java.util.Map<String,String> values) {
        org.json.JSONObject data=new org.json.JSONObject();int size=2;
        for(java.util.Map.Entry<String,String> entry:new java.util.TreeMap<>(values).entrySet()) {
            if(entry.getKey().length()>3200 || entry.getValue()==null || entry.getValue().length()>512) continue;
            int encoded=org.json.JSONObject.quote(entry.getKey()).length()+org.json.JSONObject.quote(entry.getValue()).length()+2;
            if(size+encoded>262000 || data.length()>=512) break;
            size+=encoded;
            try { data.put(entry.getKey(),entry.getValue()); } catch(org.json.JSONException invalid) { }
        }
        SharedPreferences.Editor edit=preferences.edit();String key=unavailableRecipientsKey(jid);
        if(data.length()==0) edit.remove(key);else edit.putString(key,data.toString());
        edit.apply();
    }
    private void bindUnavailableRecipients(XmppSession expected,long epoch,long owner,String jid) {
        java.util.concurrent.atomic.AtomicReference<java.util.Map<String,String>> pending=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable persist=new Runnable() {
            public void run() {
                java.util.Map<String,String> snapshot=pending.getAndSet(null);
                if(snapshot!=null && session==expected && sessionEpoch==epoch && sameAccount(owner,jid)) saveUnavailableRecipients(jid,snapshot);
                queued.set(false);
                if(pending.get()!=null && queued.compareAndSet(false,true)) worker.execute(this);
            }
        };
        expected.rooms.setUnavailableCache(unavailableRecipients(jid),snapshot -> {
            pending.set(snapshot);
            if(queued.compareAndSet(false,true)) worker.execute(persist);
        });
    }
    private void reportSkippedRecipients(long peer,com.negzero.monogram.xmpp.XmppRoomMessage message) {
        String key="last-skipped-"+accountJid()+"\n"+peer;
        SharedPreferences.Editor edit=preferences.edit();
        if(message.skippedRecipients.isEmpty()) edit.remove(key);
        else {
            StringBuilder report=new StringBuilder("Last encrypted send · ").append(new java.util.Date(message.timestamp)).append("\n");
            message.skippedRecipients.forEach((recipient,reason) -> report.append(recipient).append(": ").append(reason).append('\n'));
            edit.putString(key,report.toString());
        }
        edit.apply();
    }
    public java.util.Set<String> knownFingerprintAddresses(long peer) {
        java.util.Set<String> addresses=new java.util.LinkedHashSet<>(); if(self>0) addresses.add(store.jid(self));
        if(peer>0) addresses.add(store.jid(peer));
        else for(TLRPC.User user:store.roomUsers(peer,self)) { String address=store.jid(user.id); if(address.indexOf('/')<0) addresses.add(address); }
        return addresses;
    }
    public XmppSession session() {
        XmppSession current = session;
        if (current == null || !current.connection().isAuthenticated()) throw new IllegalStateException("XMPP is disconnected");
        return current;
    }
    public boolean encrypted(long peer) { return preferences.getBoolean("omemo-" + peer, true); }
    public boolean modernEncryption(long peer) { return peer>0 && preferences.getInt("omemo-version-"+peer,1)==2; }
    public XmppMessage.Encryption encryption(long peer) {
        return !encrypted(peer) ? XmppMessage.Encryption.PLAIN : modernEncryption(peer)
                ? XmppMessage.Encryption.OMEMO2 : XmppMessage.Encryption.LEGACY_OMEMO;
    }
    public boolean automaticEncryption(long peer) { return peer!=0 && encryptionPolicy.automatic(accountJid(),store.jid(peer)); }
    public void setAutomaticEncryption(long peer,boolean enabled) {
        encryptionPolicy.automatic(accountJid(),store.jid(peer),enabled);encryptionPolicyEpoch++;
    }
    private void selectDailyEncryption(XmppSession expected,long peer,long owner) throws Exception {
        if(!automaticEncryption(peer)) return;
        final String own=accountJid(),address=store.jid(peer);
        final long revision=encryptionPolicyEpoch,now=System.currentTimeMillis();
        XmppMessage.Encryption mode=encryptionPolicy.cached(own,address,now);
        if(mode==null) {
            mode=com.negzero.monogram.xmpp.EncryptionFallback.select(candidate -> expected.probeEncryption(address,candidate,peer<0,excludedRoomRecipients(peer),automaticRoomRecipients(peer)));
            if(owner!=accountEpoch || session!=expected || !expected.connection().isAuthenticated()) throw new IllegalStateException("Account connection changed");
            if(revision!=encryptionPolicyEpoch || !automaticEncryption(peer)) throw new IllegalStateException("Encryption setting changed");
            encryptionPolicy.selected(own,address,peer,mode,System.currentTimeMillis());
        }
        if(mode==XmppMessage.Encryption.PLAIN) warnIfPlain(peer);
    }
    private void warnIfPlain(long peer) {
        if(peer==0 || !automaticEncryption(peer) || encryption(peer)!=XmppMessage.Encryption.PLAIN) return;
        final long owner=accountEpoch,now=System.currentTimeMillis();
        final String own=accountJid(),address=store.jid(peer);
        if(encryptionPolicy.cached(own,address,now)!=XmppMessage.Encryption.PLAIN || !encryptionPolicy.needsWarning(own,address,now)) return;
        AndroidUtilities.runOnUIThread(() -> {
            if(owner!=accountEpoch || encryption(peer)!=XmppMessage.Encryption.PLAIN || !encryptionPolicy.needsWarning(own,address,System.currentTimeMillis())) return;
            org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getSafeLastFragment();
            if(fragment instanceof org.telegram.ui.ChatActivity && fragment.getCurrentAccount()==account
                    && ((org.telegram.ui.ChatActivity)fragment).getDialogId()==peer && org.telegram.ui.Components.BulletinFactory.canShowBulletin(fragment)) {
                org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip,"Sending without end-to-end encryption in this conversation.").show();
                encryptionPolicy.warned(own,address,System.currentTimeMillis());
            }
        },200);
    }
    public void setEncryption(long peer,XmppMessage.Encryption mode) {
        if (peer<0 && mode==XmppMessage.Encryption.OMEMO2) throw new UnsupportedOperationException("OMEMO 2 rooms are not implemented");
        setAutomaticEncryption(peer,false);
        if (!preferences.edit().putBoolean("omemo-"+peer,mode!=XmppMessage.Encryption.PLAIN)
                .putInt("omemo-version-"+peer,mode==XmppMessage.Encryption.OMEMO2 ? 2 : 1).commit()) throw new IllegalStateException("Cannot save encryption preference");
    }
    public void retryEncryptedHistory(long peer,boolean ownDevices) {
        worker.execute(() -> {
            java.util.Set<Long> peers=ownDevices ? store.unreadableModernPeers() : new java.util.HashSet<>(java.util.Collections.singleton(peer));
            if (peers.isEmpty() || session==null || !session.connection().isAuthenticated()) return;
            ArchiveSync sync=archiveSync;
            if (sync!=null && currentArchive(sync)) {
                // Apply the reset between pages, after the current page's decrypted messages are stored.
                sync.rescan.addAll(peers); if (sync.pending.isEmpty()) nextArchivePage(sync);
            } else {
                for(long id:peers) store.resetArchiveCursor(id);
                startArchiveSync(session);
            }
        });
    }
    public void autoDownloadSettingsChanged(boolean changed) {
        final long owner = accountEpoch;
        if (changed) {
            for (java.util.Map.Entry<Integer, FileTransfer.Job> entry : automaticJobs.entrySet()) {
                entry.getValue().cancel(); fileJobs.remove(entry.getKey(), entry.getValue());
                for (java.util.Map.Entry<String, FileTransfer.Job> name : fileNames.entrySet())
                    if (name.getValue() == entry.getValue()) fileNames.remove(name.getKey(), name.getValue());
            }
            automaticJobs.clear(); autoAttempted.clear();
        }
        worker.execute(() -> {
            if (owner != accountEpoch) return;
            if (mediaHistoryPeer != 0 && self > 0)
                for (TLRPC.Message message : store.history(mediaHistoryPeer, self, 0, 100)) maybeAutoDownload(message);
        });
    }
    public void setEncrypted(long peer, boolean encrypted) {
        setAutomaticEncryption(peer,false);
        if (!preferences.edit().putBoolean("omemo-" + peer, encrypted).commit()) throw new IllegalStateException("Cannot save encryption preference");
    }
    private int now() { return (int) (System.currentTimeMillis() / 1000); }
    private boolean sameAccount(long owner,String jid) { return owner==accountEpoch && jid.equals(accountJid()) && self>0 && jid.equals(store.jid(self)); }
    private void decryptionTimeout(long owner,String jid,int id,Runnable reveal) {
        if (id<=0 || !store.pendingDecryption(id)) return;
        Utilities.stageQueue.postRunnable(() -> worker.execute(() -> {
            if (sameAccount(owner,jid) && store.pendingDecryption(id)) {
                try { reveal.run(); } finally { store.finishPendingDecryption(id); }
            }
        }),5000);
    }
    private void received(long owner,String jid,XmppMessage message,Runnable accepted) {
        worker.execute(() -> {
            // A reconnect may replace the session after decryption consumed a ratchet key.
            if(!sameAccount(owner,jid)) return;
            if(message.archived) {
                long peer=store.peer(message.peer.toString());
                java.util.Set<Integer> updated=new java.util.HashSet<>();int id=store.save(message);
                if(id!=0) updated.add(id);
                publishArchiveChanges(new ArchiveSync(null,owner,jid),java.util.Collections.singleton(peer),updated,id!=0);
            } else incoming(message);
            if(accepted!=null) accepted.run();
        });
    }
    private void receivedRoom(long owner,String jid,com.negzero.monogram.xmpp.XmppRoomMessage message,Runnable accepted) {
        worker.execute(() -> {
            if(!sameAccount(owner,jid)) return;
            long dialog=store.knownRoom(message.room.toString());
            if(dialog==0) { if(accepted!=null) pendingRecoveryRooms.add(message.room.toString());return; }
            pendingRecoveryRooms.remove(message.room.toString());
            if(message.archived) {
                java.util.Set<Integer> updated=new java.util.HashSet<>();int id=store.saveRoom(message,self);
                if(id!=0) updated.add(id);
                publishArchiveChanges(new ArchiveSync(null,owner,jid),java.util.Collections.singleton(dialog),updated,id!=0);
            } else incomingRoom(message);
            if(accepted!=null) accepted.run();
        });
    }
    private void incoming(XmppMessage message) {
        long peer = store.peer(message.peer.toString());
        if(message.reactions!=null) { publishReactions(peer,store.reactions.save(message,self)); return; }
        if (store.profile(peer) == null) refreshProfile(peer);
        final long archiveOwner = accountEpoch;
        final String archiveJid = accountJid();
        boolean unknown = false;
        if (!message.outgoing && peer != self && message.correction == null && message.retraction == null
                && MonogramDialogState.archiveSettings(account, archiveJid).archive_and_mute_new_noncontact_peers
                && store.history(peer, self, 0, 1).isEmpty()) {
            XmppSession connected = session;
            if (connected != null && connected.connection().isAuthenticated()) {
                Roster roster = Roster.getInstanceFor(connected.connection());
                unknown = roster.isLoaded() && roster.getEntry(message.peer) == null;
            }
        }
        final boolean newUnknownChat = unknown;
        int date = (int) (message.timestamp / 1000);
        java.util.Set<Integer> replaced = new java.util.HashSet<>();
        int id = store.save(message,replaced);
        applyRetractions(peer);
        java.util.Set<Integer> updated = new java.util.HashSet<>(); if (id != 0) updated.add(id);
        MonogramStore.CorrectionChanges corrections = store.reconcileCorrections(peer, updated);
        updated.addAll(corrections.edited); refreshReplies(peer, updated);
        publishReactions(peer,java.util.Collections.emptySet());
        ArrayList<TLRPC.Update> updates = correctionUpdates(corrections, id);
        if (id != 0 && !store.hiddenMessage(id)) {
            if(replaced.contains(id)) {
                TL_update.TL_updateEditMessage update=new TL_update.TL_updateEditMessage(); update.message=store.getMessage(id,self); updates.add(update);
            } else {
                TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage(); update.message = store.getMessage(id, self); updates.add(update);
            }
        }
        if (updates.isEmpty()) return;
        if (!UserConfig.getInstance(account).isClientActivated()) return;
        ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(store.user(peer, self)); users.add(ownUser());
        final boolean archiveIncoming = id > 0 && !store.hiddenMessage(id) && !store.getMessage(id, self).out
                && message.correction == null && message.retraction == null;
        AndroidUtilities.runOnUIThread(() -> {
            if (!sameAccount(archiveOwner, archiveJid)) return;
            if (archiveIncoming)
                MonogramDialogState.incoming(account, archiveJid, peer, id, newUnknownChat);
            Utilities.stageQueue.postRunnable(() -> {
                if (sameAccount(archiveOwner, archiveJid)) MessagesController.getInstance(account).processUpdateArray(updates, users, new ArrayList<>(), false, date);
            });
        });
        if (typingPeers.contains(peer)) publishTyping(peer, true);
        if (id != 0 && !store.hiddenMessage(id)) maybeAutoDownload(store.getMessage(id, self));
    }
    private void receivedReceipt(long owner, String own, com.negzero.monogram.xmpp.MessageReceipt receipt) {
        // A very fast peer can acknowledge before send() returns and saves the local ID.
        sendWorker.execute(() -> worker.execute(() -> {
            if (!sameAccount(owner,own)) return;
            long peer = store.knownPeer(receipt.peer.toString());
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            for (int id : store.receipt(peer,receipt.id,receipt.displayed))
                if (!store.hiddenMessage(id)) messages.add(store.getMessage(id,self));
            publishReceiptMessages(owner,own,peer,messages);
        }));
    }
    private void receivedRoomDisplayed(long owner,String own,com.negzero.monogram.xmpp.RoomDisplayedMarker marker) {
        worker.execute(() -> {
            if(!sameAccount(owner,own)) return;
            long peer=store.knownRoom(marker.room.toString());if(peer==0) return;
            if(marker.own) {
                applyOwnDisplayed(owner,own,peer,marker.id);
                return;
            }
            ArrayList<TLRPC.Message> messages=new ArrayList<>();
            for(int id:store.roomDisplayed(peer,marker)) if(!store.hiddenMessage(id)) messages.add(store.getMessage(id,self));
            publishReceiptMessages(owner,own,peer,messages);
        });
    }
    private void applyOwnDisplayed(long owner,String own,long peer,String wire) {
        if(peer==0 || !sameAccount(owner,own)) return;
        int anchor=store.reads.ownMarkerAnchor(peer,wire);if(anchor==0) return;
        AndroidUtilities.runOnUIThread(() -> {
            if(sameAccount(owner,own)) MessagesController.getInstance(account).applyMonogramSyncedRead(peer,anchor);
        });
    }
    private void publishReceiptMessages(long owner,String own,long peer,ArrayList<TLRPC.Message> messages) {
            if (messages.isEmpty() || !sameAccount(owner,own)) return;
            MessagesStorage.getInstance(account).putMessages(messages,false,true,false,0,0,0);
            Utilities.stageQueue.postRunnable(() -> AndroidUtilities.runOnUIThread(() -> {
                if (!sameAccount(owner,own)) return;
                MessagesController controller = MessagesController.getInstance(account);
                ArrayList<MessageObject> objects = new ArrayList<>();
                ArrayList<MessageObject> latest = controller.dialogMessage.get(peer);
                for (TLRPC.Message message : messages) {
                    MessageObject object = new MessageObject(account,message,true,true); objects.add(object);
                    if (latest != null) for (int i=0;i<latest.size();i++) if (latest.get(i).getId()==message.id) latest.set(i,object);
                    MessageObject indexed = controller.dialogMessagesByIds.get(message.id);
                    if (indexed != null && indexed.getDialogId()==peer) controller.dialogMessagesByIds.put(message.id,object);
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects,peer,objects);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
            }));
    }
    private ArrayList<TLRPC.Update> correctionUpdates(MonogramStore.CorrectionChanges changes, int arriving) {
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        for (int id : changes.edited) {
            TL_update.TL_updateEditMessage edit = new TL_update.TL_updateEditMessage(); edit.message = store.getMessage(id, self); updates.add(edit);
        }
        TL_update.TL_updateDeleteMessages hidden = new TL_update.TL_updateDeleteMessages();
        for (int id : changes.hidden) if (id != arriving) hidden.messages.add(id);
        if (!hidden.messages.isEmpty()) updates.add(hidden);
        for (int id : changes.shown) if (id != arriving) {
            TL_update.TL_updateNewMessage shown = new TL_update.TL_updateNewMessage(); shown.message = store.getMessage(id, self); updates.add(shown);
        }
        return updates;
    }
    private void publishCorrections(long peer, ArrayList<TLRPC.Update> updates) {
        if (updates.isEmpty() || !UserConfig.getInstance(account).isClientActivated()) return;
        ArrayList<TLRPC.User> users=new ArrayList<>(); ArrayList<TLRPC.Chat> chats=new ArrayList<>();
        if(peer<0) { users.addAll(store.roomUsers(peer,self)); chats.add(store.roomChat(peer,self)); }
        else { users.add(store.user(peer,self)); users.add(ownUser()); }
        final long owner=accountEpoch;
        Utilities.stageQueue.postRunnable(() -> { if(accountEpoch==owner) MessagesController.getInstance(account).processUpdateArray(updates,users,chats,false,now()); });
    }
    private void refreshReplies(long peer) { refreshReplies(peer, java.util.Collections.emptySet()); }
    public boolean canRetract(int id,long peer) {
        try { store.retractionTarget(id,peer,self); return true; } catch(IllegalArgumentException unavailable) { return false; }
    }
    public void retractMessages(ArrayList<Integer> ids,long peer,Consumer<String> completed) {
        final long owner=accountEpoch; final ArrayList<Integer> selection=new ArrayList<>(ids);
        AndroidUtilities.runOnUIThread(() -> {
            org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getSafeLastFragment();
            if(owner==accountEpoch && fragment!=null && fragment.getCurrentAccount()==account && org.telegram.ui.Components.BulletinFactory.canShowBulletin(fragment))
                org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip,LocaleController.getString(R.string.MonogramRequestingRetraction)).show();
        });
        sendWorker.execute(() -> {
            int sent=0; String error=null;
            try {
                if(owner!=accountEpoch) throw new IllegalStateException("Account changed");
                XmppSession expected=session(); String jid=store.jid(peer);
                ArrayList<MonogramRetractions.Target> targets=new ArrayList<>();
                for(int id:selection) targets.add(store.retractionTarget(id,peer,self));
                boolean legacy=peer<0 ? expected.rooms.legacyRetractions(jid) : expected.legacyRetractions(jid);
                for(MonogramRetractions.Target target:targets) {
                    if(owner!=accountEpoch || session!=expected) throw new IllegalStateException("Connection changed before the request was sent");
                    boolean old=legacy || target.current==null;
                    String reference=old ? target.legacy : target.current;
                    if(reference==null) throw new IllegalArgumentException("This client or room needs an unavailable original message reference");
                    com.negzero.monogram.xmpp.MessageRetraction request=new com.negzero.monogram.xmpp.MessageRetraction(reference,old);
                    if(peer<0) {
                        // Room retractions are author-bound control metadata, as sent by Monocles.
                        // They contain no original content and must not depend on OMEMO device availability.
                        expected.rooms.sendRetraction(jid,request,target.sender,target.stable,false,excludedRoomRecipients(peer),automaticRoomRecipients(peer));
                    } else {
                        String own=MonogramRetractions.fingerprint(expected.senderFingerprint(target.encryption));
                        if(target.encryption!=XmppMessage.Encryption.PLAIN && (own==null || target.fingerprint!=null && !target.fingerprint.equals(own)))
                            throw new IllegalArgumentException("Retract this encrypted message from the device that originally sent it.");
                        expected.sendRetraction(jid,request,target.encryption);
                    }
                    sent++;
                    worker.execute(() -> {
                        if(owner!=accountEpoch) return;
                        store.deleteMessages(java.util.Collections.singleton(target.id));
                        FileTransfer.Job job=fileJobs.remove(target.id); if(job!=null) job.cancel(); automaticJobs.remove(target.id);
                        TL_update.TL_updateDeleteMessages deletion=new TL_update.TL_updateDeleteMessages(); deletion.messages.add(target.id);
                        ArrayList<TLRPC.Update> updates=new ArrayList<>(); updates.add(deletion); publishCorrections(peer,updates);
                        refreshReplies(peer,java.util.Collections.singleton(target.id));
                    });
                }
            } catch(Exception failed) {
                error=(sent==0 ? "Retraction could not be completed. " : "Sent "+sent+" retraction requests before an error. ")
                        +com.negzero.monogram.xmpp.XmppRequestException.describe(failed);
            }
            final String result=error;
            worker.execute(() -> AndroidUtilities.runOnUIThread(() -> { if(accountEpoch==owner) completed.accept(result); }));
        });
    }
    private void applyRetractions(long peer) {
        java.util.Set<Integer> removed=store.reconcileRetractions(peer);
        if(removed.isEmpty()) return;
        for(int id:removed) { FileTransfer.Job job=fileJobs.remove(id); if(job!=null) job.cancel(); automaticJobs.remove(id); }
        TL_update.TL_updateDeleteMessages deletion=new TL_update.TL_updateDeleteMessages(); deletion.messages.addAll(removed);
        ArrayList<TLRPC.Update> updates=new ArrayList<>(); updates.add(deletion); publishCorrections(peer,updates);
        refreshReplies(peer,removed);
    }
    private void refreshNotifications(java.util.Set<Integer> updated) {
        if(updated.isEmpty() || !UserConfig.getInstance(account).isClientActivated()) return;
        long owner=accountEpoch;
        androidx.collection.LongSparseArray<ArrayList<MessageObject>> edits=new androidx.collection.LongSparseArray<>();
        for(int id:updated) {
            if(store.hiddenMessage(id)) continue;
            TLRPC.Message message=store.getMessage(id,self);
            if(message.out) continue;
            ArrayList<MessageObject> messages=edits.get(message.dialog_id);
            if(messages==null) { messages=new ArrayList<>(); edits.put(message.dialog_id,messages); }
            messages.add(new MessageObject(account,message,true,true));
        }
        // Stay behind pending live notifications; only replace entries still in the notification tray.
        Utilities.stageQueue.postRunnable(() -> MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() ->
                AndroidUtilities.runOnUIThread(() -> {
                    if(accountEpoch==owner) org.telegram.messenger.NotificationsController.getInstance(account).processEditedMessages(edits);
                })));
    }
    private void refreshReplies(long peer, java.util.Set<Integer> updated) {
        ArrayList<TLRPC.Message> changed = store.changedReplies(peer, self, updated);
        if (changed.isEmpty() || !UserConfig.getInstance(account).isClientActivated()) return;
        long owner = accountEpoch;
        MessagesStorage.getInstance(account).putMessages(changed, false, true, false, 0, 0, 0);
        AndroidUtilities.runOnUIThread(() -> {
            if (accountEpoch != owner) return;
            ArrayList<MessageObject> objects = new ArrayList<>();
            for (TLRPC.Message message : changed) objects.add(new MessageObject(account, message, true, true));
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, peer, objects);
        });
    }
    private static final class ReplyUnavailable extends IllegalArgumentException {
        ReplyUnavailable(Exception cause) { super("Original reply message is unavailable", cause); }
    }
    private ReplyReference replyTo(int target, long peer, boolean encrypted, String quote, int offset) {
        try {
            XmppSession current=session;
            boolean fallbackAllowed=peer>0 || current!=null && Boolean.FALSE.equals(current.rooms.supportsReplies(store.jid(peer)));
            return store.replyTo(target, peer, self, encrypted, quote, offset, fallbackAllowed);
        }
        catch (IllegalArgumentException invalid) { throw new ReplyUnavailable(invalid); }
    }
    private ReplyReference outgoingReply(TLRPC.InputReplyTo reply, long peer, boolean encrypted) {
        if (reply == null) return null;
        if (!(reply instanceof TLRPC.TL_inputReplyToMessage) || reply.top_msg_id != 0 || reply.todo_item_id != 0 || reply.poll_option != null
                || reply.monoforum_peer_id != null || reply.reply_to_peer_id != null && (peer<0 ? -reply.reply_to_peer_id.chat_id : reply.reply_to_peer_id.user_id) != peer) throw new UnsupportedOperationException();
        return replyTo(reply.reply_to_msg_id, peer, encrypted, reply.quote_text, reply.quote_offset);
    }
    private void sentLinkPreview(int notification,int currentAccount,Object... args) {
        if(currentAccount!=account || args.length<2 || !(args[1] instanceof Integer) || (int)args[1]<=0) return;
        final int id=(int)args[1];final long owner=accountEpoch;
        // ACK observers finish on the UI thread before preview replacements are delivered there.
        worker.execute(() -> {
            if(owner!=accountEpoch || store.hiddenMessage(id)) return;
            try { maybeLinkPreview(store.getMessage(id,self)); } catch(IllegalArgumentException unknownMessage) { /* Not a persisted XMPP message. */ }
        });
    }
    private void historyMediaLoaded(int notification,int currentAccount,Object... args) {
        if(currentAccount!=account || args.length<3 || !(args[2] instanceof java.util.List)) return;
        final long owner=accountEpoch;
        ArrayList<TLRPC.Message> messages=new ArrayList<>();
        for(Object item:(java.util.List<?>)args[2]) if(item instanceof MessageObject) messages.add(((MessageObject)item).messageOwner);
        // Start media work after native history delivery, so replacements cannot precede their rows.
        worker.execute(() -> {
            if(owner!=accountEpoch) return;
            for(TLRPC.Message message:messages) {
                maybeLinkPreview(message);
                if(!MonogramAttachments.has(message)) continue;
                File path=new File(message.attachPath);
                if(path.isFile()) refreshCachedMedia(message,path); else maybeAutoDownload(message);
            }
        });
    }
    private final java.util.Set<Integer> mediaInfoScanned=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer,Boolean>());
    private void refreshCachedMedia(TLRPC.Message message,File path) {
        if(!MonogramAttachments.needsMediaRefresh(message) || !mediaInfoScanned.add(message.id)) return;
        if(mediaInfoScanned.size()>4096) { mediaInfoScanned.clear(); mediaInfoScanned.add(message.id); }
        final long owner=accountEpoch;
        final String address=message.params.get(MonogramAttachments.PARAM);
        fileWorker.execute(() -> {
            boolean publicationQueued=false;
            try {
                if(owner!=accountEpoch || !path.isFile()) return;
                com.negzero.monogram.xmpp.FileShare sharing=store.sharing(message.id);
                String filename=sharing==null ? com.negzero.monogram.xmpp.FileLink.parse(address).fileName() : sharing.name;
                TLRPC.MessageMedia media=MonogramStickers.inspectDisplayHint(MonogramStickers.preserve(message.media,MonogramAttachments.decodedMedia(message.id,message.date,path,filename,sharing==null ? message.media.document.mime_type : sharing.mime)),path,store.displayStickerHint(message.id,address));
                MonogramAttachments.preserveMediaAttributes(message.media,media);
                if(media.document==null || MonogramAttachments.needsMediaInfo(media.document)) return;
                worker.execute(() -> {
                    try {
                        if(owner!=accountEpoch) return;
                        TLRPC.Message current=store.getMessage(message.id,self);
                        if(!address.equals(current.params.get(MonogramAttachments.PARAM)) || !path.equals(store.attachmentPath(message.id)) || !MonogramAttachments.needsMediaRefresh(current)) return;
                        MonogramAttachments.preserveMediaAttributes(current.media,media);
                        media.document.date=current.date;
                        if(!store.attachDownloaded(message.id,address,sharing,path,media)) return;
                        TLRPC.Message updated=store.getMessage(message.id,self);
                        ArrayList<TLRPC.Message> stored=new ArrayList<>(); stored.add(updated);
                        MessagesStorage.getInstance(account).putMessages(stored,false,true,false,0,0,0);
                        AndroidUtilities.runOnUIThread(() -> {
                            if(owner!=accountEpoch) return;
                            ArrayList<MessageObject> objects=new ArrayList<>(); objects.add(new MessageObject(account,updated,true,true));
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects,updated.dialog_id,objects);
                        });
                    } catch(Exception changed) { /* Cached metadata cannot replace a changed/deleted attachment. */ }
                    finally { mediaInfoScanned.remove(message.id); }
                });
                publicationQueued=true;
            } catch(Exception unavailable) { /* Metadata is optional; retain the cached document. */ }
            finally {
                // Suppress concurrent scans, but let a later history load retry a failed preview.
                if(!publicationQueued) mediaInfoScanned.remove(message.id);
            }
        });
    }
    private void maybeLinkPreview(TLRPC.Message message) {
        if(!MonogramLinkPreviews.candidate(message) || store.pendingDecryption(message.id) || store.hiddenMessage(message.id)) return;
        TLRPC.Message saved=store.getMessage(message.id,self);
        if(!MonogramLinkPreviews.candidate(saved) || !java.util.Objects.equals(MonogramLinkPreviews.firstUrl(saved.message),MonogramLinkPreviews.firstUrl(message.message))) return;
        final long owner=accountEpoch;
        if(linkPreviews.enabled()) linkPreviews.prepareCache();
        if((saved.media instanceof TLRPC.TL_messageMediaWebPage)!=(message.media instanceof TLRPC.TL_messageMediaWebPage)) publishLinkPreview(saved,owner);
        if(!linkPreviews.enabled() || saved.media instanceof TLRPC.TL_messageMediaWebPage && message.media instanceof TLRPC.TL_messageMediaWebPage && store.linkPreviewFresh(MonogramLinkPreviews.firstUrl(saved.message),System.currentTimeMillis())) return;
        linkPreviews.maybeFetch(saved,store.encryptionOf(message.id)>0,() -> owner==accountEpoch,worker,() -> {
            if(owner!=accountEpoch || store.hiddenMessage(message.id)) return;
            TLRPC.Message updated=store.getMessage(message.id,self);
            if(!java.util.Objects.equals(MonogramLinkPreviews.firstUrl(updated.message),MonogramLinkPreviews.firstUrl(message.message))) return;
            publishLinkPreview(updated,owner);
        });
    }
    private void publishLinkPreview(TLRPC.Message updated,long owner) {
        ArrayList<TLRPC.Message> stored=new ArrayList<>();stored.add(updated);
        MessagesStorage.getInstance(account).putMessages(stored,false,true,false,0,0,0);
        AndroidUtilities.runOnUIThread(() -> {
            if(owner!=accountEpoch) return;
            ArrayList<MessageObject> objects=new ArrayList<>();objects.add(new MessageObject(account,updated,true,true));
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects,updated.dialog_id,objects);
        });
    }
    public void refreshLinkPreviews() {
        final long owner=accountEpoch;
        worker.execute(() -> {
            if(owner!=accountEpoch || mediaHistoryPeer==0) return;
            for(TLRPC.Message message:store.history(mediaHistoryPeer,self,0,150)) maybeLinkPreview(message);
        });
    }
    public void clearLinkPreviews() {
        final long owner=accountEpoch;
        worker.execute(() -> { if(owner!=accountEpoch) return;linkPreviews.clear();
            if(mediaHistoryPeer!=0) for(TLRPC.Message message:store.history(mediaHistoryPeer,self,0,150))
                if(MonogramLinkPreviews.firstUrl(message.message)!=null) publishLinkPreview(message,owner);
        });
    }
    private void maybeAutoDownload(TLRPC.Message message) {
        maybeLinkPreview(message);
        if (!MonogramAttachments.has(message)) return;
        File path=new File(message.attachPath);
        if(path.isFile()) return;
        if(DownloadController.getInstance(account).getCurrentDownloadMask()==0) return;
        final com.negzero.monogram.xmpp.FileShare sharing;
        try { sharing = store.sharing(message.id); }
        catch (IllegalStateException invalid) { return; }
        if (sharing != null && sharing.manual) return;
        if (!autoAttempted.add(message.id)) return;
        download(new MessageObject(account, message, true, true), true);
    }
    public void openPeer(String address, RequestDelegate callback) {
        worker.execute(() -> {
            try {
                org.jxmpp.jid.EntityBareJid jid = org.jxmpp.jid.impl.JidCreate.entityBareFrom(address);
                long peer = store.peer(jid.toString());
                Roster.getInstanceFor(session().connection()).createItemAndRequestSubscription(jid, jid.toString(), null);
                TLRPC.User user = store.user(peer, self);
                ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user);
                MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(account).putUser(user, false);
                    callback.run(user, null);
                });
            } catch (Exception e) {
                TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 400; error.text = "XMPP_CONTACT_FAILED";
                AndroidUtilities.runOnUIThread(() -> callback.run(null, error));
            }
        });
    }
    public void reserveFile(MonogramFileRequest request) {
        synchronized(fileNames) {
            fileJobs.put(request.localId, request.job);
            if(request.message.attachPath!=null && !request.message.attachPath.isEmpty()) fileNames.put(request.message.attachPath,request.job);
            long size=MonogramFileRequest.prepareProgress(request.message,request.file);
            if(size>0) progress(request.message.attachPath,0,size,true,false);
        }
        MonogramMediaDrafts.accepted(account,request.message);
    }
    public File attachmentPath(int id) { return store.attachmentPath(id); }
    public boolean isFileLoading(String name) { return name != null && fileNames.containsKey(name); }
    public void cancelFile(int message) {
        for(PendingSend pending:pendingSends.values()) if(pending.request instanceof MonogramFileRequest && ((MonogramFileRequest)pending.request).localId==message) cancelRequest(pending.token);
        FileTransfer.Job job = fileJobs.remove(message);
        if (job != null) {
            job.cancel();
            for (java.util.Map.Entry<String, FileTransfer.Job> entry : fileNames.entrySet())
                if (entry.getValue() == job) finishUpload(message,entry.getKey(),job);
        }
    }
    public void cancelFile(String name) {
        FileTransfer.Job job = name == null ? null : fileNames.get(name);
        if (job != null) {
            job.cancel();
            for(PendingSend pending:pendingSends.values())
                if(pending.request instanceof MonogramFileRequest && ((MonogramFileRequest)pending.request).job==job) cancelRequest(pending.token);
            for(java.util.Map.Entry<Integer,FileTransfer.Job> entry:fileJobs.entrySet())
                if(entry.getValue()==job) finishUpload(entry.getKey(),name,job);
            finishUpload(0,name,job);
        }
    }
    private void cancelFileTransfers() {
        synchronized(fileNames) {
            for(FileTransfer.Job job:fileJobs.values()) job.cancel();
            for(java.util.Map.Entry<String,FileTransfer.Job> entry:fileNames.entrySet()) {
                entry.getValue().cancel();
                finishUpload(0,entry.getKey(),entry.getValue());
            }
            fileJobs.clear();
        }
    }
    private final java.util.concurrent.ConcurrentHashMap<String,Long> progressUpdates=new java.util.concurrent.ConcurrentHashMap<>();
    private void progress(String name,long bytes,long total,boolean upload,boolean encrypted) {
        long maximum=total<=0 ? Math.max(bytes,1) : total;
        org.telegram.messenger.ImageLoader.getInstance().setFileProgress(name,bytes,maximum);
        long now=android.os.SystemClock.elapsedRealtime(); Long previous=progressUpdates.putIfAbsent(name,now);
        if(previous!=null && now-previous<100 && bytes!=0 && bytes<maximum) return;
        progressUpdates.put(name,now);
        // This native flag selects Telegram secret chats, not XMPP payload encryption.
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(
            upload ? NotificationCenter.fileUploadProgressChanged : NotificationCenter.fileLoadProgressChanged,name,bytes,maximum,false));
    }
    private void clearProgress(String name) {
        progressUpdates.remove(name); org.telegram.messenger.ImageLoader.getInstance().setFileProgress(name,-1,0);
    }
    private static String omemoSendError(com.negzero.monogram.xmpp.OmemoSendException error) {
        return "MONOGRAM_OMEMO_"+error.reason.name()+(error.detail==null ? "" : "|"+error.detail);
    }
    private TLRPC.TL_error fileError(Exception e) {
        TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 400;
        if (e instanceof ReplyUnavailable) error.text = "MONOGRAM_REPLY_UNAVAILABLE";
        else if (e instanceof FileTransfer.Failure) error.text = "MONOGRAM_FILE_" + ((FileTransfer.Failure) e).reason;
        else if (e instanceof org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException) error.text = "MONOGRAM_OMEMO_TRUST_REQUIRED";
        else if (e instanceof com.negzero.monogram.xmpp.OmemoSendException) error.text = omemoSendError((com.negzero.monogram.xmpp.OmemoSendException)e);
        else if (e instanceof UnsupportedOperationException) error.text = "MONOGRAM_FEATURE_UNAVAILABLE";
        else if (session == null || !session.connection().isAuthenticated()) error.text = "MONOGRAM_XMPP_DISCONNECTED";
        else error.text = "MONOGRAM_FILE_TRANSFER_FAILED|"+com.negzero.monogram.xmpp.XmppRequestException.describe(e);
        return error;
    }
    private void finishUpload(int id,String path,FileTransfer.Job job) {
        fileJobs.remove(id,job);
        // A cancelled worker may finish after a retry has taken ownership of this path.
        synchronized(fileNames) {
            if(path!=null && fileNames.remove(path,job)) {
                clearProgress(path);
                AndroidUtilities.runOnUIThread(() -> {
                    if(!fileNames.containsKey(path)) org.telegram.messenger.VideoEncodingService.uploadFinished(account,path);
                });
            }
        }
    }
    public void retryForward(TLRPC.Message pending) {
        TLRPC.Message source=null;
        try {
            if(pending.params!=null && pending.params.containsKey("fwd_id")) {
                TLRPC.Message saved=store.getMessage(Integer.parseInt(pending.params.get("fwd_id")),self);
                if(Long.toString(saved.dialog_id).equals(pending.params.get("fwd_peer"))) source=saved;
            }
        } catch(RuntimeException unavailable) { /* A cached local file can still be sent if the original was removed. */ }
        MonogramForwarding.retry(pending,source);
    }
    public File forwardSource(java.util.Map<String,String> params) {
        try {
            int id=Integer.parseInt(params.get(MonogramForwarding.SOURCE));
            TLRPC.Message source=store.getMessage(id,self);
            if(!MonogramAttachments.has(source) || !source.params.get(MonogramAttachments.PARAM).equals(params.get(MonogramForwarding.ADDRESS))) return null;
            return store.attachmentPath(id);
        } catch(RuntimeException invalid) { return null; }
    }
    private void prepareForwardFile(MonogramFileRequest request,XmppSession expected) throws Exception {
        if(request.message.params==null || !request.message.params.containsKey(MonogramForwarding.SOURCE)) return;
        long owner=accountEpoch;
        if(session!=expected || request.file==null || !request.file.equals(forwardSource(request.message.params)))
            throw new java.io.IOException("The forwarded attachment is no longer available");
        int id=Integer.parseInt(request.message.params.get(MonogramForwarding.SOURCE));
        String address=request.message.params.get(MonogramForwarding.ADDRESS);
        FileLink link=FileLink.parse(address);
        com.negzero.monogram.xmpp.FileShare sharing=store.sharing(id);
        if(sharing!=null && !address.equals(sharing.link.wireUrl)) throw new java.io.IOException("Attachment reference changed");
        boolean cached=store.hasCachedAttachment(address,request.file);
        if(cached && sharing!=null) {
            try { sharing.verify(request.file,request.job); }
            catch(java.io.IOException invalid) { request.job.check();cached=false; }
        }
        if(!cached) {
            // Source downloads are preparation, not bytes uploaded to the destination.
            boolean downloaded=sharing==null ? FileTransfer.download(link,request.file,request.job,null,null)
                    : FileTransfer.download(sharing,request.file,request.job,null,null);
            if(!downloaded) throw new java.io.IOException("Cannot download the forwarded attachment");
        }
        request.job.check();
        if(session!=expected || owner!=accountEpoch) throw new IllegalStateException("Account connection changed");
        TLRPC.Message source=store.getMessage(id,self);
        if(!store.attachDownloaded(id,address,sharing,request.file,source.media))
            throw new java.io.IOException("The forwarded attachment description changed. Please retry.");
    }
    private void upload(MonogramFileRequest request, RequestDelegate callback) {
        final XmppSession expected;
        try {
            request.job.check();
            expected = session();
            String queuedMode=request.message.params==null ? null : request.message.params.get(QUEUED_ENCRYPTION);
            if(queuedMode!=null && !queuedMode.equals(encryption(request.message.dialog_id).name())) {
                finishUpload(request.localId,request.message.attachPath,request.job);
                TLRPC.TL_error error=new TLRPC.TL_error(); error.code=400; error.text="MONOGRAM_SENDING_MODE_CHANGED";
                Utilities.stageQueue.postRunnable(() -> callback.run(null,error)); return;
            }
            if (!request.supported || request.message.dialog_id == 0 || store.jid(request.message.dialog_id).contains("/"))
                throw new UnsupportedOperationException();
            String gifSource=request.message.params==null ? null : request.message.params.get("monogram-gif-source");
            if (gifSource!=null && (request.file==null || !request.file.isFile() || request.file.length()==0)) {
                if (!MonogramGiphy.sourceFile(gifSource).equals(request.file)) throw new IllegalArgumentException("GIF source changed");
                final long owner=accountEpoch;
                AndroidUtilities.runOnUIThread(() -> {
                    if(owner!=accountEpoch || request.job.cancelled()) return;
                    MonogramGiphy.download(account,gifSource,success -> {
                        if(owner!=accountEpoch || request.job.cancelled()) return;
                        if(success) sendWorker.execute(() -> { if(owner==accountEpoch && !request.job.cancelled()) upload(request,callback); });
                        else {
                            finishUpload(request.localId,request.message.attachPath,request.job);
                            TLRPC.TL_error error=fileError(new java.io.IOException("GIF source download failed"));
                            Utilities.stageQueue.postRunnable(() -> { if(owner==accountEpoch && !request.job.cancelled()) callback.run(null,error); });
                        }
                    });
                });
                return;
            }
            boolean forwarded=request.message.params!=null && request.message.params.containsKey(MonogramForwarding.SOURCE);
            if (request.file == null || !forwarded && !request.file.isFile() && !MonogramAudioSources.isAudioSource(request.message.attachPath)) throw new java.io.FileNotFoundException();
        } catch (Exception e) {
            finishUpload(request.localId,request.message.attachPath,request.job);
            TLRPC.TL_error error = fileError(e);
            if (!request.job.cancelled()) Utilities.stageQueue.postRunnable(() -> callback.run(null, error));
            return;
        }
        XmppMessage.Encryption mode = encryption(request.message.dialog_id);
        boolean encrypted = mode != XmppMessage.Encryption.PLAIN, modern = mode == XmppMessage.Encryption.OMEMO2;
        final boolean displayStickerHint = encrypted && !modern && request.message.media != null && MonogramStickers.isSticker(request.message.media.document);
        String peer = store.jid(request.message.dialog_id);
        final ReplyReference reply;
        final MessageMarkup captionMarkup;
        final ArrayList<TLRPC.MessageEntity> captionEntities = new ArrayList<>(request.message.entities);
        try {
            MessageMarkup contentMarkup=MonogramMentions.fromEntities(store,request.message.dialog_id,request.caption == null ? "" : request.caption,captionEntities).styled().withSpoiler(request.message.media!=null && request.message.media.spoiler);
            com.negzero.monogram.xmpp.ForwardedContent author=MonogramForwarding.attribution(request.message.params,contentMarkup.body);
            captionMarkup=author==null ? contentMarkup : contentMarkup.inBody(author.wireBody(),author.prefix().codePointCount(0,author.prefix().length()));
            TLRPC.MessageReplyHeader header = request.message.reply_to;
            if (header != null && (header.reply_to_peer_id != null && (request.message.dialog_id<0 ? -header.reply_to_peer_id.chat_id : header.reply_to_peer_id.user_id) != request.message.dialog_id || header.reply_to_top_id != 0)) throw new UnsupportedOperationException();
            reply = header == null ? null : replyTo(header.reply_to_msg_id, request.message.dialog_id, encrypted, header.quote_text, header.quote_offset);
        } catch (Exception failed) {
            finishUpload(request.localId,request.message.attachPath,request.job);
            Utilities.stageQueue.postRunnable(() -> callback.run(null, fileError(failed))); return;
        }
        FileTransfer.Job job = request.job;
        synchronized(fileNames) {
            if(job.cancelled()) return;
            fileJobs.put(request.localId, job); fileNames.put(request.message.attachPath, job);
        }
        String progressName = request.message.attachPath;
        FileTransfer.Progress uploadProgress=(bytes,total) -> {
            synchronized(fileNames) {
                if(!job.cancelled() && fileNames.get(progressName)==job) progress(progressName,bytes,total,true,encrypted);
            }
        };
        fileWorker.execute(() -> {
            File snapshot = null;
            try {
                prepareForwardFile(request,expected);
                job.check();
                final String uploadName=request.uploadName();
                if(MonogramAudioSources.isAudioSource(progressName)) {
                    long declared=request.message.media.document==null ? 0 : request.message.media.document.size;
                    if(declared>0) FileTransfer.checkUploadSize(expected.connection(),declared,encrypted,job);
                    snapshot=MonogramAudioSources.materialize(account,android.net.Uri.parse(progressName),uploadName,
                            declared>0 ? Math.min(declared,FileTransfer.MAX_BYTES) : FileTransfer.MAX_BYTES,job::cancelled);
                    FileTransfer.checkUploadSize(expected.connection(),snapshot.length(),encrypted,job);
                } else {
                    FileTransfer.checkUploadSize(expected.connection(),request.file.length(),encrypted,job);
                    snapshot = FileTransfer.snapshot(request.file, ApplicationLoader.applicationContext.getCacheDir(), job);
                }
                uploadProgress.update(0,snapshot.length());
                FileLink link = FileTransfer.upload(expected.connection(), snapshot, modern ? java.util.UUID.randomUUID()+".bin" : uploadName,
                        modern ? "application/octet-stream" : request.mime, encrypted, job,
                        uploadProgress);
                com.negzero.monogram.xmpp.FileShare sharing = encrypted && !modern ? null : MonogramStickers.description(request.message, com.negzero.monogram.xmpp.FileShare.describe(link, snapshot, uploadName, request.mime, job));
                File cached = sharing == null ? store.attachmentFile(link.wireUrl) : store.attachmentFile(sharing);
                if (!cached.getParentFile().isDirectory() && !cached.getParentFile().mkdirs()) throw new java.io.IOException("Cannot create attachment cache");
                job.check();
                if (!snapshot.renameTo(cached)) throw new java.io.IOException("Cannot commit attachment cache");
                sendWorker.execute(() -> {
                    try {
                        job.check();
                        if (session != expected || encryption(request.message.dialog_id) != mode) throw new IllegalStateException("Sending mode or account changed");
                        int id; long timestamp; String caption;
                        com.negzero.monogram.xmpp.ForwardedContent forwardedFile=MonogramForwarding.attribution(request.message.params,link.wireUrl);
                        String protectedFileBody=forwardedFile==null ? link.wireUrl : forwardedFile.wireBody();
                        if(request.message.dialog_id<0) {
                            com.negzero.monogram.xmpp.XmppRoomMessage message=expected.rooms.sendFile(peer,link,sharing,captionMarkup.body,encrypted,reply,excludedRoomRecipients(request.message.dialog_id),automaticRoomRecipients(request.message.dialog_id),sharing == null ? MessageMarkup.body(link.wireUrl).withSpoiler(captionMarkup.spoiler) : captionMarkup,displayStickerHint,forwardedFile);
                            id=store.saveRoom(message,self); timestamp=message.timestamp; caption=message.caption;
                            if(message.encrypted) reportSkippedRecipients(request.message.dialog_id,message);
                        } else {
                            String fingerprint=expected.senderFingerprint(mode);
                            String stanza=encrypted && !modern ? expected.sendFile(peer,link,true,reply,displayStickerHint,captionMarkup.spoiler,forwardedFile) : expected.sendFile(peer,sharing,captionMarkup.body,reply,captionMarkup);
                            XmppMessage message=encrypted && !modern ? XmppMessage.sentFile(expected.connection().getUser(),peer,stanza,link,true,reply).withForwarding(forwardedFile).withMarkup(MessageMarkup.body(protectedFileBody).withSpoiler(captionMarkup.spoiler))
                                    : XmppMessage.sentFile(expected.connection().getUser(),peer,stanza,sharing,captionMarkup.body,reply).withMarkup(captionMarkup);
                            message=message.withSenderFingerprint(fingerprint).withDisplayStickerHint(displayStickerHint);
                            id=store.save(message); timestamp=message.timestamp; caption=message.caption;
                        }
                        TLRPC.MessageMedia media = request.message.media.photo == null ? request.message.media
                                : MonogramAttachments.media(id, (int) (timestamp / 1000), cached, sharing == null ? link.fileName() : sharing.name);
                        media.spoiler=captionMarkup.spoiler;
                        store.attach(id, link.wireUrl, cached, media);
                        refreshReplies(request.message.dialog_id);
                        MonogramAttachments.apply(request.message, link, cached, media, sharing == null ? caption : request.caption, sharing == null ? link.fileName() : sharing.name);
                        request.message.entities.clear();
                        if (sharing != null) request.message.entities.addAll(captionEntities);
                        if (request.message.entities.isEmpty()) request.message.flags &= ~TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
                        else request.message.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
                        TLRPC.TL_updateShortSentMessage response = new TLRPC.TL_updateShortSentMessage();
                        response.id = id; response.date = (int) (timestamp / 1000); response.out = true; response.pts_count = 1;
                        TLRPC.Message projected = store.getMessage(id, self);
                        response.message = projected.message; response.entities = projected.entities;
                        response.pts = MessagesStorage.getInstance(account).getLastPtsValue() + 1;
                        Utilities.stageQueue.postRunnable(() -> {
                            callback.run(response, null);
                            // Legacy OMEMO cannot carry SFS content; keep its separate encrypted caption until OMEMO2 is integrated.
                            if (encrypted && !modern && request.caption != null && !request.caption.isEmpty()) AndroidUtilities.runOnUIThread(() ->
                                    SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                                            request.caption, request.message.dialog_id, null, null, null, false, captionEntities, null, null, true, 0, 0, null, false)));
                        });
                    } catch (Exception e) {
                        TLRPC.TL_error error = fileError(e);
                        if (!job.cancelled()) Utilities.stageQueue.postRunnable(() -> callback.run(null, error));
                    } finally { finishUpload(request.localId,progressName,job); }
                });
            } catch (Exception e) {
                TLRPC.TL_error error = fileError(e);
                if (!job.cancelled()) Utilities.stageQueue.postRunnable(() -> callback.run(null, error));
                finishUpload(request.localId,progressName,job);
            } finally { if (snapshot != null && snapshot.exists() && !snapshot.delete()) snapshot.deleteOnExit(); }
        });
    }
    public void download(MessageObject object) {
        download(object, false);
    }
    private void finishDownload(int id, String name, FileTransfer.Job job) {
        synchronized(fileNames) {
            fileJobs.remove(id, job);
            for(String alias:downloadNames(name,job)) {
                fileNames.remove(alias,job);clearProgress(alias);
            }
        }
        if(automaticJobs.remove(id,job)) {
            File path=store.attachmentPath(id);
            if(path==null || !path.isFile()) autoAttempted.remove(id);
        }
    }
    private java.util.Set<String> downloadNames(String name,FileTransfer.Job job) {
        java.util.Set<String> names=new java.util.HashSet<>();names.add(name);
        for(java.util.Map.Entry<String,FileTransfer.Job> entry:fileNames.entrySet())
            if(entry.getValue()==job) names.add(entry.getKey());
        return names;
    }
    private void download(MessageObject object, boolean automatic) {
        if (!MonogramAttachments.has(object.messageOwner)) return;
        int id = object.getId();
        FileTransfer.Job job = new FileTransfer.Job();
        if (fileJobs.putIfAbsent(id, job) != null) return;
        if (automatic) automaticJobs.put(id, job);
        String name = object.getFileName(); fileNames.put(name, job);
        String address = object.messageOwner.params.get(MonogramAttachments.PARAM);
        long epoch = sessionEpoch;
        long owner = accountEpoch;
        (automatic ? autoDownloadWorker : fileWorker).execute(() -> {
            try {
                job.check();
                FileLink link = FileLink.parse(address);
                com.negzero.monogram.xmpp.FileShare sharing = store.sharing(id);
                if (sharing != null && !sharing.link.wireUrl.equals(address)) throw new IllegalStateException("Attachment reference changed");
                File path = sharing == null ? store.attachmentFile(address) : store.attachmentFile(sharing);
                String filename = sharing == null ? link.fileName() : sharing.name;
                FileTransfer.Progress onProgress = (bytes, total) -> {
                    synchronized(fileNames) {
                        if(job.cancelled() || fileJobs.get(id)!=job) return;
                        for(String alias:downloadNames(name,job)) progress(alias,bytes,total,false,link.encrypted());
                    }
                };
                final String[] responseMime={null};
                FileTransfer.Metadata metadata = (mime,size) -> {
                    responseMime[0]=mime;
                    // Protected file-sharing metadata already provides a better placeholder.
                    if(sharing!=null) return;
                    worker.execute(() -> {
                        try {
                            if(job.cancelled() || sessionEpoch!=epoch || owner!=accountEpoch || fileJobs.get(id)!=null && fileJobs.get(id)!=job) return;
                            TLRPC.Message saved=store.getMessage(id,self);
                            TLRPC.MessageMedia remote=MonogramStickers.preserve(saved.media,MonogramAttachments.remoteMedia(id,filename,mime,size));
                            MonogramAttachments.preserveMediaAttributes(saved.media,remote);
                            if(remote.photo!=null) remote.photo.date=saved.date;
                            if(remote.document!=null) remote.document.date=saved.date;
                            if(!store.attachRemoteMetadata(id,address,path,remote)) return;
                            TLRPC.Message updated=store.getMessage(id,self);
                            MessageObject replacement=new MessageObject(account,updated,true,true);
                            synchronized(fileNames) {
                                if(job.cancelled()) return;
                                if(fileJobs.get(id)==job) fileNames.put(replacement.getFileName(),job);
                            }
                            ArrayList<TLRPC.Message> messages=new ArrayList<>();messages.add(updated);
                            MessagesStorage.getInstance(account).putMessages(messages,false,true,false,0,0,0);
                            AndroidUtilities.runOnUIThread(() -> {
                                if(owner!=accountEpoch || sessionEpoch!=epoch || job.cancelled()) return;
                                ArrayList<MessageObject> objects=new ArrayList<>();objects.add(replacement);
                                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects,updated.dialog_id,objects);
                            });
                        } catch(Exception error) { org.telegram.messenger.FileLog.e(error); }
                    });
                };
                FileTransfer.DownloadPolicy policy = automatic ? (mime, size) -> DownloadController.getInstance(account).getMonogramDownloadLimit(
                        object.messageOwner, MonogramAttachments.remoteMedia(id, filename, sharing==null ? mime : sharing.mime, size), size) : null;
                boolean cached = store.hasCachedAttachment(address, path);
                if (cached && sharing != null) {
                    try { sharing.verify(path, job); }
                    catch (java.io.IOException invalidCache) { job.check(); cached = false; }
                }
                boolean downloaded = cached || (sharing == null ? FileTransfer.download(link, path, job, onProgress, policy, metadata) : FileTransfer.download(sharing, path, job, onProgress, policy, metadata));
                if (!downloaded) {
                    java.util.Set<String> names=downloadNames(name,job);
                    finishDownload(id, name, job);
                    AndroidUtilities.runOnUIThread(() -> {
                        if(owner==accountEpoch) for(String alias:names) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoadFailed, alias, 0);
                    });
                    return;
                }
                job.check();
                TLRPC.MessageMedia decoded=MonogramStickers.preserve(object.messageOwner.media,MonogramAttachments.decodedMedia(id,object.messageOwner.date,path,filename,
                        sharing!=null ? sharing.mime : responseMime[0]!=null ? responseMime[0] : object.messageOwner.media!=null && object.messageOwner.media.document!=null ? object.messageOwner.media.document.mime_type : null));
                MonogramStickers.inspectDisplayHint(decoded,path,store.displayStickerHint(id,address));
                MonogramAttachments.preserveMediaAttributes(object.messageOwner.media,decoded);
                job.check();
                worker.execute(() -> {
                    boolean sourceChanged=false;
                    try {
                        job.check();
                        if (sessionEpoch != epoch || owner!=accountEpoch || store.hiddenMessage(id)) return;
                        TLRPC.Message saved = store.getMessage(id, self);
                        if(decoded.document!=null) decoded.document.date=saved.date;
                        if(decoded.photo!=null) decoded.photo.date=saved.date;
                        if(!store.attachDownloaded(id,address,sharing,path,decoded)) { sourceChanged=true;return; }
                        mediaInfoScanned.remove(id);
                        TLRPC.Message updated = store.getMessage(id, self);
                        MonogramAttachments.apply(object.messageOwner, link, path, updated.media, updated.message, filename);
                        ArrayList<TLRPC.Message> messages = new ArrayList<>(); messages.add(updated);
                        MessagesStorage.getInstance(account).putMessages(messages, false, true, false, 0, 0, 0);
                        java.util.Set<String> completedNames=downloadNames(name,job);
                        AndroidUtilities.runOnUIThread(() -> {
                            if(owner!=accountEpoch || sessionEpoch!=epoch) return;
                            ArrayList<MessageObject> objects = new ArrayList<>(); objects.add(new MessageObject(account, updated, true, true));
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, updated.dialog_id, objects);
                            for(String alias:completedNames) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoaded, alias, path);
                        });
                    } catch (Exception e) { downloadError(name, object, job, e, automatic,owner); }
                    finally {
                        finishDownload(id, name, job);
                        if(sourceChanged && automatic && owner==accountEpoch && sessionEpoch==epoch && !store.hiddenMessage(id))
                            maybeAutoDownload(store.getMessage(id,self));
                    }
                });
            } catch (Exception e) {
                downloadError(name, object, job, e, automatic,owner); finishDownload(id, name, job);
            }
        });
    }
    private void downloadError(String name, MessageObject object, FileTransfer.Job job, Exception error, boolean automatic,long owner) {
        java.util.Set<String> names=downloadNames(name,job);
        AndroidUtilities.runOnUIThread(() -> {
            if(owner!=accountEpoch) return;
            for(String alias:names) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoadFailed, alias, 0);
            if (!automatic && !job.cancelled()) org.telegram.ui.Components.AlertsCreator.processError(account, fileError(error), null,
                    new MonogramFileRequest(object.messageOwner, null, "application/octet-stream", ""));
        });
    }
    public static long sendingPeer(TLObject request) {
        if(request instanceof MonogramFileRequest) return ((MonogramFileRequest)request).message.dialog_id;
        TLRPC.InputPeer peer=request instanceof TLRPC.TL_messages_sendMessage ? ((TLRPC.TL_messages_sendMessage)request).peer
            : request instanceof TLRPC.TL_messages_sendMedia ? ((TLRPC.TL_messages_sendMedia)request).peer
            : request instanceof TLRPC.TL_messages_editMessage ? ((TLRPC.TL_messages_editMessage)request).peer : null;
        return peer==null ? 0 : peer.chat_id!=0 ? -peer.chat_id : peer.channel_id!=0 ? -peer.channel_id : peer.user_id;
    }
    private String sendErrorKey(long peer) { return "last-send-error-"+accountJid()+"\n"+peer; }
    private MonogramStore.EditTarget editTarget(int id,long peer) {
        MonogramStore.EditTarget target=store.editTarget(id,peer,self);
        if(target.sharing!=null && target.encryption==XmppMessage.Encryption.PLAIN && encryption(peer)!=XmppMessage.Encryption.PLAIN)
            throw new MonogramStore.EditUnavailable("MONOGRAM_EDIT_CAPTION_ENCRYPTION_REQUIRED");
        return target;
    }
    public String lastSendError(long peer) { return preferences.getString(sendErrorKey(peer),null); }
    public void request(TLObject request,RequestDelegate callback) { request(0,request,callback); }
    public void request(int token,TLObject request,RequestDelegate callback) {
        try { MonogramSelfChat.resolve(request,self); }
        catch(IllegalStateException signedOut) {
            TLRPC.TL_error error=new TLRPC.TL_error();error.code=401;error.text="MONOGRAM_ACCOUNT_REQUIRED";
            if(callback!=null) Utilities.stageQueue.postRunnable(() -> callback.run(null,error));
            return;
        }
        if(request instanceof TLRPC.TL_messages_translateText) {
            translate(token,(TLRPC.TL_messages_translateText)request,callback);return;
        }
        final long peer=sendingPeer(request),owner=accountEpoch;
        RequestDelegate completed=(response,error) -> {
            if(peer!=0 && owner==accountEpoch) {
                SharedPreferences.Editor edit=preferences.edit();
                String kind=request instanceof MonogramFileRequest || request instanceof TLRPC.TL_messages_sendMedia ? "file" : "text";
                if(error==null) edit.remove(sendErrorKey(peer)+"-"+kind);
                else edit.putString(sendErrorKey(peer)+"-"+kind,error.text);
                if(error==null) edit.remove(sendErrorKey(peer)); else edit.putString(sendErrorKey(peer),error.text);
                edit.apply();
            }
            if(callback!=null) callback.run(response,error);
        };
        if(request instanceof org.telegram.tgnet.tl.TL_stories.TL_stories_sendStory
                || request instanceof org.telegram.tgnet.tl.TL_stories.TL_stories_deleteStories
                || request instanceof org.telegram.tgnet.tl.TL_stories.TL_stories_editStory) {
            stories().request(token,request,completed);return;
        }
        if(MonogramPolls.supports(request)) {
            try {
                TLRPC.TL_messages_sendMessage converted=MonogramPolls.text((TLRPC.TL_messages_sendMedia)request);
                Integer local=queuedLocalIds.remove(request);String mode=queuedEncryption.remove(request);
                if(local!=null) queuedLocalIds.put(converted,local);if(mode!=null) queuedEncryption.put(converted,mode);
                RequestDelegate pollCompleted=(response,error) -> {
                    if(response instanceof TLRPC.TL_updateShortSentMessage && error==null) {
                        TLRPC.TL_updateShortSentMessage sent=(TLRPC.TL_updateShortSentMessage)response;
                        TLRPC.Message projected=store.getMessage(sent.id,self);sent.media=projected.media;sent.message=projected.message;sent.flags|=512;
                    }
                    completed.run(response,error);
                };
                if(converted.schedule_date!=0) scheduleOutgoing(converted,pollCompleted);else queueSend(token,converted,pollCompleted);
            } catch(MonogramPolls.Invalid invalid) {
                queuedLocalIds.remove(request);queuedEncryption.remove(request);
                TLRPC.TL_error error=new TLRPC.TL_error();error.code=400;error.text="MONOGRAM_XMPP_FAILED|"+invalid.getMessage();
                Utilities.stageQueue.postRunnable(() -> completed.run(null,error));
            }
            return;
        }
        if(MonogramLocation.supports(request)) {
            try {
                TLRPC.TL_messages_sendMessage converted=MonogramLocation.text((TLRPC.TL_messages_sendMedia)request);
                Integer local=queuedLocalIds.remove(request);String mode=queuedEncryption.remove(request);
                if(local!=null) queuedLocalIds.put(converted,local);if(mode!=null) queuedEncryption.put(converted,mode);
                if(converted.schedule_date!=0) scheduleOutgoing(converted,completed);
                else queueSend(token,converted,completed);
            } catch(Exception invalid) { queuedLocalIds.remove(request);queuedEncryption.remove(request);TLRPC.TL_error error=new TLRPC.TL_error();error.code=400;error.text="MONOGRAM_INVALID_LOCATION";Utilities.stageQueue.postRunnable(() -> completed.run(null,error)); }
        } else if(request instanceof TLRPC.TL_messages_sendMessage && ((TLRPC.TL_messages_sendMessage)request).schedule_date!=0)
            scheduleOutgoing((TLRPC.TL_messages_sendMessage)request,completed);
        else if(request instanceof TLRPC.TL_messages_sendMessage || request instanceof MonogramFileRequest) queueSend(token,request,completed);
        else dispatchRequest(request,completed);
    }
    private static final String QUEUED_ENCRYPTION="monogram-encryption";
    private final java.util.concurrent.ConcurrentHashMap<TLObject,Integer> queuedLocalIds=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<TLObject,String> queuedEncryption=new java.util.concurrent.ConcurrentHashMap<>();
    public void prepareOutgoingMessage(TLRPC.Message message) {
        if(message.params==null) message.params=new java.util.HashMap<>();
        if (message.dialog_id>0 && message.dialog_id!=self) MonogramReceipts.pending(message);
        message.params.putIfAbsent(QUEUED_ENCRYPTION,encryption(message.dialog_id).name());
    }
    public void prepareOutgoingRequest(TLObject request,TLRPC.Message message) {
        MonogramForwarding.prepare(request,message);
        if(!(request instanceof TLRPC.TL_messages_sendMessage) && !(request instanceof MonogramFileRequest) && !MonogramLocation.supports(request) && !MonogramPolls.supports(request)) return;
        MonogramSelfChat.resolve(request,self);
        prepareOutgoingMessage(message); queuedEncryption.put(request,message.params.get(QUEUED_ENCRYPTION)); queuedLocalIds.put(request,message.id);
    }
    /** Sends a detached local quick-reply template through the regular Monogram outbox. */
    public void sendQuickReply(long dialogId,MonogramQuickReplies.Outgoing outgoing) {
        if(dialogId==0 || outgoing==null) throw new IllegalArgumentException("Quick reply and conversation are required");
        SendMessagesHelper.getInstance(account).sendMonogramQuickReply(dialogId,outgoing);
    }
    private void scheduleOutgoing(TLRPC.TL_messages_sendMessage request,RequestDelegate completed) {
        Integer local=queuedLocalIds.remove(request); String selected=queuedEncryption.remove(request);
        try {
            long peer=sendingPeer(request);
            if(local==null || local>=0 || peer==0) throw new IllegalArgumentException("Scheduled message was not added to the local outbox");
            if(request.rich_message!=null) throw new UnsupportedOperationException("Scheduled articles are not available yet");
            if(request.schedule_date==0x7ffffffe) throw new UnsupportedOperationException("Send when online cannot be represented by an XMPP schedule");
            String owner=accountJid(); if(owner.isEmpty()) throw new IllegalStateException("Sign in before scheduling a message");
            if(selected==null) selected=encryption(peer).name();
            String destination=store.jid(peer);
            int scheduledId=MonogramScheduledMessages.allocateId(account,owner,accountEpoch);
            if(scheduledId<=0) throw new IllegalStateException("Could not allocate a scheduled-message id");
            MonogramScheduledMessages.Entry entry=MonogramScheduledMessages.fromRequest(scheduledId,local,request.schedule_date*1000L,
                    request.schedule_repeat_period,destination,request,selected,!automaticEncryption(peer),"");
            if(!MonogramScheduledMessages.put(account,owner,accountEpoch,entry)) throw new IllegalStateException("Could not save scheduled message");
            armScheduledWake();
            Updates updates=new Updates(); updates.date=now();
            TL_update.TL_updateMessageID mapped=new TL_update.TL_updateMessageID(); mapped.id=entry.id; mapped.random_id=request.random_id; updates.updates.add(mapped);
            TL_update.TL_updateNewScheduledMessage update=new TL_update.TL_updateNewScheduledMessage(); update.message=MonogramScheduledMessages.project(entry,self); updates.updates.add(update);
            Utilities.stageQueue.postRunnable(() -> completed.run(updates,null));
        } catch(Exception failed) {
            TLRPC.TL_error error=new TLRPC.TL_error(); error.code=400;
            error.text=failed instanceof UnsupportedOperationException ? "MONOGRAM_SCHEDULED_UNAVAILABLE: "+failed.getMessage() : "MONOGRAM_SCHEDULED_FAILED";
            Utilities.stageQueue.postRunnable(() -> completed.run(null,error));
        }
    }
    private void armScheduledWake() {
        AndroidUtilities.cancelRunOnUIThread(scheduledWake);
        final String owner=accountJid(); final long epoch=accountEpoch;
        XmppSession expected=session;
        if(owner.isEmpty() || expected==null || !expected.connection().isAuthenticated()) return;
        long now=System.currentTimeMillis();
        long wakeAt=MonogramScheduledMessages.nextWakeAt(account,owner,epoch,now);
        if(wakeAt<=0 || wakeAt==Long.MAX_VALUE) return;
        long delay=Math.max(1000L,wakeAt-now);
        AndroidUtilities.runOnUIThread(scheduledWake,delay);
    }
    private void drainScheduledMessages() {
        if(!scheduledDrainQueued.compareAndSet(false,true)) return;
        worker.execute(() -> {
            try {
                final String owner=accountJid(); final long epoch=accountEpoch;
                XmppSession expected=session;
                if(owner.isEmpty() || expected==null || !expected.connection().isAuthenticated()) return;
                for(MonogramScheduledMessages.Claim claim:MonogramScheduledMessages.claimDue(account,owner,epoch,System.currentTimeMillis(),32))
                    dispatchScheduledClaim(claim,owner,epoch);
            } finally {
                scheduledDrainQueued.set(false); armScheduledWake();
            }
        });
    }
    private void dispatchScheduledClaim(MonogramScheduledMessages.Claim claim,String owner,long epoch) {
        try {
            MonogramScheduledMessages.Entry entry=claim.entry;
            if(epoch!=accountEpoch || !owner.equals(accountJid())) { MonogramScheduledMessages.releaseGeneration(account,owner,epoch);return; }
            if(!entry.destination.equals(store.jid(entry.peer))) throw new IllegalStateException("The recipient changed");
            TLRPC.TL_messages_sendMessage request=entry.request(MessagesController.getInstance(account).getInputPeer(entry.peer));
            // The scheduled projection has a native positive id.  A fired XMPP request is a new
            // ordinary outbox item, never that scheduled id, or native storage can conflate both.
            queuedEncryption.put(request,entry.manualEncryption ? encryption(entry.peer).name() : entry.encryption);
            queueSend(0,request,(response,error) -> completeScheduledClaim(claim,response,error,owner,epoch));
        } catch(Exception failed) {
            MonogramScheduledMessages.fail(claim,System.currentTimeMillis()); armScheduledWake();
        }
    }
    private void completeScheduledClaim(MonogramScheduledMessages.Claim claim,TLObject response,TLRPC.TL_error error,String owner,long epoch) {
        if(epoch!=accountEpoch || !owner.equals(accountJid())) { MonogramScheduledMessages.releaseGeneration(account,owner,epoch);return; }
        if(error!=null || !(response instanceof TLRPC.TL_updateShortSentMessage)) {
            MonogramScheduledMessages.fail(claim,System.currentTimeMillis()); armScheduledWake(); return;
        }
        MonogramScheduledMessages.Completion completion=MonogramScheduledMessages.complete(claim,System.currentTimeMillis());
        if(!completion.committed) { armScheduledWake(); return; }
        MonogramScheduledMessages.Entry next=completion.next;
        int sentId=((TLRPC.TL_updateShortSentMessage)response).id;
        try {
            TLRPC.Message sent=store.getMessage(sentId,self);
            ArrayList<TLRPC.Message> sentRows=new ArrayList<>(); sentRows.add(sent);
            MessagesStorage.getInstance(account).putMessages(sentRows,false,true,false,0,0,0);
            ArrayList<Integer> scheduledIds=new ArrayList<>(); scheduledIds.add(claim.entry.id);
            // Replace a repeating projection rather than appending another row with the same
            // native scheduled id. The ordinary sent message above stays in the normal history.
            MessagesController.getInstance(account).deleteMessages(scheduledIds,null,null,claim.entry.peer,false,org.telegram.ui.ChatActivity.MODE_SCHEDULED,true,0,null,0);
            if(next!=null) {
                Updates repeatUpdate=new Updates(); repeatUpdate.date=now();
                TL_update.TL_updateNewScheduledMessage update=new TL_update.TL_updateNewScheduledMessage(); update.message=MonogramScheduledMessages.project(next,self); repeatUpdate.updates.add(update);
                Utilities.stageQueue.postRunnable(() -> {
                    if(epoch==accountEpoch && owner.equals(accountJid())) MessagesController.getInstance(account).processUpdates(repeatUpdate,false);
                });
            }
            AndroidUtilities.runOnUIThread(() -> {
                if(epoch!=accountEpoch || !owner.equals(accountJid())) return;
                ArrayList<MessageObject> objects=new ArrayList<>(); objects.add(new MessageObject(account,sent,true,true));
                MessagesController.getInstance(account).updateInterfaceWithMessages(claim.entry.peer,objects,0);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.scheduledMessagesUpdated,claim.entry.peer,0,false);
            });
        } catch(Exception ignored) { /* The durable queue has completed; normal XMPP history will reconcile this row. */ }
        armScheduledWake();
    }
    private static final class PendingSend {
        final long created=System.nanoTime();
        final String encryption;
        final int token,localId; final long owner; final TLObject request; final RequestDelegate completed;
        final java.util.concurrent.atomic.AtomicBoolean scheduled=new java.util.concurrent.atomic.AtomicBoolean();
        PendingSend(int token,long owner,TLObject request,RequestDelegate completed,String encryption,int localId) { this.localId=localId; this.encryption=encryption; this.token=token; this.owner=owner; this.request=request; this.completed=completed; }
    }
    private final java.util.concurrent.ConcurrentHashMap<Integer,PendingSend> pendingSends=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger localSendToken=new java.util.concurrent.atomic.AtomicInteger(-1);
    private static final class TranslationRequest {
        final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        volatile Runnable cancelNetwork;
        void cancel() { cancelled.set(true);Runnable pending=cancelNetwork;if(pending!=null) pending.run(); }
        void network(Runnable pending) { cancelNetwork=pending;if(cancelled.get() && pending!=null) pending.run(); }
    }
    private final java.util.concurrent.ConcurrentHashMap<Integer,TranslationRequest> translationRequests=new java.util.concurrent.ConcurrentHashMap<>();
    private void translate(int token,TLRPC.TL_messages_translateText request,RequestDelegate callback) {
        final int key=token==0 ? localSendToken.getAndDecrement() : token;
        final long owner=accountEpoch;
        TranslationRequest pending=new TranslationRequest();
        TranslationRequest previous=translationRequests.put(key,pending);if(previous!=null) previous.cancel();
        RequestDelegate completed=(result,error) -> Utilities.stageQueue.postRunnable(() -> {
            if(translationRequests.remove(key,pending) && !pending.cancelled.get() && owner==accountEpoch && callback!=null) callback.run(result,error);
        });
        cacheWorker.execute(() -> {
            if(pending.cancelled.get() || owner!=accountEpoch) { translationRequests.remove(key,pending);return; }
            try {
                TLRPC.TL_messages_translateText input=new TLRPC.TL_messages_translateText();input.flags=2;input.to_lang=request.to_lang;
                if((request.flags&1)!=0) {
                    if((request.flags&2)!=0 || request.peer==null || request.id.isEmpty() || request.id.size()>100)
                        throw new IllegalArgumentException("Invalid translation selection");
                    long dialog=request.peer.chat_id>0 ? -request.peer.chat_id : request.peer.user_id;
                    if(dialog==0 || request.peer.channel_id!=0) throw new IllegalArgumentException("Unknown conversation");
                    for(int id:request.id) input.text.add(store.translationText(id,dialog,self));
                } else if((request.flags&2)!=0) input.text.addAll(request.text);
                else throw new IllegalArgumentException("Missing translation text");
                if(pending.cancelled.get() || owner!=accountEpoch) { translationRequests.remove(key,pending);return; }
                pending.network(MonogramTranslation.request(account,input,completed));
                if(owner!=accountEpoch) pending.cancel();
            } catch(Exception invalid) {
                TLRPC.TL_error error=new TLRPC.TL_error();error.code=400;error.text="MONOGRAM_TRANSLATION_TEXT_UNAVAILABLE";
                completed.run(null,error);
            }
        });
    }
    private void warnArticleFallback() {
        AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(org.telegram.messenger.ApplicationLoader.applicationContext,
                "This will look broken outside of Monogram",android.widget.Toast.LENGTH_LONG).show());
    }
    public int queuedSendCount() { return (int)pendingSends.values().stream().filter(send -> !send.scheduled.get()).count(); }
    public void cancelRequest(int token) {
        if(storyBridge!=null) storyBridge.cancel(token);
        TranslationRequest translation=translationRequests.remove(token);if(translation!=null) translation.cancel();
        PendingSend pending=pendingSends.remove(token);
        if(pending!=null && pending.request instanceof MonogramFileRequest) {
            MonogramFileRequest file=(MonogramFileRequest)pending.request; file.job.cancel();
            finishUpload(file.localId,file.message.attachPath,file.job);
        }
    }
    private void queueSend(int token,TLObject request,RequestDelegate completed) {
        if(token==0) token=localSendToken.getAndDecrement();
        String mode=queuedEncryption.remove(request);
        if(mode==null) mode=encryption(sendingPeer(request)).name();
        Integer local=queuedLocalIds.remove(request);
        PendingSend pending=new PendingSend(token,accountEpoch,request,completed,mode,local==null ? request instanceof MonogramFileRequest ? ((MonogramFileRequest)request).localId : 0 : local);
        pendingSends.put(token,pending); scheduleSend(pending);
    }
    private void scheduleSend(PendingSend pending) {
        if(!pending.scheduled.compareAndSet(false,true)) return;
        sendWorker.execute(() -> {
            if(pendingSends.get(pending.token)!=pending) return;
            if(pending.owner!=accountEpoch) { pendingSends.remove(pending.token,pending); return; }
            if(pending.request instanceof MonogramFileRequest && ((MonogramFileRequest)pending.request).job.cancelled()) { pendingSends.remove(pending.token,pending); return; }
            XmppSession expected=session;
            boolean ready=expected!=null && expected.connection().isAuthenticated();
            long peer=sendingPeer(pending.request);
            if(ready && peer<0) {
                boolean autojoin;
                try { autojoin=store.room(peer).autojoin; } catch(IllegalArgumentException unknown) { autojoin=false; }
                if(autojoin) try { ready=expected.rooms.state(store.jid(peer)).joined; } catch(Exception notJoined) { ready=false; }
            }
            if(!ready) {
                pending.scheduled.set(false);
                if(preferences.contains("jid")) restore();
                return;
            }
            try { selectDailyEncryption(expected,peer,pending.owner); }
            catch(Exception failed) {
                if(pending.owner!=accountEpoch) { pendingSends.remove(pending.token,pending);return; }
                if(session!=expected || !expected.connection().isAuthenticated()) {
                    pending.scheduled.set(false);restore();return;
                }
                if(pendingSends.remove(pending.token,pending)) {
                    if(pending.request instanceof MonogramFileRequest) {
                        MonogramFileRequest file=(MonogramFileRequest)pending.request;
                        finishUpload(file.localId,file.message.attachPath,file.job);
                    }
                    TLRPC.TL_error error=new TLRPC.TL_error();error.code=400;
                    error.text="MONOGRAM_XMPP_FAILED|"+com.negzero.monogram.xmpp.XmppRequestException.describe(failed);
                    Utilities.stageQueue.postRunnable(() -> { if(pending.owner==accountEpoch) pending.completed.run(null,error); });
                }
                return;
            }
            if(automaticEncryption(peer) && pending.request instanceof MonogramFileRequest) {
                MonogramFileRequest file=(MonogramFileRequest)pending.request;
                if(file.message.params==null) file.message.params=new java.util.HashMap<>();
                file.message.params.put(QUEUED_ENCRYPTION,encryption(peer).name());
            }
            if(!automaticEncryption(peer) && !pending.encryption.equals(encryption(peer).name())) {
                if(pendingSends.remove(pending.token,pending)) {
                    if(pending.request instanceof MonogramFileRequest) {
                        MonogramFileRequest file=(MonogramFileRequest)pending.request;
                        finishUpload(file.localId,file.message.attachPath,file.job);
                    }
                    TLRPC.TL_error error=new TLRPC.TL_error(); error.code=400; error.text="MONOGRAM_SENDING_MODE_CHANGED";
                    Utilities.stageQueue.postRunnable(() -> { if(pending.owner==accountEpoch) pending.completed.run(null,error); });
                }
                return;
            }
            dispatchRequest(pending.request,(response,error) -> {
                if(pendingSends.get(pending.token)!=pending || pending.owner!=accountEpoch) return;
                if(error!=null && "MONOGRAM_XMPP_DISCONNECTED".equals(error.text)) {
                    pending.scheduled.set(false);
                    if(session!=null && session.connection().isAuthenticated()) flushPendingSends();
                    else if(preferences.contains("jid")) restore(); return;
                }
                if(pendingSends.remove(pending.token,pending)) {
                    if(error==null && response instanceof TLRPC.TL_updateShortSentMessage) {
                        int id=((TLRPC.TL_updateShortSentMessage)response).id;
                        worker.execute(() -> {
                            if(pending.owner!=accountEpoch) return;
                            try {
                                if(id>0 && !store.hiddenMessage(id)) {
                                    TLRPC.Message sent=store.getMessage(id,self);
                                    outbox.completed(pending.localId,sent);
                                }
                            } catch(RuntimeException unavailable) {
                                if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram sent-message refresh: "+unavailable.getClass().getSimpleName());
                            } finally {
                                Utilities.stageQueue.postRunnable(() -> { if(pending.owner==accountEpoch) pending.completed.run(response,null); });
                            }
                        });
                    } else pending.completed.run(response,error);
                }
            },pending.owner);
        });
    }
    private void flushPendingSends() {
        sendWorker.execute(() -> pendingSends.values().stream().sorted(java.util.Comparator.comparingLong(send -> send.created)).forEach(this::scheduleSend));
    }
    private void recoverPendingSends() {
        flushPendingSends();
        flushDisplayedMarkers();
        long owner=accountEpoch;
        AndroidUtilities.runOnUIThread(() -> {
            XmppSession current=session;
            if(owner==accountEpoch && current!=null && current.ownJid().equals(accountJid()) && UserConfig.getInstance(account).isClientActivated()
                    && UserConfig.getInstance(account).getClientUserId()==self) SendMessagesHelper.getInstance(account).checkUnsentMessages();
        });
    }
    private final java.util.Map<Long,XmppSession> rosterLoads=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long,Long> rosterAttempts=new java.util.concurrent.ConcurrentHashMap<>();
    public void refreshRoomRoster(long dialog,boolean force) {
        XmppSession expected=session;long epoch=sessionEpoch;
        if(expected==null || state!=ConnectionsManager.ConnectionStateConnected || !isRoom(dialog)) return;
        long now=android.os.SystemClock.elapsedRealtime();
        if((!force && now-rosterAttempts.getOrDefault(dialog,Long.MIN_VALUE/2)<30000) || rosterLoads.putIfAbsent(dialog,expected)!=null) return;
        rosterAttempts.put(dialog,now);
        roomWorker.execute(() -> {
            try {
                requireRoomSession(expected,epoch);
                java.util.Map<String,String> members=expected.rooms.roster(store.room(dialog).jid);
                worker.execute(() -> {
                    try {
                        requireRoomSession(expected,epoch);store.roomRoster(dialog,members);publishRoom(dialog);
                        publishRoomInfo(dialog,expected,epoch);
                    } catch(Exception failed) { /* Keep the last complete roster if the account changed or storage failed. */ }
                    finally { rosterLoads.remove(dialog,expected); }
                });
            } catch(Exception failed) { rosterLoads.remove(dialog,expected); }
        });
    }
    private void publishRoomInfo(long dialog,XmppSession expected,long epoch) {
        TLRPC.TL_messages_chatFull full=store.roomFull(dialog,self);
        MessagesStorage.getInstance(account).updateChatInfo(full.full_chat,false);
        AndroidUtilities.runOnUIThread(() -> {
            if(session!=expected || sessionEpoch!=epoch) return;
            MessagesController.getInstance(account).putUsers(full.users,false);
            MessagesController.getInstance(account).putChatFull(full.full_chat);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.chatInfoDidLoad,full.full_chat,0,false,false);
        });
    }
    private static boolean roomMutation(TLObject request) {
        return request instanceof TLRPC.TL_messages_createChat || request instanceof TLRPC.TL_messages_addChatUser
                || request instanceof TLRPC.TL_messages_editChatTitle || request instanceof TLRPC.TL_messages_editChatAbout
                || request instanceof TLRPC.TL_messages_editChatPhoto;
    }
    private void requireRoomSession(XmppSession expected,long epoch) {
        if(session!=expected || sessionEpoch!=epoch) throw new IllegalStateException("Account connection changed. Please retry.");
    }
    private Updates roomUpdates(long dialog) {
        Updates updates=new Updates(); updates.date=now(); updates.chats.add(store.roomChat(dialog,self));
        updates.users.addAll(store.roomUsers(dialog,self)); return updates;
    }
    private long nativeRoomState(XmppSession expected,long epoch,com.negzero.monogram.xmpp.XmppRooms.State room,boolean created) throws Exception {
        return worker.submit(() -> {
            requireRoomSession(expected,epoch);
            long dialog=created ? store.room(room.room.toString(),room.nickname) : store.knownRoom(room.room.toString());
            if(dialog==0) throw new IllegalArgumentException("Unknown room");
            store.roomState(room,self); publishRoom(dialog);
            if(created) {
                dirtyRoomBookmark(dialog);
                publishDialogs(dialogs(),() -> session==expected && sessionEpoch==epoch,() -> {});
            }
            return dialog;
        }).get();
    }
    private String inviteAddress(TLRPC.InputUser input) throws Exception {
        long peer=input instanceof TLRPC.TL_inputUserSelf ? self : input.user_id;
        String address=store.jid(peer);
        if(peer<=0 || !org.jxmpp.jid.impl.JidCreate.from(address).isEntityBareJid())
            throw new IllegalArgumentException("Select a person's bare XMPP address, rather than an anonymous room nickname.");
        return address;
    }
    private TLRPC.TL_messages_invitedUsers createNativeRoom(TLRPC.TL_messages_createChat request) throws Exception {
        if(request.ttl_period>0) throw new IllegalArgumentException("Disappearing group messages are not supported by this XMPP room flow.");
        java.util.LinkedHashMap<String,TLRPC.InputUser> invited=new java.util.LinkedHashMap<>();
        for(TLRPC.InputUser input:request.users) { String address=inviteAddress(input); if(!address.equals(accountJid())) invited.put(address,input); }
        XmppSession expected=session(); long epoch=sessionEpoch;
        String nickname=org.jxmpp.jid.impl.JidCreate.entityBareFrom(accountJid()).getLocalpart().toString();
        com.negzero.monogram.xmpp.XmppRooms.State room=expected.rooms.createPrivate(nickname,request.title);
        requireRoomSession(expected,epoch);
        long dialog=nativeRoomState(expected,epoch,room,true);
        MonogramInvitedUsers result=new MonogramInvitedUsers(); java.util.ArrayList<String> failures=new java.util.ArrayList<>();
        for(String address:invited.keySet()) {
            requireRoomSession(expected,epoch);
            try { expected.rooms.invite(room.room.toString(),address,"Join us in this room"); }
            catch(Exception failed) { failures.add(address+": "+roomError(failed)); }
        }
        requireRoomSession(expected,epoch);
        result.updates=roomUpdates(dialog);refreshRoomRoster(dialog,true);
        result.failure=failures.isEmpty() ? null : "The group was created, but these invitations failed:\n\n"+android.text.TextUtils.join("\n",failures);
        return result;
    }
    private void dispatchRequest(TLObject request,RequestDelegate callback) { dispatchRequest(request,callback,accountEpoch); }
    private void dispatchRequest(TLObject request, RequestDelegate callback,long epoch) {
        if (request instanceof MonogramFileRequest) {
            sendWorker.execute(() -> { if(epoch==accountEpoch) upload((MonogramFileRequest)request,callback); }); return;
        }
        boolean cached = MonogramLocalFilters.supports(request)
                || request instanceof TLRPC.TL_messages_updatePinnedMessage || request instanceof TLRPC.TL_messages_unpinAllMessages
                || request instanceof TLRPC.TL_messages_sendReaction && ((TLRPC.TL_messages_sendReaction)request).peer!=null && ((TLRPC.TL_messages_sendReaction)request).peer.user_id==self
                || request instanceof TLRPC.TL_messages_getDefaultTagReactions || request instanceof TLRPC.TL_messages_getSavedReactionTags || request instanceof TLRPC.TL_messages_updateSavedReactionTag
                || request instanceof TLRPC.TL_messages_getDialogs || request instanceof TLRPC.TL_messages_getPinnedDialogs || request instanceof TLRPC.TL_messages_getHistory
                || request instanceof TLRPC.TL_messages_search || request instanceof TLRPC.TL_messages_searchGlobal
                || request instanceof TLRPC.TL_messages_getEmojiKeywords || request instanceof TLRPC.TL_messages_getEmojiKeywordsDifference
                || request instanceof TLRPC.TL_messages_getMessages || request instanceof TLRPC.TL_users_getFullUser
                || request instanceof TLRPC.TL_users_getUsers || request instanceof TLRPC.TL_photos_getUserPhotos
                || request instanceof TLRPC.TL_messages_getPollResults || request instanceof TLRPC.TL_messages_getPollVotes
                || request instanceof TLRPC.TL_messages_sendVote && ((TLRPC.TL_messages_sendVote)request).peer!=null && ((TLRPC.TL_messages_sendVote)request).peer.user_id==self
                || request instanceof TLRPC.TL_messages_getMessagesReactions || request instanceof TLRPC.TL_messages_getMessageReactionsList
                || request instanceof TLRPC.TL_messages_getMessageReadParticipants
                || request instanceof TLRPC.TL_messages_getMessageEditData || request instanceof TLRPC.TL_messages_getFullChat || request instanceof TLRPC.TL_messages_getChats;
        (cached ? cacheWorker : roomMutation(request) ? roomWorker : sendingPeer(request)!=0 || request instanceof TLRPC.TL_messages_sendReaction || request instanceof TLRPC.TL_messages_sendVote ? sendWorker : worker).execute(() -> {
            TLObject response = null; TLRPC.TL_error error = null;
            try { if(epoch!=accountEpoch) throw new IllegalStateException("Account changed"); response = handle(request); }
            catch (Exception e) {
                if(debugLog.enabled() && (sendingPeer(request)!=0 || e instanceof java.security.GeneralSecurityException
                        || request instanceof TLRPC.TL_messages_sendReaction)) {
                    StringBuilder detail=new StringBuilder(com.negzero.monogram.xmpp.XmppRequestException.describe(e));
                    for(StackTraceElement frame:e.getStackTrace()) {
                        if(frame.getClassName().startsWith("com.negzero.monogram.") || frame.getClassName().startsWith("org.telegram.messenger.xmpp."))
                            detail.append("\n").append(frame);
                        if(detail.length()>4096) break;
                    }
                    debugLog.record("Request failure",detail.toString());
                }
                error = new TLRPC.TL_error(); error.code = 400;
                if(roomMutation(request)) error.text="MONOGRAM_XMPP_ROOM: "+roomError(e);
                else if(e instanceof ReactionConsentRequired) error.text="MONOGRAM_REACTION_CONSENT_REQUIRED";
                else if(request instanceof TLRPC.TL_messages_sendReaction && e instanceof IllegalArgumentException) error.text="MONOGRAM_REACTION_UNAVAILABLE: "+e.getMessage();
                else if(e instanceof MonogramPolls.Invalid) error.text="MONOGRAM_XMPP_FAILED|"+e.getMessage();
                else if(e instanceof MonogramArticles.Failure) error.text="MONOGRAM_XMPP_FAILED|"+e.getMessage();
                else if (e instanceof ReplyUnavailable) error.text = "MONOGRAM_REPLY_UNAVAILABLE";
                else if (e instanceof MonogramStore.EditUnavailable) error.text = ((MonogramStore.EditUnavailable)e).code;
                else if ((request instanceof TLRPC.TL_messages_editMessage || request instanceof TLRPC.TL_messages_getMessageEditData)
                        && e instanceof IllegalArgumentException) error.text = "MONOGRAM_EDIT_UNAVAILABLE";
                else if (e instanceof org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException) error.text = "MONOGRAM_OMEMO_TRUST_REQUIRED";
                else if (e instanceof com.negzero.monogram.xmpp.OmemoSendException) error.text = omemoSendError((com.negzero.monogram.xmpp.OmemoSendException)e);
                else if (e instanceof UnsupportedOperationException) error.text = "MONOGRAM_FEATURE_UNAVAILABLE";
                else if (session == null || !session.connection().isAuthenticated()) error.text = "MONOGRAM_XMPP_DISCONNECTED";
                else error.text = "MONOGRAM_XMPP_FAILED|"+com.negzero.monogram.xmpp.XmppRequestException.describe(e);
            }
            if(error!=null && (request instanceof TLRPC.TL_messages_editChatTitle
                    || request instanceof TLRPC.TL_messages_editChatAbout || request instanceof TLRPC.TL_messages_editChatPhoto)) {
                final String detail=error.text;
                AndroidUtilities.runOnUIThread(() -> {
                    org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getSafeLastFragment();
                    if(epoch==accountEpoch && fragment!=null && fragment.getCurrentAccount()==account && fragment.getParentActivity()!=null)
                        org.telegram.ui.Components.AlertsCreator.showSimpleAlert(fragment,LocaleController.getString(R.string.AppName),detail);
                });
            }
            final TLObject completed = response; final TLRPC.TL_error failed = error;
            if (callback != null) {
                Runnable deliver = () -> Utilities.stageQueue.postRunnable(() -> {
                    if (epoch != accountEpoch) {
                        TLRPC.TL_error changed = new TLRPC.TL_error(); changed.code = 400; changed.text = "MONOGRAM_ACCOUNT_CHANGED";
                        callback.run(null, changed);
                    } else callback.run(completed, failed);
                });
                if (completed instanceof TLRPC.messages_Dialogs) {
                    TLRPC.messages_Dialogs dialogs = (TLRPC.messages_Dialogs) completed;
                    MessagesStorage.getInstance(account).restoreMonogramReadState(dialogs.messages,dialogs.dialogs,deliver);
                } else deliver.run();
            }
        });
    }
    private static final class ReactionConsentRequired extends IllegalStateException {}
    private XmppMessage.Encryption reactionEncryption(int id,long peer) {
        int original=store.encryptionOf(id);
        if(original==2 || encryption(peer)==XmppMessage.Encryption.OMEMO2) return XmppMessage.Encryption.OMEMO2;
        return original==1 || encrypted(peer) ? XmppMessage.Encryption.LEGACY_OMEMO : XmppMessage.Encryption.PLAIN;
    }
    public boolean requiresPlainReactionConsent(int id,long peer) {
        try { return reactionEncryption(id,peer)==XmppMessage.Encryption.LEGACY_OMEMO && !plainReactionsAllowed(peer); }
        catch(IllegalArgumentException unknown) { return false; }
    }
    private String plainReactionKey(long peer) { return "plain-reactions-"+accountJid()+"\n"+store.jid(peer); }
    public boolean plainReactionsAllowed(long peer) { return preferences.getBoolean(plainReactionKey(peer),false); }
    public boolean setPlainReactions(long peer,boolean allowed) { return preferences.edit().putBoolean(plainReactionKey(peer),allowed).commit(); }
    public boolean allowPlainReactions(long peer) { return setPlainReactions(peer,true); }
    private volatile java.util.Map<String,Integer> savedTagColors=java.util.Collections.emptyMap();
    public int savedTagColor(TLRPC.Reaction reaction,int fallback) {
        try { Integer color=savedTagColors.get(MonogramSavedTags.key(reaction));return color==null || color==0 ? fallback : color; }
        catch(IllegalArgumentException unsupported) { return fallback; }
    }
    public void editSavedTag(TLRPC.Reaction original,TLRPC.Reaction replacement,String title,int color,Consumer<String> completed) {
        long owner=accountEpoch;
        cacheWorker.execute(() -> {
            String error=null;
            try {
                if(owner!=accountEpoch || self<=0) throw new IllegalStateException("Account changed");
                ArrayList<Integer> changed=store.savedTags.edit(self,original,replacement,title,color);
                savedTagColors=store.savedTags.colors();
                Updates updates=reactionUpdates(self,changed);updates.updates.add(new TL_update.TL_updateSavedReactionTags());
                Utilities.stageQueue.postRunnable(() -> {
                    if(owner==accountEpoch) MessagesController.getInstance(account).processUpdates(updates,false);
                });
            } catch(Exception failed) { error=failed.getMessage()==null ? "Could not save this tag." : failed.getMessage(); }
            String failure=error;
            AndroidUtilities.runOnUIThread(() -> {
                if(owner!=accountEpoch) return;
                MediaDataController.getInstance(account).loadSavedReactions(true);
                if(failure==null) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.savedReactionTagsUpdate,0L,original,replacement);
                completed.accept(failure);
            });
        });
    }
    public TLRPC.TL_messageReactions cachedReactions(int id) {
        try { return store.getMessage(id,self).reactions; }
        catch(IllegalArgumentException removed) { return new TLRPC.TL_messageReactions(); }
    }
    private Updates reactionUpdates(long peer,java.util.Collection<Integer> ids) {
        Updates result=new Updates(); result.date=now();
        java.util.Set<Long> users=new java.util.LinkedHashSet<>();
        for(int id:ids) {
            if(store.hiddenMessage(id)) continue;
            TLRPC.Message message=store.getMessage(id,self);
            if(message.dialog_id!=peer) throw new IllegalArgumentException("Reaction target belongs to another conversation");
            TL_update.TL_updateMessageReactions update=new TL_update.TL_updateMessageReactions();
            update.peer=peer<0 ? new TLRPC.TL_peerChat() : new TLRPC.TL_peerUser();
            if(peer<0) update.peer.chat_id=-peer; else update.peer.user_id=peer;
            update.msg_id=id; update.reactions=message.reactions; result.updates.add(update);
            if(message.media instanceof TLRPC.TL_messageMediaPoll) result.updates.add(MonogramPolls.update(message));
            if(message.from_id!=null && message.from_id.user_id!=0) users.add(message.from_id.user_id);
            for(TLRPC.MessagePeerReaction reaction:message.reactions.recent_reactions)
                if(reaction.peer_id!=null && reaction.peer_id.user_id!=0) users.add(reaction.peer_id.user_id);
        }
        TL_update.TL_updateDeleteMessages hidden=new TL_update.TL_updateDeleteMessages(); hidden.messages.addAll(store.reactions.hidden(peer));
        if(!hidden.messages.isEmpty()) result.updates.add(hidden);
        if(result.updates.isEmpty()) return result;
        if(peer<0) result.chats.add(store.roomChat(peer,self)); else users.add(peer);
        users.add(self);
        for(long user:users) result.users.add(store.user(user,self));
        return result;
    }
    private void publishReactions(long peer,java.util.Collection<Integer> ids) {
        java.util.Set<Integer> changed=new java.util.LinkedHashSet<>(ids==null ? store.reactions.targets(peer) : ids);
        changed.addAll(store.reactions.reconcile(peer));
        if(!UserConfig.getInstance(account).isClientActivated()) return;
        Updates updates=reactionUpdates(peer,changed); if(updates.updates.isEmpty()) return;
        final long owner=accountEpoch;
        Utilities.stageQueue.postRunnable(() -> { if(accountEpoch==owner) MessagesController.getInstance(account).processUpdates(updates,false); });
    }
    public void reloadReactions(int id,long peer) {
        final long owner=accountEpoch;
        cacheWorker.execute(() -> { if(accountEpoch==owner) try { publishReactions(peer,java.util.Collections.singleton(id)); } catch(IllegalArgumentException ignored) {} });
    }
    private MonogramBlocking blockingCache() { return new MonogramBlocking(preferences,store,accountJid(),self); }
    private void refreshBlocked(XmppSession expected,long epoch) {
        profileWorker.execute(() -> {
            try { expected.blocking.list(); worker.execute(() -> {
                if(session==expected && sessionEpoch==epoch) rememberBlocked(expected,epoch);
            }); } catch(Exception unavailable) { /* Keep the last acknowledged snapshot when disconnected or unsupported. */ }
        });
    }
    private void rememberBlocked(XmppSession expected,long epoch) {
        if(session!=expected || sessionEpoch!=epoch || !expected.ownJid().equals(accountJid())) return;
        java.util.List<String> snapshot=expected.blocking.snapshot(); if(snapshot==null) return;
        final long owner=accountEpoch;final String jid=accountJid();
        try {
            MonogramBlocking cache=blockingCache();cache.remember(snapshot);
            TLRPC.TL_contacts_blockedSlice all=cache.page(snapshot,0,1000);
            java.util.Set<Long> blocked=new java.util.LinkedHashSet<>();ArrayList<TLRPC.User> users=new ArrayList<>();
            for(int offset=0;offset<snapshot.size();offset+=1000) {
                TLRPC.TL_contacts_blockedSlice page=offset==0 ? all : cache.page(snapshot,offset,1000);
                users.addAll(page.users);for(TLRPC.TL_peerBlocked item:page.blocked) blocked.add(item.peer_id.user_id);
            }
            java.util.Set<Long> effective=new java.util.HashSet<>();
            for(long peer:store.peers()) if(MonogramBlocking.blocked(snapshot,store.jid(peer))) effective.add(peer);
            AndroidUtilities.runOnUIThread(() -> {
                if(session==expected && sessionEpoch==epoch && sameAccount(owner,jid))
                    MessagesController.getInstance(account).applyMonogramBlockedPeers(users,blocked,effective::contains);
            });
        } catch(Exception invalid) { if(BuildVars.LOGS_ENABLED) FileLog.e("Monogram blocking snapshot: "+invalid.getClass().getSimpleName()); }
    }
    private TLObject blockingRequest(TLObject request) throws Exception {
        MonogramBlocking cache=blockingCache();XmppSession expected=session;long epoch=sessionEpoch;
        boolean connected=expected!=null && expected.connection().isAuthenticated();
        if(request instanceof TLRPC.TL_contacts_getBlocked) {
            TLRPC.TL_contacts_getBlocked query=(TLRPC.TL_contacts_getBlocked)request;
            if(query.my_stories_from) throw new UnsupportedOperationException();
            java.util.List<String> list;
            if(connected) {
                list=expected.blocking.list();requireRoomSession(expected,epoch);rememberBlocked(expected,epoch);
            } else { list=cache.cached();if(list==null) throw new IllegalStateException("XMPP is disconnected"); }
            return cache.page(list,query.offset,query.limit);
        }
        TLRPC.InputPeer input;boolean block=request instanceof TLRPC.TL_contacts_block;
        if(block) {
            TLRPC.TL_contacts_block query=(TLRPC.TL_contacts_block)request;
            if(query.my_stories_from) throw new UnsupportedOperationException();input=query.id;
        } else {
            TLRPC.TL_contacts_unblock query=(TLRPC.TL_contacts_unblock)request;
            if(query.my_stories_from) throw new UnsupportedOperationException();input=query.id;
        }
        String target=cache.target(input);expected=session();epoch=sessionEpoch;
        if(block) expected.blocking.block(target);else expected.blocking.unblock(target);
        requireRoomSession(expected,epoch);rememberBlocked(expected,epoch);return new TLRPC.TL_boolTrue();
    }
    private long scheduledPeer(TLRPC.InputPeer peer) {
        if(peer instanceof TLRPC.TL_inputPeerSelf) return self;
        if(peer==null || peer.channel_id!=0) throw new IllegalArgumentException("Conversation required");
        long dialog=peer.chat_id!=0 ? -peer.chat_id : peer.user_id;
        if(dialog==0) throw new IllegalArgumentException("Conversation required");
        return dialog;
    }
    private Updates scheduledDeleteUpdates(long peer,java.util.Collection<Integer> ids) {
        Updates result=new Updates(); result.date=now();
        TL_update.TL_updateDeleteScheduledMessages update=new TL_update.TL_updateDeleteScheduledMessages();
        update.peer=peer<0 ? new TLRPC.TL_peerChat() : new TLRPC.TL_peerUser();
        if(peer<0) update.peer.chat_id=-peer; else update.peer.user_id=peer;
        update.messages.addAll(ids); result.updates.add(update); return result;
    }
    private TLRPC.TL_messages_messages scheduledMessages(long peer,java.util.Collection<Integer> ids) {
        TLRPC.TL_messages_messages result=new TLRPC.TL_messages_messages();
        java.util.HashSet<Integer> wanted=ids==null ? null : new java.util.HashSet<>(ids);
        for(MonogramScheduledMessages.Entry entry:MonogramScheduledMessages.list(account,accountJid(),accountEpoch,peer))
            if(wanted==null || wanted.contains(entry.id)) result.messages.add(MonogramScheduledMessages.project(entry,self));
        return result;
    }
    public void restoreStory(long peer,org.telegram.tgnet.tl.TL_stories.StoryItem story) { stories().restore(peer,story); }
    private MonogramStories storyBridge;
    private synchronized MonogramStories stories() {
        if(storyBridge==null) storyBridge=new MonogramStories(account,store,()->session());
        return storyBridge;
    }
    private TLObject handle(TLObject request) throws Exception {
        TLObject localFilters=MonogramLocalFilters.handle(request);if(localFilters!=null) return localFilters;
        TLObject storyResult=stories().handle(request);if(storyResult!=null) return storyResult;
        if(request instanceof TLRPC.TL_messages_getQuickReplyMessages) {
            TLRPC.TL_messages_getQuickReplyMessages query=(TLRPC.TL_messages_getQuickReplyMessages)request;
            MonogramQuickReplies.Outgoing template=org.telegram.ui.Business.QuickRepliesController.getInstance(account).monogramOutgoing(query.shortcut_id);
            TLRPC.TL_messages_messages result=new TLRPC.TL_messages_messages();
            if(template!=null) for(TLRPC.Message message:template.messages)
                if(query.id.isEmpty() || query.id.contains(message.id)) result.messages.add(message);
            return result;
        }
        if(request instanceof TLRPC.TL_messages_deleteQuickReplyMessages) {
            TLRPC.TL_messages_deleteQuickReplyMessages query=(TLRPC.TL_messages_deleteQuickReplyMessages)request;
            org.telegram.ui.Business.QuickRepliesController controller=org.telegram.ui.Business.QuickRepliesController.getInstance(account);
            TL_update.TL_updateDeleteQuickReplyMessages update=new TL_update.TL_updateDeleteQuickReplyMessages();
            update.shortcut_id=query.shortcut_id;
            for(Integer id:query.id) if(controller.deleteMonogramTemplateMessage(query.shortcut_id,id)) update.messages.add(id);
            Updates result=new Updates(); result.date=now(); result.updates.add(update); return result;
        }
        if(request instanceof TLRPC.TL_messages_sendQuickReplyMessages) {
            TLRPC.TL_messages_sendQuickReplyMessages query=(TLRPC.TL_messages_sendQuickReplyMessages)request;
            long peer=scheduledPeer(MonogramSelfChat.resolve(query.peer,self));
            MonogramQuickReplies.Outgoing outgoing=org.telegram.ui.Business.QuickRepliesController.getInstance(account).monogramOutgoing(query.shortcut_id);
            if(outgoing==null) throw new IllegalArgumentException("Quick reply does not exist");
            if(!query.id.isEmpty()) {
                java.util.HashSet<Integer> selected=new java.util.HashSet<>(query.id);
                java.util.HashSet<Integer> available=new java.util.HashSet<>();
                for(TLRPC.Message message:outgoing.messages) available.add(message.id);
                if(!available.containsAll(selected)) throw new IllegalArgumentException("Quick reply message no longer exists");
                outgoing.messages.removeIf(message -> !selected.contains(message.id));
            }
            sendQuickReply(peer,outgoing);
            Updates result=new Updates(); result.date=now(); return result;
        }
        if(request instanceof TLRPC.TL_messages_getScheduledHistory) {
            TLRPC.TL_messages_getScheduledHistory query=(TLRPC.TL_messages_getScheduledHistory)request;
            return scheduledMessages(scheduledPeer(query.peer),null);
        }
        if(request instanceof TLRPC.TL_messages_getScheduledMessages) {
            TLRPC.TL_messages_getScheduledMessages query=(TLRPC.TL_messages_getScheduledMessages)request;
            return scheduledMessages(scheduledPeer(query.peer),query.id);
        }
        if(request instanceof TLRPC.TL_messages_deleteScheduledMessages) {
            TLRPC.TL_messages_deleteScheduledMessages query=(TLRPC.TL_messages_deleteScheduledMessages)request;
            long peer=scheduledPeer(query.peer);
            java.util.Set<Integer> removed=MonogramScheduledMessages.cancel(account,accountJid(),accountEpoch,peer,query.id);
            if(!removed.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.scheduledMessagesUpdated,peer,0,false));
            }
            armScheduledWake(); return scheduledDeleteUpdates(peer,removed);
        }
        if(request instanceof TLRPC.TL_messages_sendScheduledMessages) {
            TLRPC.TL_messages_sendScheduledMessages query=(TLRPC.TL_messages_sendScheduledMessages)request;
            long peer=scheduledPeer(query.peer);
            MonogramScheduledMessages.sendNow(account,accountJid(),accountEpoch,peer,query.id,System.currentTimeMillis());
            drainScheduledMessages(); return new Updates();
        }
        if(request instanceof TLRPC.TL_messages_getCustomEmojiDocuments) {
            org.telegram.tgnet.Vector<TLRPC.Document> result=new org.telegram.tgnet.Vector<>(TLRPC.Document::TLdeserialize);
            for(long id:((TLRPC.TL_messages_getCustomEmojiDocuments)request).document_id) {
                TLRPC.Document document=MonogramPacks.document(account,id);
                if(document==null) document=customEmoji(id);
                if(document!=null) result.objects.add(document);
            }
            return result;
        }
        if(MonogramBlocking.handles(request)) return blockingRequest(request);
        if(MonogramCallPrivacy.handles(request)) return callPrivacy().handle(request);
        if(request instanceof TLRPC.TL_messages_createChat) return createNativeRoom((TLRPC.TL_messages_createChat)request);
        if(request instanceof TLRPC.TL_messages_addChatUser) {
            TLRPC.TL_messages_addChatUser query=(TLRPC.TL_messages_addChatUser)request;
            long dialog=-query.chat_id; String address=inviteAddress(query.user_id);
            XmppSession expected=session(); long epoch=sessionEpoch;
            preferences.edit().remove("last-room-invite-error-"+dialog).apply();
            expected.rooms.invite(store.room(dialog).jid,address,"Join us in this room"); requireRoomSession(expected,epoch);refreshRoomRoster(dialog,true);
            TLRPC.TL_messages_invitedUsers result=new TLRPC.TL_messages_invitedUsers(); result.updates=roomUpdates(dialog); return result;
        }
        if(request instanceof TLRPC.TL_messages_editChatTitle || request instanceof TLRPC.TL_messages_editChatAbout) {
            boolean title=request instanceof TLRPC.TL_messages_editChatTitle;
            long dialog=title ? -((TLRPC.TL_messages_editChatTitle)request).chat_id : -((TLRPC.TL_messages_editChatAbout)request).peer.chat_id;
            String value=title ? ((TLRPC.TL_messages_editChatTitle)request).title : ((TLRPC.TL_messages_editChatAbout)request).about;
            XmppSession expected=session(); long epoch=sessionEpoch;
            com.negzero.monogram.xmpp.XmppRooms.State room=expected.rooms.configure(store.room(dialog).jid,title ? value : null,title ? null : value);
            nativeRoomState(expected,epoch,room,false);
            return title ? roomUpdates(dialog) : new TLRPC.TL_boolTrue();
        }
        if(request instanceof TLRPC.TL_messages_editChatPhoto) {
            TLRPC.TL_messages_editChatPhoto query=(TLRPC.TL_messages_editChatPhoto)request;
            long dialog=-query.chat_id; String room=store.room(dialog).jid; byte[] png;
            if(query.photo instanceof TLRPC.TL_inputChatPhotoEmpty) png=new byte[0];
            else if(query.photo instanceof TLRPC.TL_inputChatUploadedPhoto) {
                TLRPC.TL_inputChatUploadedPhoto upload=(TLRPC.TL_inputChatUploadedPhoto)query.photo;
                if(!(upload.file instanceof MonogramAvatarFile) || upload.video!=null || upload.video_emoji_markup!=null)
                    throw new IllegalArgumentException("Choose a static image for the room avatar.");
                File source=((MonogramAvatarFile)upload.file).source;
                if(!source.isFile() || source.length()==0 || source.length()>com.negzero.monogram.xmpp.XmppProfile.MAX_AVATAR_BYTES)
                    throw new IllegalArgumentException("The room avatar file is missing or too large.");
                byte[] bytes=new byte[(int)source.length()];
                try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(source))) { in.readFully(bytes); }
                png=MonogramProfiles.png(bytes);
            } else throw new IllegalArgumentException("Choose a new room avatar or remove the current one.");
            XmppSession expected=session(); long epoch=sessionEpoch;
            if(!expected.rooms.state(room).joined) throw new IllegalStateException("Join the room before changing its avatar.");
            profileVersions.merge(dialog,1L,Long::sum);
            com.negzero.monogram.xmpp.XmppProfile changed=expected.profiles.roomAvatar(room,png);
            requireRoomSession(expected,epoch); profileVersions.merge(dialog,1L,Long::sum);
            store.profile(dialog,changed); publishRoom(dialog); return roomUpdates(dialog);
        }
        if(request instanceof TLRPC.TL_messages_deletePhoneCallHistory) {
            if(((TLRPC.TL_messages_deletePhoneCallHistory)request).revoke)
                throw new UnsupportedOperationException("Call history can only be deleted on this device");
            TLRPC.TL_messages_affectedFoundMessages result=new TLRPC.TL_messages_affectedFoundMessages();
            result.messages.addAll(store.callMessageIds(self));store.deleteMessages(result.messages);
            androidx.collection.LongSparseArray<ArrayList<Integer>> removed=new androidx.collection.LongSparseArray<>();removed.put(0,result.messages);
            long owner=accountEpoch;
            AndroidUtilities.runOnUIThread(() -> {
                if(owner==accountEpoch) NotificationsController.getInstance(account).removeDeletedMessagesFromNotifications(removed,false);
            });
            return result;
        }
        if(request instanceof TLRPC.TL_messages_deleteMessages) {
            TLRPC.TL_messages_deleteMessages query=(TLRPC.TL_messages_deleteMessages)request;
            if(query.revoke) throw new UnsupportedOperationException("XMPP message retraction is not implemented");
            java.util.Set<Long> peers=new java.util.HashSet<>();
            for(int id:query.id) {
                try { peers.add(store.getMessage(id,self).dialog_id); } catch(IllegalArgumentException unknown) { }
                FileTransfer.Job job=fileJobs.remove(id); if(job!=null) job.cancel();
                automaticJobs.remove(id);
            }
            store.deleteMessages(query.id);
            for(long peer:peers) refreshReplies(peer,new java.util.HashSet<>(query.id));
            androidx.collection.LongSparseArray<ArrayList<Integer>> removed=new androidx.collection.LongSparseArray<>();
            removed.put(0,new ArrayList<>(query.id));
            long owner=accountEpoch;
            AndroidUtilities.runOnUIThread(() -> {
                if(accountEpoch==owner) NotificationsController.getInstance(account).removeDeletedMessagesFromNotifications(removed,false);
            });
            return new TLRPC.TL_messages_affectedMessages();
        }
        if(request instanceof TLRPC.TL_messages_getEmojiKeywords) return MonogramEmojiKeywords.load(ApplicationLoader.applicationContext.getAssets(),((TLRPC.TL_messages_getEmojiKeywords)request).lang_code,-1);
        if(request instanceof TLRPC.TL_messages_getEmojiKeywordsDifference) {
            TLRPC.TL_messages_getEmojiKeywordsDifference query=(TLRPC.TL_messages_getEmojiKeywordsDifference)request;
            return MonogramEmojiKeywords.load(ApplicationLoader.applicationContext.getAssets(),query.lang_code,query.from_version);
        }
        if (request instanceof TLRPC.TL_updates_getState) {
            TLRPC.TL_updates_state result = new TLRPC.TL_updates_state(); result.date = now(); return result;
        }
        if (request instanceof TLRPC.TL_updates_getDifference) {
            TLRPC.TL_updates_differenceEmpty result = new TLRPC.TL_updates_differenceEmpty(); result.date = now(); return result;
        }
        if (request instanceof TLRPC.TL_contacts_getContacts) {
            session();
            TLRPC.TL_contacts_contacts result = new TLRPC.TL_contacts_contacts();
            for (RosterEntry entry : Roster.getInstanceFor(session().connection()).getEntries()) {
                long id = store.peer(entry.getJid().toString()); store.rosterName(id, entry.getName()); result.users.add(store.user(id, self));
                TLRPC.TL_contact contact = new TLRPC.TL_contact(); contact.user_id = id; contact.mutual = entry.canSeeMyPresence(); result.contacts.add(contact);
            }
            return result;
        }
        if(request instanceof TLRPC.TL_messages_getMessageReadParticipants) {
            TLRPC.TL_messages_getMessageReadParticipants query=(TLRPC.TL_messages_getMessageReadParticipants)request;
            if(query.peer==null || query.peer.channel_id!=0 || query.peer.chat_id<=0) throw new IllegalArgumentException("Room required");
            org.telegram.tgnet.Vector<TLRPC.TL_readParticipantDate> result=new org.telegram.tgnet.Vector<>(TLRPC.TL_readParticipantDate::TLdeserialize);
            for(long user:store.roomReaders(-query.peer.chat_id,query.msg_id,self)) {
                TLRPC.TL_readParticipantDate reader=new TLRPC.TL_readParticipantDate();
                reader.user_id=user;
                // XEP-0333 supplies a read frontier, not the exact time this
                // particular message was read. Zero leaves the timestamp hidden.
                reader.date=0;result.objects.add(reader);
            }
            return result;
        }
        if (request instanceof TLRPC.TL_users_getUsers) {
            org.telegram.tgnet.Vector<TLRPC.User> result = new org.telegram.tgnet.Vector<>(TLRPC.User::TLdeserialize);
            for (TLRPC.InputUser input : ((TLRPC.TL_users_getUsers) request).id) {
                long peer = input instanceof TLRPC.TL_inputUserSelf ? self : input.user_id;
                result.objects.add(store.user(peer, self));
            }
            return result;
        }
        if (request instanceof TLRPC.TL_users_getFullUser) {
            TLRPC.InputUser input = ((TLRPC.TL_users_getFullUser) request).id;
            long peer = input instanceof TLRPC.TL_inputUserSelf ? self : input.user_id;
            // Respond from the offline cache; refresh separately without blocking the profile screen.
            refreshProfile(peer);
            XmppSession calls=session;
            if(peer>0 && peer!=self && calls!=null && calls.connection().isAuthenticated()) calls.calls.discover(store.jid(peer));
            return profileResult(peer);
        }
        if (request instanceof TL_account.updateProfile) {
            TL_account.updateProfile edit = (TL_account.updateProfile) request;
            String first = (edit.flags & 1) != 0 ? edit.first_name : null;
            String last = (edit.flags & 2) != 0 ? edit.last_name : null;
            String about = (edit.flags & 4) != 0 ? edit.about : null;
            profileVersions.merge(self, 1L, Long::sum);
            com.negzero.monogram.xmpp.XmppProfile changed = session().profiles.edit(first, last, about, store.profile(self));
            profileVersions.merge(self, 1L, Long::sum);
            store.profile(self, changed); publishProfile(self, sessionEpoch);
            return ownUser();
        }
        if (request instanceof TLRPC.TL_photos_uploadProfilePhoto) {
            TLRPC.TL_photos_uploadProfilePhoto upload = (TLRPC.TL_photos_uploadProfilePhoto) request;
            if (!(upload.file instanceof MonogramAvatarFile) || upload.video != null || upload.video_emoji_markup != null) throw new UnsupportedOperationException();
            File source = ((MonogramAvatarFile) upload.file).source;
            if (source.length() > com.negzero.monogram.xmpp.XmppProfile.MAX_AVATAR_BYTES) throw new IllegalArgumentException("Avatar too large");
            byte[] bytes = new byte[(int) source.length()];
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(source))) { in.readFully(bytes); }
            profileVersions.merge(self, 1L, Long::sum);
            com.negzero.monogram.xmpp.XmppProfile changed = session().profiles.avatar(MonogramProfiles.png(bytes));
            profileVersions.merge(self, 1L, Long::sum);
            store.profile(self, changed); publishProfile(self, sessionEpoch);
            TLRPC.TL_photos_photo result = new TLRPC.TL_photos_photo(); result.photo = store.fullUser(self, self).profile_photo;
            result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_photos_updateProfilePhoto) {
            if (!(((TLRPC.TL_photos_updateProfilePhoto) request).id instanceof TLRPC.TL_inputPhotoEmpty)) throw new UnsupportedOperationException();
            profileVersions.merge(self, 1L, Long::sum);
            com.negzero.monogram.xmpp.XmppProfile changed = session().profiles.avatar(new byte[0]);
            profileVersions.merge(self, 1L, Long::sum);
            store.profile(self, changed); publishProfile(self, sessionEpoch);
            TLRPC.TL_photos_photo result = new TLRPC.TL_photos_photo(); result.photo = new TLRPC.TL_photoEmpty(); result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_photos_getUserPhotos) {
            TLRPC.TL_photos_getUserPhotos query = (TLRPC.TL_photos_getUserPhotos) request;
            long peer = query.user_id instanceof TLRPC.TL_inputUserSelf ? self : query.user_id.user_id;
            TLRPC.TL_photos_photos result = new TLRPC.TL_photos_photos();
            TLRPC.Photo photo = store.fullUser(peer, self).profile_photo;
            if (photo instanceof TLRPC.TL_photo && query.offset == 0 && query.max_id == 0 && query.limit > 0) result.photos.add(photo);
            result.users.add(store.user(peer, self)); result.count = photo instanceof TLRPC.TL_photo ? 1 : 0; return result;
        }
        if (request instanceof TLRPC.TL_messages_getChats) {
            TLRPC.TL_messages_chats result=new TLRPC.TL_messages_chats();
            for(long id:((TLRPC.TL_messages_getChats)request).id) result.chats.add(store.roomChat(-id,self)); return result;
        }
        if (request instanceof TLRPC.TL_messages_getFullChat) {
            long dialog=-((TLRPC.TL_messages_getFullChat)request).chat_id;refreshRoomRoster(dialog,false);return store.roomFull(dialog,self);
        }
        if (request instanceof TLRPC.TL_messages_deleteChatUser) {
            TLRPC.TL_messages_deleteChatUser leave=(TLRPC.TL_messages_deleteChatUser)request;
            if (!(leave.user_id instanceof TLRPC.TL_inputUserSelf) && leave.user_id.user_id!=self) throw new UnsupportedOperationException();
            session().rooms.leave(store.jid(-leave.chat_id)); store.leaveRoom(-leave.chat_id); dirtyRoomBookmark(-leave.chat_id); publishRoom(-leave.chat_id);
            Updates result=new Updates(); result.date=now(); result.chats.add(store.roomChat(-leave.chat_id,self)); return result;
        }
        TLObject pins=MonogramMessagePins.handle(account,accountJid(),store,self,request);
        if(pins!=null) return pins;
        if (request instanceof TLRPC.TL_messages_getDialogs) return MonogramDialogState.dialogs(account,accountJid(),dialogs(),(TLRPC.TL_messages_getDialogs)request);
        if (request instanceof TLRPC.TL_messages_getPinnedDialogs) return MonogramDialogState.pinned(account,accountJid(),dialogs(),((TLRPC.TL_messages_getPinnedDialogs)request).folder_id);
        if (request instanceof TLRPC.TL_messages_getMessages) {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            java.util.Set<Long> peers = new java.util.HashSet<>();
            for (int input : ((TLRPC.TL_messages_getMessages) request).id) {
                if(store.hiddenMessage(input)) continue;
                try { TLRPC.Message message = store.getMessage(input, self); result.messages.add(message); peers.add(message.dialog_id); }
                catch (IllegalArgumentException missing) { /* The requested local message is unavailable. */ }
            }
            for (long peer : peers) { if(peer<0) { result.chats.add(store.roomChat(peer,self)); result.users.addAll(store.roomUsers(peer,self)); } else result.users.add(store.user(peer, self)); } result.users.add(ownUser()); return result;
        }
        if(request instanceof TLRPC.TL_messages_search || request instanceof TLRPC.TL_messages_searchGlobal) {
            TLRPC.TL_messages_messagesSlice result;
            if(request instanceof TLRPC.TL_messages_search) {
                TLRPC.TL_messages_search query=(TLRPC.TL_messages_search)request;
                result=query.filter instanceof TLRPC.TL_inputMessagesFilterPinned ? MonogramMessagePins.search(account,accountJid(),store,self,query)
                        : store.search(self,searchPeer(query.peer),query.q,query.offset_id,query.add_offset,query.limit,
                        searchPeer(query.from_id),query.min_date,query.max_date,query.filter,false,false,query.saved_reaction==null ? java.util.Collections.emptyList() : query.saved_reaction);
            } else {
                TLRPC.TL_messages_searchGlobal query=(TLRPC.TL_messages_searchGlobal)request;
                result=query.broadcasts_only ? new TLRPC.TL_messages_messagesSlice() : store.search(self,0,query.q,query.offset_id,0,query.limit,
                        0,query.min_date,query.max_date,query.filter,query.groups_only,query.users_only);
            }
            java.util.Set<Long> peers=new java.util.HashSet<>();
            for(TLRPC.Message message:result.messages) peers.add(message.dialog_id);
            for(long peer:peers) {
                if(peer<0) { result.chats.add(store.roomChat(peer,self)); result.users.addAll(store.roomUsers(peer,self)); }
                else result.users.add(store.user(peer,self));
            }
            result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory query = (TLRPC.TL_messages_getHistory) request;
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            long peer=query.peer.chat_id!=0 ? -query.peer.chat_id : query.peer.user_id;
            result.messages.addAll(store.history(peer, self, query.offset_id, query.limit));
            if(peer<0) { result.chats.add(store.roomChat(peer,self)); result.users.addAll(store.roomUsers(peer,self)); } else result.users.add(store.user(peer, self)); result.users.add(ownUser()); return result;
        }
        if(request instanceof TLRPC.TL_messages_getMessagesReactions) {
            TLRPC.TL_messages_getMessagesReactions query=(TLRPC.TL_messages_getMessagesReactions)request;
            return reactionUpdates(query.peer.chat_id!=0 ? -query.peer.chat_id : query.peer.user_id,query.id);
        }
        if(request instanceof TLRPC.TL_messages_getMessageReactionsList) {
            TLRPC.TL_messages_getMessageReactionsList query=(TLRPC.TL_messages_getMessageReactionsList)request;
            long peer=query.peer.chat_id!=0 ? -query.peer.chat_id : query.peer.user_id;
            if(store.getMessage(query.id,self).dialog_id!=peer) throw new IllegalArgumentException("Wrong conversation");
            TLRPC.TL_messages_messageReactionsList result=new TLRPC.TL_messages_messageReactionsList();
            int offset=query.offset==null || query.offset.isEmpty() ? 0 : Integer.parseInt(query.offset);
            if(offset<0) throw new IllegalArgumentException("Invalid offset");
            java.util.ArrayList<TLRPC.MessagePeerReaction> filtered=new java.util.ArrayList<>();
            for(TLRPC.MessagePeerReaction reaction:store.reactions.snapshot(query.id,self).recent_reactions)
                if(query.reaction==null || query.reaction instanceof TLRPC.TL_reactionEmpty || query.reaction instanceof TLRPC.TL_reactionEmoji
                    && ((TLRPC.TL_reactionEmoji)query.reaction).emoticon.equals(((TLRPC.TL_reactionEmoji)reaction.reaction).emoticon)) filtered.add(reaction);
            result.count=filtered.size(); int end=Math.min(filtered.size(),offset+Math.min(Math.max(query.limit,1),100));
            for(int i=offset;i<end;i++) result.reactions.add(filtered.get(i));
            if(end<filtered.size()) { result.flags=1; result.next_offset=Integer.toString(end); }
            java.util.Set<Long> users=new java.util.LinkedHashSet<>(); for(TLRPC.MessagePeerReaction reaction:result.reactions) users.add(reaction.peer_id.user_id);
            for(long user:users) result.users.add(store.user(user,self)); return result;
        }
        if(request instanceof TLRPC.TL_messages_getDefaultTagReactions) return store.savedTags.defaults(self);
        if(request instanceof TLRPC.TL_messages_getSavedReactionTags) {
            TLRPC.TL_messages_getSavedReactionTags query=(TLRPC.TL_messages_getSavedReactionTags)request;
            if(query.peer!=null && searchPeer(query.peer)!=self) return new TLRPC.TL_messages_savedReactionsTags();
            savedTagColors=store.savedTags.colors();
            return store.savedTags.list(self);
        }
        if(request instanceof TLRPC.TL_messages_updateSavedReactionTag) {
            TLRPC.TL_messages_updateSavedReactionTag rename=(TLRPC.TL_messages_updateSavedReactionTag)request;
            store.savedTags.rename(rename.reaction,(rename.flags & 1)!=0 ? rename.title : null);
            return new TLRPC.TL_boolTrue();
        }
        if(request instanceof TLRPC.TL_messages_sendVote) {
            TLRPC.TL_messages_sendVote vote=(TLRPC.TL_messages_sendVote)request;
            long peer=MonogramPolls.dialog(vote.peer);
            return handle(MonogramPolls.vote(vote,store.getMessage(vote.msg_id,self),self));
        }
        if(request instanceof TLRPC.TL_messages_getPollResults) {
            TLRPC.TL_messages_getPollResults query=(TLRPC.TL_messages_getPollResults)request;long peer=MonogramPolls.dialog(query.peer);
            TLRPC.Message message=store.getMessage(query.msg_id,self);MonogramPolls.require(message,peer);
            return reactionUpdates(peer,java.util.Collections.singleton(query.msg_id));
        }
        if(request instanceof TLRPC.TL_messages_getPollVotes) {
            TLRPC.TL_messages_getPollVotes query=(TLRPC.TL_messages_getPollVotes)request;
            return MonogramPolls.voters(query,store.getMessage(query.id,self),store,self);
        }
        if(request instanceof TLRPC.TL_messages_sendReaction) {
            TLRPC.TL_messages_sendReaction send=(TLRPC.TL_messages_sendReaction)request;
            if(send.peer==null || send.peer.channel_id!=0) throw new IllegalArgumentException("Unsupported conversation");
            long peer=send.peer.chat_id!=0 ? -send.peer.chat_id : send.peer.user_id;
            if(peer==self) {
                store.savedTags.set(send.msg_id,self,send.reaction);
                Updates changed=reactionUpdates(peer,java.util.Collections.singleton(send.msg_id));
                changed.updates.add(new TL_update.TL_updateSavedReactionTags());return changed;
            }
            String target=store.reactions.target(send.msg_id,peer,self);
            java.util.List<String> emojis=new java.util.ArrayList<>();
            for(TLRPC.Reaction reaction:send.reaction) {
                if(!(reaction instanceof TLRPC.TL_reactionEmoji) || !MonogramReactions.isEmoji(((TLRPC.TL_reactionEmoji)reaction).emoticon)) throw new IllegalArgumentException("Choose a standard emoji");
                emojis.add(((TLRPC.TL_reactionEmoji)reaction).emoticon);
            }
            com.negzero.monogram.xmpp.MessageReactions reactions=new com.negzero.monogram.xmpp.MessageReactions(target,emojis);
            XmppMessage.Encryption mode=reactionEncryption(send.msg_id,peer);
            if(mode==XmppMessage.Encryption.LEGACY_OMEMO) {
                if(!plainReactionsAllowed(peer)) throw new ReactionConsentRequired();
                mode=XmppMessage.Encryption.PLAIN;
            }
            XmppSession expected=session();
            if(peer<0) {
                if(mode!=XmppMessage.Encryption.PLAIN) throw new IllegalArgumentException("Encrypted room reactions are not supported by this OMEMO version");
                if(!store.room(peer).autojoin) throw new IllegalArgumentException("Join the room first");
                store.reactions.save(expected.rooms.sendReactions(store.jid(peer),reactions),self);
            } else {
                String wire=expected.sendReactions(store.jid(peer),reactions,mode);
                store.reactions.save(XmppMessage.sentReactions(expected.connection().getUser(),store.jid(peer),wire,reactions,mode),self);
            }
            return reactionUpdates(peer,java.util.Collections.singleton(send.msg_id));
        }
        if (request instanceof TLRPC.TL_messages_getMessageEditData) {
            TLRPC.TL_messages_getMessageEditData query = (TLRPC.TL_messages_getMessageEditData) request;
            if (query.peer == null || query.peer.user_id<=0 && query.peer.chat_id<=0) throw new IllegalArgumentException("Conversation required");
            MonogramStore.EditTarget target=editTarget(query.id, query.peer.chat_id>0 ? -query.peer.chat_id : query.peer.user_id);
            TLRPC.TL_messages_messageEditData result=new TLRPC.TL_messages_messageEditData(); result.caption=target.sharing!=null;
            return result;
        }
        if (request instanceof TLRPC.TL_messages_editMessage && ((TLRPC.TL_messages_editMessage)request).schedule_date!=0) {
            TLRPC.TL_messages_editMessage edit=(TLRPC.TL_messages_editMessage)request;
            long peer=scheduledPeer(edit.peer);
            if(edit.media!=null || edit.rich_message!=null || edit.quick_reply_shortcut_id!=0)
                throw new UnsupportedOperationException("This scheduled message cannot be edited here");
            MonogramScheduledMessages.Entry previous=null;
            for(MonogramScheduledMessages.Entry entry:MonogramScheduledMessages.list(account,accountJid(),accountEpoch,peer))
                if(entry.id==edit.id) { previous=entry;break; }
            if(previous==null) throw new IllegalArgumentException("Scheduled message is no longer available");
            String body=(edit.flags&2048)!=0 ? edit.message : previous.body;
            ArrayList<TLRPC.MessageEntity> entities=(edit.flags&8)!=0 ? edit.entities : previous.entities;
            int repeat=(edit.flags&TLObject.FLAG_18)!=0 ? edit.schedule_repeat_period : previous.repeatPeriod;
            MonogramScheduledMessages.Entry replacement=new MonogramScheduledMessages.Entry(previous.id,previous.localId,previous.peer,previous.destination,body,
                    entities,previous.replyTo,previous.encryption,previous.manualEncryption,edit.no_webpage,previous.silent,
                    previous.createdAt,edit.schedule_date*1000L,repeat,previous.attempts,previous.payload);
            if(!MonogramScheduledMessages.edit(account,accountJid(),accountEpoch,replacement))
                throw new IllegalStateException("Scheduled message is already being sent");
            armScheduledWake(); Updates result=new Updates(); result.date=now();
            TL_update.TL_updateEditMessage update=new TL_update.TL_updateEditMessage(); update.message=MonogramScheduledMessages.project(replacement); result.updates.add(update);
            return result;
        }
        if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage edit = (TLRPC.TL_messages_editMessage) request;
            if(edit.media instanceof TLRPC.TL_inputMediaPoll) MonogramPolls.close(edit,store.getMessage(edit.id,self),self);
            if (edit.peer == null || edit.peer.user_id == 0 && edit.peer.chat_id == 0 || edit.media != null || edit.schedule_date != 0 || edit.quick_reply_shortcut_id != 0
                    || (edit.flags & 2048) == 0 && edit.rich_message==null) throw new UnsupportedOperationException();
            long peer = edit.peer.chat_id>0 ? -edit.peer.chat_id : edit.peer.user_id;
            MonogramStore.EditTarget target = editTarget(edit.id, peer);
            com.negzero.monogram.xmpp.InlineEmoji inline=target.sharing==null ? MonogramEmojis.fromEntities(account,edit.message,edit.entities) : null;
            MessageMarkup markup = edit.rich_message!=null ? new MessageMarkup(MonogramArticles.markdown(edit.rich_message),java.util.Collections.emptyList()).asMarkdown()
                    : MonogramMentions.fromEntities(store,peer,edit.message,edit.entities);
            markup=inline==null ? markup.styled() : markup.preservingBody();
            if(edit.rich_message!=null) warnArticleFallback();
            XmppMessage.Encryption mode = target.sharing!=null ? target.encryption
                    : target.encryption==XmppMessage.Encryption.PLAIN ? encryption(peer) : target.encryption;
            int saved;
            if(peer<0) {
                com.negzero.monogram.xmpp.XmppRoomMessage message=target.sharing==null
                        ? session().rooms.send(store.jid(peer),markup.body,mode!=XmppMessage.Encryption.PLAIN,target.reply,target.correction,excludedRoomRecipients(peer),automaticRoomRecipients(peer),markup,inline)
                        : session().rooms.correctFile(store.jid(peer),target.sharing,markup.body,target.reply,target.correction,excludedRoomRecipients(peer),automaticRoomRecipients(peer),markup);
                saved=store.saveRoom(message,self); if(message.encrypted) reportSkippedRecipients(peer,message);
            }
            else {
                XmppSession expected=session(); String fingerprint=expected.senderFingerprint(mode);
                String stanza=target.sharing==null ? expected.correct(store.jid(peer),markup.body,mode,target.reply,target.correction,markup,inline)
                        : expected.correctFile(store.jid(peer),target.sharing,markup.body,mode,target.reply,target.correction,markup);
                XmppMessage message=target.sharing==null ? XmppMessage.sent(expected.connection().getUser(),store.jid(peer),stanza,markup.body,mode,target.reply,target.correction)
                        : XmppMessage.sentFile(expected.connection().getUser(),store.jid(peer),stanza,target.sharing,markup.body,target.reply,target.correction);
                saved=store.save(message.withMarkup(markup).withInlineEmoji(inline).withSenderFingerprint(fingerprint));
            }
            MonogramStore.CorrectionChanges corrections = store.reconcileCorrections(peer, java.util.Collections.singleton(saved));
            refreshReplies(peer, corrections.edited);
            corrections.edited.remove(edit.id); publishCorrections(peer, correctionUpdates(corrections, saved));
            Updates result = new Updates(); result.date = now();
            TL_update.TL_updateEditMessage update = new TL_update.TL_updateEditMessage(); update.message = store.getMessage(edit.id, self); result.updates.add(update);
            if(update.message.media instanceof TLRPC.TL_messageMediaPoll) result.updates.add(MonogramPolls.update(update.message));
            if(peer<0) { result.users.addAll(store.roomUsers(peer,self)); result.chats.add(store.roomChat(peer,self)); }
            else { result.users.add(store.user(peer, self)); result.users.add(ownUser()); }
            return result;
        }
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
            if(send.reply_to instanceof TLRPC.TL_inputReplyToStory) {
                String prefix=stories().replyQuote(send.reply_to.story_id,send.peer.user_id);
                send.message=prefix+(send.message==null?"":send.message);
                for(TLRPC.MessageEntity entity:send.entities) entity.offset+=prefix.length();
                send.reply_to=null;send.flags&=~1;
            }
            com.negzero.monogram.xmpp.InlineEmoji inline=MonogramEmojis.fromEntities(account,send.message,send.entities);
            MessageMarkup markup = send.rich_message!=null ? new MessageMarkup(MonogramArticles.markdown(send.rich_message),java.util.Collections.emptyList()).asMarkdown()
                    : MonogramMentions.fromEntities(store,send.peer.chat_id>0 ? -send.peer.chat_id : send.peer.user_id,send.message,send.entities);
            markup=inline==null ? markup.styled() : markup.preservingBody();
            if(send.rich_message!=null) warnArticleFallback();
            if (send.peer.chat_id!=0) {
                if (send.schedule_date!=0) throw new UnsupportedOperationException();
                long dialog=-send.peer.chat_id;
                if(!store.room(dialog).autojoin) throw new IllegalStateException("Room has been left");
                com.negzero.monogram.xmpp.XmppRoomMessage message=session().rooms.send(store.jid(dialog),markup.body,encrypted(dialog),outgoingReply(send.reply_to,dialog,encrypted(dialog)),null,excludedRoomRecipients(dialog),automaticRoomRecipients(dialog),markup,inline);
                int id=store.saveRoom(message,self); refreshReplies(dialog); if(message.encrypted) reportSkippedRecipients(dialog,message);
                TLRPC.TL_updateShortSentMessage result=new TLRPC.TL_updateShortSentMessage(); result.id=id; result.date=(int)(message.timestamp/1000); result.out=true;
                TLRPC.Message projected=store.getMessage(id,self); result.message=projected.message; result.entities=projected.entities;
                if(projected.media instanceof TLRPC.TL_messageMediaPoll) { result.media=projected.media;result.flags|=512; }
                result.pts_count=1; result.pts=MessagesStorage.getInstance(account).getLastPtsValue()+1; return result;
            }
            if (send.schedule_date != 0 || send.peer.user_id == 0) throw new UnsupportedOperationException();
            long peer = send.peer.user_id; XmppMessage.Encryption mode = encryption(peer);
            ReplyReference reply = outgoingReply(send.reply_to, peer, mode!=XmppMessage.Encryption.PLAIN);
            XmppSession expected=session(); String fingerprint=expected.senderFingerprint(mode);
            String stanza = expected.send(store.jid(peer), markup.body, mode, reply, markup,inline);
            XmppMessage message = XmppMessage.sent(expected.connection().getUser(), store.jid(peer), stanza, markup.body, mode, reply,null).withMarkup(markup).withInlineEmoji(inline).withSenderFingerprint(fingerprint);
            int date = (int) (message.timestamp / 1000); int id = store.save(message);
            refreshReplies(peer);
            TLRPC.TL_updateShortSentMessage result = new TLRPC.TL_updateShortSentMessage();
            result.id = id; result.date = date; result.out = true; result.pts_count = 1;
            TLRPC.Message projected=store.getMessage(id,self); result.message=projected.message; result.entities=projected.entities;
                if(projected.media instanceof TLRPC.TL_messageMediaPoll) { result.media=projected.media;result.flags|=512; }
            result.pts = MessagesStorage.getInstance(account).getLastPtsValue() + 1;
            return result;
        }
        if (request instanceof TL_account.updateStatus) {
            boolean offline = ((TL_account.updateStatus) request).offline;
            session().connection().sendStanza(new org.jivesoftware.smack.packet.Presence(offline ? org.jivesoftware.smack.packet.Presence.Type.unavailable : org.jivesoftware.smack.packet.Presence.Type.available));
            return new TLRPC.TL_boolTrue();
        }
        throw new UnsupportedOperationException(request.getClass().getSimpleName());
    }
}
